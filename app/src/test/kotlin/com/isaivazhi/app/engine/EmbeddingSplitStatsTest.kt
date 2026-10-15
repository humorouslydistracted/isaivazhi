package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class EmbeddingSplitStatsTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val day1 = 1_760_313_600_000L // 2025-10-13T00:00Z
    private val day2 = day1 + 86_400_000L

    private fun row(
        hash: String,
        split: Int,
        source: EmbeddingSplitStats.Source,
        ts: Long = day1,
        clips: Int = split,
    ) = EmbeddingSplitStats.Row(hash, "/m/$hash.mp3", "$hash.mp3", ts, split, clips, source)

    @Test
    fun countsKnownSplitsAndFindsMismatches() {
        val rows = listOf(
            row("a", 5, EmbeddingSplitStats.Source.RECORDED),
            row("b", 5, EmbeddingSplitStats.Source.LABELLED),
            row("c", 3, EmbeddingSplitStats.Source.RECORDED),
            row("d", 7, EmbeddingSplitStats.Source.RECORDED),
            row("e", 0, EmbeddingSplitStats.Source.UNKNOWN),
        )
        val s = EmbeddingSplitStats.summarize(rows, targetSplit = 5, zone = utc)
        assertEquals(5, s.total)
        assertEquals(listOf(3 to 1, 5 to 2, 7 to 1), s.bySplit)
        assertEquals(1, s.labelledCount)
        assertEquals(listOf("c", "d"), s.mismatched.map { it.contentHash })
        assertEquals(1, s.unknownCount)
    }

    @Test
    fun shortSongWithFewerClipsIsNotAMismatch() {
        val rows = listOf(row("short", 5, EmbeddingSplitStats.Source.RECORDED, clips = 2))
        val s = EmbeddingSplitStats.summarize(rows, targetSplit = 5, zone = utc)
        assertTrue(s.mismatched.isEmpty())
        assertEquals("5 splits, 2 clips (short song)", EmbeddingSplitStats.describe(rows[0]))
    }

    @Test
    fun unknownRowsAreGroupedByDayNewestFirstUndatedLast() {
        val rows = listOf(
            row("a", 0, EmbeddingSplitStats.Source.UNKNOWN, ts = day1 + 3_600_000L),
            row("b", 0, EmbeddingSplitStats.Source.UNKNOWN, ts = day2 + 60_000L),
            row("c", 0, EmbeddingSplitStats.Source.UNKNOWN, ts = day1 + 80_000_000L),
            row("d", 0, EmbeddingSplitStats.Source.UNKNOWN, ts = 0L),
        )
        val groups = EmbeddingSplitStats.summarize(rows, targetSplit = 5, zone = utc).unknownByDay
        assertEquals(listOf(day2, day1, 0L), groups.map { it.dayStartMs })
        assertEquals(listOf("a", "c"), groups[1].rows.map { it.contentHash })
    }

    @Test
    fun describeMentionsLabelledAndUnknown() {
        assertEquals(
            "5 splits — labelled by you",
            EmbeddingSplitStats.describe(row("x", 5, EmbeddingSplitStats.Source.LABELLED, clips = 0)),
        )
        assertEquals(
            "Split not recorded (embedded before tracking)",
            EmbeddingSplitStats.describe(row("y", 0, EmbeddingSplitStats.Source.UNKNOWN)),
        )
    }
}
