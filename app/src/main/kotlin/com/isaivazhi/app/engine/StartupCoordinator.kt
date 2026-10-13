package com.isaivazhi.app.engine

import android.content.Context
import android.provider.MediaStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class StartupPhase {
    IDLE,
    SNAPSHOT_LOADING,
    UI_READY,
    EMBEDDINGS_LOADING,
    RECOMMENDATIONS_LOADING,
    READY,
    DEGRADED,
}

class StartupCoordinator(
    context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val store = StartupSnapshotStore(appContext)
    private val started = AtomicBoolean(false)
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val saveMutex = Mutex()

    private val _phase = MutableStateFlow(StartupPhase.IDLE)
    val phase: StateFlow<StartupPhase> = _phase.asStateFlow()

    private val _uiSnapshot = MutableStateFlow<StartupUiSnapshot?>(null)
    val uiSnapshot: StateFlow<StartupUiSnapshot?> = _uiSnapshot.asStateFlow()

    private val _refreshReady = MutableStateFlow(false)
    val refreshReady: StateFlow<Boolean> = _refreshReady.asStateFlow()

    private val _authoritativeUiReady = MutableStateFlow(false)
    val authoritativeUiReady: StateFlow<Boolean> = _authoritativeUiReady.asStateFlow()

    private val _restoredTab = MutableStateFlow(DEFAULT_TAB)
    val restoredTab: StateFlow<String> = _restoredTab.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { runStartup() }
        scope.launch(Dispatchers.IO) {
            for (ignored in saveRequests) {
                delay(SAVE_COALESCE_MS)
                saveCurrentSnapshot()
            }
        }
        startPersistenceObservers()
    }

    fun requestSave() {
        if (_authoritativeUiReady.value) saveRequests.trySend(Unit)
    }

    fun recordCurrentTab(tabName: String) {
        if (tabName.isBlank() || tabName == _restoredTab.value) return
        _restoredTab.value = tabName
        requestSave()
    }

    suspend fun flush() {
        if (_authoritativeUiReady.value) saveCurrentSnapshot()
    }

    fun clearSnapshot() {
        _uiSnapshot.value = null
        scope.launch(Dispatchers.IO) { store.clear() }
    }

    fun currentMediaStoreVersion(): String =
        runCatching { MediaStore.getVersion(appContext) }.getOrDefault("")

    private suspend fun runStartup() {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        _phase.value = StartupPhase.SNAPSHOT_LOADING
        // Media3 transport readiness is independent from AI readiness.
        container.playback.preWarm()
        val storedSnapshot = withContext(Dispatchers.IO) { store.load() }
        val restoredTab = storedSnapshot?.lastTab?.takeIf { it.isNotBlank() }
            ?: runCatching { container.preferences.loadLastTab() }.getOrDefault(DEFAULT_TAB)
                .ifBlank { DEFAULT_TAB }
        _restoredTab.value = restoredTab
        val snapshot = storedSnapshot?.copy(lastTab = restoredTab)
        if (snapshot != null) {
            _uiSnapshot.value = snapshot
            if (container.library.value.isEmpty() && snapshot.songs.isNotEmpty()) {
                container.library.value = snapshot.songs
            }
            container.playback.hydrateOptimistic(snapshot)
            scope.launch { restorePlayback(snapshot) }
        } else if (container.library.value.isEmpty()) {
            LibraryCache.loadCached(appContext, allowStale = true)?.songs?.let {
                container.library.value = it
            }
        }
        _phase.value = StartupPhase.UI_READY
        container.activityLog.log(
            category = "engine",
            type = "STARTUP_SNAPSHOT_READY",
            message = "Startup UI snapshot ${if (snapshot == null) "miss" else "loaded"} in " +
                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
            data = mapOf(
                "songs" to (snapshot?.songs?.size ?: 0),
                "queue" to (snapshot?.queueFilenames?.size ?: 0),
            ),
        )

        // Let Compose present the cached page before allocating/decoding the
        // ~6 MB vector heap. This keeps snapshot rendering ahead of AI work.
        delay(FIRST_CONTENT_GRACE_MS)

        // DataStore engine hydration and IVZ decoding are independent. Running
        // them concurrently prevents slow preference migrations from sitting
        // in front of vector readiness on cold start.
        val authoritativeHydration = scope.launch(Dispatchers.IO) {
            coroutineScope {
                launch { container.history.awaitReady() }
                launch { container.favorites.awaitReady() }
                launch { container.disliked.awaitReady() }
                launch { container.playlists.awaitReady() }
                launch { container.taste.awaitReady() }
                launch { container.loadRecommendationModePrefs() }
            }
            _authoritativeUiReady.value = true
            requestSave()
        }

        val cachedStats = runCatching { container.preferences.cachedEmbedStats() }
            .getOrDefault(AppPreferences.CachedEmbedStats(0, 0, false))
        val expectedRows = maxOf(cachedStats.rowCount, snapshot?.embeddingRowCount ?: 0)
        _phase.value = StartupPhase.EMBEDDINGS_LOADING
        val ivzWarm = container.embeddingDb.restoreHeapFromPortableBin(expectedRows)
        val warmCount: Int
        val warmSource: String
        if (ivzWarm.success) {
            warmCount = ivzWarm.count
            warmSource = ivzWarm.source
            runCatching {
                container.preferences.saveCachedEmbedStats(
                    rowCount = ivzWarm.count,
                    dim = ivzWarm.dim,
                    vecExt = cachedStats.vecExt,
                )
            }
        } else if (expectedRows > 0) {
            warmCount = runCatching { container.embeddingDb.fullWarmFromDb() }.getOrDefault(0)
            warmSource = "sqlite_fallback"
        } else {
            warmCount = 0
            warmSource = "no_embeddings"
        }

        val enriched = container.embeddingDb.enrichSongsWithKnownHashes(container.library.value)
        container.library.value = enriched
        container.embeddingDb.prewarmFromLibrary(enriched)

        authoritativeHydration.join()
        _phase.value = StartupPhase.RECOMMENDATIONS_LOADING
        container.recommendationCache.start()
        val precomputeFinished = if (container.playback.state.value.currentMediaId != null) {
            container.recommendationCache.precomputeAndAwait(
                reason = "startup",
                timeoutMs = 10_000L,
            )
        } else {
            true
        }
        _phase.value = if (expectedRows == 0 || warmCount > 0) {
            StartupPhase.READY
        } else {
            StartupPhase.DEGRADED
        }
        _refreshReady.value = _phase.value == StartupPhase.READY
        container.playback.setRefreshAvailable(_refreshReady.value)
        container.activityLog.log(
            category = "engine",
            type = "STARTUP_READY",
            message = "Startup ready source=$warmSource vectors=$warmCount precompute=$precomputeFinished",
            data = mapOf(
                "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - startedAt),
                "warmSource" to warmSource,
                "vectors" to warmCount,
                "precomputeFinished" to precomputeFinished,
            ),
        )

        // Live DB verification is deliberately after readiness: the validated
        // IVZ heap is sufficient for recommendations and avoids blocking UI.
        scope.launch(Dispatchers.IO) {
            runCatching { container.embeddingDb.stats() }.getOrNull()?.let { stats ->
                container.preferences.saveCachedEmbedStats(
                    rowCount = stats.optInt("count", expectedRows),
                    dim = stats.optInt("dim", cachedStats.dim),
                    vecExt = stats.optBoolean("vecExtensionLoaded", cachedStats.vecExt),
                )
            }
        }
        requestSave()
    }

    private fun startPersistenceObservers() {
        scope.launch { container.library.collect { requestSave() } }
        scope.launch { container.playback.state.collect { requestSave() } }
        scope.launch { container.history.events.collect { requestSave() } }
        scope.launch { container.history.stats.collect { requestSave() } }
        scope.launch { container.favorites.favorites.collect { requestSave() } }
        scope.launch { container.disliked.disliked.collect { requestSave() } }
        scope.launch { container.playlists.playlists.collect { requestSave() } }
    }

    private suspend fun saveCurrentSnapshot() = saveMutex.withLock {
        val songs = container.library.value
        if (songs.isEmpty()) return@withLock
        val playback = container.playback.state.value
        val preferencesSnapshot = runCatching { container.preferences.snapshot() }.getOrNull()
        val cachedStats = runCatching { container.preferences.cachedEmbedStats() }
            .getOrDefault(AppPreferences.CachedEmbedStats(0, 0, false))
        val stats = container.history.stats.value
        val events = container.history.events.value
        val favorites = container.favorites.favorites.value
        val disliked = container.disliked.disliked.value
        val cooldown = container.recentlySurfacedTracker.activeEntries()
        val snapshot = StartupUiSnapshot(
            savedAt = System.currentTimeMillis(),
            mediaStoreVersion = currentMediaStoreVersion(),
            lastTab = _restoredTab.value,
            songs = songs,
            currentMediaId = playback.currentMediaId ?: preferencesSnapshot?.mediaId,
            currentPositionMs = container.playback.livePosition.value,
            queueFilenames = playback.queueFilenames.ifEmpty {
                preferencesSnapshot?.queueFilenames.orEmpty()
            },
            queueIndex = playback.queueIndex,
            currentTitle = playback.title.ifBlank { preferencesSnapshot?.title.orEmpty() },
            currentArtist = playback.artist.ifBlank { preferencesSnapshot?.artist.orEmpty() },
            currentAlbum = playback.album.ifBlank { preferencesSnapshot?.album.orEmpty() },
            historyStats = stats,
            historyEvents = events,
            favorites = favorites,
            disliked = disliked,
            playlists = container.playlists.playlists.value.map {
                StartupPlaylist(it.id, it.name, it.songFilenames.toList(), it.updatedAt)
            },
            cooldownEntries = cooldown,
            browseCategoryFilenames = buildBrowseProjection(
                songs, stats, events, favorites, disliked, cooldown,
            ),
            embeddingRowCount = maxOf(cachedStats.rowCount, container.embeddingDb.vecCacheSize),
            embeddingDim = cachedStats.dim,
            vecExtensionLoaded = cachedStats.vecExt,
        )
        withContext(Dispatchers.IO) { store.save(snapshot) }
        _uiSnapshot.value = snapshot
    }

    private suspend fun restorePlayback(snapshot: StartupUiSnapshot) {
        val currentMediaId = snapshot.currentMediaId
        if (currentMediaId.isNullOrBlank()) {
            container.playback.markPlaybackRecoveryComplete()
            return
        }
        val service = com.isaivazhi.app.Media3PlaybackService.INSTANCE
        val serviceMediaId = service?.getCurrentMediaIdSnapshot().orEmpty()
        if (service != null && serviceMediaId.isNotBlank()) {
            container.playback.syncStateFromController(snapshot.songs)
            return
        }
        val byFilename = snapshot.songs.associateBy { it.filename }
        val queue = snapshot.queueFilenames.mapNotNull(byFilename::get).ifEmpty {
            listOfNotNull(byFilename[currentMediaId])
        }
        if (queue.isEmpty()) {
            container.playback.markPlaybackRecoveryComplete()
            return
        }
        val index = queue.indexOfFirst { it.filename == currentMediaId }.coerceAtLeast(0)
        container.playback.prepareForResume(queue, index, snapshot.currentPositionMs)
    }

    private fun buildBrowseProjection(
        songs: List<Song>,
        stats: Map<String, HistoryEngine.Stats>,
        events: List<HistoryEngine.Event>,
        favorites: Set<String>,
        disliked: Set<String>,
        cooldown: List<RecentlySurfacedTracker.ActiveEntry>,
    ): Map<String, List<String>> {
        val available = songs.associateBy { it.filename }
        return linkedMapOf(
            "MostPlayed" to stats.entries.filter { it.value.plays > 0 }
                .sortedByDescending { it.value.plays }.mapNotNull { available[it.key]?.filename },
            "RecentlyPlayed" to events.distinctBy { it.filename }
                .mapNotNull { available[it.filename]?.filename },
            "ResumesIn" to cooldown.mapNotNull { available[it.filename]?.filename },
            "NeverPlayed" to songs.filter {
                it.filePath != null && it.filename !in stats
            }.map { it.filename },
            "LastAdded" to songs.filter { it.filePath != null }
                .sortedByDescending { it.dateModified }.map { it.filename },
            "Favorites" to songs.filter { it.filename in favorites }.map { it.filename },
            "Disliked" to songs.filter { it.filename in disliked }.map { it.filename },
        )
    }

    companion object {
        private const val SAVE_COALESCE_MS = 200L
        private const val FIRST_CONTENT_GRACE_MS = 200L
        private const val DEFAULT_TAB = "Songs"
    }
}
