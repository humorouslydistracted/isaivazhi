package com.isaivazhi.app.engine

private const val SONG_CAP = 40
private const val ALBUM_CAP = 15
private const val OTHER_CAP = 15

/** Lower score = higher rank. Null = no match. */
fun matchScore(text: String, query: String): Int? {
    val q = query.trim()
    if (q.isBlank()) return null
    val idx = text.indexOf(q, ignoreCase = true)
    if (idx < 0) return null
    val wordIndex = text.substring(0, idx)
        .split(Regex("\\s+"))
        .count { it.isNotEmpty() }
    return wordIndex * 10_000 + idx
}

private fun containsQuery(text: String, query: String): Boolean =
    matchScore(text, query) != null

private fun songDisplayTitle(song: Song): String =
    song.title.ifBlank { song.filename }

data class SongHit(val song: Song, val score: Int)

data class AlbumHit(val album: AlbumGroup, val score: Int)

sealed class OtherHit {
    abstract val score: Int

    data class ArtistSong(val song: Song, override val score: Int) : OtherHit()
    data class Playlist(val playlist: PlaylistsEngine.Playlist, override val score: Int) : OtherHit()
    data class Folder(val folder: FolderEntry, override val score: Int) : OtherHit()
}

data class SearchResults(
    val songs: List<SongHit>,
    val albums: List<AlbumHit>,
    val others: List<OtherHit>,
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && others.isEmpty()
}

fun searchLibrary(
    query: String,
    songs: List<Song>,
    playlists: List<PlaylistsEngine.Playlist> = emptyList(),
    folders: List<FolderEntry> = emptyList(),
): SearchResults {
    val q = query.trim()
    if (q.isBlank()) return SearchResults(emptyList(), emptyList(), emptyList())

    val playable = songs.filter { it.filePath != null }

    val songHits = playable.mapNotNull { song ->
        val titleScore = matchScore(songDisplayTitle(song), q)
        val filenameScore = if (song.title.isNotBlank()) {
            matchScore(song.filename, q)
        } else {
            null
        }
        val score = listOfNotNull(titleScore, filenameScore).minOrNull() ?: return@mapNotNull null
        SongHit(song, score)
    }.sortedWith(
        compareBy<SongHit> { it.score }
            .thenBy { songDisplayTitle(it.song).length }
            .thenBy { songDisplayTitle(it.song).lowercase() },
    ).take(SONG_CAP)

    val songsInResults = songHits.map { it.song.id }.toSet()

    val albumHits = groupIntoAlbums(playable)
        .mapNotNull { album ->
            val score = matchScore(album.name, q) ?: return@mapNotNull null
            AlbumHit(album, score)
        }
        .sortedWith(
            compareBy<AlbumHit> { it.score }
                .thenBy { it.album.name.length }
                .thenBy { it.album.name.lowercase() },
        )
        .take(ALBUM_CAP)

    val others = mutableListOf<OtherHit>()

    for (song in playable) {
        if (song.id in songsInResults) continue
        if (containsQuery(song.album, q)) continue
        val score = matchScore(song.artist, q) ?: continue
        others += OtherHit.ArtistSong(song, score)
    }

    for (playlist in playlists) {
        val score = matchScore(playlist.name, q) ?: continue
        others += OtherHit.Playlist(playlist, score)
    }

    for (folder in folders) {
        val score = listOfNotNull(
            matchScore(folder.displayName, q),
            matchScore(folder.path, q),
        ).minOrNull() ?: continue
        others += OtherHit.Folder(folder, score)
    }

    val sortedOthers = others
        .sortedWith(
            compareBy<OtherHit> { it.score }
                .thenBy { otherSortKey(it) },
        )
        .take(OTHER_CAP)

    return SearchResults(songHits, albumHits, sortedOthers)
}

private fun otherSortKey(hit: OtherHit): String = when (hit) {
    is OtherHit.ArtistSong -> hit.song.artist.lowercase()
    is OtherHit.Playlist -> hit.playlist.name.lowercase()
    is OtherHit.Folder -> hit.folder.displayName.lowercase()
}
