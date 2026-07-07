package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteEngineHardBlockTest {

    private val scores = mapOf(
        "a" to -3.0f,
        "b" to -2.5f,
        "c" to -2.0f,
        "d" to -1.0f,
        "e" to 1.0f,
    )

    @Test
    fun strengthZero_returnsEmpty() {
        assertTrue(RecommendationPolicy.hardBlockedFilenames(scores, 0f).isEmpty())
    }

    @Test
    fun strengthOne_matchesFullPolicyCap() {
        val blocked = RecommendationPolicy.hardBlockedFilenames(scores, 1f)
        val full = RecommendationPolicy.hardBlockedFilenames(scores, 1f)
        assertEquals(full, blocked)
        assertTrue("a" in blocked)
        assertTrue("e" !in blocked)
    }

    @Test
    fun scaledStrength_reducesBlockedCount() {
        val full = RecommendationPolicy.hardBlockedFilenames(scores, 1f)
        val half = RecommendationPolicy.hardBlockedFilenames(scores, 0.5f)
        assertTrue(half.size <= full.size)
    }

    /** Disliked-neighbor halos move overall score, not directScore — policy must use score. */
    @Test
    fun overallScore_negativeFromSimilarity_only_canBlockAtFullGuard() {
        val overall = mapOf("halo" to -0.30f)
        val directOnly = mapOf("halo" to 0f)
        assertTrue("halo" in RecommendationPolicy.hardBlockedFilenames(overall, 1f))
        assertTrue("halo" !in RecommendationPolicy.hardBlockedFilenames(directOnly, 1f))
    }
}
