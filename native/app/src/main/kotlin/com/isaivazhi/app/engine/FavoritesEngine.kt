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
            }
        }
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

    private suspend fun persist(set: Set<String>) {
        try {
            appContext.dataStoreLocal.edit { it[KEY] = set.joinToString("\n") }
        } catch (t: Throwable) {
            android.util.Log.w("FavoritesEngine", "persist failed: ${t.message}")
        }
    }
}
