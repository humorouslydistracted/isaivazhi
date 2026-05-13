package com.isaivazhi.app.engine

import android.content.Context
import com.isaivazhi.app.EmbeddingDbManager
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

    private val manager: EmbeddingDbManager = EmbeddingDbManager.get(appContext)

    suspend fun migrateFromLegacy(): JSONObject? = awaitJsonObject { cb ->
        manager.migrateFromLegacyIfNeeded(cb)
    }

    suspend fun stats(): JSONObject? = awaitJsonObject { cb ->
        manager.stats(cb)
    }

    suspend fun exportBinSnapshot(): JSONObject? = awaitJsonObject { cb ->
        manager.exportBinSnapshot(cb)
    }

    /** Ingest any pending_embeddings.json into SQLite + clear the pending file. */
    suspend fun recoverPendingIfAny(): JSONObject? = awaitJsonObject { cb ->
        manager.recoverPendingIfAny(cb)
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

    /**
     * Batch fetch of vec FloatArrays by content hash. Used by the MMR rerank
     * to load candidate vectors in one SQL round-trip instead of one query
     * per candidate.
     */
    suspend fun getVecsByHashes(hashes: List<String>): Map<String, FloatArray> {
        if (hashes.isEmpty()) return emptyMap()
        val res = awaitJsonObject { cb -> manager.getVecsByHashes(hashes, cb) } ?: return emptyMap()
        val vecsJson = res.optJSONObject("vectors") ?: return emptyMap()
        val out = HashMap<String, FloatArray>(vecsJson.length())
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
            out[key] = floats
        }
        return out
    }

    /**
     * Top-k similar songs to the supplied filename. Runs entirely on the
     * EmbeddingDb worker thread (sqlite-vec when available, NativeAccelerator
     * NEON SIMD fallback otherwise).
     *
     * Returns a list of [NnResult] sorted by descending similarity, or empty
     * on any failure (query song missing embedding, DB closed, etc.).
     */
    suspend fun nearestNeighborsForFilename(
        queryFilename: String,
        k: Int,
        excludeHashes: List<String> = emptyList(),
    ): List<NnResult> {
        val res = awaitJsonObject { cb ->
            manager.nearestNeighborsForFilename(queryFilename, k, excludeHashes, cb)
        } ?: return emptyList()
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
            if (error != null) {
                cont.resumeWithException(error)
            } else {
                cont.resume(result)
            }
        }
        call(cb)
    }
}
