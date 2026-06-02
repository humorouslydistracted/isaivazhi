package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderBlendWeightsTest {

    @Test
    fun refreshMode_withAllVectors_uses304030() {
        val w = BlendWeightLogic.compute("refresh", 5, hasCurrent = true, hasSession = true, hasProfile = true)
        assertEquals(0.30f, w.wCurrent, 0.001f)
        assertEquals(0.40f, w.wSession, 0.001f)
        assertEquals(0.30f, w.wProfile, 0.001f)
    }

    @Test
    fun playMode_noSessionOrProfile_isCurrentOnly() {
        val w = BlendWeightLogic.compute("play", 0, hasCurrent = true, hasSession = false, hasProfile = false)
        assertEquals(1.0f, w.wCurrent, 0.001f)
        assertEquals(0f, w.wSession, 0.001f)
        assertEquals(0f, w.wProfile, 0.001f)
    }

    @Test
    fun playMode_rampsSessionAsListensGrow() {
        val early = BlendWeightLogic.compute("play", 1, hasCurrent = true, hasSession = true, hasProfile = true)
        val late = BlendWeightLogic.compute("play", 20, hasCurrent = true, hasSession = true, hasProfile = true)
        assertTrue(early.wSession < late.wSession)
        assertTrue(early.wCurrent > late.wCurrent)
    }
}
