package com.isaivazhi.app.ui.screens

import com.isaivazhi.app.engine.ActivityLogEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLogScreenTest {

    @Test
    fun activityEntryListKey_keepsIdenticalEventsUniqueByRow() {
        val entry = ActivityLogEngine.Entry(
            timestamp = 1781982014762L,
            category = "playback",
            type = "PLAY",
            message = "Started playback",
        )

        val keys = List(3) { index -> activityEntryListKey(index, entry) }

        assertEquals(keys.size, keys.toSet().size)
    }
}
