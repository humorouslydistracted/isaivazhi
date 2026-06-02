# Discover Startup Delay Diagnosis - Native Android

Date: 2026-05-31

Scope: `native/` Kotlin/Compose Android app only. The old JavaScript/Capacitor path (`src/app.js`) is not the active code path for this diagnosis.

## Executive Summary

The native app does not contain `STARTUP_AI_HYDRATION_DELAY_MS`, `preloadEmbeddingsEarly()`, or the JavaScript startup sequence previously identified in `src/app.js`. Startup for the native Discover page is controlled by:

- `MainActivity.onCreate()` creating `AppContainer`, pre-warming Media3, and composing `AppRoot`.
- The audio permission gate and `LibraryCache.loadOrScan()` producing `songs`.
- `PlaybackEngine` setting `playbackState.currentMediaId`, which is the exact gate for MiniPlayer visibility.
- `DiscoverCacheEngine.loaded` plus `songs.isNotEmpty()`, which is the exact gate for cached Discover hydration.
- `embeddingsRowCount > 0`, which gates fresh AI recommendations.

The most important finding is that cached Discover content still waits for the library list because the persisted Discover cache stores filenames, not complete renderable song metadata. That is intentional in the current design: cached filenames must be mapped to current `Song` objects before rendering.

The mini player delay is not directly caused by Discover AI work. The mini player returns early until `playbackState.currentMediaId != null` in `MiniPlayer.kt`. On startup, that field is populated only after the playback restore/sync path runs in `MainActivity` and `PlaybackEngine`.

## Native App Confirmation

The Kotlin native project is under `native/`.

- `native/app/build.gradle.kts`: `applicationId = "com.isaivazhi.app.kt"` so the Kotlin port installs alongside the existing Capacitor app during migration.
- `native/app/src/main/AndroidManifest.xml`: `MainActivity` is `com.isaivazhi.app.MainActivity`.
- `Media3PlaybackService` is in-process for latency. The manifest comment explicitly says the old Capacitor build used a separate `:playback` process, but native keeps playback in-process to avoid Binder latency.
- `EmbeddingForegroundService` runs in `:ai` with `stopWithTask="false"`, isolating ONNX/data-sync embedding work from the UI process.

## Startup Dependency Graph

### 1. App open

Source: `native/app/src/main/kotlin/com/isaivazhi/app/MainActivity.kt`

`MainActivity.onCreate()`:

1. Creates `AppContainer(applicationContext)`.
2. Logs whether this is process-cold or Activity recreation using `ACTIVITY_PROCESS_COLD` / `ACTIVITY_RECREATED`.
3. Starts `container.embeddingDb.migrateFromLegacy()` on `Dispatchers.Default`.
4. Calls `container.playback.preWarm()`.
5. Calls `setContent { AppRoot(container) }`.

Timing:

- `preWarm()` is asynchronous and does not block `setContent`.
- The controller connect time is not statically known. It depends on `MediaController.Builder(...).buildAsync()` completing.
- There is no fixed 1500 ms delay in the native startup path.

What it waits for:

- UI composition does not wait for playback prewarm or embedding DB migration.
- Playback state visibility later depends on Media3 controller/service state, not this initial call alone.

### 2. First Compose pass

Source: `MainActivity.kt`, `AppRoot`.

Important state collectors:

- `playbackState by container.playback.state.collectAsState()`.
- `embeddingStatus by container.embedding.status.collectAsState()`.
- `favorites`, `disliked`, `history`, `taste`, `signalTimeline`, and other engines.
- `discoverCacheState by container.discoverCache.snapshot.collectAsState()`.
- `discoverCacheLoaded by container.discoverCache.loaded.collectAsState()`.

Timing:

- Compose can draw structural UI immediately once permissions/onboarding gates allow it.
- `container.discoverCache` is lazy. It starts loading when the collector first touches it during composition.

What it waits for:

- The main page content waits for audio permission and a non-empty `songs` list.
- Cached Discover hydration waits for both `discoverCacheLoaded` and `songs.isNotEmpty()`.

### 3. Library load gate

Source: `MainActivity.kt` `LaunchedEffect(permission.granted)` and `LibraryCache.kt`.

When audio permission is granted:

1. Logs `LIBRARY_LOAD_START`.
2. Calls `songs = LibraryCache.loadOrScan(ctx)`.
3. Logs `LIBRARY_LOAD_DONE` with elapsed ms and song count.
4. Starts album art prefetch in IO.

`LibraryCache.loadOrScan()`:

- Reads `<externalFilesDir>/song_library.json` if present and fresh.
- Cache freshness TTL is 6 hours.
- If missing/stale, runs `LibraryScanner.scan(ctx)` and writes the cache.

Timing:

- Runtime measured by `LIBRARY_LOAD_DONE.elapsedMs`.
- If cache is fresh, this should be file JSON parse time.
- If cache is stale/missing, this can become a MediaStore/filesystem scan and may be seconds.

What it waits for and why:

- Discover cache hydration waits for `songs` because the persisted Discover snapshot stores filenames, not full `Song` objects.
- The code comment explicitly identifies this as the gate that can hold back Discover cache hydration.

### 4. Playback restore / mini player gate

Sources:

- `MainActivity.kt` `LaunchedEffect(songs)`.
- `PlaybackEngine.kt`.
- `MiniPlayer.kt`.

Exact UI gate:

```kotlin
if (state.currentMediaId == null) return
```

This is in `MiniPlayer.kt`. The bottom bar always calls `MiniPlayer(...)`, but `MiniPlayer` renders nothing until `playbackState.currentMediaId` is non-null.

How `currentMediaId` becomes non-null:

1. `LaunchedEffect(songs)` runs after `songs.isNotEmpty()`.
2. It waits for engine state readiness: taste, signal timeline, history, activity log, favorites.
3. It reconciles pending playback evidence and service transition buffers.
4. It checks whether `Media3PlaybackService.INSTANCE` is alive and has an active playback session.
5. If the service is alive with a valid media ID and playback instance ID, it skips destructive restore and calls `container.playback.syncStateFromController(songs)`.
6. If the service is not alive, it reads `container.preferences.snapshot()` and calls `container.playback.prepareForResume(queue, resolvedIndex, snap.positionMs)`.

Timing:

- The visible mini player delay is the time from Activity open until `_state.currentMediaId` is set by `syncStateFromController()` or `prepareForResume()`.
- `syncStateFromController()` awaits `ensureController()`, then reads the live Media3 queue/current item.
- `prepareForResume()` launches a coroutine, awaits `ensureController()`, sends a queue command to the service, then updates `_state.currentMediaId`.
- There is no log that directly measures "app open to first non-null `currentMediaId`"; that should be added before changing behavior.

What it waits for and why:

- It waits for `songs` because playback restore maps saved/live filenames to `Song` metadata and queue entries.
- It waits for engine readiness and evidence reconciliation to avoid losing playback/taste history during process death or background playback.
- It checks the live service before DataStore restore because `prepareForResume()` destructively replaces Media3's queue. The comment says this avoids clobbering headphone-skip/background auto-advance state.

Risk note:

- Moving restore earlier without understanding the service-live path can regress playback correctness. The code explicitly prevents DataStore restore from overwriting live service state.

### 5. Discover cache load

Source: `DiscoverCacheEngine.kt`.

On initialization:

1. Launches an IO coroutine.
2. Reads `discover_cache_v1_json` from `dataStoreLocal`.
3. Parses it into `Snapshot`.
4. Sets `_loaded.value = true`.
5. Logs `DISCOVER_CACHE_LOAD` with `loadElapsedMs`, counts, age, and fingerprint.

Timing:

- Runtime measured by `DISCOVER_CACHE_LOAD.loadElapsedMs`.
- It is independent of embeddings.

What it waits for and why:

- It waits only for DataStore read/parse.
- It does not wait for playback restore.
- It cannot render by itself because it stores filename lists only.

### 6. Discover cache hydration gate

Source: `MainActivity.kt` `LaunchedEffect(discoverCacheLoaded, songs.isNotEmpty())`.

Exact gate:

```kotlin
if (!discoverCacheLoaded || songs.isEmpty()) return
```

Additional gates:

- Snapshot must exist: `snap.computedAt != 0L`.
- Fingerprint must match: `snap.recommendationFingerprint == container.taste.recommendationFingerprint()` when a cached fingerprint exists.
- Sections hydrate only if their current lists are empty.

What resolves it:

- `discoverCacheLoaded` resolves in `DiscoverCacheEngine` after DataStore read completes.
- `songs.isNotEmpty()` resolves after `LibraryCache.loadOrScan(ctx)` completes.

Timing:

- Hydration itself is mostly in-memory filename-to-song mapping.
- The wait before hydration is `max(discover cache load time, library load time)`, plus any fingerprint dependency readiness needed by `taste.recommendationFingerprint()`.

Why cached content is not immediate:

- Cache entries are filenames. Rendering cards requires current `Song` objects for title, artist, album, file path, content hash, and embedding/file membership.
- Therefore the cache cannot populate Discover until the library is loaded.

### 7. Embedding DB stats gate

Source: `MainActivity.kt` `LaunchedEffect(permission.granted, songs.size)` and `refreshDbStats()`.

When `songs` is non-empty and `embeddingsRowCount == null`:

1. Logs `PERF_DB_START`.
2. Calls `refreshDbStats(container)`.
3. `refreshDbStats()` launches IO work to read:
   - `container.embeddingDb.stats()`
   - `container.embeddingDb.allFilenames()`
   - `container.embeddingDb.allFilepaths()`
4. Callback updates `embeddingsRowCount`, `embeddedFilenames`, and `embeddedFilepaths`.
5. Logs `PERF_DB_DONE` with elapsed ms.

Timing:

- Runtime measured by `PERF_DB_DONE.elapsedMs`.
- This does not block cached Discover hydration.
- It does gate fresh AI sections.

What it waits for and why:

- Fresh recommender queries need to know whether embeddings exist and which files are embedded.
- The UI also uses `embeddedFilepaths` to show embedding status/dots correctly.

### 8. Fresh Discover computation

Sources: `MainActivity.kt`, `DiscoverScreen.kt`.

Fresh Most Similar:

- Keyed by `playbackState.currentMediaId`, `embeddingsRowCount`, and `tuning.adventurous`.
- Returns immediately if no current media ID or no songs.
- Has a fixed 250 ms debounce before `recommender.recommendScored(...)`.

Fresh Discover queue:

- Same key shape.
- Has a fixed 250 ms debounce before `recommender.buildPlayQueue(...)` or fallback shuffle.

Fresh For You / Because You Played:

- Keyed by `songs.isNotEmpty()`, `embeddingsRowCount`, tuning knobs, `forYouTick / 5`, and `rebuildPulse`.
- Requires `songs.isNotEmpty()` and `embeddingsRowCount > 0`.
- Has a fixed 250 ms debounce.
- Logs `DISCOVER_FORYOU_FIRE` and `DISCOVER_FORYOU_DONE` with elapsed ms.
- Saves Discover cache after building.

Fresh Unexplored:

- Keyed by `songs.isNotEmpty()`, `embeddingsRowCount`, and manual refresh counter.
- Requires `songs.isNotEmpty()` and `embeddingsRowCount > 0`.
- Has a fixed 250 ms debounce.
- Logs `DISCOVER_UNEXP_FIRE` and `DISCOVER_UNEXP_DONE` with elapsed ms.

Profile vector:

- Requires `songs.isNotEmpty()` and `embeddingsRowCount > 0`.
- Has a fixed 300 ms debounce before rebuilding/saving `profileVec`.
- This supports recommendation quality, but is not required for cached Discover hydration.

Timing:

- Known fixed waits: 250 ms for Most Similar, queue, For You/BYP, and Unexplored; 300 ms for profile vector rebuild.
- Recommender query time is runtime-dependent and logged for For You/BYP and Unexplored.

What it waits for and why:

- These effects wait for embeddings and taste/history state because they perform actual AI/recommender work.
- They should not be treated as the mini-player gate.

## Investigation: 1500 ms Delay

Native result: not present.

Searches in `native/` found no:

- `STARTUP_AI_HYDRATION_DELAY_MS`
- `preloadEmbeddingsEarly`
- `delay(1500)`
- AI hydration delay equivalent

The only relevant `1500` hits are unrelated logcat/debug or service plausibility constants. Therefore the 1500 ms delay belongs to the old JavaScript/Capacitor path, not this native Kotlin path.

## Investigation: Embedding Preload Placement

Native result: there is no `preloadEmbeddingsEarly()` equivalent.

Native embeddings are DB-backed through `embeddingDb` and `refreshDbStats()`. Embedding inference is isolated in `EmbeddingForegroundService` under the `:ai` process. The startup path does not appear to load a large embedding model into the UI process before Discover.

The code does intentionally defer embedding DB stats until after `songs` exists:

- The app needs library songs to make embedding membership meaningful in UI.
- Fresh Discover AI sections require `embeddingsRowCount > 0`.
- Cached Discover hydration does not require `embeddingsRowCount`.

No direct audio interruption risk was found from `refreshDbStats()` itself because it runs database reads on IO and embedding inference is isolated. However, there is a clear playback correctness risk around moving the playback restore sequence because the code avoids clobbering a live service queue.

## Exact Blocking Gates

Mini player:

- Gate: `PlaybackState.currentMediaId`.
- UI condition: `MiniPlayer.kt` returns immediately when `state.currentMediaId == null`.
- Resolver: `PlaybackEngine.syncStateFromController()` when service is alive, or `PlaybackEngine.prepareForResume()` when service is dead and DataStore has saved playback.
- Upstream dependency: `MainActivity` restore effect runs after `songs.isNotEmpty()`.

Discover page shell:

- Gate: permission granted, notification gate not blocking, no scan error, onboarding not shown.
- Resolver: permission state plus `songs` plus notification/onboarding state.

Cached Discover content:

- Gate: `discoverCacheLoaded && songs.isNotEmpty()`.
- Additional gates: non-empty snapshot, fingerprint match.
- Resolver: `DiscoverCacheEngine` DataStore load and `LibraryCache.loadOrScan()`.

Fresh Discover content:

- Gate: `songs.isNotEmpty() && embeddingsRowCount > 0`.
- Additional waits: fixed 250 ms debounce and recommender execution.
- Resolver: `LibraryCache.loadOrScan()` and `refreshDbStats()` callback.

Most Similar:

- Gate: `playbackState.currentMediaId != null`, `songs.isNotEmpty()`, and optionally embeddings.
- Additional wait: fixed 250 ms debounce.

## Cold Start vs Kill/Reopen While Music Is Playing

The code has two distinct startup paths.

Process-cold:

- Logged as `ACTIVITY_PROCESS_COLD`.
- `Media3PlaybackService.INSTANCE` may be null if the process/service is dead.
- The app uses `AppPreferences.snapshot()` and `prepareForResume(...)` to rebuild the queue without autoplay.
- Mini player appears only after `prepareForResume()` updates `_state.currentMediaId`.

Process alive / Activity recreated:

- Logged as `ACTIVITY_RECREATED`.
- `Media3PlaybackService.INSTANCE` may exist with current media ID and playback instance ID.
- The app skips `prepareForResume()` and calls `syncStateFromController(songs)`.
- This is intentional because `prepareForResume()` would replace the live Media3 queue and could clobber background headphone skips or auto-advances.

Killed from recents while music continues:

- Because `Media3PlaybackService` is in-process, exact behavior depends on whether Android keeps the foreground media service/process alive.
- If the service remains alive, the service-live path should sync from controller.
- If the process is actually killed, the DataStore restore path is used.
- The embedding foreground service is a separate `:ai` process with `stopWithTask=false`, but that does not make the playback service separate.

Expected delay difference:

- Service-live reopen should avoid full queue reconstruction and should be faster once `songs` is loaded.
- True process-cold reopen must read DataStore and rebuild the queue, so it can be slower.
- Both paths currently wait for `songs` before setting the mini player's `currentMediaId`, so a slow library load can affect both.

## Current Runtime Instrumentation

Already present:

- `ACTIVITY_PROCESS_COLD` / `ACTIVITY_RECREATED`: identifies cold process vs Activity recreation.
- `LIBRARY_LOAD_START` / `LIBRARY_LOAD_DONE`: measures `LibraryCache.loadOrScan()`.
- `PERF_DB_START` / `PERF_DB_DONE`: measures embedding DB stats.
- `DISCOVER_CACHE_LOAD`: measures Discover cache DataStore load.
- `DISCOVER_HYDRATE_SKIP` / `DISCOVER_HYDRATE_HIT`: identifies cache hydration behavior.
- `DISCOVER_FORYOU_FIRE` / `DISCOVER_FORYOU_DONE`: measures For You/BYP fresh build.
- `DISCOVER_UNEXP_FIRE` / `DISCOVER_UNEXP_DONE`: measures Unexplored fresh build.
- `prepareForResume...` and `syncStateFromController...`: log restore path selected.

Missing but needed before changing behavior:

- App-open timestamp to first non-null `playbackState.currentMediaId`.
- App-open timestamp to first MiniPlayer render.
- Time spent inside `ensureController()` from `preWarm()`, `syncStateFromController()`, and `prepareForResume()`.
- Whether `LaunchedEffect(songs)` is delaying playback restore behind evidence reconciliation on affected devices.
- Whether `LibraryCache.loadOrScan()` is using fresh cache or falling through to `LibraryScanner.scan(ctx)` during the 5-10 second delay.

## Safe, Targeted Next Steps

1. Add diagnostics before changing startup ordering:
   - Log elapsed time from `MainActivity.onCreate()` to first non-null `playbackState.currentMediaId`.
   - Log elapsed time from `MainActivity.onCreate()` to first `MiniPlayer` render.
   - Log `ensureController()` duration and caller path: `preWarm`, `syncStateFromController`, or `prepareForResume`.
   - Log whether `LibraryCache.loadOrScan()` used cache vs scan.

2. Verify whether the observed 5-10 seconds is library-bound:
   - If `LIBRARY_LOAD_DONE.elapsedMs` is close to the delay, cached Discover and mini player are both waiting on `songs`.
   - If library load is fast but mini player is late, focus on `LaunchedEffect(songs)` restore/evidence reconciliation or Media3 controller connection.
   - If mini player appears fast but Discover sections are late, focus on `DISCOVER_CACHE_LOAD`, `DISCOVER_HYDRATE_SKIP`, fingerprint mismatch, or `PERF_DB_DONE`.

3. Do not remove or parallelize playback restore logic yet:
   - The code explicitly checks the live service before `prepareForResume()` to avoid overwriting background playback state.
   - Any change here must preserve the service-live source-of-truth behavior.

4. If the delay is confirmed library-bound, consider safe design changes:
   - Store enough display metadata in `DiscoverCacheEngine.Snapshot` to render cached cards before `songs` is loaded, then reconcile with library later.
   - Add a tiny saved now-playing snapshot for MiniPlayer title/artist/art path so the mini player can render immediately while the controller/library restore completes.
   - Keep audio commands gated on the real Media3 controller; only make the initial UI render optimistic.

5. If the delay is confirmed controller-bound:
   - Measure `ensureController()` and service bind time first.
   - Keep `preWarm()` early.
   - Consider making service-live state sync happen as soon as controller is ready, but only if it can preserve the live-service queue and not rely on stale DataStore values.

6. If the delay is confirmed Discover-cache-bound:
   - Inspect `DISCOVER_HYDRATE_SKIP` reasons.
   - Fingerprint mismatches may intentionally skip stale recommendations.
   - A no-cache or stale-fingerprint state should show a cheap placeholder/last-known metadata rather than waiting for full AI recomputation.

## Bottom Line

For the native app, the old JS recommendation to remove a 1500 ms delay does not apply. The native delay is most likely one of:

- `LibraryCache.loadOrScan()` taking seconds before `songs` is available.
- Playback restore/sync waiting behind `LaunchedEffect(songs)` and engine reconciliation before setting `currentMediaId`.
- Discover cache hydration waiting for `songs` because the cache stores filenames only.
- Fresh Discover AI work waiting for `embeddingsRowCount` plus 250 ms debounce and recommender execution.

The safest next move is instrumentation, not parallelization. Specifically, measure app-open to first non-null `currentMediaId`, library cache path, controller connect time, and Discover hydrate result on the same failing cold-start/reopen scenarios.
