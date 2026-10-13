package com.isaivazhi.app.engine

import android.os.Handler
import android.os.Looper
import android.content.Context
import com.isaivazhi.app.EmbeddingDbManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject

/**
 * Coroutine-friendly façade over the existing Java EmbeddingDbManager
 * (Phase 1+2 work today). The manager itself runs every database call on a
 * dedicated HandlerThread; the suspend wrappers below adapt its Callback
 * style to coroutines so the UI layer can `await` it naturally.
 */
class EmbeddingDbFacade(appContext: Context) {

    private val appContext = appContext.applicationContext
    private val manager: EmbeddingDbManager = EmbeddingDbManager.get(this.appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Bugfix 2026-06-01h: in-memory vec cache. sqlite-vec uses mmap for its
    // vector index, and the kernel evicts those pages within 1-2s of the
    // Activity going to background (even though the foreground service keeps
    // the process alive). This causes every backgrounded nearestNeighbors
    // call to pay a ~30-60s cold page-fault cost.
    //
    // Solution: store all FloatArrays in the JVM heap. JVM heap is never
    // subject to OS mmap eviction — it lives for the process lifetime.
    // 2455 rows × 512 dims × 4 bytes ≈ 5 MB. Populated lazily on the first
    // getVecsByHashes call (which happens during process_start precompute,
    // in the foreground). All subsequent calls — including backgrounded
    // precomputes — are served from heap in <1ms.
    private val vecHeapCache = java.util.concurrent.ConcurrentHashMap<String, FloatArray>(4096)

    // filename → contentHash and hash → NnResult metadata. Populated by
    // prewarmFromLibrary() at startup so nearestNeighborsForFilename can
    // also be served from heap without a DB round-trip.
    private val filenameToHashCache = java.util.concurrent.ConcurrentHashMap<String, String>(4096)
    private val hashToMetaCache = java.util.concurrent.ConcurrentHashMap<String, HeapMeta>(4096)
    @Volatile var lastNearestNeighborSource: String = "none"
        private set

    data class HeapMeta(val filename: String, val filepath: String)

    data class HeapWarmResult(
        val success: Boolean,
        val count: Int = 0,
        val dim: Int = 0,
        val source: String = "none",
        val error: String? = null,
    )

    /** Call once after library loads. Populates filename→hash and hash→meta
     *  lookups so nearestNeighborsForFilename can bypass the DB. */
    fun prewarmFromLibrary(songs: List<Song>) {
        for (song in songs) {
            val hash = song.contentHash ?: continue
            filenameToHashCache[song.filename] = hash
            hashToMetaCache[hash] = HeapMeta(song.filename, song.filePath ?: song.filename)
        }
    }

    /**
     * Bugfix 2026-06-01i: load every embedding vector into [vecHeapCache] in
     * a single batched DB call. Without this, the cache only fills lazily as
     * songs are played — meaning the first lockscreen Refresh for any
     * unfamiliar song still pays the ~30-60s cold mmap penalty. Calling this
     * once at process start (in the foreground, when mmap is hot) costs
     * ~1-2s for ~2500 rows × 512 dims and immunizes the cache for the rest
     * of the process lifetime.
     *
     * Batched in chunks of 500 to keep the SQL `IN (...)` placeholder list
     * and the JSON payload reasonable. Each chunk is a single round-trip
     * to the EmbeddingDb worker thread.
     */
    suspend fun fullWarmAllVectors(): Int {
        val hashes = hashToMetaCache.keys.toList()
        if (hashes.isEmpty()) return vecHeapCache.size
        // 06-01j: chunk size reduced from 500→200. Each chunk returns
        // ~chunkSize × 512 floats × 4 bytes of base64 JSON. Smaller
        // chunks bound the per-call blocking window on the single
        // sqlite worker thread, so any urgent queries that race past
        // the warm don't wait as long. Per-chunk Log.d makes progress
        // visible in logcat (the activity log buffer may rotate before
        // a 30s warm completes).
        val chunkSize = 200
        var i = 0
        var chunkIndex = 0
        while (i < hashes.size) {
            val chunk = hashes.subList(i, minOf(i + chunkSize, hashes.size))
            // getVecsByHashes already populates vecHeapCache as a side effect.
            getVecsByHashes(chunk)
            i += chunkSize
            chunkIndex++
            android.util.Log.d(
                "EmbeddingDbFacade",
                "warm chunk $chunkIndex done, cache=${vecHeapCache.size}/${hashes.size}",
            )
        }
        return vecHeapCache.size
    }

    /**
     * Bugfix 2026-06-01k: replaces fullWarmAllVectors for cold-start warm.
     * The old path depended on hashToMetaCache being pre-populated by
     * prewarmFromLibrary, which silently skipped every song because
     * Song.contentHash is null on cold start - result: VEC_HEAP_WARMED
     * size=0. This path queries the embedding DB directly via a single
     * Cursor and decodes vec bytes straight into the heap maps in one
     * pass (no JSON, no base64, no chunking).
     */
    suspend fun fullWarmFromDb(): Int {
        val hashToFn = java.util.concurrent.ConcurrentHashMap<String, String>(4096)
        val hashToFp = java.util.concurrent.ConcurrentHashMap<String, String>(4096)
        val count = awaitInt { cb ->
            manager.loadAllVecsIntoHeap(
                vecHeapCache,
                hashToFn,
                hashToFp,
                filenameToHashCache,
                cb,
            )
        }
        for ((hash, fn) in hashToFn) {
            val fp = hashToFp[hash] ?: fn
            hashToMetaCache[hash] = HeapMeta(fn, fp)
        }
        android.util.Log.d(
            "EmbeddingDbFacade",
            "fullWarmFromDb count=$count vecCache=${vecHeapCache.size} metaCache=${hashToMetaCache.size}",
        )
        return count
    }

    /**
     * Restore the recommendation heap directly from the atomically-maintained
     * portable IVZ file. This deliberately does not touch SQLite, so cold
     * startup avoids opening Room/sqlite-vec on the successful fast path.
     */
    suspend fun restoreHeapFromPortableBin(expectedRows: Int = 0): HeapWarmResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            val file = File(dir, EmbeddingDbManager.EMBEDDINGS_BIN)
            if (!file.exists() || file.length() <= 0L) {
                return@withContext HeapWarmResult(
                    success = false,
                    source = "ivz",
                    error = "portable IVZ snapshot missing",
                )
            }
            try {
                val parsed = PortableEmbeddingSnapshotCodec.parse(file.readBytes(), expectedRows)
                // Publish only after the entire file validates and decodes.
                vecHeapCache.clear()
                filenameToHashCache.clear()
                hashToMetaCache.clear()
                vecHeapCache.putAll(parsed.vectors)
                filenameToHashCache.putAll(parsed.filenameToHash)
                hashToMetaCache.putAll(parsed.metadata)
                android.util.Log.i(
                    "EmbeddingDbFacade",
                    "IVZ heap restored count=${parsed.vectors.size} dim=${parsed.dim}",
                )
                HeapWarmResult(
                    success = true,
                    count = parsed.vectors.size,
                    dim = parsed.dim,
                    source = "ivz",
                )
            } catch (t: Throwable) {
                android.util.Log.w("EmbeddingDbFacade", "IVZ heap restore failed: ${t.message}")
                HeapWarmResult(
                    success = false,
                    source = "ivz",
                    error = t.message ?: t.javaClass.simpleName,
                )
            }
        }

    val vecCacheSize: Int get() = vecHeapCache.size

    suspend fun migrateFromLegacy(): JSONObject? = awaitJsonObject { cb ->
        manager.migrateFromLegacyIfNeeded(cb)
    }

    /** Re-ingest portable backup (IVZ .bin preferred, legacy JSON fallback). */
    suspend fun forceReimportEmbeddings(): JSONObject? = awaitJsonObject { cb ->
        manager.forceReimportEmbeddings(cb)
    }

    /** @deprecated Use [forceReimportEmbeddings]. */
    suspend fun forceReimportLegacyJson(): JSONObject? = forceReimportEmbeddings()

    /** Clear JVM heap caches after a full DB replace (import). */
    fun clearVecHeapCache() {
        vecHeapCache.clear()
        hashToMetaCache.clear()
        filenameToHashCache.clear()
    }

    suspend fun exportEmbeddingsBin(splitCount: Int = 3): JSONObject? = awaitJsonObject { cb ->
        manager.exportEmbeddingsBin(EmbeddingSplitCount.normalize(splitCount), cb)
    }

    /**
     * Link current library filepaths to imported embedding rows by matching
     * basename / filename / artist+album when stored paths differ.
     */
    suspend fun relinkLibraryPaths(songs: List<Song>): JSONObject? {
        val arr = org.json.JSONArray()
        for (s in songs) {
            val fp = s.filePath ?: continue
            arr.put(
                org.json.JSONObject().apply {
                    put("filepath", fp)
                    put("filename", s.filename)
                    put("artist", s.artist)
                    put("album", s.album)
                },
            )
        }
        return awaitJsonObject { cb -> manager.relinkLibraryPaths(arr, cb) }
    }

    /** filepath → content_hash for recommendation + pending checks. */
    suspend fun contentHashByFilepath(): Map<String, String> {
        val res = awaitJsonObject { cb -> manager.getPathIndexMap(cb) } ?: return emptyMap()
        val paths = res.optJSONObject("paths") ?: return emptyMap()
        val out = HashMap<String, String>(paths.length())
        val keys = paths.keys()
        while (keys.hasNext()) {
            val fp = keys.next()
            val hash = paths.optString(fp, "")
            if (fp.isNotEmpty() && hash.isNotEmpty()) out[fp] = hash
        }
        return out
    }

    /** Attach content hashes from the path index so AI can use imported vectors. */
    fun enrichSongsWithHashes(songs: List<Song>, hashByFilepath: Map<String, String>): List<Song> {
        if (hashByFilepath.isEmpty()) return songs
        return songs.map { s ->
            val fp = s.filePath ?: return@map s
            val hash = hashByFilepath[fp] ?: return@map s
            if (s.contentHash == hash && s.hasEmbedding) s
            else s.copy(contentHash = hash, hasEmbedding = true)
        }
    }

    /** Attach hashes already known from the process-lifetime heap warm. */
    fun enrichSongsWithKnownHashes(songs: List<Song>): List<Song> {
        if (songs.isEmpty() || filenameToHashCache.isEmpty()) return songs
        var changed = false
        val out = songs.map { song ->
            val hash = filenameToHashCache[song.filename] ?: return@map song
            if (song.contentHash == hash && song.hasEmbedding) {
                song
            } else {
                changed = true
                song.copy(contentHash = hash, hasEmbedding = true)
            }
        }
        return if (changed) out else songs
    }

    suspend fun stats(): JSONObject? = awaitJsonObject { cb ->
        manager.stats(cb)
    }

    suspend fun exportBinSnapshot(): JSONObject? = awaitJsonObject { cb ->
        manager.exportBinSnapshot(cb)
    }

    /** Ingest any pending_embeddings.json into SQLite + clear the pending file. */
    suspend fun recoverPendingIfAny(): JSONObject? {
        val res = awaitJsonObject { cb -> manager.recoverPendingIfAny(cb) }
        // A re-embedded song keeps its content hash, so a stale heap copy
        // would otherwise outlive the new vector until the next restart.
        val stale = ArrayList<String>()
        res?.optJSONArray("hashes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val hash = arr.optString(i, "")
                if (hash.isNotEmpty() && vecHeapCache.remove(hash) != null) stale += hash
            }
        }
        if (stale.isNotEmpty()) runCatching { getVecsByHashes(stale) }
        return res
    }

    /**
     * Push #46: write the current SQLite contents to
     * `<external-files>/local_embeddings.json` atomically (tmp + rename).
     * Used as a portable backup the user can copy off the device and
     * re-import on reinstall. Wired into:
     *   • EmbeddingEngine MSG_COMPLETE → after every batch, the freshly
     *     embedded songs are reflected in the JSON.
     *   • Activity.onPause()           → catches any drift before the
     *     user might background or close the app.
     *   • AI page → "Export embeddings backup now" button → on demand.
     */
    suspend fun exportLegacyMirror(): JSONObject? = awaitJsonObject { cb ->
        manager.exportLegacyMirror(cb)
    }

    /** Fast portable backup (IVZ1 single file). Preferred over [exportLegacyMirror]. */
    suspend fun exportPortableBin(): JSONObject? = exportEmbeddingsBin()

    /**
     * Batch fetch of vec FloatArrays by content hash. Used by the MMR rerank
     * to load candidate vectors in one SQL round-trip instead of one query
     * per candidate.
     *
     * Bugfix 2026-06-01h: results are cached in [vecHeapCache]. Once a hash
     * is fetched it is served from JVM heap forever, bypassing sqlite-vec
     * mmap entirely (immune to OS page eviction when backgrounded).
     */
    suspend fun getVecsByHashes(hashes: List<String>): Map<String, FloatArray> {
        if (hashes.isEmpty()) return emptyMap()
        // Serve cache hits without touching the DB.
        val result = HashMap<String, FloatArray>(hashes.size)
        val uncached = ArrayList<String>(hashes.size)
        for (h in hashes) {
            val cached = vecHeapCache[h]
            if (cached != null) result[h] = cached else uncached += h
        }
        if (uncached.isEmpty()) return result
        // Only fetch the genuinely missing hashes.
        val res = awaitJsonObject { cb -> manager.getVecsByHashes(uncached, cb) } ?: return result
        val vecsJson = res.optJSONObject("vectors") ?: return result
        val keys = vecsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val b64 = vecsJson.optString(key, "")
            if (b64.isEmpty()) continue
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            if (bytes.size % 4 != 0) continue
            val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            for (i in floats.indices) floats[i] = buf.float
            vecHeapCache[key] = floats  // cache for future backgrounded calls
            result[key] = floats
        }
        return result
    }

    /**
     * Top-k similar songs to the supplied filename. Runs entirely on the
     * EmbeddingDb worker thread (sqlite-vec when available, NativeAccelerator
     * NEON SIMD fallback otherwise).
     *
     * Bugfix 2026-06-01h: if the query song's vector is already in
     * [vecHeapCache] (populated after the first process_start precompute),
     * the entire kNN computation is done in-heap — no DB I/O, no mmap.
     *
     * Returns a list of [NnResult] sorted by descending similarity, or empty
     * on any failure (query song missing embedding, DB closed, etc.).
     */
    suspend fun nearestNeighborsForFilename(
        queryFilename: String,
        k: Int,
        excludeHashes: List<String> = emptyList(),
    ): List<NnResult> {
        // --- heap path ---
        val queryHash = filenameToHashCache[queryFilename]
        val queryVec = if (queryHash != null) vecHeapCache[queryHash] else null
        if (queryVec != null && vecHeapCache.size >= 100) {
            val excludeSet = excludeHashes.toHashSet()
            val scored = EmbeddingSimilarity.rankAsHeapPath(
                query = queryVec,
                candidates = vecHeapCache,
                excludeKeys = excludeSet + setOfNotNull(queryHash),
                k = k,
            )
            val out = ArrayList<NnResult>(k)
            for (row in scored) {
                val hash = row.key
                val sim = row.similarity
                val meta = hashToMetaCache[hash] ?: continue
                out += NnResult(contentHash = hash, filepath = meta.filepath,
                    filename = meta.filename, similarity = sim)
            }
            if (out.isNotEmpty()) {
                lastNearestNeighborSource = "heap"
                android.util.Log.d(
                    "EmbeddingDbFacade",
                    "nearestNeighborsForFilename query=$queryFilename source=heap count=${out.size}",
                )
                return out
            }
        }
        // --- DB fallback ---
        val res = awaitJsonObject { cb ->
            manager.nearestNeighborsForFilename(queryFilename, k, excludeHashes, cb)
        } ?: return emptyList()
        lastNearestNeighborSource = res.optString("source", "db")
        val arr = res.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<NnResult>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            out += NnResult(
                contentHash = row.optString("contentHash", ""),
                filepath = row.optString("filepath", ""),
                filename = row.optString("filename", ""),
                similarity = row.optDouble("similarity", 0.0).toFloat(),
            )
        }
        android.util.Log.d(
            "EmbeddingDbFacade",
            "nearestNeighborsForFilename query=$queryFilename source=$lastNearestNeighborSource count=${out.size}",
        )
        return out
    }

    data class NnResult(
        val contentHash: String,
        val filepath: String,
        val filename: String,
        val similarity: Float,
    )

    /**
     * Push #61: a group of filepaths whose audio collapses to the same
     * content_hash. T_EMB only stores one row per hash, but T_PATH keeps
     * filepath→hash mappings for all duplicates so we can surface them.
     */
    data class AudioDuplicateGroup(
        val contentHash: String,
        val filepaths: List<String>,
    )

    suspend fun getAudioDuplicates(): List<AudioDuplicateGroup> {
        val res = awaitJsonObject { cb -> manager.getAudioDuplicateGroups(cb) } ?: return emptyList()
        val arr = res.optJSONArray("groups") ?: return emptyList()
        val out = ArrayList<AudioDuplicateGroup>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hash = o.optString("contentHash", "")
            val fpsArr = o.optJSONArray("filepaths") ?: continue
            val fps = ArrayList<String>(fpsArr.length())
            for (j in 0 until fpsArr.length()) {
                val s = fpsArr.optString(j, "")
                if (s.isNotEmpty()) fps += s
            }
            if (hash.isNotEmpty() && fps.size >= 2) out += AudioDuplicateGroup(hash, fps)
        }
        return out
    }

    /**
     * Push #61: drop a single filepath from T_PATH. Used after the user
     * deletes the file via the Audio Duplicates section so the deleted
     * filepath doesn't keep showing up in the duplicate group.
     */
    suspend fun removePathIndexEntry(filepath: String): Int = suspendCancellableCoroutine { cont ->
        val cb = EmbeddingDbManager.Callback<Int> { result, error ->
            if (error != null) cont.resumeWithException(error)
            else cont.resume(result ?: 0)
        }
        manager.removePathIndexEntry(filepath, cb)
    }

    /**
     * Push #50: a single row in the embeddings table, surfaced to the AI
     * page's Duplicates section. timestamp is millis (epoch).
     */
    data class DuplicateRow(
        val contentHash: String,
        val filepath: String,
        val filename: String,
        val artist: String,
        val album: String,
        val timestamp: Long,
        val dim: Int,
    )

    /**
     * Push #50: list every row whose filename has more than one embedding
     * row. Ordered by filename ASC, then timestamp DESC (newest first
     * within each group). UI groups them by filename and renders a per-row
     * remove icon + a top-of-section "Remove all extras" bulk action.
     */
    suspend fun getDuplicates(): List<DuplicateRow> {
        val res = awaitJsonObject { cb -> manager.getDuplicateRows(cb) } ?: return emptyList()
        val arr = res.optJSONArray("rows") ?: return emptyList()
        val out = ArrayList<DuplicateRow>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            out += DuplicateRow(
                contentHash = row.optString("contentHash", ""),
                filepath = row.optString("filepath", ""),
                filename = row.optString("filename", ""),
                artist = row.optString("artist", ""),
                album = row.optString("album", ""),
                timestamp = row.optLong("timestamp", 0L),
                dim = row.optInt("dim", 0),
            )
        }
        return out
    }

    /**
     * Push #50: delete embedding rows by content_hash (the PK). Used for
     * per-row removal from the AI page's Duplicates section.
     */
    suspend fun deleteEmbeddingsByContentHash(hashes: List<String>): Int {
        if (hashes.isEmpty()) return 0
        val arr = org.json.JSONArray()
        for (h in hashes) if (h.isNotEmpty()) arr.put(h)
        return suspendCancellableCoroutine { cont ->
            val cb = EmbeddingDbManager.Callback<Int> { result, error ->
                if (error != null) cont.resumeWithException(error)
                else cont.resume(result ?: 0)
            }
            manager.deleteByHashes(arr, cb)
        }
    }

    // Push #51: dedupeByFilename removed — replaced by a per-hash kill-list
    // computed in the AI page (so missing-file groups can be fully deleted
    // while present-file groups keep the newest row). Path goes through
    // deleteEmbeddingsByContentHash above.

    /** Quick row count for "are embeddings ready" checks. */
    suspend fun rowCount(): Int = stats()?.optInt("count", 0) ?: 0

    /**
     * Push #49: drop embedding rows whose filename appears in [filenames].
     * Used by the AI page "Remove N stale embeddings" flow when imported
     * (or device-deleted) songs leave embedding rows that don't match any
     * MediaStore entry. Returns the number of rows actually deleted; the
     * caller is responsible for refreshing the JSON mirror + on-screen stats.
     */
    suspend fun deleteEmbeddingsByFilename(filenames: List<String>): Int {
        if (filenames.isEmpty()) return 0
        val arr = org.json.JSONArray()
        for (fn in filenames) if (fn.isNotEmpty()) arr.put(fn)
        return suspendCancellableCoroutine { cont ->
            val cb = EmbeddingDbManager.Callback<Int> { result, error ->
                if (error != null) cont.resumeWithException(error)
                else cont.resume(result ?: 0)
            }
            manager.deleteByFilenames(arr, cb)
        }
    }

    /** Set of every filename currently in the embeddings DB. */
    suspend fun allFilenames(): Set<String> {
        val res = awaitJsonObject { cb -> manager.getAllFilenames(cb) } ?: return emptySet()
        val arr = res.optJSONArray("filenames") ?: return emptySet()
        val out = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    /**
     * Push #56 diagnostic: returns formatted lines (`emb:hash:"path"` /
     * `path:hash:"path"`) showing the DB-side filepath strings for every
     * row whose filename matches. Used by MainActivity to log a side-by-
     * side against `Song.filePath` so we can spot why a Pending song
     * doesn't match despite being in the DB.
     */
    suspend fun diagnoseByFilename(filename: String): List<String> {
        val res = awaitJsonObject { cb -> manager.diagnoseByFilename(filename, cb) } ?: return emptyList()
        val arr = res.optJSONArray("lines") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    /**
     * Push #53: filepath set for every embedded row (excluding empty
     * filepaths from legacy JSON imports). The AI page's Pending list
     * uses this set instead of filenames because MediaStore's
     * DISPLAY_NAME can diverge from File(path).getName() — same physical
     * file with two different filename strings.
     */
    suspend fun allFilepaths(): Set<String> {
        val res = awaitJsonObject { cb -> manager.getAllFilepaths(cb) } ?: return emptySet()
        val arr = res.optJSONArray("filepaths") ?: return emptySet()
        val out = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    private suspend fun awaitJsonObject(
        call: (EmbeddingDbManager.Callback<JSONObject>) -> Unit
    ): JSONObject? = suspendCancellableCoroutine { cont ->
        val cb = EmbeddingDbManager.Callback<JSONObject> { result, error ->
            // EmbeddingDbManager callbacks arrive on EmbeddingDbWorker.
            // Always resume on Main so callers can update Compose state.
            mainHandler.post {
                if (!cont.isActive) return@post
                if (error != null) cont.resumeWithException(error)
                else cont.resume(result)
            }
        }
        call(cb)
    }

    private suspend fun awaitInt(
        call: (EmbeddingDbManager.Callback<Int>) -> Unit
    ): Int = suspendCancellableCoroutine { cont ->
        val cb = EmbeddingDbManager.Callback<Int> { result, error ->
            mainHandler.post {
                if (!cont.isActive) return@post
                if (error != null) cont.resumeWithException(error)
                else cont.resume(result ?: 0)
            }
        }
        call(cb)
    }
}

internal object PortableEmbeddingSnapshotCodec {
    private const val HEADER_SIZE = 20
    private const val VERSION = 1
    private const val MAX_ROWS = 100_000
    private const val MAX_DIM = 16_384
    private val MAGIC = byteArrayOf('I'.code.toByte(), 'V'.code.toByte(), 'Z'.code.toByte(), '1'.code.toByte())

    data class ParsedHeap(
        val dim: Int,
        val vectors: Map<String, FloatArray>,
        val filenameToHash: Map<String, String>,
        val metadata: Map<String, EmbeddingDbFacade.HeapMeta>,
    )

    fun parse(data: ByteArray, expectedRows: Int = 0): ParsedHeap {
        require(data.size >= HEADER_SIZE) { "IVZ file too short" }
        require(data.copyOfRange(0, 4).contentEquals(MAGIC)) { "bad IVZ magic" }
        val header = ByteBuffer.wrap(data, 4, HEADER_SIZE - 4).order(ByteOrder.LITTLE_ENDIAN)
        val version = header.int
        val dim = header.int
        val rowCount = header.int
        val metadataLength = header.int
        require(version == VERSION) { "unsupported IVZ version $version" }
        require(dim in 1..MAX_DIM) { "invalid IVZ dimension $dim" }
        require(rowCount in 1..MAX_ROWS) { "invalid IVZ row count $rowCount" }
        require(expectedRows <= 0 || rowCount == expectedRows) {
            "IVZ row count $rowCount does not match expected $expectedRows"
        }
        require(metadataLength >= 2) { "invalid IVZ metadata length" }
        val vectorBytes = rowCount.toLong() * dim.toLong() * 4L
        val expectedLength = HEADER_SIZE.toLong() + metadataLength.toLong() + vectorBytes
        require(expectedLength == data.size.toLong()) { "IVZ file length mismatch" }

        val metadataStart = HEADER_SIZE
        val vectorStart = metadataStart + metadataLength
        val metadataJson = String(
            data,
            metadataStart,
            metadataLength,
            Charsets.UTF_8,
        )
        val root = org.json.JSONObject(metadataJson)
        val entries = root.optJSONArray("entries") ?: error("IVZ entries missing")
        require(entries.length() == rowCount) { "IVZ entry count mismatch" }

        val vectors = HashMap<String, FloatArray>(rowCount * 2)
        val filenameToHash = HashMap<String, String>(rowCount * 2)
        val metadata = HashMap<String, EmbeddingDbFacade.HeapMeta>(rowCount * 2)
        for (i in 0 until rowCount) {
            val entry = entries.optJSONObject(i) ?: error("IVZ entry $i missing")
            val hash = entry.optString("contentHash", entry.optString("key", ""))
            require(hash.isNotBlank()) { "IVZ entry $i has no content hash" }
            val filename = entry.optString("filename", "")
            val filepath = entry.optString("filepath", "").ifBlank { filename }
            val offset = vectorStart + i * dim * 4
            val vector = FloatArray(dim)
            ByteBuffer.wrap(data, offset, dim * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
                .get(vector)
            vectors[hash] = vector
            if (filename.isNotBlank()) filenameToHash[filename] = hash
            metadata[hash] = EmbeddingDbFacade.HeapMeta(filename, filepath)
        }
        require(vectors.size == rowCount) { "IVZ contains duplicate content hashes" }
        return ParsedHeap(dim, vectors, filenameToHash, metadata)
    }
}
