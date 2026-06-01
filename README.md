# IsaiVazhi

IsaiVazhi is an offline-first Android music player that learns from local
listening behavior. It combines native playback, durable background signal
capture, and CLAP audio embeddings to recommend music without accounts,
streaming, tracking, or a server.

<p align="center">
  <img src="docs/screenshots/library.png" width="23%" alt="Songs library">
  <img src="docs/screenshots/now-playing.png" width="23%" alt="Now playing">
  <img src="docs/screenshots/song-actions.png" width="23%" alt="Song actions">
</p>

## Why It Exists

Most music apps optimize for streaming catalogs. IsaiVazhi is built for people
with personal music libraries who still want recommendation behavior that feels
adaptive. The app keeps the music, embeddings, listening history, favorites,
dislikes, playlists, and recommendation state on the device.

## Highlights

- Native Android player built with Kotlin, Jetpack Compose, Media3, and ExoPlayer.
- Offline recommendation engine that blends current song, session taste,
  long-term taste, explicit feedback, skip behavior, and CLAP similarity.
- Background-safe playback signal capture through a Media3 `MediaSessionService`.
- **Similar Songs row** in Now Playing — AI surfaces tracks similar to the
  current song; tap to play, long-press to queue.
- **AI is invisible by design** — recommendation work runs entirely in the
  background; UI never lags, stalls, or shows AI loading states.
- **Lockscreen Refresh button** — rebuilds the upcoming queue with fresh AI
  recommendations without unlocking the phone.
- **Refresh spinner** — NowPlaying and MiniPlayer show a subtle progress
  indicator only while the AI refresh is actively running.
- Taste Signal page with audit-style playback evidence, tuning controls, and
  visible positive/negative signals.
- Playlist, album, Up Next, favorites, disliked songs, search, and batch delete
  flows for local libraries.
- AI management page for importing embeddings, scanning coverage, retrying
  failures, detecting duplicates, and cleaning stale rows.
- Kaggle, Colab, and local scripts for precomputing CLAP embeddings.

## Tech Stack

| Area | Choices |
| --- | --- |
| App | Kotlin, Jetpack Compose, Material 3 |
| Playback | Media3 `MediaSessionService`, ExoPlayer, Android media notification controls |
| Persistence | DataStore Preferences, SQLite, sqlite-vec |
| Recommendations | CLAP audio embeddings, vector similarity, session/taste scoring, recency decay |
| Native acceleration | C++ / NEON vector dot-product path |
| Tooling | Gradle, Android SDK 36, Python embedding scripts for Kaggle/Colab/local GPU |

## Repository Layout

```text
.
|-- native/                  # Android app source
|   |-- app/src/main/kotlin/ # Compose UI and Kotlin engines
|   |-- app/src/main/java/   # Media3 service and embedding database bridge
|   |-- app/src/main/cpp/    # Native vector acceleration
|   `-- gradle/              # Gradle wrapper
|-- tools/embeddings/        # Kaggle, Colab, local, and merge tools
|-- docs/
|   |-- architecture.md
|   `-- screenshots/
|-- LICENSE
`-- README.md
```

See [docs/architecture.md](docs/architecture.md) for a deeper overview.

## Install

Download the APK from the latest GitHub release and sideload it on Android.
Because the app is not installed from the Play Store, Android will ask you to
allow installs from your browser or file manager.

## Build From Source

Requirements:

- Android Studio with Android SDK API 36+
- JDK 17 or newer. The JBR bundled with Android Studio works well.
- Android NDK for the native acceleration target

```bash
git clone https://github.com/humorouslydistracted/isaivazhi.git
cd isaivazhi

# Point this at your local Android SDK.
echo "sdk.dir=/path/to/Android/Sdk" > native/local.properties

# Point JAVA_HOME at Android Studio's bundled JBR or any compatible JDK 17+.
export JAVA_HOME="/path/to/Android Studio/jbr"

cd native
./gradlew :app:assembleDebug
```

Debug APK:

```text
native/app/build/outputs/apk/debug/app-debug.apk
```

## AI Architecture

The recommendation engine runs in a separate `:ai` process
(`EmbeddingForegroundService`) so that vector operations never block the UI
thread. The UI process talks to it over a command bus; results arrive
asynchronously and are applied without any visible stall.

### How recommendations surface

| Surface | Trigger | What it shows |
| --- | --- | --- |
| Now Playing — Similar Songs row | Current track changes | Top 10 tracks most similar to the current song (CLAP cosine similarity) |
| Up Next queue | Refresh button (NowPlaying, MiniPlayer, lockscreen) | 50-track blended queue from current song + session taste + long-term taste |
| Queue end | Last track of an AI or Library queue | Silently appends 50 fresh AI picks so playback never stops |

### Refresh button

The Refresh button appears in three places:
- **Now Playing screen** — top-right area, next to the track title
- **Mini Player** — right side of the persistent bottom bar
- **Lockscreen / notification** — alongside the Favorite button (SLOT_OVERFLOW)

Tapping Refresh replaces the upcoming queue with a fresh AI-blended selection
based on what you are currently playing and your long-term taste. Any songs you
manually queued with "Play Next" are preserved at the front.

A small circular progress indicator is visible in NowPlaying and MiniPlayer
while the refresh is running. It disappears automatically when done.

## Embeddings

The player works as a local music player without precomputed embeddings. The
recommendation engine becomes much more useful after importing CLAP embeddings
for the library.

Use the scripts in [tools/embeddings](tools/embeddings):

- Kaggle GPU workflow: `kaggle_embedding_generator.py`
- Google Colab workflow: `colab_embedding_generator.py`
- Local CUDA/CPU workflow: `local_embedding_generator.py`
- Strict merge/validation: `merge_local_embeddings.py`

The generated `local_embeddings.json` can be imported from the app's AI page.

## Privacy

IsaiVazhi is designed around local ownership:

- no account
- no analytics service
- no streaming backend
- no cloud recommendation API
- playback evidence and taste profile stay on device

Kaggle, Colab, or a local GPU machine are optional tools for precomputing
embeddings. They are not required for normal playback.

## Status

This is an active personal/open-source project. The current codebase is the
native Kotlin/Compose app in `native/`.

## License

[MIT](LICENSE)
