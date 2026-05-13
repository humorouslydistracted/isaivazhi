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

    /** Wipes saved current-song + queue. Used by AppContainer.resetEngine(). */
    suspend fun clear() {
        appContext.dataStore.edit { prefs ->
            prefs.remove(Keys.CURRENT_MEDIA_ID)
            prefs.remove(Keys.CURRENT_POSITION_MS)
            prefs.remove(Keys.QUEUE_FILENAMES)
            prefs.remove(Keys.QUEUE_INDEX)
        }
    }
}
