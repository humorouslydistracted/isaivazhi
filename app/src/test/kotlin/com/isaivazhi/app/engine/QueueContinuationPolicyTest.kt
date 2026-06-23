package com.isaivazhi.app.engine

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
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
    fun shouldForceRepeatOffForContinuation_whenLoopOnAndAiEligible() {
        assertTrue(
            QueueContinuationPolicy.shouldForceRepeatOffForContinuation(
                PlaybackEngine.QueueContext.LIBRARY,
                Player.REPEAT_MODE_ALL,
            )
        )
        assertFalse(
            QueueContinuationPolicy.shouldForceRepeatOffForContinuation(
                PlaybackEngine.QueueContext.ALBUM,
                Player.REPEAT_MODE_ALL,
            )
        )
        assertFalse(
            QueueContinuationPolicy.shouldForceRepeatOffForContinuation(
                PlaybackEngine.QueueContext.LIBRARY,
                Player.REPEAT_MODE_OFF,
            )
        )
    }

    @Test
    fun recommendExcludeFilenames_matchesRefreshPathNotFullQueue() {
        val queueHistory = (1..50).map { "played_$it.mp3" }.toSet()
        val playNext = setOf("play_next.mp3")
        val current = "current.mp3"
        val cooldown = setOf("cooldown.mp3")

        val refreshStyle = QueueContinuationPolicy.recommendExcludeFilenames(
            playNextFilenames = playNext,
            currentFilename = current,
            recentlySurfaced = cooldown,
        )
        val queueWide = queueHistory + cooldown

        assertTrue("played_1.mp3" in queueWide)
        assertFalse("played_1.mp3" in refreshStyle)
        assertTrue(current in refreshStyle)
        assertTrue("play_next.mp3" in refreshStyle)
        assertTrue("cooldown.mp3" in refreshStyle)
        assertEquals(3, refreshStyle.size)
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
