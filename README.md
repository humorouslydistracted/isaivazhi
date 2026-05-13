# IsaiVazhi

An offline-first Android music player that learns from how you actually listen — no accounts, no streaming, no tracking.

**v2.x (current): full Kotlin / Jetpack Compose rewrite.** UI, recommender, signal capture, and persistence are all native Kotlin. Playback runs on Media3 `MediaSessionService` + `ExoPlayer`. The pre-rewrite Capacitor build (HTML/CSS/JS UI on top of the same Media3 stack) lived through v1.x and is archived in [`capasitor_legacy.md`](capasitor_legacy.md). Application id: `com.isaivazhi.app.kt` (the legacy Capacitor build used `com.isaivazhi.app`, so both can coexist on one device).

---

## Features

- **Local library scan** from MediaStore — no server, no account, no tracking.
- **Native Android playback** with proper lockscreen / notification controls, audio focus, Bluetooth / head-unit media metadata, custom favorite / close actions.
- **AI recommender** that blends:
  - direct playback signals (plays, skips, completions, favorites, dislikes) with Capacitor-parity scoring formulas
  - CLAP audio-embedding similarity (top-10 neighbor cascading boost on every playback / favorite / dislike / queue-remove)
  - recency decay (`0.5^(daysSince/30)` half-life), favorite / dislike priors that fade with plays
  - strongly-negative songs hard-blocked from Up Next (top 18% by negative strength, floor ≥ 1.5)
- **Discover surfaces**: Most Similar (similarity-only, supports Freeze), For You, Because You Played, Unexplored Sounds.
- **Taste Signal page** with chip-rich per-song rows (Favorite / Disliked / Mixed / X N / Similarity ±N / Top ±30 / Short-listened / Rec blocked / Neutral), tunable knobs (Adventurous / Session weight / Skip strength), engine snapshot, per-event audit timeline with copyable logs.
- **Playlists, Albums, Up Next** with drag-reorder, swipe-remove, album long-press menu (Play / Shuffle / Delete).
- **Robust signal capture** at the service level — accumulator survives Activity destruction, Doze, and seeks.
- **Built-in embedding manager** (AI page): scan, embed, retry, dedupe (filepath + audio-identical groups), purge stale rows.

## Install (easy path)

Download `app-debug.apk` from the [latest release](../../releases/latest) and install on Android (sideload — you'll need to allow "Install unknown apps" for your browser / file manager).

Then generate embeddings for your music — see **Embeddings pipeline** below.

## Build from source

Requirements:
- Android Studio (latest stable) with NDK installed.
- JDK 17+ — easiest is the JBR that ships with Android Studio (`/path/to/Android Studio/jbr`).
- Android SDK API level 36+.

```bash
git clone https://github.com/humorouslydistracted/isaivazhi.git
cd isaivazhi
echo "sdk.dir=/path/to/Android/Sdk" > native/local.properties
# Point JAVA_HOME at the Android Studio JBR (path varies per OS):
export JAVA_HOME="/path/to/Android Studio/jbr"
cd native && ./gradlew :app:assembleDebug
# APK: native/app/build/outputs/apk/debug/app-debug.apk
```

The optional CLAP ONNX encoder (`clap_audio_encoder.onnx` + `.data`, ~272 MB) is intentionally not bundled — the on-device inference path is unused. Embeddings are precomputed in Colab / locally and copied into the SQLite store (see below).

## Embeddings pipeline

The recommender's similarity arm needs precomputed CLAP embeddings of your library, stored in the app's SQLite database. Two workflows:

1. **Colab (free, slow)**: open `colab_embedding_generator.py`, upload your music, run cells. Produces a `.pkl` file → use the in-app **Import** flow in the AI page.
2. **Local (faster, requires GPU)**: `python local_embedding_generator.py /path/to/music`. Same output.
3. (Optional) `merge_local_embeddings.py` if you generate in batches.

The app's **AI page** handles import, dedupe, scan, retry-failed, and a per-song / per-album manager.

## Project layout

```
.
├── native/                       # Kotlin/Compose Android project (the app)
│   ├── app/
│   │   ├── build.gradle.kts
│   │   ├── proguard-rules.pro
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── kotlin/com/isaivazhi/app/        # UI + engines (~30 .kt files)
│   │       ├── java/com/isaivazhi/app/          # Media3 service, embedding DB (legacy Java)
│   │       ├── cpp/                              # NEON SIMD dot-product accelerator
│   │       ├── jniLibs/                          # sqlite-vec prebuilt .so per ABI
│   │       └── res/                              # icons, themes, strings
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   └── gradle/wrapper/
├── colab_embedding_generator.py
├── local_embedding_generator.py
├── merge_local_embeddings.py
├── project_development.md        # full per-push development log
├── capasitor_legacy.md           # archived Capacitor-era history (pushes #1–#21)
├── AGENTS_1.md                   # agent-mode operating notes
├── CLAUDE_1.md                   # Claude Code project notes
└── LICENSE
```

## License

See [LICENSE](LICENSE).
