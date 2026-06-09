package com.isaivazhi.app.engine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

data class FolderEntry(
    val path: String,
    /** Last path segment shown in the UI, e.g. "WhatsApp Audio". */
    val displayName: String,
    /** Number of songs whose parent directory matches this path. */
    val songCount: Int,
    val isExcluded: Boolean,
    /** True when the user manually added this folder via the folder picker. */
    val isManual: Boolean,
)

/**
 * Manages which source directories are visible in the app.
 *
 * Two DataStore keys:
 *  - excluded_folder_paths_v1  : newline-separated absolute paths the user chose to hide
 *  - manual_folder_paths_v1    : newline-separated paths the user added manually via SAF picker
 *
 * Filtering is pure (stateless) — callers pass the current excluded set so the
 * logic can run on any coroutine dispatcher without holding stale state.
 *
 * Paths are normalized before persistence and matched with suffix fallback so
 * exclusions survive library rescans after app updates (MediaStore path drift).
 */
class FolderExclusionEngine(private val appContext: Context) {

    companion object {
        private const val SUFFIX_MATCH_MIN_SEGMENTS = 2
    }

    private val KEY_EXCLUDED = stringPreferencesKey("excluded_folder_paths_v1")
    private val KEY_MANUAL = stringPreferencesKey("manual_folder_paths_v1")

    // ---------------------------------------------------------------------------
    // Flows
    // ---------------------------------------------------------------------------

    val excludedPaths: Flow<Set<String>> = appContext.dataStoreLocal.data.map { prefs ->
        parsePathSet(prefs[KEY_EXCLUDED])
    }

    val manualPaths: Flow<Set<String>> = appContext.dataStoreLocal.data.map { prefs ->
        parsePathSet(prefs[KEY_MANUAL])
    }

    // ---------------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------------

    suspend fun setExcluded(folderPath: String, excluded: Boolean) {
        val target = normalizeFolderPath(folderPath)
        if (target.isBlank()) return
        appContext.dataStoreLocal.edit { prefs ->
            val current = parsePathSet(prefs[KEY_EXCLUDED]).toMutableSet()
            if (excluded) {
                current.removeAll { matchesSameFolder(it, target) }
                current.add(target)
            } else {
                current.removeAll { matchesSameFolder(it, target) }
            }
            prefs[KEY_EXCLUDED] = current.joinToString("\n")
        }
    }

    suspend fun addManualFolder(path: String) {
        val normalized = normalizeFolderPath(path)
        if (normalized.isBlank()) return
        appContext.dataStoreLocal.edit { prefs ->
            val current = parsePathSet(prefs[KEY_MANUAL]).toMutableSet()
            current.add(normalized)
            prefs[KEY_MANUAL] = current.joinToString("\n")
        }
    }

    suspend fun removeManualFolder(path: String) {
        val normalized = normalizeFolderPath(path)
        appContext.dataStoreLocal.edit { prefs ->
            val current = parsePathSet(prefs[KEY_MANUAL]).toMutableSet()
            current.removeAll { matchesSameFolder(it, normalized) }
            prefs[KEY_MANUAL] = current.joinToString("\n")
            val excl = parsePathSet(prefs[KEY_EXCLUDED]).toMutableSet()
            excl.removeAll { matchesSameFolder(it, normalized) }
            prefs[KEY_EXCLUDED] = excl.joinToString("\n")
        }
    }

    /**
     * Remap stored excluded paths to the current library's parent directories.
     * Called after each library load/rescan so exclusions survive path drift
     * across Play Store updates (e.g. /storage/emulated/0/... vs /sdcard/...).
     */
    suspend fun reconcileExcludedPaths(currentParentPaths: Set<String>) {
        val stored = excludedPathsSnapshot()
        if (stored.isEmpty()) return
        val reconciled = mutableSetOf<String>()
        var changed = false
        for (old in stored) {
            val match = findMatchingCurrentPath(old, currentParentPaths)
            when {
                match != null -> {
                    val normalized = normalizeFolderPath(match)
                    reconciled.add(normalized)
                    if (normalizeFolderPath(old) != normalized) changed = true
                }
                else -> {
                    val normalized = normalizeFolderPath(old)
                    reconciled.add(normalized)
                    if (old != normalized) changed = true
                }
            }
        }
        val deduped = reconciled.toSet()
        val storedNormalized = stored.map { normalizeFolderPath(it) }.toSet()
        if (changed || deduped != storedNormalized) {
            persistExcluded(deduped)
        }
    }

    // ---------------------------------------------------------------------------
    // Pure helpers (no I/O, safe to call on any thread)
    // ---------------------------------------------------------------------------

    /**
     * Filters [songs] by removing any whose parent directory is in [excluded].
     */
    fun filter(songs: List<Song>, excluded: Set<String>): List<Song> {
        if (excluded.isEmpty()) return songs
        return songs.filter { song ->
            val parent = song.filePath?.let { File(it).parent } ?: return@filter true
            !isPathExcluded(parent, excluded)
        }
    }

    /**
     * Builds the merged folder list for the Folders screen.
     * Auto-discovered folders come from the song library; manual paths are
     * overlaid so a user-added folder appears even if the rescan hasn't run yet.
     * Orphan excluded paths (stored but not in current scan) are also shown.
     */
    fun allFolders(
        autoSongs: List<Song>,
        manualPaths: Set<String>,
        excluded: Set<String>,
    ): List<FolderEntry> {
        val counts = mutableMapOf<String, Int>()
        for (song in autoSongs) {
            val parent = song.filePath?.let { File(it).parent } ?: continue
            counts[parent] = (counts[parent] ?: 0) + 1
        }

        val result = mutableMapOf<String, FolderEntry>()

        for ((path, count) in counts) {
            result[path] = FolderEntry(
                path = path,
                displayName = File(path).name.ifBlank { path },
                songCount = count,
                isExcluded = isPathExcluded(path, excluded),
                isManual = manualPaths.any { matchesSameFolder(it, path) },
            )
        }

        for (path in manualPaths) {
            val existing = result.entries.firstOrNull { matchesSameFolder(it.key, path) }
            if (existing != null) {
                result[existing.key] = existing.value.copy(isManual = true)
            } else {
                result[path] = FolderEntry(
                    path = path,
                    displayName = File(path).name.ifBlank { path },
                    songCount = 0,
                    isExcluded = isPathExcluded(path, excluded),
                    isManual = true,
                )
            }
        }

        for (path in excluded) {
            val represented = result.keys.any { matchesSameFolder(it, path) }
            if (!represented) {
                result[path] = FolderEntry(
                    path = path,
                    displayName = File(path).name.ifBlank { path },
                    songCount = 0,
                    isExcluded = true,
                    isManual = manualPaths.any { matchesSameFolder(it, path) },
                )
            }
        }

        return result.values.sortedBy { it.displayName.lowercase() }
    }

    fun folderCount(songs: List<Song>): Int =
        songs.mapNotNull { it.filePath?.let { p -> File(p).parent } }.toSet().size

    // ---------------------------------------------------------------------------
    // Path normalization + matching
    // ---------------------------------------------------------------------------

    fun normalizeFolderPath(path: String): String {
        val cleaned = path.trim().replace('\\', '/').trimEnd('/')
        if (cleaned.isBlank()) return cleaned
        return runCatching { File(cleaned).canonicalFile.absolutePath }
            .getOrElse { cleaned }
    }

    private fun pathSuffix(path: String, minSegments: Int = SUFFIX_MATCH_MIN_SEGMENTS): String? {
        val norm = normalizeFolderPath(path)
        val parts = norm.split('/').filter { it.isNotBlank() }
        if (parts.size < minSegments) return null
        return parts.takeLast(minSegments).joinToString("/")
    }

    private fun matchesSameFolder(a: String, b: String): Boolean {
        val normA = normalizeFolderPath(a)
        val normB = normalizeFolderPath(b)
        if (normA == normB) return true
        val suffixA = pathSuffix(normA) ?: return false
        val suffixB = pathSuffix(normB) ?: return false
        return suffixA == suffixB
    }

    private fun isPathExcluded(path: String, excluded: Set<String>): Boolean {
        if (excluded.isEmpty()) return false
        return excluded.any { matchesSameFolder(it, path) }
    }

    private fun findMatchingCurrentPath(stored: String, currentParents: Set<String>): String? {
        val normStored = normalizeFolderPath(stored)
        currentParents.firstOrNull { normalizeFolderPath(it) == normStored }?.let { return it }
        val suffix = pathSuffix(normStored) ?: return null
        val matches = currentParents.filter { pathSuffix(it) == suffix }
        return matches.singleOrNull() ?: matches.firstOrNull()
    }

    private fun parsePathSet(raw: String?): Set<String> =
        raw?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.map { normalizeFolderPath(it) }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    private suspend fun persistExcluded(paths: Set<String>) {
        appContext.dataStoreLocal.edit { prefs ->
            prefs[KEY_EXCLUDED] = paths.joinToString("\n")
        }
    }

    // ---------------------------------------------------------------------------
    // Snapshot read (for use outside Compose)
    // ---------------------------------------------------------------------------

    suspend fun excludedPathsSnapshot(): Set<String> =
        parsePathSet(appContext.dataStoreLocal.data.first()[KEY_EXCLUDED])

    suspend fun manualPathsSnapshot(): Set<String> =
        parsePathSet(appContext.dataStoreLocal.data.first()[KEY_MANUAL])
}
