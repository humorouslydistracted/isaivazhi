package com.isaivazhi.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Opportunistic album-art extraction after the library scan completes.
 *
 * Runs on Dispatchers.IO behind a small semaphore (4 concurrent) so it
 * doesn't saturate disk + MediaMetadataRetriever and starve UI scroll. Each
 * song's art lands in the AlbumArtHelper on-disk cache; subsequent UI loads
 * of those rows are ~5 ms disk reads instead of ~50–100 ms extractions.
 *
 * The prefetch is "best effort" — bails after `limit` songs to avoid
 * spending several minutes extracting art for songs the user may never
 * scroll to. Default 200 covers most "first session" browsing.
 */
object ArtPrefetch {
    suspend fun prefetch(ctx: Context, songs: List<Song>, limit: Int = 200) {
        val targets = songs.filter { it.filePath != null }.take(limit)
        if (targets.isEmpty()) return
        coroutineScope {
            val semaphore = Semaphore(4)
            for (song in targets) {
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            // Loading via the repository populates both the
                            // on-disk JPG cache and the in-memory LRU.
                            AlbumArtRepository.load(ctx, song.filePath!!, sampleSize = 4)
                            // Tiny yield so we don't dominate the dispatcher.
                            delay(2L)
                        } catch (_: Throwable) {
                            // best effort
                        }
                    }
                }
            }
        }
    }
}
