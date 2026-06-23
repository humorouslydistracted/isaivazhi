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
        synchronized(requestGuard) {
            if (container.playback.refreshBusy.value) return
            container.playback.setRefreshBusy(true)
        }
        appScope.launch(Dispatchers.Default) {
            try {
                refreshUpcomingWithAI(reason)
            } finally {
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
        val mediaId = playbackState.currentMediaId ?: return
        val songs = container.library.value
        val current = songs.firstOrNull { it.filename == mediaId } ?: return

        val cachedTail = container.recommendationCache.popTail()
        if (cachedTail != null) {
            cachedTail.blendInfo?.let { _lastBlendInfo.value = it }
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
        val embeddingRows = runCatching { container.embeddingDb.rowCount() }.getOrDefault(0)
        val byFn = songs.associateBy { it.filename }
        val playNextSongs = playNextSet.mapNotNull { byFn[it] }
        val excludeFns = playNextSet + mediaId
        val blendedTriple = runCatching {
            container.recommender.buildBlendedVec(
                currentSongHash = current.contentHash,
                sessionListened = container.session.listened.value,
                profileVec = runCatching { container.preferences.loadProfileVec() }.getOrNull()?.vec,
                library = songs,
                mode = "refresh",
                sessionBias = tuning.sessionBias,
            )
        }.getOrNull()
        val blendInfo = blendedTriple?.let { RecommendationBlendInfo(it.second, it.third) }
        blendInfo?.let { _lastBlendInfo.value = it }
        val blendedVec = blendedTriple?.first
        val recentlySurfaced = container.recentlySurfacedTracker.recentlySurfaced()
        val excludeFnsWithRecency = excludeFns + recentlySurfaced
        val newTail = if (recMode && embeddingRows > 0) {
            runCatching {
                container.recommender.recommendUpcoming(
                    currentSong = current,
                    library = songs,
                    k = 50,
                    adventurous = tuning.adventurous,
                    extraExcludeFilenames = excludeFnsWithRecency,
                    hardBlockedFilenames = container.taste.hardBlockedFilenamesForPolicy(tuning.negativeStrength),
                    dislikedFilenames = container.disliked.disliked.value,
                    softExcludedFilenames = container.taste.softExcludedFilenamesForPolicy(tuning.negativeStrength),
                    blendedQueryVec = blendedVec,
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
        val policyExcludes = RecommendationPolicy.unionExcludes(
            container.taste.hardBlockedFilenamesForPolicy(tuning.negativeStrength),
            container.taste.softExcludedFilenamesForPolicy(tuning.negativeStrength),
            container.disliked.disliked.value,
        )
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
