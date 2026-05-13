package com.isaivazhi.app.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Single shared DataStore for the secondary engines (Favorites, Playlists,
 * History). AppPreferences already owns "isaivazhi_prefs" for the
 * playback-state hot path; this one is for everything else so a slow disk
 * write on, say, a 5000-entry listen log doesn't compete with the
 * playback-state save on the same DataStore instance.
 */
internal val Context.dataStoreLocal: DataStore<Preferences> by preferencesDataStore(name = "isaivazhi_library_prefs")
