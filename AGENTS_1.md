# CLAUDE.md — IsaiVazhi Music Player

Personal Android music player with a fully on-device CLAP-based recommendation
engine. No server. No cloud. Capacitor (JS frontend) + Android (Java) native
layer, ONNX Runtime for inference.

## How to use this file

- Auto-loaded by Claude Code at session start.
- For **current state, open bugs, build steps**: see `project_development.md`.
- For **scope and architecture history**: see `PROJECT_BRIEF.md`.
- This file = rules and constraints that **don't change session-to-session**.

If files disagree, prefer: live code → this file → `project_development.md` →
archive.

---

## Core principles

### 1. Don't assume. Surface confusion.
- If a request is ambiguous, ask. Do not pick an interpretation silently.
- If you find inconsistencies between the request and the code, surface them.
- If a simpler approach exists, say so before implementing the asked one.
- State assumptions explicitly when you must make them.

### 2. Surgical changes only
- Touch the minimum code required. Nothing speculative.
- Do not refactor, rename, or reformat code adjacent to the change.
- Do not delete or rewrite comments you do not fully understand.
- If a change touches IDs, persistence files, or process boundaries between
  JS and Java, **stop and confirm** before writing code. The orphan-cleanup
  bug corrupted favorites, queue, and history once. Don't repeat.

### 3. Minimum code
- No abstractions for single-use code.
- No "flexibility" or "configurability" not requested.
- 200 lines that should have been 50 — rewrite.
- "Would a senior engineer call this overcomplicated?" If yes, simplify.

### 4. Goal-driven, verified
- A task is not done until the verification matrix passes.
- The matrix lives in `project_development.md` under "Build Steps".
- Web tests, Android unit tests, and connected emulator tests all matter.
- If you cannot run the verification, say so explicitly. Do not claim done.

---

## Hard constraints — do not relitigate

### Model & inference
- **HTSAT-base only.** HTSAT-tiny was tried and rejected for poor
  recommendation quality.
- `enable_fusion=False` is required. The checkpoint
  (`music_audioset_epoch_15_esc_90.14.pt`) was not trained with fusion
  weights. `enable_fusion=True` has failed before.
- Input is **raw 48 kHz waveform**. The mel spectrogram is computed inside
  the ONNX graph. Do not reimplement DSP on the Android side.
- On-device coverage uses **multi-window averaging**: 3 windows at 20%, 50%,
  80% of the song. Do not change this without an explicit review.
- Embeddings are 512-dim, L2-normalized.

### Architecture boundary
- Fully on-device. **No cloud, no external API calls, no server code.**
- JS layer (`engine.js`, `app.js`, `recommender.js`, `embedding-cache.js`)
  owns UI, session state, and recommendation logic.
- Java layer (`EmbeddingService.java`, `MusicBridgePlugin.java`) owns ONNX
  inference, audio decode (`MediaExtractor`/`MediaCodec`), file I/O for
  embeddings, and notification controls.
- Do not blur the boundary. If something can be done in JS, do it in JS.

### Data integrity
- **`EmbeddingService` is the sole writer** to `local_embeddings.json`
  during embedding. JS must not write to it concurrently. A dual-write race
  caused real corruption before.
- **ID matching must be robust.** Filename-only fallback in
  `_mergeLocalEmbeddings` caused wrong songs to receive wrong embeddings.
  Any code touching the song-id ↔ embedding map needs explicit review.
- ONNX sessions must be **released after use**. `embedSingleSong` was
  recently caught leaking. Check session lifecycle in any new inference
  path.
- Schema changes to `song_library.json`, `local_embeddings.json`, or
  `profile_summary_v2` are breaking. Migrate, do not rewrite in place.
- Profile summaries are keyed by **filename** in `profile_summary_v2`. Do
  not switch the key without a migration.

### Recommendation system
- The three-vector blend (current song + session + profile) with ramp-up
  weights is intentional. Do not collapse to one vector.
- Negative signal mechanisms (X-score, `negativeListenWeight`, dislike
  flag, similarity-boost propagation) intentionally overlap. The taste
  review system is the human-in-the-loop mitigation. Do not "simplify" by
  removing layers without an explicit architecture discussion.
- Similarity-boost propagation at 0.1/neighbor is bounded by design — do
  not raise without review.
- Soft-refresh zones (frozen / stable / fluid) are intentional.

---

## Workflow — surgical changes (default mode)

For file splits, module reorganization, or architecture migrations, this
section does **not** apply. Skip ahead to "Structural changes".

### Before writing code
1. State the one bug or feature being addressed. One at a time.
2. Restate the failure mode or goal in your own words (1–2 lines).
3. List the files you intend to touch.
4. If the change touches: IDs, persistence files, JS↔Java boundary, ONNX
   lifecycle, or the recommendation blend — pause and confirm.

### While writing code
- Match existing style. Use `logger.js`, not `console.log`.
- Do not edit code you do not understand, even if adjacent.
- Do not change unrelated comments or formatting.
- Avoid speculative `try/catch`; only catch what you can actually handle.

### Before claiming done
- Run the relevant subset of the verification matrix in
  `project_development.md` → "Build Steps".
- For changes touching the JS engine: web unit tests + Playwright.
- For changes touching native: Android unit tests + connected tests.
- For changes touching both: all of the above.
- Use the **app-scoped** connected-test task, not aggregate
  `connectedDebugAndroidTest`.

---

## Structural changes (file splits, architecture migrations)

The "surgical changes only" principle (Core Principle 2) is **overridden**
in this mode. Principles 1 (surface confusion), 3 (minimum code per
slice), and 4 (verified) still apply.

Trigger: any change that moves code across files, splits a file, merges
files, replaces one subsystem with another, or shifts a process boundary
(e.g. JS → native, main process → `:ai` process).

### Two-phase rule

Plan and execution are **separate turns**. Do not write code in the same
turn as the plan. Wait for explicit approval before executing.

"Looks good, proceed" is approval. Silence is not.

### Every plan must contain

1. **Scope boundary** — exactly what is in scope, exactly what is out.
2. **Parity criteria** — the observable behavior that must remain
   identical, stated as something testable.
3. **Slice plan** — break the change into independently verifiable
   slices. Big-bang structural changes are rejected.
4. **Verification per slice** — which tests must pass at each slice
   boundary (web unit, Playwright, Robolectric, connected — name them).
5. **Rollback path** — how to back out if a slice breaks.
6. **Out-of-scope risks** — what the plan deliberately does not address.

### File-split rules

- **Move code as-is** in the split slice. No rewrites, no renames, no
  signature changes in the same slice. Refactoring is a separate pass.
- **Preserve old import paths** as re-exports from the original file for
  at least one slice. Callers migrate in a later slice.
- **Search the whole repo** for imports of moved symbols: JS, tests,
  build config, Android assets. Capacitor bundles the JS — a missed
  import is a runtime failure, not a build failure.
- **Circular dependencies are stop-the-line.** Surface them. Do not
  paper over with lazy `require` or dynamic `import()`.
- **Module-load side effects** (anything that runs at file top level)
  are load-order risks. Call them out before moving.
- **One concern per slice.** No combining split with feature work, bug
  fixes, or renames.

### Migration rules

- **Old path stays alive** until the new path passes the full
  verification matrix. No deletions in the introduction slice.
- **Both paths run side by side** during the migration window, gated by
  a feature flag, build flag, or runtime switch. State which.
- **Behavioral parity is the bar.** Any intentional behavior change is a
  separate decision, called out explicitly and given its own slice.
- **Persistence schemas** (`song_library.json`, `local_embeddings.json`,
  `profile_summary_v2`) require an explicit migration step with a
  backup. Do not migrate in place.
- **Each slice ends with the build green.** Intermediate states must be
  ship-able even if the migration is paused indefinitely.
- **Track residual scope.** At the end of each slice, list what still
  uses the old path.

### Anti-patterns specific to structural work

- "While I was in there, I also..." — reject. Open a separate task.
- Removing the old code in the same slice that introduces the new code.
- Splitting a file and refactoring its API in one pass.
- Changing a JSON persistence schema during a JS-only refactor.
- Treating a migration as done before the old path is removed and the
  full matrix has passed on the new path.

---

## Files of note

| File | Role |
|---|---|
| `engine.js` | session state, favorites, profile, blending |
| `recommender.js` | cosine, MMR, k-means |
| `app.js` | UI logic |
| `embedding-cache.js` | local embedding cache (read path) |
| `logger.js` | use this for all logging |
| `EmbeddingService.java` | sole writer to `local_embeddings.json` |
| `MusicBridgePlugin.java` | Capacitor bridge, native media actions |
| `index.html` | app shell |
| `song_library.json` | song catalog (handle with care) |
| `local_embeddings.json` | per-song 512-dim vectors |
| `profile_summary_v2` | filename-keyed profile state |

---

## Anti-patterns seen before (do not repeat)

- Filename-only ID fallback in `_mergeLocalEmbeddings` → wrong songs got
  wrong embeddings.
- ID reassignment in `removeOrphanedEmbeddings` → corrupted favorites,
  queue, history.
- JS writing to `local_embeddings.json` while `EmbeddingService` was
  writing → race condition.
- HTSAT-tiny → poor recommendation quality.
- `enable_fusion=True` with this checkpoint → failure to load.
- Reimplementing mel spectrogram on the Android side → unnecessary; ONNX
  graph already handles it.
- Skipping `restorePlaybackState` ordering → cold-start play before
  `currentSong` is restored.

---

## Out of scope

- Cloud features, external APIs, server code.
- Replacing CLAP with another encoder (decision is settled).
- Replacing on-device inference with a remote service (decision is settled).
- Adding a different audio backend before Media3 migration is closed.
