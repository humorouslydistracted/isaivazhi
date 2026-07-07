package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderBlendWeightsTest {

    @Test
    fun autoMode_withFiveEvidenceUnits_usesEvidenceSchedule() {
        val w = BlendWeightLogic.compute("refresh", 5, hasCurrent = true, hasSession = true, hasProfile = true)
        assertEquals(0.30f, w.wCurrent, 0.001f)
        assertEquals(0.21f, w.wSession, 0.001f)
        assertEquals(0.49f, w.wProfile, 0.001f)
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

    @Test
    fun unavailableManualSource_isRedistributedAcrossAvailableSources() {
        val w = BlendWeightLogic.computeManual(
            currentWeight = 0.5f,
            sessionWeight = 0.3f,
            hasCurrent = false,
            hasSession = true,
            hasProfile = true,
        )
        assertEquals(0f, w.wCurrent, 0.001f)
        assertEquals(0.6f, w.wSession, 0.001f)
        assertEquals(0.4f, w.wProfile, 0.001f)
        assertEquals(1f, w.wCurrent + w.wSession + w.wProfile, 0.001f)
    }

    @Test
    fun manualBlendAlwaysSumsToOne() {
        val w = BlendWeightLogic.computeManual(
            currentWeight = 0.6f,
            sessionWeight = 0.3f,
            hasCurrent = true,
            hasSession = true,
            hasProfile = true,
        )
        assertEquals(0.6f, w.wCurrent, 0.001f)
        assertEquals(0.3f, w.wSession, 0.001f)
        assertEquals(0.1f, w.wProfile, 0.001f)
        assertEquals(1f, w.wCurrent + w.wSession + w.wProfile, 0.001f)
    }
}
