package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionEngineUnifiedContractTest {

    @Test
    fun sessionUsesTheSameOutcomeWeightsAsSongEvidence() {
        val engine = SessionEngine()
        listOf(0.20f, 0.35f, 0.60f, 0.80f).forEachIndexed { index, fraction ->
            engine.recordEvent(
                fraction = fraction,
                isSkip = false,
                isManual = false,
                filename = "song-$index",
                source = "test",
            )
        }

        val counters = engine.current()
        assertEquals(4, counters.encounters)
        assertEquals(1, counters.early)
        assertEquals(1, counters.partial)
        assertEquals(1, counters.listens)
        assertEquals(1, counters.strong)
        assertEquals(2.5f, counters.evidenceUnits, 0.001f)
        assertEquals(3, engine.listened.value.size)
        assertEquals(0.175f, engine.listened.value.first().contribution, 0.001f)
    }
}
