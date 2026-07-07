package com.isaivazhi.app.engine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ring buffer of the last MAX (default 30) playback signal updates with
 * before/after taste snapshots. Powers the "Last 30 Playback Signal Updates"
 * section in TasteScreen so the user can audit how each play/skip moved
 * the per-song taste score, x-score, and library averages.
 *
 * Push #63: extended with session-level counters (encounters/skips/positives)
 * to match Capacitor's `sessionBefore` / `sessionAfter` snapshot fields.
 *
 * Persisted to DataStore as JSON; reload-safe across cold starts. Older
 * persisted events without the new fields decode with defaults (0).
 */
class SignalTimelineEngine(private val appContext: Context) {

    enum class Classification { SKIP, LISTEN }

    data class Snapshot(
        val detailVersion: Int = 2,
        val score: Float = 0f,
        val direct: Float = 0f,
        val similarity: Float = 0f,
        val plays: Int = 0,
        val skips: Int = 0,
        val avgFraction: Float = 0f,
        val encounters: Int = 0,
        val early: Int = 0,
        val partial: Int = 0,
        val listens: Int = 0,
        val strong: Int = 0,
        val avgAllFraction: Float = 0f,
        val ownPositive: Float = 0f,
        val favorite: Float = 0f,
        val avoidance: Float = 0f,
        val dislike: Float = 0f,
        val queueRemovals: Int = 0,
    )

    data class Event(
        val timestamp: Long,
        val filename: String,
        val title: String,
        val artist: String,
        val source: String,
        val fraction: Float,
        val classification: Classification,
        val tasteBefore: Snapshot,
        val tasteAfter: Snapshot,
        val xScoreBefore: Float,
        val xScoreAfter: Float,
        val sessionPullBefore: Float = 0f,
        val sessionPullAfter: Float = 0f,
        val libraryAvgBefore: Float,
        val libraryAvgAfter: Float,
        // Push #63: per-session running counts.
        val sessionEncountersBefore: Int = 0,
        val sessionEncountersAfter: Int = 0,
        val sessionSkipsBefore: Int = 0,
        val sessionSkipsAfter: Int = 0,
        val sessionPositivesBefore: Int = 0,
        val sessionPositivesAfter: Int = 0,
        val eventId: String = "",
        val kind: TasteEventKind = TasteEventKind.PLAYBACK,
        val outcome: PlaybackOutcome = UnifiedTasteContract.outcomeFor(fraction),
        val endReason: PlaybackEndReason = PlaybackEndReason.fromSource(source),
        val contribution: Float = UnifiedTasteContract.positiveContribution(fraction),
        val avoidanceDelta: Float = 0f,
        val relatedSongsChanged: Int = 0,
        val relatedScoreDelta: Float = 0f,
    )

    private val KEY = stringPreferencesKey("signal_timeline_v1_json")
    private val MAX_EVENTS = 30

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readyDeferred = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                val raw = appContext.dataStoreLocal.data.first()[KEY] ?: return@launch
                _events.value = parse(raw)
            } catch (t: Throwable) {
                android.util.Log.w("SignalTimeline", "load failed: ${t.message}")
            } finally {
                if (!readyDeferred.isCompleted) readyDeferred.complete(Unit)
            }
        }
    }

    suspend fun awaitReady() {
        readyDeferred.await()
    }

    fun append(event: Event) {
        android.util.Log.i(
            "SignalTimeline",
            "append cls=${event.classification} frac=${event.fraction} src=${event.source} filename=${event.filename}"
        )
        if (event.fraction < 0f) {
            android.util.Log.w("SignalTimeline", "  dropped: fraction<0")
            return
        }
        if (event.filename.isBlank()) {
            android.util.Log.w("SignalTimeline", "  dropped: blank filename")
            return
        }
        if (event.eventId.isNotBlank() && _events.value.any { it.eventId == event.eventId }) {
            android.util.Log.i("SignalTimeline", "  skipped duplicate eventId=${event.eventId}")
            return
        }
        // Push #65: removed the "<10% skip" noise filter. It was inherited
        // from Capacitor where the timeline was a curated "interesting
        // signals" feed, but Kotlin users have used the timeline as an
        // audit trail of every transition. Hiding tap-through events made
        // the page look broken ("Last 30 = 0") when in fact every
        // transition was being dropped as noise. Now every valid event
        // shows up; users can mentally filter the short ones.
        _events.value = (listOf(event) + _events.value).take(MAX_EVENTS)
        scope.launch { persist() }
    }

    fun clear() {
        _events.value = emptyList()
        scope.launch { persist() }
    }

    /**
     * Push #76: one-time scan that removes the pre-#74 historical
     * duplicate pairs (same filename + same fraction + same timestamp,
     * one tagged `background_recovery_task_removed` and the other
     * `background_recovery_datastore`). These appear in the user's
     * timeline because the cold-start LE drained both DataStore and SP
     * pending-evidence stores independently before push #74's dedup
     * landed. Returns the number of duplicate entries removed.
     */
    fun cleanLegacyDuplicates(): Int {
        val before = _events.value
        if (before.isEmpty()) return 0
        // Group by (filename, timestamp-rounded-to-5sec, fraction-rounded-to-1pct).
        // Within each group, if multiple events exist where one has source
        // ending in "_task_removed" or "_datastore" (the bug's pairing),
        // keep the first and drop the rest.
        val seen = HashMap<String, Int>()  // key → index of first occurrence
        val toRemove = HashSet<Int>()
        for ((idx, e) in before.withIndex()) {
            val srcRoot = when {
                e.source.endsWith("_task_removed") -> "_recovery_legacy"
                e.source.endsWith("_datastore") -> "_recovery_legacy"
                else -> e.source
            }
            val key = "${e.filename}|${e.timestamp / 5000L}|${(e.fraction * 100).toInt()}|$srcRoot"
            val firstIdx = seen[key]
            if (firstIdx == null) {
                seen[key] = idx
            } else {
                toRemove += idx
            }
        }
        if (toRemove.isEmpty()) return 0
        _events.value = before.filterIndexed { i, _ -> i !in toRemove }
        scope.launch { persist() }
        return toRemove.size
    }

    fun snapshotCopyText(): String = events.value.mapIndexed { index, event ->
        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            java.util.Locale.US,
        ).format(java.util.Date(event.timestamp))
        val label = if (event.kind == TasteEventKind.PLAYBACK) {
            event.outcome.name
        } else {
            event.kind.name
        }
        buildString {
            appendLine("${index + 1}. ${event.title.ifBlank { event.filename }} | $label")
            appendLine("Time: $timestamp")
            if (event.kind == TasteEventKind.PLAYBACK) {
                appendLine("Heard: ${(event.fraction * 100).toInt()}% | Ended by: ${event.endReason.label}")
                appendLine("Evidence: ${fmtSigned(event.contribution)}")
            } else {
                appendLine(
                    "Song score change: " +
                        fmtSigned(event.tasteAfter.direct - event.tasteBefore.direct)
                )
            }
            if (kotlin.math.abs(event.avoidanceDelta) > 0.001f) {
                val avoidanceLabel = if (event.avoidanceDelta > 0f) {
                    "Avoidance"
                } else {
                    "Avoidance repaired"
                }
                appendLine("$avoidanceLabel: ${fmtSigned(-event.avoidanceDelta)}")
            }
            append(
                "Similar: ${fmtSigned(event.tasteAfter.similarity)} | " +
                    "Total: ${fmtSigned(event.tasteAfter.score)}"
            )
        }
    }.joinToString("\n\n")

    @Suppress("unused")
    private fun legacySnapshotCopyText(): String = events.value.mapIndexed { idx, e ->
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp))
        val cls = if (e.classification == Classification.SKIP) "skip" else "positive listen"
        val pctFrac = "${(e.fraction * 100).toInt()}%"
        buildString {
            appendLine("${idx + 1}. ${e.title.ifBlank { e.filename }} | ${e.artist.ifBlank { "-" }}")
            appendLine("Time: $ts")
            appendLine("Classification: $cls | Listened: $pctFrac | Source: ${e.source}")
            appendLine("Direct play effect: ${fmtSigned(e.tasteBefore.direct)} -> ${fmtSigned(e.tasteAfter.direct)} (Δ ${fmtSigned(e.tasteAfter.direct - e.tasteBefore.direct)})")
            appendLine("Similarity delta effect: ${fmtSigned(e.tasteAfter.similarity)}")
            appendLine("Total score: ${fmtSigned(e.tasteBefore.score)} -> ${fmtSigned(e.tasteAfter.score)}")
            appendLine("Session counts: encounters ${e.sessionEncountersBefore} -> ${e.sessionEncountersAfter} | skips ${e.sessionSkipsBefore} -> ${e.sessionSkipsAfter} | positive ${e.sessionPositivesBefore} -> ${e.sessionPositivesAfter}")
            appendLine("Library counts: starts ${e.tasteBefore.plays} -> ${e.tasteAfter.plays} | skips ${e.tasteBefore.skips} -> ${e.tasteAfter.skips}")
            appendLine("Library avg: ${(e.libraryAvgBefore * 100).toInt()}% -> ${(e.libraryAvgAfter * 100).toInt()}%")
            append("X-score: ${"%.1f".format(e.xScoreBefore)} -> ${"%.1f".format(e.xScoreAfter)}")
        }
    }.joinToString("\n\n")

    private fun fmtSigned(v: Float): String = (if (v >= 0f) "+" else "") + "%.2f".format(v)

    private suspend fun persist() {
        try {
            val arr = JSONArray()
            for (e in _events.value) {
                val o = JSONObject()
                o.put("ts", e.timestamp)
                o.put("filename", e.filename)
                o.put("title", e.title)
                o.put("artist", e.artist)
                o.put("source", e.source)
                o.put("fraction", e.fraction.toDouble())
                o.put("classification", e.classification.name)
                o.put("tasteBefore", snapshotJson(e.tasteBefore))
                o.put("tasteAfter", snapshotJson(e.tasteAfter))
                o.put("xScoreBefore", e.xScoreBefore.toDouble())
                o.put("xScoreAfter", e.xScoreAfter.toDouble())
                o.put("libraryAvgBefore", e.libraryAvgBefore.toDouble())
                o.put("libraryAvgAfter", e.libraryAvgAfter.toDouble())
                o.put("sessionEncountersBefore", e.sessionEncountersBefore)
                o.put("sessionEncountersAfter", e.sessionEncountersAfter)
                o.put("sessionSkipsBefore", e.sessionSkipsBefore)
                o.put("sessionSkipsAfter", e.sessionSkipsAfter)
                o.put("sessionPositivesBefore", e.sessionPositivesBefore)
                o.put("sessionPositivesAfter", e.sessionPositivesAfter)
                if (e.eventId.isNotBlank()) o.put("eventId", e.eventId)
                o.put("kind", e.kind.name)
                o.put("outcome", e.outcome.name)
                o.put("endReason", e.endReason.name)
                o.put("contribution", e.contribution.toDouble())
                o.put("avoidanceDelta", e.avoidanceDelta.toDouble())
                o.put("relatedSongsChanged", e.relatedSongsChanged)
                o.put("relatedScoreDelta", e.relatedScoreDelta.toDouble())
                arr.put(o)
            }
            appContext.dataStoreLocal.edit { it[KEY] = JSONObject().put("events", arr).toString() }
        } catch (t: Throwable) {
            android.util.Log.w("SignalTimeline", "persist failed: ${t.message}")
        }
    }

    private fun snapshotJson(s: Snapshot): JSONObject = JSONObject().apply {
        put("detailVersion", s.detailVersion)
        put("score", s.score.toDouble())
        put("direct", s.direct.toDouble())
        put("similarity", s.similarity.toDouble())
        put("plays", s.plays)
        put("skips", s.skips)
        put("avgFraction", s.avgFraction.toDouble())
        put("encounters", s.encounters)
        put("early", s.early)
        put("partial", s.partial)
        put("listens", s.listens)
        put("strong", s.strong)
        put("avgAllFraction", s.avgAllFraction.toDouble())
        put("ownPositive", s.ownPositive.toDouble())
        put("favorite", s.favorite.toDouble())
        put("avoidance", s.avoidance.toDouble())
        put("dislike", s.dislike.toDouble())
        put("queueRemovals", s.queueRemovals)
    }

    private fun parseSnapshot(o: JSONObject?): Snapshot {
        if (o == null) return Snapshot()
        return Snapshot(
            detailVersion = o.optInt("detailVersion", 1),
            score = o.optDouble("score", 0.0).toFloat(),
            direct = o.optDouble("direct", 0.0).toFloat(),
            similarity = o.optDouble("similarity", 0.0).toFloat(),
            plays = o.optInt("plays", 0),
            skips = o.optInt("skips", 0),
            avgFraction = o.optDouble("avgFraction", 0.0).toFloat(),
            encounters = o.optInt("encounters", o.optInt("plays", 0)),
            early = o.optInt("early", 0),
            partial = o.optInt("partial", 0),
            listens = o.optInt("listens", o.optInt("plays", 0)),
            strong = o.optInt("strong", 0),
            avgAllFraction = o.optDouble(
                "avgAllFraction",
                o.optDouble("avgFraction", 0.0),
            ).toFloat(),
            ownPositive = o.optDouble("ownPositive", 0.0).toFloat(),
            favorite = o.optDouble("favorite", 0.0).toFloat(),
            avoidance = o.optDouble("avoidance", 0.0).toFloat(),
            dislike = o.optDouble("dislike", 0.0).toFloat(),
            queueRemovals = o.optInt("queueRemovals", 0),
        )
    }

    private fun parse(raw: String): List<Event> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Event>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Event(
                timestamp = o.optLong("ts", 0L),
                filename = o.optString("filename", ""),
                title = o.optString("title", ""),
                artist = o.optString("artist", ""),
                source = o.optString("source", ""),
                fraction = o.optDouble("fraction", 0.0).toFloat(),
                classification = runCatching { Classification.valueOf(o.optString("classification", "SKIP")) }
                    .getOrDefault(Classification.SKIP),
                tasteBefore = parseSnapshot(o.optJSONObject("tasteBefore")),
                tasteAfter = parseSnapshot(o.optJSONObject("tasteAfter")),
                xScoreBefore = o.optDouble("xScoreBefore", 0.0).toFloat(),
                xScoreAfter = o.optDouble("xScoreAfter", 0.0).toFloat(),
                sessionPullBefore = o.optDouble("sessionPullBefore", 0.0).toFloat(),
                sessionPullAfter = o.optDouble("sessionPullAfter", 0.0).toFloat(),
                libraryAvgBefore = o.optDouble("libraryAvgBefore", 0.0).toFloat(),
                libraryAvgAfter = o.optDouble("libraryAvgAfter", 0.0).toFloat(),
                sessionEncountersBefore = o.optInt("sessionEncountersBefore", 0),
                sessionEncountersAfter = o.optInt("sessionEncountersAfter", 0),
                sessionSkipsBefore = o.optInt("sessionSkipsBefore", 0),
                sessionSkipsAfter = o.optInt("sessionSkipsAfter", 0),
                sessionPositivesBefore = o.optInt("sessionPositivesBefore", 0),
                sessionPositivesAfter = o.optInt("sessionPositivesAfter", 0),
                eventId = o.optString("eventId", ""),
                kind = runCatching {
                    TasteEventKind.valueOf(o.optString("kind", TasteEventKind.PLAYBACK.name))
                }.getOrDefault(TasteEventKind.PLAYBACK),
                outcome = runCatching {
                    PlaybackOutcome.valueOf(
                        o.optString(
                            "outcome",
                            UnifiedTasteContract.outcomeFor(
                                o.optDouble("fraction", 0.0).toFloat(),
                            ).name,
                        )
                    )
                }.getOrDefault(
                    UnifiedTasteContract.outcomeFor(o.optDouble("fraction", 0.0).toFloat())
                ),
                endReason = runCatching {
                    PlaybackEndReason.valueOf(
                        o.optString(
                            "endReason",
                            PlaybackEndReason.fromSource(o.optString("source", "")).name,
                        )
                    )
                }.getOrDefault(PlaybackEndReason.fromSource(o.optString("source", ""))),
                contribution = o.optDouble(
                    "contribution",
                    UnifiedTasteContract.positiveContribution(
                        o.optDouble("fraction", 0.0).toFloat(),
                    ).toDouble(),
                ).toFloat(),
                avoidanceDelta = o.optDouble("avoidanceDelta", 0.0).toFloat(),
                relatedSongsChanged = o.optInt("relatedSongsChanged", 0),
                relatedScoreDelta = o.optDouble("relatedScoreDelta", 0.0).toFloat(),
            )
        }
        return out
    }
}

internal fun timelineSnapshotOf(
    signal: TasteEngine.TasteSignal,
    now: Long = System.currentTimeMillis(),
): SignalTimelineEngine.Snapshot {
    val breakdown = computeUnifiedScore(
        positiveEvidence = signal.positiveEvidence.takeIf { it > 0f }
            ?: (signal.plays * signal.avgFraction),
        positiveEvidenceUpdatedAt = signal.positiveEvidenceUpdatedAt
            .takeIf { it > 0L } ?: signal.lastPlayedAt,
        xScore = signal.xScore,
        favoritePrior = signal.favoritePrior,
        dislikePrior = signal.dislikePrior,
        similarityBoost = signal.similarityBoost,
        xScoreUpdatedAt = signal.xScoreUpdatedAt,
        similarityBoostUpdatedAt = signal.similarityBoostUpdatedAt,
        lastUpdatedAt = signal.lastUpdatedAt,
        now = now,
    )
    return SignalTimelineEngine.Snapshot(
        score = breakdown.score,
        direct = breakdown.directScore,
        similarity = breakdown.score - breakdown.directScore,
        plays = signal.plays,
        skips = signal.skips,
        avgFraction = signal.avgFraction,
        encounters = signal.encounters,
        early = signal.earlyCount,
        partial = signal.partialCount,
        listens = signal.listenCount,
        strong = signal.strongCount,
        avgAllFraction = signal.avgAllFraction,
        ownPositive = (breakdown.positiveWeight - signal.favoritePrior).coerceAtLeast(0f),
        favorite = signal.favoritePrior,
        avoidance = (breakdown.negativeWeight - signal.dislikePrior).coerceAtLeast(0f),
        dislike = signal.dislikePrior,
        queueRemovals = signal.queueRemovals,
    )
}
