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
    fun rankingUsesConfiguredTasteInfluenceAndMonotonicNegativeGuard() {
        assertEquals(
            0.675f,
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
    fun tasteInfluenceSupportsZeroTwentyThirtyAndFiftyPercent() {
        val audio = 0.8f
        val overall = 2f
        val expected = mapOf(
            0f to 0.8f,
            0.2f to 0.74f,
            0.3f to 0.71f,
            0.5f to 0.65f,
        )
        expected.forEach { (influence, relevance) ->
            assertEquals(
                relevance,
                UnifiedTasteContract.finalRelevance(
                    audioRelevance = audio,
                    totalScore = overall,
                    negativeGuard = 0.5f,
                    tasteInfluence = influence,
                ),
                0.001f,
            )
        }
    }

    @Test
    fun negativeGuardThresholdMatchesTooltipExamples() {
        assertTrue(UnifiedTasteContract.isLearnedNegativeBlocked(-0.25f, 1f))
        assertTrue(UnifiedTasteContract.isLearnedNegativeBlocked(-0.50f, 0.5f))
        assertTrue(UnifiedTasteContract.isLearnedNegativeBlocked(-1.0f, 0.25f))
        assertFalse(UnifiedTasteContract.isLearnedNegativeBlocked(-10f, 0f))
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

    @Test
    fun profileAnchorScore_blendsFavoriteAndBehaviorWithoutLettingFavoriteDominate() {
        val now = 100L * TasteEngine.PROFILE_DAY_MS
        val favoriteOnly = profileScore(
            signal = TasteEngine.TasteSignal(
                isFavorite = true,
                favoritePrior = TasteEngine.MANUAL_FAVORITE_PRIOR_BASE,
            ),
            now = now,
        )
        val strongListened = profileScore(
            signal = TasteEngine.TasteSignal(
                positiveEvidence = UnifiedTasteContract.TASTE_SCORE_NORMALIZER,
                positiveEvidenceUpdatedAt = now,
                lastUpdatedAt = now,
            ),
            now = now,
        )
        val favoriteAndListened = profileScore(
            signal = TasteEngine.TasteSignal(
                isFavorite = true,
                favoritePrior = TasteEngine.MANUAL_FAVORITE_PRIOR_BASE,
                positiveEvidence = UnifiedTasteContract.TASTE_SCORE_NORMALIZER,
                positiveEvidenceUpdatedAt = now,
                lastUpdatedAt = now,
            ),
            now = now,
        )

        assertEquals(0.40f, favoriteOnly, 0.001f)
        assertEquals(0.60f, strongListened, 0.001f)
        assertEquals(1.00f, favoriteAndListened, 0.001f)
        assertTrue(strongListened > favoriteOnly)
        assertTrue(favoriteAndListened > strongListened)
    }

    @Test
    fun profileAnchorScore_usesOnlyPositiveSimilarityAndRejectsNegativeTaste() {
        val now = 100L * TasteEngine.PROFILE_DAY_MS
        val positiveSimilarity = profileScore(
            signal = TasteEngine.TasteSignal(
                positiveEvidence = 2f,
                positiveEvidenceUpdatedAt = now,
                similarityBoost = 0.5f,
                similarityBoostUpdatedAt = now,
                lastUpdatedAt = now,
            ),
            now = now,
        )
        val negativeSimilarity = profileScore(
            signal = TasteEngine.TasteSignal(
                positiveEvidence = 2f,
                positiveEvidenceUpdatedAt = now,
                similarityBoost = -0.5f,
                similarityBoostUpdatedAt = now,
                lastUpdatedAt = now,
            ),
            now = now,
        )
        val avoidedDespitePositiveSimilarity = profileScore(
            signal = TasteEngine.TasteSignal(
                positiveEvidence = 1f,
                positiveEvidenceUpdatedAt = now,
                xScore = 1.2f,
                xScoreUpdatedAt = now,
                similarityBoost = 0.5f,
                similarityBoostUpdatedAt = now,
                lastUpdatedAt = now,
            ),
            now = now,
        )

        assertEquals(0.375f, positiveSimilarity, 0.001f)
        assertEquals(0.30f, negativeSimilarity, 0.001f)
        assertEquals(0f, avoidedDespitePositiveSimilarity, 0.001f)
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

    private fun profileScore(signal: TasteEngine.TasteSignal, now: Long): Float {
        val breakdown = computeUnifiedScore(
            positiveEvidence = signal.positiveEvidence,
            positiveEvidenceUpdatedAt = signal.positiveEvidenceUpdatedAt,
            xScore = signal.xScore,
            favoritePrior = signal.favoritePrior,
            dislikePrior = signal.dislikePrior,
            similarityBoost = signal.similarityBoost,
            xScoreUpdatedAt = signal.xScoreUpdatedAt,
            similarityBoostUpdatedAt = signal.similarityBoostUpdatedAt,
            lastUpdatedAt = signal.lastUpdatedAt,
            now = now,
        )
        return profileAnchorScore(signal, breakdown)
    }
}
