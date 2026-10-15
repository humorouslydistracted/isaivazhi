package com.isaivazhi.app.engine

import java.util.Calendar
import java.util.TimeZone

/**
 * Which split count each stored embedding was made with, so a library that
 * mixes 3 / 5 / 7 can be found and fixed.
 *
 * Rows embedded before the split was recorded have splitCount 0. They are
 * grouped by the day they were embedded, because the user usually remembers
 * which split a given run used and can label that day's rows in one go.
 *
 * Short songs use fewer clips than the split by design (clipCount <
 * splitCount); they still count under the split that was requested.
 */
object EmbeddingSplitStats {

    enum class Source {
        RECORDED, LABELLED, UNKNOWN;

        companion object {
            fun from(raw: String): Source = when (raw) {
                "recorded" -> RECORDED
                "labelled" -> LABELLED
                else -> UNKNOWN
            }
        }
    }

    data class Row(
        val contentHash: String,
        val filepath: String,
        val filename: String,
        val timestamp: Long,
        val splitCount: Int,
        val clipCount: Int,
        val source: Source,
    ) {
        val known: Boolean get() = splitCount > 0
    }

    /** [dayStartMs] is local midnight of the embed day, or 0 when the row has no timestamp. */
    data class DayGroup(val dayStartMs: Long, val rows: List<Row>)

    data class Summary(
        val total: Int,
        /** Known split → row count, ascending by split. */
        val bySplit: List<Pair<Int, Int>>,
        val labelledCount: Int,
        /** Rows with a known split different from the target. */
        val mismatched: List<Row>,
        /** Rows with no known split, newest day first; undated rows last. */
        val unknownByDay: List<DayGroup>,
    ) {
        val unknownCount: Int get() = unknownByDay.sumOf { it.rows.size }
        fun countFor(split: Int): Int = bySplit.firstOrNull { it.first == split }?.second ?: 0
    }

    fun summarize(rows: List<Row>, targetSplit: Int, zone: TimeZone = TimeZone.getDefault()): Summary {
        val known = rows.filter { it.known }
        val bySplit = known.groupingBy { it.splitCount }.eachCount().toList().sortedBy { it.first }
        val unknownByDay = rows.filterNot { it.known }
            .groupBy { if (it.timestamp > 0) dayStart(it.timestamp, zone) else 0L }
            .map { (day, list) -> DayGroup(day, list.sortedBy { it.filename.lowercase() }) }
            .sortedWith(compareBy<DayGroup> { it.dayStartMs == 0L }.thenByDescending { it.dayStartMs })
        return Summary(
            total = rows.size,
            bySplit = bySplit,
            labelledCount = known.count { it.source == Source.LABELLED },
            mismatched = known.filter { it.splitCount != targetSplit }.sortedBy { it.filename.lowercase() },
            unknownByDay = unknownByDay,
        )
    }

    fun dayStart(timestampMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = timestampMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** "5 splits, 3 clips (short song)" style description for song details. */
    fun describe(row: Row): String {
        if (!row.known) return "Split not recorded (embedded before tracking)"
        val base = "${row.splitCount} splits"
        val clips = when {
            row.clipCount <= 0 -> ""
            row.clipCount < row.splitCount -> ", ${row.clipCount} clip${if (row.clipCount == 1) "" else "s"} (short song)"
            else -> ", ${row.clipCount} clips"
        }
        val source = if (row.source == Source.LABELLED) " — labelled by you" else ""
        return base + clips + source
    }
}
