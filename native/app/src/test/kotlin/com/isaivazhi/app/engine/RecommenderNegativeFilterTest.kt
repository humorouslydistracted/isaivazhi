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
}
