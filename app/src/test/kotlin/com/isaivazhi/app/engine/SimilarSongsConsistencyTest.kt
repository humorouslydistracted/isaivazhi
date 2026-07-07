package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarSongsConsistencyTest {

    @Test
    fun cosineOrdering_matchesAcrossSqliteHeapAndNormalizedFallbackPaths() {
        val query = floatArrayOf(10f, 0f)
        val candidates = linkedMapOf(
            "same_direction" to floatArrayOf(9f, 0f),
            "bigger_but_off_axis" to floatArrayOf(100f, 100f),
            "orthogonal" to floatArrayOf(0f, 10f),
        )

        val sqliteOrder = EmbeddingSimilarity.rankAsSqliteVec(query, candidates, emptySet(), 3)
            .map { it.key }
        val heapOrder = EmbeddingSimilarity.rankAsHeapPath(query, candidates, emptySet(), 3)
            .map { it.key }
        val fallbackOrder = EmbeddingSimilarity.rankAsNormalizedFallback(query, candidates, emptySet(), 3)
            .map { it.key }

        assertEquals(listOf("same_direction", "bigger_but_off_axis", "orthogonal"), sqliteOrder)
        assertEquals(sqliteOrder, heapOrder)
        assertEquals(sqliteOrder, fallbackOrder)
    }

    @Test
    fun cosineOrdering_ignoresMagnitudeWhenDirectionIsWorse() {
        val query = floatArrayOf(3f, 4f)
        val betterDirection = EmbeddingSimilarity.cosineSimilarity(query, floatArrayOf(6f, 8f))
        val worseDirection = EmbeddingSimilarity.cosineSimilarity(query, floatArrayOf(300f, 0f))

        assertTrue(betterDirection > worseDirection)
    }

    @Test
    fun resolveSimilarCandidates_filtersPolicyExcludesWithoutReorderingRemainingSongs() {
        val current = Song(1, "current", "Current", "", "", "/music/current.mp3", contentHash = "h0")
        val keepA = Song(2, "keep_a", "Keep A", "", "", "/music/a.mp3", contentHash = "h1")
        val filtered = Song(3, "filtered", "Filtered", "", "", "/music/b.mp3", contentHash = "h2")
        val keepB = Song(4, "keep_b", "Keep B", "", "", "/music/c.mp3", contentHash = "h3")
        val library = listOf(current, keepA, filtered, keepB)
        val candidates = listOf(
            EmbeddingDbFacade.NnResult("h1", "/music/a.mp3", "keep_a", 0.92f),
            EmbeddingDbFacade.NnResult("h2", "/music/b.mp3", "filtered", 0.90f),
            EmbeddingDbFacade.NnResult("h3", "/music/c.mp3", "keep_b", 0.87f),
        )

        val resolved = Recommender.resolveSimilarCandidates(
            candidates = candidates,
            library = library,
            policyExcludes = setOf("filtered"),
            k = 3,
        )

        assertEquals(listOf("keep_a", "keep_b"), resolved.map { it.song.filename })
        assertEquals(listOf(0.92f, 0.87f), resolved.map { it.similarity })
    }

    @Test
    fun resolveSimilarCandidates_fallsBackToContentHashForDuplicateFilenameCases() {
        val current = Song(1, "current", "Current", "", "", "/music/current.mp3", contentHash = "h0")
        val staleDuplicate = Song(2, "dup.mp3", "Stale", "", "", null, contentHash = "old_hash")
        val playableDuplicate = Song(3, "playable_alias.mp3", "Playable", "", "", "/music/real.mp3", contentHash = "target_hash")
        val library = listOf(current, staleDuplicate, playableDuplicate)
        val candidates = listOf(
            EmbeddingDbFacade.NnResult("target_hash", "/music/real.mp3", "dup.mp3", 0.88f),
        )

        val resolved = Recommender.resolveSimilarCandidates(
            candidates = candidates,
            library = library,
            policyExcludes = emptySet(),
            k = 5,
        )

        assertEquals(listOf("playable_alias.mp3"), resolved.map { it.song.filename })
        assertEquals(listOf("Playable"), resolved.map { it.song.title })
    }
}
