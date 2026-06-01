package com.isaivazhi.app.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Bugfix 2026-06-01d/e: process-lifetime recommendation precompute. The
 * lockscreen Refresh tap problem is that sqlite-vec mmap pages get
 * evicted within ~7s of the app backgrounding (OS aggressive paging on
 * locked screens), and the first `nearestNeighbors` call after eviction
 * costs ~20s to page back in. Mitigation: as soon as the current song
 * changes (or favorites/dislikes toggle), we kick off a recompute in the
 * background and cache the result. When the user taps Refresh — even
 * from the lockscreen — we serve the cache instantly.
 *
 * Lifetime: lives in [AppContainer], owned by IsaiVazhiApp; survives
 * Activity destruction.
 *
 * Concurrency model (06-01e revision):
 *   - Single-flight: one DB-bound compute at a time, gated by `computeMutex`.
 *   - **No cancellation.** The 06-01d revision cancelled in-flight jobs
 *     on every new seed. Two failures resulted:
 *       a) the blocking sqlite-vec syscall ran to completion anyway on
 *          the EmbeddingDb worker thread, so the "cancel" was cosmetic.
 *       b) `runCatching` swallowed the CancellationException and
 *          returned `emptyList()`, leaving the cache empty so the next
 *          Refresh fell through to the slow live path. Log evidence:
 *          22:22:40/45/53 PRECOMPUTE_DONE size=0 in 3-10s, then
 *          22:22:49 REFRESH_DISP with no REFRESH_RECOMMEND_DONE.
 *   - Coalescing: if a new song change arrives mid-compute, we stash
 *     `pendingSeed`. The running compute finishes; on release we re-run
 *     once for the latest pending seed.
 *   - Debounce: 4000ms. Auto-advance + neutral skips run 2-3s apart;
 *     1500ms was too short and caused cascade.
 *   - No `popTail` refill: that triggered a second compute that
 *     collided with the natural auto-advance song change. The
 *     song_change observer handles refill on its own.
 */
class RecommendationCache(
    private val container: AppContainer,
    private val appScope: CoroutineScope,
) {
    private val _tail = MutableStateFlow<List<Song>?>(null)
    val tail: StateFlow<List<Song>?> = _tail.asStateFlow()

    private val _computing = MutableStateFlow(false)
    val computing: StateFlow<Boolean> = _computing.asStateFlow()

    private val computeMutex = Mutex()

    @Volatile private var pendingSeed: String? = null
    @Volatile private var pendingReason: String = ""
    @Volatile private var lastSeed: String? = null

    /** Start observing song-change + taste-change signals. Idempotent. */
    fun start() {
        appScope.launch {
            container.playback.state
                .map { it.currentMediaId }
                .distinctUntilChanged()
                .collect { mediaId ->
                    if (mediaId.isNullOrBlank()) return@collect
                    delay(DEBOUNCE_MS)
                    val nowMediaId = container.playback.state.value.currentMediaId
                    if (nowMediaId == mediaId) {
                        requestCompute(mediaId, reason = "song_change")
                    }
                }
        }
        appScope.launch {
            container.rebuildSignal.collect { reason ->
                val mediaId = container.playback.state.value.currentMediaId
                if (!mediaId.isNullOrBlank()) {
                    requestCompute(mediaId, reason = "rebuild_$reason")
                }
            }
        }
    }

    /**
     * Take the cached tail (if any), clear it, and immediately request
     * a refill for the current song so the NEXT refresh tap also has
     * data. The 06-01e revision tried to rely on the song_change
     * observer to refill, but that has DEBOUNCE_MS latency — and if
     * the user taps Refresh again on lockscreen before that fires, the
     * cache is empty and they hit the 20s live-compute path. The
     * coalescing logic (Mutex.tryLock + pendingSeed) makes immediate
     * refill safe: if a song_change is already debouncing or in flight,
     * the pop_refill request coalesces with it instead of stampeding.
     */
    fun popTail(): List<Song>? {
        val taken = _tail.value
        if (taken != null) {
            _tail.value = null
            val seed = container.playback.state.value.currentMediaId
            if (!seed.isNullOrBlank()) {
                requestCompute(seed, reason = "pop_refill")
            }
        }
        return taken
    }

    /** Manual trigger — used at process start. */
    fun precomputeNow(reason: String = "manual") {
        val seed = container.playback.state.value.currentMediaId ?: return
        requestCompute(seed, reason)
    }

    private fun requestCompute(seed: String, reason: String) {
        if (computeMutex.isLocked) {
            pendingSeed = seed
            pendingReason = reason
            return
        }
        appScope.launch { runCompute(seed, reason) }
    }

    private suspend fun runCompute(initialSeed: String, initialReason: String) {
        var seed = initialSeed
        var reason = initialReason
        while (true) {
            if (!computeMutex.tryLock()) {
                pendingSeed = seed
                pendingReason = reason
                return
            }
            try {
                _computing.value = true
                val startedAt = System.currentTimeMillis()
                var tailSize = 0
                var status = "ok"
                try {
                    val tail = computeTail(seed)
                    tailSize = tail.size
                    if (tail.isNotEmpty()) {
                        _tail.value = tail
                        lastSeed = seed
                    } else {
                        status = "empty"
                    }
                } catch (ce: CancellationException) {
                    status = "cancelled"
                    throw ce
                } catch (t: Throwable) {
                    status = "error"
                    android.util.Log.w("RecommendationCache", "precompute failed: ${t.message}")
                } finally {
                    val elapsed = System.currentTimeMillis() - startedAt
                    container.activityLog.log(
                        category = "queue",
                        type = "PRECOMPUTE_DONE",
                        message = "Precompute tail for $seed (reason=$reason) size=$tailSize in ${elapsed}ms status=$status",
                        data = mapOf(
                            "elapsedMs" to elapsed,
                            "tailSize" to tailSize,
                            "seed" to seed,
                            "reason" to reason,
                            "status" to status,
                            "vecCacheSize" to container.embeddingDb.vecCacheSize,
                        ),
                    )
                    _computing.value = false
                }
            } finally {
                computeMutex.unlock()
            }

            val nextSeed = pendingSeed
            val nextReason = pendingReason
            if (nextSeed != null && nextSeed != lastSeed) {
                pendingSeed = null
                pendingReason = ""
                seed = nextSeed
                reason = "$nextReason+coalesced"
                continue
            }
            pendingSeed = null
            pendingReason = ""
            return
        }
    }

    private suspend fun computeTail(seedFilename: String): List<Song> {
        val library = container.library.value
        if (library.isEmpty()) return emptyList()
        val current = library.firstOrNull { it.filename == seedFilename } ?: return emptyList()

        val recMode = container.preferences.recMode.first()
        val tuning = container.taste.tuning.value
        val decoratedSignals = container.taste.decoratedSignals.value
        val hardBlockedFilenames = decoratedSignals.hardBlockedFilenames
        val playbackState = container.playback.state.value
        val playNextSet = playbackState.playNextFilenames
        val excludeFns = playNextSet + seedFilename

        val profileVec = runCatching { container.preferences.loadProfileVec() }.getOrNull()?.vec

        if (!recMode) {
            return library.filter { it.filePath != null && it.filename !in excludeFns }
                .shuffled().take(50)
        }

        // Bugfix 2026-06-01e: do NOT wrap embedding-DB calls in runCatching.
        // runCatching catches CancellationException, which (in 06-01d) made
        // cancelled computes silently return empty and leave the cache stale.
        // Explicit try/catch lets us re-throw cancellation while still
        // tolerating real errors.
        val blendedTriple = try {
            container.recommender.buildBlendedVec(
                currentSongHash = current.contentHash,
                sessionListened = container.session.listened.value,
                profileVec = profileVec,
                library = library,
                mode = "refresh",
                sessionBias = tuning.sessionBias,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w("RecommendationCache", "buildBlendedVec failed: ${t.message}")
            null
        }
        val blendedVec = blendedTriple?.first

        return try {
            container.recommender.recommendUpcoming(
                current, library, k = 50,
                adventurous = tuning.adventurous,
                extraExcludeFilenames = excludeFns,
                hardBlockedFilenames = hardBlockedFilenames,
                blendedQueryVec = blendedVec,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w("RecommendationCache", "recommendUpcoming failed: ${t.message}")
            emptyList()
        }
    }

    companion object {
        // 06-01f: reduced from 4000ms. With cancellation removed, the
        // 4s window was the dominant source of cache-empty failures:
        // during lockscreen, mmap pages evict in ~7s, so the precompute
        // needed to fire well before that. 1500ms gives rapid skips
        // time to coalesce (typical skip cadence is 2-3s) without
        // leaving the cache empty long enough for mmap to go cold.
        private const val DEBOUNCE_MS = 1500L
    }
}
