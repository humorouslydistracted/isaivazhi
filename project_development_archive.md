# Project Development Archive

This archive keeps only compact historical context that is still useful.

If this file, `project_development.md`, and live code ever disagree, prefer:
1. live code
2. `project_development.md`
3. this archive only as background

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
