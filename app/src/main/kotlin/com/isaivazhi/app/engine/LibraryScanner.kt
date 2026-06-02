package com.isaivazhi.app.engine

import android.content.Context
import android.database.Cursor
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

        // Stable ordering by display name; preserve cursor order otherwise.
        Log.i(TAG, "Library scan: ${songs.size} songs")
        songs.mapIndexed { i, s -> s.copy(id = i) }
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
