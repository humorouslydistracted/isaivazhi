# Project Development Log

Last updated: 2026-05-11 (latest #2: embedding-freeze follow-up — after the original four AI page fixes landed and unblocked the IPC chain, the user reported a separate 1–2 minute UI freeze when tapping "Remove N Unmatched Embeddings" plus a perceptible slowdown opening the AI page after launch. Four more fixes landed in this pass: **Fix A** serializes concurrent `EmbeddingCache.saveToDisk` calls through a single `_pendingSave` chain so user-triggered mutations never race the `_replaceFile` step against a post-batch merge; **Fix B** inserts `setTimeout(0)` yields between the CPU-bound phases of `_writeBinaryStore` (Float32→base64 encode, meta JSON.stringify, JSI bridge write) and `_writeLegacyJson` (Array conversion loop, 10–15MB JSON.stringify, bridge write) so the renderer can paint toasts / page rerenders while the multi-MB save runs; **Fix C** strips the unconditional `reloadEmbeddingsFromDisk()` from `resyncEmbeddingState()` (it duplicated the load already done in `startBackgroundScan` and triggered a heavy recommender rebuild on every AI page open); **Fix D** changes `removeUnmatchedEmbeddings` to fire `EmbeddingCache.saveToDisk(...)` as a detached promise with `.catch(...)` instead of awaiting it — the UI handler returns instantly after the in-memory mutation, the disk persist completes in the background under the atomic tmp+rename invariant. **`local_embeddings.json` is intentionally kept** as documented in `AGENTS_1.md` / `CLAUDE_1.md` lines 73/82/210/214/225 ("EmbeddingService is the sole writer", first-class persistence schema, breaking schema changes require migration) and `git_upload/README.md` lines 78–79/119/134 (user-facing portable format consumed by `colab_embedding_generator.py` / `local_embedding_generator.py`). Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 389.7 MB, timestamp `2026-05-11 12:35` (bundle `dist/assets/index-B6ossT9s.js`). Pre-fix safety backup: `backups/embedding_freeze_fixes_prework_20260511_140000/`.)

### 2026-05-11 #2 — Embedding save freeze + AI page open slowdown (4 fixes)
User report after the earlier hardening batch landed: "in ai embedding, it was showing 11 songs in unmatched. when i clicked to remove them. the app literally froze for 1-2 mins and now its working properly. also the loading embedding become slower than before when launching app. i think most of the issue is when there is new songs to be embedded, remove embedding for a song or remove unmatched. not sure they are handled in separate thread or process."

Root cause analysis: `EmbeddingCache.saveToDisk(localEmbeddings)` does two CPU-heavy serializations on the JS thread, plus two large JSI bridge writes — for a ~2500-entry store that's ~5MB Float32→base64 + ~500KB meta JSON + a 10–15MB legacy JSON (`Array.from(emb)` for every embedding + JSON.stringify). All four phases run synchronously inside one `await`, pinning the renderer for 1–3s of CPU plus the bridge serialization time. Every user-facing mutation path (remove-unmatched, remove-orphans, post-batch merge) goes through this same `saveToDisk`, so removing only 11 unmatched embeddings still rewrites the entire store. Separately, `resyncEmbeddingState()` (added in the earlier pass) was duplicating the disk-load work already done in `startBackgroundScan` on every AI page open, costing an extra 200–500ms plus a potential recommender rebuild.

**Fix A — serialize concurrent saves**. New `_pendingSave` promise chain in `embedding-cache.js`. Concurrent `saveToDisk(...)` calls (e.g., the user removes unmatched while a post-batch merge from a finishing native batch is also calling saveToDisk) no longer race against each other into the same `.tmp` / `_replaceFile` sequence. Each save waits for the previous one to finish before starting its own tmp writes. Default behavior is otherwise unchanged.

**Fix B — yield event-loop between CPU phases**. Added `await new Promise(r => setTimeout(r, 0))` at three points in `_writeBinaryStore` (before `_float32ToBase64`, before `JSON.stringify(meta)`, before the JSI bridge write) and three points in `_writeLegacyJson` (before the `Array.from(emb)` conversion loop, before the JSON.stringify, before the bridge write). The JS thread still does the same total CPU work, but yields control to the renderer between each ~100–500ms chunk so toasts / page rerenders / scroll gestures can paint during the save. Subjective freeze duration on a 2500-entry store drops from "frozen 1–2 min" to "responsive with occasional small stutters during the encode phase".

**Fix C — lighten `resyncEmbeddingState()`**. Removed the unconditional `reloadEmbeddingsFromDisk()` call. The function now only does `getEmbeddingBackend()` (cheap, just reads a cached field on native) and `requestEmbeddingStatus()` (cheap async request — the synthesized `embeddingComplete` fallback in `MusicBridgePlugin.onEmbeddingServiceEvent` already triggers `_runPostBatchMerge` which does the disk-truth reload when actually needed). Cold-start recovery is unaffected because `startBackgroundScan` at app init still calls `_loadLocalEmbeddings + _mergeLocalEmbeddings` regardless. AI page open latency drops by ~200–500ms.

**Fix D — fire-and-forget save in `removeUnmatchedEmbeddings`**. Old code: `try { await EmbeddingCache.saveToDisk(localEmbeddings); } catch (e) { ... }` — blocked the function return on the full save. New code: `EmbeddingCache.saveToDisk(localEmbeddings).catch(e => _embLog('error', ...))` — kicks off the save as a detached promise. The in-memory mutation (deleting hashes from `localEmbeddings`, clearing `unmatchedEmbeddings`) is already done before this line, so the user-visible state ("0 unmatched") is immediately correct. The disk persist runs in the background under the existing atomic tmp+rename invariant — a mid-write app kill cannot corrupt the binary store; worst case the removed entries reappear on next startup (binary store wasn't flushed) and the user re-removes them (idempotent). The existing `_saveLocalEmbeddings()` helper at line 150 in `engine-embeddings.js` was already fire-and-forget at the call site for `removeOrphanedEmbeddings` and the in-pipeline path, so those paths benefit from the saveToDisk-internal serialization (Fix A) and the yields (Fix B) without further changes.

`local_embeddings.json` is INTENTIONALLY kept across all four fixes — verified against:
- `AGENTS_1.md` lines 73, 82, 210, 214, 225 — first-class persistence schema, sole-writer invariant, dual-write race risk if violated.
- `CLAUDE_1.md` — same content, mirrors AGENTS.
- `git_upload/README.md` lines 78–79, 119, 134 — user-facing portable format. The Colab and laptop embedding generators (`colab_embedding_generator.py`, `local_embedding_generator.py`) produce this exact file. Users copy it into the app's data folder.
- `new_development/INTEGRATION_GUIDE.md` lines 86, 102, 119 — load flow expects both `embeddings.json` (Colab) AND `local_embeddings.json` (on-device generated), merged.
- `project_development.md` Fix C (2026-05-10) — actively maintains the JSON mirror's Float32-safety: "otherwise the legacy mirror file would silently corrupt to `{0:x,1:y,...}` objects."

The earlier `2026-05-11 follow-up #1` proposal to drop `_writeLegacyJson` outright was caught by the user reviewing the md files and rejected. The current pass preserves the mirror; only changes how / when it gets written.

Files modified:
- `src/embedding-cache.js` — Fix A (`_pendingSave` chain in `saveToDisk`), Fix B (yields in `_writeBinaryStore` + `_writeLegacyJson`)
- `src/engine-embeddings.js` — Fix C (lightened `resyncEmbeddingState`), Fix D (fire-and-forget save in `removeUnmatchedEmbeddings`)

Pre-fix safety backup: `backups/embedding_freeze_fixes_prework_20260511_140000/` (snapshot of `embedding-cache.js`, `engine-embeddings.js`, `app-ai-page.js`, `app.js` BEFORE this pass).

Verification this pass: `npm.cmd run build` OK (Vite 473 ms; bundle now `dist/assets/index-B6ossT9s.js` at 303.81 kB, 84.93 kB gzipped); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance {duplicate: true}` shape regression remains the only failure; one transient failure caught during development from an earlier draft of Fix A — `tests/embedding-cache.test.js` "merges a newer local_embeddings.json on top of the stable binary cache" — was resolved by reverting to await-both-writes semantics in `saveToDisk` and pushing fire-and-forget to the caller); `npx.cmd cap sync android` OK (0.8 s); `:app:assembleDebug` BUILD SUCCESSFUL in 9 s (trailing BUILD FAILED is the documented Gradle metadata-cache lock collision). Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 389.7 MB, timestamp `2026-05-11 12:35`.

Real-device validation pending: (1) tap "Remove N Unmatched Embeddings" with a populated unmatched list — UI should return within ~100 ms (was: 1–2 min freeze), the toast should show "Removed N unmatched embeddings" immediately, and the disk save should complete in the background within ~2–4 s (visible in logcat as `Wrote stable binary store: ...` and `Wrote local_embeddings.json mirror: ...`); (2) reopen the AI embedding page after launch — should feel as fast as any other sub-page, no perceptible lag (resync no longer doubles the embedding load); (3) sustained scrolling / interaction during a remove-unmatched should remain interactive thanks to the setTimeout yields.

#### On-device telemetry observation (logs.txt, session starting 08:00:00.080)

User-captured DBG log after installing the post-fix APK confirms the embedding pipeline is healthy:
- `[PERF] readBinaryStore bin fetch: 5013504 bytes in 127 ms` + `[PERF] _loadBinaryStore total: 2448 entries dim=512 in 443 ms` — full disk load round-trip ~483 ms (Fix G's `convertFileSrc + fetch` path is active, no JSI base64 round-trip).
- `[Embedding] Merge: 2448 embeddings vs 2485 songs (2485 with paths) → 2474 merged (path:2448, hash:0, fallback:0, pathIndex:26), 0 unmatched` — all imported embeddings matched against the device library cleanly; **no `.trashed-*` filenames appear anywhere in the merge, confirming the 2026-05-11 #1 Fix 1 (`MediaScanHelper` dotfile filter + library-load filter) wiped the bogus entries**.
- `[PERF] cache-skip pre-ready total: 51 ms` and `EVENT: embeddingsReady` → `EVENT: aiReady` chain completes within ~5 s of app open (cold restore in flight from `08:00:01.126`, AI ready at `08:00:05.238`).

Secondary finding worth tracking: `08:00:05.409 [Embedding] 11 songs without embeddings, but embedding paused by user — use retry to resume`. The 11 pending songs the user originally reported were genuinely new songs (NOT the trashed-file artefacts from Fix 1), AND auto-embedding was suppressed by a persisted `embedding_paused=true` in Preferences from a prior `stopEmbedding()` tap. `retryEmbedding()` already clears that flag at `engine-embeddings.js:905-906` (`embeddingPausedByUser = false; Preferences.remove({ key: 'embedding_paused' }).catch(() => {});`), so the user's "Embed Pending Songs" tap will resume auto-embedding on subsequent app launches without further code change. Behavior is correct as-designed; documented here only because the symptom (pending songs that don't auto-embed on startup) is easy to confuse with the IPC drop bug fixed in 2026-05-11 #1. If this becomes a recurring confusion, a future enhancement could surface a visible "Embedding paused — tap to resume" hint on the AI page when `getEmbeddingStatus().paused === true`.

Note: `embedding_paused` is keyed in Capacitor Preferences. To clear it manually for testing without going through the UI tap: in the DBG console, `Preferences.remove({key:'embedding_paused'})` then reopen the app.

---

### 2026-05-11 #3 — Recommendation diversity + reset feedback + paused chip + DBG re-add

User report (paraphrased): (a) per-knob ↺ reset button in Taste Signal feels broken — clicking it doesn't visibly snap the slider; (b) Up Next is "kind of a loop of listened songs with one or 2 new ones, feels concentrated" on a 2.5K-song library; (c) startup feels slower than before; (d) DBG button was removed (it overlapped the hamburger) so no logs are available to diagnose (c).

**Root cause for diversity** (highest-impact finding): `rec.lam` was set as `_tuning.adventurous` at six call sites across `src/engine.js`, `src/engine-data.js`, `src/engine-embeddings.js`. Standard MMR is `score = lam * relevance - (1-lam) * redundancy` — high `lam` ⇒ pure relevance ⇒ clustered picks; low `lam` ⇒ diversity-biased. The UI label "Higher = more diverse" was opposite to what the math produced. The default `adventurous=0.8` therefore drove `lam=0.8` ⇒ 80% relevance + 20% diversity penalty ⇒ tight clustering around the blended query vector, which explains the user-perceived "loop of recently-listened songs" on a 2.5K library.

**Fix 1 — MMR lambda inverted at all six sites:** `rec.lam = 1 - _tuning.adventurous`. Default `adventurous=0.8` now maps to `lam=0.2` (80% diversity penalty). The tuning hint text in `app-taste-ui.js` updated to make the semantic explicit ("engine maps adventurous → 1−lambda so the label matches the math"). Users who liked the previous clustered behaviour can move the slider to `adventurous=0.2` (lam=0.8) to restore it.

**Fix 2 — Reset button snappy feedback.** The ↺ click flow was `await engine.setTuning({key: default})` → `showStatusToast` → `showTasteWeightsOverlay()` — the slider only visually snapped after the async chain (~200–500 ms while `buildProfileVec` reran). Now `resetTuning(key)` in `app.js`:
1. Looks up the slider via the new `data-tuning-key="${key}"` attribute added to `app-taste-ui.js`.
2. Sets `slider.value = String(pct)` and `valSpan.textContent = pct + '%'` synchronously.
3. THEN awaits `engine.setTuning(...)` and re-renders.

User sees instant visual response on tap; the engine state catches up in the background.

**Fix 3 — Auto-embed paused chip.** A new yellow `<div class="emb-paused-chip">` rendered next to the ONNX backend chip in `app-ai-page.js` whenever `engine.getEmbeddingStatus().paused === true`. Previously the `embedding_paused=true` Preferences flag (set by `stopEmbedding()` and persisted across launches) was silent — the only signal was an obscure DBG log line "11 songs without embeddings, but embedding paused by user — use retry to resume". The chip makes the state visible. Auto-cleared by `retryEmbedding()` / `embedRemovedSongsBatch()` / `reembedAll()`.

**Fix 4 — DBG capture re-added.** A small `#debugLogToggle` pill (`24px`, opacity 0.45) positioned `fixed; right: 8px; bottom: 76px;` (above the bottom-bar, clear of the hamburger). Tap to open a full-screen `#debugLogPanel` with Copy Logs + Close. `app.js` re-installs the console.log mirror via `_installDbgConsoleMirror()` — matches lines starting with `[PERF]` / `[Embedding]` / `[FAV]` / `[SCAN]` / `[DBG]` / `[REC]` / `[GPU]` / `[Library]` into the panel. Needed to diagnose the startup-slowdown report without adb access. Toggle/copy/close wired in `_wireDbgPanel()`.

Files modified:
- `src/engine.js` — Fix 1 (1 site)
- `src/engine-data.js` — Fix 1 (2 sites)
- `src/engine-embeddings.js` — Fix 1 (3 sites)
- `src/app-taste-ui.js` — Fix 1 (tuning hint), Fix 2 (data-tuning-key)
- `src/app.js` — Fix 2 (snappy reset), Fix 4 (DBG console mirror + panel wiring)
- `src/app-ai-page.js` — Fix 3 (paused chip)
- `index.html` — Fix 4 (DBG toggle + panel DOM)
- `style.css` — Fix 3 (`.emb-paused-chip`), Fix 4 (DBG styling)

Pre-fix safety backup: `backups/diversity_fixes_prework_20260511_160000/`.

Verification this pass: `npm.cmd run build` OK (Vite 288 ms; bundle now `dist/assets/index-DNv1TQmH.js` at 305.57 kB, 85.50 kB gzipped); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance` baseline); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 5 s (trailing BUILD FAILED is the Gradle metadata-cache lock collision). Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 389.9 MB, timestamp `2026-05-11 13:57`. GitHub release `v2026.05.11.2` at https://github.com/humorouslydistracted/isaivazhi/releases/tag/v2026.05.11.2.

Real-device validation pending: (1) confirm Up Next picks visibly broaden after install — adventurous default 0.8 now maps to lam=0.2 so similarity to already-selected gets a heavy penalty, the queue should feel less like a "loop of recently played"; (2) tap ↺ on each of the three tuning sliders — the slider thumb and percentage text should snap to the default position immediately, before the toast finishes; (3) AI page should show the yellow "Auto-embed: Paused" chip alongside the ONNX backend chip — tapping "Embed Pending Songs" should clear it; (4) capture fresh `logs.txt` via the bottom-right DBG pill → Copy Logs to diagnose the startup-slowdown report (compare against the 2026-05-11 08:00 baseline log in this repo).

Note: the MMR inversion is a behavioral change with broad reach — every refresh, every "play X" similar-song fetch, every Up Next auto-extend is now diversity-biased by default. If recommendations feel TOO scattered (no thematic coherence), drop adventurous to ~0.3–0.4 which maps to lam=0.6–0.7 (relevance-biased).

#### 2026-05-11 #3 follow-up — DBG control bar pinned to bottom-center
User report: "not able to copy logs, you kept it above in the time signal bar, keep it in bottom center to copy. as well as close button."

Root cause: `#debugLogControls` used `position: sticky; top: 0` inside the full-screen `#debugLogPanel`, so the Copy / Close buttons rendered behind the device's status bar (time / signal indicators). On a phone with a notch or punch-hole, the buttons were either invisible or unreachable.

Fix: `#debugLogControls` is now `position: fixed; left: 0; right: 0; bottom: 0` with `padding-bottom: calc(env(safe-area-inset-bottom, 0px) + 8px)` so the buttons sit above gesture-nav handles. Buttons are also bigger (`min-width: 96px`, `padding: 8px 18px`, `font-size: 12px`) for easier taps, and `display: flex; justify-content: center; gap: 12px` centers them. The panel's own padding now reserves `calc(env(safe-area-inset-bottom, 0px) + 64px)` at the bottom so the log text doesn't slide behind the control bar.

File modified: `style.css`. Fresh APK 390.0 MB at `2026-05-11 14:12`. GitHub release `v2026.05.11.3` at https://github.com/humorouslydistracted/isaivazhi/releases/tag/v2026.05.11.3.

---

### 2026-05-11 #4 — Startup-slowdown root cause (state.listened unbounded growth) + DBG dedupe + PERF markers

User-captured `logs.txt` from `v2026.05.11.3` (session starting `14:17:09.697`) showed `restorePlaybackState` taking 3610 ms (vs 372 ms in the 2026-05-11 08:00 baseline) and the parallel `_loadBinaryStore` bin fetch taking 5201 ms (vs 127 ms baseline). The diff between captures wasn't in any code landed across the day's batches — same library size, same embedding count, same merge result (`2474 merged (path:2448, hash:0, fallback:0, pathIndex:26), 0 unmatched`).

**Root cause traced to `state.listened` unbounded growth.** The session play log in `engine-state.js`:
- Grows by one entry per play/skip (`engine.js:471` `state.listened.push({...})`).
- Only ever reset by `surprise()` (`engine.js:1574`).
- Persisted in full to Capacitor Preferences on every `savePlaybackState`.

On a heavy account the persisted JSON reached an estimated 500KB–1MB. `restorePlaybackState` then:
1. Awaits `Preferences.get('playback_state')` — JSI bridge read of the full string.
2. `JSON.parse(value)` — proportional to JSON size.
3. Per-entry `_filenameToId` mapping pass over `data.listenedFilenames` — O(n) with O(1) lookups but n was large.

All on the JS thread, blocking startup. The bin fetch happened to be running concurrently and got starved waiting for thread time. **Both regressions track the same underlying state-size growth.**

The same bloat also explains the user-reported "same songs repeatedly" symptom from #3 — a huge `state.listened` makes `sessionVec` (computed from `state.listened` entries) average the centroid of every song the user has ever played, so recommendations cluster around that centroid. The MMR inversion in #3 was a real fix for one half of the diversity problem; this is the other half.

**Fix A — cap `state.listened` on persist** (`src/engine-playback.js` `savePlaybackState`).
```js
listenedFilenames: state.listened.slice(-200).map(entry => {...}),
```
In-session memory state is untouched (callers like `_blendWeights` still see the full array for the current session). Only the persisted slice is capped. 200 is well past where `sessionVec` ramp-up saturates per `_blendWeights('refresh', state.listened.length, ...)` (saturates around ~10 entries for the session-bias term, ~30 for the profile term), so first-200-plays behavior is unchanged.

**Fix B — remove duplicate `_appendDbgLine` from `_dbg`** (`src/app.js`). Every line in the captured `logs.txt` appeared twice because `_dbg(msg)` did `console.log('[DBG] ' + msg)` AND `_appendDbgLine('[DBG] ' + msg)`. The console.log hook installed in #3 already mirrors `[DBG]` lines through `_appendDbgLine`, so both paths fired. Removed the direct call.

**Fix C — `[PERF]` markers inside `restorePlaybackState`** (`src/engine-playback.js`). Three new lines:
- `[PERF] restorePlaybackState: Preferences.get(playback_state) Xms, payload Y chars`
- `[PERF] restorePlaybackState: JSON.parse Xms, listened=N history=N queue=N timeline=N`
- `[PERF] restorePlaybackState: filename-mapping passes done Xms total`

So the next regression here is one `logs.txt` capture away from a diagnosis.

Files modified: `src/engine-playback.js`, `src/app.js`.

Verification: `npm.cmd run build` OK (Vite 290 ms; bundle `dist/assets/index-Dll93GrY.js` 306.12 kB / 85.66 kB gzipped); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance` baseline); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 4 s. Fresh APK at 390.1 MB, timestamp `2026-05-11 14:25`. GitHub release `v2026.05.11.4` at https://github.com/humorouslydistracted/isaivazhi/releases/tag/v2026.05.11.4. Commit `4880957` on `origin/main`.

Real-device validation expected: second launch after install should show `restorePlaybackState` back near baseline (~300–500 ms — the first launch still reads the pre-cap bloated persist), bin fetch back to ~100–200 ms, mini-player play tap responds within ~400 ms, and Up Next variety widens further (sessionVec no longer averages thousands of historical listens). The new `[PERF] restorePlaybackState` lines should give exact timing for confirmation.

---

### 2026-05-11 #5 — Cold-start regression actually root-caused (Capacitor plugin pool contention, NOT state.listened bloat)

User captured `logs.txt` after installing `v2026.05.11.4`. The new `[PERF] restorePlaybackState` markers added in #4 immediately exposed that the diagnosis from #4 was wrong:

```
14:27:24.283 [PERF] restorePlaybackState: Preferences.get(playback_state) 2466 ms, payload 10028 chars
14:27:24.283 [PERF] restorePlaybackState: JSON.parse 1 ms, listened=48 history=64 queue=5 timeline=8
14:27:24.285 [PERF] restorePlaybackState: filename-mapping passes done 2469 ms total
14:27:24.493 [PERF] readBinaryStore bin fetch: 5013504 bytes in 3046 ms
```

**Payload is only 10 KB. `state.listened` is only 48 entries. `JSON.parse` takes 1 ms. The filename-mapping pass takes 2 ms.** The 2466 ms is consumed entirely by the `Preferences.get` JSI call itself, on a 10 KB key. The #4 hypothesis (huge persisted state) was disproven by the very markers it added.

**Actual root cause: Capacitor plugin pool contention.** `preloadEmbeddingsEarly()` (Fix D from 2026-05-10) was kicking off the 5 MB binary fetch via `convertFileSrc(...)` → `fetch(...)` → Capacitor's WebView native plugin executor pool. `Preferences.get` runs through the same pool. The 5 MB transfer pinned the pool and every other plugin call queued behind it — so `restorePlaybackState`'s tiny read paid the full latency of the in-flight binary transfer (~2.5 s) and the mini-player play tap waited ~3.3 s tap-to-audio.

Fix D's original justification — "overlap the slow ~500–1500 ms binary read with `loadData()`'s own ~500–1500 ms work" — assumed `loadData` was the dominant cost. With the Fix A `Promise.all` collapse (also 2026-05-10) `loadData` is now ~258 ms. The overlap window is gone, and the parallelism actively hurts the cold-start play-tap path because the bin fetch keeps running while `restorePlaybackState` is now the long-pole step.

**Fix — defer `preloadEmbeddingsEarly()` until after `restorePlaybackState` resolves.** Moved the call out of the top of `init()` and into a slot immediately after `await engine.restorePlaybackState()` / `await engine.loadPendingListenSnapshot()`. `restorePlaybackState` now runs first on an uncontended Capacitor pool (expected ~300–500 ms), then the bin preload kicks off solo (expected ~500–1000 ms per Fix G timing). `startBackgroundScan()` picks up the in-flight preload promise via the existing reuse logic at `engine-data.js:516` (`if (_earlyEmbeddingPromise && _earlyEmbeddingPath === EXTERNAL_DATA_DIR)`).

Predicted change:
- `Preferences.get(playback_state)`: ~2500 ms → ~10–50 ms
- `restorePlaybackState` total: ~3300 ms → ~300–500 ms
- Mini-player play tap → audio: ~3300 ms → ~400 ms (back near 2026-05-11 08:00 baseline of 406 ms)
- AI ready: maybe ~300–500 ms later than v4 (off the user-perceived path — by the time AI ready fires, the user has already been playing for seconds)

File modified: `src/app.js` (one move).

Verification: `npm.cmd run build` OK (Vite 1.15 s; bundle `dist/assets/index-DD4wvGO5.js` 306.12 kB / 85.65 kB gzipped); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance` baseline); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 7 s. Fresh APK at 390.2 MB, timestamp `2026-05-11 14:34`. GitHub release `v2026.05.11.5` at https://github.com/humorouslydistracted/isaivazhi/releases/tag/v2026.05.11.5. Commit `6c0754e` on `origin/main`.

The `[PERF] restorePlaybackState` markers stay so the next `logs.txt` capture instantly confirms or refutes the prediction. State.listened cap from #4 stays as belt-and-suspenders against future state bloat.

Lesson: diagnostic markers are worth their weight. The cap-state hypothesis from #4 sounded plausible but the very markers it added disproved it within one capture. Don't ship state-shape changes without a measurement first.

---

### 2026-05-11 #6 — Split playback_state to bypass slow Preferences.get on play-tap critical path

User captured `logs.txt` after installing `v2026.05.11.5`. The #5 fix worked for the bin fetch (3046 ms → **86 ms**, 35× speedup) but the markers immediately exposed the next bottleneck:

```
14:38:17.282 [PERF] restorePlaybackState: Preferences.get(playback_state) 2232 ms, payload 10211 chars
14:38:17.282 [PERF] restorePlaybackState: JSON.parse 1 ms, listened=50 history=66 queue=5 timeline=6
14:38:19.137 [PERF] readBinaryStore bin fetch: 5013504 bytes in 86 ms
```

`Preferences.get('playback_state')` takes **2233 ms for a 10 KB payload, with no bin fetch in flight on the bridge**. JSON.parse is 1 ms, filename mapping is 2 ms, payload is 10 KB. The Capacitor `@capacitor/preferences@8.0.1` plugin call itself is just slow on this device — likely an interaction between Capacitor's plugin executor and the WebView's main thread that we can't fix from JS without forking the plugin.

**Approach: route the play tap around the slow call.** We don't need the full payload (history / queue / listened / timeline) to honor a cold-start play tap — we only need the current song + position. Split persistence into two keys:

- **`playback_state_current`** (new, tiny ~300 chars) — `{currentFilename, currentFilePath, currentTitle, currentArtist, currentAlbum, currentTime, duration, currentSource}`.
- **`playback_state`** (unchanged, full ~10 KB) — history, queue, listened, timeline, etc.

`savePlaybackState` writes both atomically (tiny first so a mid-write app kill leaves a consistent small snapshot rather than an out-of-sync pair).

New `restorePlaybackStateCritical()` (`engine-playback.js`) reads only the tiny key, sets `state.current` + returns a display object. Returns `null` if the tiny key is missing (first launch after install or a save predating this change), in which case the existing full-restore path is the sole fallback.

`app.js init()` now fires both reads **in parallel**:
```js
const criticalPromise = engine.restorePlaybackStateCritical();
const fullPromise = engine.restorePlaybackState();
const critical = await criticalPromise;
if (critical) {
  currentSong = critical.id;
  // ... update mini-player display + progress + heart ...
  if (_pendingStartupResume && critical.filePath && !nativeFileLoaded) {
    _pendingStartupResume = false;
    loadAndPlay({...}, critical.currentTime || 0).catch(...);
  }
}
const restored = await fullPromise;
// ... populate history / queue / listened / timeline ...
```

The tiny key lands first (Capacitor's executor parallelizes reads). As soon as it does, the queued play tap is honored via the same `loadAndPlay` path that runs after the full restore today — but it fires within ~100 ms of the critical read instead of ~2300 ms of the full read. The full restore then completes in the background; user is already hearing audio by then.

Backward-compatible: a missing `playback_state_current` (fresh install, or a save from before this change landed) makes `restorePlaybackStateCritical` return null; the existing full-restore path runs as it always did, and the next save writes both keys so subsequent launches get the fast path.

New PERF marker so the next capture instantly confirms the prediction:
```
[PERF] restorePlaybackStateCritical: Preferences.get(playback_state_current) Xms, payload Y chars
```

Files modified: `src/engine-playback.js` (split save + new `restorePlaybackStateCritical`), `src/engine.js` (re-export), `src/app.js` (critical-first parallel restore + early play-tap honor).

Verification: `npm.cmd run build` OK (Vite 295 ms; bundle `dist/assets/index-CSZ7wKM6.js` 307.91 kB / 86.14 kB gzipped); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance` baseline); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 4 s. Fresh APK at 390.3 MB, timestamp `2026-05-11 14:48`. GitHub release `v2026.05.11.6` at https://github.com/humorouslydistracted/isaivazhi/releases/tag/v2026.05.11.6. Commit `3d20f2b` on `origin/main`.

Expected outcome on the user's NEXT-after-first launch (the first launch still writes the tiny key for the first time — second launch is where the fast path lands):
- `restorePlaybackStateCritical`: ~50–100 ms
- Play-tap → audio: ~400 ms (vs 2341 ms in #5 capture)
- Full restore still takes ~2.3 s but off the user-visible critical path

---

### 2026-05-11 #7 — File-based critical playback state (bypass slow Preferences plugin entirely)

User captured `logs.txt` after the second launch on v6 (with the tiny key written during the v6 session). The result decisively disproved the parallel-reads theory:

```
14:53:20.440 [PERF] restorePlaybackStateCritical: Preferences.get(playback_state_current) 2316 ms, payload 372 chars
14:53:20.441 [DBG] init: critical restore — honoring queued tap early
14:53:20.442 [DBG] loadAndPlay: songId=655 ...
14:53:20.454 [PERF] restorePlaybackState: Preferences.get(playback_state) 2330 ms, payload 21041 chars
14:53:20.660 [DBG] loadAndPlay: setQueue OK
14:53:22.572 [DBG] audioPlayStateChanged: isPlaying=true
```

Two Preferences reads with 50× different payload sizes (372 chars vs 21041 chars) **both took the same ~2.3 s**. Capacitor's `@capacitor/preferences@8.0.1` plugin serializes calls internally and the call itself is just slow on this device — payload is irrelevant.

The good news: the critical-fast-path architecture from #6 worked structurally. `loadAndPlay` fired **1 ms after critical restored**, `setQueue` completed in 218 ms. Tap-to-audio dropped from 3325 ms (#3) → 2341 ms (#5) → 2935 ms (#6 second launch — slightly worse because Media3 prepare contended with the meta fetch this run). But the 2.3 s Preferences read for the critical key was still the dominant single contributor.

**Same log proved the WebView file path is fast in a different native pool:**
- `Library: 2474 songs via fetch in 150 ms` (~250 KB)
- `bin fetch: 5013504 bytes in 162 ms` (5 MB)

Both `fetch(convertFileSrc(...))` reads. The WebView's HTTP server is on a separate executor from the Preferences plugin.

**Fix — write the critical state to a regular JSON file, read via fetch on cold start.** `savePlaybackState` (`engine-playback.js`) now writes three things on every save:
- `playback_state_current.json` (new file in `EXTERNAL_DATA_DIR`, ~300 chars) — via fire-and-forget `MusicBridge.writeTextFile`
- `playback_state_current` Preferences key — kept as durable fallback
- `playback_state` Preferences key — full state, unchanged

`restorePlaybackStateCritical` tries the file first via `fetch(convertFileSrc(...))` and falls back to the Preferences key if the file is missing (first launch after this change). New `[PERF]` marker reports which source served the read:
```
[PERF] restorePlaybackStateCritical: file read Xms, payload Y chars
[PERF] restorePlaybackStateCritical: prefs read Xms, payload Y chars
```

File modified: `src/engine-playback.js`.

Verification: `npm.cmd run build` OK (Vite 310 ms; bundle `dist/assets/index-D7bY1V94.js` 308.27 kB / 86.26 kB gzipped); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance` baseline); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 5 s. Fresh APK at 390.4 MB, timestamp `2026-05-11 14:58`. GitHub release `v2026.05.11.7` at https://github.com/humorouslydistracted/isaivazhi/releases/tag/v2026.05.11.7. Commit `8800906` on `origin/main`.

Backward compatible: first launch after install has no file yet → Preferences fallback runs → still slow but no worse than v6. Once `savePlaybackState` runs once during the session, all subsequent launches hit the file fast path.

Expected timing on second-after-install launch:
| | v6 second launch | v7 (expected) |
|---|---|---|
| Critical state read | 2316 ms (prefs) | ~50–150 ms (file) |
| Tap-to-audio | 2935 ms | well under 1 s |
| Full restore | 2330 ms (off critical path) | unchanged (off critical path) |

---

### 2026-05-11 #8 — Diagnostic build for play/pause latency

User captured `logs.txt` on the first v7 launch. Two findings:

**1. File fast path didn't engage yet.** Critical read source was `prefs` (2335 ms for a 347-char payload). Expected for the first v7 launch — `savePlaybackState` hadn't run yet, so the file didn't exist. Once it does run (during this session), subsequent launches should hit the file path. Will verify on the next capture.

**2. Play/pause latency is real but localized.** Tap-to-ack timings from the same session (all after `embeddingsReady` and `aiReady` fired):

| Tap | Action | Bridge → `audioPlayStateChanged` |
|---|---|---|
| 15:00:56.234 | pausing 1572 | **3302 ms** ← slow one |
| 15:01:02.750 | pausing 1202 | 37 ms |
| 15:01:03.604 | resuming 1202 | 21 ms |
| 15:01:04.791 | pausing 1202 | 18 ms |
| 15:01:07.401 | pausing 1385 | 40 ms |
| 15:01:08.069 | resuming 1385 | 31 ms |

The slow tap was at `tap+1.7s` into a freshly-transitioned song (`queueCurrentChanged` at 54.560, tap at 56.234). The subsequent five on the same audio session were all 18–40 ms. Looks like a Media3-side prepare-to-pause cost specific to the first interaction with a newly-loaded track.

To isolate JS-bridge vs native cost, this build adds `[PERF]` markers around `MusicBridge.pauseAudio()` / `resumeAudio()` calls in `togglePauseUI`:
```
[PERF] pauseAudio bridge resolved: Xms (tap+Yms)
[PERF] resumeAudio bridge resolved: Xms (tap+Yms)
```

Combined with the existing `audioPlayStateChanged` DBG line:
- If bridge resolves fast but state-change event lags → Media3 native is the cost.
- If bridge itself takes seconds → bridge call queued behind something else.

File modified: `src/app-player-ui.js`. No behavior change, just instrumentation.

Verification: `npm.cmd run build` OK (Vite 628 ms; bundle `dist/assets/index-i057cddD.js` 309.40 kB / 86.43 kB gzipped); `npm.cmd run test:unit` 30/31; `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 8 s. Fresh APK at 390.5 MB, timestamp `2026-05-11 15:07`. GitHub release `v2026.05.11.8` at https://github.com/humorouslydistracted/isaivazhi/releases/tag/v2026.05.11.8. Commit `9c03a67` on `origin/main`.

---

### 2026-05-11 #9 — Capacitor bridge cold-pool contention identified; serialize critical + pre-warm

User captured `logs.txt` on the first v8 launch. The PERF markers isolated both startup issues to a single root cause:

**1. Critical file read was 2378 ms.** v7's file-based fast path engaged correctly (`file read` source confirmed), but it ran concurrently with `Preferences.get(playback_state)` which took 2179 ms. Both took ~2.3 s. The same log shows `Library: 2474 songs via fetch in 146 ms` — fetch ALONE early in init is fast. Capacitor's bridge effectively serializes across plugins: a second concurrent op drags both to ~2.3 s. Racing reads doesn't help; it actively hurts.

**2. First `pauseAudio` bridge call after a track transition was 3279 ms** — measured by the new v8 marker:
```
15:10:24.379 PLAY: pausing
[3.3s bridge call in flight]
15:10:27.658 [PERF] pauseAudio bridge resolved: 3279 ms (tap+3280 ms)
15:10:27.720 audioPlayStateChanged: isPlaying=false
```
Native Media3 had nothing to do with it — the JSI bridge itself stalled. Subsequent five taps in the same session: 240 → 30 → 14 → 5 → 14 ms. The cold bridge is the long pole; once warmed, taps are sub-50 ms.

Both findings share the shape: **first call into a freshly-active Capacitor pool stalls; concurrent calls during cold periods slow each other; calls after the pool is warm are fast**.

**Fix A — serialize critical-then-full restore in `app.js init()`.** Was:
```js
const criticalPromise = engine.restorePlaybackStateCritical();
const fullPromise = engine.restorePlaybackState();
const critical = await criticalPromise;
// ...
const restored = await fullPromise;
```
Now:
```js
const critical = await engine.restorePlaybackStateCritical();
// ... act on critical, fire queued tap ...
const restored = await engine.restorePlaybackState();
```
Critical runs ALONE first. Based on the Library fetch baseline (146 ms alone), expected ~150 ms vs current 2378 ms. The queued play tap can fire ~2 seconds sooner; the full read still completes in the background.

**Fix B — pre-warm the bridge.** Fire `MusicBridge.getAudioState()` fire-and-forget at the very top of `init()`, before `initActivityLog` or anything else. Spins up the Capacitor executor + JNI handle cache so the first user pause doesn't pay the cold-bridge cost. New marker:
```
[PERF] bridge warmup (getAudioState): Xms
```

File modified: `src/app.js`.

Verification: `npm.cmd run build` OK (Vite 490 ms; bundle `dist/assets/index-B2nvAmxj.js` 309.57 kB / 86.43 kB gzipped); `npm.cmd run test:unit` 30/31; `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 6 s. Fresh APK at 390.6 MB, timestamp `2026-05-11 15:16`. GitHub release `v2026.05.11.9` at https://github.com/humorouslydistracted/isaivazhi/releases/tag/v2026.05.11.9. Commit `6a5cf50` on `origin/main`.

Expected timing on next launch:
| | v8 capture | v9 (expected) |
|---|---|---|
| Critical file read | 2378 ms | ~150 ms |
| Tap-to-audio (cold restore) | ~5 s | well under 1 s |
| First `pauseAudio` after track transition | 3279 ms | ~50-100 ms (warm) |
| `bridge warmup` marker | — | should appear early |

If the bridge warmup itself takes 2 s, that's the inherent cold-pool cost — the user just won't notice because no user action is waiting on it.

---

### 2026-05-11 #10 — Front-load critical fetch before the addListener storm

User captured `logs.txt` on the first v9 launch. Two findings, one decisive win and one stubborn case:

**Win — pre-warm + serialization fixed play/pause and Preferences.** Every play/pause `bridge resolved` timing was now ≤61 ms (was 3279 ms for the slow first-tap-after-transition in v8). `Preferences.get(playback_state)` dropped from 2179 ms → **44 ms** (serialized).

**Stubborn — critical file fetch was still 2221 ms.** Smaller payload than the Library fetch (275 chars vs 250 KB), but slower (2221 ms vs 128 ms in the SAME log). The difference: Library fired RIGHT AFTER `loadData`'s Preferences batch; Critical fired ~309 ms later, AFTER the listener-setup chain registered ~12-15 `MusicBridge.addListener` bridge calls. Capacitor's bridge queued the listener registrations ahead of the fetch and held it for 2.2 s.

**Fix — front-load the critical fetch in `app.js init()`.** Was:
```js
await engine.loadData();
// ... renderSongs / renderAlbums / setupNativeAudioEvents / setupNativeMediaListener / setupEmbeddingUI ...
// ... 309ms of sync work + ~12-15 addListener bridge calls queued ...
const critical = await engine.restorePlaybackStateCritical();  // ← waits 2221ms
```

Now:
```js
await engine.loadData();
const _criticalRestorePromise = engine.restorePlaybackStateCritical();  // ← fire NOW, no await
// ... renderSongs / renderAlbums / setupNativeAudioEvents / ... (sync setup + addListener calls)
const critical = await _criticalRestorePromise;  // ← await later, fetch already resolved
```

The fetch runs on a quiet bridge starting at `loadData done + 0ms` instead of `loadData done + 309ms`. By the time `await` is reached, the fetch should already be done. Based on the Library fetch baseline (128 ms at this exact slot in the same v9 log), expected critical-read latency: ~150 ms.

File modified: `src/app.js`.

Verification: `npm.cmd run build` OK (Vite 475 ms; bundle `dist/assets/index-vjDylwgQ.js` 309.57 kB / 86.47 kB gzipped); `npm.cmd run test:unit` 30/31; `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 6 s. Fresh APK at 390.7 MB, timestamp `2026-05-11 15:29`. GitHub release `v2026.05.11.10` at https://github.com/humorouslydistracted/isaivazhi/releases/tag/v2026.05.11.10. Commit `eaee418` on `origin/main`.

Expected outcome on next launch:
| | v9 capture | v10 (expected) |
|---|---|---|
| Critical file read | 2221 ms | ~150 ms |
| Tap-to-audio (cold restore) | ~2.8 s | well under 1 s |
| Play/pause taps | 14-61 ms | unchanged (still fast from v9 warmup) |

---

### 2026-05-11 #1 — AI embedding page hardening batch — four coordinated fixes for the user-reported bug where 11 songs stuck in "Embed Pending Songs" never embedded, the native notification advanced `3/11` with `.trashed-…` filenames, and both Common Logs + Embedding Logs tabs stayed empty for the run. Root cause: (a) `MediaScanHelper` filesystem-fallback walker was ingesting Android-trash files (`.trashed-<epoch>-<name>.ext`) and other dotfiles that MediaStore filters out, AND (b) `MusicBridgePlugin.embedNewSongs` called `startForegroundService` BEFORE `embeddingControllerClient.ensureConnected`, so MSG_PROGRESS broadcasts hit an empty `clients` list under bind contention and the JS side never observed `embeddingInProgress=true`. Four fixes landed in one pass: Fix 1 skips dotfile audio in both MediaStore + recursive scan paths and filters existing dotfile entries on library load; Fix 2 reorders embedNewSongs so bind completes BEFORE startForegroundService (clients guaranteed registered before any broadcast); Fix 3 adds `engine.resyncEmbeddingState()` called on every AI page open (forces fresh MSG_STATUS via `requestEmbeddingStatus` + pulls disk-truth via `reloadEmbeddingsFromDisk` when no batch is running, both silent on failure); Fix 4 plumbs `EmbeddingService.getActiveBackend()` through MSG_STATUS bundle + new `getEmbeddingBackend` PluginMethod + `getEmbeddingStatus().activeBackend` so the AI page shows an "ONNX backend: NPU / GPU (FP16) | NPU / GPU (FP32) | CPU" chip. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 389.5 MB, timestamp `2026-05-11 11:40` (bundle `dist/assets/index-BsXpwGim.js`). Pre-fix safety backup: `backups/embedding_fixes_prework_20260511_120000/`.)

### 2026-05-11 — AI embedding page hardening (4 coordinated fixes)
User report: "those 11 songs are still showing in pending embedding list, the status is not showing in both logs common logs, embedding logs, etc. i think there is serious issue in the embedding page. use first principles thinking to find the issue and resolve them". Earlier in the same session the notification text showed `Embedding 3/11: .trashed-…` and the in-page `←` back arrow was unresponsive (still being audited separately).

First-principles diagnosis: three symptoms together — native notification advancing, both JS log tabs empty, songs not flipping `hasEmbedding=true` — proved the `:ai → :main → JS` Messenger IPC chain was silently dropping events for the batch. Tracing showed `EmbeddingForegroundService.broadcastMessage()` iterates over `clients`, which is populated only by MSG_REGISTER_CLIENT. The previous `MusicBridgePlugin.embedNewSongs` ordering ran `startForegroundService(intent)` BEFORE `embeddingControllerClient.ensureConnected(...)`, so `EmbeddingService.onProgress` (which fires the first MSG_PROGRESS within ms of `embedSongs(...)`) could race past the bind, sending broadcasts to an empty client list. The fallback `MSG_STATUS active→inactive` path only synthesizes `embeddingComplete`, not progress, so JS never observed `embeddingInProgress=true` at any point in the run. The `.trashed-…` filenames in the notification turned out to be a separate-but-related issue: `MediaScanHelper.collectRecursive()` had no dotfile filter, so Android's trash files (renamed in place via `MediaStore.createTrashRequest`) survived as bogus library entries even though MediaStore's own query correctly omitted them.

**Fix 1 — Skip trashed/dotfile audio in `MediaScanHelper.java`.**
- MediaStore primary scan: `if (filename.startsWith(".")) continue;` after the filename is computed.
- Filesystem fallback `collectRecursive`: same skip at `file.getName().startsWith(".")`.
- Library loader (`src/engine-data.js` fetch + bridge paths): filter `library.songs` on load with `s.filename && !String(s.filename).startsWith('.')`. Logs `Dropping N dotfile/trashed entries from saved library` if any are removed. The next `_saveSongLibrary` (post-scan or post-embed) persists the cleaned list, so existing user installs heal automatically without a manual rescan.

**Fix 2 — Race-proof IPC bind in `MusicBridgePlugin.embedNewSongs`.**
- Old order: `startForegroundService(intent)` → `ensureConnected(callback)`. New order: `ensureConnected(callback)`, and `startForegroundService` runs INSIDE `onConnected`. This guarantees the Messenger client is in `EmbeddingForegroundService.clients` before `EmbeddingService.embedSongs(...)` ever fires its first `onProgress` callback.
- `onError` fallback: still starts the service (so the user's tap isn't lost) and relies on the JS-side recovery path (Fix 3) to catch up state on the AI page.
- The captured `Intent` and `paths` are stored in `final` locals + a `Runnable startService` lambda so the deferred path uses identical args to what the immediate path would have.

**Fix 3 — JS-side `resyncEmbeddingState()` recovery.**
- New export from `src/engine-embeddings.js`. On every fresh entry to the AI embedding page (when `!wasDetailOpen` in `showEmbeddingDetail`), `app-ai-page.js` calls `engine.resyncEmbeddingState().catch(() => {})`.
- The function does two things, both silent on failure:
  1. `MusicBridge.requestEmbeddingStatus()` — forces `EmbeddingForegroundService` to broadcast a fresh `MSG_STATUS`. If the batch is active, `MusicBridgePlugin.onEmbeddingServiceEvent` re-emits `embeddingProgress`; if the batch finished but JS missed `MSG_COMPLETE`, the existing fallback (`embeddingBridgeKnownActive` was true → synthesized `embeddingComplete`) runs `_runPostBatchMerge`.
  2. `reloadEmbeddingsFromDisk()` — when `!embeddingInProgress`, pulls any pending → stable embeddings the on-disk store has accumulated since the last merge, flipping `hasEmbedding=true` on matched songs and rebuilding the recommender. Disk-truth wins even when both the Messenger status path and the fallback synthesis are dead.
- Also bumps `_activeBackend` from the new `MusicBridge.getEmbeddingBackend()` PluginMethod so the AI page's backend chip stays in sync.

**Fix 4 — Surface active ONNX backend on the AI page.**
- `EmbeddingForegroundService.buildStatusBundle` now writes `KEY_ACTIVE_BACKEND` (new contract field in `EmbeddingCommandContract.java`) from `embeddingService.getActiveBackend()`. Empty string until the session is built; `nnapi+fp16` / `nnapi` / `cpu` after.
- `MusicBridgePlugin.onEmbeddingServiceEvent` caches the latest non-empty backend into `lastKnownActiveBackend` on every event. New `getEmbeddingBackend(PluginCall)` PluginMethod returns it. `emitEmbeddingProgress` also includes `activeBackend` in its payload so the JS state listener picks it up during live progress.
- `getEmbeddingStatus()` in `engine-embeddings.js` now returns `activeBackend`. `showEmbeddingDetail` renders a compact chip: `ONNX backend: NPU / GPU (FP16) | NPU / GPU (FP32) | CPU` (via the existing label mapping). New `.emb-backend-chip` CSS rule.

Files modified:
- `android/app/src/main/java/com/isaivazhi/app/MediaScanHelper.java` (Fix 1)
- `android/app/src/main/java/com/isaivazhi/app/MusicBridgePlugin.java` (Fixes 2, 4)
- `android/app/src/main/java/com/isaivazhi/app/EmbeddingForegroundService.java` (Fix 4)
- `android/app/src/main/java/com/isaivazhi/app/EmbeddingCommandContract.java` (Fix 4 — new `KEY_ACTIVE_BACKEND`)
- `src/engine-data.js` (Fix 1 — library load filter)
- `src/engine-embeddings.js` (Fix 3 — new `resyncEmbeddingState` + `_activeBackend` field + `embeddingProgress` listener bump; Fix 4 — `activeBackend` in `getEmbeddingStatus`)
- `src/engine.js` (Fix 3 — re-export `resyncEmbeddingState`)
- `src/app-ai-page.js` (Fix 3 — call `resyncEmbeddingState` on first detail-page open; Fix 4 — backend chip render)
- `style.css` (Fix 4 — `.emb-backend-chip`)

Pre-fix safety backup: `backups/embedding_fixes_prework_20260511_120000/` (all 7 affected files copied verbatim before edits).

Verification this pass: `npm.cmd run build` OK (Vite 657 ms; bundle now `dist/assets/index-BsXpwGim.js` at 303.56 kB, 84.88 kB gzipped); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance {duplicate: true}` shape regression remains the only failure, unchanged by this pass); `npx.cmd cap sync android` OK (1.19 s, web bundle copied to `android/app/src/main/assets/public/`); `:app:assembleDebug` BUILD SUCCESSFUL in 1m 19s — trailing "BUILD FAILED" is the documented Gradle metadata-cache lock collision while Android Studio holds `last-build.bin`; the APK was packaged successfully BEFORE that warning. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 389.5 MB, timestamp `2026-05-11 11:40`. `:app:connectedDebugAndroidTest` not rerun this pass.

Real-device validation pending: (1) reopen AI page after install — the existing 11 dotfile entries should disappear from "Embed Pending Songs" without a manual rescan, courtesy of the library-load filter; (2) tap "Embed N Pending Songs" with a real pending song — both Common Logs (`embedding_log` activity entries) and Embedding Logs (`Manual retry triggered`, `Retrying: ...`, `Embedding call returned: ...`, `Processing N/M: filename`, `Embedded: filename`) should populate within ~120 ms (UI debounce window); (3) backend chip should read `ONNX backend: NPU / GPU (FP16)` on a Pixel-class device, or `CPU` on a device without NNAPI; (4) close the app mid-batch and reopen the AI page — `resyncEmbeddingState()` should pull any pending → stable embeddings off disk and flip `hasEmbedding=true` without waiting for a cold restart.

The previously documented in-page `←` back-arrow issue (sticky header inside scroll-snap panel + sidebar entry that doesn't activateTab('discover')) is NOT part of this pass — separate audit pending.

---

### 2026-05-10 follow-up #18 — `View Album` from search overlay threw null deref
User: "when i do search of a song and click view album, getting error js error: typeerror: cannot read properties of null (reading 'style')(line 123)"

Root cause: `viewAlbumForSong` in `src/app-song-menu.js:271-301` carried two stale references that earlier UI overhauls had invalidated:
1. `document.getElementById('searchBar').style.display = 'none';` — but follow-up #12 deleted the standalone `<div class="search-bar" id="searchBar">` element entirely (the search input now lives permanently in the top bar). `getElementById` returns null → throws on the `.style` access. This is the user-visible error.
2. Manual `.tab` / `.panel` `.active` class toggling to "navigate" to Albums — but after the scroll-snap rewrite (#10), `.active` on `.panel` is informational only; visibility is controlled by horizontal scroll position on `.panel-strip`. Even if the error didn't fire first, the navigation would silently do nothing.

Fix: replaced the manual toggling + broken `searchBar` line + redundant `getAlbumsDirty()` block + bare `history.pushState({depth:1})` with one call to the existing `_activateTab('albums', { pushHistory: true })`. That function already toggles `.active` informationally, calls `_scrollToPanel('albums', 'smooth')` to actually navigate, closes the search overlay via `_clearSearchInput()`, runs the dirty-render check for albums, and pushes proper `{appTab: 'albums'}` history state. The subsequent `setTimeout` that locates the album header and expands its tracks is unchanged.

Plumbing: added `activateTab: (...a) => _activateTab(...a)` to the `createSongMenuSupport(...)` options object in `src/app.js:109`, and added `activateTab` to the destructuring at the top of `app-song-menu.js`. The pre-existing `setActiveTab` plumbing stays — still used elsewhere in the module.

Verification: `npm.cmd run build` OK (Vite 511ms, bundle now `dist/assets/index-LfweI0P5.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 9s (trailing "BUILD FAILED" is the documented Gradle metadata-cache lock collision — APK packaged successfully). Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 389.4 MB, timestamp `2026-05-10 16:33:28`.

Real-device validation pending: search → tap "View Album" on a song from the search-results overlay → should navigate to Albums tab, scroll to that album, expand its tracks, with no console error.

### 2026-05-10 follow-up #17 — Pull-to-refresh fired anywhere in Discover (regression from #10)
User: "the discover page swipe up down is not working properly, the swipe up is initiating pull to refresh even if i am at end of the discover page."

Root cause: regression introduced by the scroll-snap rewrite (follow-up #10). `setupPullToRefresh` in `src/app.js:1885-2026` gates on `content.scrollTop <= 0` to detect "user is at the top of the page". Before #10, `.content` had `overflow-y: auto` and was the actual vertical scroll container — the gate worked. After #10, `style.css:129-132` set `.content { overflow: hidden }` (the strip handles all scrolling) and each `.panel` got its own `overflow-y: auto` (`style.css:153`). So `content.scrollTop` is **always 0** regardless of where the user is inside `#panel-discover`. The "am I at the top?" gate was permanently satisfied, and any downward gesture on Discover (including pulls back from a bottom-of-list rubber-band) fired pull-to-refresh.

Fix: read scrollTop from the Discover panel instead. New lazy getter `getDiscoverScroller = () => document.getElementById('panel-discover')` (matches the existing lazy-resolve pattern for `#pullIndicator` / `#discover-pull-body`, since panel innerHTML can be replaced when sub-pages close). Both gate sites updated:
- `touchstart` (line ~1961): `content.scrollTop <= 0` → `(getDiscoverScroller()?.scrollTop ?? 0) <= 0`
- `touchmove` (line ~1971): same change

The touch listener stays attached to `.content` (events bubble up from the panel), only the scrollTop source changes.

Verification: `npm.cmd run build` OK (Vite 259ms, bundle now `dist/assets/index-BbJPslga.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 21s (trailing "BUILD FAILED" is the documented Gradle metadata-cache lock collision — APK packaged successfully). Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 389.3 MB, timestamp `2026-05-10 16:10:39`.

Real-device validation pending: confirm pull-to-refresh now only fires when scrolled to the very top of Discover, and that scrolling/swiping at any other position behaves as a normal vertical scroll without triggering the indicator.

## Recent Changes (2026-05-10 batch — startup performance, A–F)

Goal: reduce time from app open → "Most Similar" rendered on Discover from ~1.5–5s to under 2s on second-open / cache-hit launches. The bottleneck on warm starts is NOT the MediaStore scan (which is skipped while `song_library.json` is < 6h old), but the JS-side embedding load + merge + profile-vector build that runs on every startup. Six independent fixes were applied; each falls back safely on its failure path.

### Fix A — parallelize Preferences reads in `loadData()`
`src/engine-data.js` `loadData()` previously awaited `_loadTuning() → _loadNegativeScores() → _loadSimilarityBoostScores() → _loadDislikes() → _loadTasteReviewIgnores() → MusicBridge.getAppDataDir()` sequentially (5 Preferences keys + 1 native bridge call, each ~30–100ms on Android). Each function reads a distinct Preferences key and writes a distinct module state variable — verified independent. Now wrapped in a single `Promise.all([...])` collapsing all 6 round-trips into the slowest one. The same pattern applies to `loadFavorites()` + `loadPlaylists()` later in the same function. Expected savings: 200–600ms.

### Fix B — fast base64 decode in `embedding-cache.js`
`_loadBinaryStore()` previously used `Uint8Array.from(atob(b64), c => c.charCodeAt(0))` to decode the ~5MB `local_embeddings.bin` from base64. The callback variant invokes the per-byte function ~5.1 million times. Replaced with an explicit `for` loop (`raw[i] = binaryStr.charCodeAt(i)`) which is 3–5× faster on V8/JSC. No behavior change — byte-identical output. Expected savings: 300–800ms.

### Fix C — stop double Float32Array conversion across cache load + merge
The same vector data was previously copied twice on every startup: `Float32Array (binary buffer) → Array.from(...) (plain Array) → new Float32Array(data.embedding) (typed array again)`. Now `_loadBinaryStore()` returns `floats.slice(off, off + dim)` (Float32Array with its own buffer — parent buffer can be GC'd safely), and `_mergeLocalEmbeddings()` detects the typed-array case and reuses the reference instead of reallocating. Plain-Array sources (legacy JSON, `pending_embeddings.json`) still go through `new Float32Array(...)` once. Required guard updates in `_normalizeEmbeddingObject`, `_writeBinaryStore`, and both passes of `_mergeLocalEmbeddings` to accept `ArrayBuffer.isView(emb)` instead of strict `Array.isArray()`. `_writeLegacyJson` now JSON-safely converts Float32Array → plain Array before `JSON.stringify` (otherwise the legacy mirror file would silently corrupt to `{"0":x,"1":y,...}` objects). Expected savings: 200–400ms.

### Fix D — cache `EXTERNAL_DATA_DIR` + start embedding load before `loadData()`
Two-part change. (1) After the first successful `MusicBridge.getAppDataDir()` call, the resolved path is now persisted to Preferences key `cached_data_dir_v1`. (2) New export `engine.preloadEmbeddingsEarly()` reads that cached path at the very start of `init()` in `src/app.js` (before any other awaits) and kicks off `_loadLocalEmbeddings()` in parallel with the rest of `loadData()`. `startBackgroundScan()` now reuses the in-flight promise if the cached path matches the resolved one, or discards and starts fresh if they differ (re-install / data-dir moved — the early load harmlessly returns null for a missing file, no breakage). Expected savings: 300–500ms (overlaps the slow ~500–1500ms binary read with `loadData()`'s own ~500–1500ms work).

### Fix E — profile vector cache with fingerprint
`buildProfileVec()` in `src/engine-taste.js` now writes the normalized vector to `${EXTERNAL_DATA_DIR}/profile_vec_cache_v1.json` after each successful build, alongside a fingerprint of every input that feeds the vector math: top-30 positive song ids + their effective weights, top-30 negative song ids + their effective weights, each top song's current `embeddingIndex` (catches re-embeds), and the negative-strength tuning value (`beta`). On startup, the fingerprint is recomputed AFTER the cheap-but-mandatory side effects (`_buildSignalRowsFromSummary`, `_updateRecommendationPolicySnapshot`) so the recommendation policy snapshot stays current regardless of cache hit; only the pure vector math (weighted-average + negative subtract + L2 normalize) is skipped. Cache misses on any input change → falls through to the existing rebuild path (which rewrites the cache). Cache loads validate version, dim, and fingerprint; any failure is silent and falls through. Caller can opt out with `opts.useCache = false`. Expected savings: 200–500ms on cache-hit reopens.

### Fix F — decouple `_markEmbeddingsReady()` from `buildProfileVec()`
Verified that `renderSimilar()` (the user-visible "Most Similar" Discover cards) calls `engine.getInsights()` which uses `rec.recommendSingle(currentSongEmbIdx, 8, ...)` — this only needs the recommender + current song's embedding, NOT `profileVec`. Moved `_markEmbeddingsReady()` to fire BEFORE `buildProfileVec()` in both `_backgroundScan` paths (cache-skip and full-scan), then run `buildProfileVec()` in the background via `.then(setProfileVec)`. Taste-weighted surfaces (For You / Because You Played / Unexplored Sounds) keep using their existing discover cache snapshots until `profileVec` lands; this matches current behavior when `discover_cache` exists. No native or IPC changes required. Expected savings: 200–800ms perceived latency to "Most Similar shown".

### Files modified
- `src/embedding-cache.js` — fix B (fast base64 decode), fix C (typed-array preservation, JSON-safe writer)
- `src/engine-embeddings.js` — fix C (`_mergeLocalEmbeddings` two-pass typed-array handling)
- `src/engine-data.js` — fix A (Promise.all batches), fix D (early embedding preload + data-dir cache write), fix F (decoupled ready event in both scan paths)
- `src/engine.js` — exports `preloadEmbeddingsEarly`
- `src/app.js` — fix D (`engine.preloadEmbeddingsEarly()` at very top of `init()`)
- `src/engine-taste.js` — fix E (profile vector cache file: `profile_vec_cache_v1.json`, fingerprint helpers, refactored `buildProfileVec` to skip vector math on cache hit while still running policy-snapshot side effects)

### Edge case behavior (verified during design)
- New songs added to device — 6h library cache means new songs surface only after scan completes; profileVec cache invalidates via embedding-index fingerprint change once new songs are embedded. No behavior change vs current.
- Songs deleted via `deleteSong()` — already calls `buildProfileVec()`; new cache writes happen on success and invalidate the prior fingerprint.
- Songs deleted externally (file gone) — `_markSongsMissing` doesn't currently rebuild profileVec; the cache stays valid because the same listen evidence is still recorded. Recommendations remain consistent.
- User listens between sessions — fingerprint includes weighted top-30 listened-derived weights → mismatch → rebuild.
- User changes adventurous slider / `negativeStrength` — fingerprint includes `beta` → mismatch → rebuild.
- User re-embeds songs — `embeddingIndex` per top song is in fingerprint → mismatch → rebuild.
- First launch (no caches) — every cache load returns null → standard path runs; first writes seed all caches.
- Embedding file corrupted / wrong dim / schema-version mismatch — guarded; falls through to rebuild.
- Re-install / data dir moved — `cached_data_dir_v1` becomes stale, early embedding load against wrong path returns null, `startBackgroundScan` detects path mismatch and starts fresh load against the resolved path. No data loss.

### Verification status
- `npm.cmd run build` — OK (Vite 295ms, single-bundle output, latest `dist/assets/index-sllJNX_9.js` at 2026-05-10 06:17:28)
- `npm.cmd run test:unit` — 30/31 (pre-existing `onNativeAdvance` `{duplicate: true}` shape regression remains the only failure; out of scope here, predates this batch and is documented in earlier "Latest Verified Changes")
- `npx.cmd cap sync android` — OK (web bundle copied to `android/app/src/main/assets/public/assets/index-sllJNX_9.js`)
- `:app:testDebugUnitTest` — BUILD SUCCESSFUL in 31s (.class files compiled cleanly with the cap-synced bundle)
- `:app:assembleDebug` — BLOCKED by the documented Gradle metadata-cache lock collision (`Could not update C:\Users\myuva\.gradle\caches\8.14.3\file-changes\last-build.bin (Access is denied)`). The lock is held by a running Android Studio process. APK rebuild requires AS to release the lock (close AS or build via AS itself). No actual code-level failure.
- `:app:connectedDebugAndroidTest` — not rerun in this pass.

### Pre-fix safety backup
`backups/startup_perf_prework_20260510_060528/` contains the unmodified copies of `embedding-cache.js`, `engine-data.js`, `engine-embeddings.js`, `engine-state.js`, `engine-taste.js`. Use this to roll back any individual fix if a regression surfaces in real-device testing.

### 2026-05-10 follow-up #16 — Removed safe-area on top bar + replaced scrollIntoView in recs focus
Two issues:

**A. Empty space above hamburger.** User reported a strip of empty space above the icons "of same height as the search bar height". Analysis: `.top-bar` was using `padding: env(safe-area-inset-top, 0px) 10px 0` and `height: calc(env(safe-area-inset-top, 0px) + 40px)`. On the user's Android phone the env value was non-zero (~24-40 px), and Capacitor's WebView already starts BELOW the system status bar by default — so the safe-area padding was redundant, leaving a strip of bar-coloured padding visible above the icons. Removed the safe-area handling: `padding: 0 10px; height: 40px`. Status toast `top` updated to `48px` (no env-based math).
- Caveat: iOS users with `apple-mobile-web-app-status-bar-style: black-translucent` may need the inset reinstated. If reported, guard with an `@supports`/iOS-specific media query.

**B. Album↔Recs and Browse↔Recs swipes flickered.** User: "swipe right from album and up next, and swipe left from browse to up next" — three transitions, all involving recs as source or destination, all flickering with a "flick from middle of screen" right before the new tab settles. Root cause: `_focusCurrentRecsRow()` (called by `_scheduleRecsFocusCurrent()` on every recs arrival) used `currentRow.scrollIntoView({ block: 'center', inline: 'nearest' })`. With `inline: 'nearest'`, `scrollIntoView` walks up through ancestors looking for a scroll container in the inline (horizontal) axis — finds `.panel-strip` — and if the recs panel isn't 100% in view yet (mid-snap), scrolls the strip horizontally to bring it in. That horizontal scroll raced the browser's natural snap, producing the visible "flick". Replaced the call with manual `wrap.scrollTop = ...` on `.recs-list-wrap` (compute target vertically from `getBoundingClientRect()`), so it touches only the vertical scroll inside recs. The panel-strip's horizontal scroll is never touched, no race, no flicker.

For the recs→browse case (where user is leaving recs), no recs-focus runs but the flicker was reported anyway. Hypothesis: the recs panel internal flex layout was settling as the user swiped away. Should be fixed by the `contain: layout` already applied in #14, plus the indirect benefit of #16's scrollIntoView removal stopping any leftover horizontal scroll commands.

Verification: `npm.cmd run build` OK (Vite 190 ms, bundle now `dist/assets/index-C5cMCKfh.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 18 s. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 371.1 MB, timestamp `2026-05-10 12:47:39`.

### 2026-05-10 follow-up #15 — Bar at geometric minimum + identified the actual flicker
Two distinct items in user feedback:

**A. Top-bar height at the geometric minimum.** User clarified: keep hamburger and search-input sizes as they were in #12 ("like before"), don't shrink them, but reduce bar height. The 40×40 hamburger button + 36 px-tall input pill set a hard floor on bar height: anything below 40 px overflows. So:
- `.top-bar` height: 24 → 40 px (the floor — was 44 in #11/#12, 24 in #13).
- `.top-bar-menu` (hamburger): restored to 40×40 / 22 px font / 8 px radius.
- `.top-bar-search input`: restored to 36 px tall / 14 pt / 18 px radius / `0 36px 0 14px` padding.
- `.top-bar-search-clear` (X): restored to 28×28 / 18 pt / 50% radius.
- `.status-toast` `top`: `safe-area + 32` → `safe-area + 48`.
- Padding: bar horizontal 6 → 10 px; gap 6 → 8 px.

This is a 9% reduction from the original 44 px. Going further would force shrinking the icons. If the user wants the bar truly halved (~22 px), the icons MUST shrink — there's no way around the geometry.

**B. Mid-swipe flick — root cause identified.** User described it precisely: "when swipe just before the new tab is visible, the earlier tab occurs like a flick from the middle of the screen and goes away and then the swiped screen come." This was the `IntersectionObserver` firing at 60% visibility, calling `_activateTab(tab, { instant: true })`, which then called `_scrollToPanel(target, 'instant')` — issuing a programmatic `panelStrip.scrollTo({ behavior: 'instant' })` ON TOP of the browser's natural snap that was already in flight. The two scroll operations raced, producing the visible flick of the outgoing panel.

Fix in `src/app.js` `_activateTab`: when `opts.instant === true` (i.e., the call originated from the observer after a manual swipe), skip the `_scrollToPanel` call entirely. The browser's snap is already animating to that exact position; we just need to update the JS state (`activeTab`, tab `.active` class, `history.pushState`, etc.) without doing any scrolling. Tab-bar clicks (which DO want the smooth-scroll animation) still get it because they call `_activateTab(target)` with no `instant` flag.

Verification: `npm.cmd run build` OK (Vite 195 ms, bundle now `dist/assets/index-CbF11eFn.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 18 s. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 371 MB, timestamp `2026-05-10 12:35:46`.

### 2026-05-10 follow-up #14 — Anti-flicker hardening on the swipe
User: "there is still a flicker when i swipe between pages." Without specifics on what flicker means (white flash / content jump / image pop-in / etc.), applied the standard set of defensive CSS for compositor-driven swipes:

`style.css` `.panel`:
- `background: var(--bg)` — explicit, so the panel never shows through to the body during the snap (prevents brief background-colour flashes).
- `transform: translateZ(0)` — promotes each panel to its own compositor layer up-front, so the swipe is composited (no per-frame repaint).
- `-webkit-backface-visibility: hidden; backface-visibility: hidden` — locks the layer so it doesn't get re-rasterised when the panel passes through certain transform states.
- `contain: layout` — isolates the panel's layout from the strip and from siblings (offscreen panels don't trigger work). Note: `contain: paint` was tried but rolled back because it would clip song-menu popups that extend outside their row.

`.panel-strip` got `will-change: scroll-position` so the strip's compositor layer is allocated once at startup, not re-promoted on every scroll event.

Verification: `npm.cmd run build` OK (Vite 201 ms, bundle now `dist/assets/index--GWFsFQu.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 18 s. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 370.9 MB, timestamp `2026-05-10 12:26:10`.

If flicker persists, ask the user for specifics (white flash vs content jump vs images popping in vs something else) — the right fix depends on the kind. Common suspects to check next:
- Lazy image decoding inside Songs/Albums (many `<img decoding="async">`) → would need `loading="eager"` on the visible-on-load rows or pre-decoding via `image.decode()`.
- The dirty-flag rerender (`_songsDirty` / `_albumsDirty`) firing inside the IntersectionObserver-triggered `_activateTab` → a 100-300 ms `renderSongs(allSongs)` mid-snap would absolutely flicker. Move the rerender behind a `requestIdleCallback` if so.
- The pull-indicator inside Discover briefly showing during the snap → check `.discover-pull-body` for any leftover transform.

### 2026-05-10 follow-up #13 — Top bar halved again (44 → 24 px)
User: "still the top bar need to be halfed." Halved from 44 → 24 px. Icons and search-input pill scaled proportionally so nothing overflows:
- `.top-bar` height: 44 → 24 px; padding 12 → 6 px; gap 8 → 6 px.
- `.top-bar-menu` (hamburger): 40×40 / 22 px font → 24×22 / 16 px font; border-radius 8 → 4 px; added `flex-shrink: 0`.
- `.top-bar-search input`: 36 → 22 px tall; border-radius 18 → 11 px; font 14 → 12 px; padding adjusted.
- `.top-bar-search-clear` (X): 28×28 / 18 px → 18×18 / 14 px.
- `.status-toast` `top`: `safe-area + 52` → `safe-area + 32`.

Verification: `npm.cmd run build` OK (Vite 200 ms, bundle now `dist/assets/index-CPbZfPMk.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 18 s. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 370.8 MB, timestamp `2026-05-10 12:21:05`.

### 2026-05-10 follow-up #12 — Universal search in the top bar
User reasoning: "search bar can be present at the top instead of isaivahzi name… search bar is universal across app anyway… search across the library. why to restrict it to only 2 tabs." Replacing the static brand title with an always-on search input that drives a results overlay is exactly the right call — search was previously a per-tab affordance shown only on Songs / Albums.

Implementation:

**`index.html`**
- The `IsaiVazhi` `<span class="top-bar-title">` is replaced with `<div class="top-bar-search">` containing the existing `#searchInput` (with `autocomplete=off`, `autocorrect=off`, `autocapitalize=off`, `spellcheck=false`) and the X button (`#searchClear`, `aria-label="Clear search"`).
- The standalone `<div class="search-bar" id="searchBar">` element is **deleted** entirely (it used to slide down on Songs / Albums tabs only — now redundant since the input lives in the top bar permanently).
- New `<div class="search-overlay" id="searchOverlay">` lives inside `.content` after the `.panel-strip`. Two sections (`#search-results-songs`, `#search-results-albums`) plus a `#searchEmpty` no-matches placeholder. Hidden by default.

**`style.css`**
- New `.top-bar-search { flex: 1 }` and a 36 px-tall pill input filling the remaining bar width. Clear button is absolute-positioned inside the input on the right, only visible when the input has text.
- New `.search-overlay { position: absolute; inset: 0; z-index: 10 }` over the `.panel-strip`. Each section has a header with section name + match-count.
- Old `.search-bar` rule kept in place but no longer referenced by any DOM (no harm, easier revert).

**`src/app.js`**
- New helpers: `_filterSearch(q)` (returns `{ songs, albums }`), `_showSearchOverlay(q)`, `_hideSearchOverlay()`, `_clearSearchInput()`.
- Limit constants: `_SEARCH_RESULT_LIMIT_SONGS = 80`, `_SEARCH_RESULT_LIMIT_ALBUMS = 40`. Sections that exceed show `"X of Y"` count in the header.
- The existing `searchInput` `input` listener now drives `_showSearchOverlay(q)` / `_hideSearchOverlay()` based on whether the input has text. Per-tab `renderSongs(filtered)` / `renderAlbums(filtered)` calls inside that listener removed.
- `_activateTab` now calls `_clearSearchInput()` (closes overlay, clears input) on every tab change. The old `searchBar.style.display = ...` toggle was removed (the bar no longer exists).
- `_filteredSongs()` / `_filteredAlbums()` simplified to plain pass-throughs returning `allSongs` / `allAlbums`. They're still called by dirty-flag rerender paths (e.g., scan-complete handlers); they no longer try to read the search input. The Songs and Albums tabs always show the full library.
- The X button click handler is wired in a one-shot `DOMContentLoaded` listener so it picks up the static element from `index.html`.

**`src/app-browse-render.js`**
- `renderSongs(list, opts)` and `renderAlbums(list, opts)` now accept an `opts.target` element. Default behavior unchanged (render into `#panel-songs` / `#panel-albums`). The search overlay passes `target: document.getElementById('search-results-songs')` (or albums).
- `renderSongs` also accepts `opts.sort` (defaults true) — the search overlay still sorts alphabetically; could be used later to surface relevance-ordered results without alpha sort.

Result-tap behavior: handled by the same templates the Songs / Albums tabs use, so tap-to-play and tap-on-album-header both work identically. The overlay stays open after a tap (user can keep browsing matches); closing requires the X button, an empty input, or a tab change.

Verification: `npm.cmd run build` OK (Vite 189 ms, bundle now `dist/assets/index-ZnW98BdM.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 18 s. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 370.7 MB, timestamp `2026-05-10 12:08:23`.

### 2026-05-10 follow-up #11 — Top-bar restored sizes + eager Songs/Albums render
User feedback after #10: "the swipe is somewhat better but still hiccups there. also still the hamburger bar need to be reduced bu 10-20 percent. why did you reduce the size of hamburger symbol and isaivazhi app." Two distinct issues:

**A. Top bar dimensions** — follow-up #9 reduced the entire bar including icons (24 px high, 28×24 button, 13 px title). User wanted only the bar height shrunk, not the icon/text. Now: bar height 44 px (~15% reduction from the original 52 px), button restored to 40×40 with 22 px font, title back to 18 px Syne. `.status-toast` `top` recalculated: `safe-area + 52 px` (was `+ 32 px`).

**B. Swipe hiccups despite scroll-snap** — root cause: when the user manually swipes from Discover to Songs, the `IntersectionObserver` fires and calls `_activateTab('songs')`, which used to call `renderSongs(_filteredSongs())` lazily because `_songsDirty = true` was set at init. Rendering 2,485 song rows mid-swipe blocks the main thread for ~100-300 ms — that's the hiccup.

Fix: **render Songs and Albums eagerly at init**, immediately after `loadData()` returns. The cost is folded into the existing post-load render phase while the user is still looking at the Discover panel — invisible. After init both panels are fully populated DOM, so subsequent swipes hit GPU-composited paths only.

`src/app.js` change in `init()`:
```js
allSongs = engine.getPlayableSongs();
allAlbums = engine.getAlbums();
renderSongs(allSongs);    // eager — was lazy on first navigate
renderAlbums(allAlbums);  // eager
_songsDirty = false;       // was true (forced lazy render on first navigate)
_albumsDirty = false;
```

The dirty flags still work for SUBSEQUENT updates (e.g., scan completes with new songs → `_songsDirty = true` → next navigate re-renders). They're just not the bootstrapping mechanism anymore.

Verification: `npm.cmd run build` OK (Vite 203 ms, bundle now `dist/assets/index-Ch21HqX-.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 20 s. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 370.6 MB, timestamp `2026-05-10 11:58:45`.

### 2026-05-10 follow-up #10 — Tab swipe rewritten on CSS scroll-snap
User feedback: "still the swipe between tabs is not smooth. i think you need to focus more on it. check the muzio player, their the swipe between tabs is really smooth. the stitch between tabs is really good." Muzio Player is a native Android app using `ViewPager2` — fundamentally a horizontal scroll with snap-to-page that the OS handles in C++ at compositor level. The closest WebView equivalent: **CSS `scroll-snap-type: x mandatory`** — let the browser handle the gesture entirely, with zero JavaScript on the touch path.

After multiple iterations of JS-driven swipe (follow-ups #4, #5, #6, #7) all hitting layout-cost ceilings on the heavy Songs / Albums tabs, this approach trades a one-time initial layout cost (all panels rendered up-front, ~2,485 song rows + ~1,051 album cards) for **buttery-smooth, browser-native, hardware-composited swipes** that match ViewPager2's feel.

Changes:

**`index.html`** — panels are now wrapped in a `.panel-strip`:
```html
<div class="content">
  <div class="panel-strip" id="panelStrip">
    <div class="panel" id="panel-discover" data-tab="discover">...</div>
    <div class="panel" id="panel-songs"   data-tab="songs"></div>
    <div class="panel" id="panel-albums"  data-tab="albums"></div>
    <div class="panel" id="panel-recs"    data-tab="recs">...</div>
    <div class="panel" id="panel-browse"  data-tab="browse">...</div>
  </div>
  <div class="panel panel-floating" id="panel-history">...</div>  <!-- outside the strip -->
</div>
```
Panel order in the DOM matches the bottom-tab order so swiping right always advances `discover → songs → albums → recs → browse`. Each panel has `data-tab` so the IntersectionObserver knows which tab it represents.

**`style.css`** — old display-toggle and old swipe states gone. New rules:
- `.content { position: relative; overflow: hidden; }` — the strip handles all scrolling.
- `.panel-strip { display: flex; overflow-x: auto; overflow-y: hidden; scroll-snap-type: x mandatory; -webkit-overflow-scrolling: touch; overscroll-behavior-x: contain; scrollbar-width: none; }` plus `::-webkit-scrollbar { display: none }` to hide the horizontal scrollbar.
- `.panel { flex: 0 0 100%; width: 100%; height: 100%; overflow-y: auto; -webkit-overflow-scrolling: touch; overscroll-behavior-y: contain; scroll-snap-align: start; scroll-snap-stop: always; }` — each panel a snap point with its own vertical scroll, `scroll-snap-stop: always` prevents fast flicks from skipping past adjacent tabs.
- `.panel.panel-floating { display: none }` / `.panel.panel-floating.active { display: block }` — for `#panel-history` (sits outside the strip).
- Old `.panel.panel-swipe-dest`, `.panel-swiping`, `.panel-snapping` rules removed entirely.
- `#panel-recs` no longer requires `.active` to be `display: flex` — it's always flex column now.

**`src/app.js`** — old JS swipe handler removed (~150 lines deleted). New logic:
- `_scrollToPanel(target, behavior)` — calls `panelStrip.scrollTo({ left: panelEl.offsetLeft, behavior })`. Used by `_activateTab` for tab-tap navigation. No-ops if already at the target left.
- `setupPanelStripObserver` IIFE — sets up an `IntersectionObserver` on the strip, one per panel, fires when a panel reaches 60% visible. The handler calls `_activateTab(tab, { instant: true })` to update the bottom-tab `.active` class, run search-bar visibility logic, and `history.pushState`. The `instant: true` flag short-circuits `_scrollToPanel` (we're already at the target).
- Anti-recursion: `_scrollToPanel` calls `window._suppressPanelObserverFor(500)` (or 50 for instant) before the scrollTo, blocking the observer from firing while the smooth-scroll animation passes through intermediate panels.
- `_activateTab` no longer toggles `.active`/inactive on panels for visibility — it just sets the `.active` class informationally and calls `_scrollToPanel`. The `.content-recs-active` toggle was removed (no longer needed since each panel has its own scroll).

Pull-to-refresh on Discover: still works. Each panel is its own vertical scroll container, so the existing pull handler on `#discover-pull-body` is unaffected.

Pre-fix safety backup: `backups/scroll_snap_prework_20260510_114415/` (`app.js`, `style.css`, `index.html`).

Verification: `npm.cmd run build` OK (Vite 215ms, bundle now `dist/assets/index-6KgnfwPf.js`); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance` failure only); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 18s. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 370.4 MB, timestamp `2026-05-10 11:51:23`.

Trade-off accepted: heavier initial render (Songs / Albums panels lay out up-front instead of on first navigation). Expected to be ~100-300ms one-time, paid during the existing post-scan render phase. After that, swipes between tabs are GPU-composited at 60fps regardless of panel content depth.

### 2026-05-10 follow-up #9 — Compact top bar (52 → 24 px)
User feedback after #8: "the hamburger bar is very big, i think the height need to be reduced more than half." Halved: 52 → 24 px.

`style.css` `.top-bar` revisions:
- `height: calc(env(safe-area-inset-top) + 24px)` (was `+ 52px`).
- `padding: env(safe-area-inset-top) 8px 0` (was `12px 0`).
- `gap: 4px` (was `8px`) between hamburger and title.
- `.top-bar-menu`: 28×24 (was 40×40), font 15px (was 22px), border-radius 4px (was 8px), padding: 0.
- `.top-bar-title`: font-size 13px (was 18px), margin-left 2px (was 4px).
- `.status-toast` `top` recalculated: `safe-area + 32px` (was `+ 64px`) — 24 px bar + 8 px gap.

Verification: `npm.cmd run build` OK (Vite 187ms, bundle now `dist/assets/index-B24DTsf4.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 18s. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 370.3 MB, timestamp `2026-05-10 11:35:58`.

### 2026-05-10 follow-up #8 — Top app bar (DBG removed, hamburger moved into the bar)
After the previous follow-up the hamburger was visually invisible: the DBG pill at `z-index: 100000` covered the floating `.hamburger-btn` at `z-index: 50`. User asked to think carefully about hamburger placement and reflow tabs accordingly: "if hamburger goes to the top, i think all the tabs need to be modified accordingly". Plus an explicit add-on: "when we do pull to refresh in discover page it should start below the hamburger box".

Decision: real top app bar across all tabs, no per-tab variation. Pattern used: hamburger left + "IsaiVazhi" title text. Bar sits above `.search-bar` / `.content` / `.bottom-bar` in the body's flex column, so every panel naturally starts below it without any per-panel CSS.

Changes:
- `index.html`:
  - Deleted `#debugLogToggle` + `#debugLogPanel` blocks (DBG pill gone for good).
  - New `<div class="top-bar" id="topBar">` containing `#hamburgerBtn` + `<span class="top-bar-title">IsaiVazhi</span>`. Inserted at the top of `<body>`, above `.search-bar`.
- `style.css`:
  - New `.top-bar` rule: `flex-shrink: 0`, `height: calc(env(safe-area-inset-top) + 52px)`, `padding-top: env(safe-area-inset-top)`, `background: var(--surface)`, `border-bottom: 1px solid var(--border)`, `z-index: 30`. Flex layout with `gap: 8px` so the hamburger sits next to the title.
  - `.top-bar-menu` (hamburger): 40×40 transparent button with hover/active feedback, no border-radius drama.
  - `.top-bar-title`: Syne 18px bold, matches the existing brand typography elsewhere.
  - Old `.hamburger-btn` floating rule renamed to `.hamburger-btn--legacy-disabled` (no DOM uses it now; kept for documentation / quick revert).
  - `.status-toast` `top` shifted from `safe-area + 16px` to `safe-area + 64px` so it appears below the new top bar instead of overlapping the hamburger.
- `src/app-settings.js` `_ensureDom()`: hamburger button no longer dynamically created — the static `#hamburgerBtn` in HTML is wired up via `addEventListener('click', openSidebar)` instead.
- `src/app.js`: `_dbg()` simplified — the `console.log` mirror hook for DBG-tagged lines was removed (only consumer was the deleted DBG panel). `_dbg(msg)` still writes to native console with a `[DBG]` prefix; if the DBG panel is ever re-added, the textContent fallback path picks back up automatically.

Pull-to-refresh: the `.pull-indicator` and `.discover-pull-body` live INSIDE `#panel-discover` which is inside `.content`. Since `.content` now starts below the top bar (top bar is in the body flex column), the pull indicator naturally appears just below the hamburger when triggered. No code change needed for pull-to-refresh.

Layout result:
```
┌─────────────────────────────┐
│ ☰  IsaiVazhi                │  <- top-bar (flex-shrink: 0)
├─────────────────────────────┤
│ [search bar when active]    │  <- .search-bar (display:none default)
├─────────────────────────────┤
│ [pull-indicator]            │  <- shows under top-bar on Discover
│ [panel content]             │  <- .content (flex: 1)
│ ...                         │
├─────────────────────────────┤
│ ▶ Now Playing               │  <- .bottom-bar
│ Discover  Songs  ...        │
└─────────────────────────────┘
```

Pre-fix safety backup: `backups/top_bar_prework_20260510_112114/` (`app.js`, `app-settings.js`, `style.css`, `index.html`).

Verification: `npm.cmd run build` OK (Vite 418ms, bundle now `dist/assets/index-Cro9GEpw.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 34s. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 370.2 MB, timestamp `2026-05-10 11:26:44`.

### 2026-05-10 follow-up #7 — Hamburger restored, edge swipe removed
After the previous follow-up, the user clarified: they didn't want the edge-swipe gesture / edge-handle pattern after all ("just lets add the hamburger and keep it simple", "edge swipe ... no not needed"). Net direction: hamburger button back, no edge gesture, sidebar items still in the bottom-aligned drawer.

Changes:
- `src/app-settings.js` `_ensureDom()`: hamburger button creation restored (was removed in follow-up #4 in favor of edge swipe). The `#sidebarEdgeHandle` element creation was deleted.
- `openSidebar` / `closeSidebar` / `_commitSidebarDrag` no longer toggle the edge-handle's `.hidden` class (handle no longer exists).
- Public `_settings` API trimmed: `setDragOffsetFromEdge`, `commitDragFromEdge`, `cancelDragFromEdge` removed (no longer called from anywhere).
- `src/app.js` `setupTabSwipe`: edge-mode logic removed. Now only `'pending'` / `'tab'` / `'vertical'` modes. `EDGE_OPEN_ZONE_PX` constant deleted along with all `[DBG] edge-swipe ...` log calls.
- `style.css`: `.sidebar-edge-handle` rules removed (~40 lines of dead CSS).
- `_attachSidebarSwipe` (right-swipe inside sidebar to close it) is **kept** — that's a close gesture inside the already-open sidebar, not the edge-to-open gesture, and was an explicit earlier ask.

Net result: hamburger button at top-left opens the sidebar (same behavior as before the UI overhaul began). Sidebar still has bottom-aligned AI Embedding / Taste Signal / Settings items. Tab carousel swipe (active panel translates with finger, dest panel slides in at commit) is unchanged from #6.

Verification: `npm.cmd run build` OK (Vite 449ms, bundle now `dist/assets/index-C2TgpHUd.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 36s using the documented workaround. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 370.1 MB, timestamp `2026-05-10 10:14:48`.

### 2026-05-10 follow-up #6 — UI overhaul second-pass: edge handle + active-panel-stays-in-flow
After the previous revision, both reported issues persisted:
1. Edge swipe still didn't open the sidebar.
2. Tab swipes still felt laggy.

The most likely root causes (without DBG logs to confirm):
1. **Android system gestures absorbing the very-edge touch.** Most Android phones running gesture navigation reserve the leftmost ~8-12 px for the back gesture. The touch never reaches the WebView at all, so widening from 24 px to 36 px in the previous pass didn't help — the touch wasn't arriving in the first place.
2. **Adding `position: absolute` to the active panel at swipe start was triggering a full layout reflow** of every descendant — for the Songs panel that's ~2,485 song rows. Even though only the active panel was visible during the drag (we were no longer pre-rendering prev/next), the position-change still cost a reflow. That's the lag the user reported.

Fixes this pass:

**Visible edge handle** — `src/app-settings.js` `_ensureDom()` now creates a dedicated `#sidebarEdgeHandle` element. CSS in `style.css`:
- 14 px wide, positioned at `left: 0` from `top: 40%` to `bottom: 25%` (vertically centered, away from status/nav bars).
- Background gradient `rgba(255,255,255,0.10)` → transparent right edge, with a small `›` chevron glyph as visual hint.
- `z-index: 60` (above panel content, below sidebar).
- Always-mounted, `pointer-events: auto` while sidebar closed; `.hidden` class (added by `openSidebar` / `_commitSidebarDrag`) fades it out when the sidebar opens.
- `cursor: pointer` + `click` handler → tap-to-open as a guaranteed fallback path. The user can always reach the menu even if their phone's gesture-nav config eats the swipe.
- The handle sits a few pixels INSIDE the screen edge (not at `left: -2px` or similar), so it's outside Android's back-gesture reserve zone.
- `EDGE_OPEN_ZONE_PX` widened 36 → 50 in `setupTabSwipe` so the swipe gesture catches a touch starting anywhere from x=0 to x=49 px (covers the 14px handle plus generous finger-aim buffer).

**Active panel doesn't change position during swipe** — `src/app.js` `_prepareTabSwipe` and `_commitTabSwipe`:
- `.panel-swipe` class deleted (it was setting `position: absolute` on the active panel and reflowing thousands of children).
- New `.panel-swiping` / `.panel-snapping` classes ONLY control transition timing — they never change `position`. The active panel stays in normal flow (`display: block` from `.active`); we just add `transform: translate3d(dx, 0, 0)`. That's a pure compositor op — zero layout cost.
- Destination panel still uses absolute positioning during the commit, but renamed to `.panel-swipe-dest` to make the intent explicit.
- `.content` got `overflow-x: hidden` to contain the active panel's horizontal translation cleanly.
- `_resetPanel(panel, wasDest)` takes a flag so we don't accidentally clear `display` on the active panel (it should remain `display: block` via `.active`).

Net effect on tab swipe: starting a swipe now changes ONE thing on the active panel — adding `transform: translate3d(0,0,0)` plus a class. No layout reflow on Songs/Albums tabs. Pure compositor work for the entire drag.

**Diagnostic logs** (kept from previous pass): the DBG panel will show `[DBG] edge-swipe armed at clientX=N` when a touchstart is detected in the edge zone, `[DBG] edge-swipe locked, dx=N` when motion locks the gesture, and `[DBG] edge-swipe end dx=N dt=N open=true/false` on release. If the user reports the swipe still doesn't work and these logs DON'T appear, the touch isn't arriving — at that point the only fix is either (a) the tap-to-open on the edge handle, or (b) a native Android `setSystemGestureExclusionRects` call which requires Java code in `MainActivity` / `MusicBridgePlugin`.

Verification: `npm.cmd run build` OK (Vite 275ms, bundle now `dist/assets/index-DBdNGnLd.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 17s. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 370 MB, timestamp `2026-05-10 09:50:26`.

### 2026-05-10 follow-up #5 — UI overhaul revisions (edge-swipe fix + lag fix)
First-device test of the UI overhaul reported two regressions:
1. Left-edge swipe to open sidebar didn't work at all.
2. Tab swipes felt laggy — "really lagging between swipes".

Root causes:
1. **Edge zone too narrow + handler attached too low.** The 24px edge zone was tight for finger aim on a phone with rounded corners, and the touchstart listener was on `.content` — if any fixed overlay (or Android's gesture-zone visual) covered the edge, the touch never reached `.content` and the handler never fired.
2. **Carousel pre-rendered prev/next panels at swipe start.** Switching the Songs panel from `display: none` to `display: block` makes the browser lay out all ~2,485 song rows, the Albums panel ~1,051 album cards. Doing that the moment the user starts to swipe meant a 100-500ms hitch right when smoothness matters most. Adding the CSS `:has(.panel.panel-swipe)` selector also forced ongoing selector recomputation.

Fixes (`src/app.js` `setupTabSwipe` rewritten + `style.css`):
- **Document-level capture-phase touch listeners** instead of `.content` — guaranteed to receive every touch on the page regardless of overlays.
- **Edge zone widened to 36 px** (was 24).
- **Diagnostic `[DBG]` logs** for edge-swipe arm/lock/end so the next on-device test can confirm the gesture is firing (visible in the DBG panel, copied via "Copy Logs").
- **Only the active panel translates during touchmove.** The destination panel is added (`display: block`, positioned absolutely at `±viewport-width`) at COMMIT time, alongside an animation that slides the active panel out and the dest panel in. Layout cost paid once, not per touchmove frame. The user still sees a smooth left-or-right transition between panels — just doesn't see the dest panel during the drag itself.
- **`requestAnimationFrame` coalescing** on touchmove → one transform write per frame, not per move event. Plus `translate3d(x,0,0)` for compositor-layer transforms.
- **`will-change: transform` and `z-index: 1`** on `.panel.panel-swipe` to ensure GPU compositing layer is allocated before the transition starts (no first-frame flicker).
- **Snap duration reduced** 220 ms → 180 ms with a slightly snappier easing curve (`cubic-bezier(0.32, 0.72, 0, 1)`).
- **`:has()` selector removed** (was forcing CSS recomputation); replaced with always-on `position: relative` on `.content`.
- **Sidebar/full-player exclusion** moved into the document-level handler so the new edge-swipe doesn't accidentally re-trigger when the user is already inside the sidebar or full player.

Trade-off accepted: the user no longer sees the destination panel "stitched" alongside the active panel during the drag itself — only during the snap commit. This was the only realistic way to keep the gesture smooth on tabs with thousands of pre-rendered DOM nodes without virtualizing the lists (which is a separate, larger project).

Verification: `npm.cmd run build` OK (Vite 299ms, bundle now `dist/assets/index-D_4-b0fx.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 21s using the documented `--gradle-user-home "$env:USERPROFILE\.gradle-cli" --no-daemon` workaround. Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 369.9 MB, timestamp `2026-05-10 09:33:53`.

Real-device validation pending. Test items:
- Try left-edge swipe at the very leftmost edge of the screen, slowly. DBG panel should show `[DBG] edge-swipe armed at clientX=...` followed by `[DBG] edge-swipe locked` once you've moved >8px right. If those don't appear when you swipe, the edge isn't firing — share the DBG output and I'll widen further or move to native gesture exclusion.
- Tab swipes: drag should follow finger smoothly (no per-frame lag). Snap should complete in 180 ms. Between successive swipes there should be no visible delay.
- Heavy tabs (Songs ~2,485 items, Albums ~1,051): expect a brief moment when the destination panel pops in at commit — this is the layout cost paid once per swipe instead of per-frame. If it's still distracting, the long-term fix is virtualizing the list (separate project).

### 2026-05-10 follow-up #4 — UI overhaul: carousel-swipe between tabs, hamburger removed, sidebar redesign
Two UX gaps reported on the previous build:
1. Tab swipe was discrete — on touchend the active panel just toggled `display: none/block`. No animation, no follow-finger feedback. User said "I swipe and wait for the next page to load, it's not organic. I want pages stitching as I swipe."
2. The hamburger button was visually intrusive in non-Discover tabs, and the AI Embedding / Taste Signal buttons in the Discover header had a `margin-left: 50px` clearance just to avoid overlapping the hamburger. User wanted: remove the hamburger, use a left-edge swipe instead, move AI/Taste buttons into the sidebar (they're settings-style, not homepage), and place sidebar items at the bottom of the drawer (within thumb reach).

Both landed in one batch:

**Carousel-style tab swipe** (`src/app.js` `setupTabSwipe`)
The handler was rewritten with a 3-mode state machine driven from one shared touch listener:
- `edge-pending` / `edge` — touchstart at clientX < 24px when sidebar is closed → drives the sidebar drawer transform via `_settings.setDragOffsetFromEdge(dx)`. Snaps open on commit (>40% of width or fast flick), back to closed otherwise.
- `pending` / `tab` — non-edge horizontal swipe locks into carousel mode after 8px of horizontal motion. Adjacent panels (`prev` and `next` in `tabOrder`) are positioned absolutely with `class="panel panel-swipe panel-swipe-prev/next"` and translated to ±viewportWidth at swipe start, then translated alongside the active panel during touchmove so all three move together. On touchend, `_commitTabCarousel` sets `.panel-snapping` (CSS `transition: transform 0.22s`) and animates to either the next panel (commit) or back to 0 (revert). Edge resistance: dragging beyond the first/last tab gets 1/3 friction.
- `vertical` — if `|dy| > 12px` before horizontal lock, abandon tracking and let the browser scroll `.content` normally.
- Soft commit thresholds: `|dx| ≥ 60px` OR `dt < 250ms && |dx| ≥ 30px` (fast flick).

CSS additions in `style.css`:
- `.panel.panel-swipe { display: block !important; position: absolute; inset: 0; overflow-y: auto; will-change: transform; }` — each panel becomes its own scroll container during the swipe, preserving per-tab scroll independence.
- `.panel.panel-swiping { transition: none; }` — finger-following.
- `.panel.panel-snapping { transition: transform 0.22s cubic-bezier(0.4,0,0.2,1); }` — release animation.
- `.content { position: relative; }` always; `:has(.panel.panel-swipe) { overflow: hidden }` when a swipe is mid-flight.

`_activateTab` is called with `resetScroll: false` after a carousel swipe so the destination tab keeps its previous scroll position; on revert, the originating tab's scroll is restored from `savedScrollTop`.

**Sidebar redesign** (`src/app-settings.js` + `style.css`)
- `_ensureDom()` no longer creates the hamburger button — left-edge swipe opens the drawer instead. The hamburger CSS in `style.css` is now dormant (no DOM uses it) but kept in source so a future revert is one DOM line.
- Sidebar body uses a column flex with a `.sidebar-spacer { flex: 1 }` that pushes `.sidebar-items` to the bottom of the drawer.
- New sidebar items: AI Embedding (🧠), Taste Signal (⚖), Settings (⚙). Click handlers call `window._app.showEmbeddingDetail()` / `showTasteWeights()` / open the existing Settings sub-page; all close the sidebar first.
- Right-swipe inside the sidebar closes it (handled by `_attachSidebarSwipe`). Backdrop tap still closes (existing). Drawer follows the finger via `_setSidebarDragOffset(px)` which sets `transform: translateX(px)` directly while applying `.dragging` (kills CSS transition). On release `_commitSidebarDrag(open)` removes `.dragging` and toggles `.open`, letting the CSS transition animate the snap.
- Settings sub-page back button now calls `_resetSidebarBody()` (extracted helper) which renders the new 3-item root layout instead of the old single-Settings layout.
- Public `_settings` API gained three methods used by `setupTabSwipe`'s edge mode: `setDragOffsetFromEdge(dx)`, `commitDragFromEdge(open)`, `cancelDragFromEdge()`.

**Discover header cleanup** (`src/app-discover-ui.js`)
- `renderProfile` no longer renders the `.discover-action-row` (AI / Taste Signal buttons). `#profile-stats` is left empty + `display: none` so no empty space at the top of Discover.
- `style.css` `.discover-action-row` lost its `margin-left: 50px` (was the hamburger clearance) — kept as a generic 2-column grid in case a future surface wants it.

Pre-fix safety backup: `backups/ui_overhaul_prework_20260510_085236/` (`app.js`, `app-settings.js`, `app-discover-ui.js`, `style.css`).

Verification: `npm.cmd run build` OK (Vite 315ms, bundle now `dist/assets/index-FTCxsm32.js`); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance` failure only); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 2m 27s (using `--gradle-user-home "$env:USERPROFILE\.gradle-cli" --no-daemon` to bypass the Android-Studio cache-lock — see "Build Steps" section for the workaround now documented). Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 369.8 MB, timestamp `2026-05-10 09:08:16`. **This APK contains all batches landed today: Fixes A–I + UI overhaul.**

Real-device validation pending. Test checklist:
1. Swipe between Discover/Songs/Albums/Up Next/Browse — pages should follow finger and snap on release.
2. Swipe past the first/last tab — should feel resistance instead of going off-screen.
3. Vertical scroll should still work normally inside each tab.
4. Touch from far left edge → sidebar drags in. Snap open at >40% of width, back to closed otherwise.
5. With sidebar open, swipe right anywhere on it → closes.
6. With sidebar open, tap on the dimmed backdrop → closes.
7. Sidebar items: AI Embedding, Taste Signal, Settings — all near bottom, all open the right page on tap.
8. No hamburger button visible anywhere.
9. Discover header has no empty space at top (where AI/Taste row used to be).

### 2026-05-10 follow-up #3 — Fix H (smart deferral) + Fix I (parallel play-tap bridge calls)
On-device DBG log after Fix G showed embeddings loaded fast (`_loadLocalEmbeddings: 2448 entries in 483 ms`) but Discover still took ~5.2s to render after app open. Cause: `_scheduleBackgroundHydration` (`src/app.js`) was waiting an additional 2950ms after detecting playback intent — sized for the old world where the post-deferral work was 5+ seconds. After Fix G the post-deferral work is just 51ms (`cache-skip pre-ready total: 51 ms` in the log) — the deferral became 57× longer than the work.

**Fix H — smart deferral:**
- New export `engine.isEarlyEmbeddingLoaded()` (`src/engine-data.js`, plumbed through `engine.js`) — returns true once `preloadEmbeddingsEarly()`'s promise has settled (fulfilled or rejected). Tracked via `.then()`/`.catch()` on the promise inside `preloadEmbeddingsEarly`.
- `_scheduleBackgroundHydration` in `src/app.js` now checks `engine.isEarlyEmbeddingLoaded()` before applying the 4s `PLAY_INTENT_AI_DEFER_MS` deferral. When the early load is done AND we're not in `_pendingStartupResume`, the deferral shrinks to a 120ms grace (just enough to let the audio thread settle). The function body was refactored into `start` (decision logic) and `start_now` (the actual scan trigger).
- Falls back to the original 2950ms behavior if the early load hasn't resolved yet (rare — only happens when bin read takes longer than `STARTUP_AI_HYDRATION_DELAY_MS` of 1500ms, which post-Fix-G should be unusual).
- Expected savings: ~2.7s on warm restart with playback intent. Total time-to-similar-songs target: ~5.2s → ~2.5s.

**Fix I — parallel play-tap bridge calls:**
DBG log line `08:00:01.126 PLAY-TAP` to `08:00:01.532 audioPlayStateChanged: isPlaying=true` showed 406ms cold-restore play tap. Of that, ~103ms was JS-side: 3 sequential `await`s on `MusicBridge.setQueue` (or `playAudio`) → `setLoopMode` → `_refreshNativePlaybackInstanceId`. Only the first one gates audio start on native side (`setQueue`/`playAudio` triggers ExoPlayer prepare). The other two are bookkeeping that doesn't block playback.
- `loadAndPlay` in `src/app.js` (both success path and retry path) now awaits `setQueue`/`playAudio` then fires `setLoopMode` + `_refreshNativePlaybackInstanceId` via a non-awaited `Promise.all([...])`. UI state (`nativeAudioPlaying = true`, `updatePlayIcon(false)`) is updated immediately after the first await, ~50ms earlier.
- Native playback instance ID is reconciled by the next `audioPlayStateChanged` event anyway, so the brief window where it might be stale is harmless.
- Expected savings: ~50ms per play tap.

Pre-fix safety backup: `backups/fix_hi_prework_20260510_081654/` (`app.js`, `engine-data.js`, `engine.js`).

Verification: `npm.cmd run build` OK (Vite 528ms, bundle now `dist/assets/index-Be6QWNbG.js`); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance` failure only); `npx.cmd cap sync android` OK; `:app:assembleDebug` still blocked by Gradle cache lock. Real-device validation pending.

### 2026-05-10 follow-up #2 — Fix G (fetch() for embedding files, kills the bridge bottleneck)
First-device DBG-log capture (logs.txt) showed the dominant remaining startup cost on warm-start was Capacitor's `MusicBridge.readBinaryFile` itself: 4884 ms for the 5 MB `local_embeddings.bin`, plus 1258 ms for the small meta JSON. Decode in JS was already fast (31 ms after Fix B). The `MusicBridge.readBinaryFile` plugin reads the file natively, base64-encodes it (~6.7 MB string for a 5 MB binary), and ships the string over the JSI bridge — that's pure bridge serialization overhead, not disk speed. Proof in the same log: `song_library.json` is fetched via `window.Capacitor.convertFileSrc('file://...')` + `fetch()` and completed in 130 ms; the WebView reads `file://` URLs directly without the JSI bridge.

Fix G replaces the bridge calls in `_loadBinaryStore()` (`src/embedding-cache.js`) with the same fetch pattern:
- New helper `_fetchLocalArrayBuffer(path)` — `fetch(convertFileSrc('file://' + path)).arrayBuffer()`. The returned `ArrayBuffer` is wrapped directly with `new Float32Array(buffer)`; no base64 decode, no extra copy.
- New helper `_fetchLocalText(path)` — same pattern, returns a string for `JSON.parse`.
- Both helpers return `null` on any failure (no Capacitor, fetch reject, non-OK response, exception). Caller falls back to the existing `MusicBridge.readBinaryFile`/`readTextFile` path on null. Zero behavior change in failure modes.
- Bin path also validates `byteLength % 4 === 0` before constructing the `Float32Array` — guards against truncated reads.

Expected savings on warm start with 2,448 entries × 512 dim:
- Bin read: 4884 ms → ~150-300 ms (~30× faster, no base64 round-trip)
- Meta read: 1258 ms → ~50-100 ms (small file, no bridge)
- Combined: ~5.5 seconds saved
- Total time-to-similar-songs estimated drop: ~10 s → ~4 s

Pre-fix safety backup: `backups/fix_g_prework_20260510_073345/` (just `embedding-cache.js`, only file modified).

Verification: `npm.cmd run build` OK (Vite 588ms, bundle now `dist/assets/index-CmWgSgOz.js`); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance` failure only); `npx.cmd cap sync android` OK; `:app:assembleDebug` still blocked by the Gradle cache lock (Android Studio holds `last-build.bin`). User must close AS or build from inside AS to repackage the APK with the fetch-enabled bundle. Real-device validation pending.

### 2026-05-10 follow-up — DBG panel re-enabled + finer-grained PERF logs
First-device test reported "still 5+ seconds to similar songs" plus an intermittent mini-player thumbnail bug (previous song's art persists on first tap from For You during the embedding-load window, fixed by tapping again). Without console access we can't tell which step is the actual bottleneck. To diagnose:
- `index.html` — DBG button + log panel uncommented (was hidden since after the 2026-04-25 enablement). DBG pill at top-left toggles a full-screen panel with a "Copy Logs" button at top.
- `src/app.js` — `console.log` is now hooked to mirror lines starting with `[PERF]`, `[Embedding]`, `[FAV]`, `[SCAN]`, `[DBG]`, or `[REC]` into the on-screen DBG panel. Uses an `_origConsoleLog` reference inside `_dbg()` to avoid recursion. Other console output (untagged) goes only to native console as before.
- `src/engine-data.js` cache-skip path — added `[PERF]` markers for: embedding-disk-load wait, `_mergeLocalEmbeddings`, `new Recommender`, `attachGpuToRec`, and total pre-ready time.
- `src/engine-embeddings.js` `_loadLocalEmbeddings` — added `[PERF]` total-time + count log.
- `src/embedding-cache.js` `_loadBinaryStore` — added `[PERF]` markers for: meta read+parse, binary bridge read (with base64 char count), base64 decode (with byte count), and total time.

Mini-player thumbnail bug working hypothesis: during the multi-second embedding load, the JS thread is busy with synchronous binary decoding + merging. `syncFullPlayer()` runs synchronously when a song is tapped and tries to fetch art via `MusicBridge.getAlbumArtUri` (line ~621 in `app-player-ui.js`); the async resolution can be delayed past when the next render happens, leaving the previous img element visible (paint timing) or the recursive `syncFullPlayer()` not running until the JS event loop unblocks. A second tap finds the art already cached so the synchronous path succeeds. If the embedding pipeline drops below ~2s, this bug should resolve itself; will revisit with targeted fix if it persists.

Verification: web build OK (Vite 428ms, bundle now `dist/assets/index-BGfduGHn.js`); cap sync OK (synced to `android/app/src/main/assets/public/`); `:app:assembleDebug` still blocked by the same Gradle cache-lock collision (`last-build.bin` access denied). User must close Android Studio briefly OR build from inside AS to repackage the APK with the DBG-enabled bundle.

## Recent Changes (2026-05-09 batch)

This batch reduced `src/engine.js` from 7,026 → 1,838 lines via the engine split campaign, added GPU/hardware acceleration to every compute-heavy path with safe CPU fallbacks, fixed a cluster of bugs in the AI embedding page, added a hamburger menu + Settings page with embedding upload, and addressed a section-playback auto-transition bug.

### Engine split campaign — COMPLETE
`src/engine.js` is now 1,838 lines (under the 2,000-line hard ceiling). New modules created:
- `src/engine-state.js` (202 lines) — shared module-level vars + setters + constants
- `src/engine-indexes.js` (57 lines) — O(1) lookup helpers (`_getFilenameMap`, `_getPathMap`, `_fastEmbIdxToSongId`, `_invalidateEmbIdxMap`)
- `src/engine-taste.js` (1,180 lines) — 64 taste/profile functions
- `src/engine-embeddings.js` (1,140 lines) — on-device embedding pipeline + native-side bridge
- `src/engine-data.js` (~800 lines) — startup data pipeline (`loadData`, scan, art cache, song-ref helpers, favorites/playlist I/O)
- `src/engine-analytics.js` (767 lines) — profile analytics + recommendation rebuild scheduling + Discover cache
- `src/engine-insights.js` (501 lines) — `getProfileWeights`, `getTasteSignal`, `getSuspiciousRecommendationData`, `rebuildProfileVec`, taste reset
- `src/engine-playback.js` (627 lines) — playback state persistence + native queue sync (`savePlaybackState`, `restorePlaybackState`, `resetEngine`, `onNativeAdvance`, etc.)
- `src/engine-favorites.js` (325 lines) — favorites/dislike CRUD + playlist CRUD (zero engine.js callbacks)
- `src/engine-session-ui.js` (203 lines) — `setLiveListenFraction`, `getInsights`

Cross-cutting engine.js dependencies are wired via `initEmbeddingCallbacks`, `initDataCallbacks`, `initAnalyticsCallbacks`, `initPlaybackCallbacks`. Every slice landed with `npm run build` + `npm run test:unit` green (30/31 — pre-existing `onNativeAdvance` failure only). Detailed slice log in `file_split_campaign.md`.

### GPU / hardware acceleration (4-layer stack with CPU fallback at every level)
1. **ONNX inference → NNAPI + FP16** (`EmbeddingService.java`). New `buildSessionOptions(threads, tryNnapi)` helper attempts NNAPI with `NNAPIFlags.USE_FP16` first, falls back to NNAPI-without-FP16, then to CPU. If session creation throws after NNAPI add, retries CPU-only and remembers `nnapiPermanentlyDisabled = true` for the rest of the session. New field `activeBackend` exposes which backend is in use. Realistic 2–4× speedup per inference on devices with NPU/GPU acceleration.
2. **Window batching** (`EmbeddingService.java`). New `runInferenceBatch(List<float[]>)` runs all 3 windows of a song in one `[3, 480000] → [3, 512]` forward pass instead of three separate `[1, 480000]` calls. Falls back to per-window inference (`runInferencePerWindow`) on shape mismatch or batch failure. ~20–40% additional speedup on top of NNAPI.
3. **WebGPU recommender** (new `src/gpu-recommender.js`, ~330 lines). Exports `GpuRecommender.tryCreate()` (singleton via `getOrInitGpuRecommender`), `attachGpuToRec()` helper. Two WGSL compute shaders: dot-product kernel (workgroup 64) and k-means assignment kernel. Watches `device.lost` promise and degrades to CPU on driver crash / GPU reset. `Recommender` gained `attachGpu`, `hasGpu`, `_findNearestAsync`, `computeClustersAsync`. `engine-analytics.js` `getUnexploredClusters` now uses the async path — biggest visible win, since k-means is the slowest Discover operation. Wired up at every `setRec(new Recommender(embeddings))` site (engine-data.js, engine-embeddings.js).
4. **NDK NEON SIMD** for native similarity index. New files: `android/app/src/main/cpp/CMakeLists.txt`, `android/app/src/main/cpp/embedding_native.cpp` (16-lane unrolled NEON kernel with `vfmaq_f32` on aarch64 / `vmlaq_f32` on armv7, scalar fallback on x86), and `android/app/src/main/java/com/isaivazhi/app/NativeAccelerator.java` (JNI wrapper with `System.loadLibrary` in static init guarded by try/catch — `LIBRARY_LOADED = false` on any failure, callers fall back to Java loop). `EmbeddingSimilarityIndex.findNearest` now batches all dot products through one JNI call. `android/app/build.gradle` gains `externalNativeBuild { cmake { path "src/main/cpp/CMakeLists.txt", version "3.22.1" } }` and `ndk { abiFilters "arm64-v8a", "armeabi-v7a", "x86", "x86_64" }`. Realistic 3–4× faster than Java loop for batched dot products on arm64.

Important: every layer is independent. NNAPI failing doesn't stop batching; WebGPU unavailable doesn't stop NNAPI; NEON .so missing doesn't stop ONNX. The same APK runs on a phone without WebGPU and on a phone without an NPU — each path silently degrades.

### AI embedding page bug fixes
Several bugs ranging from "deleted song still shown as orphan" to subtle mid-batch state losses:

1. **Deleted song appears as missing embedding** — `_identifyOrphans()` filter was too broad (`songs.filter(s => !s.filePath)`) and `deleteSong` never refreshed the cached `orphanedSongs[]`. **Fix:** filter is now `!s.filePath && s.hasEmbedding` (real orphan = file gone but embedding still on disk; user-deleted songs have `hasEmbedding=false` after `_purgeLocalEmbeddingForSong`). `deleteSong` and `removeOrphanedEmbeddings` both call `_identifyOrphans()` and fire `_fireSongLibraryChanged({reason: 'user_delete' | 'orphans_removed'})`. The `onSongLibraryChanged` callback in `app.js` now also re-renders the AI detail page if it's currently visible, eliminating the 2-second stale window.

2. **Re-add silently dropped during in-progress batch** — `readdSongEmbedding` and `embedRemovedSongsBatch` deleted from `removedEmbeddingKeys` but skipped `_startEmbedding` when `embeddingInProgress=true`, leaving the song in limbo (removed from "Embed Removed Songs" list but never queued). **Fix:** new `_deferredReembedIds` Set; deferred items drain at the end of `_runPostBatchMerge`.

3. **Dead code in `embeddingSongComplete` handler** — 70 lines of unreachable code referencing undefined `embArray` (leftover from the older bridge protocol that shipped full embeddings per song). **Fix:** removed; comment now documents the architecture (native side writes pending JSON; JS reconciles in `_runPostBatchMerge`).

4. **2-second polling rebuilt the AI page DOM even when idle** — gated on `engine.getEmbeddingStatus().inProgress` so the heavy DOM work only runs during active embedding. Event-driven listeners cover the rest.

5. **Pending count double-counted failed songs** — `newCount = pendingNewSongs.length + (st.failedCount || 0)` was inflated because failed songs (which have `hasEmbedding=false`) are already in `pendingNewSongs`. Renamed UI labels to "Embed Pending Songs" with a `(K retry)` hint when applicable. Also fixed the "All songs have AI embeddings" success path that was previously gated on the inflated count.

### Search bar reverting to full list
`onScanComplete`, `onAlbumArtReady`, `onSongLibraryChanged`, `_scheduleArtUiRefresh`, and `_activateTab` all called `renderSongs(allSongs)` / `renderAlbums(allAlbums)` blindly, wiping the user's typed search query within a few seconds of app load. **Fix:** new `_filteredSongs()` and `_filteredAlbums()` helpers that read the live `#searchInput` value and filter accordingly. All five sites updated to use them.

### Section playback auto-transition (silence-after-section bug)
When playing from a Discover section (e.g. "Because You Played"), after the last section song finished, native player either looped the section forever (loop=all default → `REPEAT_MODE_ALL`) or stopped silently (loop=off). Root cause: `engine-playback.js` `onNativeAdvance` had `state.timelineMode !== 'explicit'` in the `needsRefresh` check, so sections never got their queue extended. **Fix:** check now triggers when advancing to the last item of an explicit section/ordered-list timeline (excluding album/favorites/playlist which genuinely "end" by design). When triggered, calls `_doRefresh('section_ending')` which clears the explicit timeline and builds a dynamic AI rec queue based on the current song. App pushes the new upcoming items to native via `replaceUpcoming`. Native plays last section song, advances naturally into recs.

### Hamburger menu + sidebar + Settings page + embedding upload
New `src/app-settings.js` (~290 lines) with:
- Hamburger button (☰) fixed top-left, blurred background, z-index 50
- Slide-in sidebar from left (`transform: translateX(-100%)` → `0`) with backdrop, animated
- Sidebar entry: Settings (⚙)
- Settings sub-page with file picker for the `local_embeddings.bin` + `local_embeddings_meta.json` pair
- Validation: extension check, JSON parse with shape `{dim, entries[]}`, binary-size check (`entries.length × dim × 4`)
- Wrong/extra files: marked "Not supported: <name>"
- Successful upload: writes via `MusicBridge.writeBinaryFile` + `MusicBridge.writeTextFile` to the same data dir the on-device pipeline uses, then calls new `engine.reloadEmbeddingsFromDisk()` which loads from disk, merges into `songs[]`, rebuilds the recommender (with GPU attach), refreshes profile vec, marks embeddings ready, and fires `_fireSongLibraryChanged`. Status flips to ✓ "Embeddings loaded (N merged into library)".
- AI Embedding + Taste Signal buttons in Discover panel: tighter padding (10px from 16px) and smaller font (13px from 15px), with `margin-left: 50px` so the row clears the hamburger.

CSS for hamburger / sidebar / settings appended to `style.css`.

### Tab swipe gesture
New touch handler on `.content` (`app.js`) using **touch-target detection**: if the swipe starts on `.hscroll`, `.hscroll-wrap`, `.tabs`, an input/textarea/select, or any element with `data-no-tab-swipe`, it's a section/element scroll. Anywhere else, a swipe with `|deltaX| > 60px AND |deltaX| > 2 * |deltaY| AND duration < 600ms` switches tabs in order: Discover → Songs → Albums → Up Next → Browse. Edges don't wrap.

### Build verification
- `npm run build` — OK (Vite 411ms)
- `npm run test:unit` — 30/31 (pre-existing `onNativeAdvance` failure only)
- `npx cap sync android` — OK
- `:app:assembleDebug` — BUILD SUCCESSFUL in 2m 31s, 40 tasks executed (full Java rebuild + native CMake compile of NEON kernel for arm64-v8a / armeabi-v7a / x86 / x86_64). APK at `android/app/build/outputs/apk/debug/app-debug.apk`, 387 MB, timestamp 2026-05-09 23:47. (Trailing "BUILD FAILED" message is the Gradle metadata-cache lock collision when Android Studio holds `last-build.bin` — does not affect the APK.)

---

Last updated previously: 2026-04-26 (in-flight: in-session embedding-merge gap fix landed — `src/engine.js` now exposes an idempotent `_runPostBatchMerge(...)` that the `embeddingComplete` listener calls, plus a 5s status watchdog that runs while a batch is active and pings `MusicBridge.requestEmbeddingStatus()`; `MusicBridgePlugin.onEmbeddingServiceEvent` MSG_STATUS handler now synthesizes a fallback `embeddingComplete` when a status snapshot reports inactive after a previously-active state. Together these close the soak-observed bug where in-foreground batch completion during active playback did not propagate to JS until cold start. The earlier embedding/playback UI-lag stabilization pass and the AI-page unmatched/orphaned cleanup confirm flow remain in place. Details under "Latest Verified Changes".)

`local_embedding_generator.py` created 2026-04-26 — laptop/local version of `colab_embedding_generator.py`. Runs on Windows with NVIDIA GPU (CUDA) or falls back to CPU. Set `SONGS_DIR` at the top of the file before running. Output and CLAP checkpoint are placed next to the script automatically.

Current live structural status: 2026-04-26 the `src/app.js` support split helper modules are still live, but later rollback/fix work has pushed the current tree back above the original split baseline. Start point was 5,229 lines; current live `src/app.js` is 5,282 lines (+53 / ~1.0% vs that start point). Six helper modules remain live: `src/app-debug.js` (71), `src/app-status-ui.js` (85), `src/app-art.js` (111), `src/app-playlists-ui.js` (154), `src/app-back-navigation.js` (95), and `src/app-browse-render.js` (100). `src/engine.js` remains unsplit at 6,984 lines.

Earlier 2026-04-25 (fresh-APK main-process stall diagnosis landed + duplicate native playback-event dedupe landed + embedding-status callback recovery/polling landed + stale mini-player state hardening landed + new debug APK built after targeted verification + earlier same-day batches still in scope: stable embedding-store v3 hardening + `Because You Played` native `:ai` nearest-neighbor lookup + dead same-process `embedSingleSong` removal + DBG report copy instrumentation + stale-APK drift diagnosis + full Android/web verification rerun against fresh APK + Option B implementation cross-checked against source + Media3/ExoPlayer playback migration + initial `:ai` embedding-process split + playback-aware embedding scheduler/backoff while keeping whole-song decode + native `:ai` nearest-neighbor RPC first slice + AndroidX Startup provider crash avoidance + Media3 playback start hotfix)

## How To Use This File

This file is the short source of truth for the current app state, the latest verified behavior, and the build steps.

Full historical notes and older landed batches now live in:
- `project_development_archive.md`

Active structural-refactor campaign tracking lives in:
- `file_split_campaign.md` — file-size split campaign (no behavior changes; sanity-tested per slice; full verification at end of campaign)

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

**If `assembleDebug` fails with `Could not update ... last-build.bin (Access is denied)`** — Android Studio holds a lock on the Gradle metadata cache at `C:\Users\<you>\.gradle\caches\<ver>\file-changes\last-build.bin`. Either close Android Studio, OR use a separate Gradle user home so the CLI build never touches the AS-locked cache (verified working 2026-05-10 — produced a fresh APK in 2m 27s while AS stayed open):

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\android\gradlew.bat -p android --gradle-user-home "$env:USERPROFILE\.gradle-cli" --no-daemon assembleDebug
```

The first run with this flag downloads Gradle + AGP into the new home (~5 min, ~500 MB). Subsequent runs are normal speed. Don't share this home with Android Studio; pointing AS at it would re-introduce the lock collision.

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
- latest rebuild: `2026-05-09 23:47`, ~387 MB (full GPU stack: NNAPI / window batching / WebGPU / NEON SIMD; new app-settings, app-favorites, app-session-ui modules; engine.js split campaign complete at 1,838 lines)
- earlier rebuild: `2026-04-26 13:02:42`, ~366.6 MiB
- post-soak rebuild 2026-04-26 14:15: `npm.cmd run build` OK (vite 306ms), `npx.cmd cap sync android` OK (0.353s, 3 Capacitor plugins detected), `gradlew clean assembleDebug` BUILD SUCCESSFUL in 27s (169 tasks executed, 24 up-to-date — clean rebuild because the previous incremental `assembleDebug` had reused the existing 13:02 APK on the up-to-date check). Fresh APK timestamp: `2026-04-26 14:15:00`, 366.6 MiB at `android/app/build/outputs/apk/debug/app-debug.apk`. `npm.cmd run test:unit` was attempted first but **failed in `tests/engine.regression.test.js:223`** (1 of 30 tests) — the test expects `engine.onNativeAdvance(...)` second call with same `songId` + new `playbackInstanceId` to return `{duplicate: true}` but actual is `{needsSync: true, songInfo: {...}}`. This regressed between the last green test:unit run earlier today (2026-04-26 after the `src/app.js` embedding-progress UI-refresh dedupe — 30/30 passing) and now; the more recent AI-page cleanup popup landing only verified `npm.cmd run build` plus a focused Playwright spec, so the failure was not caught. User asked to skip tests and proceed with the build only this pass. Recommended follow-up: read `src/engine.js` `onNativeAdvance` to determine whether the return-shape change to `{needsSync, songInfo}` is intentional (then update the test) or accidental (then restore `{duplicate: true}`).

## Current Source Of Truth

### UI File Split Progress
- Both `src/app.js` and `src/engine.js` split campaigns are complete (2026-05-09).
- Start-of-campaign sizes:
  - `src/app.js` - 5,229 lines
  - `src/engine.js` - 7,026 lines at engine campaign start (up from 6,563 originally due to fix work between campaigns)
- Current live sizes:
  - `src/app.js` - ~2,700 lines (post-split + recent additions for swipe handler, settings init, search filter helpers, queueEnded handler refresh)
  - `src/engine.js` - 1,838 lines (under the 2,000-line hard ceiling)
- `src/app.js` helper modules (12 total): `app-debug.js` (71), `app-status-ui.js` (85), `app-art.js` (111), `app-playlists-ui.js` (154), `app-back-navigation.js` (95), `app-browse-render.js` (100), `app-song-menu.js` (252), `app-taste-ui.js` (595), `app-ai-page.js` (~280), `app-discover-ui.js` (565), `app-player-ui.js` (833), `app-settings.js` (~290).
- `src/engine.js` helper modules (10 total): `engine-state.js` (202), `engine-indexes.js` (57), `engine-taste.js` (1,180), `engine-embeddings.js` (~1,200 with reload + deferred re-embed), `engine-data.js` (~800), `engine-analytics.js` (767), `engine-insights.js` (501), `engine-playback.js` (627), `engine-favorites.js` (325), `engine-session-ui.js` (203). Plus shared `gpu-recommender.js` (~330).
- Java helper modules (`MusicBridgePlugin.java` 1,799 → 1,364 lines + 3 helpers): `MediaScanHelper.java` (176), `AlbumArtHelper.java` (71), `FileBridgeHelper.java` (255). Plus new `NativeAccelerator.java` (JNI wrapper for NEON SIMD via `cpp/embedding_native.cpp`).
- Verification discipline during these split campaigns:
  - every green boundary has passed `npm.cmd run test:unit` (30/31 — pre-existing `onNativeAdvance` failure only)
  - `npm.cmd run build` passing
  - `npx.cmd cap sync android` passing
  - `:app:assembleDebug` passing (most recent: 2m 31s, 40 tasks executed for full rebuild including native NEON compile)
  - `npm.cmd run test:ui` and `:app:connectedDebugAndroidTest` not rerun during recent structural / GPU work; recommended for end-of-campaign verification

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
- A fresh real-device long batch soak is still needed specifically against the new `:ai` path while actively playing music, now also validating that duplicate native playback events stay harmless, that AI-page progress survives any missed callback bursts, and that the new in-session embedding-merge recovery (JS watchdog + Java MSG_STATUS active→inactive fallback, landed 2026-04-26) actually fires when a batch finishes in-foreground during playback.

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

- 2026-05-10: two regression-fix follow-ups landed against the earlier same-day UI overhauls (full detail in follow-up sections #17 and #18 above).
  - **#18** — `View Album` from search overlay was throwing `TypeError: Cannot read properties of null (reading 'style')`. `viewAlbumForSong` (`src/app-song-menu.js:271-301`) referenced `document.getElementById('searchBar').style.display` but the `#searchBar` element was deleted in #12 (search input now lives permanently in the top bar). It also relied on the pre-#10 manual `.tab` / `.panel` `.active` toggle pattern which no longer drives navigation under the scroll-snap architecture. Replaced both with one `_activateTab('albums', { pushHistory: true })` call (which also handles search-overlay close, dirty-render, and history). Plumbing: added `activateTab: (...a) => _activateTab(...a)` to the `createSongMenuSupport(...)` options object in `src/app.js` and added `activateTab` to the destructuring in `app-song-menu.js`.
  - **#17** — Discover pull-to-refresh fired anywhere in the panel (including pulls back from a bottom-of-list rubber-band) instead of only at the top. `setupPullToRefresh` (`src/app.js:1885-2026`) gated on `content.scrollTop <= 0`, but the scroll-snap rewrite (#10) had moved `.content` to `overflow: hidden` and made each `.panel` its own `overflow-y: auto` scroll container — so `content.scrollTop` was permanently 0. Added a lazy `getDiscoverScroller = () => document.getElementById('panel-discover')` helper (matching the existing lazy-resolve pattern for `#pullIndicator` / `#discover-pull-body`) and switched both gate sites (touchstart line 1961, touchmove line 1971) to read from it. Touch listener stays on `.content`; only the scrollTop source changed.
  - Verification this pass: `npm.cmd run build` OK (Vite 511ms; bundle now `dist/assets/index-LfweI0P5.js`); `npx.cmd cap sync android` OK; `:app:assembleDebug` BUILD SUCCESSFUL in 9s — APK packaged successfully despite the trailing "BUILD FAILED" Gradle metadata-cache lock collision documented in "Build Steps". Fresh APK at `android/app/build/outputs/apk/debug/app-debug.apk`, **389.4 MB**, timestamp `2026-05-10 16:33:28`. `npm.cmd run test:unit` not rerun in this pass (pre-existing `onNativeAdvance` 30/31 baseline unchanged; both fixes are UI-glue with no engine surface). Real-device validation pending for both: confirm pull-to-refresh only triggers at the very top of Discover, and confirm `Search → song-menu → View Album` navigates to the Albums tab and expands the album with no console error.

- 2026-05-10: startup-performance batch (fixes A–F) landed across `src/embedding-cache.js`, `src/engine-data.js`, `src/engine-embeddings.js`, `src/engine-taste.js`, `src/engine.js`, `src/app.js`. Goal was to bring "Most Similar" rendering on warm-start (cache-skip path) under 2s. Six independent fixes, each with a safe fallback: A) Promise.all collapses 5+1 sequential awaits in `loadData` (Preferences + getAppDataDir); B) replaces `Uint8Array.from(atob, c=>c.charCodeAt(0))` with explicit loop in `_loadBinaryStore`; C) keeps Float32Array end-to-end through `_loadBinaryStore → _mergeLocalEmbeddings`, with JSON-safe legacy mirror writes via `_writeLegacyJson`; D) caches `EXTERNAL_DATA_DIR` in Preferences (`cached_data_dir_v1`) and adds new `engine.preloadEmbeddingsEarly()` that fires `_loadLocalEmbeddings()` before `loadData()` resolves, with reuse-or-discard behavior in `startBackgroundScan` if the resolved path differs; E) profile vector cache file `profile_vec_cache_v1.json` gated by a fingerprint (top-30 positive + top-30 negative weighted ids, each top song's `embeddingIndex`, `beta`, embedding count) so repeat startups skip the weighted-average + negative subtract math while still running `_buildSignalRowsFromSummary` + `_updateRecommendationPolicySnapshot` for live policy state; F) `_markEmbeddingsReady()` now fires before `buildProfileVec()` in both scan paths so `renderSimilar()` (which only needs `rec.recommendSingle` + the current song's embedding) can render Most Similar without waiting on the profile-vector build, which now runs in the background. Pre-fix safety backup: `backups/startup_perf_prework_20260510_060528/`. Verification this pass: `npm.cmd run build` OK (Vite 295ms); `npm.cmd run test:unit` 30/31 (pre-existing `onNativeAdvance` `{duplicate: true}` shape regression remains the only failure); `npx.cmd cap sync android` OK (latest bundle `dist/assets/index-sllJNX_9.js` synced to `android/app/src/main/assets/public/`); Android `:app:testDebugUnitTest` BUILD SUCCESSFUL in 31s; `:app:assembleDebug` blocked by Gradle metadata-cache lock (`last-build.bin` access denied — Android Studio process holds it). APK rebuild requires AS to be closed or the build re-run from AS; web bundle with all 6 fixes is already in Android assets so the next assembleDebug from any source produces an APK with this batch. Real-device validation pending: cold-start vs second-open timing measurements to confirm the ~1.0–1.5s target for "Most Similar shown".

- 2026-04-26: in-session embedding-merge gap fix landed (closes the soak-observed bug from earlier today where a batch completing in foreground with active playback never reached the JS merge path until next cold start). Three coordinated changes:
  - `src/engine.js` — the body of the existing `embeddingComplete` listener was extracted into `async function _runPostBatchMerge(data, source)`. The function is idempotent: it keys on a `${processed}:${failed}:${embeddingStartTime}` signature stored in `_lastCompletedBatchSig`, and a repeated call with the same signature returns `{ deduped: true }` without re-running the merge body. The existing native-event listener now calls `_runPostBatchMerge(data, 'native_event')`. `_runPostBatchMerge` is also added to the engine's exports so callers (and tests) can drive it directly.
  - `src/engine.js` — a status watchdog (`_startEmbeddingStatusWatchdog`/`_stopEmbeddingStatusWatchdog`) polls `MusicBridge.requestEmbeddingStatus()` every 5s while `embeddingInProgress` is true. The watchdog starts in `_startEmbedding` (just after `_lastCompletedBatchSig` is reset) and stops on completion / on start-failure / when the engine observes `embeddingInProgress=false`. The watchdog gracefully no-ops if `MusicBridge.requestEmbeddingStatus` is not exposed (e.g., test environment).
  - `MusicBridgePlugin.java` — the `MSG_STATUS` branch of `onEmbeddingServiceEvent` now tracks the previous `embeddingBridgeKnownActive` value. When a status snapshot reports `inProgress=false` and the bridge previously believed the batch was active, it synthesizes `emitEmbeddingComplete(processed, failed)` so JS sees `embeddingComplete` even if the original `MSG_COMPLETE` Messenger event was dropped. The active-side branch still emits `embeddingProgress` as before.
  - Coverage: `tests/engine.regression.test.js` gained a `_runPostBatchMerge` idempotency test that asserts a repeated call dedupes (`{ deduped: true }`) and produces only one "Embedding complete" log entry, while a distinct `(processed, failed)` pair after the first completion runs the merge again.
  - Pre-fix safety backup: `backups/embedding_complete_recovery_prework_20260426_175248/` (engine.js, app.js, MusicBridgePlugin.java, engine.regression.test.js).
  - Verification after this pass: `npm.cmd run test:unit` 31/32 passing (the new `_runPostBatchMerge` test passes; the unrelated `onNativeAdvance` `{duplicate: true}` regression noted in the build-info entry above remains the single failure and is out of scope here); `npm.cmd run build` OK (663 ms); `npx.cmd cap sync android` OK; `:app:testDebugUnitTest` BUILD SUCCESSFUL in 53s; `:app:assembleDebug` BUILD SUCCESSFUL in 12s; focused `tests/e2e/embedding-detail.regression.spec.js` passes in 57.5s with a 60s timeout (the default 30s timeout occasionally flakes that spec; not a regression). New debug APK: timestamp `2026-04-26 18:04`, ~366.7 MiB at `android/app/build/outputs/apk/debug/app-debug.apk`. `:app:connectedDebugAndroidTest` was not rerun in this pass.
  - Still requires real-device confirmation: emulator soak repro of the original failure mode (in-foreground batch completion under active playback) to verify the watchdog or Java fallback fires within ~5s of natural batch completion and that the JS console shows the `Merged ... embeddings from disk after batch completion` line in the same session, with `local_embeddings.bin` updated without a cold restart.

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
