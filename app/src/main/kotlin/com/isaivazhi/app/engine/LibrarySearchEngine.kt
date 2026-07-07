package com.isaivazhi.app.engine

private const val SONG_CAP = 20
private const val ALBUM_CAP = 8
private const val OTHER_CAP = 8

/** Lower score = higher rank. Null = no match. */
fun matchScore(text: String, query: String): Int? {
    val q = query.trim()
    if (q.isBlank()) return null
    return matchScoreLower(text.lowercase(), q.lowercase())
}

private fun matchScoreLower(textLower: String, queryLower: String): Int? {
    if (queryLower.isBlank()) return null
    val idx = textLower.indexOf(queryLower)
    if (idx < 0) return null

    var wordIndex = 0
    var inWord = false
    for (i in 0 until idx) {
        val isWhitespace = textLower[i].isWhitespace()
        if (isWhitespace) {
            inWord = false
        } else if (!inWord) {
            wordIndex += 1
            inWord = true
        }
    }
    return wordIndex * 10_000 + idx
}

private fun containsQueryLower(textLower: String, queryLower: String): Boolean =
    matchScoreLower(textLower, queryLower) != null

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

fun emptySearchResults(): SearchResults = SearchResults(
    songs = emptyList(),
    albums = emptyList(),
    others = emptyList(),
)

class LibrarySearchIndex(
    songs: List<Song>,
    playlists: List<PlaylistsEngine.Playlist> = emptyList(),
    folders: List<FolderEntry> = emptyList(),
) {
    private val indexedSongs: List<IndexedSong>
    private val indexedAlbums: List<IndexedAlbum>
    private val indexedPlaylists: List<IndexedPlaylist>
    private val indexedFolders: List<IndexedFolder>

    init {
        val playableSongs = songs.filter { it.filePath != null }
        indexedSongs = playableSongs.map { song ->
            val displayTitle = songDisplayTitle(song)
            IndexedSong(
                song = song,
                displayTitle = displayTitle,
                displayTitleLower = displayTitle.lowercase(),
                titleLower = song.title.lowercase(),
                filenameLower = song.filename.lowercase(),
                artistLower = song.artist.lowercase(),
                albumLower = song.album.lowercase(),
            )
        }
        indexedAlbums = groupIntoAlbums(playableSongs).map { album ->
            IndexedAlbum(
                album = album,
                nameLower = album.name.lowercase(),
            )
        }
        indexedPlaylists = playlists.map { playlist ->
            IndexedPlaylist(
                playlist = playlist,
                nameLower = playlist.name.lowercase(),
            )
        }
        indexedFolders = folders.map { folder ->
            IndexedFolder(
                folder = folder,
                displayNameLower = folder.displayName.lowercase(),
                pathLower = folder.path.lowercase(),
            )
        }
    }

    fun search(query: String): SearchResults {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return emptySearchResults()
        val queryLower = trimmedQuery.lowercase()

        val songHits = indexedSongs.mapNotNull { indexedSong ->
            val titleScore = matchScoreLower(indexedSong.displayTitleLower, queryLower)
            val filenameScore = if (indexedSong.song.title.isNotBlank()) {
                matchScoreLower(indexedSong.filenameLower, queryLower)
            } else {
                null
            }
            val score = minOfNotNull(titleScore, filenameScore) ?: return@mapNotNull null
            SongHit(indexedSong.song, score)
        }.sortedWith(
            compareBy<SongHit> { it.score }
                .thenBy { songDisplayTitle(it.song).length }
                .thenBy { songDisplayTitle(it.song).lowercase() },
        ).take(SONG_CAP)

        val songsInResults = songHits.asSequence().map { it.song.id }.toHashSet()

        val albumHits = indexedAlbums.mapNotNull { indexedAlbum ->
            val score = matchScoreLower(indexedAlbum.nameLower, queryLower) ?: return@mapNotNull null
            AlbumHit(indexedAlbum.album, score)
        }.sortedWith(
            compareBy<AlbumHit> { it.score }
                .thenBy { it.album.name.length }
                .thenBy { it.album.name.lowercase() },
        ).take(ALBUM_CAP)

        val others = mutableListOf<OtherHit>()

        for (indexedSong in indexedSongs) {
            if (indexedSong.song.id in songsInResults) continue
            if (containsQueryLower(indexedSong.albumLower, queryLower)) continue
            val score = matchScoreLower(indexedSong.artistLower, queryLower) ?: continue
            others += OtherHit.ArtistSong(indexedSong.song, score)
        }

        for (indexedPlaylist in indexedPlaylists) {
            val score = matchScoreLower(indexedPlaylist.nameLower, queryLower) ?: continue
            others += OtherHit.Playlist(indexedPlaylist.playlist, score)
        }

        for (indexedFolder in indexedFolders) {
            val score = minOfNotNull(
                matchScoreLower(indexedFolder.displayNameLower, queryLower),
                matchScoreLower(indexedFolder.pathLower, queryLower),
            ) ?: continue
            others += OtherHit.Folder(indexedFolder.folder, score)
        }

        val sortedOthers = others
            .sortedWith(
                compareBy<OtherHit> { it.score }
                    .thenBy { otherSortKey(it) },
            )
            .take(OTHER_CAP)

        return SearchResults(songHits, albumHits, sortedOthers)
    }
}

fun searchLibrary(
    query: String,
    songs: List<Song>,
    playlists: List<PlaylistsEngine.Playlist> = emptyList(),
    folders: List<FolderEntry> = emptyList(),
): SearchResults {
    return LibrarySearchIndex(
        songs = songs,
        playlists = playlists,
        folders = folders,
    ).search(query)
}

private fun otherSortKey(hit: OtherHit): String = when (hit) {
    is OtherHit.ArtistSong -> hit.song.artist.lowercase()
    is OtherHit.Playlist -> hit.playlist.name.lowercase()
    is OtherHit.Folder -> hit.folder.displayName.lowercase()
}

private data class IndexedSong(
    val song: Song,
    val displayTitle: String,
    val displayTitleLower: String,
    val titleLower: String,
    val filenameLower: String,
    val artistLower: String,
    val albumLower: String,
)

private data class IndexedAlbum(
    val album: AlbumGroup,
    val nameLower: String,
)

private data class IndexedPlaylist(
    val playlist: PlaylistsEngine.Playlist,
    val nameLower: String,
)

private data class IndexedFolder(
    val folder: FolderEntry,
    val displayNameLower: String,
    val pathLower: String,
)

private fun minOfNotNull(first: Int?, second: Int?): Int? = when {
    first == null -> second
    second == null -> first
    else -> minOf(first, second)
}
