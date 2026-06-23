package com.isaivazhi.app

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import com.isaivazhi.app.engine.AppContainer
import com.isaivazhi.app.engine.LibraryCache
import com.isaivazhi.app.ui.hasAudioReadPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-lifetime Application. Two responsibilities:
 *
 *  1. Hold a single shared [AppContainer] so MainActivity recreations
 *     (lockscreen unlock, config change, low-mem restore) reuse the same
 *     engines/preferences/embeddingDb facade instead of re-instantiating.
 *  2. Eagerly warm the EmbeddingDbManager worker the moment the process
 *     starts. The first DB call after a cold start pays ~20s of
 *     sqlite-vec / Room init cost on the single EmbeddingDbWorker
 *     HandlerThread. If we wait for MainActivity.onCreate or — worse —
 *     a lockscreen Refresh tap, the recommend pipeline ends up blocked
 *     behind that init. Firing a tiny `stats()` call here means the
 *     worker is warm by the time the user can interact.
 *
 * The warming launch is fire-and-forget on a SupervisorJob; any failure
 * is logged but never crashes the app — the next real DB call will
 * surface the error normally.
 */
class IsaiVazhiApp : Application() {

    lateinit var container: AppContainer
        private set

    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (!isDefaultProcess()) {
            android.util.Log.i(
                "IsaiVazhiApp",
                "Skipping main-process warmups in ${currentProcessName()}",
            )
            return
        }
        container.recommendationRefresh.start()
        container.queueContinuation.start()

        val startedAt = System.currentTimeMillis()
        container.activityLog.log(
            category = "engine",
            type = "PROCESS_START_WARM",
            message = "IsaiVazhiApp.onCreate — queuing eager DB warm",
        )
        warmScope.launch {
            try {
                // Step 1: stats() opens the DB connection and warms the
                // HandlerThread (~20s sqlite-vec init cost).
                container.embeddingDb.stats()
                val statsElapsed = System.currentTimeMillis() - startedAt
                container.activityLog.log(
                    category = "engine",
                    type = "PROCESS_WARM_STATS",
                    message = "embeddingDb stats warm in ${statsElapsed}ms",
                    data = mapOf("elapsedMs" to statsElapsed),
                )
                // Step 2: fire a nearestNeighbors query on the last-known
                // song. This exercises the sqlite-vec virtual table and
                // loads the mmap'd vector index pages into memory. Without
                // this, the OS evicts those pages when the app backgrounds
                // (lockscreen) and the first Refresh tap costs ~23s to
                // reload them. Use k=5 — enough to touch the index pages
                // without wasting time on scoring 2000+ rows.
                val snapshot = container.preferences.snapshot()
                val seedFilename = snapshot.mediaId
                    ?: snapshot.queueFilenames.firstOrNull()
                if (!seedFilename.isNullOrBlank()) {
                    container.embeddingDb.nearestNeighborsForFilename(seedFilename, 5)
                    val vecElapsed = System.currentTimeMillis() - startedAt
                    container.activityLog.log(
                        category = "engine",
                        type = "PROCESS_WARM_DONE",
                        message = "embeddingDb vector index warm done in ${vecElapsed}ms (seed=$seedFilename)",
                        data = mapOf("elapsedMs" to vecElapsed, "seed" to seedFilename),
                    )
                } else {
                    container.activityLog.log(
                        category = "engine",
                        type = "PROCESS_WARM_DONE",
                        message = "embeddingDb warmed (no seed song available for vector warm)",
                        data = mapOf("elapsedMs" to statsElapsed),
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w("IsaiVazhiApp", "eager DB warm failed: ${t.message}")
            }
        }

        // Bugfix 2026-06-01d: load the song library into the container
        // and start the proactive RecommendationCache. The cache observes
        // playback-state song changes for the rest of the process
        // lifetime, so even when MainActivity is destroyed during a long
        // lockscreen the next song change still triggers a fresh
        // precompute. The lockscreen Refresh tap then serves from cache
        // instantly instead of waiting 20-25s for sqlite-vec mmap pages
        // to page back in.
        warmScope.launch {
            try {
                if (!hasAudioReadPermission(this@IsaiVazhiApp)) {
                    android.util.Log.i(
                        "IsaiVazhiApp",
                        "Skipping eager library load — audio permission not granted yet"
                    )
                    return@launch
                }
                val cached = LibraryCache.loadCached(this@IsaiVazhiApp, allowStale = true)
                val songs = cached?.songs ?: LibraryCache.loadOrScan(this@IsaiVazhiApp)
                container.library.value = songs
                // Bugfix 2026-06-01h: populate filename→hash and hash→meta
                // lookup tables so all DB vector calls can be served from
                // JVM heap (immune to OS mmap eviction on lockscreen).
                container.embeddingDb.prewarmFromLibrary(songs)
                container.activityLog.log(
                    category = "engine",
                    type = "PROCESS_LIBRARY_LOADED",
                    message = "Library loaded into container size=${songs.size} source=${LibraryCache.lastLoadSource}",
                    data = mapOf(
                        "size" to songs.size,
                        "source" to LibraryCache.lastLoadSource,
                        "cacheFresh" to (cached?.isFresh ?: true),
                        "cacheAgeMs" to (cached?.ageMs ?: 0L),
                    ),
                )
                // Bugfix 2026-06-01k: run full vector heap warm BEFORE
                // starting RecommendationCache, via direct-cursor path.
                // The 06-01j attempt logged VEC_HEAP_WARMED size=0 because
                // prewarmFromLibrary skipped every song (Song.contentHash
                // is null on objects from LibraryCache.loadOrScan, so the
                // warm had zero hashes to load). fullWarmFromDb queries
                // the embedding DB directly and decodes vec bytes straight
                // into the heap maps in a single Cursor pass (no JSON,
                // no base64, no chunking).
                val warmStart = System.currentTimeMillis()
                val loaded = runCatching { container.embeddingDb.fullWarmFromDb() }.getOrDefault(0)
                val warmMs = System.currentTimeMillis() - warmStart
                container.activityLog.log(
                    category = "engine",
                    type = "VEC_HEAP_WARMED",
                    message = "Full vector heap warm done size=$loaded in ${warmMs}ms",
                    data = mapOf("size" to loaded, "elapsedMs" to warmMs),
                )
                val enriched = container.embeddingDb.enrichSongsWithKnownHashes(container.library.value)
                if (enriched !== container.library.value) {
                    container.library.value = enriched
                    container.embeddingDb.prewarmFromLibrary(enriched)
                }
                container.recommendationCache.start()
                container.recommendationCache.precomputeNow(reason = "process_start")
            } catch (t: Throwable) {
                android.util.Log.w("IsaiVazhiApp", "library/precompute init failed: ${t.message}")
            }
        }
    }

    private fun isDefaultProcess(): Boolean = currentProcessName() == packageName

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = Process.myPid()
        val am = getSystemService(ActivityManager::class.java) ?: return packageName
        return am.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
            ?: packageName
    }
}
