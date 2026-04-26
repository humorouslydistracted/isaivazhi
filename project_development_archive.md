# Project Development Archive

This archive keeps only compact historical context that is still useful.

If this file, `project_development.md`, and live code ever disagree, prefer:
1. live code
2. `project_development.md`
3. this archive only as background

## 2026-04-22

- App branding / identity renamed for public release: display name is `IsaiVazhi`; Android app id moved from `com.musicplayer.app.test` to `com.isaivazhi.app`. Clean break — no in-place upgrade from the old package.
- Media3 playback migration moved from scaffolding into the active app path. `Media3PlaybackService` declared in `:playback`, `MusicBridgePlugin` now prefers the Media3 controller for queue + transport commands, native `favorite` / `dismiss` actions and notification refresh restored on the Media3 path. Legacy `MusicPlaybackService` retained as rollback only.
- Initial `:ai` embedding-process split landed: `EmbeddingForegroundService` now runs in `:ai`, `MusicBridgePlugin` talks to it through `EmbeddingControllerClient` (Messenger), embedding events no longer send full vectors over the JS bridge, JS reloads / merges embeddings from disk on batch completion. (Subsequent landings in 2026-04-23+ added playback-aware scheduling, similarity RPC, and store hardening on top of this split — see `project_development.md` Option B status section for current state.)

## 2026-04-21

- Discover cache cold-start behavior was repaired across three regressions: `renderCachedUnexplored()` no longer goes through the embedding-readiness guard; `loadDiscover()` now saves the cache after `analyzeProfile` resolves so `forYou` is populated; `_commitAiReady()` runs a one-time `_repairDiscoverCacheOnReady()` to repopulate empty `For You` / under-filled `Because You Played`.
- Mini-player cold-start preservation hardened with a `_miniPlayerFromQuickDisplay` flag and an `onScanComplete` retry of `engine.restorePlaybackState()` once the song library is authoritative.
- Issue-2 (cold-start auto-skip) root-caused: `MusicPlaybackService.playFile`'s `setOnErrorListener` unconditionally called `advanceAuto()` on transient pre-start `prepareAsync()` errors. Fix: a `currentTrackStarted` flag gates auto-advance to post-start errors only; `emitError` disambiguates "during prepare". DBG panel was added for the diagnosis and re-hidden after the fix.
- Publish-ready `git_upload/` folder assembled for GitHub release. 1.4 MB source payload + 362 MB `app-debug.apk` staged under `releases/` (excluded by `.gitignore`; over GitHub's 100 MB per-file limit). MIT `LICENSE`, `README.md`, and updated `package.json` author / license added. No source splits performed.
- External-file-deletion reconciliation landed: `_backgroundScan` now diffs `prevFilePaths` vs scanned paths and calls `_markSongsMissing(...)` (with a guard against zero-result scans wiping the library); cache-skip startup schedules a deferred deletion check; `getPlayableSongs()` filters by `filePath`; Android `playFile` `FileNotFoundException` is now a structured `audioError` that triggers `markSongMissingByPath` from JS. `hasEmbedding` / `embeddingIndex` / `contentHash` are preserved so removed files land in the AI-page Orphaned bucket. Coverage grew to 20 tests.
- `Last 30 Playback Signal Updates` short-skip filter was broadened to suppress all skips ≤ 10% fraction regardless of `transitionAction`. Engine state, profile summary, negative scores, and similarity propagation are still updated; only the per-playback-result UI timeline is suppressed.

## 2026-04-20

- Regression coverage landed across four phases: Phase 1 `Vitest` (favorites/dislikes mutex, pending-listen snapshots, recovered-listen stats, filename restore, engine reset); Phase 2 `Playwright` browser smoke (seeded restore, songs search, song-details stats, favorites toggle, loop UI cycle); Phase 3 `Robolectric` Android-native (service storage / recovery paths); Phase 4 app-scoped `:app:connectedDebugAndroidTest` driving WebView DOM directly (seeded restore, songs search, song-menu favorite, native `favorite` / `dismiss` media-action bridge). Engine pending-coverage suite grew to 17 tests.
- `getSongPlayStats()` no longer double-counts same-session recovered listens.
- Discover refresh was tightened to manual pull-to-refresh only; `Most Similar` remains the only live-updating section. Cache saves now preserve the visible Discover snapshot for `For You` / `Because You Played` / `Unexplored Sounds` instead of silently recomputing.
- Recommendation policy unification: `For You`, `Because You Played`, `Unexplored Sounds`, and dynamic `Up Next` now share one taste-aware policy. `Most Similar` stays pure similarity.
- Taste Signal updates: `direct` vs `similarity` neighbor influence separated; strong direct-negative recommendation-blocking exposed; `Last 30 Playback Signal Updates` rows now show three score lines (`Direct play effect`, `Similarity delta effect`, `Total score`); `Engine Snapshot` defaults collapsed; `Taste Logs` moved below `Library Signals`.
- `For You` rebuild was repaired: cache is invalidated on refresh request; pool sized to 120 with 15-most-recent exclusion; the MMR diversity loop was bypassed in favor of raw nearest-neighbor (`rec._findNearest`) because MMR at pool=120 was ~27× the compute of the previous pool=40 path and caused mini-player hangs. 15 are random-sampled per refresh.
- Android lockscreen / notification-shade `favorite` and `close` actions were wired through MediaSession custom actions.
- Playback-start bookkeeping hardened across JS suspension / restore: single `song_played` per native `playbackInstanceId`; pending-listen recovery backfills a missing start before applying a recovered listen result.

## 2026-04-19

- Listen-capture design was finalized around `background snapshot = evidence` and `native transition = authority`.
- Native playback fraction moved to accumulated `playedMs / durationMs`, replacing the old raw-position interpretation that overcounted seek-heavy listening.
- Discover startup rendering was stabilized:
  - `Most Similar` keeps a reserved slot
  - `Unexplored Sounds` shows a loading placeholder and then populates automatically once embeddings are ready
- Discover-page `Most Similar` rendering was fixed to align with the already-correct `View All` similar list.
- Taste Signal gained `Copy Last 30 Signals`.
- Android playback service was hardened for Bluetooth / head-unit usage:
  - friendlier app metadata
  - stronger media-session identity
  - explicit audio-focus tracking
- APK was rebuilt successfully after these changes.

## 2026-04-18

- `Most Similar` gained `Freeze` / `Unfreeze`.
- Discover was shifted toward a stable cached snapshot model, with only the similar-songs slot updating live on song change.
- Taste Signal UI was reworked:
  - single signed list
  - tuning controls
  - `Engine Snapshot`
  - `Taste Logs`
  - playback-signal timeline
- `Reset Engine` moved into Taste Signal and now uses a custom confirm modal.
- Favorite / dislike behavior changed from permanent weighting to fading manual priors.
- Browse gained a `Disliked Songs` section.
- App display name was renamed to `Music App` while keeping the existing Android package id.

## 2026-04-17

- Startup / resume and native transport reconciliation were hardened.
- Ordered playback contexts were made explicit and visible in one unified `Up Next` timeline.
- Shuffle behavior was corrected to mean `shuffle the remaining unplayed songs`.
- Taste Signal moved away from the older split positive / negative model toward one signed list plus audit surfaces.
- AI logs were split into `Common Logs` and `Embedding Logs`.
- Album-art loading outside the full player was made more reliable.

## 2026-04-16 And Earlier

- Discover / browse structure was refactored heavily during this period.
- Recommendation tuning knobs were introduced.
- Negative-signal handling expanded beyond one simple repeat-skip rule.
- `Unexplored Sounds` was introduced as a Discover section.

## Cleanup Note

Earlier versions of this archive contained long investigation transcripts, obsolete audits, repeated build logs, and superseded design branches. Those were intentionally removed during the 2026-04-19 documentation cleanup so the archive stays useful as background instead of becoming a competing source of truth.
