# AI Recommendation, Queue, and UI Plan

Date: 2026-06-01
Scope: `native/` Kotlin/Compose Android app.
Companion to `discover_page_loading_delay_diagnosis.md`.

## 1. Problem summary

Three user-visible issues, ranked by impact:

1. **Discover page shows stale/empty AI rows for ~18 s on cold start** even though the player and cached cards are ready within ~2 s.
2. **AI recommendations don't blend into the queue** when a Discover-section queue finishes — the same ~10 songs loop or shuffle forever.
3. **Refresh-queue-with-AI exists only on the Up Next screen.** User wants it reachable from the MiniPlayer and the lockscreen.

Overarching principle the design must satisfy:
> The music player must work like any other offline music player. AI recommendation background work and updates must never be felt by the user.

## 2. Ground truth from `startup_log.txt`

Reconstructed timeline (`t=0` at `MainActivity.onCreate`, 17:36:11.140):

| t (s) | Event | Notes |
|---|---|---|
| 0.00 | `ACTIVITY_RECREATED` | onCreateCount=2, processAgeMs=1.34M, serviceAlive=false |
| 0.12–0.13 | `ON_CREATE/START/RESUME` | Media3 service alive |
| 0.26 | `LIBRARY_LOAD_START` | |
| 0.28 | `DISCOVER_CACHE_LOAD` `loadElapsedMs=170` | DataStore parse |
| 0.33 | `CONTROLLER_READY` 324 ms (preWarm) | |
| 1.08 | `LIBRARY_LOAD_DONE` 819 ms (cache hit, 2471 songs) | |
| 1.29 | `PERF_DB_START` + `PLAYBACK_RESTORE_EFFECT_START` | |
| 1.37 | `DISCOVER_HYDRATE_HIT` (mostSim=10, forYou=12, byp=2, unexp=3) | Cached cards on screen |
| 1.72 | `PLAYBACK_RESTORE_PATH=datastore` (`elapsedBeforePrepareMs=429`) | serviceAlive=true but serviceMediaId="" → fell through to DataStore |
| 1.74 | `PLAYBACK_PREPARE_DONE` 20 ms | |
| 1.91 | `FIRST_MEDIA_ID` 1924 ms | MiniPlayer can render |
| **19.64** | **`PERF_DB_DONE` 18 354 ms** (rows=2455, dim=512, vecExt=true) | **Bottleneck** |
| 19.72 | `DISCOVER_FORYOU_FIRE`/`UNEXP_FIRE` (hasEmb=true) | Fresh AI unblocked |
| 20.13 | `UNEXP_DONE` 138 ms | |
| 20.14 | `PERF_RECOMMEND_DONE` 161 ms (Most Similar) | |
| 20.38 | `FORYOU_DONE` 409 ms | |
| 20.39 | `DISCOVER_CACHE_SAVE` | |

## 3. Root cause — startup delay

`EmbeddingDbManager` serialises every DB call on **one** HandlerThread (`EmbeddingDbFacade.kt` L11–L13).

- `MainActivity.onCreate` (L221) launches `embeddingDb.migrateFromLegacy()`.
- `refreshDbStats()` (L3384–L3402) then enqueues three sequential DB calls: `stats()`, `allFilenames()`, `allFilepaths()`.
- All three queue **behind** the in-flight migration on the same worker → 18 354 ms wall time.
- Fresh For You / Unexplored / Most Similar are gated on `embeddingsRowCount > 0`, which is set only when the `refreshDbStats` callback fires.

The user already has 2455 embeddings; nothing needs re-embedding. The 18 s is migration housekeeping (likely a near-no-op) sitting on the DB worker, blocking the cheap `stats()` the UI needs.

## 4. How the music player + queue + AI work today

### Queue context tag
Every started queue is tagged in `PlaybackEngine.QueueContext`:

| Context | Set when… | AI append on queue end? |
|---|---|---|
| `LIBRARY` | Songs-tab single tap | Yes |
| `DISCOVER_SECTION` | For You / Similar / BYP / Unexplored tap | Yes |
| `ALBUM` | Album track tap | Never |
| `BROWSE_SECTION` | Favorites / Playlist / Search tap | Never |
| `AI_RECOMMENDED` | After Refresh / AI-mode toggle | Yes |

### Queue-end AI append (already exists)
`MainActivity.kt` L1543 — `LaunchedEffect(currentMediaId, queueFilenames.size, queueContext)`:
1. Must be on **last** item of queue (`queueIndex == queueSize - 1`).
2. Context must allow AI (`LIBRARY` / `DISCOVER_SECTION` / `AI_RECOMMENDED`).
3. **`repeatMode == REPEAT_MODE_OFF`** ← most likely cause of "songs loop forever".
4. Embeddings exist.

If all four hold, 500 ms debounce, then `recommendUpcoming(k=50)` with blended query vec → `appendToQueue(tail)` + toast.

### Why "same 10 songs loop" happens
If `repeatMode == ALL`, Media3 wraps queueIndex from last → 0 before the effect can fire. Shuffle alone doesn't extend the queue; it re-permutes. Either condition silently swallows the AI append.

### Refresh button today
`UpNextScreen.onRefresh` (`MainActivity.kt` L2141):
- Builds blended vec (current + session + profile centroid).
- Calls `recommendUpcoming(k=50)`.
- Preserves user "Play Next" items at the front.
- `replaceUpcoming` with `newContext = AI_RECOMMENDED`.
- Shows toast "Up Next refreshed (blend=…)".

## 5. The fixes — phase-by-phase

### Phase A — Confirm startup cause (small, safe)
1. Wrap `migrateFromLegacy()` in MainActivity.onCreate with `PERF_MIGRATE_START` / `PERF_MIGRATE_DONE` ActivityLog (elapsedMs + migrated count).
2. In `refreshDbStats`, log per-step elapsed for `stats()` / `allFilenames()` / `allFilepaths()` separately.
3. Add `STARTUP_FIRST_DISCOVER_AI_RENDER` (first `FORYOU_DONE` after `onCreate`).

### Phase B — Unblock fresh AI Discover (primary startup fix)
4. **Split `refreshDbStats`** into two stages:
   - **Stage 1** — only `stats()` → publish `embeddingsRowCount` / `dim` / `vecExt`. This is the only signal Discover AI gates need.
   - **Stage 2** — `allFilenames()` + `allFilepaths()` separately → publish `embeddedFilenames` / `embeddedFilepaths` (used by AI-page badges, not Discover).
   - Refactor as an overload so call sites (L495, L533, L572, L631, L661, L2472, L2562, L2611, L2688) don't all need touching.
5. Audit `embeddedFilenames` / `embeddedFilepaths` consumers; confirm none gate Discover/MostSimilar.
6. **Move `migrateFromLegacy()` out of the UI process critical path**:
   - **B1 (one-line, recommended now):** call `stats()` once *before* launching `migrateFromLegacy()`; publish the existing rowCount, then start migrate. Discover never waits.
   - **B2 (cleaner long-term):** move `migrateFromLegacy()` invocation into `EmbeddingForegroundService` (`:ai` process), removing it from UI process entirely.

### Phase C — Optimistic MiniPlayer (optional)
7. Save title/artist/art-path into `AppPreferences` snapshot so MiniPlayer renders the last-played song from ~0.3 s. Audio commands still gated on real Media3 controller (no audio correctness change).
8. Suppress no-op `DISCOVER_FORYOU_FIRE` log when `songs=0` or `embeddingsRowCount<=0` (fires 3× per cold start today).

### Phase D — Queue-end AI blending fix
9. In `playFromSection` (L1492) and `playFromTap`, when starting a queue with context ∈ {`DISCOVER_SECTION`, `LIBRARY`, `AI_RECOMMENDED`}, **set `repeatMode = REPEAT_MODE_OFF` once at queue start**. Don't override user's setting after that — if they later turn Repeat back on mid-queue, respect it.
10. Promote the 4 skip branches in the queue-exhaust effect (L1543) from `android.util.Log` to `ActivityLog` so we can see why an append didn't fire on a real device.
11. Verify: play a Discover-section queue to end, confirm AI append + toast.

### Phase E — Refresh button on MiniPlayer + Lockscreen
12. **Extract `refreshUpcomingWithAI()`** as a reusable helper (in `MainActivity` or a thin `QueueActions.kt`). Body is the existing `onRefresh` from UpNext L2141.
13. **MiniPlayer:** add a Refresh icon on the right side. Disabled (greyed) when `embeddingsRowCount == 0` or no current song.
14. **Media3 lockscreen / notification:** register a custom `MediaSession` command `ACTION_REFRESH_QUEUE` in `Media3PlaybackService`. Add it to the custom-layout list so it appears alongside prev/play/next. Service handler invokes the same helper.
15. **NowPlaying full-screen:** add Refresh icon next to Shuffle/Repeat. Do **not** replace Shuffle.

### Decisions / non-goals
- **Do not** replace Shuffle's behavior anywhere — breaks Bluetooth, Android Auto, Wear OS, Google Assistant, and accessibility (all talk to standard MediaSession shuffle).
- **Do not** change `EmbeddingDbManager` threading (single-writer assumption underpins sqlite-vec correctness).
- **Do not** redesign `DiscoverCacheEngine.Snapshot` to be self-rendering — cache hydration is already fast (~1.4 s).
- **Do not** touch the playback-restore service-live check (diagnosis explicitly warns; 1.9 s MiniPlayer isn't the complaint).
- **Do not** modify the soft-refresh-tail effect (working).
- **Do not** change the queue-exhaust lookahead threshold (1 item is fine).

## 6. Expected feel after all phases land

### Scenario 1 — Open app → tap Play in MiniPlayer
| t | Visible |
|---|---|
| 0 s | App icon tapped |
| ~0.3 s | Shell + MiniPlayer with last song title/artist/art (Phase C) |
| ~1.0 s | Library loaded; MiniPlayer fully live |
| ~1.4 s | Cached Discover rows on screen |
| ~1.9 s | MiniPlayer bound to Media3 controller |
| ~2.0 s | Fresh AI Discover quietly replaces cached if different |

Tap Play before ~1.9 s → tap queued, plays as soon as controller binds. After ~1.9 s → instant. Next/Previous same rule.

### Scenario 2 — Open app → tap song in any Discover section
By the time the user's thumb reaches a card (~600–1000 ms), library is loaded and controller is binding. Tap plays in <100 ms typical.

### Scenario 3 — Close from recents → reopen (warm)
Process survives recents-close in most cases. Activity recreate path:
| t | Visible |
|---|---|
| 0 s | Icon tapped |
| ~0.15 s | Activity recreated; Compose redraws from in-memory state |
| ~0.2 s | Discover fully filled; MiniPlayer live with current song |
| ~0.3 s | Tap Play → instant |

True cold start (process killed) falls back to Scenario 1 timings — ~1.9 s, not 20 s.

### Scenario 4 — Listening to a Discover-section queue
- 10 songs play through.
- On the last song's final ~500 ms, AI append fires (Phase D): 50 blended recommendations append silently.
- User hears a seamless transition into AI picks — no gap, no "queue ended".
- Toast: "Up Next refreshed with recommendations".

### Scenario 5 — User wants a fresh AI mix mid-playback
- From MiniPlayer: tap Refresh icon → upcoming replaced with new blended AI tail, Play Next preserved.
- From lockscreen: tap custom Refresh button → same.
- From NowPlaying: tap Refresh next to Shuffle → same.
- From Up Next: existing button, unchanged.

## 7. Verification checklist

Cold start:
- [ ] `PERF_MIGRATE_DONE elapsedMs` is captured (Phase A1) — proves cause.
- [ ] After Phase B: `embeddingsRowCount > 0` within <500 ms of `LIBRARY_LOAD_DONE` (vs. 18 s today).
- [ ] `DISCOVER_FORYOU_DONE` within ~2 s of `onCreate` (vs. ~20 s).
- [ ] `DISCOVER_HYDRATE_HIT` still ~1.4 s (no fast-path regression).
- [ ] `FIRST_MEDIA_ID` ≤ 1.9 s.

Phase C:
- [ ] MiniPlayer shows last song's title/artist within 500 ms of cold start.
- [ ] Tap Play before controller binds → playback starts cleanly when controller is ready, no crash.

Phase D:
- [ ] Start a Discover For-You queue, let it play to the last song, confirm AI tail appends + toast appears.
- [ ] Verify with Repeat=ALL set *after* queue start, AI append still suppressed (user choice respected).
- [ ] Verify Album queue still stops at end (no AI append).

Phase E:
- [ ] MiniPlayer Refresh disabled when no embeddings; enabled and functional otherwise.
- [ ] Lockscreen Refresh button visible and triggers a queue rebuild without unlocking phone.
- [ ] Bluetooth/Auto shuffle/prev/next/play still behave normally (no regression).
- [ ] AI page "Pending songs" and embedding-status dots still render after Stage 2 completes.

Clean-install path:
- [ ] First launch on empty SQLite: rowCount=0, AI rows stay gated, migrate populates rows in `:ai`, next `stats()` (triggered by `EmbeddingEngine.status`) flips the gate; UI never blocks.

## 8. Open questions (please confirm — answered in implementation)

> Answered during implementation (see Section 9).

## 9. Implementation log — what was actually built (2026-06-01)

The plan above was partially revised during implementation. Rather than fixing
Discover's startup delay, we removed it entirely per user decision.

### Decision: Delete the Discover tab

The Discover page had an unfixable architectural flaw: 5 interdependent reactive
inputs (mostSimilar, forYou, becauseYouPlayed, unexploredClusters, rebuildPulse)
produced observable jitter that couldn't be reconciled with a smooth UX.
The user's core request: *"AI background work must never be felt in the app ever."*
Discover was the exact opposite of this — it was a dedicated page for watching AI
work. It was deleted.

### What was removed

| Item | Status |
| --- | --- |
| `Tab.Discover` enum entry in `MainActivity.kt` | Deleted |
| All 6 Discover-related `LaunchedEffect` blocks | Deleted |
| `DiscoverCacheEngine` (`engine/DiscoverCacheEngine.kt`) | Orphaned (nothing imports it) |
| `DiscoverScreen.kt` | Orphaned (nothing imports it) |
| `EmbeddingStatusBanner` composable import | Deleted |
| `discoverCache` in `AppContainer` | Deleted |
| `Overlay.SectionViewAll` class | Deleted |

### Phase 1 — Similar Songs row in NowPlaying (✅ complete)

**Files modified:** `MainActivity.kt`, `NowPlayingScreen.kt`

- Added `similarToCurrent: List<Song>` and `similarLoading: Boolean` state vars.
- Added `LaunchedEffect(currentMediaId, embeddingsRowCount, songs.size)` calling
  `recommender.recommendScored(k=10)` — fires when the track changes, embeddings
  load, or library loads. Never blocks UI.
- Added `refreshUpcomingWithAI()` helper with a reentrancy guard (skips if already
  running).
- `NowPlayingScreen` gained `similarSongs`, `similarLoading`, `onSimilarTap`,
  `onSimilarLongPress`, `refreshInProgress` params.
- `SimilarSongsRow` + `SimilarCard` (96 dp horizontal cards) render between the
  transport controls and the dislike row.
- `SimilarCard` shows album art (via `AlbumArtRepository`) + song title + artist.

### Phase 2 — Refresh-button progress UX (✅ complete)

**Files modified:** `PlaybackEngine.kt`, `Media3PlaybackService.java`,
`PlaybackCommandContract.java`, `NowPlayingScreen.kt`, `MiniPlayer.kt`

- `PlaybackEngine`: added `_refreshBusy: MutableStateFlow<Boolean>`, public
  `refreshBusy: StateFlow<Boolean>`, and `setRefreshBusy(busy: Boolean)` which
  mirrors state to the service via `CMD_NOTIFICATION_SET_REFRESH_BUSY`.
- `PlaybackCommandContract`: added `CMD_NOTIFICATION_SET_REFRESH_BUSY` and
  `KEY_BUSY`; registered in `controllerCommands()`.
- `Media3PlaybackService`: added `refreshBusy` field; command handler calls
  `refreshNotificationFromController()` (outer-class reference fixed:
  `Media3PlaybackService.this::` not `this::` inside inner `SessionCallback`).
- `NowPlayingScreen`: Refresh `IconButton` shows 22 dp `CircularProgressIndicator`
  (stroke 2 dp) while `refreshInProgress`, disabled during refresh.
- `MiniPlayer`: Refresh button shows 20 dp spinner and is disabled while `refreshBusy`.

### Phase 3 — Lockscreen Refresh button visible (✅ complete)

**Files modified:** `Media3PlaybackService.java`,
`app/src/main/res/drawable/ic_refresh_24.xml` (new file)

- Dropped the Close button from notification buttons.
- Notification now shows `[Favorite, Refresh]` both as `SLOT_OVERFLOW`.
- Created `ic_refresh_24.xml`: Material Symbols "Refresh" vector, 24 dp,
  `fillColor="@android:color/white"` (no `?attr/` theme reference — AAPT2 cannot
  resolve app-namespace theme attrs in static XML at link time).
- Refresh `CommandButton` uses `CommandButton.ICON_UNDEFINED` +
  `.setCustomIconResId(R.drawable.ic_refresh_24)`.
- `displayName="Refreshing…"` + `enabled=false` when `refreshBusy` is true.

### Phase 4 — Last-tab persistence (✅ complete)

**Files modified:** `AppContainer.kt`, `AppPreferences.kt`, `MainActivity.kt`

- `AppPreferences`: added `Keys.LAST_TAB = stringPreferencesKey("last_tab_v1")`,
  `suspend fun loadLastTab(): String`, `suspend fun saveLastTab(tabName: String)`.
- `AppContainer`: removed `DiscoverCacheEngine` lazy init and `clear()` call.
- `MainActivity`: `pagerState` restored via `LaunchedEffect(Unit)` reading
  `preferences.loadLastTab()`; tab changes saved via `LaunchedEffect(pagerState)`
  collecting `snapshotFlow { currentPage }`.
- Remaining tabs (4): **Songs, Albums, Up Next, Browse**.

### Build fixes

| Error | Fix |
| --- | --- |
| `?attr/colorControlNormal` in `ic_refresh_24.xml` — AAPT2 cannot resolve app-namespace theme attrs | Removed `android:tint` attribute entirely; `fillColor` is already `@android:color/white` |
| `CircularProgressIndicator` unresolved in `MiniPlayer.kt` and `NowPlayingScreen.kt` | Added `import androidx.compose.material3.CircularProgressIndicator` to both files |
| `this::refreshNotificationFromController` in inner `SessionCallback` class | Changed to `Media3PlaybackService.this::refreshNotificationFromController` |

### Files changed summary

| File | Change |
| --- | --- |
| `app/src/main/kotlin/com/isaivazhi/app/MainActivity.kt` | Phases 1, 2, 4 — major changes |
| `app/src/main/kotlin/com/isaivazhi/app/engine/AppContainer.kt` | Phase 4 — removed DiscoverCacheEngine |
| `app/src/main/kotlin/com/isaivazhi/app/engine/AppPreferences.kt` | Phase 4 — added LAST_TAB persistence |
| `app/src/main/kotlin/com/isaivazhi/app/engine/PlaybackEngine.kt` | Phase 2 — refreshBusy state + command |
| `app/src/main/kotlin/com/isaivazhi/app/ui/screens/NowPlayingScreen.kt` | Phases 1, 2 — SimilarSongsRow + spinner |
| `app/src/main/kotlin/com/isaivazhi/app/ui/screens/MiniPlayer.kt` | Phase 2 — refresh spinner |
| `app/src/main/java/com/isaivazhi/app/Media3PlaybackService.java` | Phases 2, 3 — notification buttons + refreshBusy |
| `app/src/main/java/com/isaivazhi/app/PlaybackCommandContract.java` | Phase 2 — CMD_NOTIFICATION_SET_REFRESH_BUSY |
| `app/src/main/res/drawable/ic_refresh_24.xml` | Phase 3 — new white refresh vector icon |
| `README.md` | Updated to remove Discover references; document new AI surfaces |

### Orphaned files (not deleted from repo — safe to delete later)

- `app/src/main/kotlin/com/isaivazhi/app/ui/screens/DiscoverScreen.kt`
- `app/src/main/kotlin/com/isaivazhi/app/engine/DiscoverCacheEngine.kt`

Nothing in the codebase imports these files. They compile as dead code and can
be deleted at any time without consequence.

1. **Migrate placement** — B1 (one-line reorder, migrate stays in UI process) or B2 (move to `:ai` foreground service, cleaner but touches service lifecycle)?
2. **Stage 2** — load `allFilenames`/`allFilepaths` lazily only when AI page opens, or pre-warm in background a few seconds after startup?
3. **Phase C optimistic MiniPlayer** — include in this batch or defer?
4. **Phase D queue-end blending** — Proposal 1A only (auto Repeat=OFF on Discover/Library start), or also 1B (loop a finite section N times then blend AI)?
5. **Phase E Refresh** — confirm separate icon (don't hijack Shuffle).

## 9. Relevant files

- `app/src/main/kotlin/com/isaivazhi/app/MainActivity.kt`
  - L221: `migrateFromLegacy()` kickoff — Phase A1, B6.
  - L3384–L3402: `refreshDbStats()` — Phase A2, B4.
  - L1492: `playFromSection` — Phase D9.
  - L1543: queue-exhaust `LaunchedEffect` — Phase D10.
  - L2141: UpNext `onRefresh` — Phase E12 (extract).
- `app/src/main/kotlin/com/isaivazhi/app/engine/EmbeddingDbFacade.kt`
  - `stats()` L24, `allFilenames()` L240, `allFilepaths()` L276, `migrateFromLegacy()` L20.
- `app/src/main/java/com/isaivazhi/app/EmbeddingDbManager.java`
  - Single HandlerThread — do not add a second worker.
- `app/src/main/kotlin/com/isaivazhi/app/engine/PlaybackEngine.kt`
  - `QueueContext`, `setRepeatMode`, `appendToQueue`, `replaceUpcoming`, `toggleShuffle`.
- `app/src/main/java/com/isaivazhi/app/Media3PlaybackService.java`
  - MediaSession custom command registration — Phase E14.
- `app/src/main/kotlin/com/isaivazhi/app/ui/screens/UpNextScreen.kt`
  - Existing Refresh button — reference for Phase E.
- `app/src/main/kotlin/com/isaivazhi/app/ui/screens/NowPlayingScreen.kt`
  - Shuffle + repeat row — Phase E15.
- `app/src/main/kotlin/com/isaivazhi/app/engine/AppPreferences.kt`
  - Snapshot for optimistic MiniPlayer — Phase C7.

## 10. Bottom line

- Startup AI delay = **migrate blocking single-thread DB worker**. Fix by reordering + splitting `refreshDbStats` (Phase B).
- "Same 10 songs loop" = **Repeat=ALL suppressing existing queue-exhaust AI append**. Fix by auto-setting Repeat=OFF on Discover/Library queue start (Phase D).
- Refresh button = **add to MiniPlayer + lockscreen as separate icon**, do not hijack Shuffle (Phase E).
- The music player part of the app is already fast and correct. All proposed changes are about making AI work invisible to the user.