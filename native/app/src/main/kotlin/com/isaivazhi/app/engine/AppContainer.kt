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

    /**
     * Push #67: signal that fires AFTER a favorite/dislike toggle has
     * completed, so MainActivity can trigger an immediate Up Next /
     * Discover rebuild. Capacitor parity (`scheduleRecommendationRebuild`
     * with refreshQueue=true, refreshDiscover=true on toggles).
     */
    private val _rebuildSignal = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val rebuildSignal: kotlinx.coroutines.flow.SharedFlow<String> = _rebuildSignal

    val favorites: FavoritesEngine by lazy {
        FavoritesEngine(appContext).also { fav ->
            fav.onChangeHook = { fn, _ ->
                val (before, after) = taste.applyManualPriorChange(
                    filename = fn,
                    isFavorite = fav.isFavorite(fn),
                    isDisliked = disliked.isDisliked(fn),
                )
                val delta = after.directScore - before.directScore
                val nowFav = fav.isFavorite(fn)
                activityLog.log(
                    category = "taste",
                    type = if (nowFav) "favorite_added" else "favorite_removed",
                    message = (if (nowFav) "Favorited " else "Unfavorited ") + fn,
                    data = mapOf("filename" to fn, "delta" to delta),
                )
                sideEffectScope.launch {
                    taste.propagateSimilarityBoost(fn, delta, "favorite_toggle")
                    _rebuildSignal.emit("favorite_toggle")
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
                val nowDis = dis.isDisliked(fn)
                activityLog.log(
                    category = "taste",
                    type = if (nowDis) "dislike_added" else "dislike_removed",
                    message = (if (nowDis) "Disliked " else "Undisliked ") + fn,
                    data = mapOf("filename" to fn, "delta" to delta),
                )
                sideEffectScope.launch {
                    taste.propagateSimilarityBoost(fn, delta, "dislike_toggle")
                    _rebuildSignal.emit("dislike_toggle")
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

    // Push #64: persistent Discover snapshot so cold start renders the
    // page from cache instantly while fresh data overlays in the
    // background. Capacitor parity: `lastProfile` cache.
    val discoverCache: DiscoverCacheEngine by lazy { DiscoverCacheEngine(appContext) }

    // Push #69: app-wide activity log for non-playback events. Capacitor
    // parity: `engine.activity` separate from SignalTimeline.
    val activityLog: ActivityLogEngine by lazy { ActivityLogEngine(appContext) }

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
        activityLog.log("engine", "reset", "Engine reset triggered", level = ActivityLogEngine.Level.WARN)
        playback.stop()
        history.resetAllStats()
        taste.resetAllSignals()
        signalTimeline.clear()
        session.reset()
        discoverCache.clear()
        activityLog.clear()
        // Push #66: clear the transitions history buffer + pending evidence
        // so cold-start reconciliation doesn't replay pre-reset listens.
        com.isaivazhi.app.Media3PlaybackService.clearRecentTransitionsStatic(appContext)
        try {
            appContext.getSharedPreferences("playback_pending_evidence", android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (_: Throwable) {}

        // Push #72: PRESERVE favorites + dislikes across reset. They
        // represent the user's intentional taste signal — not playback
        // history. Re-apply each manual prior so the taste signal map
        // gets fresh entries with directScore from the priors (favorite
        // → +2.0, dislike → -3.0). Without this re-apply, favorites
        // would have isFavorite=true in FavoritesEngine but no
        // TasteSignal entry, so they'd show as 0 in the Taste page's
        // positive list.
        val favSet = favorites.favorites.value.toSet()
        val disSet = disliked.disliked.value.toSet()
        val toReapply = (favSet + disSet)
        for (fn in toReapply) {
            taste.applyManualPriorChange(
                filename = fn,
                isFavorite = fn in favSet,
                isDisliked = fn in disSet,
            )
        }
        android.util.Log.i(
            "AppContainer",
            "Engine reset: preserved ${favSet.size} favorites + ${disSet.size} dislikes; re-applied priors for ${toReapply.size} songs"
        )

        toaster.show("Engine reset (favorites + dislikes preserved)")
        // Saved playback state — wipe queue + position so cold restart starts fresh.
        sideEffectScope.launch {
            try { preferences.clear() } catch (t: Throwable) { android.util.Log.w("AppContainer", "preferences.clear failed: ${t.message}") }
        }
    }
}
