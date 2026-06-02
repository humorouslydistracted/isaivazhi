package com.isaivazhi.app.engine

import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Replaces MediaScanHelper.java (which returned Capacitor JSObjects) with a
 * coroutine-friendly Kotlin scanner. Returns a typed List<Song>.
 *
 * Same logic as the Java version: MediaStore query first, filesystem
 * fallback for paths MediaStore misses (e.g., on devices with broken
 * MediaStore indexing of certain dirs). Dotfile / .trashed- entries are
 * filtered out as they were in #1 today.
 */
object LibraryScanner {

    private const val TAG = "LibraryScanner"

    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma")

    private val FALLBACK_DIRS = listOf(
        "/storage/emulated/0/songs_downloaded",
        "/storage/emulated/0/Music",
        "/storage/emulated/0/Download",
    )

    suspend fun scan(ctx: Context): List<Song> = withContext(Dispatchers.IO) {
        val seenPaths = mutableSetOf<String>()
        val songs = mutableListOf<Song>()

        // MediaStore primary scan.
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DURATION,
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.DISPLAY_NAME} ASC"
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            ctx.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { c ->
                ingestCursor(c, seenPaths, songs)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore scan failed: ${t.message}")
        }

        // Filesystem fallback: catches files that MediaStore missed.
        for (dirPath in FALLBACK_DIRS) {
            val dir = File(dirPath)
            if (dir.isDirectory) collectRecursive(dir, seenPaths, songs)
        }

        val withIds = songs.mapIndexed { i, s -> s.copy(id = i) }
        Log.i(TAG, "Library scan: ${withIds.size} songs")
        enrichDurations(ctx, withIds)
    }

    /**
     * Fills [Song.durationMs] from MediaStore (bulk) and, for remaining gaps,
     * [MediaMetadataRetriever] on the file path (filesystem-only entries).
     */
    fun enrichDurations(ctx: Context, songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return songs
        val missing = songs.count { it.durationMs <= 0L && !it.filePath.isNullOrBlank() }
        if (missing == 0) return songs
        val (byPath, byFilename) = loadMediaStoreDurations(ctx)
        var filled = 0
        val out = songs.map { song ->
            if (song.durationMs > 0L) return@map song
            var dur = song.filePath?.let { byPath[it] } ?: 0L
            if (dur <= 0L) dur = byFilename[song.filename] ?: 0L
            if (dur <= 0L && !song.filePath.isNullOrBlank()) {
                dur = readDurationFromFile(song.filePath)
            }
            if (dur > 0L) {
                filled++
                song.copy(durationMs = dur)
            } else {
                song
            }
        }
        if (filled > 0) {
            Log.i(TAG, "enrichDurations: filled $filled / $missing missing durations")
        }
        return out
    }

    private fun loadMediaStoreDurations(ctx: Context): Pair<Map<String, Long>, Map<String, Long>> {
        val byPath = HashMap<String, Long>()
        val byFilename = HashMap<String, Long>()
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
            )
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            ctx.contentResolver.query(
                uri,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null,
            )?.use { c ->
                val idxName = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val idxData = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val idxDur = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    if (idxDur < 0) continue
                    val dur = c.getLong(idxDur)
                    if (dur <= 0L) continue
                    if (idxData >= 0) {
                        c.getString(idxData)?.takeIf { it.isNotBlank() }?.let { byPath[it] = dur }
                    }
                    if (idxName >= 0) {
                        c.getString(idxName)?.takeIf { it.isNotBlank() }?.let { name ->
                            byFilename.putIfAbsent(name, dur)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadMediaStoreDurations failed: ${t.message}")
        }
        return byPath to byFilename
    }

    private fun readDurationFromFile(path: String): Long {
        if (path.isBlank()) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** MediaStore date columns are seconds; filesystem uses milliseconds. */
    private fun normalizeToEpochMs(epoch: Long): Long =
        when {
            epoch <= 0L -> 0L
            epoch < 1_000_000_000_000L -> epoch * 1000L
            else -> epoch
        }

    private fun ingestCursor(c: Cursor, seen: MutableSet<String>, out: MutableList<Song>) {
        val idxName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val idxTitle = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
        val idxArtist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
        val idxAlbum = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
        val idxData = c.getColumnIndex(MediaStore.Audio.Media.DATA)
        val idxModified = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
        val idxDuration = c.getColumnIndex(MediaStore.Audio.Media.DURATION)

        while (c.moveToNext()) {
            val filename = c.getString(idxName) ?: continue
            if (filename.startsWith(".")) continue            // Android trash + dotfiles
            val path = if (idxData >= 0) c.getString(idxData) else null
            if (path != null && !seen.add(path)) continue
            // Push #58: MediaStore can return stale rows for files deleted
            // by another app (file manager, gallery) until its own scanner
            // re-walks. Filter on disk existence so externally-deleted
            // songs don't keep showing up in the library list. Skip the
            // check when path is null (rare, but the row may still be
            // playable via content URI in that case).
            if (path != null && !File(path).exists()) continue
            val title = (if (idxTitle >= 0) c.getString(idxTitle) else null)
                ?.takeIf { it.isNotBlank() } ?: filename.substringBeforeLast('.')
            out += Song(
                id = out.size,
                filename = filename,
                title = title,
                artist = (if (idxArtist >= 0) c.getString(idxArtist) else null) ?: "",
                album = (if (idxAlbum >= 0) c.getString(idxAlbum) else null) ?: "",
                filePath = path,
                dateModified = if (idxModified >= 0) normalizeToEpochMs(c.getLong(idxModified)) else 0L,
                durationMs = if (idxDuration >= 0) c.getLong(idxDuration).coerceAtLeast(0L) else 0L,
            )
        }
    }

    private fun collectRecursive(dir: File, seen: MutableSet<String>, out: MutableList<Song>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                collectRecursive(f, seen, out)
                continue
            }
            val ext = f.extension.lowercase()
            if (ext !in AUDIO_EXTS) continue
            val path = f.absolutePath
            if (!seen.add(path)) continue
            out += Song(
                id = out.size,
                filename = f.name,
                title = f.nameWithoutExtension,
                artist = "",
                album = "",
                filePath = path,
                dateModified = f.lastModified(),
            )
        }
    }
}
