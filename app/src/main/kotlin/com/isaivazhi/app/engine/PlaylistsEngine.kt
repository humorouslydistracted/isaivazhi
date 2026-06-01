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
 * User-defined playlists. Each playlist has a stable id, a name, and an
 * ordered list of song filenames (filename is stable across rescans; id is
 * not). Persisted to DataStore as one JSON blob.
 */
class PlaylistsEngine(private val appContext: Context) {

    data class Playlist(
        val id: String,
        var name: String,
        val songFilenames: MutableList<String>,
        var updatedAt: Long = System.currentTimeMillis(),
    )

    private val KEY = stringPreferencesKey("playlists_v1_json")

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            try {
                val raw = appContext.dataStoreLocal.data.first()[KEY]
                if (!raw.isNullOrBlank()) {
                    _playlists.value = parse(raw)
                }
            } catch (t: Throwable) {
                android.util.Log.w("PlaylistsEngine", "load failed: ${t.message}")
            }
        }
    }

    fun create(name: String): Playlist {
        val pl = Playlist(
            id = java.util.UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled" },
            songFilenames = mutableListOf(),
        )
        val next = _playlists.value + pl
        _playlists.value = next
        scope.launch { persist(next) }
        return pl
    }

    fun rename(id: String, newName: String) {
        val next = _playlists.value.map {
            if (it.id == id) it.copy(name = newName, updatedAt = System.currentTimeMillis())
            else it
        }
        _playlists.value = next
        scope.launch { persist(next) }
    }

    fun delete(id: String) {
        val next = _playlists.value.filterNot { it.id == id }
        _playlists.value = next
        scope.launch { persist(next) }
    }

    fun addSong(id: String, filename: String) {
        val next = _playlists.value.map {
            if (it.id == id && !it.songFilenames.contains(filename)) {
                it.copy(
                    songFilenames = (it.songFilenames + filename).toMutableList(),
                    updatedAt = System.currentTimeMillis(),
                )
            } else it
        }
        _playlists.value = next
        scope.launch { persist(next) }
    }

    fun removeSong(id: String, filename: String) {
        val next = _playlists.value.map {
            if (it.id == id && it.songFilenames.contains(filename)) {
                it.copy(
                    songFilenames = it.songFilenames.filterNot { fn -> fn == filename }.toMutableList(),
                    updatedAt = System.currentTimeMillis(),
                )
            } else it
        }
        _playlists.value = next
        scope.launch { persist(next) }
    }

    private suspend fun persist(list: List<Playlist>) {
        try {
            val arr = JSONArray()
            for (p in list) {
                val o = JSONObject()
                o.put("id", p.id)
                o.put("name", p.name)
                o.put("updatedAt", p.updatedAt)
                val songs = JSONArray()
                for (fn in p.songFilenames) songs.put(fn)
                o.put("songs", songs)
                arr.put(o)
            }
            val raw = JSONObject().apply { put("playlists", arr) }.toString()
            appContext.dataStoreLocal.edit { it[KEY] = raw }
        } catch (t: Throwable) {
            android.util.Log.w("PlaylistsEngine", "persist failed: ${t.message}")
        }
    }

    private fun parse(raw: String): List<Playlist> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("playlists") ?: return emptyList()
        val out = ArrayList<Playlist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val songs = o.optJSONArray("songs")
            val list = mutableListOf<String>()
            if (songs != null) {
                for (j in 0 until songs.length()) list += songs.optString(j, "")
            }
            out += Playlist(
                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                name = o.optString("name", "Untitled"),
                songFilenames = list.filter { it.isNotBlank() }.toMutableList(),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
        return out
    }
}
