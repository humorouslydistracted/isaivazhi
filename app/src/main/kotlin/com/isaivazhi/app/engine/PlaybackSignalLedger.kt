package com.isaivazhi.app.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable handoff between the Media3 service and Kotlin taste/history engines.
 *
 * The service owns the authoritative transition moment, so it writes a compact
 * event here whenever a playback instance ends. The Activity-side processor can
 * safely replay these events after process death and skip already-processed ids.
 */
class PlaybackSignalLedger(private val appContext: Context) {

    data class Diagnostics(
        val ledgerRawCount: Int,
        val recoveryRawCount: Int,
        val transitionRawCount: Int,
        val processedIdCount: Int,
        val pendingCount: Int,
        val newestEventId: String,
        val newestFilename: String,
        val newestAction: String,
        val newestInstanceId: Long,
    )

    data class Event(
        val eventId: String,
        val playbackInstanceId: Long,
        val filename: String,
        val title: String,
        val artist: String,
        val album: String,
        val playedMs: Long,
        val durationMs: Long,
        val fraction: Float,
        val action: String,
        val createdAtMs: Long,
    )

    private val prefs
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val recoveryPrefs
        get() = appContext.getSharedPreferences(RECOVERY_PREFS_NAME, Context.MODE_PRIVATE)

    private val transitionPrefs
        get() = appContext.getSharedPreferences(TRANSITIONS_PREFS_NAME, Context.MODE_PRIVATE)

    fun pendingEvents(): List<Event> {
        val processed = processedIds()
        val merged = LinkedHashMap<String, Event>()
        for (event in readLedgerEvents() + readRecoveryEvents() + readTransitionHistoryEvents()) {
            if (event.eventId.isBlank() || event.eventId in processed) continue
            if (event.filename.isBlank() || event.playbackInstanceId <= 0L) continue
            merged.putIfAbsent(event.eventId, event)
        }
        return merged.values.sortedBy { it.createdAtMs }
    }

    fun markProcessed(eventIds: Collection<String>) {
        val valid = eventIds.filter { it.isNotBlank() }
        if (valid.isEmpty()) return
        val nextProcessed = (processedIds() + valid).takeLastIds(MAX_PROCESSED_IDS)
        val remainingLedgerEvents = readRawLedgerArray().filterObjects { o ->
            o.optString("eventId", "") !in nextProcessed
        }
        prefs.edit()
            .putString(KEY_PROCESSED_IDS, idsToJson(nextProcessed))
            .putString(KEY_EVENTS, remainingLedgerEvents.toString())
            .commit()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    fun diagnostics(): Diagnostics {
        val ledgerRaw = readRawLedgerArray()
        val recoveryRaw = readRawRecoveryArray()
        val transitionRaw = readRawTransitionArray()
        val pending = pendingEvents()
        val newest = (parseEvents(ledgerRaw) + parseEvents(recoveryRaw) + parseEvents(transitionRaw))
            .maxByOrNull { it.createdAtMs }
        return Diagnostics(
            ledgerRawCount = ledgerRaw.length(),
            recoveryRawCount = recoveryRaw.length(),
            transitionRawCount = transitionRaw.length(),
            processedIdCount = processedIds().size,
            pendingCount = pending.size,
            newestEventId = newest?.eventId ?: "",
            newestFilename = newest?.filename ?: "",
            newestAction = newest?.action ?: "",
            newestInstanceId = newest?.playbackInstanceId ?: 0L,
        )
    }

    private fun readLedgerEvents(): List<Event> {
        return parseEvents(readRawLedgerArray())
    }

    private fun readRecoveryEvents(): List<Event> {
        return parseEvents(readRawRecoveryArray())
    }

    private fun readTransitionHistoryEvents(): List<Event> {
        return parseEvents(readRawTransitionArray())
    }

    private fun readRawLedgerArray(): JSONArray {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun readRawRecoveryArray(): JSONArray {
        val raw = recoveryPrefs.getString(RECOVERY_TRANSITIONS_KEY, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun readRawTransitionArray(): JSONArray {
        val raw = transitionPrefs.getString(TRANSITIONS_KEY, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun parseEvents(arr: JSONArray): List<Event> {
        val out = ArrayList<Event>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val instanceId = o.optLong("prevPlaybackInstanceId", o.optLong("playbackInstanceId", 0L))
            val filename = o.optString("prevFilename", o.optString("filename", ""))
            val eventId = o.optString("eventId", "").ifBlank {
                if (instanceId > 0L) "signal_$instanceId" else ""
            }
            val durationMs = o.optLong("prevDurationMs", o.optLong("durationMs", 0L))
            val playedMs = o.optLong("prevPlayedMs", o.optLong("playedMs", 0L))
            val fraction = when {
                o.has("prevFraction") -> o.optDouble("prevFraction", 0.0).toFloat()
                o.has("fraction") -> o.optDouble("fraction", 0.0).toFloat()
                durationMs > 0L -> playedMs.toFloat() / durationMs.toFloat()
                else -> 0f
            }.coerceIn(0f, 1f)
            out += Event(
                eventId = eventId,
                playbackInstanceId = instanceId,
                filename = filename,
                title = o.optString("prevTitle", o.optString("title", filename)),
                artist = o.optString("prevArtist", o.optString("artist", "")),
                album = o.optString("prevAlbum", o.optString("album", "")),
                playedMs = playedMs,
                durationMs = durationMs,
                fraction = fraction,
                action = o.optString("action", ""),
                createdAtMs = o.optLong("timestamp", o.optLong("createdAtMs", 0L)),
            )
        }
        return out
    }

    private fun processedIds(): Set<String> {
        val raw = prefs.getString(KEY_PROCESSED_IDS, null) ?: return emptySet()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptySet()
        return buildSet {
            for (i in 0 until arr.length()) {
                val id = arr.optString(i, "")
                if (id.isNotBlank()) add(id)
            }
        }
    }

    private fun idsToJson(ids: Set<String>): String {
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        return arr.toString()
    }

    private fun Set<String>.takeLastIds(limit: Int): Set<String> {
        if (size <= limit) return this
        return toList().takeLast(limit).toSet()
    }

    private fun JSONArray.filterObjects(keep: (JSONObject) -> Boolean): JSONArray {
        val out = JSONArray()
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            if (keep(o)) out.put(o)
        }
        return out
    }

    companion object {
        const val PREFS_NAME = "playback_signal_ledger_v1"
        const val KEY_EVENTS = "events_json"
        const val KEY_PROCESSED_IDS = "processed_event_ids_json"
        private const val MAX_PROCESSED_IDS = 1000

        private const val RECOVERY_PREFS_NAME = "playback_recovery_v1"
        private const val RECOVERY_TRANSITIONS_KEY = "recent_transitions"
        private const val TRANSITIONS_PREFS_NAME = "playback_transitions_history"
        private const val TRANSITIONS_KEY = "history_json"
    }
}
