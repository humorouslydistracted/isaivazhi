package com.isaivazhi.app.ui.screens

import com.isaivazhi.app.engine.PlaybackEndReason
import com.isaivazhi.app.engine.PlaybackOutcome
import com.isaivazhi.app.engine.SignalTimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class TasteSignalPresentationTest {

    @Test
    fun neutralEarlyNextIsNotPresentedAsNegative() {
        val event = playbackEvent(
            contribution = 0f,
            avoidanceDelta = 0f,
        )
        assertEquals(TimelineSignalTone.NEUTRAL, timelineSignalTone(event))
    }

    @Test
    fun repeatedEarlyNextWithAvoidanceIsPresentedAsNegative() {
        val event = playbackEvent(
            contribution = 0f,
            avoidanceDelta = 0.2f,
        )
        assertEquals(TimelineSignalTone.NEGATIVE, timelineSignalTone(event))
    }

    private fun playbackEvent(
        contribution: Float,
        avoidanceDelta: Float,
    ): SignalTimelineEngine.Event = SignalTimelineEngine.Event(
        timestamp = 1L,
        filename = "song",
        title = "Song",
        artist = "Artist",
        source = "user_next",
        fraction = 0.12f,
        classification = SignalTimelineEngine.Classification.SKIP,
        tasteBefore = SignalTimelineEngine.Snapshot(),
        tasteAfter = SignalTimelineEngine.Snapshot(),
        xScoreBefore = 0f,
        xScoreAfter = avoidanceDelta,
        sessionPullBefore = 0.5f,
        sessionPullAfter = 0.5f,
        libraryAvgBefore = 0f,
        libraryAvgAfter = 0f,
        outcome = PlaybackOutcome.EARLY,
        endReason = PlaybackEndReason.NEXT,
        contribution = contribution,
        avoidanceDelta = avoidanceDelta,
    )
}
