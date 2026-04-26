# IsaiVazhi

An offline-first Android music player that learns from how you actually listen -- no accounts, no streaming, no tracking.

An offline-first Android music player with AI-powered recommendations driven by CLAP audio embeddings. The app learns what you actually listen to — plays, skips, favorites, dislikes — and uses that, plus audio-similarity via precomputed embeddings, to surface Discover, Taste Signal, and dynamic Up Next feeds.

Built as a Capacitor hybrid: the UI and recommendation engine are plain HTML/CSS/JS, and the media-playback, notification, lockscreen, and background paths are native Android (Java) on top of Media3 `MediaSessionService` + `ExoPlayer`.

---

## Features

- Local library scan from MediaStore — no server, no account, no tracking.
- Native Android playback with proper lockscreen / notification controls, audio focus, Bluetooth / head-unit media metadata, and custom `favorite` / `close` media actions.
- Recommendation engine that blends:
  - direct playback signals (plays, skips, completions, favorites, dislikes)
  - CLAP audio-embedding similarity (neighbor-influence, not just nearest-neighbor)
  - fading manual priors (favorites / dislikes lose weight over time)
- Discover surfaces:
  - **Most Similar** — live, similarity-only, supports Freeze / Unfreeze
  - **For You**, **Because You Played**, **Unexplored Sounds** — taste-aware, refresh on manual pull only
- Taste Signal page with per-playback audit rows, engine snapshot, copyable logs, and live recommendation tuning.
- Playlists, albums, Up Next queue editing, shuffle and loop modes.
- Robust listen-capture: native playback is authoritative, JS only saves evidence snapshots on background / close; cold-start recovery reconciles the two.

## Install (easy path)

Download `app-debug.apk` from the [latest release](../../releases/latest) and install on an Android device (sideload; you'll need to allow "Install unknown apps" for your browser / file manager).

Then generate embeddings for your music — see **Embeddings pipeline** below.

> The APK lives in the `releases/` folder locally (not committed to git — it's over GitHub's 100 MB per-file limit). When publishing this repo, create a GitHub Release and attach `releases/app-debug.apk` as a release asset.

## Build from source

Requirements:
- Node.js 18+
- Android Studio (the Gradle setup uses its bundled JBR for `JAVA_HOME`)
- Python (only if you want to run the Colab embedding generator locally)

```bash
npm install
npm run build            # web bundle into dist/
npx cap sync android     # push web assets + Capacitor config into android/

# Windows / PowerShell — point JAVA_HOME at Android Studio's JBR
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
./android/gradlew.bat -p android assembleDebug
```

APK output: `android/app/build/outputs/apk/debug/app-debug.apk`.

On macOS / Linux set `JAVA_HOME` to the JBR inside your Android Studio install and use `./android/gradlew` instead of `gradlew.bat`.

## Run tests

```bash
npm run test:unit        # Vitest — engine regression + pending-coverage (27 tests)
npm run test:ui          # Playwright smoke — seeded web app with MusicBridge mock
npm run build            # web build sanity

# Android native (Robolectric) — service-side storage / recovery paths
./android/gradlew.bat -p android testDebugUnitTest

# Android connected smoke — requires an attached emulator or device
./android/gradlew.bat -p android :app:connectedDebugAndroidTest
```

Use the app-scoped `:app:connectedDebugAndroidTest`, not the aggregate task — the aggregate also walks unrelated Capacitor plugin-module variants.

## Embeddings pipeline

The app uses [CLAP](https://github.com/LAION-AI/CLAP) audio embeddings for similarity. It does **not** generate embeddings on-device — CLAP inference on a phone is slow and battery-hungry. Instead:

1. Upload your music library to Google Drive (flat folder or preserved structure).
2. Open `colab_embedding_generator.py` in Google Colab with a GPU runtime.
3. Edit `SONGS_DIR`, `OUTPUT_DIR`, and `PHONE_MUSIC_BASE` at the top of the script to match your paths.
4. Run it. It generates `local_embeddings.json` keyed by a hash of the audio file contents.
5. Copy `local_embeddings.json` to the app's private folder on your phone:
   ```
   /storage/emulated/0/Android/data/com.isaivazhi.app/files/
   ```
6. Open the app — it'll pick up the embeddings on the next scan.

Songs without embeddings still play; they just won't appear in audio-similarity surfaces until you regenerate.

If you want to generate embeddings locally on Windows instead of Colab, `local_embedding_generator.py` is the laptop-oriented variant. It can use CUDA when available and otherwise falls back to CPU.

## Project layout

```
src/
  app.js              — UI bootstrap, tab wiring, mini-player, bridge glue
  app-debug.js        — debug logger / error hooks
  app-status-ui.js    — status toast and recommendation rebuild UI
  app-art.js          — album-art queue / fallback helpers
  app-playlists-ui.js — playlist picker and CRUD modal helpers
  app-back-navigation.js — Android back-button and sub-page close helpers
  app-browse-render.js   — browse/search render helpers
  engine.js           — recommendation engine, discover cache, taste signal, persistence
  music-bridge.js     — thin Capacitor plugin wrapper
  recommender.js      — similarity / nearest-neighbor math
  embedding-cache.js  — CLAP embedding load / hash-keyed lookup
  logger.js           — shared activity log
  activity-log.js     — rendering helpers for the log UI
android/
  app/src/main/java/com/isaivazhi/app/
    MainActivity.java
    Media3PlaybackService.java          — active playback service (`:playback` process)
    Media3PlaybackControllerClient.java — controller bridge into Media3
    MusicBridgePlugin.java              — Capacitor <-> native bridge
    EmbeddingForegroundService.java     — active embedding coordinator (`:ai` process)
    EmbeddingControllerClient.java      — controller bridge into embedding service
    EmbeddingService.java               — embedding worker runtime
tests/
  engine.regression.test.js
  engine.pending-coverage.test.js
  e2e/                          — Playwright smoke
colab_embedding_generator.py    — run in Colab to produce local_embeddings.json
local_embedding_generator.py    — local Windows generator with CUDA/CPU fallback
merge_local_embeddings.py      — merge / validate multiple local_embeddings.json outputs
project_development.md          — ongoing dev log; current source of truth for landed behavior
project_development_archive.md  — compacted older history
```

`project_development.md` in particular is worth reading if you're contributing — it describes the current behavior of listen-capture, discover-cache semantics, and the recommendation policy at the level of detail that isn't obvious from reading the code cold.

## Known limitations

- Android package id is now `com.isaivazhi.app`. This is a clean Android app-identity break from the older `com.musicplayer.app.test` build, so old installs do not upgrade in place and old app-private data/embeddings paths are not migrated automatically.
- Some car head units may continue showing cached metadata until Android/head-unit caches refresh.
- Embeddings pipeline is offline / manual; there's no in-app "regenerate embeddings" button.
- Only Android is a first-class target. The web build runs but assumes Capacitor `MusicBridge` is mocked or native.
- The repo does **not** ship the CLAP ONNX model (`android/app/src/main/assets/clap_audio_encoder.onnx{,.data}`, ~285 MB). On-device inference is a stubbed-out path; the supported flow is Colab-generated `local_embeddings.json`. If you want to revive on-device inference, drop your own ONNX export into that assets folder and wire up `EmbeddingService.java`.

## Contributing

PRs welcome but not expected. If you fork and ship something cool, I'd love to hear about it — but you don't owe me a notification.

## License

MIT — see [LICENSE](LICENSE). Use it, fork it, sell it, rebrand it. No permission needed.
