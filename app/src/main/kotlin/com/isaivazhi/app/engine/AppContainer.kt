package com.isaivazhi.app.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
                // Push #74: mirror the favorites set to the CapacitorStorage
                // SharedPreferences the Java service reads in
                // Media3PlaybackService.isCurrentFavorite (line 1157). Without
                // this, a Kotlin-side toggle from the mini-player or NowPlaying
                // would leave the notification's heart icon stale.
                sideEffectScope.launch {
                    runCatching {
                        val arr = org.json.JSONArray()
                        for (f in fav.favorites.value) arr.put(f)
                        val payload = org.json.JSONObject().put("filenames", arr).toString()
                        appContext.getSharedPreferences("CapacitorStorage", android.content.Context.MODE_PRIVATE)
                            .edit().putString("favorites", payload).apply()
                    }
                }
                // Push #74: tell the service to rebuild its notification so
                // the heart icon (which reads from CapacitorStorage SP)
                // refreshes immediately, not on the next metadata change.
                runCatching { playback.refreshNotification() }
            }
        }
    }

    suspend fun reconcileNotificationFavoriteStorage() {
        favorites.awaitReady()
        val prefs = appContext.getSharedPreferences("CapacitorStorage", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("favorites", null)
        if (!raw.isNullOrBlank()) {
            val fromService = runCatching {
                val arr = org.json.JSONObject(raw).optJSONArray("filenames") ?: org.json.JSONArray()
                buildSet {
                    for (i in 0 until arr.length()) {
                        val filename = arr.optString(i, "")
                        if (filename.isNotBlank()) add(filename)
                    }
                }
            }.getOrDefault(emptySet())
            favorites.replaceAllFromExternal(fromService)
            return
        }
        runCatching {
            val arr = org.json.JSONArray()
            for (f in favorites.favorites.value) arr.put(f)
            val payload = org.json.JSONObject().put("filenames", arr).toString()
            prefs.edit().putString("favorites", payload).apply()
            playback.refreshNotification()
        }
    }

    /** Import legacy Capacitor `disliked_songs` JSON into [DislikedEngine]. */
    suspend fun reconcileCapacitorDislikedStorage() {
        disliked.awaitReady()
        val prefs = appContext.getSharedPreferences("CapacitorStorage", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("disliked_songs", null)
        if (raw.isNullOrBlank()) return
        val fromCapacitor = runCatching {
            val arr = org.json.JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    val filename = arr.optString(i, "")
                    if (filename.isNotBlank()) add(filename)
                }
            }
        }.getOrDefault(emptySet())
        if (fromCapacitor.isEmpty()) return
        disliked.replaceAllFromExternal(disliked.disliked.value + fromCapacitor)
    }

    /**
     * Backfill favorites/dislikes engines from legacy taste-signal flags and
     * sync taste priors from the authoritative engine sets.
     */
    suspend fun reconcileManualTasteFlags() {
        taste.awaitReady()
        favorites.awaitReady()
        disliked.awaitReady()
        for ((fn, sig) in taste.signals.value) {
            if (sig.isDisliked && !disliked.isDisliked(fn)) {
                disliked.addSilent(fn)
            }
            if (sig.isFavorite && !favorites.isFavorite(fn)) {
                favorites.addSilent(fn)
            }
        }
        val allManual = favorites.favorites.value + disliked.disliked.value
        for (fn in allManual) {
            taste.applyManualPriorChange(
                filename = fn,
                isFavorite = fn in favorites.favorites.value,
                isDisliked = fn in disliked.disliked.value,
            )
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
                if (nowDis) {
                    playback.removeFilenamesFromUpcoming(setOf(fn))
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

    val playbackSignalLedger: PlaybackSignalLedger by lazy { PlaybackSignalLedger(appContext) }

    val playbackSignalProcessor: PlaybackSignalProcessor by lazy { PlaybackSignalProcessor(this) }

    // Push #63: in-memory session counters (encounters/skips/positives).
    // Reset on app start; not persisted. TasteEngine reads `current()` to
    // build the session-level snapshots that the SignalTimeline records.
    val session: SessionEngine by lazy { SessionEngine() }

    // Phase 4 (2026-06-01): DiscoverCacheEngine removed alongside the Discover
    // page. "Similar to current" now recomputes inline inside NowPlayingScreen;
    // no caching needed since it's a single k=10 query per song change.

    // Push #69: app-wide activity log for non-playback events. Capacitor
    // parity: `engine.activity` separate from SignalTimeline.
    val activityLog: ActivityLogEngine by lazy { ActivityLogEngine(appContext) }

    val playback: PlaybackEngine by lazy {
        PlaybackEngine(
            appContext, preferences, history, taste, signalTimeline, session, toaster,
            activityLog, favorites,
        )
    }

    val recommender: Recommender by lazy { Recommender(embeddingDb) }

    val embedding: EmbeddingEngine by lazy {
        EmbeddingEngine(appContext, embeddingDb, toaster, preferences)
    }

    /**
     * Bugfix 2026-06-01d: process-lifetime library snapshot. Originally
     * the song list lived only as a Compose state inside MainActivity,
     * which meant background engines (like RecommendationCache) couldn't
     * see it when the Activity was destroyed on the lockscreen. Now any
     * loader (IsaiVazhiApp warm path or MainActivity's LaunchedEffect)
     * publishes the result here so all process-lifetime code can read it.
     */
    val library: kotlinx.coroutines.flow.MutableStateFlow<List<Song>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    /**
     * Bugfix 2026-06-01d: process-lifetime scope for engines that must
     * keep working after MainActivity is destroyed (lockscreen precompute,
     * etc.). Uses Default dispatcher so embedding-DB calls don't block
     * Main; the EmbeddingDbFacade itself serializes onto its own
     * HandlerThread so dispatcher choice here is just for orchestration.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Bugfix 2026-06-01d: proactive recommendation precompute. See
     * RecommendationCache kdoc for the full rationale. Must be started
     * explicitly (Application class calls `start()` after warm).
     */
    val recommendationCache: RecommendationCache by lazy {
        RecommendationCache(this, applicationScope)
    }

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
        signalTimeline.clear()
        session.reset()
        activityLog.clear()
        // Push #66: clear the transitions history buffer + pending evidence
        // so cold-start reconciliation doesn't replay pre-reset listens.
        com.isaivazhi.app.Media3PlaybackService.clearRecentTransitionsStatic(appContext)
        playbackSignalLedger.clear()
        try {
            appContext.getSharedPreferences("playback_pending_evidence", android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (_: Throwable) {}

        // Push #72: PRESERVE favorites + dislikes across reset. They
        // represent the user's intentional taste signal — not playback
        // history. Re-apply each manual prior so the taste signal map
        // gets fresh entries with directScore from the priors (favorite
        // → +1.5, dislike → -2.5; see Push #73 in TasteEngine for
        // tuning). Without this re-apply, favorites would have
        // isFavorite=true in FavoritesEngine but no TasteSignal entry,
        // so they'd show as 0 in the Taste page's positive list.
        runBlocking {
            favorites.awaitReady()
            disliked.awaitReady()
        }
        val favFromEngine = favorites.favorites.value
        val disFromEngine = disliked.disliked.value
        val favFromTaste = taste.signals.value.filter { it.value.isFavorite }.keys
        val disFromTaste = taste.signals.value.filter { it.value.isDisliked }.keys
        val favSet = (favFromEngine + favFromTaste).toSet()
        val disSet = (disFromEngine + disFromTaste).toSet()
        taste.resetAllSignalsPreservingManual(favSet, disSet)
        android.util.Log.i(
            "AppContainer",
            "Engine reset: preserved ${favSet.size} favorites + ${disSet.size} dislikes; re-applied priors for ${(favSet + disSet).size} songs"
        )

        // After reseeding manual priors, re-apply a similarity halo around each
        // favorite so its top-N similar songs regain a positive boost.
        sideEffectScope.launch {
            try {
                val snapshot = taste.signals.value
                for (fn in favSet) {
                    val sig = snapshot[fn] ?: continue
                    val delta = sig.directScore
                    if (delta != 0f) {
                        taste.propagateSimilarityBoost(fn, delta, "reset_engine_reseed")
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("AppContainer", "Engine reset similarity reseed failed: ${t.message}")
            }
        }

        toaster.show("Engine reset (favorites + dislikes preserved)")
        // Saved playback state — wipe queue + position so cold restart starts fresh.
        sideEffectScope.launch {
            try { preferences.clear() } catch (t: Throwable) { android.util.Log.w("AppContainer", "preferences.clear failed: ${t.message}") }
        }
    }
}
