package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupSnapshotCodecTest {
    private val snapshot = StartupUiSnapshot(
        savedAt = 1234L,
        mediaStoreVersion = "media-v2",
        lastTab = "Browse",
        songs = listOf(
            Song(
                id = 7,
                filename = "one.flac",
                title = "One",
                artist = "Artist",
                album = "Album",
                filePath = "/music/one.flac",
                artPath = "/art/one.jpg",
                dateModified = 99L,
                durationMs = 180_000L,
                contentHash = "hash-1",
                hasEmbedding = true,
                embeddingIndex = 4,
            ),
        ),
        currentMediaId = "one.flac",
        currentPositionMs = 42_000L,
        queueFilenames = listOf("one.flac", "two.opus"),
        queueIndex = 0,
        currentTitle = "One",
        currentArtist = "Artist",
        currentAlbum = "Album",
        historyStats = mapOf("one.flac" to HistoryEngine.Stats(3, 900L, 0.75f)),
        historyEvents = listOf(HistoryEngine.Event("one.flac", 900L, 0.75f, "event-1")),
        favorites = setOf("one.flac"),
        disliked = setOf("two.opus"),
        playlists = listOf(StartupPlaylist("p1", "Mix", listOf("one.flac"), 800L)),
        cooldownEntries = listOf(RecentlySurfacedTracker.ActiveEntry("two.opus", 100L, 200L)),
        browseCategoryFilenames = mapOf("RecentlyPlayed" to listOf("one.flac")),
        embeddingRowCount = 2450,
        embeddingDim = 512,
        vecExtensionLoaded = true,
    )

    @Test
    fun roundTripPreservesSnapshot() {
        assertEquals(snapshot, StartupSnapshotCodec.decode(StartupSnapshotCodec.encode(snapshot)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCorruptPayload() {
        val bytes = StartupSnapshotCodec.encode(snapshot)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        StartupSnapshotCodec.decode(bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedPayload() {
        val bytes = StartupSnapshotCodec.encode(snapshot)
        StartupSnapshotCodec.decode(bytes.copyOf(bytes.size - 3))
    }

    @Test
    fun preservesNullCurrentSong() {
        val decoded = StartupSnapshotCodec.decode(
            StartupSnapshotCodec.encode(StartupUiSnapshot()),
        )
        assertNull(decoded.currentMediaId)
    }
}
