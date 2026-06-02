package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Validates the replaceUpcoming-style shuffle tail used by toggleShuffle(). */
class ShuffleTailReorderTest {

    private fun shuffleTailFilenames(queue: List<String>, curIdx: Int, rng: Random): List<String> {
        val tailStart = curIdx + 1
        val head = queue.subList(0, tailStart)
        val tail = queue.subList(tailStart, queue.size).shuffled(rng)
        return head + tail
    }

    @Test
    fun shuffleTailPreservesHeadAndPermutesTail() {
        val queue = (0 until 20).map { "song$it" }
        val curIdx = 2
        val shuffled = shuffleTailFilenames(queue, curIdx, Random(42))
        assertEquals(queue.subList(0, curIdx + 1), shuffled.subList(0, curIdx + 1))
        assertEquals(
            queue.subList(curIdx + 1, queue.size).toSet(),
            shuffled.subList(curIdx + 1, shuffled.size).toSet(),
        )
        assertTrue(
            "tail should reorder for this seed",
            queue.subList(curIdx + 1, queue.size) != shuffled.subList(curIdx + 1, shuffled.size),
        )
    }
}
