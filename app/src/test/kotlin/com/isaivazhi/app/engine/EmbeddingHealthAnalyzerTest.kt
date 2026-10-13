package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EmbeddingHealthAnalyzerTest {

    private fun library(n: Int, dim: Int, seed: Int): MutableMap<String, FloatArray> {
        val rng = Random(seed)
        val out = LinkedHashMap<String, FloatArray>()
        for (i in 0 until n) out["h$i"] = FloatArray(dim) { rng.nextFloat() - 0.5f }
        return out
    }

    @Test
    fun occurrence_sumsToNTimesK() {
        val vecs = library(80, 16, seed = 1)
        val report = EmbeddingHealthAnalyzer().analyze(vecs, k = 10)
        assertEquals(80 * 10, report.raw.occurrence.values.sum())
        assertEquals(80 * 10, report.centered.occurrence.values.sum())
        assertEquals(80, report.songCount)
    }

    @Test
    fun hubAndOrphanAreDetected() {
        val dim = 32
        val rng = Random(2)
        val shared = FloatArray(dim) { 1f }
        // Like CLAP: every song = a big shared component + its own detail.
        val vecs = LinkedHashMap<String, FloatArray>()
        for (i in 0 until 60) vecs["h$i"] = FloatArray(dim) { shared[it] + (rng.nextFloat() - 0.5f) * 0.8f }
        // The pure shared direction is closer to everyone than they are to each other.
        vecs["hub"] = shared.copyOf()
        // Mostly its own direction: nobody's close neighbour.
        vecs["orphan"] = FloatArray(dim) { shared[it] }.also { it[0] = 12f; it[1] = -12f }
        val report = EmbeddingHealthAnalyzer().analyze(vecs, k = 5)
        val rawOcc = report.raw.occurrence
        assertEquals("hub", report.raw.mostSurfaced(1).single())
        assertTrue("hub occurrence ${rawOcc["hub"]}", rawOcc["hub"]!! >= 50)
        assertTrue("orphan occurrence ${rawOcc["orphan"]}", "orphan" in report.raw.orphans())
        assertTrue(
            "centred hub occurrence ${report.centered.occurrence["hub"]}",
            report.centered.occurrence["hub"]!! < rawOcc["hub"]!!,
        )
    }

    @Test
    fun nearIdenticalClusterAndInvalidAreSuspects() {
        val dim = 16
        val vecs = library(40, dim, seed = 3)
        val base = FloatArray(dim) { 1f }
        for (i in 0 until 3) vecs["dup$i"] = base.copyOf().also { it[i] += 0.001f }
        vecs["zero"] = FloatArray(dim)
        val report = EmbeddingHealthAnalyzer().analyze(vecs, k = 5)
        val byKey = report.suspects.associateBy { it.key }
        assertEquals(EmbeddingHealthAnalyzer.SuspectReason.INVALID_VECTOR, byKey["zero"]?.reason)
        for (i in 0 until 3) {
            assertEquals(
                EmbeddingHealthAnalyzer.SuspectReason.NEAR_IDENTICAL_CLUSTER,
                byKey["dup$i"]?.reason,
            )
            assertEquals(2, byKey["dup$i"]?.nearIdenticalCount)
        }
        assertEquals(EmbeddingHealthAnalyzer.SuspectReason.INVALID_VECTOR, report.suspects.first().reason)
    }

    @Test
    fun outlierIsSuspect() {
        val dim = 8
        val rng = Random(4)
        val vecs = LinkedHashMap<String, FloatArray>()
        // Tight cluster of 40 songs, plus one pointing the opposite way.
        for (i in 0 until 40) vecs["c$i"] = FloatArray(dim) { 1f + (rng.nextFloat() - 0.5f) * 0.2f }
        vecs["odd"] = FloatArray(dim) { if (it % 2 == 0) -1f else 1f }
        val report = EmbeddingHealthAnalyzer().analyze(vecs, k = 5)
        val odd = report.suspects.firstOrNull { it.key == "odd" }
        assertEquals(EmbeddingHealthAnalyzer.SuspectReason.OUTLIER, odd?.reason)
    }

    @Test
    fun nativeBatchDotResultIsUsedWhenAvailable() {
        val vecs = library(30, 8, seed = 5)
        var calls = 0
        val analyzer = EmbeddingHealthAnalyzer { q, v, count, dim, out ->
            calls++
            for (j in 0 until count) {
                var d = 0f
                for (t in 0 until dim) d += q[t] * v[j * dim + t]
                out[j] = d
            }
            true
        }
        val fallback = EmbeddingHealthAnalyzer().analyze(vecs, k = 5)
        val native = analyzer.analyze(vecs, k = 5)
        assertEquals(60, calls)
        assertEquals(fallback.raw.occurrence, native.raw.occurrence)
        assertEquals(fallback.centered.occurrence, native.centered.occurrence)
    }
}
