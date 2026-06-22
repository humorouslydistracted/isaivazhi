package com.isaivazhi.app.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationInputRevisionTrackerTest {

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
    fun cachedTailRejectedAfterTuningChange() {
        val tracker = RecommendationInputRevisionTracker()
        val cached = CachedRecommendationTail(
            seedFilename = "seed",
            revision = tracker.currentRevision(),
            tail = listOf(song("tail")),
        )

        tracker.invalidate()

        assertFalse(cached.isUsableFor(currentSeedFilename = "seed", currentRevision = tracker.currentRevision()))
    }

    @Test
    fun cachedTailRejectedAfterQueueRemove() {
        val tracker = RecommendationInputRevisionTracker()
        val cached = CachedRecommendationTail(
            seedFilename = "seed",
            revision = tracker.currentRevision(),
            tail = listOf(song("tail")),
        )

        tracker.invalidate()

        assertFalse(cached.isUsableFor(currentSeedFilename = "seed", currentRevision = tracker.currentRevision()))
    }

    @Test
    fun cachedTailRejectedAfterTasteReset() {
        val tracker = RecommendationInputRevisionTracker()
        val cached = CachedRecommendationTail(
            seedFilename = "seed",
            revision = tracker.currentRevision(),
            tail = listOf(song("tail")),
        )

        tracker.invalidate()

        assertFalse(cached.isUsableFor(currentSeedFilename = "seed", currentRevision = tracker.currentRevision()))
    }

    @Test
    fun cachedTailRejectedAfterCooldownRelease() {
        val tracker = RecommendationInputRevisionTracker()
        val cached = CachedRecommendationTail(
            seedFilename = "seed",
            revision = tracker.currentRevision(),
            tail = listOf(song("tail")),
        )

        tracker.invalidate()

        assertFalse(cached.isUsableFor(currentSeedFilename = "seed", currentRevision = tracker.currentRevision()))
    }

    @Test
    fun cachedTailAcceptedWhenSeedAndRevisionStillMatch() {
        val tracker = RecommendationInputRevisionTracker()
        val cached = CachedRecommendationTail(
            seedFilename = "seed",
            revision = tracker.currentRevision(),
            tail = listOf(song("tail")),
        )

        assertTrue(cached.isUsableFor(currentSeedFilename = "seed", currentRevision = tracker.currentRevision()))
    }
}
