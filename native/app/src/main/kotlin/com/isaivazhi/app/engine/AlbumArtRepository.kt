package com.isaivazhi.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.isaivazhi.app.AlbumArtHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * Coroutine-friendly loader on top of the existing Java AlbumArtHelper.
 *
 * Two-tier cache:
 *   1. In-memory LRU keyed by filePath — used by every visible row in
 *      LazyColumn so scrolling doesn't re-decode the same bitmap.
 *   2. On-disk JPG at `<cacheDir>/albumart/<hash>.jpg` — populated lazily
 *      by `AlbumArtHelper.extractAndCacheArt` the first time a song is
 *      asked for. Subsequent app launches reuse the disk cache, so cold
 *      starts don't re-extract anything.
 *
 * Per-path Mutex prevents multiple LazyColumn rows from racing the same
 * decode when a song first scrolls into view.
 */
object AlbumArtRepository {

    // 64 thumbnails × ~240 KB each ≈ 15 MB cap. Acceptable for a list UI.
    private const val MEM_CACHE_ENTRIES = 64

    private val memCache = object : LruCache<String, Bitmap>(MEM_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    // Coalesces concurrent loads for the same filePath onto a single decode.
    private val locks = mutableMapOf<String, Mutex>()

    // Cap concurrent MediaMetadataRetriever decodes so heavy scrolling doesn't
    // saturate IO and starve other suspending work (notably the recommender's
    // sqlite-vec queries on tap-to-play). 4 in-flight matches ArtPrefetch.
    private val decodeSemaphore = Semaphore(4)

    // Songs that don't have embedded art — remember them across the session
    // so scrolling past them again doesn't re-instantiate MediaMetadataRetriever
    // for nothing. The logcat spam of `getEmbeddedPicture failed` in push #36
    // logs came from re-retries on .flac/.opus files without embedded artwork.
    private val negativeCache: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * Synchronous LRU lookup — returns the cached bitmap if present,
     * null on miss. Safe to call from the composition thread; LruCache.get
     * is internally synchronized and O(1). Push #39: ArtThumbnail seeds
     * its initial state from this so cache hits render on the first frame
     * (no async LaunchedEffect round-trip + null-placeholder flicker).
     */
    fun getCachedBitmap(filePath: String?, sampleSize: Int = 4): Bitmap? {
        if (filePath.isNullOrEmpty()) return null
        return memCache.get("$filePath#s$sampleSize")
    }

    /**
     * Returns the album art bitmap for `filePath`, or null if extraction
     * fails or the audio file has no embedded picture. `sampleSize` controls
     * the BitmapFactory inSampleSize — 4 is good for ~240 px row thumbs;
     * 1 for full-screen player art.
     */
    suspend fun load(ctx: Context, filePath: String, sampleSize: Int = 4): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "$filePath#s$sampleSize"
            memCache.get(key)?.let { return@withContext it }
            // Skip files that we've already discovered have no embedded art
            // this session — avoids the retriever spam in logcat.
            if (filePath in negativeCache) return@withContext null

            val mutex = synchronized(locks) {
                locks.getOrPut(filePath) { Mutex() }
            }
            mutex.withLock {
                memCache.get(key)?.let { return@withContext it }
                if (filePath in negativeCache) return@withContext null

                val cached = AlbumArtHelper.cachedArtFile(ctx, filePath)
                val jpgPath: String = if (cached.exists() && cached.length() > 0) {
                    cached.absolutePath
                } else {
                    // Throttle the MediaMetadataRetriever extraction step; the
                    // on-disk decode below is cheap and doesn't need the cap.
                    val extracted = decodeSemaphore.withPermit {
                        AlbumArtHelper.extractAndCacheArt(ctx, filePath)
                    }
                    if (extracted == null) {
                        negativeCache.add(filePath)
                        return@withContext null
                    }
                    extracted
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize.coerceAtLeast(1)
                }
                val bmp = runCatching { BitmapFactory.decodeFile(jpgPath, opts) }.getOrNull()
                if (bmp == null) {
                    negativeCache.add(filePath)
                    return@withContext null
                }
                memCache.put(key, bmp)
                bmp
            }
        }

    /** Drop everything from memory (call when low-memory or on logout). */
    fun trimMemory() {
        memCache.evictAll()
    }

    /** Disk cache size — useful for a Settings "Clear art cache" button. */
    fun diskCacheBytes(ctx: Context): Long {
        val dir = AlbumArtHelper.getArtCacheDir(ctx)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clearDiskCache(ctx: Context) {
        val dir = AlbumArtHelper.getArtCacheDir(ctx)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }
}
