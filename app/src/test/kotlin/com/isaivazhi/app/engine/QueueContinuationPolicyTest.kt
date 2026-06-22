package com.isaivazhi.app.engine

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueContinuationPolicyTest {

    @Test
    fun shouldAppendAiTail_onlyForAiEligibleContextsWithRepeatOff() {
        assertTrue(
            QueueContinuationPolicy.shouldAppendAiTail(
                PlaybackEngine.QueueContext.LIBRARY,
                Player.REPEAT_MODE_OFF,
            )
        )
        assertTrue(
            QueueContinuationPolicy.shouldAppendAiTail(
                PlaybackEngine.QueueContext.DISCOVER_SECTION,
                Player.REPEAT_MODE_OFF,
            )
        )
        assertTrue(
            QueueContinuationPolicy.shouldAppendAiTail(
                PlaybackEngine.QueueContext.AI_RECOMMENDED,
                Player.REPEAT_MODE_OFF,
            )
        )

        assertFalse(
            QueueContinuationPolicy.shouldAppendAiTail(
                PlaybackEngine.QueueContext.ALBUM,
                Player.REPEAT_MODE_OFF,
            )
        )
        assertFalse(
            QueueContinuationPolicy.shouldAppendAiTail(
                PlaybackEngine.QueueContext.BROWSE_SECTION,
                Player.REPEAT_MODE_OFF,
            )
        )
        assertFalse(
            QueueContinuationPolicy.shouldAppendAiTail(
                PlaybackEngine.QueueContext.LIBRARY,
                Player.REPEAT_MODE_ALL,
            )
        )
        assertFalse(
            QueueContinuationPolicy.shouldAppendAiTail(
                PlaybackEngine.QueueContext.AI_RECOMMENDED,
                Player.REPEAT_MODE_ONE,
            )
        )
    }

    @Test
    fun rescueTransitionDecision_suppressesOnlySyntheticQueueEndJump() {
        val marker = QueueContinuationPolicy.RescueTransitionMarker(
            fromFilename = "last.mp3",
            toFilename = "fresh.mp3",
            createdAtMs = 1_000L,
        )

        val synthetic = QueueContinuationPolicy.rescueTransitionDecision(
            marker = marker,
            prevFilename = "last.mp3",
            nextFilename = "fresh.mp3",
            nowMs = 1_100L,
        )
        assertTrue(synthetic.suppressSignal)
        assertTrue(synthetic.clearMarker)

        val normalAdvance = QueueContinuationPolicy.rescueTransitionDecision(
            marker = marker,
            prevFilename = "fresh.mp3",
            nextFilename = "second.mp3",
            nowMs = 1_100L,
        )
        assertFalse(normalAdvance.suppressSignal)
        assertTrue(normalAdvance.clearMarker)

        val expired = QueueContinuationPolicy.rescueTransitionDecision(
            marker = marker,
            prevFilename = "last.mp3",
            nextFilename = "fresh.mp3",
            nowMs = 12_000L,
        )
        assertFalse(expired.suppressSignal)
        assertTrue(expired.clearMarker)
    }
}
