package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationExplainabilityTest {

    @Test
    fun blendIntervalsChangeOnlyWhenAppliedWholePercentagesChange() {
        val engine = RecommendationExplainabilityEngine()
        val first = Recommender.BlendWeights(0.30f, 0.45f, 0.25f)
        engine.recordBlend(first, reason = "Queue generated", now = 1_000L)
        engine.recordBlend(first, reason = "Same blend", now = 1_500L)
        engine.recordPlaybackResult()

        assertEquals(1, engine.blendIntervals.value.size)
        assertEquals(1, engine.blendIntervals.value.first().playbackResults)

        val second = Recommender.BlendWeights(0.20f, 0.55f, 0.25f)
        engine.recordBlend(second, reason = "Session changed", now = 2_000L)

        val intervals = engine.blendIntervals.value
        assertEquals(2, intervals.size)
        assertEquals(20, intervals[0].currentPercent)
        assertEquals(1_000L, intervals[1].startedAt)
        assertEquals(2_000L, intervals[1].endedAt)
        assertEquals(1, intervals[1].playbackResults)
    }

    @Test
    fun blendHistoryKeepsLatestTwentyIntervals() {
        val engine = RecommendationExplainabilityEngine()
        repeat(25) { index ->
            engine.recordBlend(
                Recommender.BlendWeights(
                    wCurrent = (index + 1).toFloat(),
                    wSession = (25 - index).toFloat(),
                    wProfile = 1f,
                ),
                reason = "Queue generated",
                now = index.toLong(),
            )
        }
        assertEquals(20, engine.blendIntervals.value.size)
    }

    @Test
    fun explanationIdentifiesDominantAndDiversityInfluence() {
        val sessionLed = RecommendationExplanation(
            filename = "session-song",
            currentContribution = 0.1f,
            sessionContribution = 0.7f,
            profileContribution = 0.2f,
            relevanceRank = 2,
            finalRank = 2,
        )
        assertEquals(RecommendationInfluence.SESSION, sessionLed.dominantInfluence)
        assertEquals(Triple(10, 70, 20), sessionLed.normalizedBlendContributions())
        assertFalse(sessionLed.isDiversePick)

        val diverse = sessionLed.copy(relevanceRank = 8, finalRank = 3)
        assertEquals(RecommendationInfluence.DIVERSITY, diverse.dominantInfluence)
        assertTrue(diverse.isDiversePick)
    }

    @Test
    fun explanationIdsRemainStableWhenAnItemIsReordered() {
        val original = RecommendationExplanation(
            queueGenerationId = "generation-1",
            itemId = "item-7",
            filename = "song",
            finalRank = 7,
        )
        val reordered = original.copy(finalRank = 2)
        assertEquals("generation-1", reordered.queueGenerationId)
        assertEquals("item-7", reordered.itemId)
    }

    @Test
    fun finalRelevanceRespectsTasteInfluenceSlider() {
        val audio = 0.8f
        val score = 1f
        val low = UnifiedTasteContract.finalRelevance(
            audioRelevance = audio,
            totalScore = score,
            negativeGuard = 0.5f,
            tasteInfluence = 0.30f,
        )
        val high = UnifiedTasteContract.finalRelevance(
            audioRelevance = audio,
            totalScore = score,
            negativeGuard = 0.5f,
            tasteInfluence = 0.50f,
        )
        assertTrue(low != high)
        assertEquals(
            UnifiedTasteContract.tasteAdjustment(score, 0.5f, 0.30f),
            low - (1f - 0.30f) * audio,
            0.001f,
        )
        assertEquals(
            UnifiedTasteContract.tasteAdjustment(score, 0.5f, 0.50f),
            high - (1f - 0.50f) * audio,
            0.001f,
        )
    }
}
