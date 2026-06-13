package com.isaivazhi.app.engine

import android.content.Context
import org.json.JSONObject

/**
 * Persisted recommendation cooldown: songs that entered the player from AI
 * recommendation flows and must not be re-suggested until a listening-time
 * budget expires.
 *
 * Cooldown is **listening-time bound**, not wall-clock bound: a 6-hour budget
 * decays only while music is actively playing (`isPlaying`). App-closed or
 * paused time does not count.
 *
 * Recorded when a song becomes the current track (play, skip, auto-advance).
 * Manual picks (Songs / Albums / Browse), Play Next, and unplayed Up Next
 * rows are excluded — see [shouldRecordRecommendationCooldown].
 *
 * Storage: SharedPreferences ("algo_surfacing_cache"), survives process death.
 */
class RecentlySurfacedTracker(private val appContext: Context) {

    data class ActiveEntry(
        val filename: String,
        val recordedAtListeningMs: Long,
        val remainingMs: Long,
    )

    companion object {
        /** 6 hours of active playback time before a song re-enters AI picks. */
        const val DEFAULT_COOLDOWN_MS: Long = 6 * 3600 * 1000L

        private const val PREFS_NAME = "algo_surfacing_cache"
        private const val KEY_STORAGE_VERSION = "storage_version"
        private const val KEY_LISTENING_CLOCK_MS = "listening_clock_ms"
        private const val KEY_ENTRIES = "cooldown_entries_json"
        /** Legacy v1 wall-clock key — discarded on migration. */
        private const val KEY_TIMESTAMPS_LEGACY = "surfaced_timestamps_json"

        private const val STORAGE_VERSION = 2
        private const val MAX_ENTRIES = 2_000
        /** Persist listening clock at most every 30s of advancement. */
        private const val CLOCK_PERSIST_INTERVAL_MS = 30_000L
    }

    // filename → recordedAtListeningMs
    @Volatile private var cache: HashMap<String, Long> = HashMap()
    @Volatile private var listeningClockMs: Long = 0L
    @Volatile private var lastPersistedClockMs: Long = 0L

    init {
        loadFromPrefs()
    }

    /**
     * Advance the global listening clock while music is playing.
     * Called from [PlaybackEngine] position poll (~500ms) when `isPlaying`.
     */
    @Synchronized
    fun advanceListeningClock(deltaMs: Long) {
        if (deltaMs <= 0L) return
        listeningClockMs += deltaMs
        if (listeningClockMs - lastPersistedClockMs >= CLOCK_PERSIST_INTERVAL_MS) {
            persistAll()
            lastPersistedClockMs = listeningClockMs
        }
    }

    /**
     * Record that [filenames] entered the player from an AI-eligible flow.
     * Stamps each at the current [listeningClockMs] (resets the 6h budget).
     */
    @Synchronized
    fun record(filenames: List<String>) {
        if (filenames.isEmpty()) return
        val updated = HashMap<String, Long>(cache)
        for (fn in filenames) {
            if (fn.isNotBlank()) updated[fn] = listeningClockMs
        }
        val pruned = pruneEntries(updated)
        cache = HashMap(pruned)
        persistAll()
    }

    @Synchronized
    fun activeEntries(cooldownMs: Long = DEFAULT_COOLDOWN_MS): List<ActiveEntry> {
        val clock = listeningClockMs
        return cache.entries
            .map { (filename, recordedAt) ->
                val elapsed = clock - recordedAt
                val remaining = (cooldownMs - elapsed).coerceAtLeast(0L)
                ActiveEntry(filename, recordedAt, remaining)
            }
            .filter { it.remainingMs > 0L }
            .sortedBy { it.remainingMs }
    }

    @Synchronized
    fun recentlySurfaced(cooldownMs: Long = DEFAULT_COOLDOWN_MS): Set<String> =
        activeEntries(cooldownMs).map { it.filename }.toHashSet()

    @Synchronized
    fun remove(filename: String): Boolean {
        if (filename.isBlank()) return false
        val updated = HashMap(cache)
        if (updated.remove(filename) == null) return false
        cache = updated
        persistAll()
        return true
    }

    /** Release every song from cooldown (Resumes In → Clear all). */
    @Synchronized
    fun clearAll() {
        cache = HashMap()
        persistAll()
    }

    /** Wipe cooldown entries — called from [AppContainer.resetEngine]. */
    @Synchronized
    fun clear() {
        clearAll()
    }

    private fun pruneEntries(map: Map<String, Long>): Map<String, Long> {
        val clock = listeningClockMs
        val staleThreshold = clock - DEFAULT_COOLDOWN_MS * 2
        val pruned = map.entries
            .filter { it.value >= staleThreshold }
            .associate { it.key to it.value }
        return if (pruned.size <= MAX_ENTRIES) {
            pruned
        } else {
            pruned.entries.sortedByDescending { it.value }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        }
    }

    private fun loadFromPrefs() {
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val version = prefs.getInt(KEY_STORAGE_VERSION, 0)
            if (version < STORAGE_VERSION) {
                android.util.Log.w(
                    "RecentlySurfacedTracker",
                    "migrating storage v$version → v$STORAGE_VERSION (legacy cooldown entries discarded)",
                )
                prefs.edit().clear().apply()
                return
            }
            listeningClockMs = prefs.getLong(KEY_LISTENING_CLOCK_MS, 0L)
            lastPersistedClockMs = listeningClockMs
            val raw = prefs.getString(KEY_ENTRIES, null) ?: return
            val obj = JSONObject(raw)
            val map = HashMap<String, Long>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optLong(k, 0L)
            }
            cache = map
        } catch (t: Throwable) {
            android.util.Log.w("RecentlySurfacedTracker", "load failed: ${t.message}")
        }
    }

    private fun persistAll() {
        try {
            val obj = JSONObject()
            for ((k, v) in cache) obj.put(k, v)
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_STORAGE_VERSION, STORAGE_VERSION)
                .putLong(KEY_LISTENING_CLOCK_MS, listeningClockMs)
                .putString(KEY_ENTRIES, obj.toString())
                .remove(KEY_TIMESTAMPS_LEGACY)
                .apply()
            lastPersistedClockMs = listeningClockMs
        } catch (t: Throwable) {
            android.util.Log.w("RecentlySurfacedTracker", "persist failed: ${t.message}")
        }
    }
}

/**
 * Whether [enteringFilename] should enter the recommendation cooldown when it
 * becomes the current track.
 */
fun shouldRecordRecommendationCooldown(
    enteringFilename: String,
    prevState: PlaybackEngine.PlaybackState,
    transitionSource: String,
): Boolean {
    if (enteringFilename.isBlank()) return false
    if (enteringFilename in prevState.playNextFilenames) return false
    if (prevState.queueContext == PlaybackEngine.QueueContext.ALBUM ||
        prevState.queueContext == PlaybackEngine.QueueContext.BROWSE_SECTION
    ) {
        return false
    }
    if (transitionSource == "manual_tap" ||
        transitionSource == "album" ||
        transitionSource == "browse_section"
    ) {
        return false
    }
    return prevState.queueContext == PlaybackEngine.QueueContext.LIBRARY ||
        prevState.queueContext == PlaybackEngine.QueueContext.DISCOVER_SECTION ||
        prevState.queueContext == PlaybackEngine.QueueContext.AI_RECOMMENDED
}
