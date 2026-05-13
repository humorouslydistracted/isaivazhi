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
import androidx.compose.material.icons.filled.Explore
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.isaivazhi.app.ui.screens.DebugLogsScreen
import com.isaivazhi.app.ui.screens.DiscoverScreen
import com.isaivazhi.app.ui.screens.DiscoverSectionRef
import com.isaivazhi.app.ui.screens.EmbeddingStatusBanner
import com.isaivazhi.app.ui.screens.EngineSnapshot
import com.isaivazhi.app.ui.screens.FavoritesScreen
import com.isaivazhi.app.ui.screens.HistoryScreen
import com.isaivazhi.app.ui.screens.MiniPlayer
import com.isaivazhi.app.ui.screens.NowPlayingScreen
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class Tab(val title: String) {
    Discover("Discover"),
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
    data object Debug : Overlay()
    data class PlaylistDetail(val playlistId: String) : Overlay()
    data class ViewAll(val category: BrowseCategory) : Overlay()
    /** Generic "View all" overlay for a Discover section. */
    data class SectionViewAll(val title: String, val songs: List<Song>) : Overlay()
}

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Install crash handler EARLY so any subsequent uncaught exception
        // lands in the persisted Debug Logs file before the process dies.
        DebugLogCapture.installCrashHandler(applicationContext)

        container = AppContainer(applicationContext)

        CoroutineScope(Dispatchers.Default).launch {
            try { container.embeddingDb.migrateFromLegacy() }
            catch (t: Throwable) { android.util.Log.w("MainActivity", "embDb migrate failed: ${t.message}") }
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
        // Push #46: refresh the portable local_embeddings.json mirror
        // when the user backgrounds the app. Catches any drift between
        // the last batch's auto-export and "now" (e.g. embeddings that
        // landed via :ai recovery while the user was idle). Atomic
        // tmp+rename so a process kill while writing can't corrupt.
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
                runCatching { container.embeddingDb.exportLegacyMirror() }
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
    var scanError by remember { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(pageCount = { Tab.entries.size })
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
    var discoverQueue by remember { mutableStateOf<List<Song>>(emptyList()) }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    var libraryMenuOpen by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var menuPlaylistContext by remember { mutableStateOf<String?>(null) }  // playlist id for "Remove from playlist"
    // Push #42 Tier 2I: when the long-press came from the Songs tab,
    // expose a "Play in order" entry. From Discover cards / Albums
    // (where tap already plays a section), the entry is hidden.
    var menuSongsTabIndex by remember { mutableStateOf<Int?>(null) }
    var albumToExpand by remember { mutableStateOf<String?>(null) }
    // Push #62: album-level long-press menu state. When non-null, the
    // AlbumMenuSheet renders with Play / Shuffle / Delete actions.
    var albumMenuTracks by remember { mutableStateOf<List<Song>?>(null) }
    var albumMenuName by remember { mutableStateOf<String?>(null) }
    var showAlbumDeleteConfirm by remember { mutableStateOf(false) }

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
    // Push #63: decorated signals (chips + hard-block set). Recommender
    // call sites pass `hardBlockedFilenames` so strongly-negative songs
    // (top 18%, floor 1.5) never land in Up Next or Discover sections.
    val decoratedSignals by container.taste.decoratedSignals.collectAsState()
    val hardBlockedFilenames = decoratedSignals.hardBlockedFilenames
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

    var mostSimilar by remember { mutableStateOf<List<com.isaivazhi.app.engine.Recommender.ScoredSong>>(emptyList()) }
    var forYou by remember { mutableStateOf<List<com.isaivazhi.app.engine.Recommender.ScoredSong>>(emptyList()) }
    var becauseYouPlayed by remember { mutableStateOf<List<Pair<Song, List<com.isaivazhi.app.engine.Recommender.ScoredSong>>>>(emptyList()) }
    var unexploredClusters by remember { mutableStateOf<List<List<Song>>>(emptyList()) }
    var freezeMostSimilar by remember { mutableStateOf(false) }
    var frozenMostSimilar by remember { mutableStateOf<List<com.isaivazhi.app.engine.Recommender.ScoredSong>>(emptyList()) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            importMessage = "Importing…"
            val result = EmbeddingsImport.importFromUri(ctx, uri)
            if (!result.ok) { importMessage = "Import failed: ${result.error}"; return@launch }
            importMessage = "Imported ${result.bytesCopied / 1024} KB. Ingesting…"
            try {
                val migrated = container.embeddingDb.migrateFromLegacy()
                val rows = migrated?.optInt("rowCount", 0) ?: 0
                refreshDbStats(container) { rc, dim, ext, fns, fps ->
                    embeddingsRowCount = rc; embeddingsDim = dim; vecExtLoaded = ext
                    embeddedFilenames = fns
                    embeddedFilepaths = fps
                }
                importMessage = "Loaded $rows embeddings."
                onboardingDismissed = true
            } catch (t: Throwable) {
                importMessage = "Ingest failed: ${t.message}"
            }
        }
    }

    LaunchedEffect(permission.granted) {
        if (permission.granted) {
            try {
                songs = LibraryCache.loadOrScan(ctx)
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
            refreshDbStats(container) { rc, dim, ext, fns, fps ->
                embeddingsRowCount = rc; embeddingsDim = dim; vecExtLoaded = ext
                embeddedFilenames = fns
                embeddedFilepaths = fps
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
        // Push #65: ingest pending playback evidence BEFORE preparing
        // resume playback. Two sources, both belt-and-suspenders for the
        // "user closed the app mid-song" case where the in-memory
        // accumulator value would otherwise be lost:
        //   1. DataStore record written by MainActivity.onPause's
        //      flushCurrentPlayback.
        //   2. SharedPreferences record written by
        //      Media3PlaybackService.onTaskRemoved (force-kill path
        //      that may bypass onPause entirely).
        // Push #66 — Capacitor parity (`_recoverPendingListenIfNeeded` in
        // app.js:601). Three-path reconciliation:
        //   A. If a recent native transition's prevPlaybackInstanceId
        //      matches the pending snapshot's instanceId, the transition
        //      is authoritative — use ITS values (more accurate than
        //      our pre-transition guess). Clear the snapshot.
        //   B. If the same playback instance is still active in the
        //      service, defer — the eventual transition will record it
        //      naturally. Don't double-record now.
        //   C. Otherwise the service is gone and no transition fired —
        //      use the snapshot's playedMs/durationMs as-is.
        try {
            val transitionsJson = com.isaivazhi.app.Media3PlaybackService
                .readRecentTransitionsStatic(ctx)
            val recentTransitions = parseRecentTransitions(transitionsJson)
            val liveInstanceId = com.isaivazhi.app.Media3PlaybackService.INSTANCE?.let {
                runCatching { it.javaClass.getMethod("getCurrentPlaybackInstanceId").invoke(it) as? Long }
                    .getOrNull() ?: 0L
            } ?: 0L

            val pending = container.preferences.loadPendingEvidence()
            if (pending != null) {
                reconcilePending(
                    container = container,
                    songs = songs,
                    snapshot = pending,
                    recentTransitions = recentTransitions,
                    liveInstanceId = liveInstanceId,
                    originLabel = "datastore",
                )
                container.preferences.clearPendingEvidence()
            }
            // Service-side SharedPreferences fallback (force-kill via task swipe).
            val sp = ctx.getSharedPreferences("playback_pending_evidence", android.content.Context.MODE_PRIVATE)
            val svcMediaId = sp.getString("mediaId", "") ?: ""
            val svcPlayed = sp.getLong("playedMs", 0L)
            val svcDur = sp.getLong("durationMs", 0L)
            val svcInstId = sp.getLong("playbackInstanceId", 0L)
            if (svcMediaId.isNotBlank() && svcPlayed >= 1000L && svcDur > 0L) {
                reconcilePending(
                    container = container,
                    songs = songs,
                    snapshot = com.isaivazhi.app.engine.AppPreferences.PendingEvidence(
                        mediaId = svcMediaId,
                        playedMs = svcPlayed,
                        durationMs = svcDur,
                        playbackInstanceId = svcInstId,
                    ),
                    recentTransitions = recentTransitions,
                    liveInstanceId = liveInstanceId,
                    originLabel = "task_removed",
                )
                sp.edit().clear().apply()
            }
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

    // Most Similar tracks the CURRENT song's neighbors. Render layer picks
    // frozen vs live based on freezeMostSimilar. Separate effect from the
    // discoverQueue rebuild so a slow KNN query for the queue doesn't block
    // a fast similar-songs refresh on song change, AND so tuning.adventurous
    // moves (which DON'T affect this call) don't re-fire it unnecessarily.
    //
    // Critical: do NOT clear mostSimilar on transient null mediaId between
    // Media3 transitions — that caused the "vanish + come back" flicker
    // reported after push #34. Only legitimate state changes (empty library,
    // or a successful recommender result) update the variable.
    // Push #42 Tier 1C: keyed on tuning.adventurous so the Most Similar
    // section re-sorts when the user changes the slider. Was hardcoded
    // 0.1f — slider movement had no effect on this section.
    LaunchedEffect(playbackState.currentMediaId, embeddingsRowCount, tuning.adventurous) {
        val mediaId = playbackState.currentMediaId ?: return@LaunchedEffect
        if (songs.isEmpty()) {
            mostSimilar = emptyList()
            return@LaunchedEffect
        }
        // Push #39: debounce so rapid skip-next / skip-prev presses don't
        // fire the sqlite-vec query on every transition. The previous
        // result remains on screen for up to 250 ms before being replaced.
        kotlinx.coroutines.delay(250)
        val current = songs.firstOrNull { it.filename == mediaId } ?: return@LaunchedEffect
        val hasEmbeddings = (embeddingsRowCount ?: 0) > 0
        mostSimilar = if (hasEmbeddings) {
            runCatching {
                container.recommender.recommendScored(current, songs, k = 10, adventurous = tuning.adventurous)
            }.getOrDefault(emptyList())
        } else emptyList()
    }

    // Discover queue (consumed by Taste's Engine Snapshot card + the auto-
    // build path). Separate effect so adventurous changes here don't drag
    // mostSimilar along.
    LaunchedEffect(playbackState.currentMediaId, embeddingsRowCount, tuning.adventurous) {
        val mediaId = playbackState.currentMediaId
        if (mediaId == null || songs.isEmpty()) {
            discoverQueue = emptyList()
            return@LaunchedEffect
        }
        // Same 250 ms debounce — buildPlayQueue is even heavier than
        // recommendScored (MMR diversity over the top-3K candidates).
        kotlinx.coroutines.delay(250)
        val current = songs.firstOrNull { it.filename == mediaId } ?: return@LaunchedEffect
        val hasEmbeddings = (embeddingsRowCount ?: 0) > 0
        discoverQueue = if (hasEmbeddings) {
            try { container.recommender.buildPlayQueue(
                current, songs, k = 50, adventurous = tuning.adventurous,
                hardBlockedFilenames = hardBlockedFilenames,
            ) }
            catch (t: Throwable) { fallbackShuffleQueue(songs, current, k = 50) }
        } else fallbackShuffleQueue(songs, current, k = 50)
    }

    // For You / Because You Played / Unexplored populate from the first
    // available data. We re-trigger on `qualifyingEventCount` (number of
    // history events with fractionPlayed ≥ 0.3f) rather than raw .size so
    // a fresh user sees BYP show up immediately after their first
    // qualifying listen. The `.isEmpty()` guards prevent clobbering an
    // already-populated section — once a section has content it only
    // refreshes via pull-to-refresh on Discover.
    val qualifyingEventCount = remember(historyEvents) {
        historyEvents.count { it.fractionPlayed >= 0.3f }
    }
    // Push #42 Tier 1C: keyed on all 3 tuning knobs + drop the .isEmpty()
    // gates so each slider change re-runs the recommender for these
    // sections. Result: sessionBias + negativeStrength are no longer
    // dormant — moving any slider visibly re-sorts Discover sections.
    // 250 ms debounce prevents thrashing during slider drags.
    // Push #64: subscribe to the session "qualified listens" counter so
    // we can auto-refresh For You after every 5 non-skip listens within
    // the current session. Capacitor parity (`tickForYouListenWindow`).
    val forYouTick by container.session.forYouTick.collectAsState()
    // Push #67: immediate rebuild trigger on favorite/dislike toggle.
    // SharedFlow emits a reason string; LaunchedEffect re-fires the
    // Discover builders and prepends fresh recommendations to Up Next.
    var rebuildPulse by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        container.rebuildSignal.collect { reason ->
            android.util.Log.i("MainActivity", "rebuildSignal received: $reason → triggering refresh")
            rebuildPulse++
        }
    }

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
    // Fingerprint = sorted top-30 filenames joined; if it changes,
    // recompute via forYouByProfileVector helper (which already builds
    // the centroid internally) — but we need the raw vec, so use a
    // dedicated helper. For now we compute inline.
    LaunchedEffect(historyStats.size, embeddingsRowCount, rebuildPulse) {
        if (songs.isEmpty()) return@LaunchedEffect
        if ((embeddingsRowCount ?: 0) == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        val byFilename = songs.associateBy { it.filename }
        val topPlays = historyStats.entries
            .asSequence()
            .filter { it.value.plays > 0 && it.value.avgFraction >= 0.3f }
            .filter { it.key !in dislikedSet }
            .filter { it.key !in hardBlockedFilenames }
            .sortedByDescending { it.value.plays * it.value.avgFraction }
            .take(30)
            .toList()
        if (topPlays.isEmpty()) return@LaunchedEffect
        val anchorSongs = topPlays.mapNotNull { byFilename[it.key] }
            .filter { !it.contentHash.isNullOrBlank() && it.filePath in embeddedFilepaths }
        if (anchorSongs.isEmpty()) return@LaunchedEffect
        val fingerprint = anchorSongs.map { it.filename }.sorted().joinToString("|").hashCode().toString()
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
    val discoverCacheState by container.discoverCache.snapshot.collectAsState()
    val discoverCacheLoaded by container.discoverCache.loaded.collectAsState()
    // Push #42 Tier 1C: keyed on all 3 tuning knobs + drop the .isEmpty()
    // gates so each slider change re-runs the recommender for these
    // sections. Result: sessionBias + negativeStrength are no longer
    // dormant — moving any slider visibly re-sorts Discover sections.
    // 250 ms debounce prevents thrashing during slider drags.
    //
    // Push #64: forYouTick / 5 → integer division ramps each time the
    // user crosses a 5-listen boundary; including it in the key list
    // triggers an auto-refresh of For You (and the cluster pages too,
    // cheap). The session.resetForYouTick() call closes the window so
    // the next 5 listens start a new one.
    // Push #70: manual refresh trigger for Unexplored Sounds. Incremented
    // only by pull-to-refresh in DiscoverScreen.onRefresh. The Unexplored
    // LE keys on this so it does NOT recompute on every qualified listen.
    var unexploredManualRefreshCounter by remember { mutableStateOf(0) }

    // Push #70 — For You + Because You Played LE (the "listen-responsive"
    // sections). Dropped `qualifyingEventCount` from the key set because
    // it changed on every qualified listen, which thrashed the LE. We
    // now rely on `forYouTick / 5` (intentional 5-listen window) and
    // `historyStats.size` (which only changes when a brand-new song is
    // added to history). `rebuildPulse` fires on favorite/dislike toggle.
    LaunchedEffect(
        historyStats.size, songs.isNotEmpty(), embeddingsRowCount,
        tuning.adventurous, tuning.sessionBias, tuning.negativeStrength,
        forYouTick / 5,
        rebuildPulse,
    ) {
        val hasEmbeddings = (embeddingsRowCount ?: 0) > 0
        if (songs.isEmpty() || !hasEmbeddings) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        val randomize = forYouTick >= 5
        val tStart = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i(
            "DiscoverLE",
            "ForYou+BYP fire: historyStats=${historyStats.size} forYouTick=$forYouTick(/5=${forYouTick / 5}) rebuildPulse=$rebuildPulse randomize=$randomize"
        )
        forYou = runCatching {
            val pv = container.recommender.forYouByProfileVector(
                songs, historyStats, embeddedFilepaths, dislikedSet,
                hardBlockedFilenames = hardBlockedFilenames,
                k = 12, randomize = randomize,
            )
            if (pv.isNotEmpty()) pv
            else container.recommender.forYou(
                songs, historyStats, tuning, dislikedSet,
                hardBlockedFilenames = hardBlockedFilenames, k = 12, randomize = randomize,
            )
        }.getOrDefault(forYou)
        val qualifyingRecent = historyEvents.count { it.fractionPlayed >= 0.3f }
        becauseYouPlayed = runCatching {
            container.recommender.becauseYouPlayed(
                songs, historyEvents, tuning, dislikedSet,
                hardBlockedFilenames = hardBlockedFilenames,
                statsFallback = historyStats, kPerSource = 8, sourceCount = 3,
                randomize = randomize,
            )
        }.getOrDefault(becauseYouPlayed)
        val builtMs = android.os.SystemClock.elapsedRealtime() - tStart
        android.util.Log.i(
            "DiscoverLE",
            "ForYou+BYP done: forYou=${forYou.size} BYP=${becauseYouPlayed.size}/${becauseYouPlayed.sumOf { it.second.size }} qualifyingRecent=$qualifyingRecent (took ${builtMs}ms)"
        )
        if (becauseYouPlayed.isEmpty()) {
            android.util.Log.w(
                "DiscoverLE",
                "BYP empty: qualifyingHistoryEvents=$qualifyingRecent — need more listened songs at >=30%"
            )
        }
        if (randomize) container.session.resetForYouTick()
        container.discoverCache.save(
            mostSimilarFilenames = mostSimilar.map { it.song.filename },
            forYouFilenames = forYou.map { it.song.filename },
            byp = becauseYouPlayed.map { (src, sims) ->
                com.isaivazhi.app.engine.DiscoverCacheEngine.BypEntry(
                    anchorFilename = src.filename,
                    anchorTitle = src.title.ifBlank { src.filename },
                    recommendationFilenames = sims.map { it.song.filename },
                )
            },
            unexploredFilenamesByCluster = unexploredClusters.map { cl -> cl.map { it.filename } },
            currentMediaId = playbackState.currentMediaId,
            recommendationFingerprint = container.taste.recommendationFingerprint(),
        )
    }

    // Push #70 — Unexplored Clusters LE (separate, narrower keys). Only
    // recomputes on:
    //   • Cold start (songs.isNotEmpty() becomes true)
    //   • New embeddings landed (embeddingsRowCount changes)
    //   • User pulls to refresh (unexploredManualRefreshCounter ticks)
    // Does NOT recompute after every qualified listen, which was the
    // visible "Unexplored Sounds shuffles after every song" bug.
    LaunchedEffect(songs.isNotEmpty(), embeddingsRowCount, unexploredManualRefreshCounter) {
        val hasEmbeddings = (embeddingsRowCount ?: 0) > 0
        if (songs.isEmpty() || !hasEmbeddings) {
            android.util.Log.i(
                "DiscoverLE",
                "Unexplored SKIP: songs=${songs.size} hasEmbeddings=$hasEmbeddings"
            )
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(250)
        val tStart = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i(
            "DiscoverLE",
            "Unexplored fire: embeddingsRowCount=$embeddingsRowCount manualRefresh=$unexploredManualRefreshCounter"
        )
        unexploredClusters = runCatching {
            container.recommender.unexploredClustersKMeans(
                songs, historyStats, historyStats.keys, embeddedFilepaths,
                kPerCluster = 8, displayClusters = 3,
            )
        }.getOrDefault(unexploredClusters)
        val builtMs = android.os.SystemClock.elapsedRealtime() - tStart
        android.util.Log.i(
            "DiscoverLE",
            "Unexplored done: clusters=${unexploredClusters.size} totalSongs=${unexploredClusters.sumOf { it.size }} (took ${builtMs}ms)"
        )
    }

    // Push #64: on cold start, hydrate the Discover sections from the
    // persisted cache BEFORE the heavy LaunchedEffect above kicks in.
    // Runs once when the cache finishes loading and the library is
    // available. Sections still in `emptyList()` after hydration mean
    // either no cache yet or no library overlap.
    LaunchedEffect(discoverCacheLoaded, songs.isNotEmpty()) {
        if (!discoverCacheLoaded || songs.isEmpty()) return@LaunchedEffect
        val snap = discoverCacheState
        if (snap.computedAt == 0L) return@LaunchedEffect
        // Push #68: validate cache freshness via fingerprint comparison.
        // If the recommendation policy has materially shifted since the
        // cache was written (top ±30 reshuffled, hard-block set changed),
        // skip hydration and wait for the LE to recompute.
        val liveFingerprint = container.taste.recommendationFingerprint()
        if (snap.recommendationFingerprint.isNotBlank() &&
            snap.recommendationFingerprint != liveFingerprint
        ) {
            android.util.Log.i(
                "MainActivity",
                "Discover cache fingerprint mismatch — skipping hydration cached=${snap.recommendationFingerprint} live=$liveFingerprint"
            )
            return@LaunchedEffect
        }
        val byFn = songs.associateBy { it.filename }
        fun hydrate(fns: List<String>): List<Song> = fns.mapNotNull { byFn[it] }
        if (mostSimilar.isEmpty()) {
            mostSimilar = hydrate(snap.mostSimilarFilenames).map {
                com.isaivazhi.app.engine.Recommender.ScoredSong(it, 0f)
            }
        }
        if (forYou.isEmpty()) {
            forYou = hydrate(snap.forYouFilenames).map {
                com.isaivazhi.app.engine.Recommender.ScoredSong(it, 0f)
            }
        }
        if (becauseYouPlayed.isEmpty()) {
            becauseYouPlayed = snap.byp.mapNotNull { e ->
                val src = byFn[e.anchorFilename] ?: return@mapNotNull null
                val sims = hydrate(e.recommendationFilenames).map {
                    com.isaivazhi.app.engine.Recommender.ScoredSong(it, 0f)
                }
                if (sims.isEmpty()) null else src to sims
            }
        }
        if (unexploredClusters.isEmpty()) {
            unexploredClusters = snap.unexploredFilenamesByCluster.map { hydrate(it) }
                .filter { it.isNotEmpty() }
        }
    }

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
            val tail: List<Song> = if (recMode && hasEmbeddings) {
                runCatching {
                    container.recommender.buildPlayQueue(
                        currentSong = song,
                        library = songs,
                        k = 50,
                        adventurous = tuning.adventurous,
                        hardBlockedFilenames = hardBlockedFilenames,
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

    // Push #45 perf: REMOVED the push #42 Tier 3L soft-refresh
    // LaunchedEffect. It mutated the queue (`replaceUpcoming`) on every
    // qualified-listen transition, triggering a recomposition cascade
    // (Discover sections, Up Next, MiniPlayer all re-collected). Even
    // for non-qualifying skips (the common case in logs.txt: rapid
    // neutral_skip with frac=0), the LE still fired, allocated state,
    // and re-ran on the next transition. Plus, the replaceUpcoming it
    // triggered did an `associateBy` over the 2500-song library each
    // time. The user's preferred queue-stability model is "queue is
    // sacred between explicit user actions"; soft refresh contradicts
    // that. The pull-to-refresh button still gives fresh recommendations
    // on demand (push #44 randomized anchors).

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
            android.util.Log.i(
                "QueueExhaust",
                "skip: not on last item (curIdx=$curIdx of $queueSize, ctx=${playbackState.queueContext})"
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
            android.util.Log.i(
                "QueueExhaust",
                "skip AI append: section context $ctx (no AI ever)"
            )
            return@LaunchedEffect
        }
        // Respect repeat modes: ALL loops the section; ONE loops the song.
        // Both = no append. Only OFF gets the recommender tail.
        if (playbackState.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF) {
            android.util.Log.i(
                "QueueExhaust",
                "skip AI append: repeat=${playbackState.repeatMode} (OFF=0 ONE=1 ALL=2)"
            )
            return@LaunchedEffect
        }
        val current = songs.firstOrNull { it.filename == mediaId } ?: return@LaunchedEffect
        if ((embeddingsRowCount ?: 0) == 0) {
            android.util.Log.i("QueueExhaust", "skip AI append: no embeddings")
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
        val blendedVec = blendedTriple?.first
        val tail = runCatching {
            container.recommender.recommendUpcoming(
                currentSong = current,
                library = songs,
                k = 50,
                adventurous = tuning.adventurous,
                extraExcludeFilenames = excludeFns,
                hardBlockedFilenames = hardBlockedFilenames,
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
        val blendedVec = blendedTriple?.first ?: return@LaunchedEffect
        val newTail = runCatching {
            container.recommender.softRefreshUpcomingTail(
                currentSong = current,
                upcoming = upcomingSongs,
                library = songs,
                blendedQueryVec = blendedVec,
                adventurous = tuning.adventurous,
                extraExcludeFilenames = upcomingFilenames.toSet(),
                hardBlockedFilenames = hardBlockedFilenames,
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
    val isMenuSongEmbedded = menuSong?.filePath?.let { it in embeddedFilepaths } ?: false
    val menuSongStats = menuSong?.filename?.let { historyStats[it] }

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
                            DropdownMenuItem(text = { Text("AI / Embeddings") },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                                onClick = { libraryMenuOpen = false; overlay = Overlay.Ai })
                            DropdownMenuItem(text = { Text("Debug Logs") },
                                leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                                onClick = { libraryMenuOpen = false; overlay = Overlay.Debug })
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
                        aiMode = recMode,
                        // Push #60: include the empty-set guard so the MiniPlayer
                        // doesn't briefly flash a red dot for every song during
                        // the app-start window where the DB hasn't loaded yet
                        // (embeddedFilepaths starts empty). Matches the same
                        // guard used by SongsScreen / AlbumsScreen / Discover.
                        hasEmbedding = currentSongFilePath?.let {
                            embeddedFilepaths.isEmpty() || it in embeddedFilepaths
                        } ?: true,
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
                                            Tab.Discover -> Icons.Filled.Explore
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
                !permission.granted -> PermissionGateUi(onRequest = permission.request)
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
                        onImportEmbeddings = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        onEmbedInBackground = {
                            container.embedding.embedSongs(songs); onboardingDismissed = true
                        },
                        onContinueWithoutEmbeddings = { onboardingDismissed = true },
                    )
                    if (importMessage != null) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(text = importMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
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
                        Tab.Discover -> Column(modifier = Modifier.fillMaxSize()) {
                            EmbeddingStatusBanner(status = embeddingStatus, onStop = { container.embedding.stop() })
                            DiscoverScreen(
                                mostSimilar = if (freezeMostSimilar) frozenMostSimilar else mostSimilar,
                                forYou = forYou,
                                becauseYouPlayed = becauseYouPlayed,
                                unexploredClusters = unexploredClusters,
                                freezeMostSimilar = freezeMostSimilar,
                                onToggleFreeze = {
                                    if (freezeMostSimilar) freezeMostSimilar = false
                                    else { frozenMostSimilar = mostSimilar; freezeMostSimilar = true }
                                },
                                embeddingProgress = if (embeddingStatus.inProgress)
                                    "Embedding ${embeddingStatus.processed}/${embeddingStatus.total} " +
                                    (if (embeddingStatus.activeBackend.isNotBlank()) "• ${embeddingStatus.activeBackend} " else "") +
                                    (embeddingStatus.etaSeconds?.let { "• ~${it}s left" } ?: "")
                                else null,
                                currentMediaId = playbackState.currentMediaId,
                                embeddedFilepaths = embeddedFilepaths,
                                currentSongHasEmbedding = currentSongFilePath?.let {
                                    embeddedFilepaths.isEmpty() || it in embeddedFilepaths
                                } ?: true,
                                // Push #64: pass the same EngineSnapshot the
                                // Taste page uses so the Discover strip stays
                                // in sync with it. Tap → opens Taste.
                                engineSnapshot = buildEngineSnapshot(
                                    tuning = tuning,
                                    discoverQueueSize = playbackState.queueFilenames.size,
                                    aiMode = recMode,
                                    embeddingsCovered = embeddedFilepaths.size,
                                    embeddingsTotal = songs.count { it.filePath != null },
                                    nowPlayingTitle = playbackState.title,
                                    nowPlayingArtist = playbackState.artist,
                                ),
                                onOpenTaste = { overlay = Overlay.Taste },
                                onOpenAiPage = { overlay = Overlay.Ai },
                                // Push #42 Tier 2F: Discover card tap is a SECTION tap
                                // — find which section the tapped song belongs to and
                                // play that section (linearly from tapped, or shuffled
                                // if shuffle is on). Recommender appends a tail when
                                // the section exhausts (Tier 2G LE below).
                                onCardTap = { song ->
                                    val mostSimSongs = (if (freezeMostSimilar) frozenMostSimilar else mostSimilar).map { it.song }
                                    val fySongs = forYou.map { it.song }
                                    val bypMatch = becauseYouPlayed.firstOrNull { pair ->
                                        pair.second.any { it.song.filename == song.filename }
                                    }
                                    val unexploredCluster = unexploredClusters.firstOrNull { cluster ->
                                        cluster.any { it.filename == song.filename }
                                    }
                                    val (sectionSongs, idx, label) = when {
                                        mostSimSongs.any { it.filename == song.filename } ->
                                            Triple(mostSimSongs, mostSimSongs.indexOfFirst { it.filename == song.filename }, "MostSimilar")
                                        fySongs.any { it.filename == song.filename } ->
                                            Triple(fySongs, fySongs.indexOfFirst { it.filename == song.filename }, "ForYou")
                                        bypMatch != null -> {
                                            val bypSongs = bypMatch.second.map { it.song }
                                            Triple(bypSongs, bypSongs.indexOfFirst { it.filename == song.filename }, "BYP:${bypMatch.first.filename}")
                                        }
                                        unexploredCluster != null ->
                                            Triple(unexploredCluster, unexploredCluster.indexOfFirst { it.filename == song.filename }, "Unexplored")
                                        else -> Triple(listOf(song), 0, "Single")
                                    }
                                    playFromSection(
                                        sectionSongs, idx,
                                        com.isaivazhi.app.engine.PlaybackEngine.QueueContext.DISCOVER_SECTION,
                                        label,
                                    )
                                },
                                onSongLongPress = { menuPlaylistContext = null; menuSongsTabIndex = null; menuSong = it },
                                onRefresh = {
                                    val mediaId = playbackState.currentMediaId
                                    if (mediaId != null && (embeddingsRowCount ?: 0) > 0) {
                                        val current = songs.firstOrNull { it.filename == mediaId }
                                        if (current != null) {
                                            mostSimilar = runCatching {
                                                container.recommender.recommendScored(current, songs, k = 10, adventurous = tuning.adventurous)
                                            }.getOrElse { mostSimilar }
                                        }
                                    }
                                    // Push #44: randomize = true so pull-to-refresh
                                    // visibly shuffles the anchor pool. Previously
                                    // forYou + BYP were deterministic and returned
                                    // identical results every pull, making the
                                    // refresh look broken. .getOrElse keeps the
                                    // existing list on transient errors instead of
                                    // hiding the section.
                                    forYou = runCatching {
                                        // Push #64: profile-vector path with fallback.
                                        val pv = container.recommender.forYouByProfileVector(
                                            songs, historyStats, embeddedFilepaths, dislikedSet,
                                            hardBlockedFilenames = hardBlockedFilenames,
                                            k = 12, randomize = true,
                                        )
                                        if (pv.isNotEmpty()) pv
                                        else container.recommender.forYou(songs, historyStats, tuning, dislikedSet, hardBlockedFilenames = hardBlockedFilenames, k = 12, randomize = true)
                                    }.getOrElse { forYou }
                                    becauseYouPlayed = runCatching {
                                        container.recommender.becauseYouPlayed(songs, historyEvents, tuning, dislikedSet, hardBlockedFilenames = hardBlockedFilenames, statsFallback = historyStats, kPerSource = 8, sourceCount = 3, randomize = true)
                                    }.getOrElse { becauseYouPlayed }
                                    // Push #70: pull-to-refresh is the ONLY trigger
                                    // for Unexplored Sounds. Bumping the counter
                                    // re-fires the dedicated Unexplored LE which
                                    // now lives separately from the listen-driven
                                    // For You + BYP refresh.
                                    unexploredManualRefreshCounter++
                                    android.util.Log.i(
                                        "DiscoverLE",
                                        "Pull-to-refresh: tick Unexplored counter → $unexploredManualRefreshCounter"
                                    )
                                    // Pull-to-refresh resets the auto-refresh window.
                                    container.session.resetForYouTick()
                                    container.toaster.show("Recommendations refreshed")
                                },
                                onOpenSection = { ref ->
                                    val frozenOrLive = if (freezeMostSimilar) frozenMostSimilar else mostSimilar
                                    val (title, songsForSection) = when (ref) {
                                        DiscoverSectionRef.MostSimilar ->
                                            "Most Similar" to frozenOrLive.map { it.song }
                                        DiscoverSectionRef.ForYou ->
                                            "For You" to forYou.map { it.song }
                                        is DiscoverSectionRef.BecauseYouPlayed -> {
                                            val sims = becauseYouPlayed.firstOrNull { it.first.id == ref.sourceId }?.second.orEmpty()
                                            "Because you played ${ref.sourceTitle}" to sims.map { it.song }
                                        }
                                        is DiscoverSectionRef.Unexplored -> {
                                            val cluster = unexploredClusters.getOrNull(ref.clusterIndex).orEmpty()
                                            ref.label to cluster
                                        }
                                    }
                                    if (songsForSection.isNotEmpty()) {
                                        overlay = Overlay.SectionViewAll(title, songsForSection)
                                    }
                                },
                                contentPadding = PaddingValues(bottom = 8.dp),
                            )
                        }
                        Tab.Songs -> SongsScreen(
                            songs = songs,
                            currentMediaId = playbackState.currentMediaId,
                            embeddedFilepaths = embeddedFilepaths,
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
                            initialExpandedAlbum = albumToExpand.also { albumToExpand = null },
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
                            aiMode = recMode,
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
                                    val newTail: List<Song> = if (aiMode && (embeddingsRowCount ?: 0) > 0) {
                                        runCatching {
                                            container.recommender.recommendUpcoming(
                                                current, songs, k = 50,
                                                adventurous = tuning.adventurous,
                                                extraExcludeFilenames = excludeFns,
                                                hardBlockedFilenames = hardBlockedFilenames,
                                            )
                                        }.getOrDefault(emptyList())
                                    } else {
                                        songs.filter { it.filePath != null && it.filename !in excludeFns }
                                            .shuffled().take(50)
                                    }
                                    val finalUpcoming = playNextSongs + newTail
                                    android.util.Log.i(
                                        "QueueOp",
                                        "UpNext toggle aiMode=$aiMode: preserved ${playNextSongs.size} Play Next + ${newTail.size} new"
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
                            onRefresh = {
                                val mediaId = playbackState.currentMediaId ?: return@UpNextScreen
                                val current = songs.firstOrNull { it.filename == mediaId } ?: return@UpNextScreen
                                coroutineScope.launch {
                                    // Push #70: refresh button rebuilds AI tail
                                    // but PRESERVES Play Next songs at the front
                                    // of the new upcoming list. Reset queue
                                    // context to AI_RECOMMENDED.
                                    val playNextSet = playbackState.playNextFilenames
                                    val byFn = songs.associateBy { it.filename }
                                    val playNextSongs = playNextSet.mapNotNull { byFn[it] }
                                    val excludeFns = playNextSet + mediaId
                                    val newTail: List<Song> = if (recMode && (embeddingsRowCount ?: 0) > 0) {
                                        runCatching {
                                            container.recommender.recommendUpcoming(
                                                current, songs, k = 50,
                                                adventurous = tuning.adventurous,
                                                extraExcludeFilenames = excludeFns,
                                                hardBlockedFilenames = hardBlockedFilenames,
                                            )
                                        }.getOrDefault(emptyList())
                                    } else {
                                        songs.filter { it.filePath != null && it.filename !in excludeFns }
                                            .shuffled().take(50)
                                    }
                                    val finalUpcoming = playNextSongs + newTail
                                    android.util.Log.i(
                                        "QueueOp",
                                        "Refresh button: preserved ${playNextSongs.size} Play Next + ${newTail.size} AI"
                                    )
                                    if (finalUpcoming.isNotEmpty()) container.playback.replaceUpcoming(
                                        newUpcoming = finalUpcoming,
                                        newContext = com.isaivazhi.app.engine.PlaybackEngine.QueueContext.AI_RECOMMENDED,
                                    )
                                }
                            },
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
            songCount = songs.size,
            embeddingRows = embeddingsRowCount ?: 0,
            embeddingDimText = if (embeddingsDim > 0) "${embeddingsDim}-D" else "empty",
            vecExtensionLoaded = vecExtLoaded,
            artCacheBytes = artCacheBytes,
            onBack = { overlay = Overlay.None },
            onReimportEmbeddings = { importLauncher.launch(arrayOf("application/json", "*/*")) },
            onEmbedAllNow = { container.embedding.embedSongs(songs); overlay = Overlay.None },
            onRescanLibrary = {
                coroutineScope.launch { songs = LibraryCache.invalidate(ctx) }
                overlay = Overlay.None
            },
            onClearArtCache = {
                AlbumArtRepository.clearDiskCache(ctx)
                AlbumArtRepository.trimMemory()
                coroutineScope.launch { artCacheBytes = withContextIo { AlbumArtRepository.diskCacheBytes(ctx) } }
            },
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
            timelineEvents = timelineEvents,
            engineSnapshot = buildEngineSnapshot(
                tuning = tuning,
                discoverQueueSize = discoverQueue.size,
                aiMode = recMode,
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
                container.toaster.show("Skip strength: ${(it * 100).toInt()}%")
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
                container.toaster.show("Skip strength reset to ${(TasteEngine.DEFAULT_NEGATIVE_STRENGTH * 100).toInt()}%")
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
            logLines = embeddingLogs,
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
                container.toaster.show("Rescanning library…")
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
                    val msg = "Rescan: $beforeSongs → $afterSongs songs ($deltaText)"
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
            onClearLog = {
                container.embedding.logBuffer.clear()
                container.toaster.show("Logs cleared")
            },
            // Push #46: manual one-shot export of the SQLite embeddings
            // into a portable `local_embeddings.json` mirror file. Useful
            // right before a reinstall — copy the JSON off the device,
            // install the fresh APK, drop the JSON back, app's
            // migrateFromLegacyIfNeeded() ingests it on first launch.
            onExportBackup = {
                container.toaster.show("Exporting embeddings backup…")
                container.embedding.logBuffer.append("backup", "manual export started")
                coroutineScope.launch(Dispatchers.IO) {
                    val res = runCatching { container.embeddingDb.exportLegacyMirror() }.getOrNull()
                    val rows = res?.optInt("rowCount", 0) ?: 0
                    val bytes = res?.optLong("bytes", 0L) ?: 0L
                    val mb = bytes / 1024.0 / 1024.0
                    val mbStr = "%.1f".format(mb)
                    if (res != null) {
                        container.toaster.show("Backup ready — $rows rows, $mbStr MB")
                        container.embedding.logBuffer.append(
                            "backup",
                            "manual export complete — $rows rows, $bytes bytes ($mbStr MB)"
                        )
                    } else {
                        container.toaster.show("Backup failed")
                        container.embedding.logBuffer.append("backup", "manual export FAILED")
                    }
                }
            },
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

    AnimatedVisibility(visible = overlay is Overlay.Debug,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })) {
        DebugLogsScreen(onBack = { overlay = Overlay.None })
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
            )
        }
    }

    val sectionViewAll = overlay as? Overlay.SectionViewAll
    AnimatedVisibility(visible = sectionViewAll != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })) {
        if (sectionViewAll != null) {
            ViewAllScreen(
                title = sectionViewAll.title,
                songs = sectionViewAll.songs,
                currentMediaId = playbackState.currentMediaId,
                onBack = { overlay = Overlay.None },
                // Push #70: Discover section "View all" = DISCOVER_SECTION
                // context. AI tail appends on queue exhaust.
                onPlay = { q, idx ->
                    playFromSection(
                        q, idx,
                        com.isaivazhi.app.engine.PlaybackEngine.QueueContext.DISCOVER_SECTION,
                        sectionViewAll.title,
                    )
                },
                onLongPress = { menuPlaylistContext = null; menuSongsTabIndex = null; menuSong = it },
            )
        }
    }

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
            currentPlaylistName = playlistCtxName,
            songStats = menuSongStats,
            playlists = playlistsList,
            onDismiss = { menuSong = null; menuPlaylistContext = null },
            onPlayOnly = { container.playback.playOnly(sheetSong) },
            onPlayNext = { container.playback.playNext(sheetSong) },
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
            onCreatePlaylistAndAdd = {
                val pl = container.playlists.create("New playlist")
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
                coroutineScope.launch { pagerState.animateScrollToPage(Tab.Albums.ordinal) }
            },
            onToggleEmbedding = {
                // No engine-level "remove embedding" yet — best-effort: kick off
                // an embed for the song to ensure it lands in the DB. Removal
                // requires an EmbeddingDb.deleteByFilename method that doesn't
                // exist yet; toast-only for now.
                // Push #59: filepath-based embedded check (was filename).
                if (sheetSong.filePath != null && sheetSong.filePath !in embeddedFilepaths) {
                    container.embedding.embedSongs(listOf(sheetSong))
                }
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
        return
    }
    // Case C: no matching transition, no live session. Use snapshot as-is.
    android.util.Log.i(
        "MainActivity",
        "reconcilePending case=C (use snapshot) origin=$originLabel mediaId=${snapshot.mediaId} played=${snapshot.playedMs}ms"
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
}

private fun buildEngineSnapshot(
    tuning: com.isaivazhi.app.engine.TasteEngine.Tuning,
    discoverQueueSize: Int,
    aiMode: Boolean,
    embeddingsCovered: Int,
    embeddingsTotal: Int,
    nowPlayingTitle: String,
    nowPlayingArtist: String,
): EngineSnapshot {
    // Multi-timescale blend ratios. Same shape as the Capacitor app's getInsights():
    //   currentSong portion = 1 - sessionBias (when sessionBias=0 you're 100% current)
    //   session portion     = sessionBias * 0.7
    //   profile portion     = sessionBias * 0.3
    val currentPortion = (1f - tuning.sessionBias).coerceIn(0f, 1f)
    val sessionPortion = (tuning.sessionBias * 0.7f).coerceIn(0f, 1f)
    val profilePortion = (tuning.sessionBias * 0.3f).coerceIn(0f, 1f)
    val mode = when {
        tuning.sessionBias < 0.2f -> "Current-song heavy"
        tuning.sessionBias > 0.7f -> "Strong session blend"
        else -> "Balanced"
    }
    val pct: (Float) -> String = { v -> "${(v * 100).toInt()}%" }
    val coveragePct = if (embeddingsTotal > 0) (embeddingsCovered * 100 / embeddingsTotal).toString() + "%"
    else "0%"
    return EngineSnapshot(
        blendCurrent = pct(currentPortion),
        blendSession = pct(sessionPortion),
        blendProfile = pct(profilePortion),
        blendMode = mode,
        queueSize = discoverQueueSize,
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
        val stats = container.embeddingDb.stats()
        val filenames = container.embeddingDb.allFilenames()
        val filepaths = container.embeddingDb.allFilepaths()
        onResult(
            stats?.optInt("count", 0) ?: 0,
            stats?.optInt("dim", 0) ?: 0,
            stats?.optBoolean("vecExtensionLoaded", false) ?: false,
            filenames,
            filepaths,
        )
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
private fun PermissionGateUi(onRequest: () -> Unit) {
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
