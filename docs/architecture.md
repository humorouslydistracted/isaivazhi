# Architecture

IsaiVazhi is a native Android app under `native/`. The current codebase is a
Kotlin and Jetpack Compose rewrite of an earlier Capacitor prototype.

## Runtime Layers

| Layer | Main files | Responsibility |
| --- | --- | --- |
| UI | `native/app/src/main/kotlin/com/isaivazhi/app/ui` | Compose screens for Discover, Browse, Taste, AI management, playlists, and playback. |
| App engines | `native/app/src/main/kotlin/com/isaivazhi/app/engine` | Library scanning, recommendation state, history, favorites, playlists, playback coordination, and persistence. |
| Playback service | `native/app/src/main/java/com/isaivazhi/app/Media3PlaybackService.java` | Media3 `MediaSessionService`, ExoPlayer queue, notification controls, audio focus, lockscreen integration, and durable playback signal capture. |
| Embedding database | `native/app/src/main/java/com/isaivazhi/app/EmbeddingDb*.java` | SQLite storage for CLAP vectors, filepath indexes, duplicate detection, and nearest-neighbor lookup. |
| Native acceleration | `native/app/src/main/cpp/embedding_native.cpp` | NEON-accelerated vector scoring fallback for similarity operations. |
| Offline embedding tools | `tools/embeddings` | Kaggle, Colab, and local scripts for generating `local_embeddings.json`. |

## Recommendation Flow

1. The app scans the device library through MediaStore.
2. The AI page imports or generates CLAP embeddings for local tracks.
3. Playback events, favorites, dislikes, skips, and completions are persisted as
   taste signals.
4. Discover blends current song context, session behavior, long-term taste, and
   CLAP nearest-neighbor similarity.
5. Up Next is rebuilt from scored candidates while respecting dislikes,
   repeat-skips, queue removals, and recently played songs.

## Playback Signal Capture

Playback is service-first. Media3 keeps playing when the Activity is destroyed,
and the service records durable transition events so background listening still
updates Taste Signal and Recently Played after the app returns.

The Kotlin Activity drains those service-authored events after startup and
foreground resume. Event IDs are persisted to keep recovery idempotent and avoid
duplicate history rows.

## Privacy Model

The app is designed for local libraries:

- no account
- no streaming backend
- no analytics service
- no cloud recommendation API
- embeddings and taste state live on device

External compute is optional and only used to precompute embeddings through
Kaggle, Colab, or a local GPU machine.
