package com.isaivazhi.app.engine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * Listen history + per-song stats.
 *
 *   - `events` is a rolling list of {filename, startedAt, fractionPlayed}
 *     entries capped at MAX_EVENTS. Most-recent first.
 *   - `stats` maps filename → {plays, lastPlayedAt, avgFraction}.
 *
 * The PlaybackEngine calls `recordStart(filename)` when a song begins and
 * `recordEnd(filename, fractionPlayed)` when it transitions away or stops.
 * Fraction comes from positionMs / durationMs at transition time. Both
 * persist asynchronously to DataStore.
 */
class HistoryEngine(private val appContext: Context) {

    data class Event(
        val filename: String,
        val startedAt: Long,
        val fractionPlayed: Float,
    )

    data class Stats(
        val plays: Int = 0,
        val lastPlayedAt: Long = 0L,
        val avgFraction: Float = 0f,
    )

    private val KEY_EVENTS = stringPreferencesKey("history_events_v1_json")
    private val KEY_STATS = stringPreferencesKey("history_stats_v1_json")
    private val MAX_EVENTS = 500

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val _stats = MutableStateFlow<Map<String, Stats>>(emptyMap())
    val stats: StateFlow<Map<String, Stats>> = _stats.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pendingStartFilename: String? = null
    private var pendingStartAt: Long = 0L

    init {
        scope.launch {
            try {
                val data = appContext.dataStoreLocal.data.first()
                data[KEY_EVENTS]?.let { _events.value = parseEvents(it) }
                data[KEY_STATS]?.let { _stats.value = parseStats(it) }
            } catch (t: Throwable) {
                android.util.Log.w("HistoryEngine", "load failed: ${t.message}")
            }
        }
    }

    fun recordStart(filename: String) {
        // If there's an existing in-flight song with no end-record, treat it
        // as fully played (assume Media3 transitioned cleanly) — this guards
        // against missing the onMediaItemTransition fraction signal.
        pendingStartFilename?.let { prev ->
            if (prev != filename) recordEnd(prev, fractionPlayed = 1f)
        }
        pendingStartFilename = filename
        pendingStartAt = System.currentTimeMillis()
    }

    fun recordEnd(filename: String, fractionPlayed: Float) {
        if (pendingStartFilename != filename) {
            // Stale — ignore.
            pendingStartFilename = null
            return
        }
        val frac = fractionPlayed.coerceIn(0f, 1f)
        val ev = Event(filename, pendingStartAt, frac)
        pendingStartFilename = null
        pendingStartAt = 0L

        val nextEvents = (listOf(ev) + _events.value).take(MAX_EVENTS)
        _events.value = nextEvents

        val prev = _stats.value[filename] ?: Stats()
        val nextStats = _stats.value + (filename to Stats(
            plays = prev.plays + 1,
            lastPlayedAt = ev.startedAt,
            // Running average — same formula as the JS app for parity.
            avgFraction = ((prev.avgFraction * prev.plays) + frac) / (prev.plays + 1),
        ))
        _stats.value = nextStats

        scope.launch { persist() }
    }

    /** Reset stats for a single song (per-row reset in Taste signals). */
    fun resetStatsForSong(filename: String) {
        if (filename.isBlank()) return
        if (!_stats.value.containsKey(filename)) return
        _stats.value = _stats.value - filename
        scope.launch { persist() }
    }

    /** Reset everything — used by the Taste "Clear all signals" action. */
    fun resetAllStats() {
        _events.value = emptyList()
        _stats.value = emptyMap()
        scope.launch { persist() }
    }

    private suspend fun persist() {
        try {
            val ea = JSONArray()
            for (e in _events.value) {
                val o = JSONObject()
                o.put("filename", e.filename)
                o.put("startedAt", e.startedAt)
                o.put("fractionPlayed", e.fractionPlayed.toDouble())
                ea.put(o)
            }
            val so = JSONObject()
            for ((fn, st) in _stats.value) {
                so.put(fn, JSONObject().apply {
                    put("plays", st.plays)
                    put("lastPlayedAt", st.lastPlayedAt)
                    put("avgFraction", st.avgFraction.toDouble())
                })
            }
            appContext.dataStoreLocal.edit {
                it[KEY_EVENTS] = JSONObject().apply { put("events", ea) }.toString()
                it[KEY_STATS] = so.toString()
            }
        } catch (t: Throwable) {
            android.util.Log.w("HistoryEngine", "persist failed: ${t.message}")
        }
    }

    private fun parseEvents(raw: String): List<Event> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Event>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Event(
                filename = o.optString("filename", ""),
                startedAt = o.optLong("startedAt", 0L),
                fractionPlayed = o.optDouble("fractionPlayed", 0.0).toFloat(),
            )
        }
        return out
    }

    private fun parseStats(raw: String): Map<String, Stats> {
        val o = JSONObject(raw)
        val out = HashMap<String, Stats>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optJSONObject(k) ?: continue
            out[k] = Stats(
                plays = v.optInt("plays", 0),
                lastPlayedAt = v.optLong("lastPlayedAt", 0L),
                avgFraction = v.optDouble("avgFraction", 0.0).toFloat(),
            )
        }
        return out
    }
}
