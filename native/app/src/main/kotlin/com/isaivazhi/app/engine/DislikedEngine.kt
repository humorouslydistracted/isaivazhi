package com.isaivazhi.app.engine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Disliked songs — symmetric to FavoritesEngine. Disliked tracks are hidden
 * from "For You" / "Because You Played" / "Unexplored" sections and bias the
 * recommender away from their embedding. Toggling Dislike also clears
 * Favorite (mutually exclusive).
 */
class DislikedEngine(private val appContext: Context) {

    private val KEY = stringPreferencesKey("disliked_filenames")

    private val _disliked = MutableStateFlow<Set<String>>(emptySet())
    val disliked: StateFlow<Set<String>> = _disliked.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readyDeferred = CompletableDeferred<Unit>()

    /**
     * Hook invoked after any membership change (toggle/add/remove/clear).
     * AppContainer wires this to [TasteEngine.applyManualPriorChange] so
     * the per-song directScore reflects the new dislikePrior weight
     * immediately, without waiting for a playback event.
     */
    @Volatile var onChangeHook: ((filename: String, nowDisliked: Boolean) -> Unit)? = null

    init {
        scope.launch {
            try {
                val initial = appContext.dataStoreLocal.data
                    .map { it[KEY]?.split('\n')?.filter { fn -> fn.isNotBlank() }?.toSet() ?: emptySet() }
                    .first()
                _disliked.value = initial
            } catch (t: Throwable) {
                android.util.Log.w("DislikedEngine", "load failed: ${t.message}")
            } finally {
                if (!readyDeferred.isCompleted) readyDeferred.complete(Unit)
            }
        }
    }

    suspend fun awaitReady() {
        readyDeferred.await()
    }

    fun isDisliked(filename: String): Boolean = _disliked.value.contains(filename)

    fun toggle(filename: String): Boolean {
        val cur = _disliked.value
        val nowDisliked = !cur.contains(filename)
        _disliked.value = if (nowDisliked) cur + filename else cur - filename
        android.util.Log.i("DislikedEngine", "toggle filename=$filename now=$nowDisliked (size=${_disliked.value.size})")
        scope.launch { persist(_disliked.value) }
        onChangeHook?.invoke(filename, nowDisliked)
        return nowDisliked
    }

    fun add(filename: String) {
        if (_disliked.value.contains(filename)) return
        _disliked.value = _disliked.value + filename
        scope.launch { persist(_disliked.value) }
        onChangeHook?.invoke(filename, true)
    }

    /** Persist membership without firing [onChangeHook] — used during startup reconcile. */
    fun addSilent(filename: String) {
        if (_disliked.value.contains(filename)) return
        _disliked.value = _disliked.value + filename
        scope.launch { persist(_disliked.value) }
    }

    fun remove(filename: String) {
        if (!_disliked.value.contains(filename)) return
        _disliked.value = _disliked.value - filename
        scope.launch { persist(_disliked.value) }
        onChangeHook?.invoke(filename, false)
    }

    fun replaceAllFromExternal(next: Set<String>) {
        val sanitized = next.filter { it.isNotBlank() }.toSet()
        val cur = _disliked.value
        if (cur == sanitized) return
        _disliked.value = sanitized
        scope.launch { persist(sanitized) }
        val changed = cur union sanitized
        for (filename in changed) {
            val before = filename in cur
            val after = filename in sanitized
            if (before != after) onChangeHook?.invoke(filename, after)
        }
    }

    fun clear() {
        val prev = _disliked.value
        _disliked.value = emptySet()
        scope.launch { persist(emptySet()) }
        // Fire the hook for every previously-disliked song so taste
        // priors update on Reset Engine and bulk clears.
        val hook = onChangeHook
        if (hook != null) for (fn in prev) hook(fn, false)
    }

    private suspend fun persist(set: Set<String>) {
        try {
            appContext.dataStoreLocal.edit { it[KEY] = set.joinToString("\n") }
        } catch (t: Throwable) {
            android.util.Log.w("DislikedEngine", "persist failed: ${t.message}")
        }
    }
}
