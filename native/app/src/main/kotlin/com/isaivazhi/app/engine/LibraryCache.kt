package com.isaivazhi.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Caches the scanned library to `<dataDir>/song_library.json` so subsequent
 * launches skip the MediaStore + filesystem walk. Same on-disk shape as the
 * Capacitor app's library cache; the Kotlin scanner produces identical
 * Song POJOs.
 *
 * Freshness: cache is treated as valid for `MAX_AGE_MS` (default 6 hours).
 * After that, a fresh scan runs and the cache is rewritten. Callers can
 * force a rescan by passing `force = true` to `loadOrScan`.
 */
object LibraryCache {

    private const val CACHE_FILE = "song_library.json"
    private const val MAX_AGE_MS = 6L * 60L * 60L * 1000L  // 6 hours

    suspend fun loadOrScan(ctx: Context, force: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        if (!force) {
            val cached = readIfFresh(ctx)
            if (cached != null) {
                android.util.Log.i("LibraryCache", "Loaded ${cached.size} songs from cache")
                return@withContext cached
            }
        }
        val scanned = LibraryScanner.scan(ctx)
        runCatching { write(ctx, scanned) }
            .onFailure { android.util.Log.w("LibraryCache", "save failed: ${it.message}") }
        scanned
    }

    private fun cacheFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, CACHE_FILE)
    }

    private fun readIfFresh(ctx: Context): List<Song>? {
        val f = cacheFile(ctx)
        if (!f.exists() || f.length() <= 0) return null
        val ageMs = System.currentTimeMillis() - f.lastModified()
        if (ageMs > MAX_AGE_MS) {
            android.util.Log.i("LibraryCache", "cache is ${ageMs / 60_000} min old, refreshing")
            return null
        }
        return runCatching {
            val json = JSONObject(f.readText(Charsets.UTF_8))
            val arr = json.optJSONArray("songs") ?: return null
            val out = ArrayList<Song>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out += Song(
                    id = i,
                    filename = o.optString("filename", ""),
                    title = o.optString("title", ""),
                    artist = o.optString("artist", ""),
                    album = o.optString("album", ""),
                    filePath = o.optString("filePath", "").ifEmpty { null },
                    artPath = o.optString("artPath", "").ifEmpty { null },
                    dateModified = o.optLong("dateModified", 0L),
                    contentHash = o.optString("contentHash", "").ifEmpty { null },
                    hasEmbedding = o.optBoolean("hasEmbedding", false),
                    embeddingIndex = if (o.has("embeddingIndex") && !o.isNull("embeddingIndex"))
                        o.optInt("embeddingIndex") else null,
                    disliked = o.optBoolean("disliked", false),
                )
            }
            out
        }.getOrNull()
    }

    private fun write(ctx: Context, songs: List<Song>) {
        val arr = JSONArray()
        for (s in songs) {
            val o = JSONObject()
            o.put("filename", s.filename)
            o.put("title", s.title)
            o.put("artist", s.artist)
            o.put("album", s.album)
            o.put("filePath", s.filePath ?: "")
            o.put("artPath", s.artPath ?: "")
            o.put("dateModified", s.dateModified)
            o.put("contentHash", s.contentHash ?: "")
            o.put("hasEmbedding", s.hasEmbedding)
            if (s.embeddingIndex != null) o.put("embeddingIndex", s.embeddingIndex) else o.put("embeddingIndex", JSONObject.NULL)
            o.put("disliked", s.disliked)
            arr.put(o)
        }
        val root = JSONObject().apply {
            put("songs", arr)
            put("savedAt", System.currentTimeMillis())
        }
        val tmp = File(cacheFile(ctx).parentFile, "$CACHE_FILE.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        val target = cacheFile(ctx)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    /** Forces a rescan + cache rewrite. Useful after the user deletes files. */
    suspend fun invalidate(ctx: Context): List<Song> = loadOrScan(ctx, force = true)
}
