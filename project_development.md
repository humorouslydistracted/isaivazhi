# Project Development Log

Last updated: 2026-04-26 (in-flight: embedding/playback UI-lag stabilization pass remains active; the AI-page unmatched/orphaned cleanup confirm flow is now fixed in `src/app.js`, keeping the popup visible across embedding-detail rerenders and preventing the cleanup-open scroll jump to the top; emulator soak runs on the 12:27 debug APK against 24 freshly-pushed `.mp3` songs across two batches validated playback-during-embedding, `am stop-app` + `:ai` resume from `active_embedding_batch.json`, atomic `local_embeddings.bin` writes, and clean playback through HOME/recents-swipe/media-key cycles — no FATAL/ANR/duplicate-event/Media3 player errors observed and the originally-reported playback-glitches-during-embedding symptoms did not reproduce. One real bug isolated: when a batch completes in-foreground with active playback the native `embeddingComplete` event does not propagate to JS, leaving the new vectors in `pending_embeddings.json` until next cold start when the disk-load merge path recovers them cleanly. Details under "Latest Verified Changes")

`local_embedding_generator.py` created 2026-04-26 — laptop/local version of `colab_embedding_generator.py`. Runs on Windows with NVIDIA GPU (CUDA) or falls back to CPU. Set `SONGS_DIR` at the top of the file before running. Output and CLAP checkpoint are placed next to the script automatically.

Current live structural status: 2026-04-26 the `src/app.js` support split helper modules are still live, but later rollback/fix work has pushed the current tree back above the original split baseline. Start point was 5,229 lines; current live `src/app.js` is 5,282 lines (+53 / ~1.0% vs that start point). Six helper modules remain live: `src/app-debug.js` (71), `src/app-status-ui.js` (85), `src/app-art.js` (111), `src/app-playlists-ui.js` (154), `src/app-back-navigation.js` (95), and `src/app-browse-render.js` (100). `src/engine.js` remains unsplit at 6,984 lines.

Earlier 2026-04-25 (fresh-APK main-process stall diagnosis landed + duplicate native playback-event dedupe landed + embedding-status callback recovery/polling landed + stale mini-player state hardening landed + new debug APK built after targeted verification + earlier same-day batches still in scope: stable embedding-store v3 hardening + `Because You Played` native `:ai` nearest-neighbor lookup + dead same-process `embedSingleSong` removal + DBG report copy instrumentation + stale-APK drift diagnosis + full Android/web verification rerun against fresh APK + Option B implementation cross-checked against source + Media3/ExoPlayer playback migration + initial `:ai` embedding-process split + playback-aware embedding scheduler/backoff while keeping whole-song decode + native `:ai` nearest-neighbor RPC first slice + AndroidX Startup provider crash avoidance + Media3 playback start hotfix)

## How To Use This File

This file is the short source of truth for the current app state, the latest verified behavior, and the build steps.

Full historical notes and older landed batches now live in:
- `project_development_archive.md`

If documents ever disagree, prefer:
1. live code
2. this file
3. the archive only as background

## Build Steps

Use these for normal web + Android verification:

```powershell
npm.cmd run test:unit
npm.cmd run test:ui
npm.cmd run build
npx.cmd cap sync android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\android\gradlew.bat -p android testDebugUnitTest
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\android\gradlew.bat -p android :app:connectedDebugAndroidTest
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\android\gradlew.bat -p android assembleDebug
```

Use the app-scoped connected-test task above, not aggregate `connectedDebugAndroidTest`.
The aggregate task also walks Capacitor plugin-module `androidTest` variants, which are unrelated to app regression coverage here.

### Emulator Testing Workflow

This project is now tested in two emulator modes:
- automated Android connected smoke tests via `:app:connectedDebugAndroidTest`
- manual APK/runtime verification on the emulator itself after seeding real songs + embedding data

Current verified AVD:
- `Medium_Phone_API_36`

Manual emulator workflow used in this project:
1. Start the AVD from Android Studio `Device Manager` and wait for the Android home screen.
2. Rebuild and sync the app:
   - `npm.cmd run build`
   - `npx.cmd cap sync android`
   - `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\android\gradlew.bat -p android assembleDebug`
3. Install the APK to the emulator:
   - `adb install -r android\app\build\outputs\apk\debug\app-debug.apk`
4. Seed songs into shared storage:
   - emulator path: `/sdcard/songs_downloaded/`
5. Seed app embedding/library files into the app external-files area:
   - emulator path: `/sdcard/Android/data/com.isaivazhi.app/files/`
   - expected files:
     - `song_library.json`
     - `local_embeddings.bin`
     - `local_embeddings_meta.json`
     - `local_embeddings.json`
     - `pending_embeddings.json`
6. Launch the app and verify the target flow on the seeded emulator state.

Important seeding note:
- the backup library path used in this project expects songs under `/storage/emulated/0/songs_downloaded/...`
- if only a reduced subset of songs is copied to the emulator, `song_library.json` should be filtered to that subset for a clean seeded test; otherwise extra entries show up as missing/unmatched

What the emulator is good for here:
- real APK install / startup
- library scan and saved-library load
- embedding-store detection / merge
- playback-start and queue behavior
- AI page / recommendation / cleanup-flow verification
- startup restore / mini-player / saved-state checks

What still needs a real phone:
- Bluetooth / headset buttons
- OEM notification / lockscreen quirks
- battery-saver / task-killer / process-death behavior
- exact real-device performance and long background playback behavior

Last full-suite verification: 2026-04-25 (DBG button re-enabled in shipped UI; web build, JS unit tests, Android unit tests, full connected emulator tests, and debug APK all rerun against current source)
- `npm.cmd run test:unit` - OK (rerun 2026-04-25; 29 tests across 3 files in 4.5s)
- `npm.cmd run test:ui` - OK (rerun 2026-04-25; 4 Playwright smoke tests incl. loop UI in 39.6s)
- `npm.cmd run build` - OK (rerun 2026-04-25 after DBG-panel uncomment in `index.html`)
- `npx.cmd cap sync android` - OK (rerun 2026-04-25 after web bundle rebuild)
- `:app:testDebugUnitTest` - OK (rerun 2026-04-25; BUILD SUCCESSFUL in 18s)
- `:app:connectedDebugAndroidTest` - OK (rerun 2026-04-25 on `Medium_Phone_API_36` emulator; 5 connected tests in 1m 27s)
- `:app:assembleDebug` - BUILD SUCCESSFUL (rerun 2026-04-25 after `gradlew clean`; APK timestamp 2026-04-25 15:11, ~382.8 MB)

Most recent targeted verification: 2026-04-26 (after AI-page unmatched/orphaned cleanup confirm visibility + scroll fix)
- `npm.cmd run build` - OK (rerun 2026-04-26 after the `showEmbeddingDetail()` confirm-state / scroll-anchor patch)
- `npx.cmd playwright test tests/e2e/embedding-detail.regression.spec.js --config playwright.config.mjs` - OK (rerun 2026-04-26; focused AI cleanup regression passed in 31.7s)
- `npm.cmd run test:ui` - NOT fully green in the live tree as of 2026-04-26; existing unrelated seeded-restore bootstrap failure remains in `tests/e2e/app.smoke.spec.js` (`#npTitle` expected `Beta`, received `---`)
- `npm.cmd run test:unit` - not rerun in this pass
- `npx.cmd cap sync android` - not rerun in this pass
- `:app:testDebugUnitTest` - not rerun in this pass
- `:app:assembleDebug` - not rerun in this pass
- `:app:connectedDebugAndroidTest` - not rerun in this pass

Earlier targeted verification: 2026-04-26 (after live `src/app.js` embedding-progress UI-refresh dedupe)
- `npm.cmd run test:unit` - OK (rerun 2026-04-26; 30 tests across 3 files in 3.8s)
- `npm.cmd run test:ui` - OK (rerun 2026-04-26; 4 Playwright smoke tests in 39.3s)
- `npm.cmd run build` - OK (rerun 2026-04-26 after the JS embedding UI patch)
- `npx.cmd cap sync android` - OK (rerun 2026-04-26 after the rebuilt web bundle)
- `:app:testDebugUnitTest` - OK (rerun 2026-04-26; BUILD SUCCESSFUL in 8s)
- `:app:assembleDebug` - BUILD SUCCESSFUL (rerun 2026-04-26; APK timestamp 2026-04-26 01:07:09, ~365.7 MiB)
- `:app:connectedDebugAndroidTest` - not rerun this pass

Most recent structural-split verification: 2026-04-26 (after latest `src/app.js` slice: browse/search render helpers moved into `src/app-browse-render.js`)
- `npm.cmd run test:unit` - OK (rerun 2026-04-26; 30 tests across 3 files in 3.9s)
- `npm.cmd run test:ui` - OK (rerun 2026-04-26; 4 Playwright smoke tests in 40.8s)
- `npm.cmd run build` - OK (rerun 2026-04-26 after latest `app.js` split slice)
- `npx.cmd cap sync android` - OK (rerun 2026-04-26 after rebuilt web bundle)
- `:app:testDebugUnitTest` - OK (rerun 2026-04-26; BUILD SUCCESSFUL in 8s)
- `:app:assembleDebug` - BUILD SUCCESSFUL (rerun 2026-04-26; APK timestamp 2026-04-26 01:44:40, ~365.7 MiB)
- `:app:connectedDebugAndroidTest` - not rerun during these structural slices

APK output:
- `android/app/build/outputs/apk/debug/app-debug.apk`
- latest rebuild confirmed in this session: `2026-04-26 13:02:42`, ~366.6 MiB
- post-soak rebuild 2026-04-26 14:15: `npm.cmd run build` OK (vite 306ms), `npx.cmd cap sync android` OK (0.353s, 3 Capacitor plugins detected), `gradlew clean assembleDebug` BUILD SUCCESSFUL in 27s (169 tasks executed, 24 up-to-date — clean rebuild because the previous incremental `assembleDebug` had reused the existing 13:02 APK on the up-to-date check). Fresh APK timestamp: `2026-04-26 14:15:00`, 366.6 MiB at `android/app/build/outputs/apk/debug/app-debug.apk`. `npm.cmd run test:unit` was attempted first but **failed in `tests/engine.regression.test.js:223`** (1 of 30 tests) — the test expects `engine.onNativeAdvance(...)` second call with same `songId` + new `playbackInstanceId` to return `{duplicate: true}` but actual is `{needsSync: true, songInfo: {...}}`. This regressed between the last green test:unit run earlier today (2026-04-26 after the `src/app.js` embedding-progress UI-refresh dedupe — 30/30 passing) and now; the more recent AI-page cleanup popup landing only verified `npm.cmd run build` plus a focused Playwright spec, so the failure was not caught. User asked to skip tests and proceed with the build only this pass. Recommended follow-up: read `src/engine.js` `onNativeAdvance` to determine whether the return-shape change to `{needsSync, songInfo}` is intentional (then update the test) or accidental (then restore `{duplicate: true}`).

## Current Source Of Truth

### UI File Split Progress
- Structural split mode is active for `src/app.js` only. `src/engine.js` has not been split yet.
- Start-of-campaign sizes:
  - `src/app.js` - 5,229 lines
  - `src/engine.js` - 6,563 lines at campaign start
- Current live sizes:
  - `src/app.js` - 5,282 lines
  - `src/engine.js` - 6,984 lines
- Net status in the current live tree: `src/app.js` is now 53 lines larger than the original 5,229-line start point, so the earlier split reduction has been overtaken by later rollback/fix work.
- New live files created so far: 6
  - `src/app-debug.js` - 71 lines - debug logger, error summarizer, global error hooks
  - `src/app-status-ui.js` - 85 lines - status toast and recommendation rebuild status UI
  - `src/app-art.js` - 111 lines - album-art request queue and art fallback / recovery helpers
  - `src/app-playlists-ui.js` - 154 lines - playlist picker overlay and playlist CRUD modal helpers
  - `src/app-back-navigation.js` - 95 lines - Android back-button decision flow and sub-page close helpers
  - `src/app-browse-render.js` - 100 lines - search clear, song thumb, songs/albums render helpers, album expand/collapse
- Total lines now living in those six helper modules: 616.
- `src/app.js` still owns:
  - bootstrap order
  - `window._app`
  - `App.addListener(...)` lifecycle wiring
  - main orchestration and cross-module state
- Verification discipline during this split campaign:
  - every green boundary has passed `npm.cmd run test:unit`
  - every green boundary has passed `npm.cmd run test:ui`
  - every green boundary has passed `npm.cmd run build`
  - every green boundary has passed `npx.cmd cap sync android`
  - every green boundary has passed Android `:app:testDebugUnitTest`
  - every green boundary has passed Android `:app:assembleDebug`
  - `:app:connectedDebugAndroidTest` has not been rerun during these structural slices yet

### Regression Testing Status
- Phase 1 automated regression coverage now exists via `Vitest`.
- Phase 2 browser smoke coverage now exists via `Playwright`.
- Phase 3 Android-native regression coverage now exists via `Robolectric`.
- Phase 4 Android connected smoke coverage now exists via emulator-backed `androidTest`.
- Current automated coverage focuses on deterministic engine behavior:
  - favorites / dislikes mutex behavior (including `setFavoriteState` on a disliked song)
  - pending-listen snapshot normalize / merge / clear behavior
  - recovered-listen stat capture
  - filename-based playback restore
  - duplicate native queue-advance dedupe (`onNativeAdvance`) so repeated native transition events cannot misclassify the next song
  - engine reset persistence clearing
  - playlist CRUD lifecycle (create / rename / delete / add / remove, duplicate-name guards, initial-song seeding)
  - delete-song propagation (removes from favorites, playlists, queue; audio file delete call)
  - Up Next queue editing (`addToQueue` appends manual items, `playNext` dedupes, `removeFromQueue` drops slot and rejects invalid indices)
  - `setQueueShuffleEnabled` / `getQueueShuffleEnabled` round-trip
  - `playFromAlbum` ordered playback (album mode indicator, session label, remaining-track queue)
  - Discover cache `save` → `load` round-trip and `validateDiscoverCache` stale-section detection (including recommendation-fingerprint gating and song-id reference staleness)
  - `getTasteSignal` summary shape with no embedded rows present
- Current browser smoke coverage also includes:
  - loop UI cycles off → one → all → off with matching `MusicBridge.setLoopMode` mode values (0, 1, 2, 0)
  - AI embedding cleanup confirm visibility / rerender stability for unmatched and orphaned removal flows
- Current browser smoke coverage focuses on seeded web-app flows with a browser-side `MusicBridge` mock:
  - seeded restore on boot / mini-player rendering
  - songs-tab rendering and search filtering
  - song-details modal stats wiring
  - favorites toggle via song menu reflected in Browse tiles
- Current Android-native coverage focuses on service-side storage / recovery behavior:
  - recent playback-transition retrieval from native recovery prefs
  - current-song favorite toggle persistence in `CapacitorStorage`
  - dislike removal when favorite mutex is applied natively
  - saved playback-state clearing on native dismiss path support code
- Current Android connected smoke coverage focuses on real app UI rendered inside Android WebView on emulator:
  - seeded playback restore into the mini-player
  - songs-tab rendering and search filtering
  - song-menu favorite action
  - Browse favorites tile count update after the favorite action
  - native `favorite` media-action handling updates the mini-player heart, Browse favorites count, and `CapacitorStorage`
  - native `dismiss` media-action handling clears the mini-player and saved `playback_state`
  - real Media3 `:playback` start via generated WAV file, `setQueue`, and `isPlaying=true` state assertion
- Recommended next regression-testing layers for this codebase:
  - `Maestro` for actual Android notification-surface, lockscreen, background, and resume flows
- Highest-value next coverage:
  - Discover manual refresh behavior and stable cached-section behavior
  - actual notification-surface taps, background/resume recovery, and headset / lockscreen media controls
  - favorites / dislikes / direct-negative recommendation policy deeper than the current smoke path
- Pending coverage can be grouped by testability:
  - Fully automatable with more unit / browser / connected tests:
    - Discover pull-to-refresh, cached-section stability, and refresh invalidation rules
    - deeper recommendation-policy behavior for favorites, dislikes, and strong negative taste signals
    - playlist create / rename / delete / add / remove flows
    - album playback flows
    - `Up Next` queue editing flows
    - shuffle / loop behavior through the UI
    - delete-song flow
  - Partly automatable, but still needing real-device confirmation:
    - actual Android notification button taps
    - lockscreen media controls
    - background / resume playback recovery
    - headset / Bluetooth media buttons
    - embedding / scan / rescan flows
    - permission edge cases across Android versions
  - Stress / benchmark oriented, not simple pass-fail checks:
    - large-library performance
    - long-running background playback behavior
  - Not fully guaranteeable across every Android device, only sampleable and monitorable:
    - OEM-specific notification and lockscreen behavior
    - Bluetooth / headset behavior across different hardware
    - process death / restore behavior under real battery saver, memory pressure, and vendor task-killing
- Regression verification now includes:
  - `npm.cmd run test:unit`
  - `npm.cmd run test:ui`
  - web build
  - Capacitor sync
  - Android unit tests
  - focused real Media3 connected playback-start test
  - app-scoped Android connected smoke test
  - Android debug build
  - focused manual playback / Discover / media-control checks on device

### App Identity
- Display name is `IsaiVazhi`.
- Android package / app id is `com.isaivazhi.app`.
- A future package-id rename would be a real migration decision, not just a cosmetic label change.

### Playback And Listen Capture
- Native Android playback is the transport authority during active playback.
- Live Android playback migration path now runs through `Media3PlaybackService` (`MediaSessionService` + `ExoPlayer`) in the `:playback` process, with `MusicBridgePlugin` controlling it through a `MediaController`.
- Legacy `MusicPlaybackService` is still present in source as a rollback path, but the plugin now prefers Media3 for transport / queue commands.
- Media3 controller startup is now reconnect-safe: failed or disconnected controllers are cleared and rebuilt, and both custom commands plus direct controller actions retry once instead of reusing a stale controller.
- Media3 queue commands now include both the original bundle payload and a JSON fallback payload so queue items survive the `:main` to `:playback` process boundary reliably.
- Listen fraction now comes from native accumulated `playedMs / durationMs`, not raw seek position alone.
- Seeking forward near the end no longer manufactures a fake strong listen.
- JS lifecycle events no longer classify listens directly.
- On background / close / WebView loss, JS only saves a pending listen snapshot:
  - `filename`
  - `playbackInstanceId`
  - `playedMs`
  - `durationMs`
  - timestamp / reason
- Native transition or completion is authoritative.
- On cold start, a pending listen is only finalized if no later native transition exists for the same playback instance.
- Playback-start bookkeeping is now bound to native `playbackInstanceId` when available, so a song adopted from native restore / resume is not allowed to complete without a matching recorded start.
- Pending-listen recovery now backfills a missing playback start before applying the recovered completion / skip, keeping `starts`, `completions`, and direct score math aligned.
- Native transition payloads now carry:
  - `prevFraction`
  - `prevPlayedMs`
  - `prevDurationMs`
  - `playbackInstanceId`

### Queue And Ordered Playback
- `Up Next` is one visible timeline:
  - previous items
  - current item
  - upcoming items
- Explicit ordered playback is preserved for:
  - section playback
  - albums
  - favorites
  - playlists
  - Taste Signal playback
- Shuffle only affects the remaining unplayed portion of the active queue.
- When an explicit ordered queue ends after shuffle, playback returns cleanly to recommendations instead of stopping in a stale list-finished state.

### Discover
- `Most Similar` keeps a reserved slot from startup so the page does not jump when embeddings become ready.
- `Most Similar` supports `Freeze` / `Unfreeze`.
- When not frozen, `Most Similar` is the only Discover section that live-updates on song change.
- `For You` and `Because You Played` stay on the cached Discover snapshot until a real refresh path invalidates them.
- Discover section taps and normal song changes no longer regenerate `Because You Played` or `Unexplored Sounds`.
- Outside manual refresh, Discover now reuses the cached snapshot for:
  - `For You`
  - `Because You Played`
  - `Unexplored Sounds`
- `Unexplored Sounds` stays on the cached Discover snapshot like the other non-similar Discover sections.
- `Unexplored Sounds` currently means songs without recorded starts / plays in recommendation history; favorite status alone does not remove a song from that section.
- If no cached `Unexplored Sounds` snapshot exists yet, the section waits for manual pull-to-refresh instead of auto-populating later from background AI-ready / embedding completion.
- If a song was shown in an older cached `Unexplored Sounds` snapshot and later gets played or favorited, it can remain visible there until the next manual pull-to-refresh rebuilds that section.
- Discover-page `Most Similar` cards and the `View All` similar list are aligned again for embedded songs.
- `For You` now treats an unexpectedly empty main Discover row as stale once recommendations are actually available, so the page no longer stays blank while `View All` can still show valid `For You` songs.
- Taste-based recommendation surfaces now share one recommendation policy:
  - `For You`
  - `Because You Played`
  - `Unexplored Sounds`
  - dynamic `Up Next`
- `Most Similar` stays pure similarity and is intentionally not filtered by negative taste policy.
- Strong direct-negative songs are blocked from taste-based recommendation surfaces only when they are in the strongest slice of the active negative set and below a minimum negative floor.
- Weaker negative songs are downranked rather than hard-excluded.
- Recommendation rebuilds now run as an immediate debounced background refresh after taste-changing events instead of waiting for manual Discover refresh.
- Discover shows a small inline updating state when recommendation rebuilds take long enough to be noticeable.
- Full Discover refresh is now manual-only via pull-to-refresh.
- Outside manual refresh, `Most Similar` remains the only Discover section that can update live.
- Discover cache saves now preserve the current visible snapshot for:
  - `For You`
  - `Because You Played`
  - `Unexplored Sounds`
  instead of silently recomputing them during ordinary state saves.

### Taste Signal And Logs
- Taste Signal is one signed `Library Signals` list, not separate positive / negative pages.
- The page includes:
  - `Recommendation Tuning`
  - `Engine Snapshot`
  - `Last 30 Playback Signal Updates`
  - `Taste Logs`
  - `Library Signals`
- `Last 30 Playback Signal Updates` is per playback result, not per-song aggregate.
- Each row now shows three score lines:
  - `Direct play effect` — directScore before → after with signed delta (what this play/skip/fav/dislike itself moved)
  - `Similarity delta effect` — the song's current accumulated similarityBoost snapshot (neighbor influence from other songs' plays)
  - `Total score` — total score before → after
- The older single `Taste score` line was removed because it duplicated `Total score`.
- `Last 30 Playback Signal Updates` excludes all very-early skips under 10% listened, regardless of how the skip originated (native neutral skip, section tile tap that replaces the current song, background recovery, native auto-advance, etc.). Engine state and profile evidence still record the short skip; only the per-playback-result UI timeline omits it so the list stays focused on meaningful signals.
- That block now has `Copy Last 30 Signals`, and `Copy` mirrors the same three lines per row.
- `Taste Logs` is the filtered Taste-specific view of the shared activity log.
- `Taste Logs` has its own `Copy Taste Logs` action.
- `Engine Snapshot` now defaults to collapsed.
- `Taste Logs` now sit below `Library Signals`.
- Taste Signal rows now expose:
  - total score
  - `direct` score
  - `similarity` boost / penalty
  - recommendation-blocked state for strong direct negatives
- AI page stays at 2 tabs only:
  - `Common Logs`
  - `Embedding Logs`
- AI-page cleanup confirms for `Remove ... Unmatched Embeddings` and `Remove ... Orphaned Embeddings` are now state-driven inside `showEmbeddingDetail()`, survive live detail-page rerenders, and no longer kick the outer scroll position back to the top when opened.
- The unmatched cleanup confirm now renders outside the inner unmatched-song scroll list so its action buttons stay reachable on small screens.

### Recommendation Inputs
- Persistent recommendation inputs now come from these main stores:
  - `profile_summary_v2`
  - session logs
  - `negative_scores`
  - `similarity_boost_scores_v1`
  - `disliked_songs`
  - `playback_state`
- Missing fraction history is treated as unknown rather than as a fake soft-positive listen.
- Repeated user skips can accumulate negative score gradually.
- Favorite / dislike now behaves as fading manual priors rather than permanent recommendation multipliers.
- Similar songs can now receive a small signed neighbor influence from the latest direct taste delta of a played / skipped / favorited / disliked song.
- Neighbor influence is auditable in Taste Signal and is used for ranking, not for hard exclusion.

### Embedding Model And Direction
- Status: planning note only; this section records the agreed model and proposed direction, not a newly landed code change.
- New songs should appear immediately in `Songs`, `Albums`, and `Recently Added`.
- Until a song has an embedding, it should stay out of AI recommendation surfaces and Discover-driven recommendation logic.
- If the user plays a song before its embedding exists, playback should follow the normal non-AI / shuffle fallback path for that case.
- The app should identify songs with missing embeddings and process them one by one in the background, while saving progress safely as the batch moves forward.
- The important separation is: library-visible first, AI-visible later.
- Finished embeddings can be saved one by one, but recommendation / Discover promotion should not churn one song at a time during a large batch; safer promotion points are batch completion, playback-idle moments, or next startup / explicit refresh.
- Original first-principles diagnosis before Option B work: playback and embedding sharing one Android process meant that even separate worker threads could still compete for CPU time, heap / GC pauses, audio decode, and disk I/O. The live app now has the first `:ai` process split in place, but the remaining decode / store / recommendation migration still follows this model.
- Suggested direction: playback must always win over embedding work.
- Suggested direction: do not embed the currently playing song or near-upcoming songs while playback is active.
- Suggested direction: pause or heavily yield embedding while active playback is happening.
- Suggested long-term architecture: move embedding into a separate Android process and avoid sending full embedding vectors through the JS bridge during active runs.

### Embedding Architecture Migration — Option B Status (2026-04-25)

Status: partially landed. `:playback` is live for transport, the real `:ai` batch-embedding split is in code, playback-aware scheduler/backoff is in place while keeping whole-song decode, the stable embedding store is now headered + atomically written in both JS/native paths, native `:ai` nearest-neighbor lookup is now used by the For You candidate pool plus `Because You Played`, and a fresh main-process hardening pass now guards duplicate native playback events plus missed embedding callbacks. The remaining active roadmap is the rest of recommendation-path migration away from JS vector scans plus real-device soak. Seek-per-window decode remains a future optimization idea, but whole-song decode is intentionally retained for now. Target device referenced during discussion: Pixel 7, 8 GB RAM.

#### Pre-split baseline (what we started from)
- `EmbeddingService` runs on a `Thread` with `THREAD_PRIORITY_BACKGROUND`, hosted by `EmbeddingForegroundService`, in the **same process** as `MusicPlaybackService` and the WebView. No `android:process=":embedding"` on the service.
- The ~271 MB CLAP ONNX model is extracted once to internal storage and kept resident in that single process for the whole batch.
- Per-song pipeline: `MediaExtractor` + `MediaCodec` decode of the whole song (capped at 360s) → mono downmix → linear-interpolation resample to 48 kHz → extract 3 × 10s windows at 20 / 50 / 80% → 3 ONNX inferences → average + L2 normalize → append to `pending_embeddings.json`.
- Playback-friendly knobs today: ORT intra-op threads set 2 → 1 **only at `initialize()`** (never re-read during a run), `System.gc()` every 8 songs, `Thread.sleep(20ms)` between songs when `isPlaybackActive()` is true, retry-with-`gc+sleep(1000)` on decode failure.
- Bridge emits `embeddingSongComplete` with the full 512-float embedding per song.
- `pending_embeddings.json` is fully rewritten every 5 songs.

#### Symptoms the user reported
- UI feels sluggish / laggy during embedding runs.
- Occasional "song not playing at all".
- App occasionally restarts (not yet confirmed whether OOM, ANR, or WebView child crash).
- Subjective anxiety that embedding will break playback even when in a given run it did not.

Pixel 7's 8 GB device RAM does **not** remove the per-process heap / GC problem. This app currently has `android:largeHeap="true"`, which gives more headroom than the default heap, but 271 MB model + large decode scratch + ONNX tensors can still create severe shared-process pressure. 8 GB device RAM reduces `lowmemorykiller` pressure and helps background survival, but it does not prevent in-process GC stalls or make the UI / playback / embedding processes independent.

#### Root cause ranking (first principles)
1. Heap pressure → GC stop-the-world pauses. A 6–7 min 48 kHz mono song decodes to ~80 MB of `float[]`, with ~1.5× transient during buffer regrow. The explicit `System.gc()` every 8 songs is itself a blocking collection that pauses every Java thread in the process (UI included) — it is the pause it was meant to mitigate.
2. Whole-song decode is mostly wasted. Only 3 × 10s = ~5.7 MB of samples are actually used by ONNX; the other ~75 MB is thrown away.
3. ONNX inference burst pins 2 CPU cores for several seconds per song; memory-bandwidth and L2-cache contention with the audio decoder can cause audible glitches even without a full CPU stall.
4. `isPlaybackActive()` thread-count selection is stale. It is only checked at `initialize()`, so a session created while idle keeps 2 intra-op threads even if playback starts mid-batch.
5. Hardware `MediaCodec` instance pool is system-wide (typically 2–3 audio decoders). Embedding opens one per song; playback holds one. This is a plausible root cause of "song not playing at all" and is **not** fixed by any amount of thread-priority or GC tuning.
6. Same-process fate sharing. Any heap spike, OOM, or unhandled exception on the embedding path can still pause or kill playback even with all per-thread throttling in place.
7. The full 512-float bridge payload per song is small individually but is a UI-thread tick per song and is avoidable — JS can read `pending_embeddings.json` directly.

#### Architectural decision: three-process split (`:main` + `:playback` + `:ai`) — Option B
Three options considered:
- **Option A** — two-process where `:main` holds UI + playback and `:ai` holds embedding. Simplest, one IPC boundary. A WebView crash or UI-thread ANR still affects playback.
- **Option C** — `:main` + `:ai`, defer any `:playback` split. Smaller diff than B; fixes embedding → playback interference, but leaves playback sharing fate with the WebView / JS engine. Initially recommended on shipping-cost grounds.
- **Option B — three-process: `:main` (UI + WebView + JS), `:playback` (`MusicPlaybackService`), `:ai` (embedding).** Structurally strongest; matches the tier-0 / tier-1 / tier-2 model (Tier 0 = audio must never stop; Tier 1 = UI shouldn't crash playback; Tier 2 = ML allowed to fail + retry). This is the architecture real music apps (Spotify / YouTube Music / Apple Music / Poweramp) use.

**Chosen direction: Option B.** Reasoning:
- The reported "app restarting" symptom, if it turns out to be a WebView child-process crash or JS-engine ANR, is only fixed by separating `:playback` from `:main`. Option C leaves this failure mode live.
- The user explicitly asked for "future-proof" and "solve once for all". Option C is a 80% solution; B is the full one.
- Future features that will stress `:main` (gapless, crossfade, lyrics rendering, visualizer, Android Auto, Wear, Cast) cannot regress audio smoothness if playback is already isolated.
- The `:main` → `:playback` migration is almost certainly going to be done eventually anyway once residual jitter from the UI side is observed post-`:ai`. Doing it once, now, is strictly less total work than doing it as a second round.

Trade-off accepted: ~20 `MusicPlaybackService.instance.xxx()` static call sites and ~8 event streams need to become cross-process IPC. That is a real refactor, not a silver-bullet drop-in.

#### Landed now
- `EmbeddingForegroundService` is now declared in `android:process=":ai"`.
- Main-process embedding control now goes through `EmbeddingControllerClient` (Messenger bridge) instead of same-process static service callbacks.
- `MusicBridgePlugin` now proxies:
  - start embedding
  - stop embedding
  - request embedding status
  - playback-active hint updates sent from `:main` to `:ai`
- The `:ai` service now persists the active batch to disk and can resume it after an `:ai` process restart.
- Per-song bridge events are now metadata-only (`filename` / `filepath` / `contentHash` + counts). The full 512-float vector is no longer pushed through the JS bridge on each song.
- JS now reconnects to a running embedding batch on startup, requests live status from native, and reloads / merges embeddings from disk on batch completion instead of relying on per-song vectors.
- Playback-aware scheduling is now partially landed without changing embedding quality:
  - whole-song decode is intentionally still the active decode path for now
  - `:main` sends `:ai` short cooldown hints after play / resume / seek / next / prev / queue-current changes / playback errors
  - `:ai` yields before decode, before each ONNX window inference, after each window, and after each song when playback or cooldown pressure is present
  - ONNX session thread count can switch down to the playback-friendly one-thread policy when playback / cooldown / device pressure is active
  - embedding also backs off under Android battery-saver or thermal pressure
  - periodic explicit `System.gc()` during normal successful songs was removed; retry-time GC remains only after an actual decode failure
- First native similarity slice is now landed:
  - `:main` exposes `MusicBridge.findNearestEmbeddings(...)`
  - `:ai` owns `EmbeddingSimilarityIndex`, reads `local_embeddings.bin` + `local_embeddings_meta.json`, and returns lightweight filepath / content-hash / similarity metadata
  - `For You` candidate-pool search and `Because You Played` section generation can use the native `:ai` nearest-neighbor RPC and fall back to the existing JS recommender if native lookup is unavailable
  - this only sends an occasional query vector or source-song identity to native for recommendation search; it does not restore per-song 512-float bridge spam during embedding runs
- Stable embedding-store hardening is now landed:
  - `local_embeddings.bin` now carries an `ISAIEMB3` magic header + explicit format version in both the JS writer and the native JSON-to-binary conversion path
  - the JS and native writers atomically replace the `.bin` / `.meta` pair instead of writing the stable store directly in place
  - `local_embeddings_meta.json` now records `version`, `format`, and `entryCount`
  - the native `EmbeddingSimilarityIndex` reader and JS `embedding-cache` reader both validate header / payload length, while still accepting legacy headerless stores during migration
- Native media actions are now retained until JS registers its `mediaAction` listener, so early notification / lockscreen favorite-dismiss events during app startup are not dropped.
- Connected-test startup hardening landed:
  - the failing AndroidX Startup provider path is removed from the app manifest because it crashed before app code on API 36 emulator with missing `androidx.startup.R$string`
  - the old rollback `MusicPlaybackService` delays dismiss cleanup briefly after emitting `dismiss`, matching the safer Media3 behavior
  - the JS notification-dismiss path clears the quickDisplay mini-player shim before refreshing UI state
- Safety checkpoint created before this pass:
  - `backups/ai_split_prework_20260422_232728/`
  - `backups/ai_scheduler_prework_20260423_085713/`
  - `backups/ai_similarity_prework_20260423_091048/`
  - `backups/android_startup_fix_prework_20260423_092506/`

#### Still pending for the active Option B embedding target
- Whole-song decode remains intentionally retained for now. Seek-per-window decode is no longer part of the active Option B completion bar; keep it as a future optimization if whole-song decode becomes a proven bottleneck again.
- The playback-aware scheduler is landed at coarse stage boundaries, but still needs real-device tuning against a large library while music is actively playing.
- Similarity / nearest-neighbor is only partially moved native-side: `For You` and `Because You Played` can use `:ai`, but the synchronous taste-propagation helper, `Unexplored Sounds`, `Most Similar`, and other JS recommendation paths still need a wider async/native refactor.
- A fresh real-device long batch soak is still needed specifically against the new `:ai` path while actively playing music, now also validating that duplicate native playback events stay harmless and that AI-page progress survives any missed callback bursts.

#### Source verification (2026-04-25)
Each "landed now" claim was cross-checked against the live source tree. Findings:

| Claim | Verified at |
|---|---|
| `:playback` process for Media3 | `AndroidManifest.xml` — `android:process=":playback"` on `Media3PlaybackService` |
| `:ai` process for embedding | `AndroidManifest.xml` — `android:process=":ai"` on `EmbeddingForegroundService` |
| Media3 controller client (`:main` ↔ `:playback` IPC) | `Media3PlaybackControllerClient.java` — full reconnect / retry logic |
| Embedding controller client (`:main` ↔ `:ai` Messenger) | `EmbeddingControllerClient.java` — bind, register, status, playback hint, find-nearest |
| Per-song bridge events are metadata-only | `src/engine.js` `_setupEmbeddingListeners` — `embeddingSongComplete` listener early-returns after recording metadata; the old in-listener merge code below the early return is dead and worth deleting in a future pass |
| Native nearest-neighbor RPC for `For You` + `Because You Played` | `src/engine.js` `_nativeRecommendFromQueryVec` and `_nativeRecommendFromSong` are tried first; `getBecauseYouPlayed` falls back to JS `_recommendFromSourceEmb` if native lookup is unavailable |
| `MusicBridge.findNearestEmbeddings` proxies to `:ai` | `MusicBridgePlugin.java` `findNearestEmbeddings` — forwards via `embeddingControllerClient.findNearest` |
| Stable embedding store hardening | `src/embedding-cache.js` writes `ISAIEMB3` + versioned meta, `MusicBridgePlugin.java` `convertEmbeddingsJsonToBinary` now writes atomically, `EmbeddingSimilarityIndex.java` validates header + exact payload length |
| Playback-aware scheduling in `:ai` | `EmbeddingService.java` — `isPlaybackActive`, `isInPlaybackCooldown`, `getDevicePressureDelayMs` (thermal / battery), throttle reasons emitted |
| Cooldown hints from `:main` to `:ai` | `EmbeddingControllerClient.setPlaybackState` invoked on play / resume / seek / next / prev / queue-current / errors |
| Active batch persistence in `:ai` (resume after process restart) | `EmbeddingForegroundService.java` `ACTIVE_BATCH_FILE = "active_embedding_batch.json"` + `persistActiveBatch` |
| Native media actions retained for late listeners | `MusicBridgePlugin.java` — `notifyListeners("mediaAction", data, true)` with `retain=true` |
| AndroidX Startup provider removed | `AndroidManifest.xml` — `tools:node="remove"` on `androidx.startup.InitializationProvider` |
| Whole-song decode intentionally retained for now | `EmbeddingService.java` `decodeAudio` reads the entire track via `MediaExtractor` until EOF; no `seekTo` before each 10s window |
| Legacy same-process `embedSingleSong` fallback removed | `MusicBridgePlugin.java` no longer exposes `embedSingleSong`; the live embedding path is now the `:ai` batch bridge |
| JS-side native playback-event dedupe / stale-guard hardening | `src/app.js` now dedupes `audioPlayStateChanged`, rejects repeated/stale `queueCurrentChanged`, and re-syncs paused/UI state from native queue truth |
| Engine-side duplicate native-advance protection | `src/engine.js` `onNativeAdvance(...)` now keys native transitions and ignores duplicates / stale playback-instance ordering |
| Embedding-status recovery when callbacks are missed | `src/app.js` now re-requests embedding status on resume/detail-page/stale-callback conditions; `MusicBridgePlugin.java` emits fallback `embeddingComplete` on `MSG_STATUS` active -> inactive |

Net: Option B is still partial-landed, but the store-hardening, first broader recommendation migration follow-up, and the first main-process duplicate-event / embedding-status recovery hardening pass are now in source. Remaining cleanup follow-ups worth scheduling: (a) delete the dead post-`return` code inside `_setupEmbeddingListeners`'s `embeddingSongComplete` handler; (b) continue moving the remaining sync JS recommendation paths off raw vector scans; (c) remove the legacy `MusicPlaybackService` rollback branch once `:playback` has accumulated a real-device soak.

#### Stale-APK incident (2026-04-25)
A user report listed 8 issues against an APK timestamped `2026-04-23 12:18`: scan toast hung, playback transport unresponsive after first play, Taste Signals page unreachable, Discover sections never loaded, mini-player missing on cold start, plus three lower-severity items. **None of these reproduced after a clean `gradlew clean assembleDebug` rebuild from the same source.** The shipped APK had drifted behind the source tree. Lesson: when a bug report comes in, confirm the installed APK timestamp matches a fresh `assembleDebug` output before spending investigation time. The fresh APK and a clean DBG-log capture (`logs.txt`) showed the full init chain (`loadData` → `restorePlaybackState` → `background scan started` → `embeddingsReady` → `scanComplete` → `aiReady`) completing in ~9 seconds, with normal pause / next / song-change behavior afterwards.

#### Fresh-APK live stall investigation and fix (2026-04-25)
After the stale-APK issue above was closed, a second report reproduced on a freshly rebuilt APK: while new songs were embedding, playback could continue from the notification player, but the WebView UI became effectively hung. The mini-player play icon stayed stale, page navigation stopped responding, and the AI page stopped showing new embedding-log entries even though the embedding notification kept progressing.

What the richer DBG capture showed:
- The UI stall was real. The watchdog logged long pauses in `:main` / WebView of roughly `43.8s`, `9.5s`, `48.1s`, and `14.8s`.
- In the captured run, the worst stalls started before the 1-song embedding batch actually began, so this was not simply `:ai` decode directly breaking `:playback`.
- Duplicate native playback events were present: repeated `queueCurrentChanged` and repeated play/pause transitions for the same user jump.
- JS/native playback state diverged after those duplicates, which explains the stale mini-player icon and bad UI state.
- During an active embedding batch, native embedding continued, but JS received zero `embeddingProgress` / `embeddingSongComplete` / `embeddingComplete` callbacks. This explained why the notification showed progress while the AI page Embedding Logs stayed blank.
- The same duplicate-transition burst also caused bogus taste/listen recording, where the next song could inherit the previous song's fraction classification.

Conclusion from the incident:
- This was not mainly "embedding in `:ai` still directly blocks playback transport."
- The primary live issue was still in `:main`: duplicate / out-of-order native playback events plus missed embedding callback delivery left the WebView state inconsistent and made the app feel hung even while native `:playback` and native embedding kept running.

Fixes landed from this investigation:
- The DBG report path was upgraded so the on-device `DBG` copy now includes a runtime snapshot, native `getAudioState()` and `getQueueState()`, recent DBG lines, recent embedding/activity logs, and a UI-stall watchdog report. This avoids multi-step manual log collection next time.
- `src/app.js` now dedupes repeated `audioPlayStateChanged` events, rejects repeated/stale `queueCurrentChanged` transitions using an event key plus playback-instance ordering, and syncs the mini-player/body paused state from native queue truth after a native transition.
- `src/engine.js` `onNativeAdvance(...)` is now idempotent. Even if a duplicate transition still leaks through, it no longer reprocesses the same native advance and no longer double-classifies the next song.
- While embedding is active, the UI now actively re-requests native embedding status on app resume, when the AI detail page is open, and when callback delivery looks stale. This gives the AI page a recovery path even if the original callback burst is missed.
- The engine now dedupes repeated embedding progress / complete signatures so the new status-poll fallback does not create log spam or rerun completion work.
- `MusicBridgePlugin` now treats an embedding `MSG_STATUS` transition from active -> inactive as a fallback `embeddingComplete` emit, so JS can still observe batch completion if the original completion callback was missed.
- Regression coverage added a unit test that verifies duplicate native queue advances do not misclassify the next song.

What still needs validation after the fix:
- Real-device soak while music is actively playing and new-song embedding is active.
- Confirmation that the AI page keeps updating even if individual embedding callbacks are dropped.
- Confirmation that duplicate native playback events are either gone entirely or now harmless from the JS/UI point of view.

#### What moves to `:ai`
- ONNX runtime + the 271 MB CLAP model.
- Audio decode + mono + resample + window extraction.
- The embedding **vector store** (the binary file currently read by `src/embedding-cache.js`).
- Nearest-neighbor / cosine-similarity compute (`rec._findNearest` equivalent). First slice is landed for `For You`; the full recommendation stack is not fully migrated yet.
- Embedding CRUD on song add / delete / orphan cleanup.

#### What moves to `:playback`
- `Media3PlaybackService` with ExoPlayer, `MediaSessionService`, notification, audio focus, headset / lockscreen / Bluetooth media-button handling. Legacy `MusicPlaybackService` remains in source as rollback.
- Native playback-transition history, `playbackInstanceId` bookkeeping, recovery prefs (the native source of truth for listen fractions).
- The native side of `favorite` / `close` / `dismiss` media-action handling now reaches `:main` through retained `mediaAction` events so JS state (mini-player heart, favorites set, Browse tiles, mini-player dismissal) can update even if the event fires during startup.
- The in-memory current track, queue, loop mode, shuffle flag (as transport state, distinct from JS-side queue intent).

#### What stays in `:main`
- WebView + Capacitor + JS engine (`src/engine.js`, `src/app.js`).
- Recommendation **policy**: favorites / dislikes / taste-signal combination, Discover section gating, For You / Because You Played / Unexplored Sounds scoring. Needs ranked candidate lists, not raw vectors.
- Discover cache, UI state, user preferences, persisted `playback_state` (as intent + last-known, not transport truth).
- Queue intent (what the user asked for), song library metadata, playlists, favorites / dislikes.
- `MusicBridgePlugin` — but it becomes a thin proxy that forwards calls to `:playback` and `:ai` over Messenger, and forwards events back to JS via `notifyListeners`.

#### Proposed IPC contracts (Messenger preferred over AIDL — simpler, async, no `.aidl` files, adequate for this workload)

**`:main` ↔ `:playback` (transport control, ~20 methods + ~8 event types)**
```
// control (from :main)
setQueue(items[], startIndex, seekToMs?)
playAudio() / pauseAudio() / resumeAudio() / stop()
seekTo(positionMs)
playNextTrack() / playPreviousTrack()
setLoopMode(mode)
setQueueShuffleEnabled(bool)
addToQueue(item) / playNext(item) / removeFromQueue(index)
setCurrentSongFavorite(bool)       // for lockscreen heart sync
dismissPlayer()                    // user-initiated close
requestTransportState()            // on reconnect after :playback restart

// events (to :main, fanned out to JS)
audioPlayStateChanged {isPlaying}
queueCurrentChanged {action, prev, curr, prevFraction, prevPlayedMs, prevDurationMs, playbackInstanceId}
audioPositionChanged {positionMs, durationMs}    // throttled, e.g. 500ms
audioError {message, path, phase}
mediaActionFavorite / mediaActionDismiss         // native custom-action taps
audioFocusChanged {state}
embeddingComplete fanout pass-through            // unchanged semantically
transportReady                                    // emitted on :playback cold-start / restart
```

**`:main` ↔ `:ai` (embedding + similarity, 6 methods)**
```
embedBatch(paths: string[])        → streams {progress, songComplete, done}
embedSingle(path: string)          → embedding_ready event
findNearest(songId, k, exclude?)   → songId[]
findNearestBatch(songIds[], k)     → map<songId, songId[]>
removeEmbedding(songId)            → ok
getEmbeddingStatus(songIds[])      → map<songId, boolean>
```

`:playback` and `:ai` do not talk to each other directly; `:main` brokers if coordination is ever needed (e.g., "playback started, pause embedding" is a signal `:main` sends to `:ai`, not `:playback` → `:ai`).

#### Shared-state model
- **JS / `:main`** is authoritative for *intent*: the user's queue, selected song, loop / shuffle preference, favorite / dislike state, library metadata.
- **`:playback`** is authoritative for *transport*: is audio actually playing, current position, current duration, which `playbackInstanceId`, who holds audio focus.
- `:main` persists intent to `Preferences` (`playback_state`, queue, etc.). On `:playback` cold-start or crash-restart, `:main` replays last-known intent to `:playback` via `setQueue` + `seekTo`. Transport truth is rebuilt on resume; JS reconciles using existing pending-listen recovery logic.

#### Failure contract (must be named and handled, not implicit)
1. `:ai` dies mid-batch → foreground-service auto-restart; existing `pending_embeddings.json` resume logic picks up where the batch left off. `:main` and `:playback` are unaffected.
2. `:ai` OOMs on a pathological song → mark that song as `embed_failed` with a retry-after-cooldown flag; batch continues. Extends today's retry-once pattern.
3. `:main` asks `:ai` for nearest and `:ai` is dead → `:main` falls back to the cached Discover snapshot (already the current behavior when `aiReady = false`). No error surface, no blocked UI.
4. **`:playback` dies mid-song** → foreground-service auto-restart. `:main` receives a `transportReady` event on reconnect, replays last-known queue + position from persisted `playback_state`, and resumes. Worst-case user impact is a brief audio gap, not a lost session.
5. **`:main` / WebView dies** → `:playback` keeps playing. Notification / lockscreen controls keep working. When user reopens the app, `:main` reconnects to `:playback` via `requestTransportState` and rehydrates the mini-player from live transport truth instead of a cold restore.
6. User force-kills the app → all three processes die; next launch rehydrates from `Preferences` (`playback_state`, queue) plus `pending_embeddings.json` plus the stable binary store.
7. Embedding store partial-write corruption → atomic rename-on-write + magic-header validation; `:ai` rebuilds the binary store from `pending_embeddings.json` if the binary is bad.
8. `:playback` and `:main` state diverge on reconnect (e.g., `:playback` finished a track while `:main` was gone) → `:playback` is transport truth; `:main` adopts its current song / position and runs the existing pending-listen reconciliation path against native transition history.

#### Pipeline fixes originally proposed to bundle into the same migration (historical plan note)
- **Seek-per-window decode** remains the main future memory/codec-pressure optimization if whole-song decode becomes a proven bottleneck again. Using `MediaExtractor.seekTo(targetUs, MODE_PREVIOUS_SYNC)` + short drain to decode only ~10s around each of the 3 window positions would cut peak decoded audio allocation from ~80 MB to ~5.7 MB per song.
- Remove the manual `System.gc()` calls. They are stop-the-world pauses, not mitigations. With #1 above, they are unnecessary.
- Yield between **windows** (not just between songs), and re-check `isPlaybackActive()` per window so ORT thread count and yield behavior track live state.
- Current decision: do **not** pause embedding completely during playback. Keep embedding and playback parallel, but force embedding into a conservative one-thread / yield-heavy / cooldown-aware mode while playback is active.
- Stop shipping the full embedding over the Capacitor bridge per song. Emit a lightweight `{filename, filepath, contentHash}` event; JS reads `pending_embeddings.json` via existing reader paths.

#### Why not partial measures
Removing `System.gc()` and adding more throttling inside the same process would reduce — not eliminate — interference. The user's anxiety ("embedding breakdown even though it didn't") requires a **structural** guarantee (separate process), not a "less likely to interfere" guarantee. A guarantee the user can trust also has product value independent of measured jitter.

#### Sequencing
The `:ai` split ships first because it is the single biggest jitter win and is lower risk. The `:playback` split follows as the structural finisher. Both land; "later" is part of the plan, not a defer.

**Phase 0 — evidence (no code changes)**
1. Capture a logcat of "app restarting" during an embedding run. Identify whether it is OOM, ANR, WebView child crash, or `lowmemorykiller`. The three need different fixes, and the migration covers most but not all — evidence confirms what's actually failing.

**Phase 1 — `:ai` split + decode-path work (historical plan)**
2. Design `:ai` IPC contract in detail (Messenger, 6 methods as above).
3. Add `android:process=":ai"` to `EmbeddingForegroundService` in AndroidManifest.
4. Move ONNX + decode + embedding store + nearest-neighbor compute into code owned by that process. Replace `MusicPlaybackService.instance.isCurrentlyPlaying()` from the embedding side with a cross-process signal (a shared atomic pref the playback side writes on every state change, or a Messenger poke).
5. Optional future optimization: rewrite `EmbeddingService.decodeAudio` to seek-per-window (`MediaExtractor.seekTo(MODE_PREVIOUS_SYNC)` + ~10s drain per window) if whole-song decode proves to still be a meaningful bottleneck after the remaining active Option B work lands.
6. Remove manual `System.gc()` calls. Yield between windows, not just songs. Re-check "is playing" per window. Keep embedding parallel with playback, but run it in one-thread / cooldown-aware mode while playback is active.
7. Engine (`src/engine.js`) loses direct embedding-vector access; replace with bridge calls to `:ai` methods.
8. Atomic rename-on-write + magic header on the binary embedding store.
9. Stop shipping the full 512-float embedding over the bridge per song; emit `{filename, filepath, contentHash}` only.
10. Verify Phase 1: `npm.cmd run test:unit`, `npm.cmd run test:ui`, web build, Capacitor sync, Android `testDebugUnitTest`, `:app:connectedDebugAndroidTest`, `assembleDebug`, on-device soak (large batch while actively playing).

**Phase 2 — `:playback` split (structural finisher)**
11. Design `:main` ↔ `:playback` IPC contract in detail (~20 methods + ~8 events). Decide Messenger vs bound AIDL (Messenger likely; events are natural on Messenger reply channels, and the throughput is low).
12. Add `android:process=":playback"` to `MusicPlaybackService` in AndroidManifest.
13. Convert `MusicBridgePlugin` into a thin client: replace every `MusicPlaybackService.instance.xxx()` static call with a Messenger send. Plugin no longer holds a direct service reference.
14. Build the event relay: `:playback` → `:main` → `notifyListeners` to JS. Preserve existing event names and payloads so JS engine / app code does not need to change.
15. Persist playback intent in `:main` (queue + current index + position) on every change. On `:playback` cold-start / restart, `:main` replays via `setQueue` + `seekTo`.
16. Handle state-divergence on reconnect: `:playback` is transport truth; `:main` adopts and runs pending-listen reconciliation.
17. Foreground-service lifecycle: `:playback` owns its own notification + media session; `:main` no longer starts them directly.
18. Verify Phase 2: same verification matrix as Phase 1 plus explicit on-device tests — kill the WebView process (devtools / adb) mid-song and confirm audio continues; kill `:playback` mid-song and confirm auto-restart; hot-restart the app and confirm `:main` reconnects to live transport instead of cold-restoring.

**Post-migration cleanup**
19. Retire any same-process shortcuts left behind (static instance refs, direct package-private calls).
20. Update `project_development.md` "Current Source Of Truth" sections to describe the three-process architecture as the live state (not as a plan).

### Media3 Playback Migration — Working Plan (2026-04-22)

Status: in progress, with the main playback command path already switched over. This supersedes the earlier note that Phase 2 playback IPC would likely stay on the old `MusicPlaybackService` + Messenger path. The playback target is now:
- `androidx.media3` session stack
- `ExoPlayer` as the playback engine
- `MediaSessionService` in `:playback`
- `MediaController` in `:main` / `MusicBridgePlugin`

Safety backup created before Media3 edits:
- `backups/media3_migration_20260422_222641/`

Post-parity checkpoint created after compile + unit tests + APK build:
- `backups/media3_parity_checkpoint_20260422_231741/`

Final verified checkpoint created after connected Android tests:
- `backups/media3_verified_checkpoint_20260422_232117/`

Playback hotfix backup created before the reconnect / queue-payload fix:
- `backups/playback_hotfix_prework_20260423_113928/`

Implementation approach for this migration:
- Stage the cutover so the old `MusicPlaybackService` remains a rollback path while Media3 gains parity.
- Land a new Media3 contract layer first:
  - shared command / event constants
  - queue item serialization helpers
  - `MediaSessionService` in `:playback`
  - `MediaController` proxy in `MusicBridgePlugin`
- Keep the JS contract stable while the native playback engine changes underneath it.
- Migrate in this order:
  1. add Media3 dependencies + manifest + playback service skeleton
  2. add controller/proxy layer in `MusicBridgePlugin`
  3. move core queue + transport controls to Media3
  4. restore JS event parity (`audioTimeUpdate`, `audioPlayStateChanged`, `queueCurrentChanged`, `queueChanged`, `queueEnded`, `audioError`, `mediaAction`)
  5. restore custom notification actions (`favorite` / `dismiss`)
  6. remove same-process legacy shortcuts only after parity is verified

Landed so far:
- added `androidx.media3` dependencies (`media3-exoplayer`, `media3-session`)
- created new `Media3PlaybackService` in `:playback`
- defined queue item / command / event contract shared by service and plugin
- switched `MusicBridgePlugin` to prefer the `MediaController`-based Media3 path for queue + transport commands while keeping legacy fallback code present
- restored JS event relay parity for:
  - `audioTimeUpdate`
  - `audioPlayStateChanged`
  - `queueCurrentChanged`
  - `queueChanged`
  - `queueEnded`
  - `audioError`
  - `mediaAction`
- restored notification-surface parity on the Media3 path for:
  - native `favorite`
  - native `dismiss`
  - JS-driven notification refresh after in-app favorite changes
  - `stopPlaybackService`
- hardened Media3 controller reconnect / retry behavior so a failed initial connection or dead `:playback` process does not poison later play taps or mini-player controls
- added JSON queue payload fallback next to the bundle payload for cross-process queue reliability
- added playback-start diagnostics in native logs for `CMD_SET_QUEUE`, `CMD_PLAY_AUDIO`, `setQueue`, `onIsPlayingChanged`, and `onPlayerError`
- added `Media3PlaybackServiceConnectedTest`, which generates a real WAV file on the emulator, sends `setQueue` to the Media3 service, and asserts `isPlaying=true`

Still pending before the legacy path can be retired:
- real-device validation of Media3 notification / lockscreen behavior
- broader connected/emulator coverage for the new Media3 notification path beyond the existing retained media-action bridge tests
- removal of leftover legacy same-process shortcuts once parity is proven stable

### Bluetooth And Background Playback
- The live playback path now uses Media3 / ExoPlayer in `:playback`, not the old same-process `MediaPlayer` service path.
- Media session and notification still use the app display name (`IsaiVazhi`) and a consistent content intent.
- ExoPlayer is configured with music audio attributes and `setHandleAudioBecomingNoisy(true)`.
- Notification / lockscreen `favorite` and `dismiss` actions are available again on the Media3 path via session custom commands.
- Notification `favorite` toggles the same current-song favorite state used inside the app.
- Notification `dismiss` clears saved `playback_state`, emits the same JS `mediaAction` event name, and then stops the Media3 service.
- The old custom RemoteViews notification layouts remain only on the legacy rollback path; the live Media3 path now uses the Media3 notification provider.
- Emulator coverage now verifies that Media3 can start real playback from a generated WAV through the same `setQueue` command path used by song taps.
- Remaining limitation:
  - real-device validation is still needed for notification appearance and action delivery across OEM lockscreen / notification implementations

## Latest Verified Changes

- 2026-04-26: emulator soak run against the live 12:27 debug APK (no source changes this pass — observation/validation only). Two batches were exercised: first 13 fresh `.mp3` songs pushed into `/sdcard/songs_downloaded/` (97 total on device), then 11 more `.mp3` songs (108 total). In both runs `song_library.json` `savedAt` was backdated to force the full `_backgroundScan` path because the cache-age skip threshold (~6h) was not yet exceeded. The first batch (13 songs) was interrupted by `am stop-app` (recents-swipe equivalent) — `:ai` resumed the remaining work cleanly from `active_embedding_batch.json`, the mini-player correctly restored `currentSong=67 (Jaiye Sajana)` after the kill, and 13/13 finished. The second batch (11 songs) was deliberately left to complete in-foreground with playback active throughout — all 11 finished cleanly under playback. Validated working in source across both runs: ONNX session switched 2 → 1 thread on `playback_started` and emitted 120ms yields before/after every window inference, song completion, and decode (reason field cycled through `playback_started`, `queue_current_changed` as transport events fired); `active_embedding_batch.json` persistence let the fresh `:ai` process resume after a full three-process kill; atomic write of `local_embeddings.bin` confirmed by exact-size deltas (each new embedding adds 2,048 bytes = 512 floats × 4); `restorePlaybackState` correctly rehydrated the mini-player after `am stop-app`; multiple HOME/relaunch cycles (single, 3-rapid-burst-in-30s, with media-key NEXT/PAUSE/PLAY in background) produced no FATAL / no ANR / no WebView child crash / no Media3 `onPlayerError`; only valid `neutral_skip` / `user_jump` / `auto_advance` `queueCurrentChanged` actions were observed and no duplicate-rejection events fired. Originally-suspected "playback glitches during embedding" symptoms did NOT reproduce — only ~300 ms inter-track `isPlaying false → true` transitions consistent with normal Media3 between-track state. Findings worth tracking:
  - **Real in-session merge gap (worth fixing).** When an embedding batch completes in foreground with active playback, the native `embeddingComplete` event does not propagate to JS — `EmbeddingService` correctly logs `Embedding complete: 11 processed, 0 failed` and `pending_embeddings.json` ends up with all 11 entries (121,112 bytes), but the JS console emits zero embedding-related messages from `Embedding call returned: {"status":"started","count":11}` until the next cold start; no `[Embedding] Loaded` / `Recovered ... pending` / `Wrote stable binary store` lines fire in-session, and `local_embeddings.bin` is not updated. The status-poll fallback (`MSG_STATUS active→inactive` → fallback `embeddingComplete`) only triggers on app resume / AI detail page open / stale-callback heuristics, none of which were satisfied here. Cold-start path recovers cleanly: a force-stop + relaunch immediately fires `Loaded 2486 embeddings from stable binary store` → `Recovered 11 pending embeddings into the stable store` → `Wrote stable binary store: 2497 entries, 512d` → `Merge result: 108 merged`, with `local_embeddings.bin` growing 5,091,328 → 5,113,856 (+22,528 = 11 × 2,048). So no data loss, but new embeddings do not reach Discover / recommendation surfaces until next launch when a batch finishes while the app stays active. Suggested fix direction: either (i) make native always raise the bridge `embeddingComplete` event on natural batch completion (currently it appears the bridge emit only fires on the legacy completion path or via the `MSG_STATUS` fallback), or (ii) add a JS-side post-batch poll that runs once after `Embedding call returned: started` resolves so the merge path always fires.
  - **Mini-player play-resume after recents-swipe.** After `am stop-app`, the mini-player correctly restored to `Jaiye Sajana` but a media-key PLAY started `Hayyoda` (queue first item, similarity 1.0) instead of resuming Jaiye Sajana. Could be Media3 design (transport rebuilt fresh, no idle-resume) or a real UX gap; worth a deliberate decision.
  - **`Pending embeddings saved: 12 entries`** count discrepancy logged at end of the 11-song batch — likely off-by-one because the JSON includes a `_path_index` slot alongside the 11 song entries; cosmetic, not a data issue.
  Logfiles: `soak_logs/soak_20260426_133610.log` (run 1, 13 songs + stop-app + resume), `soak_logs/verify_A_20260426_135427.log` (cold-start merge isolation), `soak_logs/soak2_20260426_135546.log` (run 2, 11 songs + in-foreground completion + cold-start merge confirmation).

- 2026-04-26: AI-page cleanup popup visibility / scroll fix landed. `src/app.js` `showEmbeddingDetail()` now tracks unmatched/orphan confirm visibility in app state instead of raw `document.getElementById(...).style.display` toggles, opening either confirm can re-anchor the detail page to that confirm after rerender, the unmatched cleanup action block now renders outside the inner `unmatchedSongsList` scrollbox, and remove/cancel flows clear the corresponding confirm state explicitly. Added focused Playwright coverage in `tests/e2e/embedding-detail.regression.spec.js`, which seeds unmatched + orphaned embeddings and verifies both confirms remain visible/reachable across forced detail-page rerenders. Verification after this pass: `npm.cmd run build` OK; `npx.cmd playwright test tests/e2e/embedding-detail.regression.spec.js --config playwright.config.mjs` OK. Known unrelated live-tree issue remains: the broader `npm.cmd run test:ui` suite still fails the seeded restore bootstrap in `tests/e2e/app.smoke.spec.js` (`#npTitle` expected `Beta`, received `---`).

- 2026-04-26: `src/app.js` no-behavior-change support split campaign advanced across six live helper-module extractions. Current live structure: `src/app-debug.js` (debug logger + global error hooks), `src/app-status-ui.js` (status toast + recommendation rebuild status), `src/app-art.js` (album-art queue / fallback recovery), `src/app-playlists-ui.js` (playlist picker + CRUD modal actions), `src/app-back-navigation.js` (Android back-button decision flow), and `src/app-browse-render.js` (browse/search render helpers). `src/app.js` has been reduced from 5,229 lines to 4,830 lines so far (-399 / ~7.6%), while `src/engine.js` remains unchanged at 6,563 lines. Verification at the latest green boundary: `npm.cmd run test:unit` OK (30 tests in 3.9s), `npm.cmd run test:ui` OK (4 Playwright smoke tests in 40.8s), `npm.cmd run build` OK, `npx.cmd cap sync android` OK, Android `:app:testDebugUnitTest` OK, Android `:app:assembleDebug` OK. New debug APK: timestamp `2026-04-26 01:44:40`, ~365.7 MiB. `:app:connectedDebugAndroidTest` has not been rerun during these structural slices.
- 2026-04-26: a narrow live JS-side mitigation for the embedding/playback UI-lag issue landed in `src/app.js`. The active embedding UI path now ignores duplicate `embeddingProgress` payloads before scheduling a UI refresh, which prevents the `requestEmbeddingStatus()` recovery poll from re-rendering the embedding UI repeatedly when native status has not actually advanced. This is intentionally smaller than the parked `src/app.js.WIP` experiment: the broader MediaStore/content-observer rescan path and other WIP diagnostics were **not** merged in this pass, so startup/cold-start behavior stays on the previously verified path while reducing avoidable embedding-status UI churn. Verification after this pass: `npm.cmd run test:unit` OK (30 tests in 3.8s), `npm.cmd run test:ui` OK (4 Playwright smoke tests in 39.3s), `npm.cmd run build` OK, `npx.cmd cap sync android` OK, Android `:app:testDebugUnitTest` OK, Android `:app:assembleDebug` OK. New debug APK: timestamp `2026-04-26 01:07:09`, ~365.7 MiB. `:app:connectedDebugAndroidTest` was not rerun in this pass.
- 2026-04-25: fresh-APK main-process stall diagnosis and stabilization pass landed. A new on-device `DBG` report copy path now exports runtime snapshot + native audio/queue state + recent DBG/embedding/activity logs + UI-stall watchdog data, which made a fresh-build repro diagnosable without multi-step manual log collection. The captured `debug.txt` showed the critical issue was not direct `:ai` -> `:playback` interference, but `:main` instability: long WebView stalls, duplicate `queueCurrentChanged` / play-state events, JS/native playback-state divergence, and missed embedding callbacks while native embedding kept progressing. The landed fix set hardens `src/app.js` with play-state dedupe, stale/duplicate queue-transition rejection, and native queue-truth UI resync; hardens `src/engine.js` so `onNativeAdvance(...)` is idempotent and duplicate embedding progress/complete signatures are ignored; and adds embedding-status recovery via `requestEmbeddingStatus()` polling plus a `MusicBridgePlugin` fallback that emits `embeddingComplete` when `MSG_STATUS` transitions from active -> inactive after a missed callback burst. Verification after this pass: `npm.cmd run test:unit` OK (30 tests), `npm.cmd run build` OK, `npx.cmd cap sync android` OK, Android `:app:compileDebugJavaWithJavac` OK, Android `:app:assembleDebug` OK. New debug APK: timestamp `2026-04-25 19:49:14`, ~365.4 MiB.
- 2026-04-25: stable embedding-store hardening + first broader native recommendation follow-up landed for Option B. `src/embedding-cache.js` and `MusicBridgePlugin.convertEmbeddingsJsonToBinary(...)` now write a versioned `ISAIEMB3` stable binary store atomically, `EmbeddingSimilarityIndex` validates the header and exact float payload length, `Because You Played` now uses native `:ai` nearest-neighbor lookup by source song with JS fallback, and the dead same-process `embedSingleSong` utility was removed from `MusicBridgePlugin`. Verification after this pass: `npm.cmd run test:unit` OK (29 tests), `npm.cmd run build` OK, Android `:app:compileDebugJavaWithJavac` OK.
- 2026-04-25: DBG button re-enabled in shipped UI (`index.html` `#debugLogToggle` / `#debugLogPanel` block uncommented) so on-device diagnostics are available without rebuilding next time. No engine / native code changes in this pass — read-only investigation against current source plus a clean rebuild. Triggered by a user report of 8 issues (scan toast hang, playback transport unresponsive, Taste Signals page unreachable, Discover sections never loaded, mini-player missing on cold start, AI count mismatch, Browse back-nav, occasional crash) against the previously installed APK; **none of those issues reproduced after a clean `gradlew clean assembleDebug`** rebuild from current source. Root cause was an APK-vs-source drift, not a live regression. Option B implementation status was also cross-checked claim-by-claim against source; results table inserted under "Embedding Architecture Migration — Option B Status / Source verification (2026-04-25)". Fresh DBG log capture (`logs.txt`) confirmed the full init chain (`loadData` → `restorePlaybackState` → `background scan started` → `embeddingsReady` → `scanComplete` → `aiReady`) completes in ~9 seconds with normal pause / song-change behavior afterwards. Verification after this pass: `npm.cmd run test:unit` OK (29 tests in 4.5s), `npm.cmd run test:ui` OK (4 Playwright tests in 39.6s), `npm.cmd run build` OK, `npx.cmd cap sync android` OK, Android `:app:testDebugUnitTest` OK (BUILD SUCCESSFUL in 18s), `:app:connectedDebugAndroidTest` OK on `Medium_Phone_API_36` emulator (5 tests in 1m 27s), `gradlew clean` + `:app:assembleDebug` OK (APK timestamp 2026-04-25 15:11, ~382.8 MB). Lesson: when a bug report comes in, confirm the installed APK timestamp matches a fresh `assembleDebug` output before spending investigation time. Two cleanup follow-ups noted but not landed this pass: (a) delete the dead post-`return` code inside `_setupEmbeddingListeners`'s `embeddingSongComplete` handler in `src/engine.js`; (b) eventually remove the legacy `MusicPlaybackService` rollback branch once `:playback` has accumulated a real-device soak.

- 2026-04-23: Media3 playback-start hotfix landed after a report that tapping songs did not start playback. `Media3PlaybackControllerClient` now clears failed/disconnected controllers and retries custom commands/direct controller actions once, queue commands include a JSON fallback payload in addition to the bundle payload, native playback-start/error logs were added, and JS now surfaces a toast if native playback fails after retry. Added `Media3PlaybackServiceConnectedTest`, which generates a WAV file on the emulator, sends `setQueue`, and verifies `isPlaying=true` from the Media3 service. Verification after this pass: `npm.cmd run test:unit` OK, `npm.cmd run build` OK, `npx.cmd cap sync android` OK, Android `:app:compileDebugJavaWithJavac` OK, Android `:app:compileDebugAndroidTestJavaWithJavac` OK, focused `Media3PlaybackServiceConnectedTest` OK, Android `:app:testDebugUnitTest` OK, full Android `:app:connectedDebugAndroidTest` OK on emulator with 5 tests, Android `:app:assembleDebug` OK.
- 2026-04-23: native `:ai` nearest-neighbor RPC first slice landed. `EmbeddingSimilarityIndex` now runs in `:ai`, reads the binary embedding store + metadata, and returns lightweight filepath/content-hash/similarity results through `MusicBridge.findNearestEmbeddings(...)`; `For You` candidate-pool search uses it with JS fallback. Early native media actions are now retained until JS registers `mediaAction`, fixing the cold-start notification-dismiss race found by connected tests. The AndroidX Startup provider crash on API 36 emulator was avoided by removing that provider from the merged app manifest. Verification after this pass: `npm.cmd run build` OK, `npm.cmd run test:unit` OK, `npx.cmd cap sync android` OK, Android `:app:compileDebugJavaWithJavac` OK, Android `:app:testDebugUnitTest` OK, Android `:app:connectedDebugAndroidTest` OK on emulator, Android `assembleDebug` OK.
- 2026-04-23: playback-aware embedding scheduler/backoff landed while intentionally keeping whole-song decode. `:main` now sends cooldown hints to `:ai` after playback starts/resumes/seeks/track transitions/errors, `EmbeddingService` yields at decode / ONNX-window / song boundaries, can switch ONNX down to one thread under playback/cooldown/device pressure, backs off under battery saver / thermal pressure, and no longer runs periodic explicit `System.gc()` after successful songs. Verification after this pass: `npm.cmd run build` OK, `npm.cmd run test:unit` OK, Android `:app:compileDebugJavaWithJavac` OK, Android `:app:testDebugUnitTest` OK, Android `assembleDebug` OK.
- 2026-04-22: initial `:ai` embedding-process split landed. `EmbeddingForegroundService` now runs in `:ai`, `MusicBridgePlugin` talks to it through `EmbeddingControllerClient` (Messenger), embedding events no longer send full vectors over the JS bridge, JS now reloads / merges embeddings from disk on batch completion, and verification after this pass was `npm.cmd run build` OK, `npm.cmd run test:unit` OK, Android `:app:compileDebugJavaWithJavac` OK, Android `:app:testDebugUnitTest` OK, Android `assembleDebug` OK.
- 2026-04-19: robust pending-listen recovery landed. Background snapshot is evidence; native transition history is authority.
- 2026-04-19: native accumulator became the sole listen-fraction authority; natural completion no longer forces `1.0`.
- 2026-04-19: Discover cold-start behavior was stabilized for `Most Similar` and `Unexplored Sounds`.
- 2026-04-19: Discover-page `Most Similar` no longer falsely reports that embedded songs have no similar songs.
- 2026-04-19: Taste Signal gained `Copy Last 30 Signals`.
- 2026-04-19: Android Bluetooth / media-session identity and audio-focus handling were hardened; APK rebuilt successfully.
- 2026-04-20: Android lockscreen / notification player gained working `favorite` and `close` actions with custom compact / expanded layouts.
- 2026-04-20: `For You`, `Because You Played`, `Unexplored Sounds`, and dynamic `Up Next` now share one taste-aware recommendation policy; `Most Similar` remains pure similarity.
- 2026-04-20: Taste Signal now separates `direct` score from `similarity` neighbor influence and marks strong direct-negative songs that are blocked from taste-based recommendations.
- 2026-04-20: web build, Capacitor sync, and Android `assembleDebug` were rerun successfully after the recommendation-policy changes.
- 2026-04-20: Discover playback no longer refreshes `Because You Played` / `Unexplored Sounds` during ordinary song taps; those sections stay stable until manual refresh.
- 2026-04-20: `For You` main-row rendering was hardened so an empty stale Discover row is rebuilt when valid `For You` data exists.
- 2026-04-20: `For You` now actually rebuilds on manual refresh. Previously `refreshForYou: true` was ignored because the rebuild branch was gated on the cache being null; the cache is now invalidated when refresh is requested and the rebuild fires.
- 2026-04-20: `For You` rebuild now pulls a larger candidate pool and random-samples 15 from it so each explicit refresh surfaces a visibly different (but still high-similarity) slate, instead of the same deterministic top-15 every time.
- 2026-04-20: `For You` rebuild pool sized up to 120 and the 15 most recently shown songs are excluded on the next rebuild so back-to-back refreshes cannot repeat picks. Falls back to the full pool if exclusion would starve it below 15.
- 2026-04-20: `For You` pool generation now bypasses `rec.recommend`'s MMR loop and uses raw nearest-neighbor (`rec._findNearest`) directly. The MMR loop was O(topN^2 * pool); at pool=120 this was ~27x the compute of the previous pool=40 path and caused noticeable Discover-page / mini-player hangs on manual refresh. MMR diversity is not needed when the pool is about to be randomly shuffled anyway.
- 2026-04-20: `Last 30 Playback Signal Updates` rows now show three clearly separated score lines — `Direct play effect` (before → after directScore with signed delta), `Similarity delta effect` (current similarityBoost snapshot), and `Total score` (before → after). The redundant `Taste score` line was removed. `Copy Last 30 Signals` mirrors the same three lines. Field values reuse the already-captured `tasteBefore` / `tasteAfter` snapshots, so each entry stays frozen at the moment the event was logged.
- 2026-04-20: web build, Capacitor sync, and Android `assembleDebug` were rerun successfully after these changes.

- 2026-04-20: Discover refresh behavior was tightened so full Discover refresh is now manual pull-to-refresh only. Background AI-ready / embedding-complete / delete paths no longer regenerate the non-similar Discover sections; `Most Similar` remains the only live-updating Discover section.
- 2026-04-20: Discover cache saves now preserve the current visible Discover snapshot instead of silently recomputing `For You` / `Because You Played` / `Unexplored Sounds` during ordinary state saves.
- 2026-04-20: Clarified `Unexplored Sounds` semantics: it excludes songs with recorded starts / plays, not favorites, and an older cached snapshot can temporarily keep an already-played / favorited song visible until manual pull-to-refresh.
- 2026-04-20: Taste Signal now notes that very early neutral skips under 10% listened do not appear in `Last 30 Playback Signal Updates`, `Engine Snapshot` defaults collapsed, and `Taste Logs` were moved below `Library Signals`.
- 2026-04-20: Android lockscreen / notification-shade `favorite` and `close` actions were fixed by wiring MediaSession custom actions through the same native handlers as service intent actions. Web build, Capacitor sync, and Android `assembleDebug` succeeded after the fix.
- 2026-04-20: Playback-start bookkeeping was hardened across JS suspension / restore paths. Native-adopted current songs now ensure a single `song_played` start per native `playbackInstanceId`, and pending-listen recovery backfills a missing start before applying the recovered listen result. Web build, Capacitor sync, and Android `assembleDebug` succeeded after the fix.
- 2026-04-20: Phase 1 automated regression coverage landed with `Vitest`. Current tests cover deterministic engine paths for favorites / dislikes mutex behavior, pending-listen snapshot persistence, recovered-listen stats, filename-based restore, and engine reset. `npm.cmd run test:unit`, `npm.cmd run build`, `npx.cmd cap sync android`, and Android `assembleDebug` all succeeded after landing.
- 2026-04-20: `getSongPlayStats()` no longer double-counts same-session recovered listens by merging live session entries on top of an already-updated recommendation summary. This fixes inflated play counts in song details after recovery paths.
- 2026-04-20: Phase 2 browser smoke coverage landed with `Playwright`. Browser tests now run the real web app against seeded `Preferences` state plus a browser-side `MusicBridge` mock, covering seeded restore, songs search, song-details stats, and favorites toggle flow. `npm.cmd run test:ui`, `npm.cmd run test:unit`, `npm.cmd run build`, `npx.cmd cap sync android`, and Android `assembleDebug` all succeeded after landing.
- 2026-04-20: Phase 3 Android-native regression coverage landed with `Robolectric`. Native tests now cover `MusicPlaybackService` storage / recovery paths for favorite-dislike mutex behavior, saved playback-state clearing, and recent playback-transition retrieval. `testDebugUnitTest` and Android `assembleDebug` succeeded after landing. The stale default Capacitor example tests were removed, and the remaining instrumented package-name stub was corrected to `com.musicplayer.app.test`.
- 2026-04-20: Phase 4 Android connected smoke coverage landed with app-scoped `:app:connectedDebugAndroidTest`. The emulator-backed smoke path now drives the real Android WebView app through seeded restore, Songs search, song-menu favorite toggle, and Browse favorites count verification. The smoke test was rewritten to drive the WebView DOM directly instead of using focus-sensitive `Espresso-Web`, which made the connected layer stable on the emulator. `:app:connectedDebugAndroidTest` and `:app:assembleDebug` both succeeded after landing.
- 2026-04-20: Android connected smoke coverage was expanded to exercise the native media-action bridge used by notification / lockscreen controls. The emulator-backed suite now verifies native `favorite` updates the real WebView mini-player state, Browse favorites count, and persisted favorites storage, and verifies native `dismiss` clears the mini-player plus saved `playback_state`. `:app:connectedDebugAndroidTest` succeeded after tightening the assertions to stable state changes instead of transient toast timing.
- 2026-04-20: Second pending-coverage round landed. `tests/engine.pending-coverage.test.js` grew to 17 tests (added `setFavoriteState(false)` does not resurrect a prior dislike, `toggleFavorite` from disliked surfaces in `getFavoritesList`, `getTasteSignal` summary shape with no embedded rows, and `validateDiscoverCache` recommendation-fingerprint gating behavior). Playwright smoke suite gained a loop UI test that verifies the loop button cycles off → one → all → off and forwards mode values (0, 1, 2, 0) to `MusicBridge.setLoopMode`. The loop test carries its own 60s timeout because the fourth sequential Playwright test tended to trip the default 30s beforeEach budget on this machine. `npm.cmd run test:unit`, `npm.cmd run test:ui`, `npm.cmd run build`, `npx.cmd cap sync android`, `testDebugUnitTest`, and Android `assembleDebug` all succeeded after landing. `:app:connectedDebugAndroidTest` was not run (no emulator attached).
- 2026-04-20: Engine-level pending-coverage landed in `tests/engine.pending-coverage.test.js` (13 tests): playlist CRUD lifecycle and duplicate-name guards, delete-song propagation into favorites / playlists / queue, Up Next editing (`addToQueue`, `playNext` dedupe, `removeFromQueue` including invalid-index rejection), `setQueueShuffleEnabled` round-trip, `playFromAlbum` ordered-mode behavior, Discover cache save/load roundtrip, and `validateDiscoverCache` stale-section flagging when a section references a missing song id. `npm.cmd run test:unit`, `npm.cmd run test:ui`, `npm.cmd run build`, `npx.cmd cap sync android`, `testDebugUnitTest`, and Android `assembleDebug` all succeeded after landing. `:app:connectedDebugAndroidTest` was not re-run this pass because an emulator was not attached — the new tests are web-side only and do not touch the Android layer.

- 2026-04-21: Discover cache behavior on cold start was repaired across three regressions that surfaced after the manual-only Discover refresh + cache model landed.
  - `renderCachedUnexplored()` no longer goes through the embedding-readiness guard in `_renderUnexploredSections()`. It now renders the cached `Unexplored Sounds` sections directly when the cache has content, so the section appears immediately on cold start instead of showing a "Loading unexplored sounds" placeholder until embeddings finish merging. The embedding-aware placeholder is only shown when the cache genuinely has zero sections.
  - `loadDiscover()` now saves the Discover cache via `_saveVisibleDiscoverCacheDebounced()` after `engine.analyzeProfile(...)` resolves and `renderProfile(data)` populates `_lastProfile.forYou`. Previously the cache was saved earlier in `_commitAiReady`, before `analyzeProfile` had resolved, so `_lastProfile.forYou` was empty in the saved snapshot. The next cold start loaded an empty For You and, because `loadDiscover()` is skipped when a cache is present, never refilled it.
  - `_commitAiReady()` now performs a one-time repair via `_repairDiscoverCacheOnReady()` when embeddings are confirmed ready. If the cached `For You` slot is empty, `engine.analyzeProfile(15, { refreshForYou: false })` runs once to populate it and the cache is saved. If `_cachedBecauseYouPlayed` has fewer than 3 sections, `engine.getBecauseYouPlayed(3, 6, {})` is recomputed once and accepted only if it yields MORE sections than currently cached (otherwise the existing cache is preserved). This is a one-shot fix for broken caches; it is not an automatic refresh and does not fire again within the session.
- 2026-04-21: Mini-player cold-start preservation was hardened. A new `_miniPlayerFromQuickDisplay` flag is set when `getLastPlayedDisplay()` succeeds and cleared only when the real engine state confirms a current song. `refreshStateUI()` no longer hides the mini player while this flag is set — the earlier regression where `refreshStateUI()` in `_commitAiReady` hid the `quickDisplay`-shown mini player if `engine.restorePlaybackState()` returned null (filename not yet resolvable by the partial song library) is eliminated. `onScanComplete` now retries `engine.restorePlaybackState()` once when `currentSong` is still null and quickDisplay had data, so by the time the scan completes and the library is authoritative the mini player reflects the real engine state.
- 2026-04-21: Issue-2 diagnostic logging landed behind the DBG panel. The `#debugLogToggle` / `#debugLogPanel` block in `index.html` was re-enabled (previously commented out) so `_dbg(...)` lines render on-device. New `_dbg(...)` calls were added on the suspected auto-skip paths: `loadAndPlay` entry (songId/items/startIndex/seekTo/embReady) and the setQueue/playAudio outcome, `queueCurrentChanged` (prev currentSong + embReady + aiReady context), `audioPlayStateChanged` (isPlaying + currentSong + embReady + aiReady), native `embeddingComplete` listener, and `_commitAiReady` entry with cached-section counts. When the song auto-advance reproduces, the DBG log should expose the event sequence that triggered the transition.
- 2026-04-21: Issue-2 root cause identified and fixed. DBG logs from an on-device repro showed `setQueue OK` at t=0 followed by `queueCurrentChanged action=auto_advance songId=<next>` ~143 ms later — well before the requested track could have played. Traced to `MusicPlaybackService.playFile`'s `setOnErrorListener`, which unconditionally called `advanceAuto()` on any `MediaPlayer` async error, including errors that fired during `prepareAsync()` / pre-start seek. On cold start after a long idle, the first prepare of the restored song would occasionally hit a transient error and the service would silently skip to the next queue item before the user's song ever started. The fix adds a `currentTrackStarted` flag on the service, set true in `onPrepared` after `mp.start()` and reset in `playFile`; the error listener now only auto-advances when the error happens after the track actually started, otherwise it stays on the same queue index so the user can retry without losing their song. `emitError` now also appends " (during prepare)" so JS logs disambiguate the two paths. The JS `audioError` listener was upgraded from `console.error` only to also emit `_dbg(...)` with the error string, `currentSong`, `nativeFileLoaded`, and `nativeAudioPos` for on-device diagnosis.
- 2026-04-21: DBG button / on-screen debug log panel re-hidden in `index.html` now that Issue 2 has been root-caused and fixed. The `_dbg(...)` lines added for Issue 2 diagnostics remain in the code and still write to `console.log` (visible via `adb logcat` or devtools); they just have no on-device panel anymore. Un-comment the `#debugLogToggle` / `#debugLogPanel` block to re-enable on-device visibility if another hard-to-trace issue surfaces.
- 2026-04-21: Publish-ready `git_upload/` folder assembled for GitHub release. Includes `src/`, `tests/`, `android/` (without `build/`, `.gradle/`, `.idea/`, `.kotlin/`, `local.properties`, `assets/public/` sync output, and the 285 MB `clap_audio_encoder.onnx{,.data}` on-device model), `index.html`, `style.css`, all config files (`package.json`, `package-lock.json`, `capacitor.config.json`, `vite.config.js`, `vitest.config.mjs`, `playwright.config.mjs`), `colab_embedding_generator.py`, both `project_development*.md` logs, a new `README.md`, MIT `LICENSE` with a subtle "fork it / ship it / sell it" note, `.gitignore`, and the 362 MB `app-debug.apk`. Final repo payload is 1.4 MB of source; the 362 MB APK lives in `git_upload/releases/app-debug.apk` as a staging folder for GitHub Release assets (it's over GitHub's 100 MB per-file limit and is not committed — `.gitignore` excludes both the file and the `releases/` folder). `package.json` `author` and `license` fields were updated to "Yuvaraj M P" / "MIT". No source file splits were performed; large files (`engine.js` 6739 lines, `app.js` 5142 lines, `MusicPlaybackService.java` 1778 lines, `MusicBridgePlugin.java` 1253 lines) were intentionally left intact to avoid regression risk before publish.

- 2026-04-21: External-file-deletion reconciliation landed. Previously, when a user deleted a song file via file manager (or any path outside the app), the song kept appearing everywhere because `_backgroundScan` upsert-only logic never removed stale `songs[]` entries whose files were absent from the new scan. The cache-skip startup path (<6h library cache) also skipped deletion detection entirely, and `getPlayableSongs()` returned all songs regardless of `filePath`, so even if an entry were marked orphan the Songs tab would still render it.
  - Full-scan path now diffs `prevFilePaths` (from the cached library) against `scannedPaths` (from the fresh scan) and calls `_markSongsMissing(missingPaths, reason)` for files no longer on device. A guard skips the deletion pass if the scan returned zero files while the cache had songs (SD-card unmount / MediaStore anomaly) so a transient scan failure cannot wipe the library.
  - Cache-skip startup path schedules a deferred, fire-and-forget `scanAudioFiles()` 3 seconds after startup via `_scheduleDeferredDeletionCheck()` that runs only the path-diff + `_markSongsMissing` pass, and fires a new `onSongLibraryChanged` callback so the UI re-renders without needing a restart.
  - New engine helper `_markSongsMissing` (internal) and `markSongMissingByPath` (exported) clear `filePath` on matching songs, remove them from favorites / queue / history / listened / currentAlbumTracks / playlists, invalidate For You + Unexplored caches, re-save song library, and re-run `_identifyOrphans()`. `hasEmbedding` / `embeddingIndex` / `contentHash` are intentionally preserved so the song lands in the AI-page Orphaned bucket for consent-gated embedding removal via the existing `removeOrphanedEmbeddings()` flow.
  - `getPlayableSongs()` now filters by `s.filePath`, which is the semantic intent of the name. `getSongs()` still returns the full `songs[]` for callers that need stable index-based embedding references. This alone closes the "deleted song keeps appearing on the Songs tab" loop; downstream callers that had defensive `.filter(s => s.filePath)` on top remain correct.
  - Android `MusicPlaybackService.playFile` catch block now detects `FileNotFoundException` and emits a structured `audioError` with a stable `File not found:` prefix plus the path. The JS `audioError` listener calls `engine.markSongMissingByPath(path)` on file-missing, re-renders Songs / mini-player, and shows a `Song file no longer on device — removed from library` toast. The existing `handleSynchronousPlaybackFailure` still auto-skips past the broken entry so the queue doesn't stall.
  - AI-page Orphaned Embeddings description was rewritten to reflect that files can disappear via file manager, other apps, or removed storage.
  - Coverage: `tests/engine.pending-coverage.test.js` grew to 20 tests. New suite `external file deletion reconciliation` (3 tests) covers `markSongMissingByPath` full cleanup, no-op for a non-matching path, and `getPlayableSongs` filtering.
  - Duplicate filenames across different folders remain supported (`_dedupeSongsArray` keys by `path:filePath`); the new detection keys by filePath, not filename, so duplicates are not affected.
  - `npm.cmd run test:unit`, `npm.cmd run test:ui`, `npm.cmd run build`, `npx.cmd cap sync android`, `testDebugUnitTest`, and Android `assembleDebug` all succeeded after landing. `:app:connectedDebugAndroidTest` was not re-run (no emulator attached).

- 2026-04-21: `Last 30 Playback Signal Updates` short-skip filter was broadened to match the UI promise. Previously only `action === 'neutral_skip'` entries with fraction ≤ 10% were suppressed — user-initiated path changes (tapping a different tile, section playback, background recovery, native auto-advance) all bypassed the filter and still showed up in the list even when the user had only listened for 1%. The filter now runs inside `_applyRecordedListen` at the `_rememberPlaybackSignalEvent` call site: any skip (`isSkip && fraction ≤ NEUTRAL_SKIP_CAPTURE_THRESHOLD`) is excluded from the per-playback-result timeline regardless of `transitionAction`. Engine state (`state.listened`, profile summary, negative scores, similarity propagation) is still updated so repeat-sampling evidence is preserved; only the render-level event is suppressed. The Taste Signal intro sub text was updated from "Very early neutral skips under 10% listened are not added here" to "Very early skips under 10% listened are not added here".
- 2026-04-22: App branding and Android identity were renamed for public release. Display name is now `IsaiVazhi`, and Capacitor/Android app id moved from `com.musicplayer.app.test` to `com.isaivazhi.app`. This is a clean identity break on Android, so installs of the older `.test` package do not upgrade in place and old app-private data / embeddings paths are not migrated automatically.
- 2026-04-22: Media3 playback migration moved beyond scaffolding into the active app path. A timestamped safety backup was created at `backups/media3_migration_20260422_222641/` before edits. Android app module now depends on `androidx.media3` (`media3-exoplayer`, `media3-session`), AndroidManifest declares `Media3PlaybackService` in `:playback`, and the shared native Media3 classes now active are `PlaybackCommandContract`, `PlaybackQueueItem`, `Media3PlaybackService`, and `Media3PlaybackControllerClient`. `MusicBridgePlugin` now prefers the Media3 controller/service path for queue + transport commands, relays Media3 playback events back into the existing JS event names, and routes `updatePlaybackState` / `stopPlaybackService` to Media3 as well. The Media3 notification path now restores native `favorite` / `dismiss` actions plus notification refresh after in-app favorite changes. Legacy `MusicPlaybackService` remains in source as rollback only; it is no longer the preferred playback route. Verification after this pass: Android `:app:compileDebugJavaWithJavac` OK, Android `:app:testDebugUnitTest` OK, Android `:app:connectedDebugAndroidTest` OK on emulator, Android `assembleDebug` OK.

## Files Most Relevant To Current Behavior

- `src/app.js`
- `src/app-debug.js`
- `src/app-status-ui.js`
- `src/app-art.js`
- `src/app-playlists-ui.js`
- `src/app-back-navigation.js`
- `src/app-browse-render.js`
- `src/engine.js`
- `src/music-bridge.js`
- `android/app/build.gradle`
- `android/app/src/main/java/com/isaivazhi/app/MusicPlaybackService.java`
- `android/app/src/main/java/com/isaivazhi/app/Media3PlaybackService.java`
- `android/app/src/main/java/com/isaivazhi/app/PlaybackCommandContract.java`
- `android/app/src/main/java/com/isaivazhi/app/PlaybackQueueItem.java`
- `android/app/src/main/java/com/isaivazhi/app/Media3PlaybackControllerClient.java`
- `android/app/src/main/java/com/isaivazhi/app/EmbeddingControllerClient.java`
- `android/app/src/main/java/com/isaivazhi/app/EmbeddingForegroundService.java`
- `android/app/src/main/java/com/isaivazhi/app/EmbeddingService.java`
- `android/app/src/main/java/com/isaivazhi/app/MusicBridgePlugin.java`
- `android/app/src/test/java/com/isaivazhi/app/MusicPlaybackServiceRobolectricTest.java`
- `android/app/src/androidTest/java/com/isaivazhi/app/MusicAppInstrumentedTest.java`
- `android/app/src/androidTest/java/com/isaivazhi/app/MainActivityWebViewSmokeTest.java`
- `android/app/src/androidTest/java/com/isaivazhi/app/Media3PlaybackServiceConnectedTest.java`
- `android/app/src/main/res/layout/notification_player_compact.xml`
- `android/app/src/main/res/layout/notification_player_expanded.xml`
- `index.html`
- `style.css`
- `tests/engine.regression.test.js`
- `tests/engine.pending-coverage.test.js`
- `tests/e2e/app.smoke.spec.js`

## Maintenance Rule

- Keep this file short and current.
- Put superseded analysis, experiments, and long debugging transcripts into the archive.
- When updating this file, describe landed behavior and current product state, not every intermediate investigation step.
