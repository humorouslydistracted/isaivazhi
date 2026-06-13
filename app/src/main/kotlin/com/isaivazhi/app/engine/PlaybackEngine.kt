package com.isaivazhi.app.engine

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.isaivazhi.app.Media3PlaybackService
import com.isaivazhi.app.PlaybackCommandContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Kotlin engine over the existing Media3PlaybackService (Java, copied verbatim
 * from the Capacitor build). The UI calls these methods directly — no
 * Capacitor bridge, no executor queue.
 *
 * Holds the current queue as Kotlin state alongside the MediaController so
 * the Discover screen can render upcoming items without round-tripping to
 * Media3 for every read.
 */
class PlaybackEngine(
    private val appContext: Context,
    private val preferences: AppPreferences,
    private val history: HistoryEngine? = null,
    private val taste: TasteEngine? = null,
    private val signalTimeline: SignalTimelineEngine? = null,
    // Push #63: in-memory session counters used for SignalTimeline snapshots.
    private val session: SessionEngine? = null,
    // Push #41: optional toaster for shuffle/repeat/queue-end feedback.
    // Optional + null-default so unit tests + ad-hoc constructions still work.
    private val toaster: Toaster? = null,
    // Push #74: app-wide activity log for human-readable in-app diagnostics.
    private val activityLog: ActivityLogEngine? = null,
    // Push #74: favorites engine — wired so the MediaController.Listener for
    // EVT_MEDIA_ACTION can sync notification taps into the Kotlin state.
    private val favorites: FavoritesEngine? = null,
    private val recentlySurfacedTracker: RecentlySurfacedTracker? = null,
) {

    /**
     * Source label for the next transition. Set to "next_button" / "skip_prev" /
     * "manual_tap" by user-facing actions so the taste engine can classify the
     * signal correctly (auto skips count, manual taps don't).
     */
    @Volatile
    var pendingTransitionSource: String = "auto_advance"

    /** Set from MainActivity so history-backed Prev can resolve filenames to [Song]. */
    @Volatile
    var resolveSongByFilename: ((String) -> Song?)? = null

    /**
     * Push #70: the kind of content currently loaded as the queue. Drives
     * the queue-exhaust LE's "should I append AI?" decision and threads
     * into all queue-op logs for diagnostics.
     *
     *   LIBRARY           — Songs-tab tap or any "play a single song" flow.
     *                       AI tail appends on queue exhaust (LOOP=OFF).
     *   ALBUM             — Album-track tap. NO AI tail ever; album is
     *                       self-contained. LOOP=OFF stops; LOOP=ALL loops.
     *   BROWSE_SECTION    — View-All section (Most Played, Last Added,
     *                       Recently Played, etc.). Same as ALBUM: no AI.
     *   DISCOVER_SECTION  — Most Similar / For You / Because You Played /
     *                       Unexplored. AI tail appends on queue exhaust.
     *   AI_RECOMMENDED    — Set after an AI tail append so the next
     *                       exhaust knows the queue was already extended.
     */
    enum class QueueContext { LIBRARY, ALBUM, BROWSE_SECTION, DISCOVER_SECTION, AI_RECOMMENDED }

    data class PlaybackState(
        val currentSongId: Int? = null,
        val currentMediaId: String? = null,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val isPlaying: Boolean = false,
        // NOTE: positionMs / durationMs are NOT updated by the 500 ms position
        // poll any more — they're kept for callers that read them at
        // transition time, but the live source of truth is the separate
        // `livePosition` / `liveDuration` StateFlows. See push #35.
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val queueFilenames: List<String> = emptyList(),
        val queueIndex: Int = 0,
        /** True after cold-start prepare so the UI can show a "tap to resume" hint without auto-playing. */
        val preparedNotPlaying: Boolean = false,
        /** Media3 shuffle mode (random queue traversal vs sequential). */
        val shuffleEnabled: Boolean = false,
        /** Media3 repeat mode: 0=OFF, 1=ONE, 2=ALL. */
        val repeatMode: Int = androidx.media3.common.Player.REPEAT_MODE_OFF,
        /** Push #70: what kind of content the queue holds. Default LIBRARY. */
        val queueContext: QueueContext = QueueContext.LIBRARY,
        /**
         * Push #70: filenames inserted via "Play Next" that should survive
         * queue rebuilds (Songs-tab taps, refresh button, etc.). Entries
         * are auto-removed when the song actually plays.
         */
        val playNextFilenames: Set<String> = emptySet(),
    )

    private companion object {
        const val CMD_SET_QUEUE = "isaivazhi.playback.SET_QUEUE"
        const val CMD_APPEND_TO_QUEUE = "isaivazhi.playback.APPEND_TO_QUEUE"
        const val CMD_INSERT_AFTER_CURRENT = "isaivazhi.playback.INSERT_AFTER_CURRENT"
        const val CMD_INSERT_BEFORE_CURRENT_AND_PLAY =
            "isaivazhi.playback.INSERT_BEFORE_CURRENT_AND_PLAY"
        const val CMD_REPLACE_UPCOMING = "isaivazhi.playback.REPLACE_UPCOMING"
        const val CMD_PLAY_INDEX = "isaivazhi.playback.PLAY_INDEX"
        const val CMD_NEXT_TRACK = "isaivazhi.playback.NEXT_TRACK"
        const val CMD_PREV_TRACK = "isaivazhi.playback.PREV_TRACK"
        const val CMD_SET_LOOP_MODE = "isaivazhi.playback.SET_LOOP_MODE"
        const val CMD_UPDATE_NOTIFICATION_STATE = "isaivazhi.playback.UPDATE_NOTIFICATION_STATE"

        const val KEY_ITEMS_JSON = "itemsJson"
        const val KEY_START_INDEX = "startIndex"
        const val KEY_SEEK_TO_MS = "seekToMs"
        const val KEY_PLAY_WHEN_READY = "playWhenReady"
        const val KEY_FILE_PATH = "filePath"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_SONG_ID = "songId"
        const val KEY_INDEX = "index"
        const val KEY_ACTION = "action"
        const val KEY_LOOP_MODE = "loopMode"
    }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // High-churn position/duration emitted via separate StateFlows so the
    // 500 ms poll doesn't trigger a full AppRoot recomposition every tick.
    // Consumers that care about the seek bar (MiniPlayer slider, NowPlaying
    // scrub bar) collect these; everyone else only reads `state` which
    // updates infrequently. Fixes the post-#34 stall regression where the
    // 11 collectAsState() subscribers in MainActivity all re-emitted every
    // 500 ms on every poll tick.
    private val _livePosition = MutableStateFlow(0L)
    val livePosition: StateFlow<Long> = _livePosition.asStateFlow()
    private val _liveDuration = MutableStateFlow(0L)
    val liveDuration: StateFlow<Long> = _liveDuration.asStateFlow()

    /** False until cold-start playback recovery completes (sync or prepareForResume). */
    private val _controllerReady = MutableStateFlow(false)
    val controllerReady: StateFlow<Boolean> = _controllerReady.asStateFlow()

    fun markPlaybackRecoveryComplete() {
        _controllerReady.value = true
    }

    // 2026-06-01: emits when the lockscreen / notification Refresh-Up-Next
    // button is tapped. The UI process collects this and invokes the same
    // refreshUpcomingWithAI() lambda used by the in-app icons. Service-side
    // we forward the tap as EVT_MEDIA_ACTION action="refresh_queue" (see
    // controllerListener.onCustomCommand below).
    private val _refreshRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
    )
    val refreshRequests: kotlinx.coroutines.flow.SharedFlow<Unit> =
        _refreshRequests.asSharedFlow()

    // Phase 2 (2026-06-01): broadcast when refreshUpcomingWithAI() is in
    // flight. UI swaps the Refresh icon for a CircularProgressIndicator;
    // Media3PlaybackService rebuilds the notification CommandButton with
    // displayName "Refreshing…" + enabled=false (Phase 3) so the
    // lockscreen reflects the busy state. Single-writer (MainActivity);
    // safe to expose mutator publicly because no concurrent producer.
    private val _refreshBusy = MutableStateFlow(false)
    val refreshBusy: StateFlow<Boolean> = _refreshBusy.asStateFlow()
    fun setRefreshBusy(busy: Boolean) {
        _refreshBusy.value = busy
        // Phase 3 (2026-06-01): mirror the flag to the playback service so
        // the lockscreen Refresh CommandButton flips to "Refreshing…" +
        // disabled while a refresh is in flight. Fire-and-forget — if the
        // controller isn't connected yet the next buildNotificationButtons()
        // will pick the default (busy=false) anyway.
        val ctrl = controller ?: return
        val args = android.os.Bundle().apply {
            putBoolean(com.isaivazhi.app.PlaybackCommandContract.KEY_BUSY, busy)
        }
        runCatching {
            sendPlaybackCommand(
                ctrl,
                com.isaivazhi.app.PlaybackCommandContract.CMD_NOTIFICATION_SET_REFRESH_BUSY,
                args,
            )
        }
    }

    /**
     * Push #39: the played-ms accumulator now lives inside
     * `Media3PlaybackService.java` (carried over verbatim from the
     * Capacitor build — `accumulatedPlayedMs` + `noteProgressSample`).
     * Reading from the service via `Media3PlaybackService.INSTANCE`
     * means signal capture works even when:
     *   - the Activity is destroyed (app swiped from recents) but the
     *     foreground service keeps playing
     *   - the device is in Doze (the FOREGROUND_SERVICE_MEDIA_PLAYBACK
     *     type exempts the service from main-thread throttling)
     *   - the user backgrounds the app for long periods
     * The service's `onMediaItemTransition` (Java, before reset) stashes
     * the prev song's played-ms + duration into
     * `lastTransitionPrevPlayedMs` / `lastTransitionPrevDurationMs`, and
     * PlaybackEngine reads them when its own MediaController-side
     * listener fires.
     *
     * Push #38's `playedAccumulatorMs` / `preTransitionPlayedMs` /
     * `preTransitionDurationMs` fields removed — the service is now the
     * single source of truth.
     */
    private fun serviceRef(): Media3PlaybackService? = Media3PlaybackService.INSTANCE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerJob: Job? = null
    private var controller: MediaController? = null
    private var lastSavedPositionMs: Long = -1L

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // isPlaying = true only when audio is actively flowing. Use it to
            // confirm the engine's actual playback status; the user-intent
            // "is the user trying to play" comes from onPlayWhenReadyChanged.
            _state.value = _state.value.copy(isPlaying = isPlaying, preparedNotPlaying = false)
            activityLog?.log(
                category = "playback",
                type = if (isPlaying) "PLAY" else "PAUSE",
                message = "${_state.value.title.ifBlank { _state.value.currentMediaId ?: "(none)" }} — ${if (isPlaying) "playing" else "paused"}",
                data = mapOf(
                    "mediaId" to (_state.value.currentMediaId ?: ""),
                    "isPlaying" to isPlaying,
                ),
            )
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Reflects user intent — flips immediately on play()/pause() calls,
            // even during buffering. Drives the play/pause icon so it switches
            // the instant the user taps it instead of waiting for Media3 to
            // confirm via onIsPlayingChanged.
            _state.value = _state.value.copy(isPlaying = playWhenReady, preparedNotPlaying = false)
        }

        override fun onMediaMetadataChanged(metadata: MediaMetadata) {
            _state.value = _state.value.copy(
                title = metadata.title?.toString() ?: _state.value.title,
                artist = metadata.artist?.toString() ?: _state.value.artist,
                album = metadata.albumTitle?.toString() ?: _state.value.album,
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val md = mediaItem?.mediaMetadata
            val ctrl = controller
            val prevState = _state.value
            val transitionSource = pendingTransitionSource
            val prevMediaId = mediaIdToFilename(prevState.currentMediaId)
            val nextMediaId = mediaIdToFilename(mediaItem?.mediaId)
            // Push #39: read the prev song's played-ms + duration from
            // the service (Java) which captured them right before
            // resetPlayedProgress in its own onMediaItemTransition. The
            // service runs inside the foreground media service and
            // therefore keeps accumulating ms even when the Activity is
            // destroyed or the device is in Doze. Falls back to
            // _livePosition only when the service reference isn't
            // available (e.g. the very first transition before the
            // service has bound).
            val svc = serviceRef()
            val prevPlayed: Long
            val prevDuration: Long
            val origin: String
            if (svc != null && svc.lastTransitionAtMs > 0L) {
                prevPlayed = svc.lastTransitionPrevPlayedMs
                prevDuration = svc.lastTransitionPrevDurationMs
                origin = "service"
            } else {
                prevPlayed = _livePosition.value
                prevDuration = _liveDuration.value
                origin = "fallback"
            }
            if (prevMediaId != null && prevMediaId != nextMediaId) {
                val frac = if (prevDuration > 0) (prevPlayed.toFloat() / prevDuration.toFloat()).coerceIn(0f, 1f) else 0f
                // Push #76: read the PREV song's playbackInstanceId from the
                // dedicated snapshot field. Until #76 we read
                // getCurrentPlaybackInstanceId() which the service had
                // already bumped to the NEW song's id by the time this
                // listener fired — that caused saveIngestWatermark to track
                // the NEW id, and the cold-start drainTransitionsBuffer
                // filter (prev > watermark) silently dropped every
                // background auto-advance entry because the buffer stored
                // PREV ids that always sat at-or-below the inflated
                // watermark. Use the explicit prev field now.
                val transitionInstId = svc?.let {
                    runCatching { svc.javaClass.getMethod("getLastTransitionPrevPlaybackInstanceId").invoke(svc) as? Long }
                        .getOrNull() ?: 0L
                } ?: 0L
                android.util.Log.i(
                    "PlaybackEngine",
                    "transition: prev=$prevMediaId played=${prevPlayed}ms dur=${prevDuration}ms frac=$frac source=$pendingTransitionSource origin=$origin instId=$transitionInstId → next=$nextMediaId rawNext=${mediaItem?.mediaId}"
                )
                history?.recordEnd(prevMediaId, frac)
                // Feed the taste signal pipeline. Snapshot before/after and
                // append to the timeline so the user can audit each event.
                val source = transitionSource
                val t = taste
                if (t != null) {
                    val (before, after) = t.recordPlaybackEvent(prevMediaId, frac, source, transitionInstId)
                    val isSkip = frac < TasteEngine.SKIP_THRESHOLD
                    val isManual = source.startsWith("manual_") || source == "song_tap" ||
                        source == "queue_tap" || source == "neutral_skip"
                    // Push #63 + #67: session counters before/after, with
                    // filename + source so the listened rolling list grows.
                    val sessionPair = session?.recordEvent(
                        fraction = frac,
                        isSkip = isSkip,
                        isManual = isManual,
                        filename = prevMediaId,
                        source = source,
                    )
                    activityLog?.log(
                        category = "playback",
                        type = if (isSkip) "SKIP" else "LISTEN",
                        message = "${prevState.title.ifBlank { prevMediaId }} — ${(frac * 100).toInt()}% via $source",
                        data = mapOf(
                            "mediaId" to prevMediaId,
                            "fraction" to frac,
                            // Push #75: include the raw played/duration/origin
                            // so cases like "FLUSH wrote 76% but transition
                            // recorded 0%" are debuggable from the in-app log.
                            // Origin = "service" means the value came from the
                            // service's transition snapshot; "fallback" means
                            // we used the Kotlin _livePosition (usually 0
                            // because skipNext resets it before the seek).
                            "prevPlayedMs" to prevPlayed,
                            "prevDurationMs" to prevDuration,
                            "origin" to origin,
                            "source" to source,
                            "instId" to transitionInstId,
                            "directBefore" to before.directScore,
                            "directAfter" to after.directScore,
                        ),
                    )
                    signalTimeline?.let { tl ->
                        val cls = if (isSkip) SignalTimelineEngine.Classification.SKIP
                            else SignalTimelineEngine.Classification.LISTEN
                        val avgLib = avgLibraryFraction()
                        val sessionPull = (t.tuning.value.sessionBias).coerceIn(0f, 1f)
                        val sBefore = sessionPair?.first
                        val sAfter = sessionPair?.second
                        tl.append(
                            SignalTimelineEngine.Event(
                                timestamp = System.currentTimeMillis(),
                                filename = prevMediaId,
                                title = prevState.title,
                                artist = prevState.artist,
                                source = source,
                                fraction = frac,
                                classification = cls,
                                tasteBefore = snapshotOf(before),
                                tasteAfter = snapshotOf(after),
                                xScoreBefore = before.xScore,
                                xScoreAfter = after.xScore,
                                sessionPullBefore = sessionPull,
                                sessionPullAfter = sessionPull,
                                libraryAvgBefore = avgLib,
                                libraryAvgAfter = avgLib,
                                sessionEncountersBefore = sBefore?.encounters ?: 0,
                                sessionEncountersAfter = sAfter?.encounters ?: 0,
                                sessionSkipsBefore = sBefore?.skips ?: 0,
                                sessionSkipsAfter = sAfter?.skips ?: 0,
                                sessionPositivesBefore = sBefore?.positives ?: 0,
                                sessionPositivesAfter = sAfter?.positives ?: 0,
                            )
                        )
                    }
                    // Push #66: this transition is authoritative for this
                    // playback session. Clear any pending listen snapshot
                    // that matches the just-ended instanceId so cold-start
                    // reconciliation doesn't re-record this listen.
                    // Push #74: also bump the ingest watermark so the cold-start
                    // transitions-buffer replay skips this entry next time.
                    if (transitionInstId > 0L) {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                val pend = preferences.loadPendingEvidence()
                                if (pend != null && pend.playbackInstanceId == transitionInstId) {
                                    preferences.clearPendingEvidence()
                                    android.util.Log.i(
                                        "PlaybackEngine",
                                        "cleared pending snapshot resolved by transition instId=$transitionInstId"
                                    )
                                }
                                preferences.saveIngestWatermark(transitionInstId)
                            }
                        }
                    }
                    // Push #63: propagate the resulting directScore delta to
                    // the song's top-10 embedding neighbors. Capacitor parity
                    // (engine.js:535).
                    val delta = after.directScore - before.directScore
                    if (kotlin.math.abs(delta) > 0.001f) {
                        scope.launch {
                            t.propagateSimilarityBoost(
                                sourceFilename = prevMediaId,
                                scoreDelta = delta,
                                reason = if (isSkip) "playback_skip" else "playback_complete",
                            )
                        }
                    }
                }
                // Reset to auto for the next implicit transition.
                pendingTransitionSource = "auto_advance"
            }
            // Push #70: if the song transitioning IN is in the Play Next
            // marker set, clear its marker — it's about to start playing,
            // no longer needs the "preserve through rebuilds" badge.
            val newMediaId = nextMediaId
            val updatedPlayNext = if (newMediaId != null && newMediaId in prevState.playNextFilenames) {
                android.util.Log.i("QueueOp", "playNext cleared on transition: $newMediaId")
                prevState.playNextFilenames - newMediaId
            } else {
                prevState.playNextFilenames
            }
            val newQueueIndex = ctrl?.currentMediaItemIndex ?: prevState.queueIndex
            _state.value = prevState.copy(
                currentMediaId = newMediaId,
                title = md?.title?.toString() ?: "",
                artist = md?.artist?.toString() ?: "",
                album = md?.albumTitle?.toString() ?: "",
                queueIndex = newQueueIndex,
                playNextFilenames = updatedPlayNext,
            )
            // Controller reconnect fires onMediaItemTransition for the current
            // item — same mediaId, not a real track change. Refresh position
            // from the service instead of resetting to 0 (seek bar flicker).
            if (prevMediaId != null && prevMediaId == nextMediaId) {
                val svc = serviceRef()
                val pos = svc?.getCurrentPositionMsSnapshot()
                    ?: ctrl?.currentPosition?.coerceAtLeast(0L)
                    ?: _livePosition.value
                val dur = svc?.getCurrentDurationMsSnapshot()
                    ?: ctrl?.duration?.takeIf { it > 0 }
                    ?: _liveDuration.value
                _livePosition.value = pos
                _liveDuration.value = dur
                return
            }
            if (!newMediaId.isNullOrBlank() && prevMediaId != newMediaId) {
                val tracker = recentlySurfacedTracker
                if (tracker != null &&
                    shouldRecordRecommendationCooldown(newMediaId, prevState, transitionSource)
                ) {
                    tracker.record(listOf(newMediaId))
                    activityLog?.log(
                        category = "queue",
                        type = "COOLDOWN_ENTER",
                        message = "$newMediaId entered recommendation cooldown",
                        data = mapOf(
                            "mediaId" to newMediaId,
                            "source" to transitionSource,
                            "ctx" to prevState.queueContext.name,
                        ),
                    )
                }
            }
            // Reset live position/duration for a new track. duration may be
            // unknown until Media3 reports it via onMediaMetadataChanged or
            // the next poll tick.
            _livePosition.value = 0L
            _liveDuration.value = ctrl?.duration?.takeIf { it > 0 } ?: 0L
            newMediaId?.let {
                history?.recordStart(it)
                persistCurrent(it, 0L)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            _livePosition.value = newPosition.positionMs.coerceAtLeast(0L)
            // Push #74/#75: log user-initiated seeks only. AUTO_TRANSITION
            // and INTERNAL discontinuities flood otherwise. Also require the
            // discontinuity to STAY within the same media item — Media3 fires
            // DISCONTINUITY_REASON_SEEK for seekToNextMediaItem too (it's a
            // seek-across-items), which would otherwise log a false "seek to
            // 0ms on <old song>" entry alongside every skip-next tap.
            if (reason == Player.DISCONTINUITY_REASON_SEEK &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                activityLog?.log(
                    category = "playback",
                    type = "SEEK",
                    message = "${_state.value.title.ifBlank { _state.value.currentMediaId ?: "(none)" }} — seek to ${newPosition.positionMs}ms",
                    data = mapOf(
                        "mediaId" to (_state.value.currentMediaId ?: ""),
                        "fromMs" to oldPosition.positionMs,
                        "toMs" to newPosition.positionMs,
                    ),
                )
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Kotlin-side shuffle keeps native shuffle OFF; don't mirror
            // `false` back into UI state when we explicitly disable native mode.
            if (!shuffleModeEnabled) return
            _state.value = _state.value.copy(shuffleEnabled = shuffleModeEnabled)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.value = _state.value.copy(repeatMode = repeatMode)
        }
    }

    /**
     * Push #74: receives custom commands the service broadcasts to
     * controllers — most importantly EVT_MEDIA_ACTION, which fires when the
     * user taps Favorite or Close on the notification. Syncs the resulting
     * state into the Kotlin FavoritesEngine so the in-app heart icon
     * matches the notification's heart icon.
     */
    private val controllerListener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (command.customAction == PlaybackCommandContract.EVT_MEDIA_ACTION) {
                val action = args.getString("action", "")
                val filename = args.getString(PlaybackCommandContract.KEY_FILENAME, "")
                when (action) {
                    "favorite" -> {
                        val isFav = args.getBoolean("isFavorite", false)
                        if (filename.isNotBlank()) {
                            favorites?.setExplicit(filename, isFav)
                        }
                        activityLog?.log(
                            category = "notification",
                            type = if (isFav) "FAV+" else "FAV-",
                            message = "Notification favorite ${if (isFav) "added" else "removed"} for $filename",
                            data = mapOf("filename" to filename, "isFavorite" to isFav),
                        )
                    }
                    "dismiss" -> {
                        activityLog?.log(
                            category = "notification",
                            type = "CLOSE",
                            message = "Notification close tapped for $filename",
                            data = mapOf("filename" to filename),
                        )
                    }
                    "refresh_queue" -> {
                        // 2026-06-01: bridge service → UI for the lockscreen
                        // Refresh button. tryEmit is non-blocking and OK to
                        // drop overlapping taps (extraBufferCapacity=1).
                        _refreshRequests.tryEmit(Unit)
                        activityLog?.log(
                            category = "notification",
                            type = "REFRESH_QUEUE",
                            message = "Lockscreen Refresh Up Next tapped",
                            data = mapOf("filename" to filename),
                        )
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Lazily connect to the Media3 session. */
    suspend fun ensureController(): MediaController {
        controller?.let { return it }
        return withContext(Dispatchers.Main.immediate) {
            controller?.let { return@withContext it }
            val token = SessionToken(
                appContext,
                ComponentName(appContext, Media3PlaybackService::class.java)
            )
            val ctrl = suspendCancellableCoroutine<MediaController> { cont ->
                // Push #74: setListener supplies the MediaController.Listener
                // (separate interface from Player.Listener). Required for
                // onCustomCommand to fire when the service emits
                // EVT_MEDIA_ACTION on notification button taps.
                val future = MediaController.Builder(appContext, token)
                    .setListener(controllerListener)
                    .buildAsync()
                future.addListener({
                    try {
                        val c = future.get()
                        cont.resume(c)
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                }, { it.run() })
            }
            ctrl.addListener(playerListener)
            controller = ctrl
            hydrateLiveStateFromService(ctrl)
            startPositionPoll()
            ctrl
        }
    }

    /** Seed position/duration/play state from the durable service snapshot. */
    private fun hydrateLiveStateFromService(ctrl: MediaController) {
        val svc = serviceRef()
        val pos = svc?.getCurrentPositionMsSnapshot()
            ?: runCatching { ctrl.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
        val dur = svc?.getCurrentDurationMsSnapshot()
            ?: runCatching { ctrl.duration.takeIf { it > 0 } ?: 0L }.getOrDefault(0L)
        val playWhenReady = runCatching { ctrl.playWhenReady }.getOrDefault(false)
        val svcMediaId = svc?.getCurrentMediaIdSnapshot()?.takeIf { it.isNotBlank() }
        val ctrlMediaId = mediaIdToFilename(ctrl.currentMediaItem?.mediaId)
        val mediaId = svcMediaId ?: ctrlMediaId
        if (pos > 0L || dur > 0L) {
            _livePosition.value = pos
            _liveDuration.value = dur
        }
        if (mediaId != null || playWhenReady) {
            _state.value = _state.value.copy(
                currentMediaId = mediaId ?: _state.value.currentMediaId,
                isPlaying = playWhenReady,
                preparedNotPlaying = !playWhenReady,
            )
        }
    }

    private fun sendPlaybackCommand(
        ctrl: MediaController,
        action: String,
        args: Bundle = Bundle.EMPTY,
    ) {
        ctrl.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    private fun songsToJsonArray(songs: List<Song>): JSONArray {
        val arr = JSONArray()
        for (song in songs) {
            val path = song.filePath ?: continue
            if (path.isBlank()) continue
            arr.put(
                JSONObject()
                    .put(KEY_SONG_ID, song.id)
                    .put(KEY_FILE_PATH, path)
                    .put(KEY_TITLE, song.title)
                    .put(KEY_ARTIST, song.artist)
                    .put(KEY_ALBUM, song.album)
            )
        }
        return arr
    }

    private fun queueCommandArgs(
        songs: List<Song>,
        startIndex: Int = 0,
        seekToMs: Long = 0L,
        playWhenReady: Boolean = true,
    ): Bundle {
        return Bundle().apply {
            putString(KEY_ITEMS_JSON, songsToJsonArray(songs).toString())
            putInt(KEY_START_INDEX, startIndex)
            putLong(KEY_SEEK_TO_MS, seekToMs.coerceAtLeast(0L))
            putBoolean(KEY_PLAY_WHEN_READY, playWhenReady)
        }
    }

    private fun mediaIdToFilename(mediaId: String?): String? {
        if (mediaId.isNullOrBlank()) return null
        val value = mediaId.substringAfter("::", mediaId)
        return File(value).name.ifBlank { mediaId }
    }

    /**
     * Plays a queue starting from `startIndex`. Replaces any existing queue.
     * Songs with null/blank filePath are filtered out before submission.
     */
    fun playQueue(
        songs: List<Song>,
        startIndex: Int = 0,
        source: String = "manual_tap",
        queueContext: QueueContext = QueueContext.LIBRARY,
    ) {
        pendingTransitionSource = source
        // Push #69: if the queue is being REPLACED while another song is
        // currently playing, flush evidence for the displaced previous
        // song as a pending snapshot so the listen isn't lost.
        val prevMediaId = _state.value.currentMediaId
        val replacingDifferent = prevMediaId != null && prevMediaId != songs.getOrNull(startIndex)?.filename
        if (replacingDifferent) {
            val svc = serviceRef()
            if (svc != null) {
                val played = svc.getAccumulatedPlayedMsSnapshot()
                val dur = svc.getCurrentDurationMsSnapshot()
                val instId = svc.getCurrentPlaybackInstanceId()
                if (played >= 1000L && dur > 0L) {
                    scope.launch(Dispatchers.IO) {
                        runCatching {
                            preferences.savePendingEvidence(prevMediaId!!, played, dur, instId)
                            android.util.Log.i(
                                "QueueOp",
                                "playQueue replace flush: prev=$prevMediaId played=${played}ms dur=${dur}ms instId=$instId"
                            )
                        }
                    }
                }
            }
        }
        // Push #39: the service captures its own pre-transition snapshot
        // in Media3PlaybackService.onMediaItemTransition. No Kotlin-side
        // snapshot needed.
        val playable = songs.filter { !it.filePath.isNullOrEmpty() }
        if (playable.isEmpty()) {
            android.util.Log.i("QueueOp", "playQueue ABORT: no playable songs (input=${songs.size})")
            return
        }
        val safeStart = startIndex.coerceIn(0, playable.size - 1)
        // Push #70: Play Next songs survive queue rebuilds. Filter them
        // from `playable` to avoid duplicates, then re-insert right after
        // the starting song so they play before anything else in the new
        // queue.
        val playNextSet = _state.value.playNextFilenames
        val playNextSongs = if (playNextSet.isEmpty()) emptyList() else {
            playable.filter { it.filename in playNextSet }
                .distinctBy { it.filename }
        }
        val rebuilt: List<Song> = if (playNextSongs.isEmpty()) {
            playable
        } else {
            val playNextFns = playNextSongs.map { it.filename }.toHashSet()
            val withoutPlayNext = playable.filterNot { it.filename in playNextFns }
            if (withoutPlayNext.isEmpty()) {
                // All incoming songs are Play Next markers (e.g. tap same
                // similar song after Play Next). coerceIn(0, -1) would crash.
                android.util.Log.i(
                    "QueueOp",
                    "playQueue: all playable songs are Play Next markers; using playable as-is"
                )
                playable
            } else {
                val safeStart2 = startIndex.coerceIn(0, withoutPlayNext.size - 1)
                val before = withoutPlayNext.subList(0, safeStart2 + 1)
                val after = withoutPlayNext.subList(safeStart2 + 1, withoutPlayNext.size)
                before + playNextSongs + after
            }
        }
        val finalSafeStart = if (playNextSongs.isEmpty()) safeStart
        else rebuilt.indexOfFirst { it.filename == playable[safeStart].filename }.coerceAtLeast(0)
        android.util.Log.i(
            "QueueOp",
            "playQueue ctx=$queueContext src=$source input=${songs.size} playable=${playable.size} " +
                "playNextPreserved=${playNextSongs.size} startIndex=$startIndex→$finalSafeStart first=${rebuilt.getOrNull(finalSafeStart)?.filename}"
        )
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            sendPlaybackCommand(
                ctrl,
                CMD_SET_QUEUE,
                queueCommandArgs(
                    songs = rebuilt,
                    startIndex = finalSafeStart,
                    playWhenReady = true,
                ),
            )
            val first = rebuilt[finalSafeStart]
            _state.value = _state.value.copy(
                currentSongId = first.id,
                currentMediaId = first.filename,
                title = first.title,
                artist = first.artist,
                album = first.album,
                isPlaying = true,
                queueFilenames = rebuilt.map { it.filename },
                queueIndex = finalSafeStart,
                preparedNotPlaying = false,
                queueContext = queueContext,
            )
            _livePosition.value = 0L
            _liveDuration.value = 0L
            persistQueue(rebuilt.map { it.filename }, finalSafeStart)
            persistCurrent(first.filename, 0L)
        }
    }

    /** Clears a Play Next marker so an explicit tap can rebuild the queue safely. */
    fun clearPlayNextMarker(filename: String) {
        val cur = _state.value
        if (filename !in cur.playNextFilenames) return
        _state.value = cur.copy(playNextFilenames = cur.playNextFilenames - filename)
    }

    /** Plays a single song. Convenience wrapper that builds a 1-item queue. */
    fun play(song: Song) {
        clearPlayNextMarker(song.filename)
        playQueue(listOf(song), 0)
    }

    /**
     * Cold-start path: prepares a queue without auto-playing. The next user
     * tap on the play button calls `controller.play()` which lands near
     * instantly (Media3 already has the source prepared). Mirrors the
     * critical-state restore pattern that fixed cold-start tap-to-audio in
     * the Capacitor build (batches #11–#18) but achieves it from Kotlin
     * synchronously instead of via SharedPreferences cache tricks.
     */
    /**
     * Push #71: sync Kotlin `_state.value` from the controller's live
     * state (current MediaItem, queue items, index, position). Called
     * by MainActivity at cold start when the service is alive with an
     * active session — avoids the prepareForResume destructive replace
     * that was clobbering background headphone-skip advances.
     *
     * Looks up Song objects from the library to populate metadata fields.
     */
    suspend fun syncStateFromController(library: List<Song>) {
        val ctrl = runCatching { ensureController() }.getOrNull() ?: return
        val byFilename = library.associateBy { it.filename }
        val total = ctrl.mediaItemCount
        if (total <= 0) {
            android.util.Log.i("PlaybackEngine", "syncStateFromController: controller has no items")
            return
        }
        val curIdx = ctrl.currentMediaItemIndex.coerceIn(0, total - 1)
        val curItem = ctrl.currentMediaItem
        val curMediaId = mediaIdToFilename(curItem?.mediaId)
        val filenames = (0 until total).mapNotNull {
            runCatching { mediaIdToFilename(ctrl.getMediaItemAt(it).mediaId) }.getOrNull()
        }
        val song = curMediaId?.let { byFilename[it] }
        val durationMs = runCatching { ctrl.duration.takeIf { it > 0 } ?: 0L }.getOrDefault(0L)
        val positionMs = runCatching { ctrl.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
        val isPlayingNow = runCatching { ctrl.isPlaying }.getOrDefault(false)
        val repeatModeNow = runCatching { ctrl.repeatMode }.getOrDefault(androidx.media3.common.Player.REPEAT_MODE_OFF)
        val shuffleOnNow = runCatching { ctrl.shuffleModeEnabled }.getOrDefault(false)
        _state.value = _state.value.copy(
            currentSongId = song?.id,
            currentMediaId = curMediaId,
            title = song?.title ?: curItem?.mediaMetadata?.title?.toString() ?: "",
            artist = song?.artist ?: curItem?.mediaMetadata?.artist?.toString() ?: "",
            album = song?.album ?: curItem?.mediaMetadata?.albumTitle?.toString() ?: "",
            isPlaying = isPlayingNow,
            queueFilenames = filenames,
            queueIndex = curIdx,
            preparedNotPlaying = !isPlayingNow,
            shuffleEnabled = shuffleOnNow,
            repeatMode = repeatModeNow,
        )
        _livePosition.value = positionMs
        _liveDuration.value = durationMs
        android.util.Log.i(
            "PlaybackEngine",
            "syncStateFromController: mediaId=$curMediaId idx=$curIdx/$total pos=${positionMs}ms isPlaying=$isPlayingNow repeat=$repeatModeNow"
        )
        // Persist the fresh values so the next true cold-start sees
        // accurate state if the service later dies.
        curMediaId?.let { persistCurrent(it, positionMs) }
        persistQueue(filenames, curIdx)
        // Push #75: prime HistoryEngine's pendingStartFilename so the
        // eventual transition fires a successful recordEnd. Without this,
        // when the Activity cold-starts onto an already-playing song
        // (no onMediaItemTransition fires for the current item),
        // recordEnd later gets rejected by the stale-event guard and the
        // song never appears in Recently Played.
        curMediaId?.let { history?.recordStart(it) }
        _controllerReady.value = true
    }

    fun prepareForResume(songs: List<Song>, startIndex: Int, seekToMs: Long) {
        val playable = songs.filter { !it.filePath.isNullOrEmpty() }
        if (playable.isEmpty()) return
        val safeStart = startIndex.coerceIn(0, playable.size - 1)
        // Push #65: log the seek target + first item filename so we can
        // confirm cross-session position-resume is actually happening.
        // If logs show seekToMs > 0 but the user reports playback starts
        // at 0, the issue is downstream of this call (Media3 setMediaItems
        // not honoring the position, or another seek racing it).
        android.util.Log.i(
            "PlaybackEngine",
            "prepareForResume queueSize=${playable.size} safeStart=$safeStart seekToMs=$seekToMs first=${playable.getOrNull(safeStart)?.filename ?: "?"}"
        )
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val targetSong = playable[safeStart]
            val targetMediaId = targetSong.filename
            val currentMediaId = mediaIdToFilename(ctrl.currentMediaItem?.mediaId)
            val safeSeek = seekToMs.coerceAtLeast(0L)
            if (ctrl.mediaItemCount > 0 && currentMediaId == targetMediaId) {
                val currentPos = runCatching { ctrl.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
                if (kotlin.math.abs(currentPos - safeSeek) > 2_000L) {
                    ctrl.seekTo(safeSeek)
                    _livePosition.value = safeSeek
                }
                _state.value = _state.value.copy(
                    currentSongId = targetSong.id,
                    currentMediaId = targetMediaId,
                    title = targetSong.title,
                    artist = targetSong.artist,
                    album = targetSong.album,
                    isPlaying = runCatching { ctrl.playWhenReady }.getOrDefault(false),
                    queueFilenames = playable.map { it.filename },
                    queueIndex = safeStart,
                    preparedNotPlaying = !runCatching { ctrl.playWhenReady }.getOrDefault(false),
                )
                android.util.Log.i(
                    "PlaybackEngine",
                    "prepareForResume: skipped SET_QUEUE — controller already has $targetMediaId at ${currentPos}ms"
                )
                _controllerReady.value = true
                return@launch
            }
            sendPlaybackCommand(
                ctrl,
                CMD_SET_QUEUE,
                queueCommandArgs(
                    songs = playable,
                    startIndex = safeStart,
                    seekToMs = safeSeek,
                    playWhenReady = false,
                ),
            )
            _state.value = _state.value.copy(
                currentSongId = targetSong.id,
                currentMediaId = targetMediaId,
                title = targetSong.title,
                artist = targetSong.artist,
                album = targetSong.album,
                isPlaying = false,
                queueFilenames = playable.map { it.filename },
                queueIndex = safeStart,
                preparedNotPlaying = true,
            )
            _livePosition.value = safeSeek
            _liveDuration.value = 0L
            _controllerReady.value = true
        }
    }

    fun togglePause() {
        if (!_controllerReady.value) return
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val nowPlaying = runCatching { ctrl.playWhenReady }.getOrDefault(_state.value.isPlaying)
            val nextPlaying = !nowPlaying
            _state.value = _state.value.copy(isPlaying = nextPlaying, preparedNotPlaying = false)
            if (nowPlaying) ctrl.pause() else ctrl.play()
        }
    }

    private fun snapshotOf(s: TasteEngine.TasteSignal): SignalTimelineEngine.Snapshot =
        SignalTimelineEngine.Snapshot(
            score = s.directScore,
            direct = s.directScore,
            similarity = s.similarityBoost,
            plays = s.plays,
            skips = s.skips,
            avgFraction = s.avgFraction,
        )

    private fun avgLibraryFraction(): Float {
        val sigs = taste?.signals?.value ?: return 0f
        if (sigs.isEmpty()) return 0f
        return sigs.values.map { it.avgFraction }.average().toFloat()
    }

    /**
     * Advance to the next track in the queue.
     *
     * @param neutral When true, marks the transition with source `neutral_skip`
     *   so TasteEngine treats it like a manual mood-change skip — the previous
     *   song's plays/skips count nudges but no X-score penalty stacks. Used by
     *   the mini-player neutral-skip button (gray skip) which differs from the
     *   thumbs-down dislike action that DOES penalize.
     */
    fun skipNext(neutral: Boolean = false) {
        pendingTransitionSource = if (neutral) "neutral_skip" else "next_button"
        // Push #39: service-side snapshot replaces the Kotlin-side
        // pre-transition fields.
        // Optimistic queue-index bump so the upcoming list re-highlights
        // without waiting for the IPC round-trip.
        val cur = _state.value
        if (cur.queueIndex < cur.queueFilenames.size - 1) {
            _state.value = cur.copy(queueIndex = cur.queueIndex + 1)
        }
        _livePosition.value = 0L
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            if (ctrl.hasNextMediaItem()) {
                sendPlaybackCommand(
                    ctrl,
                    CMD_NEXT_TRACK,
                    Bundle().apply {
                        putString(KEY_ACTION, if (neutral) "neutral_skip" else "next_button")
                    },
                )
            }
        }
    }

    fun skipPrev() {
        pendingTransitionSource = "prev_button"
        val cur = _state.value
        val livePos = _livePosition.value
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val curIdx = ctrl.currentMediaItemIndex.coerceAtLeast(0)
            val atQueueHead = curIdx <= 0 && livePos <= 3000L
            val curFilename = cur.currentMediaId
            if (atQueueHead && !curFilename.isNullOrBlank()) {
                val prevFn = history?.mostRecentListenBefore(curFilename)
                val prevSong = prevFn?.let { fn -> resolveSongByFilename?.invoke(fn) }
                if (prevSong != null && !prevSong.filePath.isNullOrEmpty()) {
                    sendPlaybackCommand(
                        ctrl,
                        CMD_INSERT_BEFORE_CURRENT_AND_PLAY,
                        queueCommandArgs(listOf(prevSong)),
                    )
                    val insertAt = curIdx.coerceAtMost(cur.queueFilenames.size)
                    val nextFilenames = cur.queueFilenames.toMutableList().also {
                        it.add(insertAt, prevSong.filename)
                    }
                    _state.value = cur.copy(
                        queueFilenames = nextFilenames,
                        queueIndex = insertAt,
                        currentMediaId = prevSong.filename,
                        title = prevSong.title,
                        artist = prevSong.artist,
                        album = prevSong.album,
                    )
                    _livePosition.value = 0L
                    persistQueue(nextFilenames, insertAt)
                    android.util.Log.i(
                        "QueueOp",
                        "skipPrev history: inserted ${prevSong.filename} at $insertAt",
                    )
                    return@launch
                }
            }
            sendPlaybackCommand(ctrl, CMD_PREV_TRACK)
        }
        if (livePos <= 3000L && cur.queueIndex > 0) {
            _state.value = cur.copy(queueIndex = cur.queueIndex - 1)
        }
        _livePosition.value = 0L
    }

    fun seekTo(ms: Long) {
        // Optimistic position update so the progress bar snaps immediately.
        _livePosition.value = ms.coerceAtLeast(0L)
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            ctrl.seekTo(ms.coerceAtLeast(0L))
        }
    }

    /** Jump to a specific index in the current queue. */
    fun playAtIndex(index: Int) {
        pendingTransitionSource = "queue_tap"
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val count = ctrl.mediaItemCount
            if (count <= 0 || index < 0 || index >= count) return@launch
            sendPlaybackCommand(ctrl, CMD_PLAY_INDEX, Bundle().apply { putInt(KEY_INDEX, index) })
        }
    }

    /**
     * Push #42 Tier 2K: Kotlin-side shuffle.
     *
     * The previous implementation flipped `Media3.shuffleModeEnabled = true`
     * which makes Media3 pick a shuffled successor on every advance, but
     * the visible Up Next list (rendered from `queueFilenames` linearly)
     * never reorders. Tap Next → Media3 picks a random next; UI shows a
     * different "next" → user perceives this as "random song bug".
     *
     * Fix: shuffle the upcoming tail via `replaceUpcoming` so the service
     * queue, ExoPlayer playlist, and Kotlin `queueFilenames` all share the
     * same order. Keep `Media3.shuffleModeEnabled = false`.
     * After toggling on, the visible Up Next IS the playback order. Tap
     * Next → Media3 advances linearly → plays whatever is shown as next.
     *
     * Going off does NOT re-sort — that would require remembering the
     * pre-shuffle order, which we don't (the queue is conceptually a
     * recommender-generated tail anyway, not a fixed playlist). The user
     * can hit Up Next → Refresh to rebuild.
     */
    fun toggleShuffle() {
        val next = !_state.value.shuffleEnabled
        _state.value = _state.value.copy(shuffleEnabled = next)
        android.util.Log.i("QueueOp", "toggleShuffle → $next (ctx=${_state.value.queueContext})")
        toaster?.show(if (next) "Shuffle on — queue randomized" else "Shuffle off")
        if (!next) return  // off: no structural change (matches Capacitor)
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            // Ensure Media3 native shuffle is OFF — we manage shuffle ourselves
            // so the visible Up Next list IS the playback order.
            ctrl.shuffleModeEnabled = false
            val curState = _state.value
            val curIdx = ctrl.currentMediaItemIndex
            val total = ctrl.mediaItemCount
            val curListBefore = curState.queueFilenames
            // Tail = positions [curIdx+1 .. total-1]. Need ≥ 2 to shuffle.
            if (curIdx < 0 || total - (curIdx + 1) < 2) return@launch
            val tailStart = curIdx + 1
            val tailFilenames = when {
                curListBefore.size == total -> curListBefore.subList(tailStart, total)
                else -> readControllerFilenames(ctrl).drop(tailStart)
            }
            if (tailFilenames.size < 2) return@launch
            val shuffledSongs = tailFilenames.shuffled().mapNotNull { fn ->
                resolveSongByFilename?.invoke(fn)
            }.filter { !it.filePath.isNullOrEmpty() }
            if (shuffledSongs.size < 2) return@launch
            // Rebuild via replaceUpcoming so the service queue, ExoPlayer, and
            // Kotlin queueFilenames all share the same shuffled tail order.
            // The old moveMediaItem+perm path desynced the UI from playback.
            val curFilename = curState.currentMediaId
            val seen = HashSet<String>()
            curFilename?.let { seen += it }
            val nextSongs = shuffledSongs.filter { seen.add(it.filename) }
            sendPlaybackCommand(ctrl, CMD_REPLACE_UPCOMING, queueCommandArgs(nextSongs))
            val nextFilenames = buildList {
                if (curFilename != null) add(curFilename)
                addAll(nextSongs.map { it.filename })
            }
            syncQueueStateAfterReplace(
                ctrl, curState, nextFilenames, curFilename, curState.queueContext,
            )
            android.util.Log.i(
                "PlaybackEngine",
                "shuffle: tail randomized via replaceUpcoming (${tailFilenames.size} songs)",
            )
        }
    }

    private fun readControllerFilenames(ctrl: MediaController): List<String> {
        val total = ctrl.mediaItemCount
        if (total <= 0) return emptyList()
        return (0 until total).mapNotNull { i ->
            runCatching { mediaIdToFilename(ctrl.getMediaItemAt(i).mediaId) }.getOrNull()
        }
    }

    /**
     * Phase D (2026-06-01): explicitly set repeat mode. Used by
     * playFromTap / playFromSection to silently force LOOP=OFF whenever
     * the user starts an AI-eligible queue, so the queue-exhaust LE can
     * append fresh recommendations. Without this, a user who previously
     * set LOOP=ALL would forever loop the same ~10 songs.
     * Silent (no toast): user did not explicitly toggle.
     */
    fun setRepeatMode(mode: Int) {
        if (_state.value.repeatMode == mode) return
        _state.value = _state.value.copy(repeatMode = mode)
        android.util.Log.i("QueueOp", "setRepeatMode → $mode (silent)")
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            sendPlaybackCommand(ctrl, CMD_SET_LOOP_MODE, Bundle().apply { putInt(KEY_LOOP_MODE, mode) })
        }
    }

    /** Cycle repeat mode: OFF → ALL → ONE → OFF. */
    fun cycleRepeat() {
        val cur = _state.value.repeatMode
        val next = when (cur) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
            androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        _state.value = _state.value.copy(repeatMode = next)
        android.util.Log.i("QueueOp", "cycleRepeat $cur → $next (ctx=${_state.value.queueContext})")
        toaster?.show(when (next) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> "Loop off"
            androidx.media3.common.Player.REPEAT_MODE_ONE -> "Loop: repeat this song"
            else -> "Loop: repeat all"
        })
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            sendPlaybackCommand(ctrl, CMD_SET_LOOP_MODE, Bundle().apply { putInt(KEY_LOOP_MODE, next) })
        }
    }

    /**
     * Pre-warm the MediaController so the user's first tap doesn't pay the
     * Future.get() connect latency. Call once at app launch.
     */
    fun preWarm() {
        scope.launch { runCatching { ensureController() } }
        // Phase C (2026-06-01) optimistic MiniPlayer: read last persisted
        // song metadata BEFORE the controller round-trip completes, so the
        // MiniPlayer renders with title/artist/album within ~500ms of
        // launch instead of waiting ~1.9s for prepareForResume to populate
        // the Song lookup. Only writes when current state is still empty
        // (so we never clobber a real transition that beat us to it).
        scope.launch(Dispatchers.IO) {
            try {
                val snap = preferences.snapshot()
                val mediaId = snap.mediaId ?: return@launch
                val cur = _state.value
                if (cur.currentMediaId != null) return@launch  // real state already arrived
                val svc = serviceRef()
                val servicePlaying = svc?.let {
                    runCatching {
                        val instId = it.getCurrentPlaybackInstanceId()
                        instId > 0L && runCatching {
                            controller?.playWhenReady
                        }.getOrNull() == true
                    }.getOrDefault(false)
                } ?: false
                _state.value = cur.copy(
                    currentMediaId = mediaId,
                    title = snap.title,
                    artist = snap.artist,
                    album = snap.album,
                    queueFilenames = if (cur.queueFilenames.isEmpty()) snap.queueFilenames else cur.queueFilenames,
                    queueIndex = if (cur.queueFilenames.isEmpty()) snap.queueIndex else cur.queueIndex,
                    isPlaying = servicePlaying,
                    preparedNotPlaying = !servicePlaying,
                )
                _livePosition.value = snap.positionMs
            } catch (t: Throwable) {
                android.util.Log.w("PlaybackEngine", "optimistic hydrate failed: ${t.message}")
            }
        }
    }

    fun stop() {
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            ctrl.stop()
        }
    }

    /**
     * Move a queue item from `fromIndex` to `toIndex`. Wraps Media3's
     * `Player.moveMediaItem` and keeps the local `queueFilenames` state in
     * sync so the Up Next list re-renders immediately. Used by the drag-
     * to-reorder gesture in UpNextScreen (push #39). Indices are
     * queue-relative (0-based) and clamped to the current queue length.
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val count = ctrl.mediaItemCount
            if (count <= 1 || fromIndex !in 0 until count || toIndex !in 0 until count) return@launch
            ctrl.moveMediaItem(fromIndex, toIndex)
            val cur = _state.value
            val list = cur.queueFilenames.toMutableList()
            if (fromIndex in list.indices) {
                val item = list.removeAt(fromIndex)
                val clampedTo = toIndex.coerceIn(0, list.size)
                list.add(clampedTo, item)
            }
            val newCurIdx = ctrl.currentMediaItemIndex
            _state.value = cur.copy(queueFilenames = list, queueIndex = newCurIdx)
            persistQueue(list, newCurIdx)
        }
    }

    /**
     * Remove a single item from the Media3 queue (Up Next per-row × button).
     *
     * Push #63: also bumps the removed song's xScore by +0.5 and
     * propagates a small negative similarity boost to its 10 nearest
     * neighbors. Matches Capacitor `engine.js:1590` — treats a manual
     * queue removal as a mild "don't recommend me this OR similar" signal.
     */
    fun removeFromQueue(index: Int) {
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            if (index < 0 || index >= ctrl.mediaItemCount) return@launch
            val cur = _state.value
            val removedFilename = cur.queueFilenames.getOrNull(index)
            ctrl.removeMediaItem(index)
            val nextFilenames = cur.queueFilenames.toMutableList().also {
                if (index < it.size) it.removeAt(index)
            }
            val nextIdx = ctrl.currentMediaItemIndex
            // Push #70: if the removed song was a Play Next marker, drop
            // its entry so it doesn't get re-prepended on the next rebuild.
            val newPlayNext = if (removedFilename != null && removedFilename in cur.playNextFilenames) {
                cur.playNextFilenames - removedFilename
            } else cur.playNextFilenames
            _state.value = cur.copy(
                queueFilenames = nextFilenames,
                queueIndex = nextIdx,
                playNextFilenames = newPlayNext,
            )
            android.util.Log.i(
                "QueueOp",
                "removeFromQueue idx=$index removed=$removedFilename remaining=${nextFilenames.size} ctx=${cur.queueContext}"
            )
            persistQueue(nextFilenames, nextIdx)
            // Push #63: signal capture for queue-remove.
            val t = taste
            if (t != null && !removedFilename.isNullOrBlank()) {
                val (before, after) = t.bumpXScoreForQueueRemove(removedFilename)
                val delta = after.directScore - before.directScore
                if (kotlin.math.abs(delta) > 0.001f) {
                    t.propagateSimilarityBoost(removedFilename, delta, "queue_remove")
                }
                signalTimeline?.append(
                    SignalTimelineEngine.Event(
                        timestamp = System.currentTimeMillis(),
                        filename = removedFilename,
                        title = removedFilename,
                        artist = "",
                        source = "queue_remove",
                        fraction = 0f,
                        classification = SignalTimelineEngine.Classification.SKIP,
                        tasteBefore = snapshotOf(before),
                        tasteAfter = snapshotOf(after),
                        xScoreBefore = before.xScore,
                        xScoreAfter = after.xScore,
                        sessionPullBefore = t.tuning.value.sessionBias.coerceIn(0f, 1f),
                        sessionPullAfter = t.tuning.value.sessionBias.coerceIn(0f, 1f),
                        libraryAvgBefore = avgLibraryFraction(),
                        libraryAvgAfter = avgLibraryFraction(),
                    ),
                )
            }
        }
    }

    /**
     * Strip [filenames] from the upcoming queue (after the current song)
     * without rebuilding AI recommendations. Used when the user dislikes
     * a track that is still queued in Up Next.
     */
    fun removeFilenamesFromUpcoming(filenames: Set<String>) {
        if (filenames.isEmpty()) return
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val cur = _state.value
            val idx = cur.queueIndex.coerceAtLeast(0)
            val list = cur.queueFilenames
            if (list.size <= idx + 1) return@launch
            val toRemove = list.indices
                .filter { i -> i > idx && list[i] in filenames }
                .sortedDescending()
            if (toRemove.isEmpty()) return@launch
            for (i in toRemove) {
                if (i < ctrl.mediaItemCount) ctrl.removeMediaItem(i)
            }
            val nextFilenames = list.filterIndexed { i, fn -> i <= idx || fn !in filenames }
            val nextIdx = ctrl.currentMediaItemIndex
            _state.value = cur.copy(
                queueFilenames = nextFilenames,
                queueIndex = nextIdx,
                playNextFilenames = cur.playNextFilenames - filenames,
            )
            persistQueue(nextFilenames, nextIdx)
            android.util.Log.i(
                "QueueOp",
                "removeFilenamesFromUpcoming removed=${filenames.size} remaining=${nextFilenames.size}",
            )
        }
    }

    /**
     * Push #42 Tier 2G: append songs to the END of the queue without
     * disturbing the current song or upcoming order. Called by the
     * queue-exhaust recommender LaunchedEffect when the last song of a
     * section starts playing and REPEAT_OFF is set, so playback
     * seamlessly continues into AI recommendations.
     */
    fun appendToQueue(songs: List<Song>) {
        val playable = songs.filter { !it.filePath.isNullOrEmpty() }
        if (playable.isEmpty()) return
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            // De-dupe against everything already in Kotlin queueFilenames.
            val have = _state.value.queueFilenames.toSet()
            val toAdd = playable.filter { it.filename !in have }
            if (toAdd.isEmpty()) {
                android.util.Log.i("QueueOp", "appendToQueue SKIP: all ${playable.size} already in queue")
                return@launch
            }
            sendPlaybackCommand(ctrl, CMD_APPEND_TO_QUEUE, queueCommandArgs(toAdd))
            val cur = _state.value
            val nextFilenames = cur.queueFilenames + toAdd.map { it.filename }
            // Push #70: AI tail append flips context to AI_RECOMMENDED so
            // future queue-exhausts know the queue was already extended
            // by the recommender. Keeps the ALBUM/BROWSE skip-AI rule
            // intact for sections that haven't been AI-extended.
            val newCtx = when (cur.queueContext) {
                QueueContext.LIBRARY, QueueContext.DISCOVER_SECTION -> QueueContext.AI_RECOMMENDED
                else -> cur.queueContext  // ALBUM / BROWSE_SECTION / AI_RECOMMENDED keep their context
            }
            _state.value = cur.copy(queueFilenames = nextFilenames, queueContext = newCtx)
            persistQueue(nextFilenames, cur.queueIndex)
            android.util.Log.i(
                "QueueOp",
                "appendToQueue: added ${toAdd.size} (deduped from ${playable.size}); total=${nextFilenames.size} ctx=${cur.queueContext}→$newCtx"
            )
        }
    }

    /**
     * Insert a song right after the current track ("Play Next" action).
     * Push #70: tracks the filename in `playNextFilenames` so the song
     * survives queue rebuilds (Songs-tab taps, refresh button, etc.).
     * The marker auto-clears when the song actually transitions to play.
     */
    fun playNext(song: Song) {
        if (song.filePath.isNullOrEmpty()) return
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val count = ctrl.mediaItemCount
            if (count <= 0) return@launch
            val curIdx = ctrl.currentMediaItemIndex.coerceIn(0, count - 1)
            val targetIdx = curIdx + 1
            val cur = _state.value
            val fn = song.filename
            val from = cur.queueFilenames.indexOf(fn)
            val newPlayNextSet = cur.playNextFilenames + fn

            when {
                from == curIdx -> return@launch
                from == targetIdx -> {
                    _state.value = cur.copy(playNextFilenames = newPlayNextSet)
                    android.util.Log.i("QueueOp", "playNext: already at $targetIdx $fn")
                }
                from >= 0 && targetIdx < count -> {
                    val moveTo = if (from < targetIdx) targetIdx - 1 else targetIdx
                    if (from !in 0 until count || moveTo !in 0 until count) return@launch
                    ctrl.moveMediaItem(from, moveTo)
                    val list = cur.queueFilenames.toMutableList()
                    if (from in list.indices) {
                        val item = list.removeAt(from)
                        list.add(moveTo.coerceIn(0, list.size), item)
                    }
                    val newCurIdx = ctrl.currentMediaItemIndex
                    _state.value = cur.copy(
                        queueFilenames = list,
                        queueIndex = newCurIdx,
                        playNextFilenames = newPlayNextSet,
                    )
                    android.util.Log.i(
                        "QueueOp",
                        "playNext: moved $fn from $from to $moveTo; playNextSet size=${newPlayNextSet.size}",
                    )
                    persistQueue(list, newCurIdx)
                }
                from >= 0 -> {
                    // Song is after current at queue end — remove then insert after current.
                    ctrl.removeMediaItem(from)
                    val list = cur.queueFilenames.toMutableList().also {
                        if (from in it.indices) it.removeAt(from)
                    }
                    val curIdxAfter = ctrl.currentMediaItemIndex.coerceAtLeast(0)
                    sendPlaybackCommand(ctrl, CMD_INSERT_AFTER_CURRENT, queueCommandArgs(listOf(song)))
                    val insertAt = (curIdxAfter + 1).coerceAtMost(list.size)
                    list.add(insertAt, fn)
                    val newCurIdx = ctrl.currentMediaItemIndex
                    _state.value = cur.copy(
                        queueFilenames = list,
                        queueIndex = newCurIdx,
                        playNextFilenames = newPlayNextSet,
                    )
                    android.util.Log.i(
                        "QueueOp",
                        "playNext: relocated $fn from $from to after current; playNextSet size=${newPlayNextSet.size}",
                    )
                    persistQueue(list, newCurIdx)
                }
                else -> {
                    sendPlaybackCommand(ctrl, CMD_INSERT_AFTER_CURRENT, queueCommandArgs(listOf(song)))
                    val insertAt = targetIdx.coerceAtMost(cur.queueFilenames.size)
                    val nextFilenames = cur.queueFilenames.toMutableList().also {
                        if (insertAt <= it.size) it.add(insertAt, fn) else it.add(fn)
                    }
                    _state.value = cur.copy(
                        queueFilenames = nextFilenames,
                        playNextFilenames = newPlayNextSet,
                    )
                    android.util.Log.i(
                        "QueueOp",
                        "playNext: inserted $fn at $insertAt; playNextSet size=${newPlayNextSet.size}",
                    )
                    persistQueue(nextFilenames, ctrl.currentMediaItemIndex)
                }
            }
        }
    }

    /**
     * Insert multiple songs right after the current track ("Queue all similar").
     * Push #70: all inserted filenames join `playNextFilenames` so they survive
     * queue rebuilds. De-dupes against current track and existing queue.
     */
    fun playNextMany(songs: List<Song>) {
        val cur = _state.value
        val have = cur.queueFilenames.toSet()
        val curFilename = cur.currentMediaId
        val seen = HashSet<String>()
        curFilename?.let { seen += it }
        val toInsert = songs.filter {
            !it.filePath.isNullOrEmpty() &&
                it.filename !in have &&
                seen.add(it.filename)
        }
        if (toInsert.isEmpty()) return
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val insertAt = (ctrl.currentMediaItemIndex + 1).coerceAtLeast(0)
            sendPlaybackCommand(ctrl, CMD_INSERT_AFTER_CURRENT, queueCommandArgs(toInsert))
            val curState = _state.value
            val nextFilenames = curState.queueFilenames.toMutableList().also { list ->
                val offset = insertAt.coerceAtMost(list.size)
                list.addAll(offset, toInsert.map { it.filename })
            }
            val newPlayNextSet = curState.playNextFilenames + toInsert.map { it.filename }.toSet()
            _state.value = curState.copy(
                queueFilenames = nextFilenames,
                playNextFilenames = newPlayNextSet,
            )
            android.util.Log.i(
                "QueueOp",
                "playNextMany: inserted ${toInsert.size} at $insertAt; playNextSet size=${newPlayNextSet.size}"
            )
            persistQueue(nextFilenames, ctrl.currentMediaItemIndex)
        }
    }

    /** "Play only" — replace queue with just this song, no auto-rebuild. */
    fun playOnly(song: Song) {
        if (song.filePath.isNullOrEmpty()) return
        clearPlayNextMarker(song.filename)
        playQueue(listOf(song), 0, source = "manual_tap")
    }

    /**
     * Replace the entire current queue while keeping the current song playing.
     * Used by the Up Next AI/Shuffle toggle: when the user flips modes we
     * rebuild the upcoming portion without restarting playback.
     *
     * Push #70: when [preservePlayNext] is true, any songs in the current
     * queue whose filename is in `playNextFilenames` are prepended to
     * the new upcoming list so they survive the replacement. Used by
     * the Refresh button on UpNextScreen.
     */
    fun replaceUpcoming(newUpcoming: List<Song>, newContext: QueueContext? = null) {
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val curIdx = ctrl.currentMediaItemIndex
            val totalBefore = ctrl.mediaItemCount
            if (curIdx < 0 || totalBefore == 0) return@launch
            val curState = _state.value
            val curFilename = curState.currentMediaId
            val seen = HashSet<String>()
            curFilename?.let { seen += it }
            val nextSongs = newUpcoming.filter {
                !it.filePath.isNullOrEmpty() && seen.add(it.filename)
            }
            sendPlaybackCommand(ctrl, CMD_REPLACE_UPCOMING, queueCommandArgs(nextSongs))
            val nextFilenames = buildList {
                if (curFilename != null) add(curFilename)
                addAll(nextSongs.map { it.filename })
            }
            val resolvedContext = newContext ?: curState.queueContext
            android.util.Log.i(
                "QueueOp",
                "replaceUpcoming newUpcoming=${newUpcoming.size} final=${nextSongs.size} " +
                    "ctx=${curState.queueContext}→$resolvedContext playNextSet=${curState.playNextFilenames.size}"
            )
            syncQueueStateAfterReplace(ctrl, curState, nextFilenames, curFilename, resolvedContext)
        }
    }

    /**
     * Align Kotlin queue index/filenames with Media3 after replaceUpcoming.
     * Service normalizes to [current] + upcoming at index 0.
     */
    private fun syncQueueStateAfterReplace(
        ctrl: MediaController,
        curState: PlaybackState,
        nextFilenames: List<String>,
        curFilename: String?,
        resolvedContext: QueueContext,
    ) {
        val actualIdx = ctrl.currentMediaItemIndex.coerceAtLeast(0)
        val actualMediaId = mediaIdToFilename(ctrl.currentMediaItem?.mediaId)
        var queueIndex = actualIdx
        if (!curFilename.isNullOrBlank()) {
            val expectedIdx = nextFilenames.indexOf(curFilename)
            if (expectedIdx >= 0) {
                queueIndex = expectedIdx
            }
        }
        if (!curFilename.isNullOrBlank() &&
            nextFilenames.getOrNull(queueIndex) != curFilename
        ) {
            android.util.Log.w(
                "QueueOp",
                "replaceUpcoming index mismatch: queueIndex=$queueIndex " +
                    "actualIdx=$actualIdx actualMediaId=$actualMediaId expected=$curFilename",
            )
            val fixIdx = nextFilenames.indexOf(curFilename)
            if (fixIdx >= 0) {
                queueIndex = fixIdx
                sendPlaybackCommand(
                    ctrl,
                    CMD_PLAY_INDEX,
                    Bundle().apply { putInt(KEY_INDEX, fixIdx) },
                )
            }
        }
        _state.value = curState.copy(
            queueFilenames = nextFilenames,
            queueIndex = queueIndex,
            queueContext = resolvedContext,
        )
        persistQueue(nextFilenames, queueIndex)
    }

    private fun buildMediaItem(song: Song): MediaItem =
        MediaItem.Builder()
            .setMediaId(song.filename)
            .setUri(android.net.Uri.fromFile(File(song.filePath!!)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .build()
            )
            .build()

    private fun persistCurrent(mediaId: String, positionMs: Long) {
        // Phase C (2026-06-01): also persist display metadata for the optimistic
        // MiniPlayer hydrate path in preWarm(). Falls back to _state.value
        // (which the controller listener keeps current). Blank strings stay
        // blank — saveCurrent() preserves prior values when null is passed.
        val st = _state.value
        val title = st.title.takeIf { it.isNotBlank() }
        val artist = st.artist.takeIf { it.isNotBlank() }
        val album = st.album.takeIf { it.isNotBlank() }
        scope.launch(Dispatchers.IO) {
            try { preferences.saveCurrent(mediaId, positionMs, title, artist, album) }
            catch (t: Throwable) { android.util.Log.w("PlaybackEngine", "saveCurrent failed: ${t.message}") }
        }
    }

    private fun persistQueue(filenames: List<String>, index: Int) {
        scope.launch(Dispatchers.IO) {
            try { preferences.saveQueue(filenames, index) }
            catch (t: Throwable) { android.util.Log.w("PlaybackEngine", "saveQueue failed: ${t.message}") }
        }
    }

    private fun startPositionPoll() {
        controllerJob?.cancel()
        controllerJob = scope.launch {
            while (true) {
                delay(500L)
                val ctrl = controller ?: continue
                val pos = ctrl.currentPosition.coerceAtLeast(0L)
                val dur = ctrl.duration.takeIf { it > 0 } ?: 0L
                // Update the high-churn live position/duration flows ONLY.
                // Do NOT touch _state — keep its positionMs/durationMs stale.
                // This avoids triggering a full AppRoot recomposition every
                // 500 ms tick. Slider readers consume livePosition/liveDuration
                // explicitly; everything else reads from _state.
                if (_livePosition.value != pos) _livePosition.value = pos
                if (_liveDuration.value != dur) _liveDuration.value = dur
                val cur = _state.value
                if (cur.isPlaying) {
                    recentlySurfacedTracker?.advanceListeningClock(500L)
                }
                // Persist position every ~5 s while playing so cold-start
                // resumes near where the user left off, without spamming
                // DataStore on every poll.
                if (cur.isPlaying && cur.currentMediaId != null &&
                    Math.abs(pos - lastSavedPositionMs) > 5_000L
                ) {
                    lastSavedPositionMs = pos
                    persistCurrent(cur.currentMediaId, pos)
                }
            }
        }
    }

    fun release() {
        controllerJob?.cancel()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        _controllerReady.value = false
    }

    /**
     * Push #74: tells the service to rebuild the notification (and therefore
     * its Favorite/Close buttons + heart icon) right now. Used after a
     * Kotlin-side favorite toggle to keep the notification icon in lockstep
     * with the in-app mini-player heart.
     */
    fun refreshNotification() {
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            runCatching {
                sendPlaybackCommand(ctrl, CMD_UPDATE_NOTIFICATION_STATE)
            }
        }
    }
}
