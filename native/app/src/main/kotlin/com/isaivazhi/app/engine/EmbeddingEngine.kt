package com.isaivazhi.app.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.isaivazhi.app.EmbeddingCommandContract
import com.isaivazhi.app.EmbeddingControllerClient
import com.isaivazhi.app.EmbeddingForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Kotlin engine over the existing Java EmbeddingForegroundService (running in
 * :ai process) and EmbeddingControllerClient (Messenger-based event channel).
 *
 * UI calls:
 *   - embedSongs(songs) — start a foreground batch over the supplied paths
 *   - stop() — abort the current batch
 *
 * UI observes:
 *   - status: StateFlow<EmbeddingStatus> — live progress, throttle reason,
 *     active backend (nnapi+fp16 / nnapi / cpu)
 *   - batchComplete: SharedFlow<BatchCompleteEvent> — emits ONCE per batch
 *     completion so the recommender can refresh. Use SharedFlow because
 *     completion is an event, not a state.
 */
class EmbeddingEngine(
    private val appContext: Context,
    private val embeddingDb: EmbeddingDbFacade,
    // Push #41: optional toaster — emits "Embeddings ready" on
    // MSG_COMPLETE and the error string on MSG_ERROR.
    private val toaster: Toaster? = null,
    /** Optional sink for human-readable log lines surfaced on the AI page. */
    val logBuffer: LogBuffer = LogBuffer(archiveContext = appContext),
) {

    data class EmbeddingStatus(
        val inProgress: Boolean = false,
        val processed: Int = 0,
        val total: Int = 0,
        val current: String = "",
        val activeBackend: String = "",
        val error: String? = null,
        /** Approx ETA in seconds for the remaining songs, when known. */
        val etaSeconds: Long? = null,
        /** Push #43: latest init-step text from the service (e.g.
         *  "Extracting audio model (~273 MB)…", "Starting NPU/GPU
         *  (nnapi+fp16)…", "NPU/GPU unavailable — falling back to CPU…").
         *  Surfaced on the AI page banner so the user sees what's
         *  actually happening during "warming up" instead of a black box. */
        val initStepText: String = "",
        /** Push #49: count of failed songs in the current (or most
         *  recently completed) batch. Used for the "(N failed)" hint in
         *  the Pending header and the post-batch "Last run" summary. */
        val failed: Int = 0,
        /** Push #49: System.currentTimeMillis() of MSG_COMPLETE for the
         *  most recent batch. Zero while a batch is running or before
         *  the first batch of this process lifetime completes. The AI
         *  screen uses this to show "Last run: N succeeded, M failed •
         *  completed Xm ago" once inProgress flips to false. */
        val lastCompletedAtMs: Long = 0L,
        /** Push #49: snapshot of `processed` taken at MSG_COMPLETE. The
         *  live `processed` field flips to 0 at the next batch start;
         *  this field stays until the next completion. */
        val lastCompletedProcessed: Int = 0,
        /** Push #49: snapshot of `failed` taken at MSG_COMPLETE. */
        val lastCompletedFailed: Int = 0,
    )

    data class BatchCompleteEvent(
        val processed: Int,
        val failed: Int,
        val recoveredIntoDb: Int,
        val totalRows: Int,
    )

    private val _status = MutableStateFlow(EmbeddingStatus())
    val status: StateFlow<EmbeddingStatus> = _status.asStateFlow()

    private val _batchComplete = MutableSharedFlow<BatchCompleteEvent>(
        replay = 0, extraBufferCapacity = 4
    )
    val batchComplete: SharedFlow<BatchCompleteEvent> = _batchComplete.asSharedFlow()

    /**
     * Push #53: per-song completion event. Emitted on MSG_SONG_COMPLETE
     * with the filepath + filename of the song the service just finished
     * embedding. MainActivity collects this and adds the filepath to
     * `embeddedFilepaths` (the authoritative "is embedded" set) so the
     * AI page's Pending stat ticks down by 1 per song. The full SQLite
     * refresh still happens on MSG_COMPLETE (via `batchComplete`) — that
     * reconciles the optimistic state.
     *
     * Buffer = 64 + DROP_OLDEST so a fast batch (NPU at ~180ms/song) can't
     * lose emissions if MainActivity is briefly suspended.
     */
    data class SongCompleteEvent(val filename: String, val filepath: String)
    private val _songComplete = MutableSharedFlow<SongCompleteEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val songComplete: SharedFlow<SongCompleteEvent> = _songComplete.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val controllerClient: EmbeddingControllerClient = EmbeddingControllerClient(
        appContext,
        ContextCompat.getMainExecutor(appContext),
    ) { what, data -> onEvent(what, data) }

    private var batchStartedAtMs: Long = 0L

    init {
        // Bind early so progress events flow even if a batch was kicked off
        // by a previous app instance and is still running in :ai.
        controllerClient.ensureConnected(object : EmbeddingControllerClient.ConnectionCallback {
            override fun onConnected() { /* status fan-in starts via incoming MSG_STATUS */ }
            override fun onError(message: String) {
                android.util.Log.w("EmbeddingEngine", "controller bind failed: $message")
            }
        })
        // Push #45 (revised): force recoverPendingIfAny on app open so
        // stale entries in pending_embeddings.json (from a previous
        // session whose MSG_COMPLETE never reached this engine) get
        // promoted to the SQLite DB. Without this, the EmbeddingService
        // sees those songs as "already embedded (pending)" on every
        // tap and skips them forever — the user's symptom of
        // "embedding for one song does nothing".
        scope.launch {
            try {
                val res = embeddingDb.recoverPendingIfAny()
                val recovered = res?.optInt("recovered", 0) ?: 0
                val totalRows = res?.optInt("totalRows", 0) ?: 0
                if (recovered > 0) {
                    android.util.Log.i("EmbeddingEngine",
                        "startup recover: $recovered promoted, totalRows=$totalRows")
                    logBuffer.append("startup", "recovered $recovered pending → DB (totalRows=$totalRows)")
                }
            } catch (t: Throwable) {
                android.util.Log.w("EmbeddingEngine", "startup recover failed: ${t.message}")
                logBuffer.append("startup", "recover failed: ${t.message}")
            }
        }
    }

    /**
     * Starts a foreground embedding batch over the supplied file paths.
     * Filters paths to existing songs that aren't already embedded. The
     * service writes pending_embeddings.json incrementally; MSG_COMPLETE
     * triggers the SQLite recover step + a BatchCompleteEvent emission.
     */
    fun embedSongs(songs: List<Song>) {
        scope.launch {
            // Filter to songs that haven't been embedded yet. The DB row count
            // gives us the upper bound; for individual presence we let the
            // Java EmbeddingService handle deduplication since it already
            // checks each contentHash against the existing embeddings file.
            val paths = songs.mapNotNull { it.filePath }.filter { it.isNotEmpty() }
            if (paths.isEmpty()) {
                _status.value = _status.value.copy(error = "no_playable_songs")
                return@launch
            }
            batchStartedAtMs = android.os.SystemClock.elapsedRealtime()
            // Push #49: preserve the previous run's snapshot fields so the
            // "Last run" line can keep showing during the brief gap
            // between the user tapping Embed and the first MSG_STATUS
            // arriving. Once the new batch's MSG_COMPLETE fires, these
            // get overwritten with the fresh numbers.
            val prev = _status.value
            _status.value = EmbeddingStatus(
                inProgress = true,
                total = paths.size,
                processed = 0,
                current = "",
                etaSeconds = estimateEtaSeconds(paths.size, prev.activeBackend),
                activeBackend = prev.activeBackend,
                failed = 0,
                lastCompletedAtMs = prev.lastCompletedAtMs,
                lastCompletedProcessed = prev.lastCompletedProcessed,
                lastCompletedFailed = prev.lastCompletedFailed,
            )
            logBuffer.append("start", "batch of ${paths.size} songs queued")
            startForegroundService(paths)
        }
    }

    private fun startForegroundService(paths: List<String>) {
        val intent = Intent(appContext, EmbeddingForegroundService::class.java).apply {
            action = EmbeddingForegroundService.ACTION_START
            putStringArrayListExtra(EmbeddingForegroundService.EXTRA_PATHS, ArrayList(paths))
            // playback-active hint: assume false here; PlaybackEngine could
            // set this via EmbeddingControllerClient.setPlaybackActive when
            // we wire that bridge in a future session.
            putExtra(EmbeddingForegroundService.EXTRA_PLAYBACK_ACTIVE, false)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        } catch (t: Throwable) {
            android.util.Log.w("EmbeddingEngine", "startForegroundService failed: ${t.message}")
            _status.value = _status.value.copy(inProgress = false, error = t.message)
        }
    }

    /**
     * Push #49: drop stale embeddings (rows whose filename no longer maps
     * to anything in MediaStore). Runs on the DB worker thread, refreshes
     * the portable JSON mirror so the change is reflected in the backup,
     * and toasts the result. Caller (MainActivity) refreshes the
     * embeddedFilenames + row count on completion.
     */
    fun removeStaleEmbeddings(filenames: List<String>, onComplete: (removed: Int) -> Unit = {}) {
        if (filenames.isEmpty()) {
            onComplete(0); return
        }
        scope.launch {
            try {
                val removed = embeddingDb.deleteEmbeddingsByFilename(filenames)
                logBuffer.append("stale", "removed $removed stale embedding row(s)")
                // Refresh the JSON backup so the next reinstall doesn't
                // re-import the rows we just deleted.
                runCatching { embeddingDb.exportLegacyMirror() }
                toaster?.show(
                    if (removed > 0) "Removed $removed stale embedding${if (removed == 1) "" else "s"}"
                    else "No stale rows to remove"
                )
                kotlinx.coroutines.withContext(Dispatchers.Default) { onComplete(removed) }
            } catch (t: Throwable) {
                android.util.Log.w("EmbeddingEngine", "removeStaleEmbeddings failed: ${t.message}")
                logBuffer.append("error", "stale remove failed: ${t.message}")
                toaster?.show("Stale removal failed")
                onComplete(0)
            }
        }
    }

    /**
     * Push #61: groups of filepaths sharing one content_hash (true audio
     * duplicates). The AI page renders these as a separate section so
     * the user can play / verify / delete dupes individually.
     */
    suspend fun getAudioDuplicates(): List<EmbeddingDbFacade.AudioDuplicateGroup> = try {
        embeddingDb.getAudioDuplicates()
    } catch (t: Throwable) {
        android.util.Log.w("EmbeddingEngine", "getAudioDuplicates failed: ${t.message}")
        logBuffer.append("error", "getAudioDuplicates failed: ${t.message}")
        emptyList()
    }

    /**
     * Push #61: cleanup hook for the Audio Duplicates section. After
     * the user has deleted the file via the standard deleteSongHelper
     * flow, drop the T_PATH entry so the now-dead filepath stops
     * showing up in duplicate groups.
     */
    fun removeAudioDupPath(filepath: String, onComplete: (Int) -> Unit = {}) {
        scope.launch {
            try {
                val n = embeddingDb.removePathIndexEntry(filepath)
                logBuffer.append("audiodupes", "removed path-index entry for $filepath ($n row)")
                onComplete(n)
            } catch (t: Throwable) {
                android.util.Log.w("EmbeddingEngine", "removeAudioDupPath failed: ${t.message}")
                logBuffer.append("error", "removeAudioDupPath failed: ${t.message}")
                onComplete(0)
            }
        }
    }

    /**
     * Push #50: fetch the current duplicate-filename list from SQLite.
     * Suspending; called from the AI page on open + after every
     * dedupe / per-row delete so the UI mirrors the DB.
     */
    suspend fun getDuplicates(): List<EmbeddingDbFacade.DuplicateRow> = try {
        embeddingDb.getDuplicates()
    } catch (t: Throwable) {
        android.util.Log.w("EmbeddingEngine", "getDuplicates failed: ${t.message}")
        logBuffer.append("error", "getDuplicates failed: ${t.message}")
        emptyList()
    }

    /**
     * Push #50: delete specific embedding rows by content_hash (PK). One
     * or many. The AI page calls this from the per-row (−) button + from
     * the detail-sheet "Remove this row" button. Refreshes the JSON
     * mirror so the next reinstall doesn't re-import the deletions.
     */
    fun removeDuplicateRows(contentHashes: List<String>, onComplete: (removed: Int) -> Unit = {}) {
        if (contentHashes.isEmpty()) {
            onComplete(0); return
        }
        scope.launch {
            try {
                val removed = embeddingDb.deleteEmbeddingsByContentHash(contentHashes)
                logBuffer.append("dupes", "removed $removed duplicate row(s) by hash")
                runCatching { embeddingDb.exportLegacyMirror() }
                toaster?.show(
                    if (removed > 0) "Removed $removed duplicate row${if (removed == 1) "" else "s"}"
                    else "No matching rows to remove"
                )
                onComplete(removed)
            } catch (t: Throwable) {
                android.util.Log.w("EmbeddingEngine", "removeDuplicateRows failed: ${t.message}")
                logBuffer.append("error", "dupes remove failed: ${t.message}")
                toaster?.show("Duplicate removal failed")
                onComplete(0)
            }
        }
    }

    // Push #51: dedupeKeepingNewest removed — bulk "Remove all extras" now
    // computes the kill list in the screen (so missing-file groups can be
    // fully deleted) and routes through removeDuplicateRows above.

    fun stop() {
        val intent = Intent(appContext, EmbeddingForegroundService::class.java).apply {
            action = EmbeddingForegroundService.ACTION_STOP
        }
        runCatching { appContext.startService(intent) }
        _status.value = _status.value.copy(inProgress = false)
    }

    private fun onEvent(what: Int, data: Bundle) {
        // Push #45 (revised): surface every received message in the
        // in-app log buffer so the user can tell whether MSG events
        // from :ai are reaching the main process. Previously the only
        // visible line was "batch of N songs queued" (logged from
        // embedSongs) — making MSG-delivery failures invisible.
        val name = when (what) {
            EmbeddingCommandContract.MSG_STATUS -> "MSG_STATUS"
            EmbeddingCommandContract.MSG_PROGRESS -> "MSG_PROGRESS"
            EmbeddingCommandContract.MSG_SONG_COMPLETE -> "MSG_SONG_COMPLETE"
            EmbeddingCommandContract.MSG_COMPLETE -> "MSG_COMPLETE"
            EmbeddingCommandContract.MSG_ERROR -> "MSG_ERROR"
            EmbeddingCommandContract.MSG_NEAREST_RESULT -> "MSG_NEAREST_RESULT"
            else -> "MSG_$what"
        }
        android.util.Log.i("EmbeddingEngine", "recv $name")
        logBuffer.append("recv", name)
        when (what) {
            EmbeddingCommandContract.MSG_STATUS -> {
                val inProgress = data.getBoolean(EmbeddingCommandContract.KEY_IN_PROGRESS, false)
                val backend = data.getString(EmbeddingCommandContract.KEY_ACTIVE_BACKEND, "") ?: ""
                val processed = data.getInt(EmbeddingCommandContract.KEY_PROCESSED, _status.value.processed)
                val total = data.getInt(EmbeddingCommandContract.KEY_TOTAL, _status.value.total)
                val failed = data.getInt(EmbeddingCommandContract.KEY_FAILED, _status.value.failed)
                val stepText = data.getString(EmbeddingCommandContract.KEY_INIT_STEP_TEXT, "") ?: ""
                _status.value = _status.value.copy(
                    inProgress = inProgress,
                    processed = processed,
                    total = total,
                    failed = failed,
                    activeBackend = backend.ifBlank { _status.value.activeBackend },
                    etaSeconds = if (inProgress) {
                        estimateEtaSeconds((total - processed).coerceAtLeast(0), backend)
                    } else null,
                    // Push #43: surface init-step text. Clear once embedding
                    // moves past init (first onProgress arrives → processed>0
                    // or current is non-blank).
                    initStepText = if (inProgress && processed == 0) stepText else "",
                )
                if (stepText.isNotBlank()) {
                    logBuffer.append("init", stepText)
                }
            }
            EmbeddingCommandContract.MSG_PROGRESS -> {
                val processed = data.getInt(EmbeddingCommandContract.KEY_PROCESSED, _status.value.processed)
                val total = data.getInt(EmbeddingCommandContract.KEY_TOTAL, _status.value.total)
                val failed = data.getInt(EmbeddingCommandContract.KEY_FAILED, _status.value.failed)
                val filename = data.getString(EmbeddingCommandContract.KEY_FILENAME, "") ?: ""
                val backend = data.getString(EmbeddingCommandContract.KEY_ACTIVE_BACKEND, "") ?: ""
                val priorBackend = _status.value.activeBackend
                _status.value = _status.value.copy(
                    inProgress = true,
                    processed = processed,
                    total = total,
                    failed = failed,
                    current = filename,
                    activeBackend = backend.ifBlank { _status.value.activeBackend },
                    etaSeconds = estimateEtaSeconds((total - processed).coerceAtLeast(0), backend),
                    // Clear init step text once embedding actually starts moving.
                    initStepText = "",
                )
                // Push #43: announce backend selection ONCE when the
                // service transitions from "unknown" to a resolved
                // backend, so the user knows whether NPU/GPU engaged or
                // we fell to CPU.
                if (backend.isNotBlank() && priorBackend.isBlank()) {
                    val label = when {
                        backend.startsWith("nnapi", ignoreCase = true) -> "Using NPU/GPU ($backend)"
                        backend.equals("cpu", ignoreCase = true) -> "Using CPU (NPU/GPU unavailable — slower)"
                        else -> "Using $backend"
                    }
                    toaster?.show(label)
                    logBuffer.append("backend", label)
                }
                if (filename.isNotBlank()) {
                    logBuffer.append("progress", "$processed/$total $filename" +
                        if (backend.isNotBlank()) " [$backend]" else "")
                }
            }
            EmbeddingCommandContract.MSG_SONG_COMPLETE -> {
                // Push #53: emit filepath + filename so MainActivity can
                // update its embeddedFilepaths set optimistically. The
                // Pending stat is derived from filepath comparison, so
                // it ticks down by 1 per song. Bulk SQLite refresh still
                // happens on MSG_COMPLETE.
                val filename = data.getString(EmbeddingCommandContract.KEY_FILENAME, "") ?: ""
                val filepath = data.getString(EmbeddingCommandContract.KEY_FILE_PATH, "") ?: ""
                if (filepath.isNotBlank()) {
                    _songComplete.tryEmit(SongCompleteEvent(filename = filename, filepath = filepath))
                }
            }
            EmbeddingCommandContract.MSG_COMPLETE -> {
                val processed = data.getInt(EmbeddingCommandContract.KEY_PROCESSED, 0)
                val failed = data.getInt(EmbeddingCommandContract.KEY_FAILED, 0)
                _status.value = _status.value.copy(
                    inProgress = false,
                    processed = processed,
                    total = processed + failed,
                    failed = failed,
                    current = "",
                    etaSeconds = null,
                    // Push #49: snapshot the run's results so the AI screen
                    // can show "Last run: N succeeded, M failed • Xm ago"
                    // until the next batch starts.
                    lastCompletedAtMs = System.currentTimeMillis(),
                    lastCompletedProcessed = processed,
                    lastCompletedFailed = failed,
                )
                logBuffer.append("complete", "processed=$processed failed=$failed")
                toaster?.show(
                    if (failed > 0) "Embeddings ready — $processed done, $failed failed"
                    else "Embeddings ready — $processed done"
                )
                scope.launch {
                    try {
                        val res = embeddingDb.recoverPendingIfAny()
                        val recovered = res?.optInt("recovered", 0) ?: 0
                        val totalRows = res?.optInt("totalRows", 0) ?: 0
                        logBuffer.append("ingest", "recovered=$recovered totalRows=$totalRows")
                        _batchComplete.tryEmit(BatchCompleteEvent(processed, failed, recovered, totalRows))
                        // Push #46: refresh the portable JSON backup so it
                        // reflects the freshly-embedded songs. Atomic
                        // (tmp + rename) so a crash mid-write can't
                        // corrupt the existing file. User can copy this
                        // off the device and use it to seed a reinstall.
                        val ex = embeddingDb.exportLegacyMirror()
                        val exRows = ex?.optInt("rowCount", 0) ?: 0
                        val exBytes = ex?.optLong("bytes", 0L) ?: 0L
                        logBuffer.append("backup", "local_embeddings.json refreshed: $exRows rows, $exBytes bytes")
                    } catch (t: Throwable) {
                        android.util.Log.w("EmbeddingEngine", "post-complete chain failed: ${t.message}")
                        logBuffer.append("error", "post-complete chain failed: ${t.message}")
                    }
                }
            }
            EmbeddingCommandContract.MSG_ERROR -> {
                val err = data.getString(EmbeddingCommandContract.KEY_ERROR, "unknown error") ?: "unknown error"
                val filepath = data.getString(EmbeddingCommandContract.KEY_FILE_PATH, "") ?: ""
                val failed = data.getInt(EmbeddingCommandContract.KEY_FAILED, _status.value.failed)
                // Push #53: per-song errors (filepath populated) are NOT
                // terminal — the foreground service keeps processing the
                // rest of the batch. Previously this code path flipped
                // inProgress=false on every error, which made the UI claim
                // the entire batch had stopped even though the next song
                // was already being embedded. Only batch-fatal errors
                // (empty filepath, e.g. backend init failure) actually
                // halt the run.
                val perSong = filepath.isNotEmpty()
                if (perSong) {
                    val fname = filepath.substringAfterLast('/').ifBlank { filepath }
                    _status.value = _status.value.copy(failed = failed)
                    logBuffer.append("error", "decode failed: $fname ($err)")
                    toaster?.show("Embed failed: $fname")
                } else {
                    _status.value = _status.value.copy(inProgress = false, error = err, failed = failed)
                    logBuffer.append("error", err)
                    toaster?.show("Embedding error: $err")
                }
            }
        }
    }

    /**
     * Rough ETA estimate. Better than nothing; replaced with a real backend
     * probe in a follow-up session.
     *
     * Per-song latency assumptions (from the Capacitor build's observed values):
     *   - nnapi+fp16: ~180 ms/song
     *   - nnapi (fp32): ~280 ms/song
     *   - cpu: ~1400 ms/song
     *   - unknown / "": assume ~400 ms (mid-range)
     */
    private fun estimateEtaSeconds(remainingSongs: Int, backend: String): Long? {
        if (remainingSongs <= 0) return null
        val perSongMs = when {
            backend.contains("fp16", ignoreCase = true) -> 180
            backend.startsWith("nnapi", ignoreCase = true) -> 280
            backend.equals("cpu", ignoreCase = true) -> 1400
            else -> 400
        }
        return (remainingSongs.toLong() * perSongMs) / 1000L
    }
}
