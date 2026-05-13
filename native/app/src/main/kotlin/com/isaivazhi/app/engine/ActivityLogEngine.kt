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
 * Push #69: app-wide activity log for non-playback events (favorites
 * toggled, dislike toggled, tuning slider changes, shuffle on/off,
 * queue ops, reset events, etc). Capacitor parity: `engine.activity`
 * separate from playback SignalTimeline.
 *
 * Rolling buffer of last 200 entries persisted to DataStore. Used by
 * Taste page diagnostics and by potential future "what happened
 * recently" UI surfaces.
 */
class ActivityLogEngine(private val appContext: Context) {

    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val timestamp: Long,
        val category: String,  // "taste", "queue", "playback", "engine", "ui"
        val type: String,      // event type, e.g. "favorite_toggled"
        val message: String,
        val level: Level = Level.INFO,
        val data: String = "",  // free-form JSON-encoded payload
    )

    private val KEY = stringPreferencesKey("activity_log_v1_json")
    private val MAX_ENTRIES = 200

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            try {
                val raw = appContext.dataStoreLocal.data.first()[KEY] ?: return@launch
                _entries.value = parse(raw)
            } catch (t: Throwable) {
                android.util.Log.w("ActivityLog", "load failed: ${t.message}")
            }
        }
    }

    fun log(
        category: String,
        type: String,
        message: String,
        level: Level = Level.INFO,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val entry = Entry(
            timestamp = System.currentTimeMillis(),
            category = category,
            type = type,
            message = message,
            level = level,
            data = if (data.isEmpty()) "" else dataToJson(data),
        )
        android.util.Log.i("ActivityLog", "[$category/$type] $message")
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        scope.launch { persist() }
    }

    fun clear() {
        _entries.value = emptyList()
        scope.launch { persist() }
    }

    private fun dataToJson(data: Map<String, Any?>): String {
        return try {
            val o = JSONObject()
            for ((k, v) in data) {
                if (v == null) continue
                when (v) {
                    is Number -> o.put(k, v)
                    is Boolean -> o.put(k, v)
                    else -> o.put(k, v.toString())
                }
            }
            o.toString()
        } catch (_: Throwable) { "" }
    }

    private suspend fun persist() {
        try {
            val arr = JSONArray()
            for (e in _entries.value) {
                val o = JSONObject()
                o.put("ts", e.timestamp)
                o.put("category", e.category)
                o.put("type", e.type)
                o.put("message", e.message)
                o.put("level", e.level.name)
                if (e.data.isNotBlank()) o.put("data", e.data)
                arr.put(o)
            }
            appContext.dataStoreLocal.edit { it[KEY] = JSONObject().put("entries", arr).toString() }
        } catch (t: Throwable) {
            android.util.Log.w("ActivityLog", "persist failed: ${t.message}")
        }
    }

    private fun parse(raw: String): List<Entry> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Entry(
                timestamp = o.optLong("ts", 0L),
                category = o.optString("category", ""),
                type = o.optString("type", ""),
                message = o.optString("message", ""),
                level = runCatching { Level.valueOf(o.optString("level", "INFO")) }
                    .getOrDefault(Level.INFO),
                data = o.optString("data", ""),
            )
        }
        return out
    }
}
