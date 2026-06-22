package com.isaivazhi.app.engine

data class AlbumGroup(val name: String, val artist: String, val tracks: List<Song>) {
    val firstArtPath: String? get() = tracks.firstOrNull { it.filePath != null }?.filePath
}

fun groupIntoAlbums(songs: List<Song>): List<AlbumGroup> {
    val byAlbum = LinkedHashMap<String, MutableList<Song>>()
    for (s in songs) {
        if (s.filePath == null) continue
        val key = s.album.ifBlank { "Unknown album" }
        byAlbum.getOrPut(key) { mutableListOf() }.add(s)
    }
    return byAlbum.entries
        .sortedBy { it.key.lowercase() }
        .map { (name, tracks) ->
            val artist = tracks.groupingBy { it.artist.ifBlank { "Unknown artist" } }
                .eachCount().maxByOrNull { it.value }?.key ?: "Unknown artist"
            AlbumGroup(name, artist, tracks)
        }
}
