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

/**
 * Push #57: persistent set of filepaths the user has chosen NOT to embed —
 * typically files whose audio Android's MediaExtractor can't decode (unusual
 * FLAC variants, broken containers, etc.). Adding a filepath here makes the
 * AI page's Pending stat ignore it, so the user isn't nagged by an
 * infinitely-failing song.
 *
 * Scope (deliberately narrow):
 *   - Affects the Pending derivation in `AiManagementScreen` only.
 *   - Does NOT block the per-row "Embed this song" icon — explicit user
 *     action overrides the skip (e.g. the user re-encoded the file and
 *     wants to retry).
 *   - Does NOT affect playback. The song still plays normally; when it's
 *     the recommendation anchor, the recommender falls back to shuffled
 *     tail (same behavior as any other un-embedded song).
 *
 * Keyed by filepath (not filename or content_hash) so renaming/moving
 * the file naturally un-skips it.
 */
class SkippedEmbeddingsEngine(private val appContext: Context) {

    private val KEY = stringPreferencesKey("skipped_embedding_filepaths")

    private val _skipped = MutableStateFlow<Set<String>>(emptySet())
    val skipped: StateFlow<Set<String>> = _skipped.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            try {
                val initial = appContext.dataStoreLocal.data.first()
                _skipped.value = initial[KEY]
                    ?.split('\n')
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    ?: emptySet()
            } catch (t: Throwable) {
                android.util.Log.w("SkippedEmbeddingsEngine", "load failed: ${t.message}")
            }
        }
    }

    fun isSkipped(filepath: String): Boolean = _skipped.value.contains(filepath)

    fun add(filepath: String) {
        if (filepath.isBlank() || _skipped.value.contains(filepath)) return
        _skipped.value = _skipped.value + filepath
        android.util.Log.i("SkippedEmbeddingsEngine", "add filepath=$filepath (size=${_skipped.value.size})")
        scope.launch { persist(_skipped.value) }
    }

    fun remove(filepath: String) {
        if (!_skipped.value.contains(filepath)) return
        _skipped.value = _skipped.value - filepath
        android.util.Log.i("SkippedEmbeddingsEngine", "remove filepath=$filepath (size=${_skipped.value.size})")
        scope.launch { persist(_skipped.value) }
    }

    fun clear() {
        _skipped.value = emptySet()
        scope.launch { persist(emptySet()) }
    }

    private suspend fun persist(set: Set<String>) {
        try {
            appContext.dataStoreLocal.edit { it[KEY] = set.joinToString("\n") }
        } catch (t: Throwable) {
            android.util.Log.w("SkippedEmbeddingsEngine", "persist failed: ${t.message}")
        }
    }
}
