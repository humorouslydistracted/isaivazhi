package com.isaivazhi.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderNegativeFilterTest {

    private val scores = mapOf(
        "mild" to -0.2f,
        "mid" to -0.4f,
        "strong" to -1.5f,
        "ok" to 0.5f,
    )

    @Test
    fun softExclude_atDefaultStrength_dropsBelowThreshold() {
        val excluded = RecommendationPolicy.softExcludedFilenames(scores, 0.5f)
        assertTrue("mid" in excluded)
        assertTrue("strong" in excluded)
        assertTrue("mild" !in excluded)
        assertTrue("ok" !in excluded)
    }

    @Test
    fun unionExcludes_includesDislikesAlways() {
        val hard = setOf("strong")
        val soft = setOf("mid")
        val disliked = setOf("disliked_track")
        val union = RecommendationPolicy.unionExcludes(hard, soft, disliked)
        assertEquals(setOf("strong", "mid", "disliked_track"), union)
    }

    @Test
    fun filterSongsForUpNext_removesPolicyExcludes() {
        val songs = listOf(
            Song(1, "ok", "Ok", "", "", null, contentHash = "h1"),
            Song(2, "bad", "Bad", "", "", null, contentHash = "h2"),
        )
        val excludes = setOf("bad")
        val filtered = RecommendationPolicy.filterSongsForUpNext(songs, excludes)
        assertEquals(listOf("ok"), filtered.map { it.filename })
    }

    @Test
    fun filterSongsForRecommendationCooldown_removesCooldownSongs() {
        val songs = listOf(
            Song(1, "fresh", "Fresh", "", "", null, contentHash = "h1"),
            Song(2, "cooldown", "Cooldown", "", "", null, contentHash = "h2"),
        )
        val filtered = RecommendationPolicy.filterSongsForRecommendationCooldown(
            songs = songs,
            cooldownFilenames = setOf("cooldown"),
        )
        assertEquals(listOf("fresh"), filtered.map { it.filename })
    }

    @Test
    fun filterSongsForRecommendationCooldown_keepsAllowedSongs() {
        val songs = listOf(
            Song(1, "current", "Current", "", "", null, contentHash = "h1"),
            Song(2, "play_next", "Play Next", "", "", null, contentHash = "h2"),
            Song(3, "cooldown", "Cooldown", "", "", null, contentHash = "h3"),
        )
        val filtered = RecommendationPolicy.filterSongsForRecommendationCooldown(
            songs = songs,
            cooldownFilenames = setOf("current", "play_next", "cooldown"),
            allowedFilenames = setOf("current", "play_next"),
        )
        assertEquals(listOf("current", "play_next"), filtered.map { it.filename })
    }
}
