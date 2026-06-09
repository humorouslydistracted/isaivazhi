package com.isaivazhi.app.engine

import android.content.Context
import org.json.JSONObject

/**
 * Persisted record of songs that the algorithm recently placed in Up Next.
 *
 * Purpose: prevent the recommendation engine from re-surfacing a song it
 * already queued until a real-world cooldown window has expired, regardless
 * of app restarts, recents clears, or device sleep.
 *
 * Only algorithm-generated placements are recorded here (refreshUpcomingWithAI,
 * auto-append at queue exhaust). Manual user taps are intentionally excluded so
 * the user can always replay a song they consciously chose.
 *
 * Storage: SharedPreferences ("algo_surfacing_cache") — lightweight key/value,
 * no DataStore migration required, survives process death.
 *
 * Thread safety: all mutations are dispatched from the IO-bound recommendation
 * coroutines; [record] and [recentlySurfaced] are synchronised on the
 * SharedPreferences edit lock and safe to call from any thread.
 */
class RecentlySurfacedTracker(private val appContext: Context) {

    companion object {
        /** Default cooldown: 6 hours. Songs won't re-appear in algorithm-built
         *  Up Next until this window has passed since they were last surfaced. */
        const val DEFAULT_COOLDOWN_MS: Long = 6 * 3600 * 1000L

        private const val PREFS_NAME = "algo_surfacing_cache"
        private const val KEY_TIMESTAMPS = "surfaced_timestamps_json"

        /** Cap the stored map size so stale entries don't accumulate forever. */
        private const val MAX_ENTRIES = 2_000
    }

    // In-memory mirror of the persisted map for fast reads on the hot path.
    // filename → surfacedAtMs
    @Volatile private var cache: HashMap<String, Long> = HashMap()

    init {
        loadFromPrefs()
    }

    /**
     * Record that the algorithm just placed [filenames] in Up Next.
     * Stamps each entry with [System.currentTimeMillis()].
     * Entries older than [DEFAULT_COOLDOWN_MS] * 2 are pruned to cap storage.
     */
    @Synchronized
    fun record(filenames: List<String>) {
        if (filenames.isEmpty()) return
        val now = System.currentTimeMillis()
        val updated = HashMap<String, Long>(cache)
        for (fn in filenames) {
            if (fn.isNotBlank()) updated[fn] = now
        }
        // Prune entries that are well past the cooldown window.
        val staleThreshold = now - DEFAULT_COOLDOWN_MS * 2
        val pruned = updated.entries
            .filter { it.value >= staleThreshold }
            .associate { it.key to it.value }
        val finalMap = if (pruned.size <= MAX_ENTRIES) {
            pruned
        } else {
            // Keep the most-recently surfaced entries when the cap is hit.
            pruned.entries.sortedByDescending { it.value }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        }
        cache = HashMap(finalMap)
        persistToPrefs(finalMap)
    }

    /**
     * Returns the set of filenames surfaced by the algorithm within
     * the last [cooldownMs] milliseconds. These should be passed as
     * additional excludes when calling [Recommender.recommendUpcoming].
     */
    @Synchronized
    fun recentlySurfaced(cooldownMs: Long = DEFAULT_COOLDOWN_MS): Set<String> {
        val cutoff = System.currentTimeMillis() - cooldownMs
        return cache.entries
            .filter { it.value >= cutoff }
            .map { it.key }
            .toHashSet()
    }

    /** Wipe all tracking data — called from [AppContainer.resetEngine]. */
    @Synchronized
    fun clear() {
        cache = HashMap()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    // ── persistence helpers ────────────────────────────────────────────────

    private fun loadFromPrefs() {
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_TIMESTAMPS, null) ?: return
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

    private fun persistToPrefs(map: Map<String, Long>) {
        try {
            val obj = JSONObject()
            for ((k, v) in map) obj.put(k, v)
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TIMESTAMPS, obj.toString())
                .apply()
        } catch (t: Throwable) {
            android.util.Log.w("RecentlySurfacedTracker", "persist failed: ${t.message}")
        }
    }
}
