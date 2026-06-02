package com.isaivazhi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.isaivazhi.app.engine.ActivityLogEngine
import com.isaivazhi.app.engine.AlbumArtRepository
import com.isaivazhi.app.engine.AppContainer
import com.isaivazhi.app.engine.ArtPrefetch
import com.isaivazhi.app.engine.DebugLogCapture
import com.isaivazhi.app.engine.EmbeddingsImport
import com.isaivazhi.app.engine.LibraryCache
import com.isaivazhi.app.engine.Song
import com.isaivazhi.app.engine.TasteEngine
import com.isaivazhi.app.engine.signedDirectScore
import com.isaivazhi.app.ui.rememberAudioPermissionGate
import com.isaivazhi.app.ui.rememberDeleteSongHelper
import com.isaivazhi.app.ui.rememberNotificationsPermissionGate
import com.isaivazhi.app.ui.screens.AiManagementScreen
import com.isaivazhi.app.ui.screens.AlbumsScreen
import com.isaivazhi.app.ui.screens.BrowseCategory
import com.isaivazhi.app.ui.screens.BrowseScreen
import com.isaivazhi.app.ui.screens.LogsScreen
import com.isaivazhi.app.ui.screens.LogsTab
// Phase 4 (2026-06-01): DiscoverScreen / DiscoverSectionRef / EmbeddingStatusBanner
// imports removed alongside the deleted Discover tab. The Similar-to-current
// row in NowPlayingScreen now covers the only valued surface from Discover.
// EmbeddingStatusBanner is still used by AiManagementScreen.
import com.isaivazhi.app.ui.screens.EngineSnapshot
import com.isaivazhi.app.ui.screens.FavoritesScreen
import com.isaivazhi.app.ui.screens.HistoryScreen
import com.isaivazhi.app.ui.screens.MiniPlayer
import com.isaivazhi.app.ui.screens.NowPlayingScreen
import com.isaivazhi.app.ui.screens.EmbeddingsImportDialog
import com.isaivazhi.app.ui.screens.OnboardingScreen
import com.isaivazhi.app.ui.screens.PlaylistDetailScreen
import com.isaivazhi.app.ui.screens.PlaylistsScreen
import com.isaivazhi.app.ui.screens.SearchOverlay
import com.isaivazhi.app.ui.screens.SettingsScreen
import com.isaivazhi.app.ui.screens.SongMenuSheet
import com.isaivazhi.app.ui.screens.SongsScreen
import com.isaivazhi.app.ui.screens.TasteScreen
import com.isaivazhi.app.ui.screens.UpNextScreen
import com.isaivazhi.app.ui.screens.ViewAllScreen
import com.isaivazhi.app.ui.screens.browseCategorySongs
import com.isaivazhi.app.ui.screens.buildBrowseTiles
import com.isaivazhi.app.ui.theme.IsaiVazhiTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class Tab(val title: String) {
    // Phase 4 (2026-06-01): Discover tab removed. AI recommendations
    // now surface only as the Similar row inside NowPlayingScreen and as
    // the Refresh button on the player. The remaining tabs behave like a
    // standard offline music player.
    Songs("Songs"),
    Albums("Albums"),
    UpNext("Up Next"),
    Browse("Browse"),
}

private sealed class Overlay {
    data object None : Overlay()
    data object FullPlayer : Overlay()
    data object Search : Overlay()
    data object Settings : Overlay()
    data object Favorites : Overlay()
    data object Playlists : Overlay()
    data object History : Overlay()
    data object Taste : Overlay()
    data object Ai : Overlay()
    data class Logs(val initialTab: LogsTab = LogsTab.Activity) : Overlay()
    data class PlaylistDetail(val playlistId: String) : Overlay()
    data class ViewAll(val category: BrowseCategory) : Overlay()
    // Phase 4 (2026-06-01): SectionViewAll removed with the Discover page.
    // Browse-tab ViewAll uses the dedicated Overlay.ViewAll(category) above.
}

private fun logLedgerState(container: AppContainer, reason: String) {
    val svc = com.isaivazhi.app.Media3PlaybackService.INSTANCE
    val diag = runCatching { container.playbackSignalLedger.diagnostics() }.getOrNull()
    container.activityLog.log(
        category = "engine",
        type = "LEDGER_STATE",
        message = "Ledger state checked ($reason)",
        data = mapOf(
            "reason" to reason,
            "serviceAlive" to (svc != null),
            "serviceMediaId" to (svc?.getCurrentMediaIdSnapshot() ?: ""),
            "serviceInstId" to (svc?.getCurrentPlaybackInstanceId() ?: 0L),
            "servicePositionMs" to (svc?.getCurrentPositionMsSnapshot() ?: 0L),
            "serviceDurationMs" to (svc?.getCurrentDurationMsSnapshot() ?: 0L),
            "servicePlayedMs" to (svc?.getAccumulatedPlayedMsSnapshot() ?: 0L),
            "ledgerRawCount" to (diag?.ledgerRawCount ?: -1),
            "recoveryRawCount" to (diag?.recoveryRawCount ?: -1),
            "transitionRawCount" to (diag?.transitionRawCount ?: -1),
            "processedIdCount" to (diag?.processedIdCount ?: -1),
            "pendingCount" to (diag?.pendingCount ?: -1),
            "newestEventId" to (diag?.newestEventId ?: ""),
            "newestFilename" to (diag?.newestFilename ?: ""),
            "newestAction" to (diag?.newestAction ?: ""),
            "newestInstanceId" to (diag?.newestInstanceId ?: 0L),
        ),
    )
}

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    companion object {
        // Push #77 diagnostic: detect process-cold vs Activity-cold restarts.
        // Static fields keep their values across Activity recreations within
        // the same process. If onCreateCount > 1 at MainActivity.onCreate time
        // but the field was already initialised, the Activity was recreated
        // while the process stayed alive (the "app reloads everything but the
        // song keeps playing" symptom).
        private val processStartedAt: Long = System.currentTimeMillis()
        @Volatile private var onCreateCount: Int = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Install crash handler EARLY so any subsequent uncaught exception
        // lands in the persisted Debug Logs file before the process dies.
        DebugLogCapture.installCrashHandler(applicationContext)

        // Bugfix 2026-06-01c: reuse the Application-scoped AppContainer
        // so the eager DB warm done in IsaiVazhiApp.onCreate is visible
        // to MainActivity. Creating a fresh AppContainer here would have
        // re-instantiated `embeddingDb` (a `by lazy` field) and undone
        // the warm. Fallback to a new container only if (in tests or
        // some edge teardown path) the app instance isn't IsaiVazhiApp.
        container = (application as? IsaiVazhiApp)?.container
            ?: AppContainer(applicationContext)

        // Push #77 diagnostic: log every Activity onCreate with the
        // process-cold vs Activity-cold distinction surfaced.
        onCreateCount++
        val processAgeMs = System.currentTimeMillis() - processStartedAt
        val isProcessCold = onCreateCount == 1
        container.activityLog.log(
            category = "engine",
            type = if (isProcessCold) "ACTIVITY_PROCESS_COLD" else "ACTIVITY_RECREATED",
            message = if (isProcessCold) "Activity.onCreate (process cold, onCreateCount=1)"
            else "Activity.onCreate (process alive, onCreateCount=$onCreateCount processAgeMs=$processAgeMs)",
            data = mapOf(
                "onCreateCount" to onCreateCount,
                "processAgeMs" to processAgeMs,
                "isProcessCold" to isProcessCold,
                "savedInstanceStateNull" to (savedInstanceState == null),
                "serviceAlive" to (com.isaivazhi.app.Media3PlaybackService.INSTANCE != null),
            ),
        )

        CoroutineScope(Dispatchers.Default).launch {
            // Bugfix 2026-06-01c: skip migrate when the DataStore cache
            // proves the DB is already populated. The migrate call is a
            // no-op in that case (reason="db_already_populated") but the
            // FIRST DB call after activity recreate still pays ~22s of
            // sqlite-vec/Room re-init cost on the single
            // EmbeddingDbWorker thread. Skipping it here keeps the worker
            // free for the recommend pipeline triggered by lockscreen
            // Refresh taps. The Application class (IsaiVazhiApp) warms
            // the DB eagerly once per process via a stats() call.
            val cached = try { container.preferences.cachedEmbedStats() } catch (_: Throwable) { null }
            if (cached != null && cached.rowCount > 0) {
                container.activityLog.log(
                    category = "engine",
                    type = "PERF_MIGRATE_SKIPPED",
                    message = "skip migrateFromLegacy (cached rowCount=${cached.rowCount})",
                    data = mapOf("cachedRowCount" to cached.rowCount),
                )
                return@launch
            }
            val migStart = System.currentTimeMillis()
            container.activityLog.log(
                category = "engine",
                type = "PERF_MIGRATE_START",
                message = "embeddingDb.migrateFromLegacy start",
            )
            var migrateResult: org.json.JSONObject? = null
            try { migrateResult = container.embeddingDb.migrateFromLegacy() }
            catch (t: Throwable) { android.util.Log.w("MainActivity", "embDb migrate failed: ${t.message}") }
            val migElapsed = System.currentTimeMillis() - migStart
            container.activityLog.log(
                category = "engine",
                type = "PERF_MIGRATE_DONE",
                message = "embeddingDb.migrateFromLegacy done in ${migElapsed}ms",
                data = mapOf(
                    "elapsedMs" to migElapsed,
                    "migrated" to (migrateResult?.optBoolean("migrated", false) ?: false),
                    "reason" to (migrateResult?.optString("reason", "") ?: ""),
                    "rowCount" to (migrateResult?.optInt("rowCount", -1) ?: -1),
                ),
            )
        }

        container.playback.preWarm()

        setContent {
            IsaiVazhiTheme {
                Surface(modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background) {
                    AppRoot(container = container)
                }
            }
        }
    }

    override fun onPause() {
        // Portable IVZ backup on background (fast ~5 MB write; no JSON float stringify).
        if (::container.isInitialized) {
            // Push #65: flush mid-song listening evidence into the
            // SignalTimeline before the process can be killed. Without
            // this, a user who plays a song to 30-50% then closes the
            // app / installs an update loses ALL signal for that listen
            // — no plays/avgFraction/xScore update, no timeline entry,
            // no similarity propagation. Capacitor README parity:
            // "JS only saves evidence snapshots on background / close;
            //  cold-start recovery reconciles the two."
            flushCurrentPlayback(reason = "app_background")
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val splits = container.preferences.getEmbeddingSplitCount()
                    container.embeddingDb.exportEmbeddingsBin(splits)
                }
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::container.isInitialized) {
            // Push #65: also flush on onDestroy. onPause should have
            // caught the common case, but onDestroy is a second chance
            // for activity recreation / system-driven teardown.
            flushCurrentPlayback(reason = "app_destroy")
            container.playback.release()
        }
        super.onDestroy()
    }

    /**
     * Push #66 (reworked from #65): save a TENTATIVE listen snapshot —
     * do NOT fire a SignalTimeline event. The service keeps accumulating
     * across background; if a real transition fires later (user comes
     * back and changes song / song ends), THAT transition is the
     * authoritative event and the snapshot is cleared. If the process
     * dies before a real transition, cold-start reconciliation replays
     * the snapshot via [ingestPendingEvidence].
     *
     * Capacitor parity: `_persistPendingListenEvidence` in app.js:534.
     * Major change from Push #65:
     *   - No more immediate signalTimeline.append → no duplicate events.
     *   - No more markEvidenceFlushed → accumulator keeps growing across
     *     background so continued listening accumulates into ONE listen.
     *   - playbackInstanceId is captured so cold-start can correlate.
     */
    private fun flushCurrentPlayback(reason: String) {
        val svc = Media3PlaybackService.INSTANCE ?: return
        val mediaId = svc.getCurrentMediaIdSnapshot()
        if (mediaId.isBlank()) return
        val played = svc.getAccumulatedPlayedMsSnapshot()
        val duration = svc.getCurrentDurationMsSnapshot()
        if (played < 1000L || duration <= 0L) {
            android.util.Log.i(
                "MainActivity",
                "flushCurrentPlayback skip reason=$reason mediaId=$mediaId played=${played}ms dur=${duration}ms (no evidence)"
            )
            return
        }
        val instId = svc.getCurrentPlaybackInstanceId()
        val frac = (played.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        android.util.Log.i(
            "MainActivity",
            "flushCurrentPlayback (tentative snapshot) reason=$reason mediaId=$mediaId played=${played}ms dur=${duration}ms frac=${"%.3f".format(frac)} instId=$instId"
        )
        container.activityLog.log(
            category = "engine",
            type = "FLUSH",
            message = "$mediaId — ${(frac * 100).toInt()}% reason=$reason",
            data = mapOf(
                "mediaId" to mediaId,
                "playedMs" to played,
                "durationMs" to duration,
                "fraction" to frac,
                "reason" to reason,
                "instId" to instId,
            ),
        )
        // Tentative snapshot only — no SignalTimeline event, no
        // recordPlaybackEvent, no markEvidenceFlushed. The actual scoring
        // happens when (a) a real native transition fires for this
        // playbackInstanceId — in which case the snapshot is cleared, OR
        // (b) cold-start reconciliation runs.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                container.preferences.savePendingEvidence(mediaId, played, duration, instId)
            }
        }
    }
}

@Composable
private fun AppRoot(container: AppContainer) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val permission = rememberAudioPermissionGate(ctx)
    // Push #55: POST_NOTIFICATIONS is now a deliberate startup step
    // with its own gate UI (push #54 had an auto-request LaunchedEffect
    // that Android silently suppressed after one denial — user saw
    // nothing). The gate shows after audio permission is granted, with
    // an explicit "Allow notifications" CTA, an "Open Settings" fallback
    // for the soft-denied case, and a "Skip for now" link that sets a
    // session-local dismiss flag.
    val notificationsPermission = rememberNotificationsPermissionGate(ctx)
    var notifGateDismissed by remember { mutableStateOf(false) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    // Bugfix 2026-06-01d: mirror songs to the process-lifetime container
    // so engines that run outside the Activity (RecommendationCache) can
    // see the library. Covers every reload site — invalidate-on-import,
    // settings rescan, library-changed broadcast — without sprinkling
    // assignments through each callsite.
    LaunchedEffect(songs) {
        if (songs.isNotEmpty()) container.library.value = songs
    }
    var scanError by remember { mutableStateOf<String?>(null) }
    // Phase 4 (2026-06-01): pager starts on Songs (index 0 after Discover
    // removal). A LaunchedEffect below restores the persisted last tab on
    // cold start, and another collector saves the user's current tab on
    // every change so the app re-opens where they left off.
    val pagerState = rememberPagerState(pageCount = { Tab.entries.size })
    LaunchedEffect(Unit) {
        try {
            val saved = runCatching { container.preferences.loadLastTab() }.getOrNull()
            if (saved.isNullOrBlank()) return@LaunchedEffect
            val target = Tab.entries.indexOfFirst { it.name == saved }
            if (target >= 0 && target < Tab.entries.size && target != pagerState.currentPage) {
                runCatching { pagerState.scrollToPage(target) }
            } else {
                // Saved tab is no longer valid (e.g. enum changed) — fall back
                // to Songs so a bad value cannot cause a startup crash loop.
                runCatching {
                    pagerState.scrollToPage(0)
                    container.preferences.saveLastTab(Tab.Songs.name)
                }
            }
        } catch (t: Throwable) {
            // Any unexpected failure during restore should not crash startup.
            // Log via Android's default logger; DebugLogCapture will persist it.
            android.util.Log.w("MainActivity", "last-tab restore failed: ${t.message}")
        }
    }
    LaunchedEffect(pagerState) {
        androidx.compose.runtime.snapshotFlow { pagerState.currentPage }
            .collect { page ->
                val name = Tab.entries.getOrNull(page)?.name ?: return@collect
                runCatching { container.preferences.saveLastTab(name) }
            }
    }
    var embeddingsRowCount by remember { mutableStateOf<Int?>(null) }
    var embeddingsDim by remember { mutableStateOf(0) }
    var vecExtLoaded by remember { mutableStateOf(false) }
    var embeddedFilenames by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Push #53: track filepaths too. The AI page's Pending list uses
    // filepath comparison (filename can drift between MediaStore's
    // DISPLAY_NAME and the EmbeddingService's File(path).getName()).
    var embeddedFilepaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var artCacheBytes by remember { mutableStateOf(0L) }
    var onboardingDismissed by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importInProgress by remember { mutableStateOf(false) }
    var exportBackupInProgress by remember { mutableStateOf(false) }
    var exportBackupStatus by remember { mutableStateOf<String?>(null) }
    var embeddingSplitCount by remember { mutableStateOf(3) }
    var libraryEmbedSplitCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        embeddingSplitCount = container.preferences.getEmbeddingSplitCount()
        libraryEmbedSplitCount = container.preferences.getLastEmbedSplitCount()
    }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    var libraryMenuOpen by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var menuPlaylistContext by remember { mutableStateOf<String?>(null) }  // playlist id for "Remove from playlist"
    // Push #42 Tier 2I: when the long-press came from the Songs tab,
    // expose a "Play in order" entry. From Discover cards / Albums
    // (where tap already plays a section), the entry is hidden.
    var menuSongsTabIndex by remember { mutableStateOf<Int?>(null) }
    var albumToExpand by remember { mutableStateOf<String?>(null) }
    var albumRevealRequestId by remember { mutableStateOf(0) }
    // Push #62: album-level long-press menu state. When non-null, the
    // AlbumMenuSheet renders with Play / Shuffle / Delete actions.
    var albumMenuTracks by remember { mutableStateOf<List<Song>?>(null) }
    var albumMenuName by remember { mutableStateOf<String?>(null) }
    var showAlbumDeleteConfirm by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSongs by rememberUpdatedState(songs)

    DisposableEffect(lifecycleOwner, permission.granted) {
        val observer = LifecycleEventObserver { _, event ->
            // Push #77 diagnostic: log every lifecycle transition so we can
            // see when the Activity backgrounds / foregrounds / stops, and
            // correlate with re-runs of the cold-start LE.
            val svc = com.isaivazhi.app.Media3PlaybackService.INSTANCE
            container.activityLog.log(
                category = "engine",
                type = "ACTIVITY_LIFECYCLE",
                message = "Activity lifecycle: ${event.name}",
                data = mapOf(
                    "event" to event.name,
                    "permissionGranted" to permission.granted,
                    "songsCount" to latestSongs.size,
                    "serviceAlive" to (svc != null),
                    "serviceIsPlaying" to (svc?.let { runCatching { it.javaClass.getMethod("getCurrentMediaIdSnapshot").invoke(it) as? String }.getOrNull()?.isNotEmpty() } ?: false),
                ),
            )
            if (event == Lifecycle.Event.ON_RESUME && permission.granted) {
                val currentSongs = latestSongs
                if (currentSongs.isNotEmpty()) {
                    coroutineScope.launch {
                        container.activityLog.awaitReady()
                        logLedgerState(container, "foreground_resume_before_drain")
                        container.playbackSignalProcessor.processPending(
                            songs = currentSongs,
                            reason = "foreground_resume",
                            logWhenEmpty = true,
                        )
                        logLedgerState(container, "foreground_resume_after_drain")
                    }
                }
            }
            // Bugfix 2026-06-01g: fire a precompute the moment the Activity
            // moves to background. mmap pages are still hot at this instant
            // (eviction starts ~1-2s later). This guarantees the cache is
            // freshly populated when the user taps Refresh on the lockscreen,
            // and the subsequent pop_refill runs against the still-warm pages
            // kept alive by the 30s keepWarmRunnable in Media3PlaybackService.
            if (event == Lifecycle.Event.ON_STOP && permission.granted) {
                runCatching {
                    container.recommendationCache.precomputeNow(reason = "on_stop")
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val playbackState by container.playback.state.collectAsState()
    // Live position/duration come from separate StateFlows so the 500 ms
    // position poll doesn't recompose the whole AppRoot every tick. Only
    // the mini player slider + Now Playing scrub bar consume these.
    val livePositionMs by container.playback.livePosition.collectAsState()
    val liveDurationMs by container.playback.liveDuration.collectAsState()

    // Push #44: replaced Material 3 SnackbarHost (bottom-center, ~4 s
    // suspending) with a custom top-end overlay. The previous setup
    // chained `showSnackbar` calls through a suspending collect, so a
    // rapid second tap (shuffle then loop) waited ~4 s for the first
    // toast to dismiss. Now each emission preempts the previous one
    // and the toast renders near the hamburger menu (top-right).
    var currentToast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        container.toaster.messages.collect { msg ->
            // Non-suspending: immediately update the visible message.
            // The auto-clear LaunchedEffect below resets when this changes.
            currentToast = msg
        }
    }
    // Auto-clear the toast after ~1.8 s. Re-keys on every new message
    // so the timer restarts and the latest emission always gets its
    // full display window.
    LaunchedEffect(currentToast) {
        if (currentToast != null) {
            kotlinx.coroutines.delay(1800)
            currentToast = null
        }
    }
    val embeddingStatus by container.embedding.status.collectAsState()
    val embeddingLogs by container.embedding.logBuffer.lines.collectAsState()
    val favoritesSet by container.favorites.favorites.collectAsState()
    val dislikedSet by container.disliked.disliked.collectAsState()
    val playlistsList by container.playlists.playlists.collectAsState()
    val historyEvents by container.history.events.collectAsState()
    val historyStats by container.history.stats.collectAsState()
    val tuning by container.taste.tuning.collectAsState()
    val tasteSignals by container.taste.signals.collectAsState()
    // Push #63: decorated signals (chips + display hard-block set at full guard).
    val decoratedSignals by container.taste.decoratedSignals.collectAsState()
    val upNextHardBlocked = remember(tuning.negativeStrength, tasteSignals, decoratedSignals) {
        container.taste.hardBlockedFilenamesForPolicy(tuning.negativeStrength)
    }
    val upNextSoftExcluded = remember(tuning.negativeStrength, tasteSignals, decoratedSignals) {
        container.taste.softExcludedFilenamesForPolicy(tuning.negativeStrength)
    }
    val upNextPolicyExcludes = remember(
        tuning.negativeStrength,
        tasteSignals,
        decoratedSignals,
        dislikedSet,
    ) {
        com.isaivazhi.app.engine.RecommendationPolicy.unionExcludes(
            upNextHardBlocked,
            upNextSoftExcluded,
            dislikedSet,
        )
    }
    var lastBlendInfo by remember { mutableStateOf<LastBlendInfo?>(null) }
    val timelineEvents by container.signalTimeline.events.collectAsState()
    // Push #57: filepaths the user marked "skip embedding" for. The AI
    // page's Pending list excludes these.
    val skippedEmbeddings by container.skippedEmbeddings.skipped.collectAsState()

    // Push #58: scoped-storage-aware delete helper. Replaces the previous
    // `runCatching { SongDelete.deleteFromDevice }.isSuccess` flow which
    // lied about success on Android 11+ because resolver.delete throws
    // RecoverableSecurityException and the file never actually got
    // deleted. The helper presents the system's delete-confirmation
    // dialog when needed and routes the real result back here so the
    // toast + library refresh only fire after the OS confirms the
    // delete actually happened.
    // Push #61: tracks the filepath the user just deleted from the Audio
    // Duplicates section. Used inside the deleteSongHelper callback to
    // also drop the T_PATH entry so the duplicate group reflects the
    // removal immediately.
    var pendingAudioDupCleanupFilepath by remember { mutableStateOf<String?>(null) }
    // Push #50/#61: AI-page version counter for the duplicates LaunchedEffect.
    // Declared here so the deleteSongHelper callback can bump it after the
    // delete completes (the LE itself is wired further down).
    var dupesRefreshTick by remember { mutableStateOf(0) }
    val deleteSongHelper = rememberDeleteSongHelper(
        ctx = ctx,
        onBatchResult = { filepaths, success, error ->
            if (success) {
                container.toaster.show("Deleted ${filepaths.size} files")
                container.embedding.logBuffer.append("delete", "batch removed ${filepaths.size} files")
                coroutineScope.launch {
                    songs = LibraryCache.invalidate(ctx)
                    // Best-effort: also drop T_PATH entries for each
                    // deleted filepath in case any were audio-dupes.
                    for (fp in filepaths) {
                        container.embedding.removeAudioDupPath(fp) { _ -> }
                    }
                    refreshDbStats(container) { rc, dim, ext, fns, fps ->
                        embeddingsRowCount = rc; embeddingsDim = dim; vecExtLoaded = ext
                        embeddedFilenames = fns
                        embeddedFilepaths = fps
                    }
                    dupesRefreshTick++
                }
            } else {
                container.toaster.show(
                    if (error == "User declined") "Album delete cancelled"
                    else "Batch delete failed${if (error != null) " — $error" else ""}"
                )
                container.embedding.logBuffer.append("delete", "batch FAILED (${filepaths.size} files) — $error")
            }
        },
    ) { filepath, success, error ->
        if (success) {
            val displayName = filepath.substringAfterLast('/')
            container.toaster.show("Deleted \"$displayName\"")
            container.embedding.logBuffer.append("delete", "removed $filepath")
            // Push #61: if the delete originated from the Audio Duplicates
            // section, also drop the T_PATH row for this filepath so the
            // duplicate group recomputes correctly. The matching content_hash
            // may still be embedded under another filepath (one of the
            // siblings) — that one stays valid.
            val wasAudioDup = pendingAudioDupCleanupFilepath == filepath
            pendingAudioDupCleanupFilepath = null
            coroutineScope.launch {
                // Refresh library so the song disappears from Songs/Albums
                // immediately (LibraryScanner now filters non-existent
                // filepaths on top of MediaStore so even pre-MediaStore-
                // re-scan we won't show the row).
                songs = LibraryCache.invalidate(ctx)
                if (wasAudioDup) {
                    container.embedding.removeAudioDupPath(filepath) { _ -> }
                }
                // Refresh embedded sets so any embedding orphaned by the
                // delete surfaces in the Stale list on next AI-page open.
                refreshDbStats(container) { rc, dim, ext, fns, fps ->
                    embeddingsRowCount = rc; embeddingsDim = dim; vecExtLoaded = ext
                    embeddedFilenames = fns
                    embeddedFilepaths = fps
                }
                // Re-pull audio dupes for the AI page.
                dupesRefreshTick++
            }
        } else {
            pendingAudioDupCleanupFilepath = null
            container.toaster.show(
                if (error == "User declined") "Delete cancelled"
                else "Delete failed${if (error != null) " — $error" else ""}"
            )
            container.embedding.logBuffer.append("delete", "FAILED $filepath ($error)")
        }
    }
    val recModeFlow = container.preferences.recMode
    val recMode by recModeFlow.collectAsState(initial = true)
    val hasEmbeddingsInLibrary = (embeddingsRowCount ?: 0) > 0
    val effectiveRecMode = recMode && hasEmbeddingsInLibrary

    LaunchedEffect(embeddingsRowCount) {
        val count = embeddingsRowCount ?: return@LaunchedEffect
        if (count == 0 && recMode) {
            container.preferences.setRecMode(false)
        }
    }

    // Phase 4 (2026-06-01): mostSimilar / forYou / becauseYouPlayed /
    // unexploredClusters / freezeMostSimilar state removed alongside the
    // Discover tab. NowPlayingScreen owns its own "Similar" state
    // (`similarToCurrent`) populated directly from Recommender.

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            container.toaster.show("Import cancelled")
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            importInProgress = true
            importMessage = "Copying file…"
            container.activityLog.log("engine", "embed_import", "Import started")
            container.embedding.logBuffer.append("import", "started from file picker")
            try {
                val result = EmbeddingsImport.importFromUri(ctx, uri)
                if (!result.ok) {
                    val err = result.error ?: "unknown error"
                    importMessage = "Import failed: $err"
                    container.activityLog.log(
                        "engine", "embed_import_failed", err,
                        level = ActivityLogEngine.Level.ERROR,
                    )
                    container.embedding.logBuffer.append("import", "FAILED copy: $err")
                    return@launch
                }
                importMessage = when (result.format) {
                    EmbeddingsImport.PortableFormat.IVZ ->
                        "Ingesting ${result.bytesCopied / 1024} KB — usually under a minute…"
                    EmbeddingsImport.PortableFormat.LEGACY_JSON ->
                        "Ingesting JSON ${result.bytesCopied / 1024} KB — may take 1–2 min…"
                    else -> "Ingesting…"
                }
                val reimport = container.embeddingDb.forceReimportEmbeddings()
                val rows = reimport?.optInt("rowCount", 0) ?: 0
                val fileSplits = reimport?.optInt("splitCount", 3) ?: 3
                val desiredSplits = container.preferences.getEmbeddingSplitCount()
                if (rows > 0) {
                    container.preferences.saveLastEmbedSplitCount(fileSplits)
                    libraryEmbedSplitCount = fileSplits
                }
                if (rows > 0 && fileSplits != desiredSplits) {
                    container.toaster.show(
                        "Backup used $fileSplits splits; settings use $desiredSplits — re-embed for best match",
                    )
                }
                val relink = container.embeddingDb.relinkLibraryPaths(songs)
                val relinked = relink?.optInt("relinked", 0) ?: 0
                val hashMap = container.embeddingDb.contentHashByFilepath()
                songs = container.embeddingDb.enrichSongsWithHashes(songs, hashMap)
                container.library.value = songs
                container.embeddingDb.clearVecHeapCache()
                container.embeddingDb.prewarmFromLibrary(songs)
                container.embeddingDb.fullWarmFromDb()
                container.recommendationCache.precomputeNow(reason = "import_done")
                refreshDbStats(container) { rc, dim, ext, fns, fps ->
                    embeddingsRowCount = rc; embeddingsDim = dim; vecExtLoaded = ext
                    embeddedFilenames = fns
                    embeddedFilepaths = fps
                }
                dupesRefreshTick++
                val doneMessage = when {
                    rows <= 0 -> "Import saved but no embeddings found in that file."
                    relinked > 0 -> "Loaded $rows embeddings. Matched $relinked songs."
                    else -> "Loaded $rows embeddings."
                }
                importMessage = doneMessage
                onboardingDismissed = true
                container.activityLog.log(
                    "engine", "embed_import_done", doneMessage,
                    data = mapOf("rows" to rows, "relinked" to relinked),
                )
                container.embedding.logBuffer.append("import", "done — $doneMessage")
            } catch (t: Throwable) {
                val err = t.message ?: t.javaClass.simpleName
                importMessage = "Import failed: $err"
                container.activityLog.log(
                    "engine", "embed_import_failed", err,
                    level = ActivityLogEngine.Level.ERROR,
                )
                container.embedding.logBuffer.append("import", "FAILED: $err")
                android.util.Log.e("MainActivity", "embeddings import failed", t)
            } finally {
                importInProgress = false
            }
        }
    }

    val exportSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        coroutineScope.launch {
            try {
                if (uri == null) {
                    exportBackupStatus = "Save cancelled"
                    container.toaster.show(
                        "Save cancelled — backup remains in app storage only"
                    )
                    return@launch
                }
                exportBackupStatus = "Writing to chosen location…"
                container.toaster.show("Saving backup…")
                val copy = EmbeddingsImport.copyBackupToUri(ctx, uri)
                if (copy.ok) {
                    val mb = copy.bytesCopied / 1024.0 / 1024.0
                    val msg = "Saved embeddings backup (${"%.1f".format(mb)} MB)"
                    exportBackupStatus = msg
                    container.toaster.show(msg)
                    container.embedding.logBuffer.append(
                        "backup",
                        "saved to user location: ${copy.bytesCopied} bytes",
                    )
                } else {
                    exportBackupStatus = "Save failed: ${copy.error ?: "unknown"}"
                    container.toaster.show("Save failed: ${copy.error ?: "unknown"}")
                    container.embedding.logBuffer.append("backup", "save to user location FAILED: ${copy.error}")
                }
            } finally {
                exportBackupInProgress = false
                // Keep status visible briefly so user sees result after picker closes
                kotlinx.coroutines.delay(2500)
                exportBackupStatus = null
            }
        }
    }

    LaunchedEffect(permission.granted) {
        if (permission.granted) {
            try {
                // Path A diagnostic — measure LibraryCache.loadOrScan timing.
                // This is the gate that holds back DiscoverCacheEngine
                // hydration (which requires songs.isNotEmpty() before it
                // can map cached filenames → Song objects). User reports
                // ~1-2s blank-Discover after Activity recreation; we want
                // to know if it's this call or something downstream.
                val libStartMs = System.currentTimeMillis()
                container.activityLog.log(
                    category = "engine",
                    type = "LIBRARY_LOAD_START",
                    message = "LibraryCache.loadOrScan start",
                    data = mapOf("permissionGranted" to permission.granted),
                )
                songs = LibraryCache.loadOrScan(ctx)
                if (songs.isEmpty()) {
                    songs = LibraryCache.loadOrScan(ctx, force = true)
                }
                // Bugfix 2026-06-01d: publish to container so the
                // process-lifetime RecommendationCache can see the
                // library even after the Activity is destroyed.
                container.library.value = songs
                val libElapsed = System.currentTimeMillis() - libStartMs
                container.activityLog.log(
                    category = "engine",
                    type = "LIBRARY_LOAD_DONE",
                    message = "LibraryCache.loadOrScan done in ${libElapsed}ms (songs=${songs.size})",
                    data = mapOf(
                        "songsCount" to songs.size,
                        "elapsedMs" to libElapsed,
                    ),
                )
                scanError = null
                coroutineScope.launch(Dispatchers.IO) {
                    try { ArtPrefetch.prefetch(ctx, songs, limit = 200) }
                    catch (t: Throwable) { android.util.Log.w("ArtPrefetch", "${t.message}") }
                }
            } catch (t: Throwable) { scanError = t.message ?: t.javaClass.simpleName }
        }
    }

    LaunchedEffect(permission.granted, songs.size) {
        if (permission.granted && songs.isNotEmpty() && embeddingsRowCount == null) {
            // Phase B (2026-06-01): hydrate embeddingsRowCount from the
            // DataStore cache IMMEDIATELY so AI Discover unblocks without
            // waiting for the DB worker. On every previous cold start we
            // saved the rowCount; reading it back is ~milliseconds, vs
            // ~18s for the first real stats() call (which pays the full
            // sqlite-vec/Room init cost).
            val cached = container.preferences.cachedEmbedStats()
            if (cached.rowCount > 0 && embeddingsRowCount == null) {
                embeddingsRowCount = cached.rowCount
                embeddingsDim = cached.dim
                vecExtLoaded = cached.vecExt
                container.activityLog.log(
                    category = "engine",
                    type = "PERF_DB_CACHE_HIT",
                    message = "hydrated rowCount=${cached.rowCount} from cache (UI unblocked optimistically)",
                    data = mapOf(
                        "rowCount" to cached.rowCount,
                        "dim" to cached.dim,
                        "vecExtLoaded" to cached.vecExt,
                    ),
                )
            }

            // Diagnostic — Discover load timing. Additive only.
            val dbStartMs = System.currentTimeMillis()
            container.activityLog.log(
                category = "engine",
                type = "PERF_DB_START",
                message = "refreshDbStats start (songs=${songs.size}, cachedHit=${cached.rowCount > 0})",
                data = mapOf("songsCount" to songs.size, "cachedHit" to (cached.rowCount > 0)),
            )
            // Phase B: stage 1 — quick stats-only fetch reconciles any drift
            // between the cached count and the live DB count.
            refreshEmbeddingRowCount(container) { rc, dim, ext ->
                embeddingsRowCount = rc
                embeddingsDim = dim
                vecExtLoaded = ext
            }
            // Bugfix 2026-06-01c: stage 2 (full refreshDbStats with
            // allFilenames + allFilepaths) is HEAVY — 3 sequential queries
            // on the single EmbeddingDbWorker thread, ~20s when cold.
            // It blocks the recommend pipeline behind it. The filename /
            // filepath sets are ONLY used by the AI management page; we
            // can lazy-load them when that page actually opens. Skip the
            // call here when cache hit; cold first launch (no cache) still
            // runs it to populate sets for the initial AI page render.
            if (cached.rowCount <= 0) {
                try {
                    container.embeddingDb.relinkLibraryPaths(songs)
                    val hashMap = container.embeddingDb.contentHashByFilepath()
                    songs = container.embeddingDb.enrichSongsWithHashes(songs, hashMap)
                    container.library.value = songs
                    container.embeddingDb.prewarmFromLibrary(songs)
                } catch (t: Throwable) {
                    android.util.Log.w("MainActivity", "startup relink failed: ${t.message}")
                }
                refreshDbStats(container) { rc, dim, ext, fns, fps ->
                    embeddingsRowCount = rc; embeddingsDim = dim; vecExtLoaded = ext
                    embeddedFilenames = fns
                    embeddedFilepaths = fps
                    val elapsed = System.currentTimeMillis() - dbStartMs
                    container.activityLog.log(
                        category = "engine",
                        type = "PERF_DB_DONE",
                        message = "refreshDbStats done in ${elapsed}ms (rows=$rc dim=$dim vecExt=$ext)",
                        data = mapOf(
                            "rowCount" to rc,
                            "dim" to dim,
                            "vecExtLoaded" to ext,
                            "embeddedFilenames" to fns.size,
                            "embeddedFilepaths" to fps.size,
                            "elapsedMs" to elapsed,
                        ),
                    )
                }
            } else {
                // Cached row count skipped the heavy filepath fetch — but
                // imported backups often have stale paths. Relink + load sets.
                try {
                    container.embeddingDb.relinkLibraryPaths(songs)
                    val hashMap = container.embeddingDb.contentHashByFilepath()
                    songs = container.embeddingDb.enrichSongsWithHashes(songs, hashMap)
                    container.library.value = songs
                    container.embeddingDb.prewarmFromLibrary(songs)
                } catch (t: Throwable) {
                    android.util.Log.w("MainActivity", "startup relink failed: ${t.message}")
                }
                refreshDbStats(container) { rc, dim, ext, fns, fps ->
                    embeddingsRowCount = rc; embeddingsDim = dim; vecExtLoaded = ext
                    embeddedFilenames = fns
                    embeddedFilepaths = fps
                    container.activityLog.log(
                        category = "engine",
                        type = "PERF_DB_DONE",
                        message = "refreshDbStats after cache-hit relink (rows=$rc filepaths=${fps.size})",
                        data = mapOf(
                            "rowCount" to rc,
                            "embeddedFilepaths" to fps.size,
                        ),
                    )
                }
                container.activityLog.log(
                    category = "engine",
                    type = "PERF_DB_STATS_DEFERRED",
                    message = "cache-hit startup: relinked paths then loaded filepath sets",
                    data = mapOf("cachedRowCount" to cached.rowCount),
                )
            }
            artCacheBytes = withContextIo { AlbumArtRepository.diskCacheBytes(ctx) }
        }
    }

    LaunchedEffect(Unit) {
        container.embedding.batchComplete.collect { ev ->
            android.util.Log.i(
                "MainActivity",
                "batchComplete: processed=${ev.processed} failed=${ev.failed} recoveredIntoDb=${ev.recoveredIntoDb} totalRows=${ev.totalRows}"
            )
            embeddingsRowCount = ev.totalRows
            libraryEmbedSplitCount = container.preferences.getLastEmbedSplitCount()
            refreshDbStats(container) { rc, dim, ext, fns, fps ->
                embeddingsRowCount = rc; embeddingsDim = dim; vecExtLoaded = ext
                embeddedFilenames = fns
                embeddedFilepaths = fps
                android.util.Log.i(
                    "MainActivity",
                    "embeddedFilenames refreshed: ${fns.size} entries (rowCount=$rc)"
                )
            }
        }
    }
    // Push #53: collect per-song completion events and add each
    // filepath to embeddedFilepaths optimistically. The AI page's
    // Pending stat is derived from filepath comparison, so it ticks
    // down by 1 as soon as each song finishes (was waiting for batch
    // end). Also add the filename to embeddedFilenames for any other
    // callers that still rely on it. The authoritative refresh on
    // batchComplete reconciles any drift.
    LaunchedEffect(Unit) {
        container.embedding.songComplete.collect { ev ->
            if (ev.filepath.isNotBlank()) embeddedFilepaths = embeddedFilepaths + ev.filepath
            if (ev.filename.isNotBlank()) embeddedFilenames = embeddedFilenames + ev.filename
        }
    }

    LaunchedEffect(songs) {
        if (songs.isEmpty()) return@LaunchedEffect
        // Engine state loads from DataStore asynchronously. Recovery writes
        // must wait for those loads; otherwise the load coroutine can replace
        // freshly-recovered Taste/History/Timeline state with stale disk state.
        container.taste.awaitReady()
        container.signalTimeline.awaitReady()
        container.history.awaitReady()
        container.activityLog.awaitReady()
        container.favorites.awaitReady()
        container.disliked.awaitReady()
        container.reconcileNotificationFavoriteStorage()
        container.reconcileCapacitorDislikedStorage()
        container.reconcileManualTasteFlags()
        logLedgerState(container, "cold_start_before_drain")
        // Push #65/#66/#74: ingest pending playback evidence BEFORE preparing
        // resume playback. Three sources, each covering a different failure
        // mode:
        //   * playback_transitions_history (SharedPreferences, written by
        //     Media3PlaybackService.rememberTransitionToPrefs on every
        //     auto-advance/skip): rolling buffer of every transition while
        //     the Activity was dead. Push #74 drains all entries above the
        //     watermark, fixing the "5 songs auto-advanced in background but
        //     only the last one was captured" bug.
        //   * DataStore (written by MainActivity.flushCurrentPlayback on
        //     onPause/onDestroy): tentative snapshot of the song that was
        //     playing when the Activity backgrounded.
        //   * SharedPreferences playback_pending_evidence (written by
        //     Media3PlaybackService.onTaskRemoved): tentative snapshot for
        //     the force-kill path that bypasses onPause.
        // Push #74 dedupe: when both snapshots reference the same
        // playbackInstanceId we pick the one with the higher playedMs and
        // ingest once, eliminating the historic
        // background_recovery_task_removed + background_recovery_datastore
        // duplicate pairs.
        try {
            val transitionsJson = com.isaivazhi.app.Media3PlaybackService
                .readRecentTransitionsStatic(ctx)
            val recentTransitions = parseRecentTransitions(transitionsJson)
            val liveInstanceId = com.isaivazhi.app.Media3PlaybackService.INSTANCE?.let {
                runCatching { it.javaClass.getMethod("getCurrentPlaybackInstanceId").invoke(it) as? Long }
                    .getOrNull() ?: 0L
            } ?: 0L

            // Push #76: one-time migration to undo the #74/#75 watermark
            // inflation bug. On those builds the watermark was bumped with
            // the NEW song's instId after every live transition, blocking
            // every subsequent buffer replay because buffer entries store
            // the PREV song's id (always <= the inflated watermark). Reset
            // the watermark once so the next drainTransitionsBuffer can
            // finally process the accumulated background auto-advance
            // entries the user has been losing.
            val migrationApplied = runCatching { container.preferences.runV76WatermarkResetIfNeeded() }
                .getOrDefault(false)
            if (migrationApplied) {
                android.util.Log.i("MainActivity", "v76 migration: watermark reset to 0 — allowing buffer replay")
                // Also sweep the legacy _task_removed + _datastore duplicate
                // pairs out of the Taste Signal timeline. Same migration
                // boundary — runs once, then never again.
                val removedDupes = runCatching { container.signalTimeline.cleanLegacyDuplicates() }
                    .getOrDefault(0)
                container.activityLog.log(
                    category = "engine",
                    type = "MIGRATION_V76",
                    message = "Watermark reset; cleaned $removedDupes legacy duplicate signals",
                    data = mapOf("legacyDuplicatesRemoved" to removedDupes),
                )
            }

            // Service-authored durable ledger is the primary recovery path.
            // It also reads the two legacy transition buffers and marks events
            // processed by stable eventId so cold-start replay is idempotent.
            container.playbackSignalProcessor.processPending(songs, reason = "cold_start", logWhenEmpty = true)
            logLedgerState(container, "cold_start_after_drain")

            // Push #74 Tier A: drain the transitions buffer first. Replays
            // every background auto-advance the Activity-scoped
            // PlaybackEngine missed. Updates the watermark in the same call.
            val watermark = drainTransitionsBuffer(container, songs, recentTransitions)

            // Push #74 Tier B: read both pending-evidence stores, dedupe by
            // playbackInstanceId, gate against the watermark. Reuses the
            // existing reconcilePending three-case logic (A/B/C) for each
            // survivor.
            val sp = ctx.getSharedPreferences("playback_pending_evidence", android.content.Context.MODE_PRIVATE)
            val dataStoreSnapshot = container.preferences.loadPendingEvidence()
            val svcMediaId = sp.getString("mediaId", "") ?: ""
            val svcPlayed = sp.getLong("playedMs", 0L)
            val svcDur = sp.getLong("durationMs", 0L)
            val svcInstId = sp.getLong("playbackInstanceId", 0L)
            val svcSnapshot = if (svcMediaId.isNotBlank() && svcPlayed >= 1000L && svcDur > 0L) {
                com.isaivazhi.app.engine.AppPreferences.PendingEvidence(
                    mediaId = svcMediaId,
                    playedMs = svcPlayed,
                    durationMs = svcDur,
                    playbackInstanceId = svcInstId,
                )
            } else null

            data class TaggedSnapshot(
                val snapshot: com.isaivazhi.app.engine.AppPreferences.PendingEvidence,
                val originLabel: String,
            )
            val toReconcile = mutableListOf<TaggedSnapshot>()
            when {
                dataStoreSnapshot != null && svcSnapshot != null &&
                    dataStoreSnapshot.playbackInstanceId == svcSnapshot.playbackInstanceId &&
                    dataStoreSnapshot.playbackInstanceId > 0L -> {
                    // Same playback session captured by BOTH onPause AND
                    // onTaskRemoved. Pick the one with the higher playedMs
                    // (truer accumulator). Tag with the winner's origin.
                    val winner = if (svcSnapshot.playedMs >= dataStoreSnapshot.playedMs) svcSnapshot else dataStoreSnapshot
                    val winnerOrigin = if (winner === svcSnapshot) "merged_task_removed" else "merged_datastore"
                    android.util.Log.i(
                        "MainActivity",
                        "pending evidence dedup: same instId=${winner.playbackInstanceId}, kept $winnerOrigin (datastorePlayed=${dataStoreSnapshot.playedMs} svcPlayed=${svcSnapshot.playedMs})"
                    )
                    toReconcile += TaggedSnapshot(winner, winnerOrigin)
                }
                else -> {
                    if (dataStoreSnapshot != null) toReconcile += TaggedSnapshot(dataStoreSnapshot, "datastore_only")
                    if (svcSnapshot != null) toReconcile += TaggedSnapshot(svcSnapshot, "task_removed_only")
                }
            }

            for (tagged in toReconcile) {
                if (tagged.snapshot.playbackInstanceId > 0L && tagged.snapshot.playbackInstanceId <= watermark) {
                    android.util.Log.i(
                        "MainActivity",
                        "pending evidence skipped (covered by buffer replay): origin=${tagged.originLabel} instId=${tagged.snapshot.playbackInstanceId} watermark=$watermark"
                    )
                    continue
                }
                reconcilePending(
                    container = container,
                    songs = songs,
                    snapshot = tagged.snapshot,
                    recentTransitions = recentTransitions,
                    liveInstanceId = liveInstanceId,
                    originLabel = tagged.originLabel,
                )
            }
            // Always clear both stores after handling, even when watermark
            // skipped them — the data is no longer needed.
            if (dataStoreSnapshot != null) container.preferences.clearPendingEvidence()
            if (svcSnapshot != null) sp.edit().clear().apply()
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "pending evidence reconciliation failed: ${t.message}")
        }
        try {
            // Push #71: BEFORE calling prepareForResume (which destructively
            // replaces Media3's queue), check whether the service is alive
            // and already has an active playback session. If yes, the
            // service's state is the source of truth — background headphone
            // skips, auto-advances, queue manipulations all happened on the
            // service while the Activity was destroyed. Calling
            // prepareForResume with the Activity's stale DataStore values
            // would CLOBBER the service's current track (the bug the user
            // reported: "Kanne Kanne restarted from middle after I used
            // headphone skip in background").
            val svc = com.isaivazhi.app.Media3PlaybackService.INSTANCE
            val svcMediaId = svc?.getCurrentMediaIdSnapshot() ?: ""
            val svcInstId = svc?.getCurrentPlaybackInstanceId() ?: 0L
            if (svc != null && svcMediaId.isNotBlank() && svcInstId > 0L) {
                val svcPos = svc.getCurrentPositionMsSnapshot()
                val snap = container.preferences.snapshot()
                android.util.Log.i(
                    "MainActivity",
                    "cold-start: service alive at mediaId=$svcMediaId instId=$svcInstId pos=${svcPos}ms — " +
                        "skipping prepareForResume (DataStore had mediaId=${snap.mediaId} pos=${snap.positionMs}ms)"
                )
                // Sync Activity state from the service's actual current
                // state. The MediaController listener will keep it
                // up-to-date going forward.
                container.playback.syncStateFromController(songs)
                return@LaunchedEffect
            }
            // Truly cold start: service is dead, no current session. Use
            // the DataStore snapshot to rebuild the queue and seek to the
            // saved position.
            val snap = container.preferences.snapshot()
            val mediaId = snap.mediaId ?: return@LaunchedEffect
            val queue = if (snap.queueFilenames.isNotEmpty()) {
                snap.queueFilenames.mapNotNull { fn -> songs.firstOrNull { it.filename == fn } }
            } else listOf(songs.firstOrNull { it.filename == mediaId } ?: return@LaunchedEffect)
            if (queue.isEmpty()) return@LaunchedEffect
            val resolvedIndex = queue.indexOfFirst { it.filename == mediaId }.coerceAtLeast(0)
            android.util.Log.i(
                "MainActivity",
                "cold-start: service NOT alive — rebuilding from DataStore mediaId=$mediaId pos=${snap.positionMs}ms idx=$resolvedIndex"
            )
            container.playback.prepareForResume(queue, resolvedIndex, snap.positionMs)
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "cold-start prepare failed: ${t.message}")
        }
    }

    // Phase 4 (2026-06-01): the standalone Most Similar LE and discoverQueue
    // LE have been deleted. The Similar-to-current row in NowPlayingScreen
    // owns its own debounced LaunchedEffect (see `similarToCurrent` below);
    // it calls Recommender.recommendScored on each song change. The
    // discoverQueue was only consumed by the deleted Discover page.

    // For You / Because You Played / Unexplored populate from the first
    // available data. We re-trigger on `qualifyingEventCount` (number of
    // Phase 4 (2026-06-01): the For You / BYP / Unexplored / Discover-cache
    // section state and their auto-refresh plumbing (forYouTick, rebuildPulse,
    // qualifyingEventCount, unexploredManualRefreshCounter, discoverCache
    // hydrate, pull-to-refresh) have all been removed alongside the Discover
    // tab. The Recommender still drives Up Next via `refreshUpcomingWithAI`
    // and the Similar-to-current row in NowPlayingScreen.

    // Push #68: profileVec cache. Loaded from disk on cold start for
    // instant blended-query availability; recomputed and persisted when
    // taste signals drift enough that the fingerprint changes.
    var cachedProfileVec by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(Unit) {
        runCatching { container.preferences.loadProfileVec() }.getOrNull()?.let {
            cachedProfileVec = it.vec
            android.util.Log.i("MainActivity", "loaded profileVec from disk dim=${it.vec.size} fp=${it.fingerprint}")
        }
    }
    // Recompute profileVec when historyStats changes substantially.
    // Key on total plays (not just size) so the centroid updates as
    // existing songs accumulate more listens, not only when new songs
    // appear for the first time. The 300ms debounce + fingerprint guard
    // below prevent unnecessary DB work on every single play event.
    val profileVecTotalPlays = remember(historyStats) { historyStats.values.sumOf { it.plays } }
    LaunchedEffect(profileVecTotalPlays, embeddingsRowCount) {
        if (songs.isEmpty()) return@LaunchedEffect
        if ((embeddingsRowCount ?: 0) == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        val byFilename = songs.associateBy { it.filename }
        val topPlays = historyStats.entries
            .asSequence()
            .filter { it.value.plays > 0 && it.value.avgFraction >= 0.3f }
            .filter { it.key !in dislikedSet }
            .filter { it.key !in upNextHardBlocked }
            .sortedByDescending { it.value.plays * it.value.avgFraction }
            .take(30)
            .toList()
        if (topPlays.isEmpty()) return@LaunchedEffect
        val anchorSongs = topPlays.mapNotNull { byFilename[it.key] }
            .filter { !it.contentHash.isNullOrBlank() && it.filePath in embeddedFilepaths }
        if (anchorSongs.isEmpty()) return@LaunchedEffect
        // Include rounded play/fraction weights so the centroid rebuilds
        // when existing top-30 songs accumulate significantly more listens,
        // not only when the set of top-30 filenames itself changes.
        val fingerprint = topPlays.take(30)
            .joinToString("|") { (fn, st) -> "$fn:${st.plays}:${"%.1f".format(st.avgFraction)}" }
            .hashCode().toString()
        val current = runCatching { container.preferences.loadProfileVec() }.getOrNull()
        if (current?.fingerprint == fingerprint && cachedProfileVec != null) return@LaunchedEffect
        // Build centroid.
        val hashes = anchorSongs.mapNotNull { it.contentHash }.distinct()
        val vecsByHash = runCatching { container.embeddingDb.getVecsByHashes(hashes) }.getOrDefault(emptyMap())
        if (vecsByHash.isEmpty()) return@LaunchedEffect
        val dim = vecsByHash.values.first().size
        val pv = FloatArray(dim)
        var totalWeight = 0f
        for (song in anchorSongs) {
            val v = vecsByHash[song.contentHash ?: continue] ?: continue
            val st = historyStats[song.filename] ?: continue
            val w = st.plays * st.avgFraction
            for (d in 0 until dim) pv[d] += v[d] * w
            totalWeight += w
        }
        if (totalWeight <= 0f) return@LaunchedEffect
        var n2 = 0f
        for (d in 0 until dim) { pv[d] /= totalWeight; n2 += pv[d] * pv[d] }
        val inv = if (n2 > 0f) 1f / kotlin.math.sqrt(n2) else 0f
        for (d in 0 until dim) pv[d] *= inv
        cachedProfileVec = pv
        runCatching { container.preferences.saveProfileVec(pv, fingerprint) }
        android.util.Log.i("MainActivity", "profileVec rebuilt dim=$dim anchors=${anchorSongs.size} fp=$fingerprint")
    }
    // Phase 4 (2026-06-01): the Discover-cache snapshot state, the For You +
    // BYP LE, the Unexplored LE, and the cache hydrate LE that lived here
    // have all been removed. The remaining Recommender helpers used by
    // refreshUpcomingWithAI run on demand only.

    // Phase 4 (2026-06-01): For You + BYP, Unexplored Clusters, and
    // Discover cache hydration LEs deleted with the Discover tab.

    val currentSongFilePath: String? = playbackState.currentMediaId?.let { mediaId ->
        songs.firstOrNull { it.filename == mediaId }?.filePath
    }

    // Push #45 perf: memoize the filename→Song lookup. Was rebuilt as
    // a 2500-entry HashMap on EVERY recomposition (transitions, scrolls,
    // state updates), contributing to the "Skipped 250 frames" jank
    // captured in logs.txt.
    val byFilenameLookup: Map<String, Song> = remember(songs) {
        songs.associateBy { it.filename }
    }
    val fullQueueSongs: List<Song> = remember(byFilenameLookup, playbackState.queueFilenames) {
        playbackState.queueFilenames.mapNotNull { byFilenameLookup[it] }
    }
    androidx.compose.runtime.LaunchedEffect(songs) {
        container.playback.resolveSongByFilename = { fn -> songs.firstOrNull { it.filename == fn } }
    }

    val isCurrentFavorite = playbackState.currentMediaId?.let { it in favoritesSet } ?: false
    val isCurrentDisliked = playbackState.currentMediaId?.let { it in dislikedSet } ?: false
    // Push #39 diagnostic: surfaces whether the heart/dislike chips on
    // mini + full player are tracking the correct state when a song
    // recurs. If `isCurrentFavorite` flips back to false for a song the
    // user previously favorited, this line will show either an empty
    // `favoritesSet` (StateFlow not propagating) or a mismatched
    // `currentMediaId` (file name not matching what was stored).
    androidx.compose.runtime.LaunchedEffect(playbackState.currentMediaId, favoritesSet, dislikedSet) {
        android.util.Log.i(
            "FavState",
            "currentMediaId=${playbackState.currentMediaId} isFav=$isCurrentFavorite isDis=$isCurrentDisliked " +
                "favoritesSize=${favoritesSet.size} dislikedSize=${dislikedSet.size} " +
                "favHas=${playbackState.currentMediaId?.let { it in favoritesSet }} " +
                "disHas=${playbackState.currentMediaId?.let { it in dislikedSet }}"
        )
    }

    /**
     * Phase E (2026-06-01): shared "refresh Up Next with AI" lambda used by
     * both the UpNext screen's Refresh button AND the new MiniPlayer /
     * lockscreen / NowPlaying Refresh buttons. Rebuilds the upcoming tail
     * while preserving any Play Next songs the user explicitly queued.
     * Capacitor parity for `_doRefresh('manual')`.
     */
    // Phase 1 (2026-06-01): Similar-to-current row for the NowPlaying
    // overlay. Replaces the old Discover page's "Most Similar" section.
    // Recomputed whenever the playing track changes; debounced 250 ms so
    // rapid skips don't thrash the embedding DB.
    var similarToCurrent by remember { mutableStateOf<List<Song>>(emptyList()) }
    var similarLoading by remember { mutableStateOf(false) }
    var similarFrozen by remember { mutableStateOf(false) }
    var similarSeedMediaId by remember { mutableStateOf<String?>(null) }
    val similarSeedTitle = similarSeedMediaId?.let { id ->
        songs.firstOrNull { it.filename == id }?.title?.takeIf { it.isNotBlank() } ?: id
    }
    val refreshInProgress by container.playback.refreshBusy.collectAsState()
    LaunchedEffect(playbackState.currentMediaId, embeddingsRowCount, songs.size, similarFrozen) {
        val mediaId = playbackState.currentMediaId
        if (mediaId == null || songs.isEmpty() || (embeddingsRowCount ?: 0) == 0) {
            similarToCurrent = emptyList()
            similarLoading = false
            return@LaunchedEffect
        }
        if (similarFrozen && similarSeedMediaId != null && mediaId != similarSeedMediaId) {
            return@LaunchedEffect
        }
        val current = songs.firstOrNull { it.filename == mediaId } ?: run {
            similarToCurrent = emptyList()
            similarLoading = false
            return@LaunchedEffect
        }
        similarLoading = true
        kotlinx.coroutines.delay(250)
        val results = runCatching {
            container.recommender.recommendScored(
                currentSong = current,
                library = songs,
                k = 10,
                adventurous = tuning.adventurous,
            )
        }.getOrDefault(emptyList())
        similarToCurrent = results.map { it.song }
        similarSeedMediaId = mediaId
        similarLoading = false
    }

    val queueAllSimilarAfterCurrent: () -> Unit = {
        val mediaId = playbackState.currentMediaId
        val toQueue = similarToCurrent.filter {
            it.filePath != null && it.filename != mediaId
        }
        if (toQueue.isEmpty()) {
            container.toaster.show("No similar songs to queue")
        } else {
            container.playback.playNextMany(toQueue)
            container.toaster.show("Added ${toQueue.size} similar songs after current")
        }
    }

    val refreshUpcomingWithAI: () -> Unit = refreshUpcomingWithAI@ {
        val mediaId = playbackState.currentMediaId ?: return@refreshUpcomingWithAI
        val current = songs.firstOrNull { it.filename == mediaId } ?: return@refreshUpcomingWithAI
        // Phase 2 (2026-06-01): re-entrancy guard + busy state. Flips the
        // Refresh icon to a spinner across MiniPlayer / NowPlaying /
        // lockscreen until the queue rebuild completes. Context-aware
        // pre-toast lets the user know when embeddings are still indexing
        // so the result reflects only what's ready so far.
        if (container.playback.refreshBusy.value) return@refreshUpcomingWithAI
        container.playback.setRefreshBusy(true)

        // Bugfix 2026-06-01d: cache-pop fast path. RecommendationCache
        // continuously precomputes a tail as the current song changes
        // (process-lifetime, so it works even when MainActivity has been
        // destroyed during a long lockscreen). If a tail is ready we
        // serve it instantly — no sqlite-vec mmap reload, no Recommender
        // call. Falls through to the live-compute path only on cache miss.
        val cachedTail = container.recommendationCache.popTail()
        if (cachedTail != null && cachedTail.isNotEmpty()) {
            val playNextSet = playbackState.playNextFilenames
            val byFn = songs.associateBy { it.filename }
            val playNextSongs = playNextSet.mapNotNull { byFn[it] }
            val policyFilteredTail = com.isaivazhi.app.engine.RecommendationPolicy.filterSongsForUpNext(
                cachedTail,
                upNextPolicyExcludes,
            )
            val finalUpcoming = playNextSongs + policyFilteredTail.filter {
                it.filename !in playNextSet && it.filename != mediaId
            }
            container.activityLog.log(
                category = "queue",
                type = "REFRESH_CACHE_HIT",
                message = "Served upcoming from RecommendationCache (size=${cachedTail.size}, afterPolicy=${policyFilteredTail.size})",
                data = mapOf(
                    "tailSize" to cachedTail.size,
                    "policyFilteredSize" to policyFilteredTail.size,
                    "mediaId" to mediaId,
                ),
            )
            container.toaster.show("Up Next refreshed")
            if (finalUpcoming.isNotEmpty()) container.playback.replaceUpcoming(
                newUpcoming = finalUpcoming,
                newContext = com.isaivazhi.app.engine.PlaybackEngine.QueueContext.AI_RECOMMENDED,
            )
            container.playback.setRefreshBusy(false)
            return@refreshUpcomingWithAI
        }

        val embStatus = container.embedding.status.value
        if (embStatus.inProgress && embStatus.total > 0) {
            container.toaster.show(
                "AI is still indexing (${embStatus.processed}/${embStatus.total}) — " +
                    "refresh uses what's ready."
            )
        }
        // Bugfix 2026-06-01b: dispatch on Dispatchers.Default, NOT the
        // default Compose rememberCoroutineScope dispatcher
        // (AndroidUiDispatcher.Main). That dispatcher runs via Choreographer
        // frame callbacks; while the activity is STOPPED (screen locked) no
        // frames tick, so a launch body queued there does not execute until
        // the user unlocks. Lockscreen Refresh taps were appearing to take
        // 10s–60s because the recommend work was deferred until unlock.
        // Running on Dispatchers.Default decouples the recommend pipeline
        // from the UI lifecycle.
        val dispatchStartedAt = android.os.SystemClock.elapsedRealtime()
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val dispatchLatencyMs = android.os.SystemClock.elapsedRealtime() - dispatchStartedAt
            container.activityLog.log(
                category = "queue",
                type = "REFRESH_DISPATCH_START",
                message = "refreshUpcomingWithAI body dispatched",
                data = mapOf(
                    "dispatchLatencyMs" to dispatchLatencyMs,
                    "mediaId" to mediaId,
                ),
            )
            val recommendStartedAt = android.os.SystemClock.elapsedRealtime()
            try {
            val playNextSet = playbackState.playNextFilenames
            val byFn = songs.associateBy { it.filename }
            val playNextSongs = playNextSet.mapNotNull { byFn[it] }
            val excludeFns = playNextSet + mediaId
            val blendedTriple = runCatching {
                container.recommender.buildBlendedVec(
                    currentSongHash = current.contentHash,
                    sessionListened = container.session.listened.value,
                    profileVec = cachedProfileVec,
                    library = songs,
                    mode = "refresh",
                    sessionBias = tuning.sessionBias,
                )
            }.getOrNull()
            blendedTriple?.let { (_, w, label) -> lastBlendInfo = LastBlendInfo(w, label) }
            val blendedVec = blendedTriple?.first
            val newTail: List<Song> = if (recMode && (embeddingsRowCount ?: 0) > 0) {
                runCatching {
                    container.recommender.recommendUpcoming(
                        current, songs, k = 50,
                        adventurous = tuning.adventurous,
                        extraExcludeFilenames = excludeFns,
                        hardBlockedFilenames = upNextHardBlocked,
                        dislikedFilenames = dislikedSet,
                        softExcludedFilenames = upNextSoftExcluded,
                        blendedQueryVec = blendedVec,
                    )
                }.getOrDefault(emptyList())
            } else {
                songs.filter { it.filePath != null && it.filename !in excludeFns }
                    .shuffled().take(50)
            }
            val recommendElapsedMs = android.os.SystemClock.elapsedRealtime() - recommendStartedAt
            container.activityLog.log(
                category = "queue",
                type = "REFRESH_RECOMMEND_DONE",
                message = "recommendUpcoming returned ${newTail.size} tracks",
                data = mapOf(
                    "elapsedMs" to recommendElapsedMs,
                    "tailSize" to newTail.size,
                    "blend" to (blendedTriple?.third ?: "n/a"),
                ),
            )
            container.toaster.show("Up Next refreshed (blend=${blendedTriple?.third ?: "current"})")
            val finalUpcoming = playNextSongs + newTail
            android.util.Log.i(
                "QueueOp",
                "Refresh: blend=${blendedTriple?.third ?: "n/a"} preserved ${playNextSongs.size} Play Next + ${newTail.size} AI"
            )
            val replaceStartedAt = android.os.SystemClock.elapsedRealtime()
            if (finalUpcoming.isNotEmpty()) container.playback.replaceUpcoming(
                newUpcoming = finalUpcoming,
                newContext = com.isaivazhi.app.engine.PlaybackEngine.QueueContext.AI_RECOMMENDED,
            )
            container.activityLog.log(
                category = "queue",
                type = "REFRESH_REPLACE_DONE",
                message = "replaceUpcoming done",
                data = mapOf(
                    "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - replaceStartedAt),
                    "finalUpcomingSize" to finalUpcoming.size,
                ),
            )
            } finally {
                container.playback.setRefreshBusy(false)
            }
        }
    }

    // Phase E (2026-06-01): bridge the lockscreen Refresh button into the
    // shared lambda above. PlaybackEngine emits on refreshRequests when the
    // service forwards EVT_MEDIA_ACTION action="refresh_queue" from the
    // notification CommandButton tap. We keep all recommender state in this
    // UI process; the service is intentionally state-free for the AI side.
    LaunchedEffect(Unit) {
        container.playback.refreshRequests.collect {
            container.activityLog.log(
                category = "notification",
                type = "LOCKSCREEN_REFRESH_TAP",
                message = "Lockscreen/notification Refresh tap received by UI",
            )
            refreshUpcomingWithAI()
        }
    }

    /**
     * Auto-build queue helper for single-song taps. Mirrors the Capacitor
     * app's `_doRefresh('manual')` — tapping a card plays that song AND
     * fills Up Next with 50 AI-blended recommendations (or shuffled tail
     * when AI mode is off / embeddings missing). Fixes the empty-Up-Next
     * + unresponsive-Next problem reported after push #33.
     */
    val playFromTap: (Song) -> Unit = { song ->
        // Push #39: 2-phase tap-to-audio to kill the visible latency
        // between the tap and audio. Phase 1 (synchronous, Main thread):
        // start playing the tapped song RIGHT NOW with a 1-item queue —
        // Media3 has the source prepared in < 100 ms after pre-warm. The
        // user hears audio before the recommender even starts. Phase 2
        // (background IO): build the AI tail and append via
        // `replaceUpcoming` so Up Next fills in over the next ~200-500 ms.
        // Capacitor parity for `_doRefresh('manual')` 2-phase behavior.
        //
        // Push #70: this is the "Songs-tab tap" path — single song + AI
        // tail. Context = LIBRARY so the queue-exhaust LE knows it's
        // allowed to keep appending fresh recs if the queue runs low.
        android.util.Log.i("CardTap", "playFromTap song=${song.filename} → LIBRARY single-song + AI tail")
        container.playback.clearPlayNextMarker(song.filename)
        // Phase D (2026-06-01): force LOOP=OFF on AI-eligible queue starts
        // so the queue-exhaust LE can append fresh recommendations. Without
        // this, a user who once enabled REPEAT_ALL/ONE silently loops the
        // same ~10 songs forever — the AI never blends in.
        container.playback.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_OFF)
        container.playback.playQueue(
            songs = listOf(song),
            startIndex = 0,
            source = "manual_tap",
            queueContext = com.isaivazhi.app.engine.PlaybackEngine.QueueContext.LIBRARY,
        )
        // Prefetch the now-playing art in parallel with audio start, so
        // the MiniPlayer's ArtThumbnail finds it in the LRU on the first
        // recomposition after currentMediaId updates. Without this, the
        // user sees a 200-1000 ms placeholder while the LaunchedEffect
        // dispatches and decodes. Push #39 fix for the "1-2 second art
        // load after tap" gap.
        song.filePath?.let { path ->
            coroutineScope.launch(Dispatchers.IO) {
                runCatching { AlbumArtRepository.load(ctx, path, sampleSize = 4) }
            }
        }
        coroutineScope.launch(Dispatchers.IO) {
            val tapStart = android.os.SystemClock.elapsedRealtime()
            val hasEmbeddings = (embeddingsRowCount ?: 0) > 0
            // Push #72: Phase-2 AI tail should respect the session+profile
            // blend, not just the tapped song's neighbors. Capacitor parity.
            val blendedTriple = runCatching {
                container.recommender.buildBlendedVec(
                    currentSongHash = song.contentHash,
                    sessionListened = container.session.listened.value,
                    profileVec = cachedProfileVec,
                    library = songs,
                    mode = "play",
                    sessionBias = tuning.sessionBias,
                )
            }.getOrNull()
            blendedTriple?.let { (_, w, label) -> lastBlendInfo = LastBlendInfo(w, label) }
            val blendedVec = blendedTriple?.first
            val tail: List<Song> = if (recMode && hasEmbeddings) {
                runCatching {
                    container.recommender.buildPlayQueue(
                        currentSong = song,
                        library = songs,
                        k = 50,
                        adventurous = tuning.adventurous,
                        hardBlockedFilenames = upNextHardBlocked,
                        dislikedFilenames = dislikedSet,
                        softExcludedFilenames = upNextSoftExcluded,
                        blendedQueryVec = blendedVec,
                    ).drop(1)   // drop the current song; replaceUpcoming dedupes anyway
                }.getOrElse {
                    songs.asSequence()
                        .filter { it.filePath != null && it.filename != song.filename }
                        .shuffled().take(50).toList()
                }
            } else {
                songs.asSequence()
                    .filter { it.filePath != null && it.filename != song.filename }
                    .shuffled().take(50).toList()
            }
            val buildMs = android.os.SystemClock.elapsedRealtime() - tapStart
            android.util.Log.i(
                "PlayFromTap",
                "tail built in ${buildMs}ms (aiMode=$recMode emb=$hasEmbeddings tailSize=${tail.size}) for ${song.filename}"
            )
            container.playback.replaceUpcoming(tail)
        }
        Unit
    }

    // Push #42 Tier 2E-F: section-aware tap handler. DiscoverSection /
    // Album / BrowseList taps build a queue from [tappedIndex..last] of
    // the visible section (or the whole section shuffled when shuffle is
    // on). Library-recommendation-mode flag routes back to playFromTap
    // for Songs-tab taps. Recommender appends a tail when the queue
    // exhausts (Tier 2G LaunchedEffect below).
    /**
     * Push #70: section-tap handler. The `queueContext` parameter drives
     * the queue-exhaust LE's "should I append AI?" decision.
     *
     *   QueueContext.DISCOVER_SECTION  — Most Similar / For You / BYP /
     *                                    Unexplored. AI tail appends on
     *                                    queue exhaust if LOOP=OFF.
     *   QueueContext.ALBUM             — Album-track tap. No AI ever.
     *                                    LOOP=OFF stops at end.
     *   QueueContext.BROWSE_SECTION    — View-All / Favorites / Playlist /
     *                                    Search results. No AI ever.
     *
     * Library single-song mode is handled by [playFromTap] directly,
     * not via this path.
     */
    val playFromSection: (
        sectionSongs: List<Song>,
        tappedIndex: Int,
        queueContext: com.isaivazhi.app.engine.PlaybackEngine.QueueContext,
        sectionLabel: String,
    ) -> Unit = playFromSection@ { sectionSongs, tappedIndex, queueContext, sectionLabel ->
        val playable = sectionSongs.filter { it.filePath != null }
        val tappedSong = playable.getOrNull(tappedIndex.coerceIn(0, (playable.size - 1).coerceAtLeast(0)))
            ?: return@playFromSection
        val shuffleOn = playbackState.shuffleEnabled
        val queueSongs: List<Song> = if (shuffleOn) {
            playable.shuffled()
        } else {
            playable.subList(tappedIndex.coerceIn(0, playable.size - 1), playable.size)
        }
        val startIdx = if (shuffleOn) queueSongs.indexOf(tappedSong).coerceAtLeast(0) else 0
        val source = when (queueContext) {
            com.isaivazhi.app.engine.PlaybackEngine.QueueContext.ALBUM -> "album"
            com.isaivazhi.app.engine.PlaybackEngine.QueueContext.BROWSE_SECTION -> "browse_section"
            com.isaivazhi.app.engine.PlaybackEngine.QueueContext.DISCOVER_SECTION -> "discover_section"
            else -> "manual_tap"
        }
        android.util.Log.i(
            "CardTap",
            "playFromSection label=$sectionLabel ctx=$queueContext tapped=${tappedSong.filename} " +
                "idx=$tappedIndex sectionSize=${playable.size} shuffleOn=$shuffleOn"
        )
        // Phase D (2026-06-01): force LOOP=OFF when starting an AI-eligible
        // queue context, so the queue-exhaust LE can append fresh AI tail.
        // Album / Browse contexts keep whatever loop mode the user chose
        // — those are finite by design.
        if (queueContext == com.isaivazhi.app.engine.PlaybackEngine.QueueContext.DISCOVER_SECTION ||
            queueContext == com.isaivazhi.app.engine.PlaybackEngine.QueueContext.LIBRARY ||
            queueContext == com.isaivazhi.app.engine.PlaybackEngine.QueueContext.AI_RECOMMENDED
        ) {
            container.playback.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_OFF)
        }
        container.playback.playQueue(
            songs = queueSongs,
            startIndex = startIdx,
            source = source,
            queueContext = queueContext,
        )
        tappedSong.filePath?.let { path ->
            coroutineScope.launch(Dispatchers.IO) {
                runCatching { AlbumArtRepository.load(ctx, path, sampleSize = 4) }
            }
        }
    }

    // Push #42 Tier 1D: REMOVED — the LaunchedEffect that rebuilt the
    // active playback queue on every tuning slider release. That was the
    // "Up Next changes when I'm not asking it to" bug — moving the
    // Adventurous slider to look at Discover replaced the user's queue.
    // New rule: tuning affects Discover sections + the recommender's
    // NEXT build (next tap, next pull-to-refresh, queue-exhaust append).
    // Never an already-active queue.

    // Push #67: soft-refresh of the Up Next tail is active again (see
    // LaunchedEffect on sessionListened below). Push #45 had removed an
    // older, overly aggressive version that ran on every transition;
    // the current one debounces, requires 2+ session listens, and only
    // runs when the upcoming queue has more than 5 tracks. Explicit
    // Refresh still replaces the tail on demand.

    // Push #42 Tier 2G: queue-exhaust recommender append. When the
    // current song is the LAST one in the queue AND repeat mode is OFF
    // (REPEAT_ALL loops the section; REPEAT_ONE loops the song), append
    // 50 AI recommendations as a tail so playback continues seamlessly
    // after the section ends. Capacitor parity: `_doRefresh('queue_exhausted')`.
    LaunchedEffect(playbackState.currentMediaId, playbackState.queueFilenames.size, playbackState.queueContext) {
        val mediaId = playbackState.currentMediaId ?: return@LaunchedEffect
        val queueSize = playbackState.queueFilenames.size
        if (queueSize == 0) return@LaunchedEffect
        // Only fire when we're on the LAST item of the queue.
        val curIdx = playbackState.queueIndex
        if (curIdx < queueSize - 1) {
            container.activityLog.log(
                category = "engine",
                type = "QUEUE_EXHAUST_SKIP",
                message = "not on last item (curIdx=$curIdx of $queueSize, ctx=${playbackState.queueContext})",
                data = mapOf("reason" to "not_last", "curIdx" to curIdx, "queueSize" to queueSize),
            )
            return@LaunchedEffect
        }
        // Push #70: queueContext drives the AI-append decision before loop mode.
        //   ALBUM / BROWSE_SECTION → never append AI. Section is finite.
        //   LIBRARY / DISCOVER_SECTION / AI_RECOMMENDED → append AI when loop=OFF.
        val ctx = playbackState.queueContext
        if (ctx == com.isaivazhi.app.engine.PlaybackEngine.QueueContext.ALBUM ||
            ctx == com.isaivazhi.app.engine.PlaybackEngine.QueueContext.BROWSE_SECTION
        ) {
            container.activityLog.log(
                category = "engine",
                type = "QUEUE_EXHAUST_SKIP",
                message = "section context $ctx (no AI ever)",
                data = mapOf("reason" to "finite_section", "ctx" to ctx.name),
            )
            return@LaunchedEffect
        }
        // Respect repeat modes: ALL loops the section; ONE loops the song.
        // Both = no append. Only OFF gets the recommender tail.
        if (playbackState.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) {
            container.activityLog.log(
                category = "engine",
                type = "QUEUE_EXHAUST_SKIP",
                message = "repeat=${playbackState.repeatMode} (OFF=0 ONE=1 ALL=2)",
                data = mapOf("reason" to "loop_on", "repeatMode" to playbackState.repeatMode),
            )
            return@LaunchedEffect
        }
        val current = songs.firstOrNull { it.filename == mediaId } ?: return@LaunchedEffect
        if ((embeddingsRowCount ?: 0) == 0) {
            container.activityLog.log(
                category = "engine",
                type = "QUEUE_EXHAUST_SKIP",
                message = "no embeddings (rowCount=0)",
                data = mapOf("reason" to "no_embeddings"),
            )
            return@LaunchedEffect
        }
        // Debounce + check that we're still on the last item after the delay
        // (user may have already tapped Next to a different queue).
        kotlinx.coroutines.delay(500)
        if (playbackState.currentMediaId != mediaId) return@LaunchedEffect
        if (playbackState.queueIndex < playbackState.queueFilenames.size - 1) return@LaunchedEffect
        android.util.Log.i(
            "QueueExhaust",
            "appending AI tail: ctx=$ctx queueSize=$queueSize mediaId=$mediaId"
        )
        val excludeFns = playbackState.queueFilenames.toSet()
        // Push #67: build a blended query vector that mixes the current
        // song with the session-listened rolling window and the long-
        // term profile centroid. This is the recommender's view of
        // "what should come next" instead of pure neighbors-of-current.
        val blendedTriple = runCatching {
            container.recommender.buildBlendedVec(
                currentSongHash = current.contentHash,
                sessionListened = container.session.listened.value,
                profileVec = cachedProfileVec,  // Push #68: persisted centroid.
                library = songs,
                mode = "play",
                sessionBias = tuning.sessionBias,
            )
        }.getOrNull()
        blendedTriple?.let { (_, w, label) -> lastBlendInfo = LastBlendInfo(w, label) }
        val blendedVec = blendedTriple?.first
        val tail = runCatching {
            container.recommender.recommendUpcoming(
                currentSong = current,
                library = songs,
                k = 50,
                adventurous = tuning.adventurous,
                extraExcludeFilenames = excludeFns,
                hardBlockedFilenames = upNextHardBlocked,
                dislikedFilenames = dislikedSet,
                softExcludedFilenames = upNextSoftExcluded,
                blendedQueryVec = blendedVec,
            )
        }.getOrDefault(emptyList())
        if (tail.isNotEmpty()) {
            container.playback.appendToQueue(tail)
            container.toaster.show("Up Next refreshed with recommendations (${blendedTriple?.third ?: "current"})")
        }
    }

    // Push #67: SOFT-REFRESH UPCOMING TAIL after a qualified listen.
    // Capacitor parity (`_softRefreshQueue`): when the session vector
    // changes (new evidence accumulated), re-rank the queue's tail —
    // frozen [0..4] untouched, stable [5..14] re-sorted by new blended
    // similarity, fluid [15+] replaced with fresh recommendations. Keyed
    // on session.listened size so it fires after each new listen.
    val sessionListened by container.session.listened.collectAsState()
    LaunchedEffect(sessionListened.size, playbackState.currentMediaId) {
        if (songs.isEmpty()) return@LaunchedEffect
        val mediaId = playbackState.currentMediaId ?: return@LaunchedEffect
        val current = songs.firstOrNull { it.filename == mediaId } ?: return@LaunchedEffect
        if ((embeddingsRowCount ?: 0) == 0) return@LaunchedEffect
        // Never soft-refresh finite sections (album / browse). The queue-
        // exhaust LE already guards these; the soft-refresh must mirror that
        // rule or it will silently re-sort and replace album tracks.
        val ctx = playbackState.queueContext
        if (ctx == com.isaivazhi.app.engine.PlaybackEngine.QueueContext.ALBUM ||
            ctx == com.isaivazhi.app.engine.PlaybackEngine.QueueContext.BROWSE_SECTION
        ) return@LaunchedEffect
        // Respect the AI / Shuffle toggle — soft-refresh is AI-only.
        if (!recMode) return@LaunchedEffect
        // Debounce so rapid-fire transitions don't thrash the recommender.
        kotlinx.coroutines.delay(750)
        // Verify state didn't change during the debounce.
        if (playbackState.currentMediaId != mediaId) return@LaunchedEffect
        if (sessionListened.size < 2) return@LaunchedEffect  // need some session evidence first
        val upcomingFilenames = playbackState.queueFilenames
            .drop(playbackState.queueIndex + 1)
        if (upcomingFilenames.size <= 5) return@LaunchedEffect  // too short to bother
        val byFn = songs.associateBy { it.filename }
        val upcomingSongs = upcomingFilenames.mapNotNull { byFn[it] }
        val blendedTriple = runCatching {
            container.recommender.buildBlendedVec(
                currentSongHash = current.contentHash,
                sessionListened = sessionListened,
                profileVec = cachedProfileVec,  // Push #68
                library = songs,
                mode = "play",
                sessionBias = tuning.sessionBias,
            )
        }.getOrNull()
        blendedTriple?.let { (_, w, label) -> lastBlendInfo = LastBlendInfo(w, label) }
        val blendedVec = blendedTriple?.first ?: return@LaunchedEffect
        val newTail = runCatching {
            container.recommender.softRefreshUpcomingTail(
                currentSong = current,
                upcoming = upcomingSongs,
                library = songs,
                blendedQueryVec = blendedVec,
                adventurous = tuning.adventurous,
                extraExcludeFilenames = upcomingFilenames.toSet(),
                hardBlockedFilenames = upNextHardBlocked,
                dislikedFilenames = dislikedSet,
                softExcludedFilenames = upNextSoftExcluded,
            )
        }.getOrDefault(emptyList())
        if (newTail.isNotEmpty() && newTail != upcomingSongs) {
            container.playback.replaceUpcoming(newTail)
            android.util.Log.i(
                "MainActivity",
                "soft-refresh applied to upcoming tail: size=${newTail.size}"
            )
        }
    }
    val isMenuSongFavorite = menuSong?.filename?.let { it in favoritesSet } ?: false
    val isMenuSongDisliked = menuSong?.filename?.let { it in dislikedSet } ?: false
    // Push #59: filepath-based embedding membership check (was filename).
    val isMenuSongEmbedded = com.isaivazhi.app.ui.songHasEmbedding(
        filePath = menuSong?.filePath,
        embeddingsRowCount = embeddingsRowCount,
        embeddedFilepaths = embeddedFilepaths,
    )
    val menuSongStats = menuSong?.filename?.let { historyStats[it] }
    val menuSongTasteSignal = menuSong?.filename?.let { tasteSignals[it] }

    BackHandler(enabled = overlay !is Overlay.None) {
        overlay = when (overlay) {
            is Overlay.PlaylistDetail -> Overlay.Playlists
            else -> Overlay.None
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // Push #39: disable Scaffold's automatic system-bar inset
        // reservation. We handle insets manually inside the topBar
        // (`statusBarsPadding`) and bottomBar (`navigationBarsPadding`).
        // The default (`WindowInsets.systemBars`) double-counted, leaving
        // a ~25 dp band below the topBar text AND a ~30 dp band above
        // the MiniPlayer — the symmetric "padding above and below" the
        // user saw.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            if (permission.granted && songs.isNotEmpty() && overlay is Overlay.None) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Push #39: was `systemBarsPadding()` which adds
                        // insets on ALL four sides — including the bottom
                        // navigation-bar inset (~30 dp) which had nothing
                        // to do with a top bar and just created a blank
                        // band between the "Discover" text and the page
                        // content below.
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = Tab.entries[pagerState.currentPage].title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { overlay = Overlay.Search }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    Box {
                        IconButton(onClick = { libraryMenuOpen = true }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Library menu", tint = MaterialTheme.colorScheme.onBackground)
                        }
                        DropdownMenu(expanded = libraryMenuOpen, onDismissRequest = { libraryMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Taste") },
                                leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                                onClick = { libraryMenuOpen = false; overlay = Overlay.Taste })
                            DropdownMenuItem(text = { Text("AI & Library") },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                                onClick = { libraryMenuOpen = false; overlay = Overlay.Ai })
                            DropdownMenuItem(text = { Text("Diagnostics") },
                                leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                                onClick = { libraryMenuOpen = false; overlay = Overlay.Logs() })
                            DropdownMenuItem(text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = { libraryMenuOpen = false; overlay = Overlay.Settings })
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (overlay is Overlay.None) {
                // Push #39: was `systemBarsPadding()` which adds insets on
                // ALL four sides — including the status-bar inset on top,
                // creating a ~25 dp empty band above the MiniPlayer. The
                // topBar already handles the status bar; the bottom bar
                // only needs the navigation-bar inset.
                Column(modifier = Modifier.navigationBarsPadding()) {
                    MiniPlayer(
                        state = playbackState,
                        positionMs = livePositionMs,
                        durationMs = liveDurationMs,
                        currentSongFilePath = currentSongFilePath,
                        isFavorite = isCurrentFavorite,
                        isDisliked = isCurrentDisliked,
                        aiMode = effectiveRecMode,
                        hasEmbeddingsInLibrary = hasEmbeddingsInLibrary,
                        hasEmbedding = com.isaivazhi.app.ui.songHasEmbedding(
                            filePath = currentSongFilePath,
                            embeddingsRowCount = embeddingsRowCount,
                            embeddedFilepaths = embeddedFilepaths,
                        ),
                        onToggleFavorite = {
                            playbackState.currentMediaId?.let { fn ->
                                val wasFav = fn in favoritesSet
                                val wasDis = fn in dislikedSet
                                container.favorites.toggle(fn)
                                if (!wasFav && wasDis) container.disliked.remove(fn)
                                container.toaster.show(
                                    when {
                                        wasFav -> "Removed from favorites"
                                        wasDis -> "Added to favorites (removed dislike)"
                                        else -> "Added to favorites"
                                    }
                                )
                            }
                        },
                        onToggleDislike = {
                            playbackState.currentMediaId?.let { fn ->
                                val wasFav = fn in favoritesSet
                                val nowDisliked = container.disliked.toggle(fn)
                                if (nowDisliked && wasFav) container.favorites.remove(fn)
                                container.toaster.show(
                                    when {
                                        !nowDisliked -> "Removed dislike"
                                        wasFav -> "Disliked (removed favorite)"
                                        else -> "Disliked"
                                    }
                                )
                            }
                        },
                        onExpand = { overlay = Overlay.FullPlayer },
                        onTogglePause = { container.playback.togglePause() },
                        onSkipPrev = { container.playback.skipPrev() },
                        onSkipNext = { container.playback.skipNext(neutral = true) },
                        onCycleRepeat = { container.playback.cycleRepeat() },
                        onToggleShuffle = { container.playback.toggleShuffle() },
                        onSeek = { container.playback.seekTo(it) },
                        onRefresh = refreshUpcomingWithAI,
                        refreshEnabled = playbackState.currentMediaId != null,
                        refreshInProgress = refreshInProgress,
                    )
                    NavigationBar {
                        Tab.entries.forEachIndexed { idx, tab ->
                            NavigationBarItem(
                                selected = pagerState.currentPage == idx,
                                onClick = {
                                    coroutineScope.launch { pagerState.animateScrollToPage(idx) }
                                },
                                icon = {
                                    Icon(
                                        imageVector = when (tab) {
                                            Tab.Songs -> Icons.Filled.LibraryMusic
                                            Tab.Albums -> Icons.Filled.Album
                                            Tab.UpNext -> Icons.Filled.QueueMusic
                                            Tab.Browse -> Icons.Filled.Apps
                                        },
                                        contentDescription = tab.title,
                                    )
                                },
                                label = { Text(tab.title) },
                            )
                        }
                    }
                }
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            val showOnboarding = permission.granted &&
                songs.isNotEmpty() &&
                embeddingsRowCount == 0 &&
                !onboardingDismissed
            when {
                !permission.granted -> PermissionGateUi(
                    onRequest = permission.request,
                    onOpenSettings = {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        ).setData(android.net.Uri.parse("package:${ctx.packageName}"))
                        runCatching { ctx.startActivity(intent) }
                    },
                )
                // Push #55: explicit notification permission step. Falls
                // between audio permission and onboarding so the user has
                // already committed to "yes, I'm setting this app up"
                // before we ask. Dismissable per-session — user isn't
                // blocked from using the app, but they'll see the prompt
                // again on next launch until granted (or denied via Settings).
                !notificationsPermission.granted && !notifGateDismissed ->
                    NotificationsPermissionGateUi(
                        onRequest = notificationsPermission.request,
                        onOpenSettings = {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            ).putExtra(
                                android.provider.Settings.EXTRA_APP_PACKAGE,
                                ctx.packageName
                            )
                            runCatching { ctx.startActivity(intent) }
                        },
                        onSkip = { notifGateDismissed = true },
                    )
                scanError != null -> ErrorPanel(scanError!!)
                showOnboarding -> Column(modifier = Modifier.fillMaxSize()) {
                    OnboardingScreen(
                        songCount = songs.size,
                        estimatedEmbedTimeSec = estimateEmbedTime(songs.size, embeddingStatus.activeBackend),
                        importInProgress = importInProgress,
                        importStatus = importMessage,
                        onImportEmbeddings = {
                            if (!importInProgress) {
                                importLauncher.launch(arrayOf("application/json", "*/*"))
                            }
                        },
                        onEmbedInBackground = {
                            container.embedding.embedSongs(songs); onboardingDismissed = true
                        },
                        onContinueWithoutEmbeddings = {
                            onboardingDismissed = true
                            coroutineScope.launch { container.preferences.setRecMode(false) }
                        },
                    )
                }
                else -> HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // Push #45 perf: pre-compose ±1 tab so swiping shows
                    // already-laid-out content instead of paying first-
                    // frame cost mid-gesture.
                    beyondViewportPageCount = 1,
                ) { page ->
                    when (Tab.entries[page]) {
                        Tab.Songs -> SongsScreen(
                            songs = songs,
                            currentMediaId = playbackState.currentMediaId,
                            permissionGranted = permission.granted,
                            embeddedFilepaths = embeddedFilepaths,
                            embeddingsRowCount = embeddingsRowCount,
                            // Push #37: tap a song from Songs tab → same
                            // behaviour as Discover card (plays this song +
                            // AI builds 50 recommended songs behind it).
                            // Previous behaviour queued the entire linear
                            // library which felt non-AI.
                            onSongTap = { song -> playFromTap(song) },
                            onSongLongPress = { song ->
                                menuPlaylistContext = null
                                // Push #42 Tier 2I: remember the song's
                                // index in the Songs library so the menu's
                                // "Play in order" entry can play from
                                // that position.
                                menuSongsTabIndex = songs.indexOfFirst { it.filename == song.filename }
                                    .takeIf { it >= 0 }
                                menuSong = song
                            },
                            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                        )
                        Tab.Albums -> AlbumsScreen(
                            songs = songs,
                            currentMediaId = playbackState.currentMediaId,
                            embeddedFilepaths = embeddedFilepaths,
                            embeddingsRowCount = embeddingsRowCount,
                            initialExpandedAlbum = albumToExpand,
                            revealRequestId = albumRevealRequestId,
                            // Push #42 Tier 2F: Album tap = SECTION tap. Queue
                            // becomes [tappedTrack..lastTrack] of the album (or
                            // all tracks shuffled if shuffle is on). Recommender
                            // appends a tail when the album exhausts (Tier 2G).
                            onPlayAlbum = { tracks, idx ->
                                playFromSection(
                                    tracks, idx,
                                    com.isaivazhi.app.engine.PlaybackEngine.QueueContext.ALBUM,
                                    "Album",
                                )
                            },
                            onSongLongPress = { menuPlaylistContext = null; menuSongsTabIndex = null; menuSong = it },
                            // Push #62: album-level long-press → open a
                            // menu with Play / Shuffle / Delete actions.
                            onAlbumLongPress = { tracks, name ->
                                albumMenuTracks = tracks
                                albumMenuName = name
                            },
                            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                        )
                        Tab.UpNext -> UpNextScreen(
                            queue = fullQueueSongs,
                            currentMediaId = playbackState.currentMediaId,
                            currentIndex = playbackState.queueIndex,
                            aiMode = effectiveRecMode,
                            aiModeEnabled = hasEmbeddingsInLibrary,
                            onToggleMode = { aiMode ->
                                coroutineScope.launch { container.preferences.setRecMode(aiMode) }
                                val mediaId = playbackState.currentMediaId ?: return@UpNextScreen
                                val current = songs.firstOrNull { it.filename == mediaId } ?: return@UpNextScreen
                                coroutineScope.launch {
                                    // Push #70: AI/Shuffle toggle on UpNext preserves Play Next.
                                    val playNextSet = playbackState.playNextFilenames
                                    val byFn = songs.associateBy { it.filename }
                                    val playNextSongs = playNextSet.mapNotNull { byFn[it] }
                                    val excludeFns = playNextSet + mediaId
                                    // Push #72: AI/Shuffle toggle uses the
                                    // blended query vec (mode=refresh) so
                                    // recommendations reflect current+session
                                    // +profile blend, not just current-song.
                                    val blendedTriple = runCatching {
                                        container.recommender.buildBlendedVec(
                                            currentSongHash = current.contentHash,
                                            sessionListened = container.session.listened.value,
                                            profileVec = cachedProfileVec,
                                            library = songs,
                                            mode = "refresh",
                                            sessionBias = tuning.sessionBias,
                                        )
                                    }.getOrNull()
                                    blendedTriple?.let { (_, w, label) -> lastBlendInfo = LastBlendInfo(w, label) }
                                    val blendedVec = blendedTriple?.first
                                    val newTail: List<Song> = if (aiMode && (embeddingsRowCount ?: 0) > 0) {
                                        runCatching {
                                            container.recommender.recommendUpcoming(
                                                current, songs, k = 50,
                                                adventurous = tuning.adventurous,
                                                extraExcludeFilenames = excludeFns,
                                                hardBlockedFilenames = upNextHardBlocked,
                                                dislikedFilenames = dislikedSet,
                                                softExcludedFilenames = upNextSoftExcluded,
                                                blendedQueryVec = blendedVec,
                                            )
                                        }.getOrDefault(emptyList())
                                    } else {
                                        songs.filter { it.filePath != null && it.filename !in excludeFns }
                                            .shuffled().take(50)
                                    }
                                    val finalUpcoming = playNextSongs + newTail
                                    android.util.Log.i(
                                        "QueueOp",
                                        "UpNext toggle aiMode=$aiMode: blend=${blendedTriple?.third ?: "n/a"} preserved ${playNextSongs.size} Play Next + ${newTail.size} new"
                                    )
                                    container.playback.replaceUpcoming(
                                        newUpcoming = finalUpcoming,
                                        newContext = com.isaivazhi.app.engine.PlaybackEngine.QueueContext.AI_RECOMMENDED,
                                    )
                                }
                            },
                            onJumpTo = { index -> container.playback.playAtIndex(index) },
                            onLongPress = { menuPlaylistContext = null; menuSongsTabIndex = null; menuSong = it },
                            onRemove = { idx -> container.playback.removeFromQueue(idx) },
                            onMove = { from, to -> container.playback.moveQueueItem(from, to) },
                            onRefresh = { refreshUpcomingWithAI() },
                            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                        )
                        Tab.Browse -> {
                            // Memoize the tile grid so it doesn't iterate 2471
                            // songs × 6 categories on every recomposition (was
                            // running 3× per song transition before push #35).
                            val browseTiles = remember(
                                songs, historyStats, historyEvents, favoritesSet, dislikedSet,
                            ) {
                                buildBrowseTiles(
                                    songs = songs,
                                    historyStats = historyStats,
                                    historyEvents = historyEvents,
                                    favorites = favoritesSet,
                                    disliked = dislikedSet,
                                )
                            }
                            BrowseScreen(
                                tiles = browseTiles,
                                playlists = playlistsList,
                                onOpenCategory = { cat -> overlay = Overlay.ViewAll(cat) },
                                onCreatePlaylist = { name -> container.playlists.create(name) },
                                onRenamePlaylist = { id, name ->
                                    container.playlists.rename(id, name)
                                    container.toaster.show("Playlist renamed")
                                },
                                onOpenPlaylist = { id -> overlay = Overlay.PlaylistDetail(id) },
                                onDeletePlaylist = { id -> container.playlists.delete(id) },
                                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // ===== Overlays =====
    AnimatedVisibility(
        visible = overlay is Overlay.FullPlayer,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        NowPlayingScreen(
            state = playbackState,
            positionMs = livePositionMs,
            durationMs = liveDurationMs,
            currentSongFilePath = currentSongFilePath,
            isFavorite = isCurrentFavorite,
            isDisliked = isCurrentDisliked,
            onClose = { overlay = Overlay.None },
            onToggleFavorite = {
                playbackState.currentMediaId?.let { fn ->
                    val wasFav = fn in favoritesSet
                    val wasDis = fn in dislikedSet
                    container.favorites.toggle(fn)
                    if (!wasFav && wasDis) container.disliked.remove(fn)
                    container.toaster.show(
                        when {
                            wasFav -> "Removed from favorites"
                            wasDis -> "Added to favorites (removed dislike)"
                            else -> "Added to favorites"
                        }
                    )
                }
            },
            onToggleDislike = {
                playbackState.currentMediaId?.let { fn ->
                    val wasFav = fn in favoritesSet
                    val nowDisliked = container.disliked.toggle(fn)
                    if (nowDisliked && wasFav) container.favorites.remove(fn)
                    container.toaster.show(
                        when {
                            !nowDisliked -> "Removed dislike"
                            wasFav -> "Disliked (removed favorite)"
                            else -> "Disliked"
                        }
                    )
                }
            },
            onToggleShuffle = { container.playback.toggleShuffle() },
            onCycleRepeat = { container.playback.cycleRepeat() },
            onTogglePause = { container.playback.togglePause() },
            onSkipPrev = { container.playback.skipPrev() },
            onSkipNext = { container.playback.skipNext(neutral = true) },
            onSeek = { container.playback.seekTo(it) },
            onRefresh = refreshUpcomingWithAI,
            refreshEnabled = playbackState.currentMediaId != null && effectiveRecMode,
            refreshInProgress = refreshInProgress,
            similarSongs = similarToCurrent,
            similarLoading = similarLoading,
            similarFrozen = similarFrozen,
            similarSeedTitle = similarSeedTitle,
            queueAllSimilarEnabled = similarToCurrent.isNotEmpty() && effectiveRecMode,
            onSimilarTap = { song -> playFromTap(song) },
            onSimilarPlayNext = { song ->
                container.playback.playNext(song)
                container.toaster.show("Playing next")
            },
            onSimilarLongPress = { song ->
                menuPlaylistContext = null
                menuSongsTabIndex = null
                menuSong = song
            },
            onQueueAllSimilar = queueAllSimilarAfterCurrent,
            onToggleSimilarFrozen = {
                if (similarFrozen) {
                    similarFrozen = false
                } else {
                    similarFrozen = true
                    similarSeedMediaId = playbackState.currentMediaId ?: similarSeedMediaId
                }
            },
        )
    }

    AnimatedVisibility(visible = overlay is Overlay.Search,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it })) {
        SearchOverlay(
            songs = songs,
            onDismiss = { overlay = Overlay.None },
            // Push #70: Search result tap = single song + AI tail (LIBRARY).
            onPlay = { queue, idx ->
                queue.getOrNull(idx)?.let { playFromTap(it) }
            },
        )
    }

    AnimatedVisibility(visible = overlay is Overlay.Settings,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })) {
        SettingsScreen(
            artCacheBytes = artCacheBytes,
            importInProgress = importInProgress,
            importStatus = importMessage,
            onBack = { overlay = Overlay.None },
            onReimportEmbeddings = {
                if (!importInProgress) {
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                }
            },
            onClearArtCache = {
                AlbumArtRepository.clearDiskCache(ctx)
                AlbumArtRepository.trimMemory()
                coroutineScope.launch { artCacheBytes = withContextIo { AlbumArtRepository.diskCacheBytes(ctx) } }
            },
            onOpenAiLibrary = { overlay = Overlay.Ai },
            onOpenLogs = { overlay = Overlay.Logs() },
        )
    }

    AnimatedVisibility(visible = overlay is Overlay.Favorites,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })) {
        FavoritesScreen(
            songs = songs,
            favorites = favoritesSet,
            currentMediaId = playbackState.currentMediaId,
            onBack = { overlay = Overlay.None },
            onUnfavorite = { container.favorites.remove(it) },
            // Push #70: Favorites = BROWSE_SECTION (no AI tail).
            onPlay = { q, idx ->
                playFromSection(
                    q, idx,
                    com.isaivazhi.app.engine.PlaybackEngine.QueueContext.BROWSE_SECTION,
                    "Favorites",
                )
            },
        )
    }

    AnimatedVisibility(visible = overlay is Overlay.Playlists,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })) {
        PlaylistsScreen(
            playlists = playlistsList,
            songs = songs,
            onBack = { overlay = Overlay.None },
            onCreate = { container.playlists.create(it) },
            onRename = { id, name ->
                container.playlists.rename(id, name)
                container.toaster.show("Playlist renamed")
            },
            onDelete = { container.playlists.delete(it) },
            onOpenPlaylist = { overlay = Overlay.PlaylistDetail(it) },
        )
    }

    val currentDetailId = (overlay as? Overlay.PlaylistDetail)?.playlistId
    val currentDetailPlaylist = currentDetailId?.let { id -> playlistsList.firstOrNull { it.id == id } }
    AnimatedVisibility(visible = currentDetailPlaylist != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })) {
        if (currentDetailPlaylist != null) {
            PlaylistDetailScreen(
                playlist = currentDetailPlaylist,
                songs = songs,
                currentMediaId = playbackState.currentMediaId,
                onBack = { overlay = Overlay.Playlists },
                // Push #70: Playlist = BROWSE_SECTION (no AI tail).
                onPlay = { q, idx ->
                    playFromSection(
                        q, idx,
                        com.isaivazhi.app.engine.PlaybackEngine.QueueContext.BROWSE_SECTION,
                        "Playlist:${currentDetailPlaylist.name}",
                    )
                },
                onRemove = { container.playlists.removeSong(currentDetailPlaylist.id, it) },
            )
        }
    }

    AnimatedVisibility(visible = overlay is Overlay.History,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })) {
        HistoryScreen(
            songs = songs,
            events = historyEvents,
            onBack = { overlay = Overlay.None },
            onPlay = { container.playback.play(it) },
        )
    }

    AnimatedVisibility(visible = overlay is Overlay.Taste,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })) {
        TasteScreen(
            tuning = tuning,
            songs = songs,
            stats = historyStats,
            signals = tasteSignals,
            decoratedSignals = decoratedSignals,
            upNextHardBlockedCount = upNextHardBlocked.size,
            upNextSoftExcludedCount = upNextSoftExcluded.size,
            upNextExcludedFilenames = upNextPolicyExcludes,
            favoritesSet = favoritesSet,
            dislikedSet = dislikedSet,
            timelineEvents = timelineEvents,
            engineSnapshot = buildEngineSnapshot(
                tuning = tuning,
                lastBlend = lastBlendInfo,
                queueSize = playbackState.queueFilenames.size,
                aiMode = effectiveRecMode,
                embeddingsCovered = embeddedFilenames.size,
                embeddingsTotal = songs.count { it.filePath != null },
                nowPlayingTitle = playbackState.title,
                nowPlayingArtist = playbackState.artist,
            ),
            onBack = { overlay = Overlay.None },
            onAdventurousChange = {
                container.taste.setAdventurous(it)
                container.toaster.show("Adventurous: ${(it * 100).toInt()}%")
            },
            onSessionBiasChange = {
                container.taste.setSessionBias(it)
                container.toaster.show("Session weight: ${(it * 100).toInt()}%")
            },
            onNegativeStrengthChange = {
                container.taste.setNegativeStrength(it)
                container.toaster.show("Negative guard: ${(it * 100).toInt()}%")
            },
            onAdventurousReset = {
                container.taste.resetAdventurous()
                container.toaster.show("Adventurous reset to ${(TasteEngine.DEFAULT_ADVENTUROUS * 100).toInt()}%")
            },
            onSessionBiasReset = {
                container.taste.resetSessionBias()
                container.toaster.show("Session weight reset to ${(TasteEngine.DEFAULT_SESSION_BIAS * 100).toInt()}%")
            },
            onNegativeStrengthReset = {
                container.taste.resetNegativeStrength()
                container.toaster.show("Negative guard reset to ${(TasteEngine.DEFAULT_NEGATIVE_STRENGTH * 100).toInt()}%")
            },
            onResetSongStats = { fn ->
                container.history.resetStatsForSong(fn)
                container.taste.resetSignalForSong(fn)
            },
            onResetEngineConfirmed = {
                container.resetEngine()
                overlay = Overlay.None
            },
            onCopyTimelineText = { container.toaster.show("Copied last 30 signals") },
            onCopyTimelineSummary = { /* unused stub */ },
            // Push #70: Taste page row tap plays the Taste-ordered list
            // as a BROWSE_SECTION (no AI tail; user wants the signal list as-is).
            onPlayOrderedList = { q, idx ->
                playFromSection(
                    q, idx,
                    com.isaivazhi.app.engine.PlaybackEngine.QueueContext.BROWSE_SECTION,
                    "TasteOrdered",
                )
            },
            // Push #74: long-press the Taste Signal header to open the
            // Activity Log overlay.
            onOpenActivityLog = { overlay = Overlay.Logs(LogsTab.Activity) },
        )
    }

    // Push #47: refresh embeddedFilenames whenever the user opens the
    // AI page. Defensive — the batchComplete LaunchedEffect already
    // refreshes after each batch, but if its emission was missed (rare:
    // SharedFlow buffer pressure, recomposition timing) the pending
    // list could show a song that was just embedded. Refreshing on AI
    // page open guarantees the list reflects the current DB state.
    //
    // Push #50: also load duplicate-filename rows on AI open + bump a
    // version counter so post-action refreshes re-trigger this LE.
    var duplicateRows by remember { mutableStateOf<List<com.isaivazhi.app.engine.EmbeddingDbFacade.DuplicateRow>>(emptyList()) }
    // Push #61: audio-duplicate groups — different filepaths sharing one
    // content_hash via T_PATH. Surfaces "same song stored twice on disk".
    var audioDuplicateGroups by remember { mutableStateOf<List<com.isaivazhi.app.engine.EmbeddingDbFacade.AudioDuplicateGroup>>(emptyList()) }
    // dupesRefreshTick declared earlier (near deleteSongHelper) so it can
    // be bumped from inside that callback.
    LaunchedEffect(overlay, dupesRefreshTick) {
        if (overlay is Overlay.Ai) {
            try {
                container.embeddingDb.relinkLibraryPaths(songs)
                val hashMap = container.embeddingDb.contentHashByFilepath()
                songs = container.embeddingDb.enrichSongsWithHashes(songs, hashMap)
                container.library.value = songs
                container.embeddingDb.prewarmFromLibrary(songs)
            } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "AI-page relink failed: ${t.message}")
            }
            refreshDbStats(container) { rc, dim, ext, fns, fps ->
                embeddingsRowCount = rc; embeddingsDim = dim; vecExtLoaded = ext
                embeddedFilenames = fns
                embeddedFilepaths = fps
            }
            // Push #56 diagnostic removed — its job (identifying that the
            // remaining Pending entries were legacy-imported rows with
            // empty T_EMB.filepath) is done; the push #55 UNION fix
            // resolved the mismatch and the only remaining Pending entry
            // is a real codec failure (Ayiram Thamarai.flac). The
            // diagnostic DB methods (EmbeddingDb.diagnoseByFilename etc.)
            // are kept for future debugging but no caller invokes them.
            // Duplicates only exist when rowCount > distinct filenames,
            // but querying is cheap — just run it. The Duplicates
            // section hides itself when the list is empty.
            try {
                duplicateRows = container.embedding.getDuplicates()
                audioDuplicateGroups = container.embedding.getAudioDuplicates()
            } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "load duplicates failed: ${t.message}")
            }
        }
    }
    // Push #51: filepath → Song lookup for the duplicate-resolver UI.
    // Built from the live MediaStore songs list so the screen can
    // render real title/artist/album for every duplicate group (the
    // embedding-row metadata is often blank for legacy imports). Also
    // serves as the "does this file exist" check — a null lookup means
    // the file is gone from the device.
    val songsByFilepath: Map<String, Song> = remember(songs) {
        songs.asSequence()
            .filter { !it.filePath.isNullOrEmpty() }
            .associateBy { it.filePath!! }
    }
    // Push #51: memoize the per-filename signals slice once per
    // timelineEvents change. The duplicate-detail sheet looks signals
    // up by filename; without memo, every recomposition of the AI page
    // would re-filter the 30-event ring per visible group.
    val signalsByFilename: Map<String, List<com.isaivazhi.app.engine.SignalTimelineEngine.Event>> =
        remember(timelineEvents) { timelineEvents.groupBy { it.filename } }

    // Push #49: precompute the stale filename list (rows in the
    // embeddings DB whose filename no longer appears in the current
    // library). Sorted for stable list rendering. Recomputed when
    // either side of the diff changes.
    val staleFilenames: List<String> = remember(embeddedFilenames, songs) {
        if (embeddedFilenames.isEmpty()) emptyList()
        else {
            val libraryNames = songs.asSequence()
                .filter { it.filePath != null }
                .mapTo(HashSet(songs.size)) { it.filename }
            embeddedFilenames.asSequence()
                .filter { it !in libraryNames }
                .sorted()
                .toList()
        }
    }

    AnimatedVisibility(visible = overlay is Overlay.Ai,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })) {
        AiManagementScreen(
            songs = songs,
            embeddingsRowCount = embeddingsRowCount ?: 0,
            embeddingsDim = embeddingsDim,
            vecExtensionLoaded = vecExtLoaded,
            status = embeddingStatus,
            embeddingSplitCount = embeddingSplitCount,
            libraryEmbedSplitCount = libraryEmbedSplitCount,
            onEmbeddingSplitCountChange = { count ->
                coroutineScope.launch {
                    container.preferences.setEmbeddingSplitCount(count)
                    embeddingSplitCount = count
                }
            },
            embeddedFilenames = embeddedFilenames,
            embeddedFilepaths = embeddedFilepaths,
            // Push #57: skip set + skip/un-skip callbacks. Pending list
            // excludes anything in this set; "Skipped Songs" sub-section
            // lists them with an un-skip option.
            skippedEmbeddings = skippedEmbeddings,
            onSkipEmbedding = { filepath ->
                container.skippedEmbeddings.add(filepath)
                container.toaster.show("Won't try embedding this song again")
                container.embedding.logBuffer.append("skip", "user skipped embedding: $filepath")
            },
            onUnskipEmbedding = { filepath ->
                container.skippedEmbeddings.remove(filepath)
                container.toaster.show("Re-added to pending")
                container.embedding.logBuffer.append("skip", "user un-skipped: $filepath")
            },
            staleFilenames = staleFilenames,
            onRemoveStale = { fns ->
                container.embedding.removeStaleEmbeddings(fns) { _ ->
                    // Refresh after the worker thread returns so the
                    // stat cell + JSON-mirror size update without a
                    // separate user action.
                    refreshDbStats(container) { rc, dim, ext, names, fps ->
                        embeddingsRowCount = rc
                        embeddingsDim = dim
                        vecExtLoaded = ext
                        embeddedFilenames = names
                        embeddedFilepaths = fps
                    }
                }
            },
            onBack = { overlay = Overlay.None },
            onEmbedPending = {
                // Push #53: use filepath, not filename, to identify
                // pending songs. MediaStore DISPLAY_NAME ≠ File(path).getName()
                // for some files, which left embedded songs stuck in
                // Pending forever.
                val pending = songs.filter { it.filePath != null && it.filePath !in embeddedFilepaths }
                if (pending.isNotEmpty()) {
                    container.embedding.embedSongs(pending)
                    container.toaster.show("Embedding ${pending.size} pending songs…")
                } else {
                    container.toaster.show("Nothing to embed — library fully covered")
                }
            },
            onEmbedOne = { song ->
                if (song.filePath != null) {
                    container.embedding.embedSongs(listOf(song))
                    container.toaster.show("Embedding \"${song.title.ifBlank { song.filename }}\"…")
                }
            },
            onReembedAll = {
                val all = songs.filter { it.filePath != null }
                container.embedding.embedSongs(all)
                container.toaster.show("Re-embedding ${all.size} songs…")
            },
            onRescanLibrary = {
                // Push #50: actionable feedback. Capture before/after
                // counts and surface them as both a toast AND a log
                // line so the user can tell what rescan actually did.
                val beforeSongs = songs.size
                val beforeEmbedded = embeddedFilenames.size
                container.toaster.show("Scanning library…")
                container.embedding.logBuffer.append("rescan", "started (library=$beforeSongs, embedded=$beforeEmbedded)")
                coroutineScope.launch {
                    songs = LibraryCache.invalidate(ctx)
                    // Push #47: rescan also refreshes the embeddedFilenames
                    // set so the pending list reflects newly-recorded
                    // songs in the DB (e.g. after a batch finished while
                    // the user was on a different screen and the
                    // batchComplete LE somehow dropped its emission).
                    refreshDbStats(container) { rc, dim, ext, fns, fps ->
                        embeddingsRowCount = rc; embeddingsDim = dim; vecExtLoaded = ext
                        embeddedFilenames = fns
                        embeddedFilepaths = fps
                    }
                    val afterSongs = songs.size
                    val songDelta = afterSongs - beforeSongs
                    val deltaText = when {
                        songDelta > 0 -> "+$songDelta new"
                        songDelta < 0 -> "${-songDelta} removed"
                        else -> "no change"
                    }
                    val msg = "Scan: $beforeSongs → $afterSongs songs ($deltaText)"
                    container.toaster.show(msg)
                    container.embedding.logBuffer.append("rescan", "done — $msg")
                    // Refresh duplicates view too, in case rescan
                    // changed which rows look stale vs duplicate.
                    dupesRefreshTick++
                }
            },
            onStop = {
                container.embedding.stop()
                container.toaster.show("Embedding stopped")
                container.embedding.logBuffer.append("stop", "user stopped embedding")
            },
            // Manual one-shot IVZ backup (isaivazhi_embeddings.bin).
            onExportBackup = {
                if (exportBackupInProgress) {
                    container.toaster.show("Export already in progress…")
                } else {
                exportBackupInProgress = true
                val rowHint = embeddingsRowCount ?: 0
                exportBackupStatus = "Preparing backup — reading $rowHint embeddings…"
                container.embedding.logBuffer.append("backup", "manual export started")
                coroutineScope.launch {
                    try {
                        val splits = container.preferences.getEmbeddingSplitCount()
                        val res = runCatching {
                            container.embeddingDb.exportEmbeddingsBin(splits)
                        }.getOrNull()
                        val rows = res?.optInt("rowCount", 0) ?: 0
                        val bytes = res?.optLong("bytes", 0L) ?: 0L
                        val mbStr = "%.1f".format(bytes / 1024.0 / 1024.0)
                        if (res == null || rows <= 0) {
                            exportBackupStatus = "Export failed — no embeddings in database"
                            container.toaster.show("Backup failed — no embeddings to export")
                            container.embedding.logBuffer.append("backup", "manual export FAILED")
                            exportBackupInProgress = false
                            kotlinx.coroutines.delay(3000)
                            exportBackupStatus = null
                            return@launch
                        }
                        container.embedding.logBuffer.append(
                            "backup",
                            "manual export complete — $rows rows, $bytes bytes ($mbStr MB)",
                        )
                        exportBackupStatus = "Ready — choose where to save ($rows songs, $mbStr MB)"
                        exportSaveLauncher.launch(EmbeddingsImport.BIN_FILENAME)
                    } catch (t: Throwable) {
                        val err = t.message ?: t.javaClass.simpleName
                        exportBackupStatus = "Export failed: $err"
                        container.toaster.show("Export failed: $err")
                        container.embedding.logBuffer.append("backup", "manual export FAILED: $err")
                        exportBackupInProgress = false
                        kotlinx.coroutines.delay(3000)
                        exportBackupStatus = null
                    }
                }
                }
            },
            exportBackupInProgress = exportBackupInProgress,
            exportBackupStatus = exportBackupStatus,
            // Push #51: Duplicates section wiring. Grouping is now
            // filepath-based (push #51 SQL change), play lookup goes
            // strictly through filepath — no filename fallback (the
            // old fallback could play a totally different Song with
            // the same filename in a different folder).
            duplicateRows = duplicateRows,
            songsByFilepath = songsByFilepath,
            signalsForFilename = { fn -> signalsByFilename[fn] ?: emptyList() },
            onPlayDuplicate = { row ->
                val match: Song? = songsByFilepath[row.filepath]
                if (match != null) {
                    playFromTap(match)
                } else {
                    container.toaster.show("File not on device — this row is stale, tap (−) to remove")
                    container.embedding.logBuffer.append(
                        "dupes",
                        "play missing: ${row.filename} (${row.filepath})"
                    )
                }
            },
            onRemoveDuplicateRows = { hashes ->
                container.embedding.removeDuplicateRows(hashes) { _ ->
                    refreshDbStats(container) { rc, dim, ext, names, fps ->
                        embeddingsRowCount = rc
                        embeddingsDim = dim
                        vecExtLoaded = ext
                        embeddedFilenames = names
                        embeddedFilepaths = fps
                    }
                    dupesRefreshTick++
                }
            },
            // Push #61: Audio Duplicates section wiring.
            audioDuplicateGroups = audioDuplicateGroups,
            onPlayAudioDup = { filepath ->
                val match = songsByFilepath[filepath]
                if (match != null) {
                    playFromTap(match)
                } else {
                    container.toaster.show("File not on device")
                    container.embedding.logBuffer.append("audiodupes", "play missing: $filepath")
                }
            },
            onDeleteAudioDup = { filepath ->
                // Route through the standard scoped-storage delete flow
                // (push #58). On success, the helper refreshes library +
                // embedded sets; we ALSO drop the T_PATH entry so the
                // dead filepath stops showing in the duplicate group
                // and bump the dupes tick so the section recomputes.
                pendingAudioDupCleanupFilepath = filepath
                deleteSongHelper.delete(filepath)
            },
        )
    }

    val showOnboardingImportUi = permission.granted &&
        songs.isNotEmpty() &&
        (embeddingsRowCount ?: 0) == 0 &&
        !onboardingDismissed
    EmbeddingsImportDialog(
        visible = (importInProgress || !importMessage.isNullOrBlank()) && !showOnboardingImportUi,
        inProgress = importInProgress,
        statusMessage = importMessage,
        onDismiss = { importMessage = null },
    )

    val logsOverlay = overlay as? Overlay.Logs
    AnimatedVisibility(visible = logsOverlay != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })) {
        if (logsOverlay != null) {
            LogsScreen(
                activityLog = container.activityLog,
                embeddingLogLines = embeddingLogs,
                onClearEmbeddingLog = {
                    container.embedding.logBuffer.clear()
                    container.toaster.show("Embedding log cleared")
                },
                initialTab = logsOverlay.initialTab,
                onBack = { overlay = Overlay.None },
            )
        }
    }

    val viewAllCat = (overlay as? Overlay.ViewAll)?.category
    AnimatedVisibility(visible = viewAllCat != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })) {
        if (viewAllCat != null) {
            val listSongs = browseCategorySongs(
                category = viewAllCat,
                songs = songs,
                historyStats = historyStats,
                historyEvents = historyEvents,
                favorites = favoritesSet,
                disliked = dislikedSet,
            )
            ViewAllScreen(
                title = viewAllCat.title,
                songs = listSongs,
                currentMediaId = playbackState.currentMediaId,
                onBack = { overlay = Overlay.None },
                // Push #70: Browse-tab ViewAll = BROWSE_SECTION context.
                // Section plays through, no AI tail appended at end.
                onPlay = { q, idx ->
                    playFromSection(
                        q, idx,
                        com.isaivazhi.app.engine.PlaybackEngine.QueueContext.BROWSE_SECTION,
                        viewAllCat.title,
                    )
                },
                onLongPress = { menuPlaylistContext = null; menuSongsTabIndex = null; menuSong = it },
                subtitleForSong = if (viewAllCat == BrowseCategory.MostPlayed) {
                    { song ->
                        val plays = historyStats[song.filename]?.plays ?: 0
                        "Played $plays ${if (plays == 1) "time" else "times"}"
                    }
                } else null,
            )
        }
    }

    // Phase 4 (2026-06-01): Overlay.SectionViewAll renderer removed alongside
    // the Discover page. Browse-tab ViewAll continues to render via the
    // Overlay.ViewAll(category) branch above.

    // Long-press song menu
    val sheetSong = menuSong
    if (sheetSong != null) {
        val playlistCtxName = menuPlaylistContext?.let { id ->
            playlistsList.firstOrNull { it.id == id }?.name
        }
        SongMenuSheet(
            song = sheetSong,
            isFavorite = isMenuSongFavorite,
            isDisliked = isMenuSongDisliked,
            hasEmbedding = isMenuSongEmbedded,
            currentSplitCount = embeddingSplitCount,
            currentPlaylistName = playlistCtxName,
            songStats = menuSongStats,
            tasteSignal = menuSongTasteSignal,
            playlists = playlistsList,
            onDismiss = { menuSong = null; menuPlaylistContext = null },
            onPlayNext = {
                container.playback.playNext(sheetSong)
                container.toaster.show("Added to play next")
            },
            onToggleFavorite = {
                val fn = sheetSong.filename
                val wasFav = fn in favoritesSet
                val wasDis = fn in dislikedSet
                container.favorites.toggle(fn)
                if (!wasFav && wasDis) container.disliked.remove(fn)
                container.toaster.show(
                    when {
                        wasFav -> "Removed from favorites"
                        wasDis -> "Added to favorites (removed dislike)"
                        else -> "Added to favorites"
                    }
                )
            },
            onToggleDislike = {
                val fn = sheetSong.filename
                val wasFav = fn in favoritesSet
                val nowDisliked = container.disliked.toggle(fn)
                if (nowDisliked && wasFav) container.favorites.remove(fn)
                container.toaster.show(
                    when {
                        !nowDisliked -> "Removed dislike"
                        wasFav -> "Disliked (removed favorite)"
                        else -> "Disliked"
                    }
                )
            },
            onAddToPlaylist = { plId ->
                container.playlists.addSong(plId, sheetSong.filename)
                val plName = playlistsList.firstOrNull { it.id == plId }?.name ?: "playlist"
                container.toaster.show("Added to \"$plName\"")
            },
            onCreatePlaylistAndAdd = { name ->
                val pl = container.playlists.create(name)
                container.playlists.addSong(pl.id, sheetSong.filename)
                container.toaster.show("Created playlist \"${pl.name}\" and added song")
            },
            onRemoveFromCurrentPlaylist = {
                val ctxId = menuPlaylistContext
                if (ctxId != null) {
                    val plName = playlistsList.firstOrNull { it.id == ctxId }?.name ?: "playlist"
                    container.playlists.removeSong(ctxId, sheetSong.filename)
                    container.toaster.show("Removed from \"$plName\"")
                }
            },
            onViewDetails = { /* dialog shown inside SongMenuSheet */ },
            onViewAlbum = {
                albumToExpand = sheetSong.album.ifBlank { "Unknown album" }
                albumRevealRequestId += 1
                coroutineScope.launch { pagerState.animateScrollToPage(Tab.Albums.ordinal) }
            },
            onResetTasteSignal = {
                container.taste.resetSignalForSong(sheetSong.filename)
                container.toaster.show("Taste signal reset")
            },
            // Push #40 Tier 1C: explicit "Embed this song" entry. Always
            // fires an embed for the song regardless of current state.
            onEmbedSong = {
                if (sheetSong.filePath != null) {
                    container.embedding.embedSongs(listOf(sheetSong))
                    container.toaster.show("Embedding \"${sheetSong.title.ifBlank { sheetSong.filename }}\"…")
                }
            },
            onDeleteFromDevice = {
                // Push #58: route through the scoped-storage-aware
                // deleteSongHelper. The helper presents the system delete
                // dialog (Android 11+) and only fires success after the
                // OS confirms the delete actually happened. The toast
                // and library refresh live inside the helper's onResult
                // callback at the top of AppRoot.
                sheetSong.filePath?.let { deleteSongHelper.delete(it) }
            },
            // Push #42 Tier 2I: only present when the long-press came
            // from the Songs tab (menuSongsTabIndex != null). Plays the
            // Songs library linearly from the tapped position.
            onPlayInOrder = menuSongsTabIndex?.let { idx ->
                {
                    // Push #70: Songs-tab "Play in order" = LIBRARY context.
                    // Plays the full Songs list from the tapped position.
                    // AI tail appends when this huge queue exhausts (rare).
                    playFromSection(
                        songs, idx,
                        com.isaivazhi.app.engine.PlaybackEngine.QueueContext.LIBRARY,
                        "SongsTabLinear",
                    )
                }
            },
        )
    }

    // Push #62: album-level long-press menu. Surfaced from AlbumsScreen
    // when the user long-presses an album header. Offers Play (linear),
    // Shuffle play, and Delete (batch).
    val albumTracks = albumMenuTracks
    val albumName = albumMenuName
    if (albumTracks != null && albumName != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { albumMenuTracks = null; albumMenuName = null },
            title = { Text(albumName) },
            text = {
                Column {
                    Text(
                        text = "${albumTracks.size} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.TextButton(
                        onClick = {
                            albumMenuTracks = null
                            albumMenuName = null
                            val playable = albumTracks.filter { it.filePath != null }
                            if (playable.isNotEmpty()) {
                                // Push #70: album = ALBUM context (no AI tail).
                                container.playback.playQueue(
                                    songs = playable,
                                    startIndex = 0,
                                    source = "album",
                                    queueContext = com.isaivazhi.app.engine.PlaybackEngine.QueueContext.ALBUM,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Play album", modifier = Modifier.weight(1f))
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            albumMenuTracks = null
                            albumMenuName = null
                            val shuffled = albumTracks.filter { it.filePath != null }.shuffled()
                            if (shuffled.isNotEmpty()) {
                                // Push #70: shuffled album = still ALBUM context.
                                container.playback.playQueue(
                                    songs = shuffled,
                                    startIndex = 0,
                                    source = "album_shuffle",
                                    queueContext = com.isaivazhi.app.engine.PlaybackEngine.QueueContext.ALBUM,
                                )
                                container.toaster.show("Shuffling \"$albumName\"")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Shuffle album", modifier = Modifier.weight(1f))
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showAlbumDeleteConfirm = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.RemoveCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Delete album",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { albumMenuTracks = null; albumMenuName = null }) {
                    Text("Close")
                }
            },
        )
    }
    if (showAlbumDeleteConfirm && albumTracks != null && albumName != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAlbumDeleteConfirm = false },
            title = { Text("Delete album \"$albumName\"?") },
            text = {
                Text(
                    "Deletes ${albumTracks.size} audio files from the device. " +
                        "Android will show a single confirmation dialog covering all of them. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showAlbumDeleteConfirm = false
                    val paths = albumTracks.mapNotNull { it.filePath }
                    albumMenuTracks = null
                    albumMenuName = null
                    if (paths.isNotEmpty()) deleteSongHelper.deleteBatch(paths)
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showAlbumDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Push #44: custom top-end toast overlay. Sibling at AppRoot's top
    // level above Scaffold and all overlay AnimatedVisibility blocks.
    // The fillMaxSize Box itself is cheap (single empty layout slot
    // unless `currentToast != null`); the heavy work is short-circuited
    // by AnimatedVisibility's invisible state.
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        androidx.compose.animation.AnimatedVisibility(
            visible = currentToast != null,
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.slideInVertically { -it / 2 },
            exit = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.slideOutVertically { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 56.dp, end = 12.dp, start = 12.dp),
        ) {
            val msg = currentToast ?: ""
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * Push #66: a parsed entry from the service's transitions history buffer.
 */
private data class RecentTransition(
    val prevPlaybackInstanceId: Long,
    val prevPlayedMs: Long,
    val prevDurationMs: Long,
    val prevFraction: Float,
    val prevFilename: String,
    val action: String,
)

private fun parseRecentTransitions(arr: org.json.JSONArray): List<RecentTransition> {
    val out = ArrayList<RecentTransition>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out += RecentTransition(
            prevPlaybackInstanceId = o.optLong("prevPlaybackInstanceId", 0L),
            prevPlayedMs = o.optLong("prevPlayedMs", 0L),
            prevDurationMs = o.optLong("prevDurationMs", 0L),
            prevFraction = o.optDouble("prevFraction", 0.0).toFloat(),
            prevFilename = o.optString("prevFilename", ""),
            action = o.optString("action", ""),
        )
    }
    return out
}

/**
 * Push #74: cold-start replay of every transition in the service's rolling
 * buffer that hasn't already been credited live. Fixes the "5 background
 * auto-advances → only the last one captured" hole: PlaybackEngine's
 * Activity-scoped listener is dead while the service auto-advances, so the
 * buffer is the only record of what played. Returns the watermark in effect
 * after replay so the snapshot reconcile path can gate against it.
 */
private suspend fun drainTransitionsBuffer(
    container: AppContainer,
    songs: List<Song>,
    recentTransitions: List<RecentTransition>,
): Long {
    val watermark = runCatching { container.preferences.loadIngestWatermark() }.getOrDefault(0L)
    val unprocessed = recentTransitions.filter {
        it.prevPlaybackInstanceId > watermark &&
            it.prevPlaybackInstanceId > 0L &&
            it.prevPlayedMs >= 1000L &&
            it.prevDurationMs > 0L &&
            it.prevFilename.isNotBlank()
    }
    if (unprocessed.isNotEmpty()) {
        android.util.Log.i(
            "MainActivity",
            "drainTransitionsBuffer: replaying ${unprocessed.size} unprocessed transitions (watermark=$watermark, bufferSize=${recentTransitions.size})"
        )
        container.activityLog.log(
            category = "engine",
            type = "buffer_replay_start",
            message = "Replaying ${unprocessed.size} background transitions",
            data = mapOf(
                "watermark" to watermark,
                "bufferSize" to recentTransitions.size,
                "unprocessed" to unprocessed.size,
            ),
        )
    }
    for (t in unprocessed) {
        ingestPendingEvidence(
            container = container,
            songs = songs,
            mediaId = t.prevFilename,
            playedMs = t.prevPlayedMs,
            durationMs = t.prevDurationMs,
            origin = "buffer_replay_${t.action}",
            playbackInstanceId = t.prevPlaybackInstanceId,
        )
    }
    val newHigh = recentTransitions.maxOfOrNull { it.prevPlaybackInstanceId } ?: 0L
    val finalWatermark = maxOf(watermark, newHigh)
    if (finalWatermark > watermark) {
        runCatching { container.preferences.saveIngestWatermark(finalWatermark) }
    }
    return finalWatermark
}

/**
 * Push #66 — Capacitor parity (`_recoverPendingListenIfNeeded`):
 * three-path reconciliation between a pending listen snapshot and the
 * native transitions buffer.
 */
private fun reconcilePending(
    container: AppContainer,
    songs: List<Song>,
    snapshot: com.isaivazhi.app.engine.AppPreferences.PendingEvidence,
    recentTransitions: List<RecentTransition>,
    liveInstanceId: Long,
    originLabel: String,
) {
    val match = recentTransitions.firstOrNull { it.prevPlaybackInstanceId == snapshot.playbackInstanceId }
    if (match != null) {
        // Case A: a real native transition fired for this playback
        // instance. The transition's data is authoritative.
        android.util.Log.i(
            "MainActivity",
            "reconcilePending case=A (transition match) origin=$originLabel mediaId=${snapshot.mediaId} instId=${snapshot.playbackInstanceId} action=${match.action}"
        )
        container.activityLog.log(
            category = "engine",
            type = "RECON_A",
            message = "${snapshot.mediaId} — transition match (${match.action}) via $originLabel",
            data = mapOf(
                "mediaId" to snapshot.mediaId,
                "instId" to snapshot.playbackInstanceId,
                "action" to match.action,
                "origin" to originLabel,
            ),
        )
        ingestPendingEvidence(
            container = container,
            songs = songs,
            mediaId = match.prevFilename.ifBlank { snapshot.mediaId },
            playedMs = match.prevPlayedMs,
            durationMs = match.prevDurationMs,
            origin = "transition_history_${match.action}",
            playbackInstanceId = snapshot.playbackInstanceId,
        )
        return
    }
    if (liveInstanceId > 0L && liveInstanceId == snapshot.playbackInstanceId) {
        // Case B: the same playback session is still active in the
        // service. Defer — the eventual transition will record it.
        android.util.Log.i(
            "MainActivity",
            "reconcilePending case=B (defer, same instance still live) origin=$originLabel instId=${snapshot.playbackInstanceId}"
        )
        container.activityLog.log(
            category = "engine",
            type = "RECON_B",
            message = "${snapshot.mediaId} — deferred (still playing) via $originLabel",
            data = mapOf(
                "mediaId" to snapshot.mediaId,
                "instId" to snapshot.playbackInstanceId,
                "origin" to originLabel,
            ),
        )
        return
    }
    // Case C: no matching transition, no live session. Use snapshot as-is.
    android.util.Log.i(
        "MainActivity",
        "reconcilePending case=C (use snapshot) origin=$originLabel mediaId=${snapshot.mediaId} played=${snapshot.playedMs}ms"
    )
    container.activityLog.log(
        category = "engine",
        type = "RECON_C",
        message = "${snapshot.mediaId} — recovered from snapshot via $originLabel",
        data = mapOf(
            "mediaId" to snapshot.mediaId,
            "playedMs" to snapshot.playedMs,
            "durationMs" to snapshot.durationMs,
            "instId" to snapshot.playbackInstanceId,
            "origin" to originLabel,
        ),
    )
    ingestPendingEvidence(
        container = container,
        songs = songs,
        mediaId = snapshot.mediaId,
        playedMs = snapshot.playedMs,
        durationMs = snapshot.durationMs,
        origin = originLabel,
        playbackInstanceId = snapshot.playbackInstanceId,
    )
}

/**
 * Push #65 (updated #66): ingest a leftover pending-evidence record into
 * TasteEngine + SignalTimeline. Source is set to "background_recovery"
 * (not on the user-skip whitelist) so partial listens DON'T bump xScore.
 */
private fun ingestPendingEvidence(
    container: AppContainer,
    songs: List<Song>,
    mediaId: String,
    playedMs: Long,
    durationMs: Long,
    origin: String,
    playbackInstanceId: Long = 0L,
) {
    if (mediaId.isBlank() || playedMs < 1000L || durationMs <= 0L) return
    val frac = (playedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val song = songs.firstOrNull { it.filename == mediaId }
    android.util.Log.i(
        "MainActivity",
        "ingestPendingEvidence origin=$origin mediaId=$mediaId played=${playedMs}ms dur=${durationMs}ms frac=${"%.3f".format(frac)} songResolved=${song != null} instId=$playbackInstanceId"
    )
    val ingestType = if (origin.startsWith("buffer_replay_")) "REPLAY" else "INGEST"
    container.activityLog.log(
        category = "engine",
        type = ingestType,
        message = "${song?.title?.ifBlank { mediaId } ?: mediaId} — ${(frac * 100).toInt()}% via $origin",
        data = mapOf(
            "mediaId" to mediaId,
            "playedMs" to playedMs,
            "durationMs" to durationMs,
            "fraction" to frac,
            "origin" to origin,
            "instId" to playbackInstanceId,
            "songResolved" to (song != null),
        ),
    )
    // Push #66: source = "background_recovery" (NOT on the user-skip
    // whitelist) so partial listens recovered from background DON'T
    // bump xScore. Strong listens still get the -0.5 NEG_LISTEN_DECAY
    // benefit. Capacitor parity.
    val source = "background_recovery"
    val (before, after) = container.taste.recordPlaybackEvent(mediaId, frac, source, playbackInstanceId)
    val isSkip = frac < com.isaivazhi.app.engine.TasteEngine.SKIP_THRESHOLD
    val cls = if (isSkip) com.isaivazhi.app.engine.SignalTimelineEngine.Classification.SKIP
        else com.isaivazhi.app.engine.SignalTimelineEngine.Classification.LISTEN
    val sessionPair = container.session.recordEvent(frac, isSkip, isManual = false)
    val sessionPull = container.taste.tuning.value.sessionBias.coerceIn(0f, 1f)
    val avgLib = run {
        val sigs = container.taste.signals.value
        if (sigs.isEmpty()) 0f else sigs.values.map { it.avgFraction }.average().toFloat()
    }
    container.signalTimeline.append(
        com.isaivazhi.app.engine.SignalTimelineEngine.Event(
            timestamp = System.currentTimeMillis(),
            filename = mediaId,
            title = song?.title?.ifBlank { mediaId } ?: mediaId,
            artist = song?.artist ?: "",
            source = "${source}_$origin",
            fraction = frac,
            classification = cls,
            tasteBefore = com.isaivazhi.app.engine.SignalTimelineEngine.Snapshot(
                score = before.directScore, direct = before.directScore,
                similarity = before.similarityBoost, plays = before.plays,
                skips = before.skips, avgFraction = before.avgFraction,
            ),
            tasteAfter = com.isaivazhi.app.engine.SignalTimelineEngine.Snapshot(
                score = after.directScore, direct = after.directScore,
                similarity = after.similarityBoost, plays = after.plays,
                skips = after.skips, avgFraction = after.avgFraction,
            ),
            xScoreBefore = before.xScore,
            xScoreAfter = after.xScore,
            sessionPullBefore = sessionPull,
            sessionPullAfter = sessionPull,
            libraryAvgBefore = avgLib,
            libraryAvgAfter = avgLib,
            sessionEncountersBefore = sessionPair.first.encounters,
            sessionEncountersAfter = sessionPair.second.encounters,
            sessionSkipsBefore = sessionPair.first.skips,
            sessionSkipsAfter = sessionPair.second.skips,
            sessionPositivesBefore = sessionPair.first.positives,
            sessionPositivesAfter = sessionPair.second.positives,
        )
    )
    val delta = after.directScore - before.directScore
    if (kotlin.math.abs(delta) > 0.001f) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                container.taste.propagateSimilarityBoost(mediaId, delta, "${source}_$origin")
            }
        }
    }
    // Push #74: bump the watermark so any subsequent cold-start replay
    // skips this instId. drainTransitionsBuffer also updates the watermark
    // collectively after its loop, but bumping per-ingest ensures
    // snapshot-only ingests (Case C) also advance it.
    if (playbackInstanceId > 0L) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { container.preferences.saveIngestWatermark(playbackInstanceId) }
        }
    }
    // Push #75: also record into HistoryEngine so Browse → Recently Played
    // reflects background/cold-start listens. The live-transition path
    // already calls history.recordEnd, but cold-start ingest bypasses that
    // path entirely — which is why Elay Keechan never showed up in
    // Recently Played despite playing for ~76% before the user closed the
    // app. recordCompleted skips the pendingStartFilename guard since the
    // cold-start record IS authoritative.
    container.history.recordCompleted(mediaId, System.currentTimeMillis(), frac)
}

private data class LastBlendInfo(
    val weights: com.isaivazhi.app.engine.Recommender.BlendWeights,
    val label: String,
)

private fun buildEngineSnapshot(
    tuning: com.isaivazhi.app.engine.TasteEngine.Tuning,
    lastBlend: LastBlendInfo?,
    queueSize: Int,
    aiMode: Boolean,
    embeddingsCovered: Int,
    embeddingsTotal: Int,
    nowPlayingTitle: String,
    nowPlayingArtist: String,
): EngineSnapshot {
    val pct: (Float) -> String = { v -> "${(v * 100).toInt()}%" }
    val currentPortion: Float
    val sessionPortion: Float
    val profilePortion: Float
    val mode: String
    val lastRefreshLabel: String?
    if (lastBlend != null) {
        val w = lastBlend.weights
        val total = (w.wCurrent + w.wSession + w.wProfile).coerceAtLeast(0.001f)
        currentPortion = w.wCurrent / total
        sessionPortion = w.wSession / total
        profilePortion = w.wProfile / total
        mode = when {
            currentPortion >= 0.55f -> "Current-song heavy"
            sessionPortion >= 0.45f -> "Strong session blend"
            profilePortion >= 0.45f -> "Profile heavy"
            else -> "Balanced"
        }
        lastRefreshLabel = "Last refresh: ${lastBlend.label} (${pct(currentPortion)} / ${pct(sessionPortion)} / ${pct(profilePortion)})"
    } else {
        currentPortion = (1f - tuning.sessionBias).coerceIn(0f, 1f)
        sessionPortion = (tuning.sessionBias * 0.7f).coerceIn(0f, 1f)
        profilePortion = (tuning.sessionBias * 0.3f).coerceIn(0f, 1f)
        mode = when {
            tuning.sessionBias < 0.2f -> "Current-song heavy"
            tuning.sessionBias > 0.7f -> "Strong session blend"
            else -> "Balanced (no Up Next refresh yet)"
        }
        lastRefreshLabel = null
    }
    val coveragePct = if (embeddingsTotal > 0) (embeddingsCovered * 100 / embeddingsTotal).toString() + "%"
    else "0%"
    return EngineSnapshot(
        blendCurrent = pct(currentPortion),
        blendSession = pct(sessionPortion),
        blendProfile = pct(profilePortion),
        blendMode = mode,
        lastRefreshLabel = lastRefreshLabel,
        queueSize = queueSize,
        queueMode = if (aiMode) "AI" else "Shuffle",
        embeddingsCovered = embeddingsCovered,
        embeddingsTotal = embeddingsTotal,
        embeddingsPercentage = coveragePct,
        nowPlayingTitle = nowPlayingTitle,
        nowPlayingArtist = nowPlayingArtist,
    )
}

private suspend fun <T> withContextIo(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

private fun refreshDbStats(
    container: AppContainer,
    onResult: (rowCount: Int, dim: Int, vecExt: Boolean, embeddedFilenames: Set<String>, embeddedFilepaths: Set<String>) -> Unit,
) {
    CoroutineScope(Dispatchers.IO).launch {
        // Phase A per-step timing (2026-06-01): instrument each DB call
        // independently so we can pinpoint whether the cold-start wait is
        // the first stats() (Room/sqlite-vec init) or the larger list
        // fetches (allFilenames / allFilepaths over ~2.4k rows).
        val t0 = System.currentTimeMillis()
        val stats = container.embeddingDb.stats()
        val tStats = System.currentTimeMillis()
        val filenames = container.embeddingDb.allFilenames()
        val tFilenames = System.currentTimeMillis()
        val filepaths = container.embeddingDb.allFilepaths()
        val tFilepaths = System.currentTimeMillis()
        val rc = stats?.optInt("count", 0) ?: 0
        val dim = stats?.optInt("dim", 0) ?: 0
        val vecExt = stats?.optBoolean("vecExtensionLoaded", false) ?: false
        container.activityLog.log(
            category = "engine",
            type = "PERF_DB_BREAKDOWN",
            message = "stats=${tStats - t0}ms allFilenames=${tFilenames - tStats}ms allFilepaths=${tFilepaths - tFilenames}ms",
            data = mapOf(
                "statsMs" to (tStats - t0),
                "filenamesMs" to (tFilenames - tStats),
                "filepathsMs" to (tFilepaths - tFilenames),
                "rowCount" to rc,
            ),
        )
        // Phase B (2026-06-01): cache the rowCount so the NEXT cold start
        // can publish it instantly and unblock AI Discover without waiting
        // for the DB worker (saves ~18s on the user's device where the
        // first DB call pays the full sqlite-vec init cost).
        runCatching { container.preferences.saveCachedEmbedStats(rc, dim, vecExt) }
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            onResult(rc, dim, vecExt, filenames, filepaths)
        }
    }
}

/**
 * Phase B (2026-06-01): lightweight startup-only helper. Calls only stats()
 * (no allFilenames/allFilepaths) so the FIRST DB worker turn returns the
 * row count as fast as possible. UI gates AI Discover on this value.
 * The heavier file-set fetch (`refreshDbStats`) runs later, lazily, when
 * the AI page actually needs the filename/filepath sets.
 */
private fun refreshEmbeddingRowCount(
    container: AppContainer,
    onResult: (rowCount: Int, dim: Int, vecExt: Boolean) -> Unit,
) {
    CoroutineScope(Dispatchers.IO).launch {
        val t0 = System.currentTimeMillis()
        val stats = container.embeddingDb.stats()
        val rc = stats?.optInt("count", 0) ?: 0
        val dim = stats?.optInt("dim", 0) ?: 0
        val vecExt = stats?.optBoolean("vecExtensionLoaded", false) ?: false
        val elapsed = System.currentTimeMillis() - t0
        container.activityLog.log(
            category = "engine",
            type = "PERF_DB_STATS_ONLY",
            message = "stats-only fetch ${elapsed}ms (rows=$rc dim=$dim vecExt=$vecExt)",
            data = mapOf(
                "elapsedMs" to elapsed,
                "rowCount" to rc,
                "dim" to dim,
                "vecExtLoaded" to vecExt,
            ),
        )
        runCatching { container.preferences.saveCachedEmbedStats(rc, dim, vecExt) }
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            onResult(rc, dim, vecExt)
        }
    }
}

private fun estimateEmbedTime(songCount: Int, backend: String): Long? {
    if (songCount <= 0) return null
    val perSongMs = when {
        backend.contains("fp16", ignoreCase = true) -> 180
        backend.startsWith("nnapi", ignoreCase = true) -> 280
        backend.equals("cpu", ignoreCase = true) -> 1400
        else -> 400
    }
    return (songCount.toLong() * perSongMs) / 1000L
}

private fun fallbackShuffleQueue(library: List<Song>, current: Song, k: Int): List<Song> {
    val tail = library.asSequence()
        .filter { it.filePath != null && it.filename != current.filename }
        .shuffled().take(k).toList()
    return listOf(current) + tail
}

@Composable
private fun PermissionGateUi(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()) {
            Text(text = "IsaiVazhi needs access to your music files.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Text(text = "Tap below to grant audio permission. The app reads only audio metadata, never modifies your files.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRequest) { Text("Grant access") }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                Text("Open app settings")
            }
        }
    }
}

/**
 * Push #55: explicit second-step gate for POST_NOTIFICATIONS. Shown after
 * audio permission is granted but before onboarding/scan results when the
 * user has not granted notifications. Tapping "Allow notifications" calls
 * the runtime permission API; if Android has soft-denied the permission
 * (after the user dismissed the prompt previously) the dialog won't show,
 * which is why the secondary "Open notification settings" button routes
 * the user directly to the per-app notification page. "Skip for now" sets
 * a session-local dismiss flag so the app doesn't loop on this screen.
 */
@Composable
private fun NotificationsPermissionGateUi(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Allow IsaiVazhi to show notifications?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Embedding progress, batch completion, and per-song status need a foreground notification — it appears in the status bar and on your lockscreen while a batch runs. No sound, no vibration; the notification disappears as soon as the batch is done.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRequest) { Text("Allow notifications") }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                Text("Open notification settings")
            }
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.TextButton(onClick = onSkip) {
                Text("Skip for now", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "If \"Allow notifications\" doesn't show a dialog, Android has previously denied this permission for the app — use \"Open notification settings\" to enable it manually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorPanel(msg: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text = "Library scan failed: $msg",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error)
    }
}
