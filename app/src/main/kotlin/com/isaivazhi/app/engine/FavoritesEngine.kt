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
import kotlinx.coroutines.runBlocking

/**
 * Favorites — keyed by song filename (stable across re-scans, unlike song ID
 * which is assigned by scan order). Persisted to DataStore as a newline-
 * separated string. Same on-disk shape as the Capacitor build's
 * `favorites_v1` Preferences entry so users migrating data don't lose their
 * hearts.
 */
class FavoritesEngine(private val appContext: Context) {

    private val KEY = stringPreferencesKey("favorites_filenames")

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readyDeferred = CompletableDeferred<Unit>()

    /**
     * Hook invoked after any membership change (toggle/add/remove). Wired
     * by [AppContainer] to call [TasteEngine.applyManualPriorChange] so
     * the per-song directScore reflects the new favoritePrior weight.
     * Capacitor parity: `_recomputeTasteAfterFavorite` runs synchronously
     * after each favorite toggle.
     */
    @Volatile var onChangeHook: ((filename: String, nowFavorite: Boolean) -> Unit)? = null

    init {
        scope.launch {
            try {
                val initial = appContext.dataStoreLocal.data
                    .map { it[KEY]?.split('\n')?.filter { fn -> fn.isNotBlank() }?.toSet() ?: emptySet() }
                    .first()
                _favorites.value = initial
            } catch (t: Throwable) {
                android.util.Log.w("FavoritesEngine", "load failed: ${t.message}")
            } finally {
                if (!readyDeferred.isCompleted) readyDeferred.complete(Unit)
            }
        }
    }

    suspend fun awaitReady() {
        readyDeferred.await()
    }

    fun isFavorite(filename: String): Boolean = _favorites.value.contains(filename)

    fun toggle(filename: String) {
        val cur = _favorites.value
        val nowFavorite = !cur.contains(filename)
        val next = if (nowFavorite) cur + filename else cur - filename
        _favorites.value = next
        android.util.Log.i("FavoritesEngine", "toggle filename=$filename now=$nowFavorite (size=${next.size})")
        scope.launch { persist(next) }
        onChangeHook?.invoke(filename, nowFavorite)
    }

    fun add(filename: String) {
        if (_favorites.value.contains(filename)) return
        val next = _favorites.value + filename
        _favorites.value = next
        scope.launch { persist(next) }
        onChangeHook?.invoke(filename, true)
    }

    fun remove(filename: String) {
        if (!_favorites.value.contains(filename)) return
        val next = _favorites.value - filename
        _favorites.value = next
        scope.launch { persist(next) }
        onChangeHook?.invoke(filename, false)
    }

    /**
     * Push #74: idempotent setter used by the MediaController.Listener when
     * the notification's Favorite button is tapped. No-op if the requested
     * state already matches — this breaks the feedback loop where a
     * notification tap → SP write → controller listener → setExplicit
     * would otherwise re-fire onChangeHook indefinitely.
     */
    fun setExplicit(filename: String, isFavorite: Boolean) {
        val currently = _favorites.value.contains(filename)
        if (currently == isFavorite) return
        if (isFavorite) add(filename) else remove(filename)
    }

    fun replaceAllFromExternal(next: Set<String>) {
        val sanitized = next.filter { it.isNotBlank() }.toSet()
        val cur = _favorites.value
        if (cur == sanitized) return
        _favorites.value = sanitized
        scope.launch { persist(sanitized) }
        val changed = cur union sanitized
        for (filename in changed) {
            val before = filename in cur
            val after = filename in sanitized
            if (before != after) onChangeHook?.invoke(filename, after)
        }
    }

    private suspend fun persist(set: Set<String>) {
        try {
            appContext.dataStoreLocal.edit { it[KEY] = set.joinToString("\n") }
        } catch (t: Throwable) {
            android.util.Log.w("FavoritesEngine", "persist failed: ${t.message}")
        }
    }
}
