# Project Development Log

Last updated: 2026-04-21 (Discover cache repair on aiReady + cached unexplored renders before embeddings + For You cache save after analyzeProfile resolves + mini-player quickDisplay preservation + scanComplete restore retry + DBG panel re-enabled with Issue 2 tracing logs + manual-only Discover refresh + unexplored/favorite clarification + taste-signal layout note + for-you refresh variance + clarified three score lines on Last 30 Playback Signal Updates + Android media-action fix + playback-start recovery fix + regression-suite landing + song-stats double-count fix + Playwright smoke coverage + Android Robolectric regression coverage + Android connected smoke coverage + Android connected media-action coverage + pending-coverage testability matrix + engine-level pending-coverage Vitest suite + taste-signal shape + loop UI Playwright coverage + git_upload publish folder prepared + external-file-deletion reconciliation + Orphaned vs Unmatched AI-page clarification)

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

Last verified: 2026-04-21
- `npm.cmd run test:unit` - OK (27 tests across 2 files, 20 in engine.pending-coverage.test.js)
- `npm.cmd run test:ui` - OK (4 Playwright smoke tests incl. loop UI)
- `npm.cmd run build` - OK
- `npx.cmd cap sync android` - OK
- `testDebugUnitTest` - OK
- `:app:connectedDebugAndroidTest` - skipped this run (requires emulator)
- `assembleDebug` - BUILD SUCCESSFUL

APK output:
- `android/app/build/outputs/apk/debug/app-debug.apk`

## Current Source Of Truth

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
  - app-scoped Android connected smoke test
  - Android debug build
  - focused manual playback / Discover / media-control checks on device

### App Identity
- Display name is `Music App`.
- Android package / app id remains `com.musicplayer.app.test`.
- A future package-id rename would be a real migration decision, not just a cosmetic label change.

### Playback And Listen Capture
- Native Android playback is the transport authority during active playback.
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

### Bluetooth And Background Playback
- The playback service now seeds friendly app metadata before the first track metadata arrives.
- Media session and notification use the app display name (`Music App`) and share a consistent content intent.
- Audio focus is tracked explicitly across request / gain / loss / abandon.
- Notification and media-session metadata now fall back to app-name metadata when track metadata is missing.
- Lockscreen and notification player now expose app-wired `favorite` and `close` actions.
- The native notification uses custom compact / expanded layouts with the shorter timeline bar requested for those extra controls.
- Notification / lockscreen `favorite` toggles the same current-song favorite state used inside the app.
- Notification / lockscreen `close` dismisses the mini player / native player surface instead of only hiding controls.
- MediaSession custom actions for `favorite` / `close` are now handled natively as well, so Android system media controls (lockscreen / notification shade player) no longer depend on custom notification-view click wiring alone.
- Remaining limitation:
  - the package / app id is still `com.musicplayer.app.test`
  - if a car head unit still shows a package-like identifier after the metadata fixes, the next step would be an explicit package rename / migration decision

## Latest Verified Changes

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

## Files Most Relevant To Current Behavior

- `src/app.js`
- `src/engine.js`
- `src/music-bridge.js`
- `android/app/build.gradle`
- `android/app/src/main/java/com/musicplayer/app/MusicPlaybackService.java`
- `android/app/src/main/java/com/musicplayer/app/MusicBridgePlugin.java`
- `android/app/src/test/java/com/musicplayer/app/MusicPlaybackServiceRobolectricTest.java`
- `android/app/src/androidTest/java/com/musicplayer/app/MusicAppInstrumentedTest.java`
- `android/app/src/androidTest/java/com/musicplayer/app/MainActivityWebViewSmokeTest.java`
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
