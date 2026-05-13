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
        )
    }

    data class Snapshot(
        val mediaId: String?,
        val positionMs: Long,
        val queueFilenames: List<String>,
        val queueIndex: Int,
    )

    suspend fun saveCurrent(mediaId: String?, positionMs: Long) {
        appContext.dataStore.edit { prefs ->
            if (mediaId == null) {
                prefs.remove(Keys.CURRENT_MEDIA_ID)
            } else {
                prefs[Keys.CURRENT_MEDIA_ID] = mediaId
            }
            prefs[Keys.CURRENT_POSITION_MS] = positionMs.coerceAtLeast(0L)
        }
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
        }
    }
}
