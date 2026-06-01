package com.isaivazhi.app.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Applies durable service-authored playback signal events to the Kotlin engines.
 *
 * MainActivity can be destroyed while the Media3 service continues playing. The
 * service therefore writes completed playback instances to [PlaybackSignalLedger],
 * and this processor replays pending events after engine state has loaded.
 */
class PlaybackSignalProcessor(private val container: AppContainer) {

    private val sideEffects = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun processPending(songs: List<Song>, reason: String, logWhenEmpty: Boolean = false): Int {
        if (songs.isEmpty()) return 0
        container.taste.awaitReady()
        container.signalTimeline.awaitReady()
        container.history.awaitReady()
        container.activityLog.awaitReady()

        val events = container.playbackSignalLedger.pendingEvents()
        if (events.isEmpty()) {
            if (logWhenEmpty) {
                container.activityLog.log(
                    category = "engine",
                    type = "LEDGER_DRAIN",
                    message = "No pending playback ledger events",
                    data = mapOf("reason" to reason, "events" to 0),
                )
            }
            return 0
        }

        val watermark = runCatching { container.preferences.loadIngestWatermark() }.getOrDefault(0L)
        val processedIds = ArrayList<String>(events.size)
        var applied = 0
        var skipped = 0

        for (event in events) {
            if (!event.isUsable()) {
                processedIds += event.eventId
                skipped++
                continue
            }
            if (event.playbackInstanceId > 0L && event.playbackInstanceId <= watermark) {
                processedIds += event.eventId
                skipped++
                continue
            }

            val appliedThisEvent = apply(event, songs)
            processedIds += event.eventId
            runCatching { container.preferences.saveIngestWatermark(event.playbackInstanceId) }
            if (appliedThisEvent) {
                applied++
            } else {
                skipped++
            }
        }

        container.playbackSignalLedger.markProcessed(processedIds)
        if (applied > 0 || skipped > 0) {
            container.activityLog.log(
                category = "engine",
                type = "LEDGER_DRAIN",
                message = "Processed $applied playback ledger events",
                data = mapOf(
                    "reason" to reason,
                    "events" to events.size,
                    "applied" to applied,
                    "skipped" to skipped,
                    "watermark" to watermark,
                ),
            )
        }
        return applied
    }

    private fun apply(event: PlaybackSignalLedger.Event, songs: List<Song>): Boolean {
        val mediaId = event.filename
        val song = songs.firstOrNull { it.filename == mediaId }
        val frac = event.fraction.coerceIn(0f, 1f)
        val source = tasteSourceFor(event.action)
        val timelineSource = "playback_ledger_${event.action.ifBlank { "unknown" }}"

        val (before, after) = container.taste.recordPlaybackEvent(
            filename = mediaId,
            fraction = frac,
            source = source,
            playbackInstanceId = event.playbackInstanceId,
            eventId = event.eventId,
        )
        if (before == after) return false

        val isSkip = frac < TasteEngine.SKIP_THRESHOLD
        val cls = if (isSkip) SignalTimelineEngine.Classification.SKIP
            else SignalTimelineEngine.Classification.LISTEN
        val isManual = source == "next_button" || source == "prev_button" ||
            source == "queue_tap" || source == "neutral_skip"
        val sessionPair = container.session.recordEvent(
            fraction = frac,
            isSkip = isSkip,
            isManual = isManual,
            filename = mediaId,
            source = source,
        )
        val sessionPull = container.taste.tuning.value.sessionBias.coerceIn(0f, 1f)
        val avgLib = avgLibraryFraction()

        container.signalTimeline.append(
            SignalTimelineEngine.Event(
                timestamp = if (event.createdAtMs > 0L) event.createdAtMs else System.currentTimeMillis(),
                filename = mediaId,
                title = song?.title?.ifBlank { event.title.ifBlank { mediaId } }
                    ?: event.title.ifBlank { mediaId },
                artist = song?.artist ?: event.artist,
                source = timelineSource,
                fraction = frac,
                classification = cls,
                tasteBefore = snapshotOf(before),
                tasteAfter = snapshotOf(after),
                xScoreBefore = before.xScore,
                xScoreAfter = after.xScore,
                sessionPullBefore = sessionPull,
                sessionPullAfter = sessionPull,
                libraryAvgBefore = avgLib,
                libraryAvgAfter = avgLib,
                sessionEncountersBefore = sessionPair.first.encounters,
                sessionEncountersAfter = sessionPair.second.encounters,
                sessionSkipsBefore = sessionPair.first.skips,
                sessionSkipsAfter = sessionPair.second.skips,
                sessionPositivesBefore = sessionPair.first.positives,
                sessionPositivesAfter = sessionPair.second.positives,
                eventId = event.eventId,
            )
        )
        container.history.recordCompleted(
            filename = mediaId,
            startedAt = if (event.createdAtMs > 0L) event.createdAtMs else System.currentTimeMillis(),
            fractionPlayed = frac,
            eventId = event.eventId,
        )

        val delta = after.directScore - before.directScore
        if (abs(delta) > 0.001f) {
            sideEffects.launch {
                runCatching {
                    container.taste.propagateSimilarityBoost(
                        sourceFilename = mediaId,
                        scoreDelta = delta,
                        reason = timelineSource,
                    )
                }
            }
        }
        container.activityLog.log(
            category = "engine",
            type = "LEDGER",
            message = "${song?.title?.ifBlank { mediaId } ?: event.title.ifBlank { mediaId }} - ${(frac * 100).toInt()}% via ${event.action}",
            data = mapOf(
                "eventId" to event.eventId,
                "mediaId" to mediaId,
                "playedMs" to event.playedMs,
                "durationMs" to event.durationMs,
                "fraction" to frac,
                "action" to event.action,
                "instId" to event.playbackInstanceId,
            ),
        )
        return true
    }

    private fun PlaybackSignalLedger.Event.isUsable(): Boolean {
        return eventId.isNotBlank() &&
            filename.isNotBlank() &&
            playbackInstanceId > 0L &&
            playedMs >= 1000L &&
            durationMs > 0L
    }

    private fun tasteSourceFor(action: String): String {
        return when (action.lowercase()) {
            "next_button", "user_next", "native_user_next" -> "next_button"
            "prev_button", "user_prev" -> "prev_button"
            "neutral_skip" -> "neutral_skip"
            "queue_tap", "user_jump" -> "queue_tap"
            "auto_advance" -> "auto_advance"
            "queue_end" -> "queue_end"
            "user_dismiss" -> "user_dismiss"
            else -> "background_recovery"
        }
    }

    private fun snapshotOf(s: TasteEngine.TasteSignal): SignalTimelineEngine.Snapshot =
        SignalTimelineEngine.Snapshot(
            score = s.directScore,
            direct = s.directScore,
            similarity = s.similarityBoost,
            plays = s.plays,
            skips = s.skips,
            avgFraction = s.avgFraction,
        )

    private fun avgLibraryFraction(): Float {
        val sigs = container.taste.signals.value
        if (sigs.isEmpty()) return 0f
        return sigs.values.map { it.avgFraction }.average().toFloat()
    }
}
