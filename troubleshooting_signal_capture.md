# Music Player Signal Capture — Troubleshooting Handoff

This document consolidates a multi-day debugging session on a Kotlin/Compose Android music player. Multiple fixes shipped (pushes #74, #75, #76) but the user reports issues remain. This is for a second LLM to pick up cold.

---

## 1. Project Context

- **App**: Custom local music player, Kotlin + Jetpack Compose UI, Media3 (`androidx.media3:1.10.0`) playback service.
- **App id**: `com.isaivazhi.app.kt`
- **Code root**: `C:\Users\myuva\Documents\music_app_development\native\`
- **Source tree**: `native/app/src/main/kotlin/com/isaivazhi/app/` (Kotlin) + `native/app/src/main/java/com/isaivazhi/app/` (Java service + Media3 plumbing)
- **Build cmd** (Git Bash): `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./native/gradlew.bat -p native :app:assembleDebug`
- **Device**: Pixel 7 running GrapheneOS (Android 14/15).
- **Predecessor**: There was an earlier Capacitor + WebView build of the same app (archived at `backups/pre_kotlin_rewrite_20260511_234500/`) that "worked perfectly fine" per the user on the same hardware. The Kotlin rewrite has introduced these regressions.
- **Library size**: ~2470 songs, ~2454 embedded. Songs are .opus/.flac/.m4a files on local storage.

### Engine architecture (relevant pieces)

| Engine | File | Role |
|---|---|---|
| `Media3PlaybackService` | `native/app/src/main/java/com/isaivazhi/app/Media3PlaybackService.java` | Foreground media service. Owns the `ExoPlayer`, the playback accumulator (`accumulatedPlayedMs`), the transitions rolling buffer, and pending-evidence on `onTaskRemoved`. Survives Activity destruction. |
| `PlaybackEngine` | `native/app/src/main/kotlin/com/isaivazhi/app/engine/PlaybackEngine.kt` | Kotlin wrapper. Holds a `MediaController` connected to the service. Listens for `onMediaItemTransition` and writes per-transition signals into TasteEngine + SignalTimelineEngine + HistoryEngine. **Lives in Activity scope — dies when Activity is destroyed.** |
| `TasteEngine` | `native/app/src/main/kotlin/com/isaivazhi/app/engine/TasteEngine.kt` | Per-song taste signals (plays/skips/avgFraction/directScore/xScore). Recommender's source of truth. |
| `SignalTimelineEngine` | `native/app/src/main/kotlin/com/isaivazhi/app/engine/SignalTimelineEngine.kt` | Last 30 playback signal updates with before/after taste snapshots. Surfaced in the Taste page. |
| `HistoryEngine` | `native/app/src/main/kotlin/com/isaivazhi/app/engine/HistoryEngine.kt` | Listen history (rolling 500 events with fractionPlayed). Powers Browse → Recently Played. |
| `ActivityLogEngine` | `native/app/src/main/kotlin/com/isaivazhi/app/engine/ActivityLogEngine.kt` | Rolling 200-entry buffer of human-readable events (PLAY/PAUSE/LISTEN/SKIP/SEEK/FLUSH/INGEST/REPLAY/RECON_*). Surfaced via `ActivityLogScreen`. New in push #74. |
| `FavoritesEngine` | `native/app/src/main/kotlin/com/isaivazhi/app/engine/FavoritesEngine.kt` | Per-user favorited filenames. DataStore-backed. |
| `AppPreferences` | `native/app/src/main/kotlin/com/isaivazhi/app/engine/AppPreferences.kt` | DataStore wrapper for app prefs: current song, queue, pending-evidence snapshot, profile vec, watermark. |
| `AppContainer` | `native/app/src/main/kotlin/com/isaivazhi/app/engine/AppContainer.kt` | Manual DI; constructs and wires the engines. |
| `MainActivity` | `native/app/src/main/kotlin/com/isaivazhi/app/MainActivity.kt` | UI host. Contains the cold-start `LaunchedEffect(songs)` block, `flushCurrentPlayback`, `reconcilePending`, `drainTransitionsBuffer`, `ingestPendingEvidence`. |

### Pre-existing mechanisms relevant to this debug

- **Pending-evidence snapshot** (push #66): when the Activity backgrounds/destroys mid-playback, `flushCurrentPlayback` writes `{mediaId, playedMs, durationMs, playbackInstanceId}` to DataStore as a "tentative listen snapshot". When the service is force-killed via `onTaskRemoved`, it writes the same shape to a separate `playback_pending_evidence` SharedPreferences file. On cold-start, the LE drains both and runs `reconcilePending` with three cases:
  - **Case A**: a recent native transition's `prevPlaybackInstanceId` matches the snapshot → use the transition's values (authoritative).
  - **Case B**: same `playbackInstanceId` is still live in the service → defer (transition will record naturally).
  - **Case C**: no match, no live session → use snapshot as-is.

- **Transitions rolling buffer** (push #66): `Media3PlaybackService.rememberTransitionToPrefs` writes the last 24 transitions into `playback_transitions_history` SharedPreferences. Each entry carries `{prevPlaybackInstanceId, prevPlayedMs, prevDurationMs, prevFraction, prevFilename, action}`. Read on cold-start via `readRecentTransitionsStatic(ctx)`.

- **Notification controls** (push #38 baseline): `Media3PlaybackService.buildNotificationButtons()` declares a Favorite button (heart filled/unfilled based on `isCurrentFavorite()`) and a Close button. Both in `SLOT_OVERFLOW`. Reading from `CapacitorStorage` SharedPreferences key `favorites` (JSON `{"filenames": [...]}`). Capacitor parity. Push #34-#37 tried inline slots and broke Close on GrapheneOS, so overflow is the known-working baseline.

- **Service ↔ Controller IPC** (push #74): service emits `EVT_MEDIA_ACTION` custom command after notification button tap via `emitCustomCommandToControllers` → `mediaSession.sendCustomCommand(controllerInfo, ...)`. Bundle contains `action="favorite"|"dismiss"`, plus `filename`, `isFavorite`, `unDisliked`, etc.

---

## 2. Symptoms Reported by the User (verbatim where possible)

### Initial report
> The app is not capturing the song play signals like play/pause/next/seek, seekbar movements.
> i have attached logs in logs.txt file. in that logcat and last 15 taste signal logs are present.
> in the screenshots folder i have attached 2 screen capture of app.
> this morning i listened to over 5 songs from the app.
> when i checked the app, in the taste signal i did not see any signals for the songs listened.
> the song play history is not captured in the recently played in browse tab as well.
> the positive and negative points listing in the taste page doesnt list them.
> yesterday, i noticed that i played a song and paused it and closed the app from recents and i did like that thrice. in the taste signal 30 signals, i saw 3 entries for the same song. i think the song Kanimaa also reflects the same, i might have pause/played or not in the app or closed the app from recents and opened again.
> actually logcat logs are not useful for me at all. i am not able to understand, the logs should be such that when i see them, i understand. i think we need to follow the log capture we did in our capacitor code which is in backup now pre_kotlin_rewrite_20260511_234500.
> in the capacitor based app i never faced this much issue.
> also, i asked for favorite and close app icons in lockscreen and notification mini player. but still that is not implemented. check capacitor code how that is implemented. it worked there perfectly.
> i am using grapheneos, not sure whether it is causing any issues, but i used the capacitor app also in the same mobile and it was working perfectly fine there.

### Subsequent reports
> still that issues are present i believe. what are we missing really. why this much issues on capturing the signals

> actually when will the recently played be updated. that elay keechan song dint update in recently played tab.
> please investigate this as well and do code changes

> still the issues are not resolved.
> let me know if you have any questions. dont assume anything

After being asked specifically what's still broken, the user confirmed via multi-select:
- **Recent plays missing from Taste Signal AND Recently Played**
- **Duplicates still appearing for NEW plays**
- **Notification heart still doesn't sync**

Scenario context (auto-advance gap from 10:05:28 to 10:19:42 in logs.txt):
> Phone was idle; service auto-advanced in background

Activity log share scope:
> I already shared everything

---

## 3. Logs Shared (key excerpts)

### Taste Signal "Last 30" — most recent entries (logs.txt items 1-6)

```
1. Elay Keechan | A.R. Rahman, Madhan Karky
Time: 2026-05-14 09:05:10
Classification: skip | Listened: 0% | Source: neutral_skip
Direct play effect: +0.00 -> +0.00 (Δ +0.00)
Library counts: starts 0 -> 0 | skips 0 -> 0
X-score: 0.0 -> 0.0

2. Elay Keechan
Time: 2026-05-14 08:58:30
Classification: skip | Listened: 2% | Source: background_recovery_datastore_only

3. Veera
Time: 2026-05-14 08:22:35
Classification: positive listen | Listened: 100% | Source: neutral_skip

4. En Jeevan
Time: 2026-05-14 08:19:53
Classification: positive listen | Listened: 100% | Source: auto_advance

5. Pavazha Malli (From "Think Indie")
Time: 2026-05-14 07:57:30
Classification: positive listen | Listened: 100% | Source: neutral_skip

6. Kutti Story
Time: 2026-05-14 07:55:05
Classification: positive listen | Listened: 100% | Source: background_recovery_transition_history_auto_advance
```

### Taste Signal log — historical entries (items 7-20, predate push #74)

Duplicate pairs (same time, same fraction, sources `_task_removed` + `_datastore`):
- Thenmozhi 07:18:11 11% (× 2)
- Thenmozhi 01:53:55 4% (× 2)
- Thenmozhi 01:53:19 8% (× 2)
- Kanimaa 00:53:58 20% (× 2)

Plus single skips of Sahana, Yedi x Golden Sparrow, Chillanjirukkiye, Mazhai Kuruvi, Kanimaa (01:20:12 0%, 01:20:08 1%).

### Activity Log (the full content the user pasted — they confirmed nothing was truncated on their end)

```
[10:20:04]  FLUSH         Hey Rama Rama.opus — 91% reason=app_background
[10:05:28]  FLUSH         Ponni Nadhi.opus — 11% reason=app_destroy
[10:05:15]  FLUSH         Ponni Nadhi.opus — 6% reason=app_background
[10:04:59]  FLUSH         Ponni Nadhi.opus — 1% reason=app_background
[10:04:56]  PLAY          Ponni Nadhi — playing
[10:04:56]  PAUSE         Ponni Nadhi — paused
[10:04:55]  PLAY          Ponni Nadhi — playing
[09:06:13]  FLUSH         Ponni Nadhi.opus — 18% reason=app_destroy
[09:06:08]  FLUSH         Ponni Nadhi.opus — 16% reason=app_background
[09:05:57]  FLUSH         Ponni Nadhi.opus — 12% reason=app_background
[09:05:46]  FLUSH         Ponni Nadhi.opus — 9% reason=app_background
[09:05:20]  PLAY          Ponni Nadhi — playing
[09:05:10]  SEEK          Ponni Nadhi — seek to 0ms
[09:05:10]  SKIP          Elay Keechan — 0% via neutral_skip
[09:05:10]  SEEK          Elay Keechan — seek to 0ms
[09:05:07]  PAUSE         Elay Keechan — paused
[09:04:35]  FLUSH         Elay Keechan.opus — 76% reason=app_background
[09:03:57]  PLAY          Elay Keechan — playing
[09:03:56]  RECON_B       Elay Keechan.opus — deferred (still playing) via datastore_only
[09:03:12]  FLUSH         Elay Keechan.opus — 65% reason=app_destroy
[09:03:05]  PAUSE         Elay Keechan — paused
[09:02:44]  FLUSH         Elay Keechan.opus — 60% reason=app_background
[09:02:31]  RECON_B       Elay Keechan.opus — deferred (still playing) via merged_task_removed
[08:59:08]  FLUSH         Elay Keechan.opus — 1% reason=app_destroy
[08:59:04]  FLUSH         Elay Keechan.opus — 0% reason=app_background
[08:59:00]  PLAY          Elay Keechan — playing
[08:58:30]  INGEST        Elay Keechan — 2% via datastore_only
[08:58:30]  RECON_C       Elay Keechan.opus — recovered from snapshot via datastore_only
[00:31:43]  favorite_rem  Unfavorited Govinda.opus
[00:31:43]  favorite_rem  Unfavorited Kanne Kanne.opus
[00:31:43]  favorite_rem  Unfavorited Azhage.opus
[00:31:43]  favorite_rem  Unfavorited Hawa Hawa.opus
[00:31:43]  favorite_rem  Unfavorited Hey Vaada Vaada.opus
```

### Key logcat lines (filtered to relevant ones)

```
05-14 10:20:20.491 ActivityLog: [engine/FLUSH] Hey Rama Rama.opus — 91% reason=app_background
05-14 10:20:20.490 MainActivity: flushCurrentPlayback (tentative snapshot) reason=app_background mediaId=Hey Rama Rama.opus played=258891ms dur=283640ms frac=0.913 instId=1778733919155

05-14 10:19:42.087 PlaybackEngine: syncStateFromController: mediaId=Hey Rama Rama.opus idx=15/51 pos=258891ms isPlaying=false repeat=0
05-14 10:19:42.082 MainActivity: cold-start: service alive at mediaId=Hey Rama Rama.opus instId=1778733919155 pos=258904ms — skipping prepareForResume (DataStore had mediaId=Ponni Nadhi.opus pos=81516ms)
05-14 10:19:42.058 MainActivity: pending evidence skipped (covered by buffer replay): origin=datastore_only instId=1778733290892 watermark=1778733618079

05-14 10:19:37.907 Media3PlaybackService: onIsPlayingChanged: false state=3 mediaIdx=15 mediaTotal=51 current=(none)

05-14 10:15:19.071 Media3PlaybackService: onMediaItemTransition: reason=1 newIndex=15 currentIndex=14 suppress=false/-1
```

Reason=1 in Media3 is `MEDIA_ITEM_TRANSITION_REASON_AUTO` — auto-advance. So the service auto-advanced from idx=14 to idx=15 (Hey Rama Rama) at 10:15:19.

### Critical observation about the activity log

Between **10:05:28** (Ponni Nadhi destroyed at 11%) and **10:19:42** (cold-start with Hey Rama Rama at idx=15), the user states the phone was idle and the service auto-advanced. The service log at 10:15:19 confirms at least one transition fired (idx 14→15). But the **Activity Log shows ZERO `REPLAY` / `INGEST` / `RECON_*` entries in that window**. The transitions buffer should have entries, and the cold-start `drainTransitionsBuffer` at 10:19:42 should have replayed them — but no replay entries appeared.

Also: the cold-start log line `pending evidence skipped (covered by buffer replay): origin=datastore_only instId=1778733290892 watermark=1778733618079` shows the watermark is sitting at 1778733618079, which is HIGHER than the snapshot's instId (1778733290892). So the watermark IS bumping somewhere, but the buffer replay is producing no visible output.

---

## 4. Investigations & Fixes Shipped

### Push #74 (08:52 APK) — "Restore signal capture, kill duplicates, add Activity Log, sync notification heart"

**What it tried to fix**:
1. Duplicate ingestion — `MainActivity.kt:545-590` drained BOTH DataStore (`loadPendingEvidence`) AND SharedPreferences (`playback_pending_evidence`) independently. Each path called `reconcilePending`, producing two events for the same listen.
2. Background auto-advance loss — `reconcilePending` line 2693 used `firstOrNull { it.prevPlaybackInstanceId == snapshot.playbackInstanceId }`. When 5 songs auto-advanced in background, only ONE matching transition was ingested; the other 4 were silently discarded.
3. Activity-scope listener gap — `PlaybackEngine.onMediaItemTransition` lives in Activity scope. When the Activity dies, listener dies; service-side transitions never reach `taste.recordPlaybackEvent` live.
4. Logging unreadable.
5. Notification heart didn't sync.

**Tier A** — Watermark-based buffer replay.
- `AppPreferences.kt`: new `LAST_INGESTED_PLAYBACK_INSTANCE_ID` longPreferencesKey + `loadIngestWatermark()` / `saveIngestWatermark(instanceId)`. Monotonic guard.
- `MainActivity.kt`: new top-level `drainTransitionsBuffer(container, songs, recentTransitions)` near `reconcilePending`. Filters entries with `prevPlaybackInstanceId > watermark && prevPlaybackInstanceId > 0L && prevPlayedMs >= 1000L && prevDurationMs > 0L && prevFilename.isNotBlank()`. Calls `ingestPendingEvidence(... origin = "buffer_replay_${t.action}")` for each.
- `PlaybackEngine.kt:268-281`: inside the existing `scope.launch(Dispatchers.IO)` cleanup, also called `preferences.saveIngestWatermark(transitionInstId)` so live captures bump the watermark.

**Tier B** — Snapshot dedup with watermark gate.
- Cold-start LE now reads BOTH stores, builds a `TaggedSnapshot` list, dedupes if same `playbackInstanceId` (picks higher `playedMs`, tags origin `merged_datastore` or `merged_task_removed`), or keeps both if different instIds. Then drops any survivor with `playbackInstanceId <= watermark`. Always clears both stores after handling.

**Tier C** — Activity Log surface.
- `PlaybackEngine` constructor extended with `activityLog: ActivityLogEngine?` param.
- `activityLog?.log(...)` calls at:
  - `onMediaItemTransition` — `LISTEN`/`SKIP` with full direct-score deltas.
  - `onIsPlayingChanged` — `PLAY`/`PAUSE`.
  - `onPositionDiscontinuity` — `SEEK` (only when `reason == DISCONTINUITY_REASON_SEEK`).
- `MainActivity.flushCurrentPlayback` → `FLUSH`.
- `MainActivity.ingestPendingEvidence` → `INGEST` or `REPLAY` (based on `origin.startsWith("buffer_replay_")`).
- `MainActivity.reconcilePending` cases → `RECON_A`/`RECON_B`/`RECON_C`.
- `drainTransitionsBuffer` → `buffer_replay_start` when count > 0.
- New `ActivityLogScreen.kt` modeled on `DebugLogsScreen.kt`. Monospace rows `[HH:mm:ss]  TYPE_PAD12  message`, category filter chips, Copy All, Clear, expandable JSON.
- Entry points: Settings → Activity Log row, and long-press the Taste Signal header.

**Tier D** — Notification favorite/close sync.
- `PlaybackCommandContract.java`: promoted to `public final class`, `EVT_MEDIA_ACTION` and `KEY_FILENAME` to `public`.
- `PlaybackEngine.kt`: new `controllerListener: MediaController.Listener` overriding `onCustomCommand`. When `command.customAction == EVT_MEDIA_ACTION`:
  - Reads `args.getString("action")`.
  - On `"favorite"`: reads `isFavorite` boolean + `filename`. Calls `favorites?.setExplicit(filename, isFavorite)`. Logs `FAV+`/`FAV-` under category `notification`.
  - On `"dismiss"`: logs `CLOSE` only.
- `ensureController` now uses `.setListener(controllerListener)` on `MediaController.Builder`.
- `PlaybackEngine` constructor extended with `favorites: FavoritesEngine?`.
- New `FavoritesEngine.setExplicit(filename, isFavorite)` — idempotent (no-op if state matches), otherwise delegates to existing `add`/`remove` so `onChangeHook` still fires.
- `AppContainer.kt` `FavoritesEngine.onChangeHook` extended: after the taste recompute + delta propagation, also mirrors the favorites set to `CapacitorStorage` SharedPreferences as `{"filenames": [...]}` JSON (the same shape `Media3PlaybackService.isCurrentFavorite()` reads). Then calls `playback.refreshNotification()`.
- New `PlaybackEngine.refreshNotification()` sends `CMD_UPDATE_NOTIFICATION_STATE` via `ctrl.sendCustomCommand` so the service rebuilds the notification buttons immediately with the new heart state.

Notification slot placement deliberately left at `SLOT_OVERFLOW` (push #34-#38 history: every inline-slot attempt broke Close on GrapheneOS).

**Build**: SUCCESS, 329 MB APK at 08:52.

### Push #75 (09:54 APK) — "Recently Played fix + SEEK log false-positive + transition diagnostics"

After install, user reported the Elay Keechan listen (visible in Activity Log as FLUSH at 60%/65%/76%) didn't appear in Browse → Recently Played.

**Diagnosed bugs**:
1. `HistoryEngine.recordEnd` has a `pendingStartFilename != filename` stale-event guard at line 81-86. When the Activity cold-starts onto an already-playing song (via push #71's `syncStateFromController` path — no `onMediaItemTransition` fires for the current item), `pendingStartFilename` is never set. The subsequent live transition's `recordEnd("Elay Keechan", ...)` gets rejected silently.
2. Cold-start ingest paths (`drainTransitionsBuffer`, `ingestPendingEvidence`, `reconcilePending`) write to TasteEngine + SignalTimeline but never touch HistoryEngine — Recently Played never reflected background plays.
3. `onPositionDiscontinuity` SEEK log false-fired on `seekToNextMediaItem` (which Media3 reports as `DISCONTINUITY_REASON_SEEK` because it IS a seek — across items).

**Fixes**:
- `HistoryEngine.kt`: new `recordCompleted(filename, startedAt, fractionPlayed)` that bypasses the pendingStartFilename guard. Refactored common body into `appendEventAndUpdateStats(ev, frac)` shared with `recordEnd`.
- `PlaybackEngine.syncStateFromController`: after populating `_state.value`, calls `history?.recordStart(curMediaId)` so the eventual transition's `recordEnd` matches the guard.
- `MainActivity.ingestPendingEvidence`: at the end, calls `container.history.recordCompleted(mediaId, System.currentTimeMillis(), frac)`.
- `PlaybackEngine.onPositionDiscontinuity` SEEK log filter tightened: requires `oldPosition.mediaItemIndex == newPosition.mediaItemIndex`.
- `PlaybackEngine.onMediaItemTransition` `activityLog.log(...)` data extended with `prevPlayedMs`, `prevDurationMs`, `origin` (`"service"` or `"fallback"`) for in-app debugging.

**Build**: SUCCESS, 329 MB APK at 09:54.

### Push #76 (13:40 APK) — "Watermark/instId bug + migration + legacy duplicate cleanup"

After install, user reported all three previous issues are still present. Activity Log around 10:05–10:20 shows no `REPLAY`/`INGEST` entries even though the service was clearly auto-advancing in the background during those 14 minutes (service log at 10:15:19 confirms reason=AUTO transition idx 14→15).

**Root cause identified**:
- `PlaybackEngine.onMediaItemTransition` line 224 reads `transitionInstId` via `svc.getCurrentPlaybackInstanceId()`.
- The service's own `onMediaItemTransition` (in `Media3PlaybackService.java:321-358`) bumps `currentPlaybackInstanceId = nextPlaybackInstanceId()` at line 354, AFTER capturing the transition snapshot but BEFORE the IPC notifies the Kotlin controller.
- So by the time Kotlin's listener fires, `getCurrentPlaybackInstanceId()` returns the NEW song's id.
- `transitionInstId` then flows into `saveIngestWatermark(transitionInstId)` → the watermark advances to a NEW-song id after every live transition.
- Buffer entries (written in `rememberTransitionToPrefs`) store the PREV song's id in `prevPlaybackInstanceId`.
- `drainTransitionsBuffer` filter: `prevPlaybackInstanceId > watermark`. After the inflation, ALL buffer entries fail this check because their prev ids sit at-or-below the inflated watermark.
- **Net effect**: every background buffer-replay attempt found 0 unprocessed entries and silently produced no log output. The 14-minute auto-advance from 10:05 to 10:19 was completely invisible.

**Tier 1** — `Media3PlaybackService.java`:
- New `private volatile long lastTransitionPrevPlaybackInstanceId = 0L` field.
- Captured alongside `lastTransitionPrevPlayedMs` in the transition handler (line 344-346): `lastTransitionPrevPlaybackInstanceId = snapshot.prevPlaybackInstanceId`. This happens BEFORE the `currentPlaybackInstanceId = nextPlaybackInstanceId()` bump at line 354.
- New public getter `getLastTransitionPrevPlaybackInstanceId()`.

**Tier 2** — `PlaybackEngine.kt`:
- One-line semantic fix at line ~224: read `svc.javaClass.getMethod("getLastTransitionPrevPlaybackInstanceId")` instead of `getCurrentPlaybackInstanceId`.

**Tier 3** — One-time migration to undo the inflated watermark on devices that ran #74/#75.
- `AppPreferences.kt`: new `MIGRATION_V76_WATERMARK_RESET` booleanPreferencesKey + `runV76WatermarkResetIfNeeded()` that checks the flag, removes `LAST_INGESTED_PLAYBACK_INSTANCE_ID` if unset, sets the flag.
- `MainActivity.kt` cold-start LE: calls `runV76WatermarkResetIfNeeded()` BEFORE `drainTransitionsBuffer` so the buffer's existing entries finally pass the filter. Logs `MIGRATION_V76` to Activity Log on the first boot.

**Tier 4** — Sweep legacy `_task_removed` + `_datastore` duplicate pairs from the timeline.
- `SignalTimelineEngine.cleanLegacyDuplicates()`: groups events by `(filename, timestamp/5sec, fraction%, source-root)` where source-root collapses `_task_removed` and `_datastore` into one bucket. Keeps the first occurrence, drops the rest. Returns count.
- Called from the same v76 migration block in `MainActivity` cold-start.

**Build**: SUCCESS, 329 MB APK at 13:40.

**User has not yet confirmed whether install of #76 resolved the issues.**

---

## 5. Outstanding Issues (per user, after push #75 install)

The user explicitly multi-selected these as still broken before push #76 was built:

1. **Recent plays missing from Taste Signal AND Recently Played**
   - The Activity Log shows you played Ponni Nadhi at 10:04-10:05 (FLUSH 1%→6%→11%) and Hey Rama Rama later (FLUSH 91% at 10:20:04). Neither appears in the Taste Signal "Last 30" list. The list still shows the same morning entries (items 1-6) and the historical duplicates (items 7-20).
   - Push #76 should fix this on first cold-start after install via the watermark migration + buffer replay.

2. **Duplicates still appearing for NEW plays**
   - User said duplicates exist for NEW plays. Looking at the visible logs, items 1-6 (post-#74 install) don't show clear duplicates. The duplicates visible are items 7-20 (pre-#74 historical).
   - **Ambiguity**: user may have meant the historical entries are "still appearing" (which they would because push #74 doesn't retroactively clean), or they're seeing NEW duplicates we haven't seen in the shared logs. Push #76's `cleanLegacyDuplicates()` should remove items 7-20 on first boot, which may resolve this complaint either way.

3. **Notification heart still doesn't sync**
   - Push #74 wired the controller listener (`onCustomCommand`) + SP mirror + `refreshNotification()`. From the logs alone, we cannot tell whether the listener actually fires when the user taps the notification heart.
   - **Diagnostic test**: after push #76 install, the Activity Log should show `FAV+` / `FAV-` lines under category `notification` when the user taps the notification heart. If they appear but the in-app heart doesn't update, the bridge between the listener and `FavoritesEngine` has a separate bug. If they DON'T appear, the IPC isn't reaching the Kotlin listener at all.

### Acknowledged side issue NOT yet fixed

**Elay Keechan 09:05:10 recorded as 0% via `neutral_skip` even though FLUSH at 09:04:35 showed 76%**.
- The transition was live (`neutral_skip` source, no `background_recovery_*` prefix), so the Activity was alive when the user tapped skip-next.
- The Activity Log shows FLUSH events at 60%/65%/76% during the Elay Keechan session, so the service's `accumulatedPlayedMs` WAS growing correctly during playback.
- At 09:05:10, the live transition captured `prevPlayedMs = 0`. Most likely: GrapheneOS killed the foreground service mid-session and respawned it with a fresh `accumulatedPlayedMs = 0L` and fresh `currentPlaybackInstanceId`. The Activity stayed alive throughout and didn't detect the respawn.
- Mitigation idea (not yet implemented): periodically persist `accumulatedPlayedMs` + `currentPlaybackInstanceId` to `playback_recovery_v1` SharedPreferences, restore on service `onCreate`. Or: Kotlin detects when `Media3PlaybackService.INSTANCE.getCurrentPlaybackInstanceId()` changes without a transition fire — that means the service respawned.
- Push #75 Tier 5 added `prevPlayedMs` / `prevDurationMs` / `origin` to the SKIP/LISTEN activity log payload as a diagnostic surface so this case is confirmable in-app.

---

## 6. Hypotheses & Open Questions for the Next LLM

### H1 — Is push #76 actually going to recover the lost plays?

The watermark/instId bug story is consistent with the symptoms. Verifying on the user's device should produce:
- One `MIGRATION_V76` entry near the top of the Activity Log on first boot of #76.
- Followed by `buffer_replay_start` with a count, and one `REPLAY` per recovered transition.
- Recovered transitions appear in the Taste Signal list tagged `background_recovery_buffer_replay_*`.
- Recovered songs appear in Browse → Recently Played.

If the user installs #76 and sees ALL of these, the watermark bug was indeed the root cause. If NONE of these appear, the buffer was empty at install time (the relevant entries had already been rotated out of the 24-slot rolling buffer) and push #76 only prevents future losses.

### H2 — Notification heart sync: where is the bridge breaking?

The Push #74 wiring path:
```
User taps notification heart
  → Media3PlaybackService.SessionCallback.onCustomCommand handles CMD_NOTIFICATION_TOGGLE_FAVORITE
  → handleNotificationFavorite() at Media3PlaybackService.java:1057
  → toggleCurrentFavoriteInStorage() updates CapacitorStorage SP
  → refreshNotificationUiState() rebuilds notification → heart icon flips on notification
  → emitMediaAction("favorite", result Bundle) at line 1060
  → emitCustomCommandToControllers(EVT_MEDIA_ACTION, data) at line 1279
  → mediaSession.sendCustomCommand(controllerInfo, command, args)
  → MediaController.Listener.onCustomCommand on the Kotlin side (PlaybackEngine.controllerListener)
  → favorites?.setExplicit(filename, isFav)
  → FavoritesEngine state updates → mini-player heart re-composes
```

Possible failure points:
- **The controller may not be in `connectedControllers`** at the moment `emitCustomCommandToControllers` runs. If `connectedControllers.isEmpty()`, the method returns silently (line 772). Check whether `Media3PlaybackService.onConnect` ever fires for this controller — and if it does, whether the controller is still in the set when the notification button is tapped.
- **The `setListener` API may not actually attach for the version of Media3 in use**. Verify Media3 1.10.0 docs. Alternative: register the listener via `controller.addListener(playerListener)` — wait, no, `Player.Listener` doesn't have `onCustomCommand`. `MediaController.Listener` is a separate interface that MUST be supplied at build time.
- **The Kotlin controller may build a separate controller instance** from what the service notifies. Different controller, different listener — the service sends to a stale controllerInfo. Verify by logging the package name in `onConnect`.
- **The user's notification button tap may be intercepted by Android's media routing UI** (e.g., system "expanded media controls" volume panel) which doesn't proxy the custom command. Test by tapping the favorite directly on the notification card vs. on the lockscreen vs. on Android's quick-tile media panel.

### H3 — Are there OTHER places where `getCurrentPlaybackInstanceId()` is misused?

The same bug pattern (reading current instId after a transition has already advanced it) could exist elsewhere. Grep for `getCurrentPlaybackInstanceId` in Kotlin code:
- `MainActivity.kt:549-552` — reads it for `liveInstanceId` to detect Case B (same instance still active). This is the CORRECT use because we want to know what's CURRENTLY live, not what just ended.
- `PlaybackEngine.kt:224` — fixed in push #76.
- Anywhere else? Worth a `Grep` pass.

### H4 — Is the Elay Keechan 0% bug actually service-respawn, or something else?

The hypothesis is GrapheneOS kills the foreground service mid-session and respawns it with `accumulatedPlayedMs = 0L`. Verify by:
- Adding periodic logging of `accumulatedPlayedMs` from the service (every 5 sec while playing) — see if it ever resets to 0 mid-song without a transition.
- Checking if `Media3PlaybackService.onCreate` log line ("onCreate: Media3 playback service starting") appears more than once in a logcat capture spanning a single user session.
- Adding service-side accumulator persistence: write `accumulatedPlayedMs` + `currentPlaybackInstanceId` + `currentIndex` to `playback_recovery_v1` SharedPreferences every progress tick (250ms is too often; every 5s is reasonable). Restore in `onCreate` if the same instId+index still match.

### H5 — Is GrapheneOS specifically interfering with foreground services?

GrapheneOS has aggressive background-task restrictions and is known to kill foreground services more readily than stock Android. The user confirmed the Capacitor build worked on the SAME device — likely because Capacitor ran the audio engine inside the WebView (tied to the Activity), not as a separate foreground service. Stock-Android Media3 patterns may need additional hardening on GrapheneOS:
- `setForegroundServiceType("mediaPlayback")` (already in manifest).
- `START_STICKY` from `onStartCommand` (verify).
- Avoid going through Doze: keep the player audibly playing.
- Wake locks may be needed for media-only background playback.

---

## 7. Files Touched Across Pushes #74-#76

### Modified
- `native/app/src/main/java/com/isaivazhi/app/Media3PlaybackService.java`
  - #76: new `lastTransitionPrevPlaybackInstanceId` field + getter; captured in `onMediaItemTransition` from `snapshot.prevPlaybackInstanceId`.
- `native/app/src/main/java/com/isaivazhi/app/PlaybackCommandContract.java`
  - #74: promoted class to `public`, `EVT_MEDIA_ACTION` and `KEY_FILENAME` to `public static final`.
- `native/app/src/main/kotlin/com/isaivazhi/app/engine/AppPreferences.kt`
  - #74: `LAST_INGESTED_PLAYBACK_INSTANCE_ID` key, `loadIngestWatermark()`, `saveIngestWatermark(instanceId)`. Extended `clear()`.
  - #76: `MIGRATION_V76_WATERMARK_RESET` key, `runV76WatermarkResetIfNeeded()`.
- `native/app/src/main/kotlin/com/isaivazhi/app/engine/AppContainer.kt`
  - #74: pass `activityLog` + `favorites` to PlaybackEngine constructor; `FavoritesEngine.onChangeHook` mirrors to `CapacitorStorage` SP and calls `playback.refreshNotification()`.
- `native/app/src/main/kotlin/com/isaivazhi/app/engine/PlaybackEngine.kt`
  - #74: constructor adds `activityLog`, `favorites` params; ActivityLog calls in `onIsPlayingChanged`, `onMediaItemTransition`, `onPositionDiscontinuity`; watermark bump in the IO cleanup block; new `controllerListener: MediaController.Listener` for `EVT_MEDIA_ACTION`; `ensureController` uses `.setListener(controllerListener)`; new `refreshNotification()` method.
  - #75: SEEK log requires same-item; transition log payload extended with `prevPlayedMs`/`prevDurationMs`/`origin`; `syncStateFromController` calls `history?.recordStart(curMediaId)`.
  - #76: `transitionInstId` reads `getLastTransitionPrevPlaybackInstanceId` instead of `getCurrentPlaybackInstanceId`.
- `native/app/src/main/kotlin/com/isaivazhi/app/engine/FavoritesEngine.kt`
  - #74: new `setExplicit(filename, isFavorite)` idempotent setter.
- `native/app/src/main/kotlin/com/isaivazhi/app/engine/HistoryEngine.kt`
  - #75: new `recordCompleted(filename, startedAt, fractionPlayed)` bypassing the pendingStartFilename guard. Refactored shared body into `appendEventAndUpdateStats`.
- `native/app/src/main/kotlin/com/isaivazhi/app/engine/SignalTimelineEngine.kt`
  - #76: new `cleanLegacyDuplicates()` returns count of removed legacy `_task_removed`+`_datastore` pairs.
- `native/app/src/main/kotlin/com/isaivazhi/app/MainActivity.kt`
  - #74: rewrote cold-start LE block 545-590 with `drainTransitionsBuffer` first + snapshot dedup logic; new top-level `drainTransitionsBuffer` function; ActivityLog calls in `flushCurrentPlayback`, `ingestPendingEvidence`, `reconcilePending` cases A/B/C; watermark bump in `ingestPendingEvidence`; `Overlay.ActivityLog` variant + `AnimatedVisibility` block; Settings + Taste wired to open it.
  - #75: `ingestPendingEvidence` calls `container.history.recordCompleted`.
  - #76: v76 migration block at top of cold-start LE (watermark reset + legacy duplicate cleanup), with `MIGRATION_V76` Activity Log entry.
- `native/app/src/main/kotlin/com/isaivazhi/app/ui/screens/SettingsScreen.kt`
  - #74: optional `onOpenActivityLog` param + ActionRow.
- `native/app/src/main/kotlin/com/isaivazhi/app/ui/screens/TasteScreen.kt`
  - #74: optional `onOpenActivityLog` param; long-press combinedClickable on the header; `@OptIn(ExperimentalFoundationApi::class)`.

### Created
- `native/app/src/main/kotlin/com/isaivazhi/app/ui/screens/ActivityLogScreen.kt` — full Compose screen for the rolling 200-entry activity log.

---

## 8. Recommended Investigation Path for Next LLM

1. **Confirm push #76 install resolves the missing-plays symptom**:
   - User installs `native/app/build/outputs/apk/debug/app-debug.apk` from 2026-05-14 13:40.
   - Opens app cold. Goes to Settings → Activity Log.
   - Expected: `MIGRATION_V76` near top + `buffer_replay_start` + per-transition `REPLAY` entries.
   - Expected: Taste Signal items #7-#20 are gone. Songs from the recovered transitions appear with `background_recovery_buffer_replay_*` source.
   - Expected: Browse → Recently Played lists the recovered songs.

2. **If recovery is partial** (some entries replayed, others still missing):
   - The 24-entry rolling buffer may have already rotated out older transitions before #76 ran. The lost plays from earlier sessions are gone forever; only the LAST 24 are recoverable.

3. **If recovery is zero** (no `REPLAY` entries at all):
   - The buffer may have been empty when `drainTransitionsBuffer` ran. Check `playback_transitions_history` SharedPreferences via `adb shell run-as com.isaivazhi.app.kt cat shared_prefs/playback_transitions_history.xml` to inspect.
   - OR the migration may not have fired (check Activity Log for the `MIGRATION_V76` entry).

4. **Notification heart sync test**:
   - With push #76 installed, play any song. Lock the device.
   - Tap the favorite/heart on the lockscreen notification.
   - Check Activity Log (via Settings entry). Look for `FAV+` or `FAV-` under category `notification`.
   - If present: the IPC is working. Check whether the in-app mini-player heart updated. If not, the bug is in `FavoritesEngine.setExplicit` → `_favorites` state propagation.
   - If absent: the IPC is broken. The `MediaController.Listener.onCustomCommand` is not firing. Investigate the Media3 controller setup in `PlaybackEngine.ensureController` (line 390-414). Verify `.setListener(controllerListener)` is the right API for Media3 1.10.0. Alternative test: log every `connectedControllers.add(controller)` call in the service to confirm the Kotlin controller is registered.

5. **Elay-Keechan-style 0% capture during live transitions**:
   - Reproduce: play a song to 50%+ in foreground. Lock device for a few minutes. Unlock, return to app, tap skip-next.
   - Check Activity Log expanded entry. If `prevPlayedMs=0 origin=service`, the service's accumulator was wiped — investigate service kill/respawn (H4).
   - Fix path: implement periodic persist of `accumulatedPlayedMs` + `currentPlaybackInstanceId` + `currentIndex` to `playback_recovery_v1` SharedPreferences, restore on `onCreate`.

6. **Reference implementation**:
   - The Capacitor build at `backups/pre_kotlin_rewrite_20260511_234500/` "worked perfectly" on the same GrapheneOS device. The signal-capture pipeline lived in `src/engine-*.js` files inside the WebView. Notification controls were in `backups/.../android/app/src/main/java/.../Media3PlaybackService.java` (the original Capacitor service). Compare its `accumulator handling`, `onTaskRemoved` behavior, and notification button slot config against the current Kotlin rewrite.

---

## 9. Diagnostic Commands

```bash
# Build
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" \
  ./native/gradlew.bat -p native :app:assembleDebug

# Install (over existing)
adb install -r native/app/build/outputs/apk/debug/app-debug.apk

# Logcat filtered to relevant tags
adb logcat -v threadtime *:S \
  MainActivity:I PlaybackEngine:I Media3PlaybackService:I \
  ActivityLog:I SignalTimeline:I TasteEngine:I QueueOp:I \
  QueueExhaust:I DiscoverLE:I FavState:I

# Dump SharedPreferences (transitions buffer + pending evidence)
adb shell run-as com.isaivazhi.app.kt \
  cat /data/data/com.isaivazhi.app.kt/shared_prefs/playback_transitions_history.xml
adb shell run-as com.isaivazhi.app.kt \
  cat /data/data/com.isaivazhi.app.kt/shared_prefs/playback_pending_evidence.xml
adb shell run-as com.isaivazhi.app.kt \
  cat /data/data/com.isaivazhi.app.kt/shared_prefs/CapacitorStorage.xml

# Inspect DataStore (binary, harder)
adb shell run-as com.isaivazhi.app.kt \
  ls -la /data/data/com.isaivazhi.app.kt/files/datastore/
```

---

## 10. Build State at Handoff

- Latest APK: `native/app/build/outputs/apk/debug/app-debug.apk` (push #76, 329 MB, 2026-05-14 13:40 IST).
- `project_development.md` has detailed push #74/#75/#76 entries at the top.
- Memory at `C:\Users\myuva\.claude\projects\C--Users-myuva-Documents-music-app-development\memory\` retains:
  - `feedback_build.md` — JAVA_HOME bash syntax.
  - `feedback_workflow.md` — no code changes without confirmation; update md file after every action.
  - `feedback_emulator.md` — don't drive adb interactively; have user share logs.txt.
  - `project_music_app.md` — Android Kotlin/Compose music player at `native/`.
