package com.isaivazhi.app.engine

import androidx.media3.common.Player

/**
 * Pure rules for keeping an AI-eligible queue alive at the end.
 */
object QueueContinuationPolicy {
    private const val RESCUE_SUPPRESSION_WINDOW_MS = 10_000L

    data class RescueTransitionMarker(
        val fromFilename: String?,
        val toFilename: String,
        val createdAtMs: Long,
    )

    data class RescueTransitionDecision(
        val suppressSignal: Boolean,
        val clearMarker: Boolean,
    )

    fun shouldAppendAiTail(
        queueContext: PlaybackEngine.QueueContext,
        repeatMode: Int,
    ): Boolean {
        if (repeatMode != Player.REPEAT_MODE_OFF) return false
        return isAiEligibleQueueContext(queueContext)
    }

    fun isAiEligibleQueueContext(queueContext: PlaybackEngine.QueueContext): Boolean =
        queueContext != PlaybackEngine.QueueContext.ALBUM &&
            queueContext != PlaybackEngine.QueueContext.BROWSE_SECTION

    /** Loop must be off for AI tail append; Refresh / queue-end paths force this. */
    fun shouldForceRepeatOffForContinuation(
        queueContext: PlaybackEngine.QueueContext,
        repeatMode: Int,
    ): Boolean = repeatMode != Player.REPEAT_MODE_OFF && isAiEligibleQueueContext(queueContext)

    /**
     * Filenames passed to [Recommender.recommendUpcoming] when extending the
     * queue at the end. Matches the Refresh path: current + Play Next +
     * cooldown — not the full playback queue (deduped at append time).
     */
    fun recommendExcludeFilenames(
        playNextFilenames: Set<String>,
        currentFilename: String,
        recentlySurfaced: Set<String>,
    ): Set<String> = playNextFilenames + currentFilename + recentlySurfaced

    fun rescueTransitionDecision(
        marker: RescueTransitionMarker?,
        prevFilename: String?,
        nextFilename: String?,
        nowMs: Long,
    ): RescueTransitionDecision {
        if (marker == null) return RescueTransitionDecision(false, false)
        if (nowMs - marker.createdAtMs > RESCUE_SUPPRESSION_WINDOW_MS) {
            return RescueTransitionDecision(false, true)
        }
        val matchesSyntheticJump =
            !marker.fromFilename.isNullOrBlank() &&
                prevFilename == marker.fromFilename &&
                nextFilename == marker.toFilename &&
                prevFilename != nextFilename
        val markerHasBeenReached = nextFilename == marker.toFilename ||
            prevFilename == marker.toFilename
        return RescueTransitionDecision(
            suppressSignal = matchesSyntheticJump,
            clearMarker = matchesSyntheticJump || markerHasBeenReached,
        )
    }
}
