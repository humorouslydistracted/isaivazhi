package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftRefreshPlannerTest {

    @Test
    fun split_protectsImmediateUpcomingAndSplitsStableAndFluidZones() {
        val upcoming = (0 until 30).map { "song$it" }

        val zones = SoftRefreshPlanner.split(upcoming)

        assertEquals((0 until 5).map { "song$it" }, zones.frozen)
        assertEquals((5 until 15).map { "song$it" }, zones.stable)
        assertEquals((15 until 30).map { "song$it" }, zones.fluid)
        assertTrue(zones.hasRefreshableTail)
    }

    @Test
    fun split_shortTailIsFullyFrozen() {
        val upcoming = (0 until 5).map { "song$it" }

        val zones = SoftRefreshPlanner.split(upcoming)

        assertEquals(upcoming, zones.frozen)
        assertEquals(emptyList<String>(), zones.stable)
        assertEquals(emptyList<String>(), zones.fluid)
        assertFalse(zones.hasRefreshableTail)
    }
}
