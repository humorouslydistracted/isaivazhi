package com.isaivazhi.app.ui.screens

import com.isaivazhi.app.engine.PlaybackEndReason
import com.isaivazhi.app.engine.PlaybackOutcome
import com.isaivazhi.app.engine.SignalTimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun timelinePresentation_usesLatestPlaybackLabelAndCurrentHistoryOnly() {
        val event = playbackEvent(
            contribution = 0.6f,
            avoidanceDelta = 0.2f,
            before = SignalTimelineEngine.Snapshot(
                direct = 0.1f,
                score = 0.3f,
                encounters = 1,
                early = 1,
            ),
            after = SignalTimelineEngine.Snapshot(
                direct = 0.7f,
                similarity = -0.1f,
                score = 0.4f,
                favorite = 1.5f,
                dislike = 2.5f,
                avoidance = 0.2f,
                encounters = 4,
                early = 1,
                partial = 1,
                listens = 1,
                strong = 1,
                avgAllFraction = 0.62f,
            ),
        )

        val presentation = buildTimelineEventPresentation(event)

        assertEquals("Evidence +0.60", presentation.effectText)
        assertEquals("Latest playback score +0.70 (Δ +0.60)", presentation.latestPlaybackScoreText)
        assertEquals("Similarity score -0.10", presentation.similarityScoreText)
        assertEquals("Avoidance -0.20", presentation.avoidanceText)
        assertEquals("Total score +0.40", presentation.totalScoreText)
        assertEquals(
            "Played 4 · Early 1 · Partial 1 · Listen 1 · Strong 1 · Avg heard 62%",
            presentation.historyLine,
        )
        assertTrue(presentation.showFavoriteTag)
        assertTrue(presentation.showDislikeTag)
        assertFalse(presentation.historyLine.contains("before", ignoreCase = true))
    }

    private fun playbackEvent(
        contribution: Float,
        avoidanceDelta: Float,
        before: SignalTimelineEngine.Snapshot = SignalTimelineEngine.Snapshot(),
        after: SignalTimelineEngine.Snapshot = SignalTimelineEngine.Snapshot(),
    ): SignalTimelineEngine.Event = SignalTimelineEngine.Event(
        timestamp = 1L,
        filename = "song",
        title = "Song",
        artist = "Artist",
        source = "user_next",
        fraction = 0.12f,
        classification = SignalTimelineEngine.Classification.SKIP,
        tasteBefore = before,
        tasteAfter = after,
        xScoreBefore = 0f,
        xScoreAfter = avoidanceDelta,
        libraryAvgBefore = 0f,
        libraryAvgAfter = 0f,
        outcome = PlaybackOutcome.EARLY,
        endReason = PlaybackEndReason.NEXT,
        contribution = contribution,
        avoidanceDelta = avoidanceDelta,
    )
}
