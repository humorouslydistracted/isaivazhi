package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteEnginePlaybackEventTest {

    @Test
    fun nextButton_skip_bumpsXScore() {
        val before = 0f
        val after = TasteEngine.nextXScoreAfterPlayback(before, fraction = 0.1f, source = "next_button")
        assertTrue(after > before)
    }

    @Test
    fun neutralSkip_doesNotBumpXScore() {
        val before = 2.0f
        val after = TasteEngine.nextXScoreAfterPlayback(before, fraction = 0.1f, source = "neutral_skip")
        assertEquals(before, after, 0.001f)
    }

    @Test
    fun fullListen_decaysXScore() {
        val before = 2.0f
        val after = TasteEngine.nextXScoreAfterPlayback(before, fraction = 0.85f, source = "auto_advance")
        assertTrue(after < before)
    }
}
