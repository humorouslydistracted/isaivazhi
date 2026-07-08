package com.isaivazhi.app.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RecommendationRefreshCoordinator(
    private val container: AppContainer,
    private val appScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val requestGuard = Any()

    /**
     * Bugfix 2026-07-08: a refresh request that arrived while another was
     * already running used to be silently dropped (`if (refreshBusy) return`).
     * That's harmless for a single Diversity tap, but the Blend control is a
     * two-thumb RangeSlider users tend to drag several times in quick
     * succession while comparing splits — each of those intermediate
     * requests could land in the busy window and vanish, even though the UI
     * had already shown a "Blend applied" toast. Instead of dropping, we now
     * remember the latest pending reason and the in-flight run picks it up
     * immediately after finishing, mirroring the coalescing pattern already
     * used by [RecommendationCache].
     */
    @Volatile private var pendingReason: String? = null

    private val _lastBlendInfo = MutableStateFlow<RecommendationBlendInfo?>(null)
    val lastBlendInfo: StateFlow<RecommendationBlendInfo?> = _lastBlendInfo.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            container.playback.refreshRequests.collect {
                container.activityLog.log(
                    category = "notification",
                    type = "LOCKSCREEN_REFRESH_TAP",
                    message = "Lockscreen/notification Refresh tap received by coordinator",
                )
                requestRefresh(reason = "notification")
            }
        }
    }

    fun requestRefresh(reason: String = "manual") {
        if (!container.startup.refreshReady.value) {
            container.activityLog.log(
                category = "queue",
                type = "REFRESH_NOT_READY",
                message = "Refresh ignored while startup is ${container.startup.phase.value}",
                data = mapOf("reason" to reason),
            )
            if (reason != "notification") {
                container.toaster.show("AI is getting ready…")
            }
            return
        }
        synchronized(requestGuard) {
            if (container.playback.refreshBusy.value) {
                pendingReason = reason
                android.util.Log.d(
                    "BlendDebug",
                    "requestRefresh reason=$reason coalesced (busy) pendingReason=$pendingReason",
                )
                return
            }
            container.playback.setRefreshBusy(true)
        }
        android.util.Log.d("BlendDebug", "requestRefresh reason=$reason launching runRefreshLoop")
        appScope.launch(Dispatchers.Default) {
            runRefreshLoop(reason)
        }
    }

    /**
     * Runs [refreshUpcomingWithAI] for [initialReason], then keeps going for
     * any reason that got coalesced in via [pendingReason] while that run
     * was in flight. `refreshBusy` only clears once there's no more pending
     * work, so the UI's busy indicator stays accurate across the whole
     * coalesced chain instead of flickering between runs.
     */
    private suspend fun runRefreshLoop(initialReason: String) {
        var reason = initialReason
        // Only true once we've cleared refreshBusy ourselves via the normal
        // synchronized release below. Used by the `finally` guard so it
        // never force-clears a *different*, legitimately-busy request that
        // another thread may have started in the tiny window right after
        // our own normal release (see comment on `finally` below).
        var releasedNormally = false
        try {
            while (true) {
                android.util.Log.d("BlendDebug", "runRefreshLoop iteration start reason=$reason")
                try {
                    refreshUpcomingWithAI(reason)
                    android.util.Log.d("BlendDebug", "runRefreshLoop iteration done reason=$reason")
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    // Must not be swallowed: rethrowing preserves structured
                    // concurrency. The outer finally below still guarantees
                    // refreshBusy is released even on cancellation.
                    throw ce
                } catch (t: Throwable) {
                    android.util.Log.w("RecommendationRefreshCoordinator", "refresh failed: ${t.message}", t)
                }
                val next = synchronized(requestGuard) {
                    val p = pendingReason
                    pendingReason = null
                    if (p == null) container.playback.setRefreshBusy(false)
                    p
                }
                android.util.Log.d(
                    "BlendDebug",
                    "runRefreshLoop coalesce-check pendingReason=$next -> " +
                        if (next != null) "continuing" else "releasing refreshBusy",
                )
                if (next == null) {
                    releasedNormally = true
                    return
                }
                reason = next
            }
        } finally {
            // Belt-and-suspenders: if this coroutine is cancelled (or an
            // unexpected exception escapes) before the normal release point
            // above ran, don't leave refreshBusy permanently stuck true —
            // that would silently swallow every future requestRefresh()
            // call forever (they'd just keep overwriting pendingReason with
            // nobody left to read it), which looks exactly like "the slider
            // stopped refreshing". Gated on `!releasedNormally` so this
            // never clobbers a different, legitimately-busy request that
            // started immediately after our own normal release.
            if (!releasedNormally && container.playback.refreshBusy.value) {
                android.util.Log.w(
                    "BlendDebug",
                    "runRefreshLoop exiting abnormally with refreshBusy still true — force-clearing to avoid a stuck queue",
                )
                container.playback.setRefreshBusy(false)
            }
        }
    }

    private suspend fun refreshUpcomingWithAI(reason: String) {
        val playbackState = container.playback.state.value
        if (QueueContinuationPolicy.shouldForceRepeatOffForContinuation(
                playbackState.queueContext,
                playbackState.repeatMode,
            )
        ) {
            container.playback.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_OFF)
        }
        val mediaId = playbackState.currentMediaId
        if (mediaId == null) {
            android.util.Log.w("BlendDebug", "refreshUpcomingWithAI reason=$reason aborted: currentMediaId is null")
            return
        }
        val songs = container.library.value
        val current = songs.firstOrNull { it.filename == mediaId }
        if (current == null) {
            android.util.Log.w(
                "BlendDebug",
                "refreshUpcomingWithAI reason=$reason aborted: mediaId=$mediaId not found in library " +
                    "(librarySize=${songs.size})",
            )
            return
        }
        val tuningAtStart = container.taste.tuning.value
        android.util.Log.d(
            "BlendDebug",
            "refreshUpcomingWithAI reason=$reason mediaId=$mediaId adventurous=${tuningAtStart.adventurous} " +
                "manualBlend=${tuningAtStart.manualBlend} revision=${container.recommendationInputRevisions.currentRevision()}",
        )

        val cachedTail = container.recommendationCache.popTail()
        if (cachedTail != null) {
            cachedTail.blendInfo?.let {
                _lastBlendInfo.value = it
                container.recommendationExplainability.recordBlend(it.weights, "Queue refreshed")
            }
            val finalResult = buildFinalHardRefreshResult(
                candidateTail = cachedTail.tail,
                songs = songs,
                playbackState = playbackState,
                currentMediaId = mediaId,
            )
            container.activityLog.log(
                category = "queue",
                type = "REFRESH_CACHE_HIT",
                message = "Served upcoming from RecommendationCache (size=${cachedTail.tail.size}, afterPolicy=${finalResult.policyFilteredTail.size}, afterCooldown=${finalResult.cooldownFilteredTail.size}, final=${finalResult.finalTail.size})",
                data = mapOf(
                    "tailSize" to cachedTail.tail.size,
                    "policyFilteredSize" to finalResult.policyFilteredTail.size,
                    "afterCooldownSize" to finalResult.cooldownFilteredTail.size,
                    "finalTailSize" to finalResult.finalTail.size,
                    "mediaId" to mediaId,
                    "reason" to reason,
                    "revision" to cachedTail.revision,
                ),
            )
            container.toaster.show("Up Next refreshed")
            container.playback.replaceUpcoming(
                newUpcoming = finalResult.finalUpcoming,
                newContext = PlaybackEngine.QueueContext.AI_RECOMMENDED,
            )
            val tuning = container.taste.tuning.value
            val blendedDetails = runCatching {
                container.recommender.buildBlendedQuery(
                    currentSongHash = current.contentHash,
                    sessionListened = container.session.listened.value,
                    profileVec = runCatching { container.preferences.loadProfileVec() }.getOrNull()?.vec,
                    library = songs,
                    mode = "refresh",
                    overallScores = container.taste.overallScoresByFilename(),
                    manualBlend = tuning.manualBlend,
                    manualCurrentWeight = tuning.manualCurrentWeight,
                    manualSessionWeight = tuning.manualSessionWeight,
                )
            }.getOrNull()
            if (blendedDetails != null) {
                container.publishRecommendationExplanations(
                    songs = finalResult.finalTail,
                    details = blendedDetails,
                    negativeFilteredCount = container.taste
                        .hardBlockedFilenamesForPolicy(tuning.negativeStrength).size,
                )
            }
            return
        }

        val embStatus = container.embedding.status.value
        if (embStatus.inProgress && embStatus.total > 0) {
            container.toaster.show(
                "AI is still indexing (${embStatus.processed}/${embStatus.total}) - refresh uses what's ready."
            )
        }

        val dispatchStartedAt = android.os.SystemClock.elapsedRealtime()
        container.activityLog.log(
            category = "queue",
            type = "REFRESH_DISPATCH_START",
            message = "refreshUpcomingWithAI body dispatched",
            data = mapOf(
                "dispatchLatencyMs" to 0L,
                "mediaId" to mediaId,
                "reason" to reason,
            ),
        )
        val recommendStartedAt = android.os.SystemClock.elapsedRealtime()
        val playNextSet = playbackState.playNextFilenames
        val tuning = container.taste.tuning.value
        val recMode = container.preferences.recMode.first()
        val embeddingRows = container.embeddingDb.vecCacheSize.takeIf { it > 0 }
            ?: runCatching { container.preferences.cachedEmbedStats().rowCount }.getOrDefault(0)
        val byFn = songs.associateBy { it.filename }
        val playNextSongs = playNextSet.mapNotNull { byFn[it] }
        val excludeFns = playNextSet + mediaId
        val blendedDetails = runCatching {
            container.recommender.buildBlendedQuery(
                currentSongHash = current.contentHash,
                sessionListened = container.session.listened.value,
                profileVec = runCatching { container.preferences.loadProfileVec() }.getOrNull()?.vec,
                library = songs,
                mode = "refresh",
                overallScores = container.taste.overallScoresByFilename(),
                manualBlend = tuning.manualBlend,
                manualCurrentWeight = tuning.manualCurrentWeight,
                manualSessionWeight = tuning.manualSessionWeight,
            )
        }.getOrNull()
        val blendInfo = blendedDetails?.let { RecommendationBlendInfo(it.weights, it.label) }
        blendInfo?.let {
            _lastBlendInfo.value = it
            container.recommendationExplainability.recordBlend(it.weights, "Queue refreshed")
        }
        val blendedVec = blendedDetails?.queryVector
        val recentlySurfaced = container.recentlySurfacedTracker.recentlySurfaced()
        val excludeFnsWithRecency = QueueContinuationPolicy.recommendExcludeFilenames(
            playNextFilenames = playNextSet,
            currentFilename = mediaId,
            recentlySurfaced = recentlySurfaced,
        )
        val newTail = if (recMode && embeddingRows > 0) {
            runCatching {
                container.recommender.recommendUpcoming(
                    currentSong = current,
                    library = songs,
                    k = 50,
                    adventurous = tuning.adventurous,
                    extraExcludeFilenames = excludeFnsWithRecency,
                    hardBlockedFilenames = container.taste.hardBlockedFilenamesForPolicy(tuning.negativeStrength),
                    blendedQueryVec = blendedVec,
                    totalTasteScores = container.taste.overallScoresByFilename(),
                    negativeGuard = tuning.negativeStrength,
                    tasteInfluence = tuning.tasteInfluence,
                    traceReason = "refresh",
                )
            }.getOrDefault(emptyList())
        } else {
            songs.filter { it.filePath != null && it.filename !in excludeFnsWithRecency }
                .shuffled()
                .take(50)
        }
        val recommendElapsedMs = android.os.SystemClock.elapsedRealtime() - recommendStartedAt
        container.activityLog.log(
            category = "queue",
            type = "REFRESH_RECOMMEND_DONE",
            message = "recommendUpcoming returned ${newTail.size} tracks",
            data = mapOf(
                "elapsedMs" to recommendElapsedMs,
                "tailSize" to newTail.size,
                "blend" to (blendInfo?.label ?: "n/a"),
                "reason" to reason,
            ),
        )
        val finalResult = buildFinalHardRefreshResult(
            candidateTail = newTail,
            songs = songs,
            playbackState = playbackState,
            currentMediaId = mediaId,
        )
        container.toaster.show("Up Next refreshed (blend=${blendInfo?.label ?: "current"})")
        val replaceStartedAt = android.os.SystemClock.elapsedRealtime()
        container.playback.replaceUpcoming(
            newUpcoming = finalResult.finalUpcoming,
            newContext = PlaybackEngine.QueueContext.AI_RECOMMENDED,
        )
        if (recMode && blendedDetails != null) {
            container.publishRecommendationExplanations(
                songs = finalResult.finalTail,
                details = blendedDetails,
                negativeFilteredCount = container.taste
                    .hardBlockedFilenamesForPolicy(tuning.negativeStrength).size,
            )
        }
        container.activityLog.log(
            category = "queue",
            type = "REFRESH_REPLACE_DONE",
            message = "replaceUpcoming done",
            data = mapOf(
                "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - replaceStartedAt),
                "finalUpcomingSize" to finalResult.finalUpcoming.size,
                "reason" to reason,
                "dispatchStartedAt" to dispatchStartedAt,
            ),
        )
    }

    private fun buildFinalHardRefreshResult(
        candidateTail: List<Song>,
        songs: List<Song>,
        playbackState: PlaybackEngine.PlaybackState,
        currentMediaId: String,
    ): RecommendationRefreshFinalizer.HardRefreshResult {
        val byFn = songs.associateBy { it.filename }
        val playNextSet = playbackState.playNextFilenames
        val playNextSongs = playNextSet.mapNotNull { byFn[it] }
        val tuning = container.taste.tuning.value
        val policyExcludes =
            container.taste.hardBlockedFilenamesForPolicy(tuning.negativeStrength)
        val cooldownFilenames = container.recentlySurfacedTracker.recentlySurfaced()
        return RecommendationRefreshFinalizer.finalizeHardRefresh(
            candidateTail = candidateTail,
            playNextSongs = playNextSongs,
            playNextFilenames = playNextSet,
            currentFilename = currentMediaId,
            policyExcludes = policyExcludes,
            cooldownFilenames = cooldownFilenames,
        )
    }
}
