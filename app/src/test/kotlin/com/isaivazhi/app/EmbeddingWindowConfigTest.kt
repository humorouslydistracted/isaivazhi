package com.isaivazhi.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingWindowConfigTest {

    private fun plan(dur: Double, n: Int, hash: String) =
        EmbeddingWindowConfig.planWindowStarts(dur, 10.0, n, EmbeddingWindowConfig.seedFromContentHash(hash))

    /** Golden values produced by tools/embeddings/embedding_config.py — keeps phone and laptop vectors comparable. */
    @Test
    fun matchesPythonPlanner() {
        assertArrayEquals(doubleArrayOf(72.3569902, 101.812553302, 194.560732706), plan(240.0, 3, "a1b2c3d4e5f60718"), 1e-6)
        assertArrayEquals(
            doubleArrayOf(33.759407965, 64.698389945, 89.158709177, 120.843939535, 141.897063039),
            plan(185.5, 5, "ffffffffffffffff"), 1e-6,
        )
        assertArrayEquals(
            doubleArrayOf(89.308011409, 145.183420047, 223.979882153, 269.045951538, 367.540604906, 441.682997373, 542.530570964),
            plan(600.0, 7, "0000000000000000"), 1e-6,
        )
        assertArrayEquals(doubleArrayOf(0.0, 3.3, 7.5, 11.7, 15.0), plan(25.0, 5, "1234567890abcdef"), 1e-6)
        assertArrayEquals(doubleArrayOf(0.0), plan(9.0, 3, "abcdef"), 1e-9)
        assertArrayEquals(
            doubleArrayOf(47.8, 76.4, 19.2),
            EmbeddingWindowConfig.alternativeStarts(240.0, 10.0, 3, EmbeddingWindowConfig.seedFromContentHash("a1b2c3d4e5f60718"), 0),
            1e-6,
        )
    }

    @Test
    fun seedParsing() {
        assertEquals(-1L, EmbeddingWindowConfig.seedFromContentHash("ffffffffffffffff"))
        assertEquals(0L, EmbeddingWindowConfig.seedFromContentHash(null))
        assertEquals(0L, EmbeddingWindowConfig.seedFromContentHash("unknown"))
    }

    @Test
    fun oneWindowPerSectionInsideTrimmedRange() {
        for (n in intArrayOf(3, 5, 7)) {
            for (h in listOf("a1b2c3d4e5f60718", "0123456789abcdef", "deadbeefdeadbeef")) {
                val dur = 300.0
                val trim = 24.0
                val section = (dur - 2 * trim) / n
                val starts = plan(dur, n, h)
                assertEquals(n, starts.size)
                for ((i, s) in starts.withIndex()) {
                    val secStart = trim + i * section
                    assertTrue("n=$n i=$i s=$s", s >= secStart - 1e-9 && s + 10.0 <= secStart + section + 1e-9)
                }
            }
        }
    }

    @Test
    fun deterministicPerSongButVariesAcrossSongs() {
        assertArrayEquals(plan(240.0, 5, "a1b2c3d4e5f60718"), plan(240.0, 5, "a1b2c3d4e5f60718"), 0.0)
        assertFalse(plan(240.0, 5, "a1b2c3d4e5f60718").contentEquals(plan(240.0, 5, "0123456789abcdef")))
    }

    @Test
    fun edgeTrimIsCappedForLongTracks() {
        val starts = plan(3600.0, 3, "0123456789abcdef")
        assertTrue(starts.first() >= 30.0)
        assertTrue(starts.last() + 10.0 <= 3570.0)
    }

    @Test
    fun alternativesStayInOwnSectionAndDifferFromPlan() {
        val seed = EmbeddingWindowConfig.seedFromContentHash("0123456789abcdef")
        val planned = EmbeddingWindowConfig.planWindowStarts(300.0, 10.0, 5, seed)
        val section = (300.0 - 48.0) / 5
        for (i in 0 until 5) {
            val alts = EmbeddingWindowConfig.alternativeStarts(300.0, 10.0, 5, seed, i)
            assertTrue(alts.isNotEmpty())
            for (a in alts) {
                assertTrue(a >= 24.0 + i * section - 1e-9 && a + 10.0 <= 24.0 + (i + 1) * section + 1e-9)
                assertTrue(kotlin.math.abs(a - planned[i]) >= 1.0)
            }
        }
        assertEquals(0, EmbeddingWindowConfig.alternativeStarts(25.0, 10.0, 5, seed, 0).size)
    }

    @Test
    fun silentFraction() {
        val sr = 48000
        val silent = FloatArray(sr * 10)
        assertEquals(1f, EmbeddingWindowConfig.silentFraction(silent, silent.size, sr), 0f)
        val loud = FloatArray(sr * 10) { if (it % 2 == 0) 0.2f else -0.2f }
        assertEquals(0f, EmbeddingWindowConfig.silentFraction(loud, loud.size, sr), 0f)
        val halfAndMore = FloatArray(sr * 10) { i -> if (i < sr * 6) 0f else 0.2f }
        assertEquals(0.6f, EmbeddingWindowConfig.silentFraction(halfAndMore, halfAndMore.size, sr), 1e-6f)
        assertEquals(1f, EmbeddingWindowConfig.silentFraction(loud, 10, sr), 0f)
    }
}
