package com.isaivazhi.app.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentlySurfacedPolicyTest {

    private val aiState = PlaybackEngine.PlaybackState(
        queueContext = PlaybackEngine.QueueContext.AI_RECOMMENDED,
    )

    @Test
    fun aiSongsEnteringThroughAutoNextAndPlayNextEnterCooldown() {
        assertTrue(shouldRecordRecommendationCooldown("auto", aiState, "auto_advance"))
        assertTrue(shouldRecordRecommendationCooldown("next", aiState, "next_button"))
        assertTrue(shouldRecordRecommendationCooldown("play-next", aiState, "play_next"))
        assertTrue(shouldRecordRecommendationCooldown("prev", aiState, "history_prev"))
        assertTrue(
            shouldRecordRecommendationCooldown(
                "play-next",
                aiState.copy(
                    queueContext = PlaybackEngine.QueueContext.ALBUM,
                    playNextFilenames = setOf("play-next"),
                ),
                "auto_advance",
            )
        )
    }

    @Test
    fun anyActualPlaybackEntryEntersCooldown_exceptRecoveryOnlyTransitions() {
        assertTrue(shouldRecordRecommendationCooldown("manual", aiState, "manual_tap"))
        assertTrue(
            shouldRecordRecommendationCooldown(
                "album",
                aiState.copy(queueContext = PlaybackEngine.QueueContext.ALBUM),
                "album",
            )
        )
        assertTrue(
            shouldRecordRecommendationCooldown(
                "browse",
                aiState.copy(queueContext = PlaybackEngine.QueueContext.BROWSE_SECTION),
                "browse_section",
            )
        )
        assertTrue(
            shouldRecordRecommendationCooldown(
                "queue-jump",
                aiState,
                "queue_tap",
            )
        )
        assertFalse(
            shouldRecordRecommendationCooldown(
                "recovery",
                aiState,
                "background_recovery",
            )
        )
        assertFalse(shouldRecordRecommendationCooldown("unknown", aiState, "dismiss"))
    }
}
