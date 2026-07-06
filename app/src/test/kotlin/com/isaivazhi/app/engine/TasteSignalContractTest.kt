package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteSignalContractTest {

    @Test
    fun playbackThresholds_useOneContractForOutcomeAndContribution() {
        assertOutcome(0.20f, PlaybackOutcome.EARLY, 0f)
        assertOutcome(0.35f, PlaybackOutcome.PARTIAL, 0.175f)
        assertOutcome(0.60f, PlaybackOutcome.LISTEN, 0.60f)
        assertOutcome(0.80f, PlaybackOutcome.STRONG, 0.80f)
    }

    @Test
    fun repeatedVeryEarlyUserExits_compoundAvoidanceAfterTheFirst() {
        val expected = listOf(0f, 0f, 0.2f, 0.5f, 1.0f, 1.5f)
        expected.forEachIndexed { count, penalty ->
            assertEquals(penalty, UnifiedTasteContract.earlyRejectionPenalty(count), 0.001f)
        }
    }

    @Test
    fun onlyUserNavigationQualifiesForVeryEarlyRejection() {
        assertTrue(UnifiedTasteContract.isUserInitiated(PlaybackEndReason.NEXT))
        assertTrue(UnifiedTasteContract.isUserInitiated(PlaybackEndReason.PREVIOUS))
        assertTrue(UnifiedTasteContract.isUserInitiated(PlaybackEndReason.SONG_TAP))
        assertTrue(UnifiedTasteContract.isUserInitiated(PlaybackEndReason.QUEUE_TAP))
        assertFalse(UnifiedTasteContract.isUserInitiated(PlaybackEndReason.AUTO_ADVANCE))
        assertFalse(UnifiedTasteContract.isUserInitiated(PlaybackEndReason.RECOVERY))
    }

    @Test
    fun serviceReasons_mapWithoutTurningRecoveryIntoAUserSkip() {
        assertEquals(PlaybackEndReason.NEXT, PlaybackEndReason.fromSource("next_button"))
        assertEquals(PlaybackEndReason.NEXT, PlaybackEndReason.fromSource("user_next"))
        assertEquals(PlaybackEndReason.NEXT, PlaybackEndReason.fromSource("neutral_skip"))
        assertEquals(PlaybackEndReason.PREVIOUS, PlaybackEndReason.fromSource("prev_button"))
        assertEquals(PlaybackEndReason.PREVIOUS, PlaybackEndReason.fromSource("user_prev"))
        assertEquals(PlaybackEndReason.QUEUE_TAP, PlaybackEndReason.fromSource("queue_tap"))
        assertEquals(
            PlaybackEndReason.RECOVERY,
            PlaybackEndReason.fromSource("background_recovery_merged_task_removed"),
        )
    }

    @Test
    fun rankingUsesEightyTwentyBlendAndMonotonicNegativeGuard() {
        assertEquals(
            0.70f,
            UnifiedTasteContract.finalRelevance(
                audioRelevance = 0.75f,
                totalScore = 2f,
                negativeGuard = 0.5f,
            ),
            0.001f,
        )
        val lowGuard = UnifiedTasteContract.finalRelevance(0.75f, -2f, 0.25f)
        val highGuard = UnifiedTasteContract.finalRelevance(0.75f, -2f, 1f)
        assertTrue(highGuard < lowGuard)
        assertFalse(UnifiedTasteContract.isLearnedNegativeBlocked(-0.4f, 0.5f))
        assertTrue(UnifiedTasteContract.isLearnedNegativeBlocked(-0.4f, 1f))
    }

    @Test
    fun learnedEvidenceHasAThirtyDayHalfLifeBeforeNewEvidenceIsAdded() {
        val now = 100L * TasteEngine.PROFILE_DAY_MS
        val thirtyDaysAgo = now - 30L * TasteEngine.PROFILE_DAY_MS
        assertEquals(
            1f,
            decayedLearnedValue(
                value = 2f,
                updatedAt = thirtyDaysAgo,
                fallbackAt = 0L,
                now = now,
            ),
            0.001f,
        )
    }

    private fun assertOutcome(
        fraction: Float,
        expectedOutcome: PlaybackOutcome,
        expectedContribution: Float,
    ) {
        assertEquals(expectedOutcome, UnifiedTasteContract.outcomeFor(fraction))
        assertEquals(
            expectedContribution,
            UnifiedTasteContract.positiveContribution(fraction),
            0.001f,
        )
    }
}
