package com.isaivazhi.app.engine

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.isaivazhi.app.Media3PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
) {

    /**
     * Source label for the next transition. Set to "next_button" / "skip_prev" /
     * "manual_tap" by user-facing actions so the taste engine can classify the
     * signal correctly (auto skips count, manual taps don't).
     */
    @Volatile
    var pendingTransitionSource: String = "auto_advance"

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
    )

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
            if (prevState.currentMediaId != null && prevState.currentMediaId != mediaItem?.mediaId) {
                val frac = if (prevDuration > 0) (prevPlayed.toFloat() / prevDuration.toFloat()).coerceIn(0f, 1f) else 0f
                android.util.Log.i(
                    "PlaybackEngine",
                    "transition: prev=${prevState.currentMediaId} played=${prevPlayed}ms dur=${prevDuration}ms frac=$frac source=$pendingTransitionSource origin=$origin → next=${mediaItem?.mediaId}"
                )
                history?.recordEnd(prevState.currentMediaId, frac)
                // Feed the taste signal pipeline. Snapshot before/after and
                // append to the timeline so the user can audit each event.
                val source = pendingTransitionSource
                val t = taste
                if (t != null) {
                    val (before, after) = t.recordPlaybackEvent(prevState.currentMediaId, frac, source)
                    val isSkip = frac < TasteEngine.SKIP_THRESHOLD
                    val isManual = source.startsWith("manual_") || source == "song_tap" ||
                        source == "queue_tap" || source == "neutral_skip"
                    // Push #63: session counters before/after.
                    val sessionPair = session?.recordEvent(frac, isSkip, isManual)
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
                                filename = prevState.currentMediaId,
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
                    // Push #63: propagate the resulting directScore delta to
                    // the song's top-10 embedding neighbors. Capacitor parity
                    // (engine.js:535).
                    val delta = after.directScore - before.directScore
                    if (kotlin.math.abs(delta) > 0.001f) {
                        scope.launch {
                            t.propagateSimilarityBoost(
                                sourceFilename = prevState.currentMediaId,
                                scoreDelta = delta,
                                reason = if (isSkip) "playback_skip" else "playback_complete",
                            )
                        }
                    }
                }
                // Reset to auto for the next implicit transition.
                pendingTransitionSource = "auto_advance"
            }
            _state.value = prevState.copy(
                currentMediaId = mediaItem?.mediaId,
                title = md?.title?.toString() ?: "",
                artist = md?.artist?.toString() ?: "",
                album = md?.albumTitle?.toString() ?: "",
                queueIndex = ctrl?.currentMediaItemIndex ?: prevState.queueIndex,
            )
            // Reset live position/duration for the new track. duration may be
            // unknown until Media3 reports it via onMediaMetadataChanged or
            // the next poll tick.
            _livePosition.value = 0L
            _liveDuration.value = ctrl?.duration?.takeIf { it > 0 } ?: 0L
            mediaItem?.mediaId?.let {
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
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.value = _state.value.copy(shuffleEnabled = shuffleModeEnabled)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.value = _state.value.copy(repeatMode = repeatMode)
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
                val future = MediaController.Builder(appContext, token).buildAsync()
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
            startPositionPoll()
            ctrl
        }
    }

    /**
     * Plays a queue starting from `startIndex`. Replaces any existing queue.
     * Songs with null/blank filePath are filtered out before submission.
     */
    fun playQueue(songs: List<Song>, startIndex: Int = 0, source: String = "manual_tap") {
        pendingTransitionSource = source
        // Push #39: the service captures its own pre-transition snapshot
        // in Media3PlaybackService.onMediaItemTransition. No Kotlin-side
        // snapshot needed.
        val playable = songs.filter { !it.filePath.isNullOrEmpty() }
        if (playable.isEmpty()) return
        val safeStart = startIndex.coerceIn(0, playable.size - 1)
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val items = playable.map { buildMediaItem(it) }
            ctrl.setMediaItems(items, safeStart, 0L)
            ctrl.prepare()
            ctrl.play()
            val first = playable[safeStart]
            _state.value = _state.value.copy(
                currentSongId = first.id,
                currentMediaId = first.filename,
                title = first.title,
                artist = first.artist,
                album = first.album,
                isPlaying = true,
                queueFilenames = playable.map { it.filename },
                queueIndex = safeStart,
                preparedNotPlaying = false,
            )
            _livePosition.value = 0L
            _liveDuration.value = 0L
            persistQueue(playable.map { it.filename }, safeStart)
            persistCurrent(first.filename, 0L)
        }
    }

    /** Plays a single song. Convenience wrapper that builds a 1-item queue. */
    fun play(song: Song) = playQueue(listOf(song), 0)

    /**
     * Cold-start path: prepares a queue without auto-playing. The next user
     * tap on the play button calls `controller.play()` which lands near
     * instantly (Media3 already has the source prepared). Mirrors the
     * critical-state restore pattern that fixed cold-start tap-to-audio in
     * the Capacitor build (batches #11–#18) but achieves it from Kotlin
     * synchronously instead of via SharedPreferences cache tricks.
     */
    fun prepareForResume(songs: List<Song>, startIndex: Int, seekToMs: Long) {
        val playable = songs.filter { !it.filePath.isNullOrEmpty() }
        if (playable.isEmpty()) return
        val safeStart = startIndex.coerceIn(0, playable.size - 1)
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val items = playable.map { buildMediaItem(it) }
            ctrl.setMediaItems(items, safeStart, seekToMs.coerceAtLeast(0L))
            ctrl.prepare()
            val first = playable[safeStart]
            _state.value = _state.value.copy(
                currentSongId = first.id,
                currentMediaId = first.filename,
                title = first.title,
                artist = first.artist,
                album = first.album,
                isPlaying = false,
                queueFilenames = playable.map { it.filename },
                queueIndex = safeStart,
                preparedNotPlaying = true,
            )
            _livePosition.value = seekToMs.coerceAtLeast(0L)
            _liveDuration.value = 0L
        }
    }

    fun togglePause() {
        // Optimistic UI flip — the icon swaps the instant the user taps,
        // before Media3 confirms via onPlayWhenReadyChanged. If the controller
        // somehow refuses the command, onPlayWhenReadyChanged will correct
        // the state back. Driven by playWhenReady (user intent), not isPlaying
        // (actual audio flowing) — buffering frames don't confuse it.
        val nowPlaying = _state.value.isPlaying
        _state.value = _state.value.copy(isPlaying = !nowPlaying, preparedNotPlaying = false)
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
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
            if (ctrl.hasNextMediaItem()) ctrl.seekToNextMediaItem()
        }
    }

    fun skipPrev() {
        pendingTransitionSource = "prev_button"
        // Push #39: service-side snapshot replaces the Kotlin-side
        // pre-transition fields.
        val cur = _state.value
        val livePos = _livePosition.value
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            // Mirror common music-app convention: tap-prev restarts the current
            // track if we're past 3 s, otherwise jumps to the previous track.
            if (ctrl.currentPosition > 3000L) {
                ctrl.seekTo(0L)
            } else if (ctrl.hasPreviousMediaItem()) {
                ctrl.seekToPreviousMediaItem()
            } else {
                ctrl.seekTo(0L)
            }
        }
        // Optimistic update for the prev-track case so the UI doesn't lag.
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
            ctrl.seekTo(index, 0L)
            ctrl.play()
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
     * Fix: shuffle the queue list ITSELF in Kotlin AND mirror to Media3
     * via `controller.moveMediaItem`. Keep `Media3.shuffleModeEnabled = false`.
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
        toaster?.show(if (next) "Shuffle on — queue randomized" else "Shuffle off")
        if (!next) return  // off: no structural change (matches Capacitor)
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            // Ensure Media3 native shuffle is OFF — we manage shuffle ourselves
            // so the visible Up Next list IS the playback order.
            ctrl.shuffleModeEnabled = false
            val curIdx = ctrl.currentMediaItemIndex
            val total = ctrl.mediaItemCount
            // Tail = positions [curIdx+1 .. total-1]. Need ≥ 2 to shuffle.
            if (curIdx < 0 || total - (curIdx + 1) < 2) return@launch
            val tailStart = curIdx + 1
            val tailSize = total - tailStart
            // Generate a random permutation by Fisher-Yates over the tail
            // indices, then apply each swap via Media3's moveMediaItem.
            // moveMediaItem doesn't re-create MediaItems — it just
            // reorders the existing list, which is exactly what we want.
            val perm = (0 until tailSize).toMutableList().also { it.shuffle() }
            // Translate the permutation into a series of swap operations.
            // Naive but correct: track current positions in a working
            // array and apply moves until the array matches `perm`.
            val cur = IntArray(tailSize) { it }
            val pos = IntArray(tailSize) { it }   // pos[origIdx] = currentSlot
            for (slot in 0 until tailSize) {
                val targetOrig = perm[slot]
                val curSlot = pos[targetOrig]
                if (curSlot == slot) continue
                val from = tailStart + curSlot
                val to = tailStart + slot
                ctrl.moveMediaItem(from, to)
                // Update bookkeeping
                val displaced = cur[slot]
                cur[slot] = targetOrig
                cur[curSlot] = displaced
                pos[targetOrig] = slot
                pos[displaced] = curSlot
            }
            // Mirror the new order into Kotlin queueFilenames.
            val curList = _state.value.queueFilenames
            if (curList.size == total) {
                val head = curList.subList(0, tailStart)
                val oldTail = curList.subList(tailStart, total)
                val newTail = perm.map { oldTail[it] }
                val nextFilenames = head + newTail
                _state.value = _state.value.copy(queueFilenames = nextFilenames, queueIndex = curIdx)
                persistQueue(nextFilenames, curIdx)
            }
            android.util.Log.i("PlaybackEngine", "shuffle: tail randomized ($tailSize songs)")
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
        toaster?.show(when (next) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> "Loop off"
            androidx.media3.common.Player.REPEAT_MODE_ONE -> "Loop: repeat this song"
            else -> "Loop: repeat all"
        })
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            ctrl.repeatMode = next
        }
    }

    /**
     * Pre-warm the MediaController so the user's first tap doesn't pay the
     * Future.get() connect latency. Call once at app launch.
     */
    fun preWarm() {
        scope.launch { runCatching { ensureController() } }
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
            _state.value = cur.copy(queueFilenames = nextFilenames, queueIndex = nextIdx)
            persistQueue(nextFilenames, nextIdx)
            // Push #63: signal capture for queue-remove.
            val t = taste
            if (t != null && !removedFilename.isNullOrBlank()) {
                val (before, after) = t.bumpXScoreForQueueRemove(removedFilename)
                val delta = after.directScore - before.directScore
                if (kotlin.math.abs(delta) > 0.001f) {
                    t.propagateSimilarityBoost(removedFilename, delta, "queue_remove")
                }
            }
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
            if (toAdd.isEmpty()) return@launch
            ctrl.addMediaItems(toAdd.map { buildMediaItem(it) })
            val cur = _state.value
            val nextFilenames = cur.queueFilenames + toAdd.map { it.filename }
            _state.value = cur.copy(queueFilenames = nextFilenames)
            persistQueue(nextFilenames, cur.queueIndex)
            android.util.Log.i("PlaybackEngine", "appendToQueue: added ${toAdd.size} songs; total=${nextFilenames.size}")
        }
    }

    /** Insert a song right after the current track ("Play Next" action). */
    fun playNext(song: Song) {
        if (song.filePath.isNullOrEmpty()) return
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val insertAt = (ctrl.currentMediaItemIndex + 1).coerceAtLeast(0)
            ctrl.addMediaItem(insertAt, buildMediaItem(song))
            val cur = _state.value
            val nextFilenames = cur.queueFilenames.toMutableList().also {
                if (insertAt <= it.size) it.add(insertAt, song.filename) else it.add(song.filename)
            }
            _state.value = cur.copy(queueFilenames = nextFilenames)
            persistQueue(nextFilenames, ctrl.currentMediaItemIndex)
        }
    }

    /** "Play only" — replace queue with just this song, no auto-rebuild. */
    fun playOnly(song: Song) {
        if (song.filePath.isNullOrEmpty()) return
        playQueue(listOf(song), 0, source = "manual_tap")
    }

    /**
     * Replace the entire current queue while keeping the current song playing.
     * Used by the Up Next AI/Shuffle toggle: when the user flips modes we
     * rebuild the upcoming portion without restarting playback.
     */
    fun replaceUpcoming(newUpcoming: List<Song>) {
        scope.launch {
            val ctrl = runCatching { ensureController() }.getOrNull() ?: return@launch
            val curIdx = ctrl.currentMediaItemIndex
            val totalBefore = ctrl.mediaItemCount
            if (curIdx < 0 || totalBefore == 0) return@launch
            // Push #42 Tier 2J: remove ALL items before AND after the
            // current MediaItem so Media3's queue ends as
            // [current, ...newTail]. Previously we only removed items
            // after curIdx, leaving history in Media3 while Kotlin
            // state's queueFilenames dropped it — `queueIndex` then
            // drifted (Media3 stayed at curIdx, Kotlin reset to 0). The
            // next transition would highlight the wrong "Now Playing"
            // row.
            if (curIdx + 1 < totalBefore) {
                ctrl.removeMediaItems(curIdx + 1, totalBefore)
            }
            if (curIdx > 0) {
                ctrl.removeMediaItems(0, curIdx)
            }
            // Media3 now has exactly 1 item (current). Append the tail,
            // de-duped against the current track.
            val playable = newUpcoming.filter { !it.filePath.isNullOrEmpty() && it.filename != _state.value.currentMediaId }
            if (playable.isNotEmpty()) {
                ctrl.addMediaItems(playable.map { buildMediaItem(it) })
            }
            val cur = _state.value
            val curFilename = cur.currentMediaId
            val nextFilenames = buildList {
                if (curFilename != null) add(curFilename)
                addAll(playable.map { it.filename })
            }
            _state.value = cur.copy(queueFilenames = nextFilenames, queueIndex = 0)
            persistQueue(nextFilenames, 0)
        }
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
        scope.launch(Dispatchers.IO) {
            try { preferences.saveCurrent(mediaId, positionMs) }
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
    }
}
