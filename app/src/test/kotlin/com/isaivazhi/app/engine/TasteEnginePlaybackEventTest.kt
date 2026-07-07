package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteEnginePlaybackEventTest {

    @Test
    fun nextButton_doesNotAddAvoidanceByItself() {
        val before = 2f
        val after = TasteEngine.nextXScoreAfterPlayback(before, fraction = 0.1f, source = "next_button")
        assertEquals(before, after, 0.001f)
    }

    @Test
    fun recoveredEarlyEnding_doesNotAddAvoidance() {
        val before = 2.0f
        val after = TasteEngine.nextXScoreAfterPlayback(
            before,
            fraction = 0.1f,
            source = "background_recovery",
        )
        assertEquals(before, after, 0.001f)
    }

    @Test
    fun strongListen_repairsAvoidance() {
        val before = 2.0f
        val after = TasteEngine.nextXScoreAfterPlayback(before, fraction = 0.85f, source = "auto_advance")
        assertTrue(after < before)
    }

    @Test
    fun nextButton_belowEarlyThreshold_countsAsEarlyUserRejection() {
        assertTrue(TasteEngine.isEarlyUserRejection("next_button", 0.1f))
    }

    @Test
    fun songTap_belowEarlyThreshold_doesNotCountAsEarlyUserRejection() {
        assertFalse(TasteEngine.isEarlyUserRejection("song_tap", 0.1f))
        assertFalse(TasteEngine.isExplicitUserSkip("song_tap", 0.1f))
    }

    @Test
    fun queueTap_belowEarlyThreshold_doesNotCountAsEarlyUserRejection() {
        assertFalse(TasteEngine.isEarlyUserRejection("queue_tap", 0.1f))
        assertFalse(TasteEngine.isExplicitUserSkip("queue_tap", 0.1f))
    }
}
