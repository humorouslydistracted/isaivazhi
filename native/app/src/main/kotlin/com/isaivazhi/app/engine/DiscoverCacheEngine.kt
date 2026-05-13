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
 * Push #64: persistent snapshot of the Discover page's section state so
 * cold start renders instantly from cache while fresh computation runs in
 * the background. Capacitor parity: `lastProfile` +
 * `renderDiscoverSnapshotFromCache` flow.
 *
 * Stores only filename lists (not Song objects); MainActivity hydrates by
 * looking up the current library snapshot at render time. This avoids
 * staleness if a song was renamed/deleted between cache write and read.
 *
 * Each cache write is async; reads are one-shot during init. Invalidated
 * on Reset Engine.
 */
class DiscoverCacheEngine(private val appContext: Context) {

    data class Snapshot(
        val mostSimilarFilenames: List<String> = emptyList(),
        val forYouFilenames: List<String> = emptyList(),
        val byp: List<BypEntry> = emptyList(),
        val unexploredFilenamesByCluster: List<List<String>> = emptyList(),
        val computedAt: Long = 0L,
        val currentMediaId: String? = null,
        // Push #68: recommendation policy fingerprint at save time. On
        // cold start, if the live fingerprint differs from this one, the
        // cache is treated as stale and a fresh build is preferred.
        // Capacitor parity (`recommendationFingerprint` in
        // `validateDiscoverCache`).
        val recommendationFingerprint: String = "",
    )

    data class BypEntry(
        val anchorFilename: String,
        val anchorTitle: String,
        val recommendationFilenames: List<String>,
    )

    private val KEY = stringPreferencesKey("discover_cache_v1_json")

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** True once the initial load has completed (success or failure). */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            try {
                val raw = appContext.dataStoreLocal.data.first()[KEY]
                if (raw != null) _snapshot.value = parse(raw)
            } catch (t: Throwable) {
                android.util.Log.w("DiscoverCache", "load failed: ${t.message}")
            } finally {
                _loaded.value = true
            }
        }
    }

    /**
     * Save the current Discover sections to disk. Caller passes filename
     * lists derived from the live recomputed state. No-op when every
     * section is empty (avoids overwriting a useful cache with an empty
     * mid-load snapshot).
     */
    fun save(
        mostSimilarFilenames: List<String>,
        forYouFilenames: List<String>,
        byp: List<BypEntry>,
        unexploredFilenamesByCluster: List<List<String>>,
        currentMediaId: String?,
        recommendationFingerprint: String = "",
    ) {
        if (mostSimilarFilenames.isEmpty() &&
            forYouFilenames.isEmpty() &&
            byp.all { it.recommendationFilenames.isEmpty() } &&
            unexploredFilenamesByCluster.all { it.isEmpty() }
        ) return
        val snap = Snapshot(
            mostSimilarFilenames = mostSimilarFilenames,
            forYouFilenames = forYouFilenames,
            byp = byp,
            unexploredFilenamesByCluster = unexploredFilenamesByCluster,
            computedAt = System.currentTimeMillis(),
            currentMediaId = currentMediaId,
            recommendationFingerprint = recommendationFingerprint,
        )
        _snapshot.value = snap
        scope.launch { persist(snap) }
    }

    fun clear() {
        _snapshot.value = Snapshot()
        scope.launch { persist(Snapshot()) }
    }

    private suspend fun persist(snap: Snapshot) {
        try {
            val root = JSONObject()
            root.put("mostSimilar", JSONArray(snap.mostSimilarFilenames))
            root.put("forYou", JSONArray(snap.forYouFilenames))
            val bypArr = JSONArray()
            for (e in snap.byp) {
                bypArr.put(JSONObject().apply {
                    put("anchorFilename", e.anchorFilename)
                    put("anchorTitle", e.anchorTitle)
                    put("recs", JSONArray(e.recommendationFilenames))
                })
            }
            root.put("byp", bypArr)
            val unArr = JSONArray()
            for (cluster in snap.unexploredFilenamesByCluster) unArr.put(JSONArray(cluster))
            root.put("unexplored", unArr)
            root.put("computedAt", snap.computedAt)
            root.put("recommendationFingerprint", snap.recommendationFingerprint)
            snap.currentMediaId?.let { root.put("currentMediaId", it) }
            appContext.dataStoreLocal.edit { it[KEY] = root.toString() }
        } catch (t: Throwable) {
            android.util.Log.w("DiscoverCache", "persist failed: ${t.message}")
        }
    }

    private fun parse(raw: String): Snapshot {
        return try {
            val o = JSONObject(raw)
            Snapshot(
                mostSimilarFilenames = jsonArrayToStringList(o.optJSONArray("mostSimilar")),
                forYouFilenames = jsonArrayToStringList(o.optJSONArray("forYou")),
                byp = parseByp(o.optJSONArray("byp")),
                unexploredFilenamesByCluster = parseClusters(o.optJSONArray("unexplored")),
                computedAt = o.optLong("computedAt", 0L),
                currentMediaId = o.optString("currentMediaId", "").takeIf { it.isNotBlank() },
                recommendationFingerprint = o.optString("recommendationFingerprint", ""),
            )
        } catch (t: Throwable) {
            android.util.Log.w("DiscoverCache", "parse failed: ${t.message}")
            Snapshot()
        }
    }

    private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    private fun parseByp(arr: JSONArray?): List<BypEntry> {
        if (arr == null) return emptyList()
        val out = ArrayList<BypEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += BypEntry(
                anchorFilename = o.optString("anchorFilename", ""),
                anchorTitle = o.optString("anchorTitle", ""),
                recommendationFilenames = jsonArrayToStringList(o.optJSONArray("recs")),
            )
        }
        return out
    }

    private fun parseClusters(arr: JSONArray?): List<List<String>> {
        if (arr == null) return emptyList()
        val out = ArrayList<List<String>>(arr.length())
        for (i in 0 until arr.length()) {
            out += jsonArrayToStringList(arr.optJSONArray(i))
        }
        return out
    }
}
