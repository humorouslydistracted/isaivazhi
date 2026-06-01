package com.isaivazhi.app.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Typed wrapper over androidx.datastore.preferences. Replaces the Capacitor
 * `Preferences` plugin from the JS app.
 *
 * Hot-path reads are async (Flow). Cold-start critical reads use `readSync()`
 * which blocks the calling coroutine briefly — fine because callers should
 * be on a worker dispatcher, never on Main.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "isaivazhi_prefs")

class AppPreferences(private val appContext: Context) {

    private object Keys {
        val CURRENT_MEDIA_ID = stringPreferencesKey("current_media_id")
        val CURRENT_POSITION_MS = longPreferencesKey("current_position_ms")
        val QUEUE_FILENAMES = stringPreferencesKey("queue_filenames")  // newline-separated
        val QUEUE_INDEX = longPreferencesKey("queue_index")            // current index in the queue
        val UP_NEXT_REC_MODE = booleanPreferencesKey("up_next_rec_mode_v1")  // true=AI, false=Shuffle
        // Push #65: belt-and-suspenders evidence record for cases where
        // MainActivity.onPause flushes a signal event in memory but the
        // process is killed before the timeline persist completes. On
        // next cold start, MainActivity reads these three and ingests
        // them via taste.recordPlaybackEvent + signalTimeline.append,
        // then clears them.
        val PENDING_EVIDENCE_MEDIA_ID = stringPreferencesKey("pending_evidence_media_id")
        val PENDING_EVIDENCE_PLAYED_MS = longPreferencesKey("pending_evidence_played_ms")
        val PENDING_EVIDENCE_DURATION_MS = longPreferencesKey("pending_evidence_duration_ms")
        // Push #66: playbackInstanceId correlates the snapshot with the
        // native transitions history so cold-start reconciliation can
        // detect "this listen was already finalized by a real transition"
        // vs "this listen was lost when the app went away".
        val PENDING_EVIDENCE_INSTANCE_ID = longPreferencesKey("pending_evidence_instance_id")
        // Push #68: profileVec disk cache. Saved after compute, loaded on
        // cold start so the first Discover render uses a real centroid
        // instead of waiting for the LE to recompute.
        val PROFILE_VEC_JSON = stringPreferencesKey("profile_vec_v1_json")
        val PROFILE_VEC_FINGERPRINT = stringPreferencesKey("profile_vec_fingerprint_v1")
        // Push #74: monotonic watermark on the highest playbackInstanceId
        // already credited to TasteEngine. Used by cold-start replay of the
        // service transitions buffer to skip entries already captured live
        // (by PlaybackEngine.onMediaItemTransition) and by snapshot
        // reconciliation to skip events covered by the buffer drain.
        val LAST_INGESTED_PLAYBACK_INSTANCE_ID = longPreferencesKey("last_ingested_playback_instance_id")
        // Push #76: one-time migration flag. Push #74/#75 bumped this
        // watermark with the NEW song's instId (a bug fixed in #76 by
        // reading lastTransitionPrevPlaybackInstanceId from the service);
        // on devices that ran #74/#75 the watermark sits at the wrong
        // (too-high) value and silently blocks every legitimate buffer
        // replay. Clearing it once on the first #76 boot lets the
        // accumulated background auto-advance entries flow through.
        val MIGRATION_V76_WATERMARK_RESET = booleanPreferencesKey("migration_v76_watermark_reset")
        // 2026-06-01 startup-perf fix: cache the last-known embedding row count
        // so the UI can publish a non-zero embeddingsRowCount immediately on
        // cold start (unblocking AI Discover) while the actual DB stats() call
        // still runs in background to reconcile. Without this, the UI gates
        // AI Discover behind the first DB worker call which pays the full
        // sqlite-vec/Room init cost (~18s on the user's device).
        val CACHED_EMBED_ROW_COUNT = longPreferencesKey("cached_embed_row_count_v1")
        val CACHED_EMBED_DIM = longPreferencesKey("cached_embed_dim_v1")
        val CACHED_VEC_EXT = booleanPreferencesKey("cached_vec_ext_v1")
        // 2026-06-01 optimistic MiniPlayer: persist current song's display
        // metadata so PlaybackEngine.preWarm() can hydrate _state.value
        // immediately on cold start (MiniPlayer renders in <500ms instead
        // of waiting ~1.9s for service prepareForResume to complete).
        val LAST_TITLE = stringPreferencesKey("last_title_v1")
        val LAST_ARTIST = stringPreferencesKey("last_artist_v1")
        val LAST_ALBUM = stringPreferencesKey("last_album_v1")
        // Phase 4 (2026-06-01): persist the bottom-nav tab the user last
        // viewed. Restored on cold start so the app re-opens where they
        // left off. Empty string on first-ever launch → caller picks the
        // default (Songs).
        val LAST_TAB = stringPreferencesKey("last_tab_v1")
    }

    val recMode: kotlinx.coroutines.flow.Flow<Boolean> =
        appContext.dataStore.data.map { it[Keys.UP_NEXT_REC_MODE] ?: true }

    suspend fun setRecMode(aiMode: Boolean) {
        appContext.dataStore.edit { it[Keys.UP_NEXT_REC_MODE] = aiMode }
    }

    val currentMediaId: Flow<String?> =
        appContext.dataStore.data.map { it[Keys.CURRENT_MEDIA_ID] }

    val currentPositionMs: Flow<Long> =
        appContext.dataStore.data.map { it[Keys.CURRENT_POSITION_MS] ?: 0L }

    val queueFilenames: Flow<List<String>> =
        appContext.dataStore.data.map { prefs ->
            prefs[Keys.QUEUE_FILENAMES]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        }

    val queueIndex: Flow<Int> =
        appContext.dataStore.data.map { (it[Keys.QUEUE_INDEX] ?: 0L).toInt() }

    /** One-shot snapshot — useful at app launch for the cold-start prepare. */
    suspend fun snapshot(): Snapshot {
        val prefs = appContext.dataStore.data.first()
        return Snapshot(
            mediaId = prefs[Keys.CURRENT_MEDIA_ID],
            positionMs = prefs[Keys.CURRENT_POSITION_MS] ?: 0L,
            queueFilenames = prefs[Keys.QUEUE_FILENAMES]
                ?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
            queueIndex = (prefs[Keys.QUEUE_INDEX] ?: 0L).toInt(),
            title = prefs[Keys.LAST_TITLE] ?: "",
            artist = prefs[Keys.LAST_ARTIST] ?: "",
            album = prefs[Keys.LAST_ALBUM] ?: "",
        )
    }

    data class Snapshot(
        val mediaId: String?,
        val positionMs: Long,
        val queueFilenames: List<String>,
        val queueIndex: Int,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
    )

    suspend fun saveCurrent(
        mediaId: String?,
        positionMs: Long,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
    ) {
        appContext.dataStore.edit { prefs ->
            if (mediaId == null) {
                prefs.remove(Keys.CURRENT_MEDIA_ID)
            } else {
                prefs[Keys.CURRENT_MEDIA_ID] = mediaId
            }
            prefs[Keys.CURRENT_POSITION_MS] = positionMs.coerceAtLeast(0L)
            // Only overwrite display metadata when caller supplies it — preserves
            // the previously persisted values during fast position-only updates.
            title?.let { prefs[Keys.LAST_TITLE] = it }
            artist?.let { prefs[Keys.LAST_ARTIST] = it }
            album?.let { prefs[Keys.LAST_ALBUM] = it }
        }
    }

    /** Cached embedding row count snapshot read at cold start (non-blocking). */
    suspend fun cachedEmbedStats(): CachedEmbedStats {
        val prefs = appContext.dataStore.data.first()
        return CachedEmbedStats(
            rowCount = (prefs[Keys.CACHED_EMBED_ROW_COUNT] ?: 0L).toInt(),
            dim = (prefs[Keys.CACHED_EMBED_DIM] ?: 0L).toInt(),
            vecExt = prefs[Keys.CACHED_VEC_EXT] ?: false,
        )
    }

    suspend fun saveCachedEmbedStats(rowCount: Int, dim: Int, vecExt: Boolean) {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.CACHED_EMBED_ROW_COUNT] = rowCount.coerceAtLeast(0).toLong()
            prefs[Keys.CACHED_EMBED_DIM] = dim.coerceAtLeast(0).toLong()
            prefs[Keys.CACHED_VEC_EXT] = vecExt
        }
    }

    data class CachedEmbedStats(
        val rowCount: Int,
        val dim: Int,
        val vecExt: Boolean,
    )

    /** Phase 4 (2026-06-01): last bottom-nav tab name, or empty on first launch. */
    suspend fun loadLastTab(): String {
        return appContext.dataStore.data.first()[Keys.LAST_TAB] ?: ""
    }

    suspend fun saveLastTab(tabName: String) {
        appContext.dataStore.edit { it[Keys.LAST_TAB] = tabName }
    }

    suspend fun saveQueue(filenames: List<String>, index: Int) {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.QUEUE_FILENAMES] = filenames.joinToString("\n")
            prefs[Keys.QUEUE_INDEX] = index.toLong()
        }
    }

    /**
     * Push #65: persist a pending evidence record describing the current
     * song + its accumulator state. Used by MainActivity.onPause as a
     * backup in case the in-memory flush doesn't reach SignalTimeline
     * persistence before the process dies.
     */
    suspend fun savePendingEvidence(
        mediaId: String,
        playedMs: Long,
        durationMs: Long,
        playbackInstanceId: Long = 0L,
    ) {
        appContext.dataStore.edit { prefs ->
            prefs[Keys.PENDING_EVIDENCE_MEDIA_ID] = mediaId
            prefs[Keys.PENDING_EVIDENCE_PLAYED_MS] = playedMs.coerceAtLeast(0L)
            prefs[Keys.PENDING_EVIDENCE_DURATION_MS] = durationMs.coerceAtLeast(0L)
            prefs[Keys.PENDING_EVIDENCE_INSTANCE_ID] = playbackInstanceId
        }
    }

    suspend fun clearPendingEvidence() {
        appContext.dataStore.edit { prefs ->
            prefs.remove(Keys.PENDING_EVIDENCE_MEDIA_ID)
            prefs.remove(Keys.PENDING_EVIDENCE_PLAYED_MS)
            prefs.remove(Keys.PENDING_EVIDENCE_DURATION_MS)
            prefs.remove(Keys.PENDING_EVIDENCE_INSTANCE_ID)
        }
    }

    data class PendingEvidence(
        val mediaId: String,
        val playedMs: Long,
        val durationMs: Long,
        val playbackInstanceId: Long,
    )

    /**
     * Push #68: persist the latest profileVec to disk. Stored as a JSON
     * float[] + a fingerprint (hash of the top-30 anchor filenames) so
     * we can detect when the source data has changed.
     */
    suspend fun saveProfileVec(vec: FloatArray, fingerprint: String) {
        val arr = org.json.JSONArray()
        for (f in vec) arr.put(f.toDouble())
        appContext.dataStore.edit { prefs ->
            prefs[Keys.PROFILE_VEC_JSON] = arr.toString()
            prefs[Keys.PROFILE_VEC_FINGERPRINT] = fingerprint
        }
    }

    data class ProfileVecCache(val vec: FloatArray, val fingerprint: String)

    suspend fun loadProfileVec(): ProfileVecCache? {
        val prefs = appContext.dataStore.data.first()
        val raw = prefs[Keys.PROFILE_VEC_JSON] ?: return null
        val fp = prefs[Keys.PROFILE_VEC_FINGERPRINT] ?: return null
        return try {
            val arr = org.json.JSONArray(raw)
            val out = FloatArray(arr.length()) { i -> arr.optDouble(i, 0.0).toFloat() }
            if (out.isEmpty()) null else ProfileVecCache(out, fp)
        } catch (_: Throwable) { null }
    }

    suspend fun loadPendingEvidence(): PendingEvidence? {
        val prefs = appContext.dataStore.data.first()
        val id = prefs[Keys.PENDING_EVIDENCE_MEDIA_ID] ?: return null
        val played = prefs[Keys.PENDING_EVIDENCE_PLAYED_MS] ?: return null
        val dur = prefs[Keys.PENDING_EVIDENCE_DURATION_MS] ?: return null
        val instId = prefs[Keys.PENDING_EVIDENCE_INSTANCE_ID] ?: 0L
        if (id.isBlank() || played < 1000L || dur <= 0L) return null
        return PendingEvidence(id, played, dur, instId)
    }

    /**
     * Push #74: read the highest playbackInstanceId already credited to the
     * taste engine. Returns 0L when no listen has been recorded yet.
     */
    suspend fun loadIngestWatermark(): Long {
        return appContext.dataStore.data.first()[Keys.LAST_INGESTED_PLAYBACK_INSTANCE_ID] ?: 0L
    }

    /**
     * Push #74: monotonically bump the watermark. Writes only when
     * `instanceId` exceeds the stored value, preventing a stale cold-start
     * replay from overwriting a fresher live transition's watermark.
     */
    suspend fun saveIngestWatermark(instanceId: Long) {
        if (instanceId <= 0L) return
        appContext.dataStore.edit { prefs ->
            val current = prefs[Keys.LAST_INGESTED_PLAYBACK_INSTANCE_ID] ?: 0L
            if (instanceId > current) prefs[Keys.LAST_INGESTED_PLAYBACK_INSTANCE_ID] = instanceId
        }
    }

    /**
     * Push #76: one-shot watermark reset. Returns true if the migration ran
     * (so the cold-start LE can log it), false if it was already done.
     * Clears LAST_INGESTED_PLAYBACK_INSTANCE_ID so drainTransitionsBuffer
     * can finally process the buffer entries that #74/#75's inflated
     * watermark was silently filtering out.
     */
    suspend fun runV76WatermarkResetIfNeeded(): Boolean {
        val prefs = appContext.dataStore.data.first()
        if (prefs[Keys.MIGRATION_V76_WATERMARK_RESET] == true) return false
        appContext.dataStore.edit { p ->
            p.remove(Keys.LAST_INGESTED_PLAYBACK_INSTANCE_ID)
            p[Keys.MIGRATION_V76_WATERMARK_RESET] = true
        }
        return true
    }

    /** Wipes saved current-song + queue. Used by AppContainer.resetEngine(). */
    suspend fun clear() {
        appContext.dataStore.edit { prefs ->
            prefs.remove(Keys.CURRENT_MEDIA_ID)
            prefs.remove(Keys.CURRENT_POSITION_MS)
            prefs.remove(Keys.QUEUE_FILENAMES)
            prefs.remove(Keys.QUEUE_INDEX)
            prefs.remove(Keys.PENDING_EVIDENCE_MEDIA_ID)
            prefs.remove(Keys.PENDING_EVIDENCE_PLAYED_MS)
            prefs.remove(Keys.PENDING_EVIDENCE_DURATION_MS)
            prefs.remove(Keys.PENDING_EVIDENCE_INSTANCE_ID)
            prefs.remove(Keys.PROFILE_VEC_JSON)
            prefs.remove(Keys.PROFILE_VEC_FINGERPRINT)
            prefs.remove(Keys.LAST_INGESTED_PLAYBACK_INSTANCE_ID)
        }
    }
}
