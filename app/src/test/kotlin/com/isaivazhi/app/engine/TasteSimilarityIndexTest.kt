package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteSimilarityIndexTest {

    @Test
    fun outboundSimilarityIndex_groupsNeighborsBySourceAndSortsByMagnitude() {
        val signals = mapOf(
            "neighbor_a" to signalWithContributor("source_song", 0.09f),
            "neighbor_b" to signalWithContributor("source_song", -0.30f),
            "neighbor_c" to signalWithContributor("source_song", 0.18f),
            "neighbor_d" to signalWithContributor("other_source", 0.11f),
        )

        val outbound = buildOutboundSimilarityIndex(signals)
        val sourceImpacts = outbound.getValue("source_song")

        assertEquals(listOf("neighbor_b", "neighbor_c", "neighbor_a"), sourceImpacts.map { it.targetFilename })
        assertEquals(listOf(-0.30f, 0.18f, 0.09f), sourceImpacts.map { it.amount })
    }

    @Test
    fun negativePropagation_keepsNeighborContributionsNonPositive() {
        val negativeDelta = -0.50f
        val weights = TasteEngine.SIMILARITY_NEIGHBOR_WEIGHTS.take(3)

        val signals = weights.mapIndexed { index, weight ->
            "neighbor_$index" to signalWithContributor(
                sourceFilename = "negative_source",
                amount = negativeDelta * weight,
            )
        }.toMap()

        val outbound = buildOutboundSimilarityIndex(signals)
        val impacts = outbound.getValue("negative_source")

        assertTrue(impacts.isNotEmpty())
        assertTrue(impacts.all { it.amount <= 0f })
    }

    private fun signalWithContributor(
        sourceFilename: String,
        amount: Float,
    ) = TasteEngine.TasteSignal(
        similarityContributors = listOf(
            TasteEngine.SimilarityContributor(
                sourceFilename = sourceFilename,
                amount = amount,
                reason = "test",
                updatedAt = 1L,
            ),
        ),
    )
}
