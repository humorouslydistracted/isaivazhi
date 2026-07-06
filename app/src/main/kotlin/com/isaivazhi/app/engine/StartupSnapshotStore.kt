package com.isaivazhi.app.engine

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

data class StartupUiSnapshot(
    val savedAt: Long = 0L,
    val mediaStoreVersion: String = "",
    val lastTab: String = "",
    val songs: List<Song> = emptyList(),
    val currentMediaId: String? = null,
    val currentPositionMs: Long = 0L,
    val queueFilenames: List<String> = emptyList(),
    val queueIndex: Int = 0,
    val currentTitle: String = "",
    val currentArtist: String = "",
    val currentAlbum: String = "",
    val historyStats: Map<String, HistoryEngine.Stats> = emptyMap(),
    val historyEvents: List<HistoryEngine.Event> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val disliked: Set<String> = emptySet(),
    val playlists: List<StartupPlaylist> = emptyList(),
    val cooldownEntries: List<RecentlySurfacedTracker.ActiveEntry> = emptyList(),
    val browseCategoryFilenames: Map<String, List<String>> = emptyMap(),
    val embeddingRowCount: Int = 0,
    val embeddingDim: Int = 0,
    val vecExtensionLoaded: Boolean = false,
)

data class StartupPlaylist(
    val id: String,
    val name: String,
    val songFilenames: List<String>,
    val updatedAt: Long,
)

/**
 * Disposable, display-only cold-start checkpoint. DataStore, MediaStore,
 * Media3 and the embedding database remain authoritative.
 */
class StartupSnapshotStore(context: Context) {
    private val atomicFile = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    fun load(): StartupUiSnapshot? {
        if (!atomicFile.baseFile.exists()) return null
        return runCatching { StartupSnapshotCodec.decode(atomicFile.readFully()) }
            .onFailure {
                android.util.Log.w(TAG, "snapshot ignored: ${it.message}")
            }
            .getOrNull()
    }

    fun save(snapshot: StartupUiSnapshot) {
        val bytes = StartupSnapshotCodec.encode(snapshot)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(bytes)
            output.flush()
            atomicFile.finishWrite(output)
        } catch (t: Throwable) {
            output?.let { atomicFile.failWrite(it) }
            throw t
        }
    }

    fun clear() {
        atomicFile.delete()
    }

    companion object {
        private const val TAG = "StartupSnapshotStore"
        private const val FILE_NAME = "startup_ui_v1.bin"
    }
}

internal object StartupSnapshotCodec {
    private const val MAGIC = 0x49565A53 // IVZS
    private const val VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024
    private const val MAX_COLLECTION_SIZE = 100_000
    private const val MAX_STRING_BYTES = 1024 * 1024

    fun encode(snapshot: StartupUiSnapshot): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeLong(snapshot.savedAt)
                out.writeString(snapshot.mediaStoreVersion)
                out.writeSongs(snapshot.songs)
                out.writeNullableString(snapshot.currentMediaId)
                out.writeLong(snapshot.currentPositionMs)
                out.writeStrings(snapshot.queueFilenames)
                out.writeInt(snapshot.queueIndex)
                out.writeString(snapshot.currentTitle)
                out.writeString(snapshot.currentArtist)
                out.writeString(snapshot.currentAlbum)

                out.writeInt(snapshot.historyStats.size)
                snapshot.historyStats.forEach { (filename, stats) ->
                    out.writeString(filename)
                    out.writeInt(stats.plays)
                    out.writeLong(stats.lastPlayedAt)
                    out.writeFloat(stats.avgFraction)
                }

                out.writeInt(snapshot.historyEvents.size)
                snapshot.historyEvents.forEach { event ->
                    out.writeString(event.filename)
                    out.writeLong(event.startedAt)
                    out.writeFloat(event.fractionPlayed)
                    out.writeString(event.eventId)
                }

                out.writeStrings(snapshot.favorites.toList())
                out.writeStrings(snapshot.disliked.toList())

                out.writeInt(snapshot.playlists.size)
                snapshot.playlists.forEach { playlist ->
                    out.writeString(playlist.id)
                    out.writeString(playlist.name)
                    out.writeStrings(playlist.songFilenames)
                    out.writeLong(playlist.updatedAt)
                }

                out.writeInt(snapshot.cooldownEntries.size)
                snapshot.cooldownEntries.forEach { entry ->
                    out.writeString(entry.filename)
                    out.writeLong(entry.recordedAtListeningMs)
                    out.writeLong(entry.remainingMs)
                }

                out.writeInt(snapshot.browseCategoryFilenames.size)
                snapshot.browseCategoryFilenames.forEach { (category, filenames) ->
                    out.writeString(category)
                    out.writeStrings(filenames)
                }

                out.writeInt(snapshot.embeddingRowCount)
                out.writeInt(snapshot.embeddingDim)
                out.writeBoolean(snapshot.vecExtensionLoaded)
                // Trailing and optional so older v1 snapshots remain readable.
                out.writeString(snapshot.lastTab)
            }
            bytes.toByteArray()
        }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "snapshot payload too large" }
        val crc = CRC32().apply { update(payload) }.value
        return ByteArrayOutputStream(payload.size + 24).use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(payload.size)
                out.writeLong(crc)
                out.write(payload)
            }
            bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): StartupUiSnapshot {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "bad snapshot magic" }
            require(input.readInt() == VERSION) { "unsupported snapshot version" }
            val payloadLength = input.readInt()
            require(payloadLength in 0..MAX_PAYLOAD_BYTES) { "invalid snapshot length" }
            val expectedCrc = input.readLong()
            require(input.available() == payloadLength) { "truncated snapshot" }
            val payload = ByteArray(payloadLength)
            input.readFully(payload)
            val actualCrc = CRC32().apply { update(payload) }.value
            require(actualCrc == expectedCrc) { "snapshot checksum mismatch" }
            return decodePayload(payload)
        }
    }

    private fun decodePayload(payload: ByteArray): StartupUiSnapshot {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val savedAt = input.readLong()
            val mediaStoreVersion = input.readString()
            val songs = input.readSongs()
            val currentMediaId = input.readNullableString()
            val currentPositionMs = input.readLong()
            val queueFilenames = input.readStrings()
            val queueIndex = input.readInt()
            val currentTitle = input.readString()
            val currentArtist = input.readString()
            val currentAlbum = input.readString()

            val historyStats = LinkedHashMap<String, HistoryEngine.Stats>()
            repeat(input.readCount()) {
                historyStats[input.readString()] = HistoryEngine.Stats(
                    plays = input.readInt(),
                    lastPlayedAt = input.readLong(),
                    avgFraction = input.readFloat(),
                )
            }

            val historyEvents = ArrayList<HistoryEngine.Event>()
            repeat(input.readCount()) {
                historyEvents += HistoryEngine.Event(
                    filename = input.readString(),
                    startedAt = input.readLong(),
                    fractionPlayed = input.readFloat(),
                    eventId = input.readString(),
                )
            }

            val favorites = input.readStrings().toSet()
            val disliked = input.readStrings().toSet()

            val playlists = ArrayList<StartupPlaylist>()
            repeat(input.readCount()) {
                playlists += StartupPlaylist(
                    id = input.readString(),
                    name = input.readString(),
                    songFilenames = input.readStrings(),
                    updatedAt = input.readLong(),
                )
            }

            val cooldownEntries = ArrayList<RecentlySurfacedTracker.ActiveEntry>()
            repeat(input.readCount()) {
                cooldownEntries += RecentlySurfacedTracker.ActiveEntry(
                    filename = input.readString(),
                    recordedAtListeningMs = input.readLong(),
                    remainingMs = input.readLong(),
                )
            }

            val browse = LinkedHashMap<String, List<String>>()
            repeat(input.readCount()) {
                browse[input.readString()] = input.readStrings()
            }

            val embeddingRowCount = input.readInt()
            val embeddingDim = input.readInt()
            val vecExtensionLoaded = input.readBoolean()
            val lastTab = if (input.available() > 0) input.readString() else ""
            val snapshot = StartupUiSnapshot(
                savedAt = savedAt,
                mediaStoreVersion = mediaStoreVersion,
                lastTab = lastTab,
                songs = songs,
                currentMediaId = currentMediaId,
                currentPositionMs = currentPositionMs,
                queueFilenames = queueFilenames,
                queueIndex = queueIndex,
                currentTitle = currentTitle,
                currentArtist = currentArtist,
                currentAlbum = currentAlbum,
                historyStats = historyStats,
                historyEvents = historyEvents,
                favorites = favorites,
                disliked = disliked,
                playlists = playlists,
                cooldownEntries = cooldownEntries,
                browseCategoryFilenames = browse,
                embeddingRowCount = embeddingRowCount,
                embeddingDim = embeddingDim,
                vecExtensionLoaded = vecExtensionLoaded,
            )
            require(input.available() == 0) { "unexpected trailing snapshot data" }
            return snapshot
        }
    }

    private fun DataOutputStream.writeSongs(songs: List<Song>) {
        writeInt(songs.size)
        songs.forEach { song ->
            writeInt(song.id)
            writeString(song.filename)
            writeString(song.title)
            writeString(song.artist)
            writeString(song.album)
            writeNullableString(song.filePath)
            writeNullableString(song.artPath)
            writeLong(song.dateModified)
            writeLong(song.durationMs)
            writeNullableString(song.contentHash)
            writeBoolean(song.hasEmbedding)
            writeBoolean(song.embeddingIndex != null)
            song.embeddingIndex?.let(::writeInt)
            writeBoolean(song.disliked)
        }
    }

    private fun DataInputStream.readSongs(): List<Song> {
        val songs = ArrayList<Song>()
        repeat(readCount()) {
            songs += Song(
                id = readInt(),
                filename = readString(),
                title = readString(),
                artist = readString(),
                album = readString(),
                filePath = readNullableString(),
                artPath = readNullableString(),
                dateModified = readLong(),
                durationMs = readLong(),
                contentHash = readNullableString(),
                hasEmbedding = readBoolean(),
                embeddingIndex = if (readBoolean()) readInt() else null,
                disliked = readBoolean(),
            )
        }
        return songs
    }

    private fun DataOutputStream.writeStrings(values: List<String>) {
        writeInt(values.size)
        values.forEach { writeString(it) }
    }

    private fun DataInputStream.readStrings(): List<String> {
        val result = ArrayList<String>()
        repeat(readCount()) { result += readString() }
        return result
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "snapshot string too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES && length <= available()) {
            "invalid snapshot string length"
        }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataInputStream.readCount(): Int {
        val count = readInt()
        require(count in 0..MAX_COLLECTION_SIZE) { "invalid snapshot collection size" }
        return count
    }
}
