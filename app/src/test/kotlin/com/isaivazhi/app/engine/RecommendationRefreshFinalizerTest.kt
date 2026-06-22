package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationRefreshFinalizerTest {

    private fun song(filename: String): Song =
        Song(
            id = filename.hashCode(),
            filename = filename,
            title = filename,
            artist = "",
            album = "",
            filePath = "/music/$filename.mp3",
            contentHash = "hash_$filename",
        )

    @Test
    fun finalizeHardRefreshRemovesExcludedSongsAndPreservesPlayNext() {
        val playNext = listOf(song("play_next"))
        val result = RecommendationRefreshFinalizer.finalizeHardRefresh(
            candidateTail = listOf(
                song("hard_blocked"),
                song("soft_excluded"),
                song("disliked"),
                song("cooldown"),
                song("play_next"),
                song("current"),
                song("ok"),
                song("ok"),
            ),
            playNextSongs = playNext,
            playNextFilenames = setOf("play_next"),
            currentFilename = "current",
            policyExcludes = setOf("hard_blocked", "soft_excluded", "disliked"),
            cooldownFilenames = setOf("cooldown"),
        )

        assertEquals(listOf("ok"), result.finalTail.map { it.filename })
        assertEquals(listOf("play_next", "ok"), result.finalUpcoming.map { it.filename })
    }

    @Test
    fun finalizeSoftRefreshKeepsFrozenDropsBlockedStableAndDedupesFluid() {
        val result = RecommendationRefreshFinalizer.finalizeSoftRefresh(
            frozenZone = listOf(song("frozen_1"), song("frozen_2")),
            stableCandidates = listOf(song("blocked_stable"), song("stable_ok")),
            fluidCandidates = listOf(
                song("stable_ok"),
                song("cooldown"),
                song("fresh"),
                song("frozen_1"),
                song("fresh"),
            ),
            currentFilename = "current",
            policyExcludes = setOf("blocked_stable"),
            cooldownFilenames = setOf("cooldown"),
        )

        assertEquals(listOf("frozen_1", "frozen_2"), result.frozenZone.map { it.filename })
        assertEquals(listOf("stable_ok"), result.stableZone.map { it.filename })
        assertEquals(listOf("fresh"), result.fluidZone.map { it.filename })
        assertEquals(listOf("frozen_1", "frozen_2", "stable_ok", "fresh"), result.finalTail.map { it.filename })
    }
}
