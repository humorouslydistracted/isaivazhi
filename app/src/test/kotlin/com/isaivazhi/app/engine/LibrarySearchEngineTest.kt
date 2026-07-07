package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchEngineTest {

    private fun song(
        id: Int,
        title: String,
        artist: String = "",
        album: String = "",
        filename: String = title,
    ) = Song(
        id = id,
        filename = filename,
        title = title,
        artist = artist,
        album = album,
        filePath = "/music/$filename.mp3",
    )

    @Test
    fun matchScore_firstWordMatchRanksAboveSecondWord() {
        val first = matchScore("Kadhal Kadhal", "kadhal")!!
        val second = matchScore("Engaeyo Kadhal", "kadhal")!!
        assertTrue(first < second)
    }

    @Test
    fun searchLibrary_ordersSongsByWordPosition() {
        val results = searchLibrary(
            query = "kadhal",
            songs = listOf(
                song(1, "Engaeyo Kadhal"),
                song(2, "Kadhal Kadhal"),
            ),
        )
        assertEquals(2, results.songs.size)
        assertEquals("Kadhal Kadhal", results.songs[0].song.title)
        assertEquals("Engaeyo Kadhal", results.songs[1].song.title)
    }

    @Test
    fun searchLibrary_titleMatchGoesToSongs() {
        val results = searchLibrary(
            query = "kadhal",
            songs = listOf(song(1, "Engaeyo Kadhal", album = "Kadhal Hits")),
        )
        assertEquals(1, results.songs.size)
        assertEquals(1, results.albums.size)
        assertTrue(results.others.isEmpty())
    }

    @Test
    fun searchLibrary_albumOnlyMatchGoesToAlbums() {
        val results = searchLibrary(
            query = "hits",
            songs = listOf(song(1, "Track One", album = "Kadhal Hits")),
        )
        assertTrue(results.songs.isEmpty())
        assertEquals(1, results.albums.size)
        assertEquals("Kadhal Hits", results.albums[0].album.name)
    }

    @Test
    fun searchLibrary_artistOnlyMatchGoesToOthers() {
        val results = searchLibrary(
            query = "rahman",
            songs = listOf(song(1, "Track One", artist = "A.R. Rahman", album = "Hits")),
        )
        assertTrue(results.songs.isEmpty())
        assertTrue(results.albums.isEmpty())
        assertEquals(1, results.others.size)
        assertTrue(results.others[0] is OtherHit.ArtistSong)
    }

    @Test
    fun searchLibrary_noDuplicateAcrossSections() {
        val results = searchLibrary(
            query = "kadhal",
            songs = listOf(
                song(1, "Kadhal Song", artist = "Kadhal Artist", album = "Kadhal Album"),
            ),
        )
        assertEquals(1, results.songs.size)
        assertEquals(1, results.albums.size)
        assertTrue(results.others.isEmpty())
    }

    @Test
    fun searchLibrary_playlistMatchGoesToOthers() {
        val results = searchLibrary(
            query = "workout",
            songs = emptyList(),
            playlists = listOf(
                PlaylistsEngine.Playlist(
                    id = "p1",
                    name = "Workout Mix",
                    songFilenames = mutableListOf(),
                ),
            ),
        )
        assertEquals(1, results.others.size)
        assertTrue(results.others[0] is OtherHit.Playlist)
    }

    @Test
    fun searchLibrary_folderMatchGoesToOthers() {
        val results = searchLibrary(
            query = "whatsapp",
            songs = emptyList(),
            folders = listOf(
                FolderEntry(
                    path = "/storage/WhatsApp Audio",
                    displayName = "WhatsApp Audio",
                    songCount = 5,
                    isExcluded = false,
                    isManual = false,
                ),
            ),
        )
        assertEquals(1, results.others.size)
        assertTrue(results.others[0] is OtherHit.Folder)
    }

    @Test
    fun searchLibrary_blankQueryReturnsEmpty() {
        val results = searchLibrary(
            query = "  ",
            songs = listOf(song(1, "Anything")),
        )
        assertTrue(results.isEmpty)
    }

    @Test
    fun librarySearchIndex_matchesSearchLibraryOrderingAndSections() {
        val songs = listOf(
            song(1, "Engaeyo Kadhal", artist = "Harris Jayaraj", album = "Hits"),
            song(2, "Kadhal Kadhal", artist = "A.R. Rahman", album = "Kadhal Album"),
            song(3, "Track One", artist = "Kadhal Artist", album = "Other Album"),
        )
        val playlists = listOf(
            PlaylistsEngine.Playlist(
                id = "p1",
                name = "Kadhal Mix",
                songFilenames = mutableListOf(),
            ),
        )
        val folders = listOf(
            FolderEntry(
                path = "/storage/Kadhal Collection",
                displayName = "Kadhal Collection",
                songCount = 3,
                isExcluded = false,
                isManual = false,
            ),
        )

        val indexedResults = LibrarySearchIndex(songs, playlists, folders).search("kadhal")
        val directResults = searchLibrary("kadhal", songs, playlists, folders)

        assertEquals(
            directResults.songs.map { it.song.id },
            indexedResults.songs.map { it.song.id },
        )
        assertEquals(
            directResults.albums.map { it.album.name },
            indexedResults.albums.map { it.album.name },
        )
        assertEquals(
            directResults.others.map { it::class.simpleName },
            indexedResults.others.map { it::class.simpleName },
        )
    }

    @Test
    fun librarySearchIndex_enforcesSmallerCaps() {
        val songs = (1..25).map { idx ->
            song(
                id = idx,
                title = "Kadhal Track $idx",
                artist = "Kadhal Artist $idx",
                album = "Kadhal Album $idx",
                filename = "kadhal_$idx",
            )
        }
        val playlists = (1..12).map { idx ->
            PlaylistsEngine.Playlist(
                id = "playlist_$idx",
                name = "Kadhal Playlist $idx",
                songFilenames = mutableListOf(),
            )
        }
        val folders = (1..12).map { idx ->
            FolderEntry(
                path = "/storage/kadhal_$idx",
                displayName = "Kadhal Folder $idx",
                songCount = idx,
                isExcluded = false,
                isManual = false,
            )
        }

        val results = LibrarySearchIndex(songs, playlists, folders).search("kadhal")

        assertEquals(20, results.songs.size)
        assertEquals(8, results.albums.size)
        assertEquals(8, results.others.size)
    }
}
