package com.isaivazhi.app.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Single source of engines for the app. Constructed once at process start
 * (by the Application class or MainActivity if no custom Application).
 * Manual DI for now — Hilt can be introduced later if injection complexity
 * grows.
 */
class AppContainer(private val appContext: Context) {

    val preferences: AppPreferences by lazy { AppPreferences(appContext) }

    // Push #41: app-wide snackbar/toast channel. MainActivity's Scaffold
    // consumes `toaster.messages` via a LaunchedEffect; any engine or UI
    // handler can fire `toaster.show("...")` to surface feedback.
    val toaster: Toaster by lazy { Toaster() }

    val embeddingDb: EmbeddingDbFacade by lazy { EmbeddingDbFacade(appContext) }

    // Scope used to fan out async work from synchronous engine callbacks
    // (favorite toggle → similarity propagation, etc.). Kept small and
    // supervisor-rooted so a propagation failure can never crash the app.
    private val sideEffectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Favorites + Disliked are wired with onChangeHook callbacks that
    // synchronously update TasteEngine.directScore when the user toggles
    // a heart or thumbs-down. Capacitor parity: a favorited unplayed song
    // immediately gets a +2.0 favoritePrior boost; a disliked song gets
    // a −3.0 dislikePrior penalty. Without these hooks the manual prior
    // was inert and only the per-song filter set was updated (push #38).
    // Push #63: after the directScore recompute, also propagate the
    // resulting delta to the song's top-10 embedding neighbors (Capacitor
    // engine-favorites.js:77, engine-disliked.js:132).
    val favorites: FavoritesEngine by lazy {
        FavoritesEngine(appContext).also { fav ->
            fav.onChangeHook = { fn, _ ->
                val (before, after) = taste.applyManualPriorChange(
                    filename = fn,
                    isFavorite = fav.isFavorite(fn),
                    isDisliked = disliked.isDisliked(fn),
                )
                val delta = after.directScore - before.directScore
                sideEffectScope.launch {
                    taste.propagateSimilarityBoost(fn, delta, "favorite_toggle")
                }
            }
        }
    }

    val disliked: DislikedEngine by lazy {
        DislikedEngine(appContext).also { dis ->
            dis.onChangeHook = { fn, _ ->
                val (before, after) = taste.applyManualPriorChange(
                    filename = fn,
                    isFavorite = favorites.isFavorite(fn),
                    isDisliked = dis.isDisliked(fn),
                )
                val delta = after.directScore - before.directScore
                sideEffectScope.launch {
                    taste.propagateSimilarityBoost(fn, delta, "dislike_toggle")
                }
            }
        }
    }

    val playlists: PlaylistsEngine by lazy { PlaylistsEngine(appContext) }

    val history: HistoryEngine by lazy { HistoryEngine(appContext) }

    val taste: TasteEngine by lazy {
        TasteEngine(appContext).also { t ->
            // Push #63: inject the neighbor-lookup callback so the engine
            // can fan a score-delta to a song's top-10 embedding neighbors
            // without depending on EmbeddingDbFacade directly. Capacitor's
            // engine-taste.js calls _getNearestNeighborSongIds which is
            // equivalent to nearestNeighborsForFilename here.
            t.neighborLookup = { fn, k ->
                runCatching {
                    embeddingDb.nearestNeighborsForFilename(
                        queryFilename = fn,
                        k = k,
                        excludeHashes = emptyList(),
                    ).map { it.filename }.filter { it.isNotBlank() && it != fn }
                }.getOrDefault(emptyList())
            }
        }
    }

    val signalTimeline: SignalTimelineEngine by lazy { SignalTimelineEngine(appContext) }

    // Push #63: in-memory session counters (encounters/skips/positives).
    // Reset on app start; not persisted. TasteEngine reads `current()` to
    // build the session-level snapshots that the SignalTimeline records.
    val session: SessionEngine by lazy { SessionEngine() }

    val playback: PlaybackEngine by lazy {
        PlaybackEngine(appContext, preferences, history, taste, signalTimeline, session, toaster)
    }

    val recommender: Recommender by lazy { Recommender(embeddingDb) }

    val embedding: EmbeddingEngine by lazy { EmbeddingEngine(appContext, embeddingDb, toaster) }

    /**
     * Push #57: filepaths the user has chosen to skip embedding for. The
     * AI page's Pending list excludes these so a permanently-failing song
     * (e.g. an unusual FLAC variant Android's MediaExtractor can't open)
     * doesn't show up as Pending forever.
     */
    val skippedEmbeddings: SkippedEmbeddingsEngine by lazy { SkippedEmbeddingsEngine(appContext) }

    /**
     * Reset Engine — clears every per-song signal so taste re-learns from
     * scratch. The user invokes this from the Taste page with a confirm
     * dialog. The expected scope matches the Capacitor app's reset:
     * "Clears: Up Next, playback history, Taste profile, favorites, dislikes,
     *  X-score, session logs, saved playback state. Keeps: song files,
     *  embeddings, playlists, common logs."
     */
    fun resetEngine() {
        playback.stop()
        history.resetAllStats()
        taste.resetAllSignals()
        signalTimeline.clear()
        session.reset()
        toaster.show("Engine reset")
        // Favorites + Disliked are part of the user's intentional taste —
        // include them in the reset per the Capacitor parity contract.
        disliked.clear()
        // Best-effort favorites clear — FavoritesEngine has no public clear()
        // helper today, so iterate the current set.
        for (fn in favorites.favorites.value) favorites.remove(fn)
        // Saved playback state — wipe queue + position so cold restart starts fresh.
        sideEffectScope.launch {
            try { preferences.clear() } catch (t: Throwable) { android.util.Log.w("AppContainer", "preferences.clear failed: ${t.message}") }
        }
    }
}
