package com.isaivazhi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovedPathHealerTest {

    private fun plan(
        index: Map<String, List<String>>,
        embedding: Map<String, String>,
        library: Set<String>,
        onDisk: Set<String>,
    ) = MovedPathHealer.plan(index, embedding, library) { it in onDisk }

    @Test
    fun movedSong_oldPathIsDroppedAndEmbeddingRepointed() {
        val moves = plan(
            index = mapOf("h1" to listOf("/Music/A/song.mp3", "/Music/B/song.mp3")),
            embedding = mapOf("h1" to "/Music/A/song.mp3"),
            library = setOf("/Music/B/song.mp3"),
            onDisk = setOf("/Music/B/song.mp3"),
        )
        assertEquals(1, moves.size)
        assertEquals("/Music/A/song.mp3", moves[0].oldPath)
        assertEquals("/Music/B/song.mp3", moves[0].newPath)
        assertTrue(moves[0].embeddingRepointed)
    }

    @Test
    fun genuineDuplicates_bothOnDisk_areLeftAlone() {
        val moves = plan(
            index = mapOf("h1" to listOf("/Music/A/song.mp3", "/Music/B/song (1).mp3")),
            embedding = mapOf("h1" to "/Music/A/song.mp3"),
            library = setOf("/Music/A/song.mp3", "/Music/B/song (1).mp3"),
            onDisk = setOf("/Music/A/song.mp3", "/Music/B/song (1).mp3"),
        )
        assertTrue(moves.isEmpty())
    }

    @Test
    fun twoCopiesThenOneMoved_onlyTheMissingPathGoes() {
        val moves = plan(
            index = mapOf("h1" to listOf("/A/song.mp3", "/B/song.mp3", "/C/song.mp3")),
            embedding = mapOf("h1" to "/B/song.mp3"),
            library = setOf("/B/song.mp3", "/C/song.mp3"),
            onDisk = setOf("/B/song.mp3", "/C/song.mp3"),
        )
        assertEquals(listOf("/A/song.mp3"), moves.map { it.oldPath })
        assertFalse(moves[0].embeddingRepointed)
    }

    @Test
    fun copyInExcludedFolder_stillOnDisk_isLeftAlone() {
        val moves = plan(
            index = mapOf("h1" to listOf("/Excluded/song.mp3", "/Music/song.mp3")),
            embedding = mapOf("h1" to "/Excluded/song.mp3"),
            library = setOf("/Music/song.mp3"),
            onDisk = setOf("/Excluded/song.mp3", "/Music/song.mp3"),
        )
        assertTrue(moves.isEmpty())
    }

    @Test
    fun deletedSongWithNoOtherCopy_isLeftForTheStaleList() {
        val moves = plan(
            index = mapOf("h1" to listOf("/A/gone.mp3")),
            embedding = mapOf("h1" to "/A/gone.mp3"),
            library = emptySet(),
            onDisk = emptySet(),
        )
        assertTrue(moves.isEmpty())
    }

    @Test
    fun allCopiesMissing_nothingChanges() {
        val moves = plan(
            index = mapOf("h1" to listOf("/A/song.mp3", "/B/song.mp3")),
            embedding = mapOf("h1" to "/A/song.mp3"),
            library = emptySet(),
            onDisk = emptySet(),
        )
        assertTrue(moves.isEmpty())
    }

    @Test
    fun movedAndCopied_prefersTheCopyWithTheSameName() {
        val moves = plan(
            index = mapOf("h1" to listOf("/Old/song.mp3", "/New/a-copy.mp3", "/New2/song.mp3")),
            embedding = mapOf("h1" to "/Old/song.mp3"),
            library = setOf("/New/a-copy.mp3", "/New2/song.mp3"),
            onDisk = setOf("/New/a-copy.mp3", "/New2/song.mp3"),
        )
        assertEquals("/New2/song.mp3", moves.single().newPath)
    }

    @Test
    fun embeddingRowOnlyPath_isConsideredToo() {
        val moves = plan(
            index = mapOf("h1" to listOf("/New/song.mp3")),
            embedding = mapOf("h1" to "/Old/song.mp3"),
            library = setOf("/New/song.mp3"),
            onDisk = setOf("/New/song.mp3"),
        )
        assertEquals("/Old/song.mp3", moves.single().oldPath)
        assertTrue(moves.single().embeddingRepointed)
    }
}
