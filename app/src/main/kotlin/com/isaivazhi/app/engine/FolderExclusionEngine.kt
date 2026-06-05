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
 */
class FolderExclusionEngine(private val appContext: Context) {

    private val KEY_EXCLUDED = stringPreferencesKey("excluded_folder_paths_v1")
    private val KEY_MANUAL = stringPreferencesKey("manual_folder_paths_v1")

    // ---------------------------------------------------------------------------
    // Flows
    // ---------------------------------------------------------------------------

    val excludedPaths: Flow<Set<String>> = appContext.dataStoreLocal.data.map { prefs ->
        prefs[KEY_EXCLUDED]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    val manualPaths: Flow<Set<String>> = appContext.dataStoreLocal.data.map { prefs ->
        prefs[KEY_MANUAL]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    // ---------------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------------

    suspend fun setExcluded(folderPath: String, excluded: Boolean) {
        appContext.dataStoreLocal.edit { prefs ->
            val current = prefs[KEY_EXCLUDED]?.split('\n')?.filter { it.isNotBlank() }
                ?.toMutableSet() ?: mutableSetOf()
            if (excluded) current.add(folderPath) else current.remove(folderPath)
            prefs[KEY_EXCLUDED] = current.joinToString("\n")
        }
    }

    suspend fun addManualFolder(path: String) {
        appContext.dataStoreLocal.edit { prefs ->
            val current = prefs[KEY_MANUAL]?.split('\n')?.filter { it.isNotBlank() }
                ?.toMutableSet() ?: mutableSetOf()
            current.add(path)
            prefs[KEY_MANUAL] = current.joinToString("\n")
        }
    }

    suspend fun removeManualFolder(path: String) {
        appContext.dataStoreLocal.edit { prefs ->
            val current = prefs[KEY_MANUAL]?.split('\n')?.filter { it.isNotBlank() }
                ?.toMutableSet() ?: mutableSetOf()
            current.remove(path)
            prefs[KEY_MANUAL] = current.joinToString("\n")
            // Also un-exclude it so re-adding later starts clean
            val excl = prefs[KEY_EXCLUDED]?.split('\n')?.filter { it.isNotBlank() }
                ?.toMutableSet() ?: mutableSetOf()
            excl.remove(path)
            prefs[KEY_EXCLUDED] = excl.joinToString("\n")
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
            parent !in excluded
        }
    }

    /**
     * Builds the merged folder list for the Folders screen.
     * Auto-discovered folders come from the song library; manual paths are
     * overlaid so a user-added folder appears even if the rescan hasn't run yet.
     */
    fun allFolders(
        autoSongs: List<Song>,
        manualPaths: Set<String>,
        excluded: Set<String>,
    ): List<FolderEntry> {
        // Count songs per parent directory
        val counts = mutableMapOf<String, Int>()
        for (song in autoSongs) {
            val parent = song.filePath?.let { File(it).parent } ?: continue
            counts[parent] = (counts[parent] ?: 0) + 1
        }

        val result = mutableMapOf<String, FolderEntry>()

        // Auto-discovered
        for ((path, count) in counts) {
            result[path] = FolderEntry(
                path = path,
                displayName = File(path).name.ifBlank { path },
                songCount = count,
                isExcluded = path in excluded,
                isManual = false,
            )
        }

        // Manual paths overlay (mark isManual; update count if songs found there)
        for (path in manualPaths) {
            val existing = result[path]
            if (existing != null) {
                result[path] = existing.copy(isManual = true)
            } else {
                // Folder was added manually but no songs scanned yet (e.g., first add before rescan)
                result[path] = FolderEntry(
                    path = path,
                    displayName = File(path).name.ifBlank { path },
                    songCount = 0,
                    isExcluded = path in excluded,
                    isManual = true,
                )
            }
        }

        return result.values.sortedBy { it.displayName.lowercase() }
    }

    /**
     * Total number of distinct source folders in [songs]. Used for the Settings
     * summary line before the Folders screen is opened.
     */
    fun folderCount(songs: List<Song>): Int =
        songs.mapNotNull { it.filePath?.let { p -> File(p).parent } }.toSet().size

    // ---------------------------------------------------------------------------
    // Snapshot read (for use outside Compose)
    // ---------------------------------------------------------------------------

    suspend fun excludedPathsSnapshot(): Set<String> =
        appContext.dataStoreLocal.data.first()[KEY_EXCLUDED]
            ?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    suspend fun manualPathsSnapshot(): Set<String> =
        appContext.dataStoreLocal.data.first()[KEY_MANUAL]
            ?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
}
