package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EmbeddingSpaceTest {

    private fun randomVec(rng: Random, dim: Int, bias: FloatArray? = null): FloatArray =
        FloatArray(dim) { i -> (rng.nextFloat() - 0.5f) + (bias?.get(i) ?: 0f) }

    @Test
    fun centeredQueryScore_matchesExplicitCenteredCosine() {
        val rng = Random(7)
        val dim = 32
        val shared = FloatArray(dim) { 2f }
        val library = List(40) { randomVec(rng, dim, shared) }
        val mean = EmbeddingSpace.libraryMean(library, dim)!!
        val query = EmbeddingSpace.unit(library[0])
        val cq = EmbeddingSpace.CenteredQuery(query, mean)
        val cqUnit = EmbeddingSpace.centeredUnit(query, mean)
        for (v in library) {
            val expected = EmbeddingSpace.dot(cqUnit, EmbeddingSpace.centeredUnit(v, mean))
            assertEquals(expected, cq.score(v), 1e-4f)
        }
    }

    @Test
    fun centering_spreadsSimilaritiesThatSharedComponentSqueezes() {
        val rng = Random(11)
        val dim = 64
        val shared = FloatArray(dim) { 3f }
        val library = List(60) { randomVec(rng, dim, shared) }
        val mean = EmbeddingSpace.libraryMean(library, dim)!!
        val q = EmbeddingSpace.unit(library[0])
        val raw = library.drop(1).map { EmbeddingSimilarity.cosineSimilarity(q, it) }
        val cq = EmbeddingSpace.CenteredQuery(q, mean)
        val centered = library.drop(1).map { cq.score(it) }
        val rawSpread = raw.max() - raw.min()
        val centeredSpread = centered.max() - centered.min()
        assertTrue("raw=$rawSpread centered=$centeredSpread", centeredSpread > rawSpread * 3)
    }

    @Test
    fun calibrateByRank_ordersByCenteredButKeepsRawMagnitudes() {
        val items = listOf(
            EmbeddingSpace.Scored("a", raw = 0.95f, centered = 0.10f),
            EmbeddingSpace.Scored("b", raw = 0.90f, centered = 0.80f),
            EmbeddingSpace.Scored("c", raw = 0.85f, centered = 0.50f),
        )
        val out = EmbeddingSpace.calibrateByRank(items)
        assertEquals(listOf("b", "c", "a"), out.map { it.first })
        assertEquals(listOf(0.95f, 0.90f, 0.85f), out.map { it.second })
        assertEquals(listOf("b", "c"), EmbeddingSpace.calibrateByRank(items, k = 2).map { it.first })
    }

    @Test
    fun quantile_interpolates() {
        val v = floatArrayOf(4f, 1f, 3f, 2f, 5f)
        assertEquals(1f, EmbeddingSpace.quantile(v, 0f), 1e-6f)
        assertEquals(3f, EmbeddingSpace.quantile(v, 0.5f), 1e-6f)
        assertEquals(4f, EmbeddingSpace.quantile(v, 0.75f), 1e-6f)
        assertEquals(5f, EmbeddingSpace.quantile(v, 1f), 1e-6f)
    }

    @Test
    fun inject_placesPicksEveryTenthAndKeepsSize() {
        val selected = (1..50).map { "s$it" }
        val out = DiscoverySlots.inject(selected, listOf("d1", "d2", "d3"), maxSize = 50)
        assertEquals(50, out.size)
        assertEquals("d1", out[9])
        assertEquals("d2", out[19])
        assertEquals("d3", out[29])
        assertEquals(selected.take(9), out.take(9))
        assertEquals(out.size, out.toSet().size)
        assertEquals(listOf("s48", "s49", "s50").filter { it in out }, emptyList<String>())
    }

    @Test
    fun inject_withNoPicksReturnsSelected() {
        val selected = (1..12).map { "s$it" }
        assertEquals(selected, DiscoverySlots.inject(selected, emptyList(), maxSize = 12))
    }

    @Test
    fun slotCount_isOneInTen() {
        assertEquals(0, DiscoverySlots.slotCount(9))
        assertEquals(1, DiscoverySlots.slotCount(10))
        assertEquals(5, DiscoverySlots.slotCount(50))
    }

    @Test
    fun pick_onlyEligibleSongsAboveQuantileFloor() {
        val scored = (0 until 100).map { "s$it" to it / 100f }
        val eligible = setOf("s5", "s50", "s80", "s90", "s99")
        val picks = DiscoverySlots.pick(scored, { it in eligible }, count = 5, random = Random(1))
        assertEquals(setOf("s80", "s90", "s99"), picks.toSet())
        assertEquals(listOf("s99", "s90", "s80"), picks)
    }

    @Test
    fun pick_samplesFromBestCandidatesInSimilarityOrder() {
        val scored = (0 until 100).map { "s$it" to it / 100f }
        val picks = DiscoverySlots.pick(scored, { true }, count = 2, random = Random(3))
        assertEquals(2, picks.size)
        val pool = setOf("s99", "s98", "s97", "s96", "s95", "s94")
        assertTrue(picks.all { it in pool })
        assertTrue(scored.toMap()[picks[0]]!! > scored.toMap()[picks[1]]!!)
    }
}
