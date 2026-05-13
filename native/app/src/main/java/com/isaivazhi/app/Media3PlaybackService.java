package com.isaivazhi.app;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.CommandButton;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaController;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Media3PlaybackService extends MediaSessionService {

    /**
     * Static accessor so Kotlin code (PlaybackEngine) can read the
     * service's accumulated played-ms snapshot directly, mirroring the
     * Capacitor `MusicPlaybackService.instance` pattern. The accumulator
     * lives in the service so it survives Activity destruction, Doze,
     * and process restarts as long as the foreground service is alive.
     * Null when the service hasn't been bound or has been destroyed.
     */
    public static volatile Media3PlaybackService INSTANCE = null;

    private static final String TAG = "Media3PlaybackService";
    private static final int LOOP_OFF = 0;
    private static final int LOOP_ONE = 1;
    private static final int LOOP_ALL = 2;
    private static final long PROGRESS_INTERVAL_MS = 250L;
    private static final String RECOVERY_PREFS_NAME = "playback_recovery_v1";
    private static final String RECOVERY_TRANSITIONS_KEY = "recent_transitions";
    private static final int MAX_RECOVERY_TRANSITIONS = 24;
    private static final String CAPACITOR_STORAGE_PREFS = "CapacitorStorage";
    private static final String PREF_KEY_PLAYBACK_STATE = "playback_state";
    private static final String PREF_KEY_FAVORITES = "favorites";
    private static final String PREF_KEY_DISLIKED_SONGS = "disliked_songs";

    private final Object queueLock = new Object();
    private final Set<MediaSession.ControllerInfo> connectedControllers =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            if (player.isPlaying()) {
                emitAudioTimeUpdate();
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS);
            }
        }
    };

    private ExoPlayer player;
    private MediaSession mediaSession;
    private ArrayList<PlaybackQueueItem> queue = new ArrayList<>();
    private int currentIndex = C.INDEX_UNSET;
    private int loopMode = LOOP_ALL;
    private long currentPlaybackInstanceId = 0L;
    private long accumulatedPlayedMs = 0L;
    private long lastProgressSampleMs = -1L;
    private long lastKnownPositionMs = 0L;
    private long lastKnownDurationMs = 0L;
    // Captured BEFORE resetPlayedProgress in onMediaItemTransition so the
    // Kotlin PlaybackEngine can read the prev song's true played-ms after
    // the service has already reset its accumulator. Read via the public
    // getters. Volatile because PlaybackEngine reads from MainActivity's
    // thread while the service updates from its own player thread.
    private volatile long lastTransitionPrevPlayedMs = 0L;
    private volatile long lastTransitionPrevDurationMs = 0L;
    private volatile long lastTransitionAtMs = 0L;
    private boolean playbackCompletedState = false;
    private boolean suppressNextTransitionEvent = false;
    // 2026-05-11 #19: track last emitted play-state so we can dedupe and avoid
    // emitting false during BUFFERING (which would make the UI flicker pause).
    private Boolean lastEmittedPlayState = null;
    private int suppressedTransitionIndex = C.INDEX_UNSET;
    private String displayTitle = "";
    private String displayArtist = "";
    private String displayAlbum = "";

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        Log.i(TAG, "onCreate: Media3 playback service starting");

        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new PlayerEventListener());

        PendingIntent sessionActivity = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivity)
                .setCallback(new SessionCallback())
                .build();
        setMediaNotificationProvider(new PlaybackNotificationProvider());
        refreshNotificationUiState();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: Media3 playback service stopping");
        stopProgressUpdates();
        connectedControllers.clear();
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    private final class SessionCallback implements MediaSession.Callback {
        @Override
        public MediaSession.ConnectionResult onConnect(
                MediaSession session,
                MediaSession.ControllerInfo controller
        ) {
            Log.d(TAG, "Controller connected: " + controller.getPackageName());
            SessionCommands sessionCommands = PlaybackCommandContract.controllerCommands();
            Player.Commands playerCommands = new Player.Commands.Builder()
                    .addAllCommands()
                    .build();
            return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands);
        }

        @Override
        public void onPostConnect(MediaSession session, MediaSession.ControllerInfo controller) {
            connectedControllers.add(controller);
            sendCustomCommand(controller, PlaybackCommandContract.EVT_TRANSPORT_READY, buildTransportStateBundle());
        }

        @Override
        public void onDisconnected(MediaSession session, MediaSession.ControllerInfo controller) {
            connectedControllers.remove(controller);
        }

        @Override
        public ListenableFuture<SessionResult> onCustomCommand(
                MediaSession session,
                MediaSession.ControllerInfo controller,
                SessionCommand customCommand,
                Bundle args
        ) {
            String action = customCommand.customAction;
            if (action == null) {
                return success(new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
            }
            Bundle safeArgs = args != null ? args : Bundle.EMPTY;

            try {
                switch (action) {
                    case PlaybackCommandContract.CMD_SET_QUEUE: {
                        ArrayList<PlaybackQueueItem> items = PlaybackQueueItem.fromCommandBundle(safeArgs);
                        int startIndex = safeArgs.getInt(PlaybackCommandContract.KEY_START_INDEX, 0);
                        long seekToMs = safeArgs.getLong(PlaybackCommandContract.KEY_SEEK_TO_MS, 0L);
                        Log.i(TAG, "CMD_SET_QUEUE items=" + items.size()
                                + " startIndex=" + startIndex
                                + " first=" + summarizePath(items.isEmpty() ? "" : items.get(0).filePath));
                        setQueue(items, startIndex, seekToMs);
                        return success();
                    }
                    case PlaybackCommandContract.CMD_PLAY_AUDIO: {
                        PlaybackQueueItem item = new PlaybackQueueItem(
                                safeArgs.getInt(PlaybackCommandContract.KEY_SONG_ID, -1),
                                safeArgs.getString(PlaybackCommandContract.KEY_FILE_PATH, ""),
                                safeArgs.getString(PlaybackCommandContract.KEY_TITLE, ""),
                                safeArgs.getString(PlaybackCommandContract.KEY_ARTIST, ""),
                                safeArgs.getString(PlaybackCommandContract.KEY_ALBUM, "")
                        );
                        long seekToMs = safeArgs.getLong(PlaybackCommandContract.KEY_SEEK_TO_MS, 0L);
                        Log.i(TAG, "CMD_PLAY_AUDIO path=" + summarizePath(item.filePath));
                        playSingle(item, seekToMs);
                        return success();
                    }
                    case PlaybackCommandContract.CMD_REPLACE_UPCOMING: {
                        ArrayList<PlaybackQueueItem> items = PlaybackQueueItem.fromCommandBundle(safeArgs);
                        replaceUpcoming(items);
                        return success();
                    }
                    case PlaybackCommandContract.CMD_INSERT_AFTER_CURRENT: {
                        ArrayList<PlaybackQueueItem> items = PlaybackQueueItem.fromCommandBundle(safeArgs);
                        insertAfterCurrent(items);
                        return success();
                    }
                    case PlaybackCommandContract.CMD_APPEND_TO_QUEUE: {
                        ArrayList<PlaybackQueueItem> items = PlaybackQueueItem.fromCommandBundle(safeArgs);
                        appendToQueue(items);
                        return success();
                    }
                    case PlaybackCommandContract.CMD_CLEAR_QUEUE_AFTER_CURRENT:
                        clearQueueAfterCurrent();
                        return success();
                    case PlaybackCommandContract.CMD_PLAY_INDEX:
                        playIndex(safeArgs.getInt(PlaybackCommandContract.KEY_INDEX, 0));
                        return success();
                    case PlaybackCommandContract.CMD_NEXT_TRACK:
                        nextTrack(
                                safeArgs.getString(PlaybackCommandContract.KEY_ACTION, "user_next"),
                                safeArgs.containsKey(PlaybackCommandContract.KEY_PREV_FRACTION)
                                        ? (float) safeArgs.getDouble(PlaybackCommandContract.KEY_PREV_FRACTION, -1d)
                                        : -1f
                        );
                        return success();
                    case PlaybackCommandContract.CMD_PREV_TRACK:
                        prevTrack(
                                safeArgs.containsKey(PlaybackCommandContract.KEY_PREV_FRACTION)
                                        ? (float) safeArgs.getDouble(PlaybackCommandContract.KEY_PREV_FRACTION, -1d)
                                        : -1f
                        );
                        return success();
                    case PlaybackCommandContract.CMD_SET_LOOP_MODE:
                        setLoopMode(safeArgs.getInt(PlaybackCommandContract.KEY_LOOP_MODE, LOOP_ALL));
                        return success();
                    case PlaybackCommandContract.CMD_GET_AUDIO_STATE:
                        return success(new SessionResult(SessionResult.RESULT_SUCCESS, buildAudioStateBundle()));
                    case PlaybackCommandContract.CMD_GET_QUEUE_STATE:
                        return success(new SessionResult(SessionResult.RESULT_SUCCESS, buildQueueStateBundle()));
                    case PlaybackCommandContract.CMD_REQUEST_TRANSPORT_STATE:
                        return success(new SessionResult(SessionResult.RESULT_SUCCESS, buildTransportStateBundle()));
                    case PlaybackCommandContract.CMD_UPDATE_NOTIFICATION_STATE:
                        updateNotificationState(safeArgs);
                        return success();
                    case PlaybackCommandContract.CMD_STOP_SERVICE:
                        mainHandler.post(() -> stopPlaybackAndCleanup(false));
                        return success();
                    case PlaybackCommandContract.CMD_NOTIFICATION_TOGGLE_FAVORITE:
                        handleNotificationFavorite();
                        return success();
                    case PlaybackCommandContract.CMD_NOTIFICATION_DISMISS:
                        handleNotificationDismiss();
                        return success();
                    default:
                        return success(new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
                }
            } catch (Exception e) {
                Log.e(TAG, "Custom command failed: " + action, e);
                return success(new SessionResult(SessionResult.RESULT_ERROR_UNKNOWN, errorBundle(e.getMessage(), currentItemPath())));
            }
        }
    }

    private final class PlayerEventListener implements Player.Listener {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            // 2026-05-11 #15: log more context so we can tell WHY isPlaying flipped.
            int state = player != null ? player.getPlaybackState() : -1;
            int idx = player != null ? player.getCurrentMediaItemIndex() : -1;
            int total = player != null ? player.getMediaItemCount() : -1;
            Log.i(TAG, "onIsPlayingChanged: " + isPlaying
                    + " state=" + state    // 1=IDLE 2=BUFFERING 3=READY 4=ENDED
                    + " mediaIdx=" + idx
                    + " mediaTotal=" + total
                    + " current=" + summarizePath(currentItemPath()));
            if (isPlaying) {
                playbackCompletedState = false;
                startProgressUpdates();
            } else {
                stopProgressUpdates();
                emitAudioTimeUpdate();
            }
            // 2026-05-11 #19: Media3's isPlaying flips to false during BUFFERING
            // and back to true on READY. Emitting that directly causes the JS
            // play icon to flicker and triggers state-save churn. Emit the
            // user-intended state (playWhenReady) instead, so buffering hiccups
            // don't look like user-initiated pauses.
            boolean intended = player != null && player.getPlayWhenReady();
            emitPlayStateIfChanged(intended);
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            int newIndex = player != null ? player.getCurrentMediaItemIndex() : C.INDEX_UNSET;
            // 2026-05-11 #15: log transition reason (AUTO=0, SEEK=1, PLAYLIST_CHANGED=2, REPEAT=3).
            Log.i(TAG, "onMediaItemTransition: reason=" + reason
                    + " newIndex=" + newIndex
                    + " currentIndex=" + currentIndex
                    + " suppress=" + suppressNextTransitionEvent + "/" + suppressedTransitionIndex);
            if (suppressNextTransitionEvent && newIndex == suppressedTransitionIndex) {
                suppressNextTransitionEvent = false;
                suppressedTransitionIndex = C.INDEX_UNSET;
                return;
            }

            synchronized (queueLock) {
                if (newIndex == C.INDEX_UNSET || newIndex == currentIndex) return;
            TransitionSnapshot snapshot = captureTransitionSnapshot(resolveTransitionAction(reason), -1f);
            // Stash the snapshot's played-ms + duration BEFORE
            // resetPlayedProgress wipes the accumulator. The Kotlin
            // PlaybackEngine reads these in its own onMediaItemTransition
            // to compute the prev song's fraction. Push #39: source of
            // truth moved from PlaybackEngine's poll-tick accumulator
            // (which dies with the Activity) to the service (durable
            // across Activity destruction / Doze).
            lastTransitionPrevPlayedMs = snapshot.prevPlayedMs;
            lastTransitionPrevDurationMs = snapshot.prevDurationMs;
            lastTransitionAtMs = System.currentTimeMillis();
            // Push #66: record the transition into a rolling buffer so
            // cold-start reconciliation can correlate a pending listen
            // snapshot against an authoritative native transition. The
            // buffer carries `prevPlaybackInstanceId` which uniquely
            // identifies the just-ended playback session.
            rememberTransitionToPrefs(snapshot);
            currentIndex = newIndex;
            currentPlaybackInstanceId = nextPlaybackInstanceId();
            resetPlayedProgress(0L);
            playbackCompletedState = false;
            emitCurrentChanged(snapshot);
            }
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            // 2026-05-11 #15: log every state change so we can tell when STATE_ENDED
            // fires unexpectedly (which would explain isPlaying=false + immediate
            // queueCurrentChanged at the same currentIndex).
            Log.i(TAG, "onPlaybackStateChanged: state=" + playbackState
                    + " (1=IDLE 2=BUFFERING 3=READY 4=ENDED)");
            if (playbackState == Player.STATE_READY) {
                lastKnownDurationMs = currentDurationMs();
                emitAudioTimeUpdate();
                return;
            }

            if (playbackState == Player.STATE_ENDED) {
                synchronized (queueLock) {
                    playbackCompletedState = true;
                    noteProgressSample(lastKnownDurationMs);
                    stopProgressUpdates();
                    rememberStopTransition("queue_end");
                    handleQueueEndedState("queue_end");
                    emitQueueEnded();
                }
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            String path = currentItemPath();
            Log.e(TAG, "onPlayerError path=" + summarizePath(path), error);
            Bundle bundle = errorBundle(
                    error != null ? error.getMessage() : "Playback failed",
                    path
            );
            emitCustomCommandToControllers(PlaybackCommandContract.EVT_AUDIO_ERROR, bundle);
        }
    }

    private void playSingle(PlaybackQueueItem item, long seekToMs) {
        ArrayList<PlaybackQueueItem> items = new ArrayList<>();
        if (item != null) items.add(item);
        setQueue(items, 0, seekToMs);
    }

    private void setQueue(List<PlaybackQueueItem> items, int startIndex, long seekToMs) {
        synchronized (queueLock) {
            queue = new ArrayList<>(items != null ? items : new ArrayList<>());
            if (queue.isEmpty()) {
                Log.w(TAG, "setQueue: empty queue; stopping playback");
                currentIndex = C.INDEX_UNSET;
                currentPlaybackInstanceId = 0L;
                playbackCompletedState = false;
                accumulatedPlayedMs = 0L;
                lastProgressSampleMs = -1L;
                lastKnownPositionMs = 0L;
                lastKnownDurationMs = 0L;
                if (player != null) {
                    player.clearMediaItems();
                    player.stop();
                }
                displayTitle = "";
                displayArtist = "";
                displayAlbum = "";
                refreshNotificationUiState();
                emitQueueChanged();
                return;
            }

            currentIndex = clampIndex(startIndex, queue.size());
            currentPlaybackInstanceId = nextPlaybackInstanceId();
            resetPlayedProgress(Math.max(0L, seekToMs));
            playbackCompletedState = false;
            suppressUpcomingTransition(currentIndex);
            PlaybackQueueItem startItem = queue.get(currentIndex);
            // 2026-05-11 #15: log first 5 ids of the queue for end-to-end trace.
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < Math.min(5, queue.size()); i++) {
                if (i > 0) ids.append(',');
                ids.append(queue.get(i).songId);
            }
            Log.i(TAG, "setQueue: starting index=" + currentIndex
                    + " size=" + queue.size()
                    + " seekMs=" + Math.max(0L, seekToMs)
                    + " firstIds=[" + ids + "]"
                    + " file=" + summarizePath(startItem.filePath));

            player.setMediaItems(PlaybackQueueItem.toMediaItems(queue), currentIndex, Math.max(0L, seekToMs));
            applyLoopMode();
            player.prepare();
            player.play();
            refreshNotificationUiState();
            emitQueueChanged();
        }
    }

    private void replaceUpcoming(List<PlaybackQueueItem> items) {
        synchronized (queueLock) {
            // 2026-05-11 #15: log entry state + incoming items for full trace.
            StringBuilder incomingIds = new StringBuilder();
            int incomingSize = items != null ? items.size() : 0;
            for (int i = 0; i < Math.min(5, incomingSize); i++) {
                if (i > 0) incomingIds.append(',');
                incomingIds.append(items.get(i).songId);
            }
            long currentSongId = (currentIndex >= 0 && currentIndex < queue.size())
                    ? queue.get(currentIndex).songId : -1L;
            Log.i(TAG, "replaceUpcoming: entry currentIndex=" + currentIndex
                    + " queueSize=" + queue.size()
                    + " currentSongId=" + currentSongId
                    + " incomingSize=" + incomingSize
                    + " incomingFirstIds=[" + incomingIds + "]");
            if (currentIndex == C.INDEX_UNSET || currentIndex >= queue.size()) {
                Log.w(TAG, "replaceUpcoming: no usable current, falling back to setQueue");
                setQueue(items, 0, 0L);
                return;
            }

            ArrayList<PlaybackQueueItem> rebuilt = new ArrayList<>();
            for (int i = 0; i <= currentIndex && i < queue.size(); i++) rebuilt.add(queue.get(i));
            if (items != null) rebuilt.addAll(items);
            queue = rebuilt;

            int fromIndex = Math.min(currentIndex + 1, player.getMediaItemCount());
            int toIndex = player.getMediaItemCount();
            player.replaceMediaItems(fromIndex, toIndex, PlaybackQueueItem.toMediaItems(items));
            // Log final queue state after the rebuild.
            StringBuilder finalIds = new StringBuilder();
            for (int i = 0; i < Math.min(5, queue.size()); i++) {
                if (i > 0) finalIds.append(',');
                finalIds.append(queue.get(i).songId);
            }
            Log.i(TAG, "replaceUpcoming: after rebuild queueSize=" + queue.size()
                    + " currentIndex=" + currentIndex
                    + " firstIds=[" + finalIds + "]"
                    + " replaceMediaItems(from=" + fromIndex + ", to=" + toIndex + ")");
            emitQueueChanged();
        }
    }

    private void insertAfterCurrent(List<PlaybackQueueItem> items) {
        synchronized (queueLock) {
            if (currentIndex == C.INDEX_UNSET || queue.isEmpty()) {
                setQueue(items, 0, 0L);
                return;
            }
            if (items == null || items.isEmpty()) return;
            queue.addAll(currentIndex + 1, items);
            player.addMediaItems(currentIndex + 1, PlaybackQueueItem.toMediaItems(items));
            emitQueueChanged();
        }
    }

    private void appendToQueue(List<PlaybackQueueItem> items) {
        synchronized (queueLock) {
            if (queue.isEmpty()) {
                setQueue(items, 0, 0L);
                return;
            }
            if (items == null || items.isEmpty()) return;
            queue.addAll(items);
            player.addMediaItems(PlaybackQueueItem.toMediaItems(items));
            emitQueueChanged();
        }
    }

    private void clearQueueAfterCurrent() {
        synchronized (queueLock) {
            if (currentIndex == C.INDEX_UNSET || currentIndex >= queue.size() - 1) return;
            queue = new ArrayList<>(queue.subList(0, currentIndex + 1));
            player.removeMediaItems(currentIndex + 1, player.getMediaItemCount());
            emitQueueChanged();
        }
    }

    private void playIndex(int index) {
        synchronized (queueLock) {
            if (index < 0 || index >= queue.size()) return;
            TransitionSnapshot snapshot = captureTransitionSnapshot("user_jump", -1f);
            currentIndex = index;
            currentPlaybackInstanceId = nextPlaybackInstanceId();
            resetPlayedProgress(0L);
            playbackCompletedState = false;
            suppressUpcomingTransition(index);
            player.seekToDefaultPosition(index);
            player.play();
            emitCurrentChanged(snapshot);
        }
    }

    private void nextTrack(String action, float prevFractionOverride) {
        synchronized (queueLock) {
            if (queue.isEmpty()) return;
            TransitionSnapshot snapshot = captureTransitionSnapshot(
                    action != null && !action.isEmpty() ? action : "user_next",
                    prevFractionOverride
            );

            int newIndex;
            if (currentIndex + 1 < queue.size()) {
                newIndex = currentIndex + 1;
            } else if (loopMode == LOOP_ALL) {
                newIndex = 0;
            } else {
                rememberStopTransition(snapshot.action);
                handleQueueEndedState("user_next_end");
                emitQueueEnded();
                return;
            }

            currentIndex = newIndex;
            currentPlaybackInstanceId = nextPlaybackInstanceId();
            resetPlayedProgress(0L);
            suppressUpcomingTransition(newIndex);
            player.seekToDefaultPosition(newIndex);
            player.play();
            emitCurrentChanged(snapshot);
        }
    }

    private void prevTrack(float prevFractionOverride) {
        synchronized (queueLock) {
            if (queue.isEmpty()) return;
            long pos = currentPositionMs();
            if (pos > 3000 && currentIndex >= 0) {
                seekTo(0L);
                return;
            }
            if (currentIndex <= 0) {
                seekTo(0L);
                return;
            }

            TransitionSnapshot snapshot = captureTransitionSnapshot("user_prev", prevFractionOverride);
            currentIndex--;
            currentPlaybackInstanceId = nextPlaybackInstanceId();
            resetPlayedProgress(0L);
            suppressUpcomingTransition(currentIndex);
            player.seekToDefaultPosition(currentIndex);
            player.play();
            emitCurrentChanged(snapshot);
        }
    }

    private void seekTo(long positionMs) {
        if (player == null) return;
        player.seekTo(Math.max(0L, positionMs));
        lastKnownPositionMs = Math.max(0L, positionMs);
        lastProgressSampleMs = lastKnownPositionMs;
    }

    private void setLoopMode(int mode) {
        if (mode < LOOP_OFF || mode > LOOP_ALL) return;
        loopMode = mode;
        applyLoopMode();
    }

    private void applyLoopMode() {
        if (player == null) return;
        switch (loopMode) {
            case LOOP_ONE:
                player.setRepeatMode(Player.REPEAT_MODE_ONE);
                break;
            case LOOP_ALL:
                player.setRepeatMode(Player.REPEAT_MODE_ALL);
                break;
            case LOOP_OFF:
            default:
                player.setRepeatMode(Player.REPEAT_MODE_OFF);
                break;
        }
    }

    private void emitQueueChanged() {
        Bundle bundle = new Bundle();
        synchronized (queueLock) {
            bundle.putInt("length", queue.size());
            bundle.putInt("currentIndex", currentIndex);
            // 2026-05-11 #15: include first 5 ids + 2 around currentIndex so the
            // JS DBG log can see what Media3's queue actually looks like. Helps
            // verify replaceUpcoming behaviour without needing adb logcat.
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < Math.min(5, queue.size()); i++) {
                if (i > 0) ids.append(',');
                ids.append(queue.get(i).songId);
            }
            bundle.putString("firstIds", ids.toString());
            if (currentIndex >= 0 && currentIndex < queue.size()) {
                bundle.putLong("currentSongId", queue.get(currentIndex).songId);
            }
            if (currentIndex + 1 >= 0 && currentIndex + 1 < queue.size()) {
                bundle.putLong("nextSongId", queue.get(currentIndex + 1).songId);
            }
        }
        emitCustomCommandToControllers(PlaybackCommandContract.EVT_QUEUE_CHANGED, bundle);
    }

    private void emitQueueEnded() {
        emitCustomCommandToControllers(PlaybackCommandContract.EVT_QUEUE_ENDED, new Bundle());
    }

    private void emitAudioTimeUpdate() {
        long positionMs = currentPositionMs();
        long durationMs = currentDurationMs();
        noteProgressSample(positionMs);

        Bundle bundle = new Bundle();
        bundle.putDouble("position", positionMs / 1000.0);
        bundle.putDouble("duration", durationMs / 1000.0);
        bundle.putBoolean("isPlaying", player != null && player.isPlaying());
        bundle.putLong("playedMs", getAccumulatedPlayedMsSnapshot());
        bundle.putLong("durationMs", durationMs);
        bundle.putLong("playbackInstanceId", currentPlaybackInstanceId);
        emitCustomCommandToControllers(PlaybackCommandContract.EVT_AUDIO_TIME_UPDATE, bundle);
    }

    private void emitAudioPlayStateChanged(boolean isPlaying) {
        Bundle bundle = new Bundle();
        bundle.putBoolean("isPlaying", isPlaying);
        emitCustomCommandToControllers(PlaybackCommandContract.EVT_AUDIO_PLAY_STATE_CHANGED, bundle);
    }

    // 2026-05-11 #19: dedupe-aware emit. Only fires when the play state
    // actually transitions, so repeated emissions during a buffering loop
    // (or multiple onIsPlayingChanged calls for the same intended state)
    // don't churn the JS UI.
    private void emitPlayStateIfChanged(boolean isPlaying) {
        if (lastEmittedPlayState != null && lastEmittedPlayState == isPlaying) return;
        lastEmittedPlayState = isPlaying;
        emitAudioPlayStateChanged(isPlaying);
    }

    private void emitCurrentChanged(TransitionSnapshot snapshot) {
        Bundle data = new Bundle();
        data.putString("action", snapshot.action);
        data.putInt("prevIndex", snapshot.prevIndex);
        data.putFloat("prevFraction", snapshot.prevFraction);
        data.putLong("prevPlayedMs", snapshot.prevPlayedMs);
        data.putLong("prevDurationMs", snapshot.prevDurationMs);
        data.putLong("prevPlaybackInstanceId", snapshot.prevPlaybackInstanceId);
        data.putLong("currentPlaybackInstanceId", currentPlaybackInstanceId);

        PlaybackQueueItem prevItem = snapshot.prevItem;
        if (prevItem != null) {
            data.putInt("prevSongId", prevItem.songId);
            data.putString("prevFilePath", prevItem.filePath);
            data.putString("prevFilename", prevItem.fileName());
            data.putString("prevTitle", prevItem.title);
        }

        data.putInt("newIndex", currentIndex);
        data.putInt("queueLength", queue.size());
        PlaybackQueueItem currentItem = currentItem();
        if (currentItem != null) {
            data.putInt("songId", currentItem.songId);
            data.putString("filePath", currentItem.filePath);
            data.putString("title", currentItem.title);
            data.putString("artist", currentItem.artist);
            data.putString("album", currentItem.album);
        }

        rememberTransition(snapshot, currentItem);
        refreshNotificationUiState();
        emitCustomCommandToControllers(PlaybackCommandContract.EVT_QUEUE_CURRENT_CHANGED, data);
    }

    private Bundle buildAudioStateBundle() {
        Bundle ret = new Bundle();
        long positionMs = currentPositionMs();
        long durationMs = currentDurationMs();
        ret.putDouble("position", positionMs / 1000.0);
        ret.putDouble("duration", durationMs / 1000.0);
        ret.putLong("playedMs", getAccumulatedPlayedMsSnapshot());
        ret.putLong("durationMs", durationMs);
        ret.putBoolean("isPlaying", player != null && player.isPlaying());
        ret.putBoolean("completedState", playbackCompletedState);
        ret.putLong("currentPlaybackInstanceId", currentPlaybackInstanceId);
        PlaybackQueueItem item = currentItem();
        ret.putString("filePath", item != null ? item.filePath : "");
        ret.putString("filename", item != null ? item.fileName() : "");
        ret.putString("title", item != null ? item.title : "");
        ret.putString("artist", item != null ? item.artist : "");
        ret.putString("album", item != null ? item.album : "");
        return ret;
    }

    private Bundle buildQueueStateBundle() {
        Bundle ret = new Bundle();
        synchronized (queueLock) {
            ret.putInt("currentIndex", currentIndex);
            ret.putInt("length", queue.size());
            ret.putInt("loopMode", loopMode);
            ret.putBoolean("isPlaying", player != null && player.isPlaying());
            ret.putLong("currentPlaybackInstanceId", currentPlaybackInstanceId);
            ret.putParcelableArrayList("items", PlaybackQueueItem.toBundleList(queue));
        }
        return ret;
    }

    private Bundle buildTransportStateBundle() {
        Bundle bundle = buildAudioStateBundle();
        bundle.putAll(buildQueueStateBundle());
        return bundle;
    }

    private Bundle errorBundle(String message, String path) {
        Bundle bundle = new Bundle();
        bundle.putString("error", message != null ? message : "Unknown playback error");
        if (path != null && !path.isEmpty()) bundle.putString("path", path);
        return bundle;
    }

    private void emitCustomCommandToControllers(String action, Bundle args) {
        if (mediaSession == null || connectedControllers.isEmpty()) return;
        SessionCommand command = PlaybackCommandContract.command(action);
        for (MediaSession.ControllerInfo controller : connectedControllers) {
            sendCustomCommand(controller, action, args);
        }
    }

    private void sendCustomCommand(MediaSession.ControllerInfo controller, String action, Bundle args) {
        if (mediaSession == null || controller == null) return;
        try {
            mediaSession.sendCustomCommand(controller, PlaybackCommandContract.command(action), args != null ? args : Bundle.EMPTY);
        } catch (Exception e) {
            Log.w(TAG, "sendCustomCommand failed: " + action + " to " + controller.getPackageName(), e);
        }
    }

    private void suppressUpcomingTransition(int expectedIndex) {
        suppressNextTransitionEvent = true;
        suppressedTransitionIndex = expectedIndex;
    }

    private void startProgressUpdates() {
        mainHandler.removeCallbacks(progressRunnable);
        mainHandler.post(progressRunnable);
    }

    private void stopProgressUpdates() {
        mainHandler.removeCallbacks(progressRunnable);
    }

    private long currentPositionMs() {
        if (player == null) return lastKnownPositionMs;
        long pos = Math.max(0L, player.getCurrentPosition());
        lastKnownPositionMs = pos;
        return pos;
    }

    private long currentDurationMs() {
        if (player == null) return lastKnownDurationMs;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET || duration < 0L) {
            return lastKnownDurationMs;
        }
        lastKnownDurationMs = duration;
        return duration;
    }

    private void noteProgressSample(long positionMs) {
        long safePos = Math.max(0L, positionMs);
        if (lastProgressSampleMs >= 0L) {
            long delta = safePos - lastProgressSampleMs;
            if (delta > 0L && delta <= 2000L) {
                accumulatedPlayedMs += delta;
            }
        }
        lastProgressSampleMs = safePos;
        lastKnownPositionMs = safePos;
    }

    private void resetPlayedProgress(long initialPositionMs) {
        accumulatedPlayedMs = 0L;
        lastProgressSampleMs = Math.max(0L, initialPositionMs);
        lastKnownPositionMs = Math.max(0L, initialPositionMs);
        lastKnownDurationMs = currentDurationMs();
    }

    /**
     * Public so Kotlin PlaybackEngine can query the live accumulator —
     * used as a fallback when there's no recent transition snapshot
     * available (e.g. very first transition on a freshly-rebuilt Activity).
     */
    public long getAccumulatedPlayedMsSnapshot() {
        long dur = currentDurationMs();
        if (dur <= 0L) return accumulatedPlayedMs;
        return Math.min(accumulatedPlayedMs, dur);
    }

    /** Snapshot of the previous song's accumulated played-ms, captured
     *  immediately before resetPlayedProgress on each transition. */
    public long getLastTransitionPrevPlayedMs() { return lastTransitionPrevPlayedMs; }

    /** Snapshot of the previous song's duration captured alongside
     *  lastTransitionPrevPlayedMs. */
    public long getLastTransitionPrevDurationMs() { return lastTransitionPrevDurationMs; }

    /** Wall-clock ms (System.currentTimeMillis) when the snapshot above
     *  was captured. PlaybackEngine uses this to decide whether the
     *  snapshot belongs to the transition it's currently handling. */
    public long getLastTransitionAtMs() { return lastTransitionAtMs; }

    /** Public so Kotlin can read duration without going through the
     *  MediaController for every fraction calculation. */
    public long getCurrentDurationMsSnapshot() { return currentDurationMs(); }

    /** Push #66: current playback session id. Incremented on every
     *  transition / queue rebuild / user-jump. Cold-start reconciliation
     *  uses this to detect "the same playback session is still active"
     *  vs "a new session started" cases. Returns 0L when no session is
     *  loaded. */
    public long getCurrentPlaybackInstanceId() { return currentPlaybackInstanceId; }

    /** Push #71: current playback position (ms) of whatever the service
     *  is playing. Used by cold-start to log the divergence between
     *  service state and DataStore state when deciding whether to skip
     *  prepareForResume. */
    public long getCurrentPositionMsSnapshot() {
        if (player == null) return lastKnownPositionMs;
        try {
            return Math.max(0L, player.getCurrentPosition());
        } catch (Throwable t) {
            return lastKnownPositionMs;
        }
    }

    /** Push #65: filename (mediaId) of the currently-playing item, or
     *  empty string if no item is loaded. Used by MainActivity.onPause to
     *  identify which song the to-be-flushed accumulator belongs to. */
    public String getCurrentMediaIdSnapshot() {
        if (player == null) return "";
        try {
            androidx.media3.common.MediaItem mi = player.getCurrentMediaItem();
            if (mi == null) return "";
            String id = mi.mediaId;
            return id == null ? "" : id;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Push #65: zero the played-ms accumulator without touching the
     *  player. Called by Kotlin's flushCurrentPlayback() after it has
     *  synthesized a SignalTimeline event from the current accumulator
     *  value, so the next session doesn't double-count the same ms. */
    public void markEvidenceFlushed() {
        accumulatedPlayedMs = 0L;
        // Keep lastProgressSampleMs at the current position so the next
        // tick continues to accumulate forward from "now" rather than
        // re-counting playback that happened before the flush.
        lastProgressSampleMs = currentPositionMs();
    }

    /** Push #66: rolling buffer of the last N transitions, persisted to
     *  SharedPreferences so cold-start reconciliation can match a pending
     *  listen snapshot against an authoritative native transition.
     *  Capacitor parity (`rememberTransition` in MusicPlaybackService.java). */
    private static final String TRANSITIONS_PREFS_NAME = "playback_transitions_history";
    private static final String TRANSITIONS_KEY = "history_json";
    private static final int MAX_REMEMBERED_TRANSITIONS = 24;

    private void rememberTransitionToPrefs(TransitionSnapshot snap) {
        if (snap == null || snap.prevPlaybackInstanceId <= 0L) return;
        try {
            android.content.SharedPreferences prefs = getSharedPreferences(
                    TRANSITIONS_PREFS_NAME, android.content.Context.MODE_PRIVATE);
            String raw = prefs.getString(TRANSITIONS_KEY, null);
            org.json.JSONArray arr = (raw != null && !raw.isEmpty())
                    ? new org.json.JSONArray(raw) : new org.json.JSONArray();
            org.json.JSONObject ev = new org.json.JSONObject();
            ev.put("timestamp", System.currentTimeMillis());
            ev.put("action", snap.action != null ? snap.action : "");
            ev.put("prevPlaybackInstanceId", snap.prevPlaybackInstanceId);
            ev.put("prevPlayedMs", snap.prevPlayedMs);
            ev.put("prevDurationMs", snap.prevDurationMs);
            ev.put("prevFraction", snap.prevFraction);
            if (snap.prevItem != null) {
                ev.put("prevFilename", summarizePath(snap.prevItem.filePath));
                ev.put("prevTitle", snap.prevItem.title);
            }
            arr.put(ev);
            org.json.JSONArray trimmed = new org.json.JSONArray();
            int start = Math.max(0, arr.length() - MAX_REMEMBERED_TRANSITIONS);
            for (int i = start; i < arr.length(); i++) {
                trimmed.put(arr.getJSONObject(i));
            }
            prefs.edit().putString(TRANSITIONS_KEY, trimmed.toString()).apply();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "rememberTransitionToPrefs failed: " + t.getMessage());
        }
    }

    /** Push #66: read the recent transitions history. Used by Kotlin
     *  cold-start reconciliation. */
    public static org.json.JSONArray readRecentTransitionsStatic(android.content.Context ctx) {
        try {
            android.content.SharedPreferences prefs = ctx.getSharedPreferences(
                    TRANSITIONS_PREFS_NAME, android.content.Context.MODE_PRIVATE);
            String raw = prefs.getString(TRANSITIONS_KEY, null);
            if (raw == null || raw.isEmpty()) return new org.json.JSONArray();
            return new org.json.JSONArray(raw);
        } catch (Throwable t) {
            return new org.json.JSONArray();
        }
    }

    /** Push #66: clear transitions buffer (Reset Engine path). */
    public static void clearRecentTransitionsStatic(android.content.Context ctx) {
        try {
            ctx.getSharedPreferences(TRANSITIONS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (Throwable ignore) { }
    }

    /** Push #65: belt-and-suspenders for force-kill cases where
     *  onPause/onDestroy may not complete. The OS calls this when the
     *  user swipes the app off recents. We snapshot the accumulator +
     *  currentMediaId to SharedPreferences so the next cold start can
     *  ingest the leftover listening evidence via Kotlin's pending-
     *  evidence path. */
    @Override
    public void onTaskRemoved(android.content.Intent rootIntent) {
        try {
            long played = getAccumulatedPlayedMsSnapshot();
            long dur = getCurrentDurationMsSnapshot();
            String mediaId = getCurrentMediaIdSnapshot();
            if (played >= 1000L && dur > 0L && !mediaId.isEmpty()) {
                android.content.SharedPreferences sp = getSharedPreferences(
                        "playback_pending_evidence", android.content.Context.MODE_PRIVATE);
                sp.edit()
                        .putString("mediaId", mediaId)
                        .putLong("playedMs", played)
                        .putLong("durationMs", dur)
                        .putLong("playbackInstanceId", currentPlaybackInstanceId)
                        .putLong("capturedAtMs", System.currentTimeMillis())
                        .apply();
                android.util.Log.i(TAG,
                        "onTaskRemoved: persisted pending evidence mediaId=" + summarizePath(mediaId)
                                + " played=" + played + "ms dur=" + dur + "ms");
            } else {
                android.util.Log.i(TAG,
                        "onTaskRemoved: nothing to persist (played=" + played + " dur=" + dur + " id=" + summarizePath(mediaId) + ")");
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "onTaskRemoved flush failed: " + t.getMessage());
        }
        super.onTaskRemoved(rootIntent);
    }

    private float computePrevFraction() {
        long dur = currentDurationMs();
        if (dur <= 0L) return 0f;
        long pos = playbackCompletedState && dur > 0L ? dur : currentPositionMs();
        noteProgressSample(pos);
        return Math.max(0f, Math.min(1f, (float) getAccumulatedPlayedMsSnapshot() / (float) dur));
    }

    private long nextPlaybackInstanceId() {
        long now = System.currentTimeMillis();
        if (now <= currentPlaybackInstanceId) now = currentPlaybackInstanceId + 1L;
        return now;
    }

    private int clampIndex(int idx, int size) {
        if (size <= 0) return C.INDEX_UNSET;
        if (idx < 0) return 0;
        if (idx >= size) return size - 1;
        return idx;
    }

    @Nullable
    private PlaybackQueueItem currentItem() {
        synchronized (queueLock) {
            if (currentIndex < 0 || currentIndex >= queue.size()) return null;
            return queue.get(currentIndex);
        }
    }

    private String currentItemPath() {
        PlaybackQueueItem item = currentItem();
        return item != null ? item.filePath : "";
    }

    private void updateNotificationState(Bundle args) {
        Bundle safe = args != null ? args : Bundle.EMPTY;
        if (safe.containsKey(PlaybackCommandContract.KEY_TITLE)) {
            displayTitle = safe.getString(PlaybackCommandContract.KEY_TITLE, "");
        }
        if (safe.containsKey(PlaybackCommandContract.KEY_ARTIST)) {
            displayArtist = safe.getString(PlaybackCommandContract.KEY_ARTIST, "");
        }
        if (safe.containsKey(PlaybackCommandContract.KEY_ALBUM)) {
            displayAlbum = safe.getString(PlaybackCommandContract.KEY_ALBUM, "");
        }
        refreshNotificationUiState();
    }

    private void handleNotificationFavorite() {
        Bundle favoriteResult = toggleCurrentFavoriteInStorage();
        refreshNotificationUiState();
        emitMediaAction("favorite", favoriteResult);
    }

    private void handleNotificationDismiss() {
        rememberStopTransition("user_dismiss");
        clearSavedPlaybackState();
        emitMediaAction("dismiss", buildCurrentMediaPayload());
        emitPlayStateIfChanged(false);
        mainHandler.postDelayed(() -> stopPlaybackAndCleanup(true), 120L);
    }

    private void stopPlaybackAndCleanup(boolean clearQueueEvent) {
        stopProgressUpdates();
        playbackCompletedState = false;
        synchronized (queueLock) {
            queue.clear();
            currentIndex = C.INDEX_UNSET;
        }
        if (player != null) {
            try {
                player.pause();
                player.clearMediaItems();
            } catch (Exception e) {
                Log.w(TAG, "stopPlaybackAndCleanup failed to clear player", e);
            }
        }
        suppressNextTransitionEvent = false;
        suppressedTransitionIndex = C.INDEX_UNSET;
        accumulatedPlayedMs = 0L;
        lastProgressSampleMs = -1L;
        lastKnownPositionMs = 0L;
        lastKnownDurationMs = 0L;
        currentPlaybackInstanceId = 0L;
        displayTitle = "";
        displayArtist = "";
        displayAlbum = "";
        refreshNotificationUiState();
        if (clearQueueEvent) {
            emitQueueChanged();
        }
        emitPlayStateIfChanged(false);
        pauseAllPlayersAndStopSelf();
    }

    private void refreshNotificationUiState() {
        if (mediaSession == null) return;
        ArrayList<CommandButton> buttons = buildNotificationButtons();
        try {
            mediaSession.setCustomLayout(buttons);
            mediaSession.setMediaButtonPreferences(buttons);
        } catch (Exception e) {
            Log.w(TAG, "refreshNotificationUiState failed", e);
        }
    }

    private ArrayList<CommandButton> buildNotificationButtons() {
        ArrayList<CommandButton> buttons = new ArrayList<>();
        boolean hasCurrentMedia = currentItem() != null || !currentItemPath().isEmpty();
        boolean isFavorite = hasCurrentMedia && isCurrentFavorite();

        // Push #39: match the Capacitor build verbatim — both Favorite
        // and Close declare ONLY SLOT_OVERFLOW. Push #34–#37 each tried a
        // different slot combination (SLOT_BACK, SLOT_FORWARD,
        // SLOT_FORWARD+SLOT_OVERFLOW) hoping to surface Favorite inline,
        // but the slot conflicts displaced Close on the user's
        // GrapheneOS notification renderer. The Capacitor config (both
        // SLOT_OVERFLOW) is the known-working baseline.
        buttons.add(new CommandButton.Builder(
                isFavorite ? CommandButton.ICON_HEART_FILLED : CommandButton.ICON_HEART_UNFILLED)
                .setSessionCommand(PlaybackCommandContract.command(PlaybackCommandContract.CMD_NOTIFICATION_TOGGLE_FAVORITE))
                .setDisplayName(isFavorite ? "Unfavorite" : "Favorite")
                .setEnabled(hasCurrentMedia)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build());

        buttons.add(new CommandButton.Builder(CommandButton.ICON_STOP)
                .setSessionCommand(PlaybackCommandContract.command(PlaybackCommandContract.CMD_NOTIFICATION_DISMISS))
                .setDisplayName("Close")
                .setEnabled(hasCurrentMedia)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build());

        return buttons;
    }

    private SharedPreferences getCapacitorStoragePrefs() {
        return getSharedPreferences(CAPACITOR_STORAGE_PREFS, MODE_PRIVATE);
    }

    private void clearSavedPlaybackState() {
        getCapacitorStoragePrefs().edit().remove(PREF_KEY_PLAYBACK_STATE).commit();
    }

    private boolean isCurrentFavorite() {
        String filename = summarizePath(currentItemPath());
        if (filename.isEmpty() || "(none)".equals(filename)) return false;
        try {
            String raw = getCapacitorStoragePrefs().getString(PREF_KEY_FAVORITES, null);
            if (raw == null || raw.isEmpty()) return false;
            JSONArray favoritesPayload = new JSONObject(raw).optJSONArray("filenames");
            if (favoritesPayload == null) return false;
            for (int i = 0; i < favoritesPayload.length(); i++) {
                if (filename.equalsIgnoreCase(favoritesPayload.optString(i, ""))) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isCurrentFavorite failed", e);
        }
        return false;
    }

    private Bundle buildCurrentMediaPayload() {
        Bundle data = new Bundle();
        PlaybackQueueItem item = currentItem();
        if (item != null) {
            data.putInt("songId", item.songId);
            data.putString("filePath", item.filePath);
            data.putString("filename", item.fileName());
            data.putString("title", !item.title.isEmpty() ? item.title : item.fileName());
            data.putString("artist", !item.artist.isEmpty() ? item.artist : displayArtist);
            data.putString("album", !item.album.isEmpty() ? item.album : displayAlbum);
            return data;
        }

        String path = currentItemPath();
        String filename = summarizePath(path);
        data.putInt("songId", -1);
        data.putString("filePath", path);
        data.putString("filename", filename);
        data.putString("title", !displayTitle.isEmpty()
                ? displayTitle
                : (!filename.isEmpty() && !"(none)".equals(filename) ? filename : getString(R.string.app_name)));
        data.putString("artist", displayArtist);
        data.putString("album", displayAlbum);
        return data;
    }

    private Bundle toggleCurrentFavoriteInStorage() {
        Bundle result = buildCurrentMediaPayload();
        String filename = result.getString("filename", "");
        if (filename.isEmpty() || "(none)".equals(filename)) {
            result.putBoolean("ok", false);
            return result;
        }
        try {
            SharedPreferences prefs = getCapacitorStoragePrefs();
            String rawFavorites = prefs.getString(PREF_KEY_FAVORITES, null);
            ArrayList<String> filenames = new ArrayList<>();
            if (rawFavorites != null && !rawFavorites.isEmpty()) {
                JSONArray storedFavorites = new JSONObject(rawFavorites).optJSONArray("filenames");
                if (storedFavorites != null) {
                    for (int i = 0; i < storedFavorites.length(); i++) {
                        String value = storedFavorites.optString(i, "");
                        if (!value.isEmpty()) filenames.add(value);
                    }
                }
            }

            int matchIndex = -1;
            for (int i = 0; i < filenames.size(); i++) {
                if (filename.equalsIgnoreCase(filenames.get(i))) {
                    matchIndex = i;
                    break;
                }
            }

            boolean isFavorite;
            if (matchIndex >= 0) {
                filenames.remove(matchIndex);
                isFavorite = false;
            } else {
                filenames.add(filename);
                isFavorite = true;
            }

            boolean unDisliked = false;
            JSONArray dislikesOut = new JSONArray();
            String rawDislikes = prefs.getString(PREF_KEY_DISLIKED_SONGS, null);
            if (rawDislikes != null && !rawDislikes.isEmpty()) {
                JSONArray storedDislikes = new JSONArray(rawDislikes);
                for (int i = 0; i < storedDislikes.length(); i++) {
                    String value = storedDislikes.optString(i, "");
                    if (value.isEmpty()) continue;
                    if (isFavorite && filename.equalsIgnoreCase(value)) {
                        unDisliked = true;
                        continue;
                    }
                    dislikesOut.put(value);
                }
            }

            JSONArray favoritesOut = new JSONArray();
            for (String value : filenames) {
                favoritesOut.put(value);
            }
            JSONObject favoritesPayload = new JSONObject();
            favoritesPayload.put("filenames", favoritesOut);

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PREF_KEY_FAVORITES, favoritesPayload.toString());
            if (isFavorite || rawDislikes != null) {
                editor.putString(PREF_KEY_DISLIKED_SONGS, dislikesOut.toString());
            }
            editor.commit();

            result.putBoolean("ok", true);
            result.putBoolean("isFavorite", isFavorite);
            result.putBoolean("unDisliked", unDisliked);
        } catch (Exception e) {
            Log.w(TAG, "toggleCurrentFavoriteInStorage failed", e);
            result.putBoolean("ok", false);
        }
        return result;
    }

    private void emitMediaAction(String action, @Nullable Bundle payload) {
        Bundle data = payload != null ? new Bundle(payload) : new Bundle();
        data.putString("action", action != null ? action : "");
        emitCustomCommandToControllers(PlaybackCommandContract.EVT_MEDIA_ACTION, data);
    }

    private CharSequence notificationTitle() {
        PlaybackQueueItem item = currentItem();
        if (item != null) {
            if (item.title != null && !item.title.isEmpty()) return item.title;
            String filename = item.fileName();
            if (!filename.isEmpty()) return filename;
        }
        if (displayTitle != null && !displayTitle.isEmpty()) return displayTitle;
        return getString(R.string.app_name);
    }

    private CharSequence notificationText() {
        PlaybackQueueItem item = currentItem();
        String artist = item != null && item.artist != null && !item.artist.isEmpty() ? item.artist : displayArtist;
        String album = item != null && item.album != null && !item.album.isEmpty() ? item.album : displayAlbum;
        if (artist != null && !artist.isEmpty()) {
            if (album != null && !album.isEmpty() && !"Unknown".equalsIgnoreCase(album)) {
                return artist + " · " + album;
            }
            return artist;
        }
        if (album != null && !album.isEmpty() && !"Unknown".equalsIgnoreCase(album)) {
            return album;
        }
        return "";
    }

    private String resolveTransitionAction(int reason) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return "auto_advance";
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) return "user_jump";
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return "queue_update";
        return "auto_advance";
    }

    private TransitionSnapshot captureTransitionSnapshot(String action, float prevFractionOverride) {
        PlaybackQueueItem prevItem = currentItem();
        float prevFraction = prevFractionOverride >= 0f ? prevFractionOverride : computePrevFraction();
        return new TransitionSnapshot(
                action,
                currentIndex,
                prevFraction,
                getAccumulatedPlayedMsSnapshot(),
                currentDurationMs(),
                currentPlaybackInstanceId,
                prevItem
        );
    }

    private SharedPreferences getRecoveryPrefs() {
        return getSharedPreferences(RECOVERY_PREFS_NAME, MODE_PRIVATE);
    }

    private void rememberTransition(TransitionSnapshot snapshot, @Nullable PlaybackQueueItem currentItem) {
        if (snapshot == null || snapshot.prevPlaybackInstanceId <= 0L) return;
        try {
            JSONObject event = new JSONObject();
            event.put("eventId", "native_" + snapshot.prevPlaybackInstanceId + "_" + System.currentTimeMillis());
            event.put("timestamp", System.currentTimeMillis());
            event.put("action", snapshot.action != null ? snapshot.action : "");
            event.put("prevIndex", snapshot.prevIndex);
            event.put("prevFraction", snapshot.prevFraction);
            event.put("prevPlayedMs", snapshot.prevPlayedMs);
            event.put("prevDurationMs", snapshot.prevDurationMs);
            event.put("prevPlaybackInstanceId", snapshot.prevPlaybackInstanceId);
            event.put("currentPlaybackInstanceId", currentPlaybackInstanceId);

            if (snapshot.prevItem != null) {
                event.put("prevSongId", snapshot.prevItem.songId);
                event.put("prevFilePath", snapshot.prevItem.filePath);
                event.put("prevFilename", summarizePath(snapshot.prevItem.filePath));
                event.put("prevTitle", snapshot.prevItem.title);
                event.put("prevArtist", snapshot.prevItem.artist);
                event.put("prevAlbum", snapshot.prevItem.album);
            }
            if (currentItem != null) {
                event.put("songId", currentItem.songId);
                event.put("filePath", currentItem.filePath);
                event.put("filename", summarizePath(currentItem.filePath));
                event.put("title", currentItem.title);
                event.put("artist", currentItem.artist);
                event.put("album", currentItem.album);
            }

            SharedPreferences prefs = getRecoveryPrefs();
            String raw = prefs.getString(RECOVERY_TRANSITIONS_KEY, null);
            JSONArray arr = raw != null && !raw.isEmpty() ? new JSONArray(raw) : new JSONArray();
            arr.put(event);

            JSONArray trimmed = new JSONArray();
            int start = Math.max(0, arr.length() - MAX_RECOVERY_TRANSITIONS);
            for (int i = start; i < arr.length(); i++) {
                trimmed.put(arr.getJSONObject(i));
            }
            prefs.edit().putString(RECOVERY_TRANSITIONS_KEY, trimmed.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "rememberTransition failed", e);
        }
    }

    private void rememberStopTransition(String action) {
        if (currentPlaybackInstanceId <= 0L) return;
        TransitionSnapshot snapshot = captureTransitionSnapshot(action, -1f);
        rememberTransition(snapshot, null);
    }

    private void handleQueueEndedState(String reason) {
        Log.d(TAG, "handleQueueEndedState: reason=" + reason
                + " currentIndex=" + currentIndex
                + " queueLength=" + queue.size());
        playbackCompletedState = false;
        stopProgressUpdates();
        if (player != null) {
            player.pause();
        }
        lastKnownPositionMs = 0L;
        lastKnownDurationMs = 0L;
        accumulatedPlayedMs = 0L;
        currentPlaybackInstanceId = 0L;
        lastProgressSampleMs = -1L;
        refreshNotificationUiState();
        emitPlayStateIfChanged(false);
    }

    private String summarizePath(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "(none)";
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }

    private ListenableFuture<SessionResult> success() {
        return success(new SessionResult(SessionResult.RESULT_SUCCESS));
    }

    private ListenableFuture<SessionResult> success(SessionResult result) {
        return Futures.immediateFuture(result);
    }

    private final class PlaybackNotificationProvider extends DefaultMediaNotificationProvider {
        PlaybackNotificationProvider() {
            super(Media3PlaybackService.this);
        }

        @Override
        protected CharSequence getNotificationContentTitle(MediaMetadata mediaMetadata) {
            return notificationTitle();
        }

        @Override
        protected CharSequence getNotificationContentText(MediaMetadata mediaMetadata) {
            return notificationText();
        }
    }

    private static final class TransitionSnapshot {
        final String action;
        final int prevIndex;
        final float prevFraction;
        final long prevPlayedMs;
        final long prevDurationMs;
        final long prevPlaybackInstanceId;
        final PlaybackQueueItem prevItem;

        TransitionSnapshot(
                String action,
                int prevIndex,
                float prevFraction,
                long prevPlayedMs,
                long prevDurationMs,
                long prevPlaybackInstanceId,
                PlaybackQueueItem prevItem
        ) {
            this.action = action;
            this.prevIndex = prevIndex;
            this.prevFraction = prevFraction;
            this.prevPlayedMs = prevPlayedMs;
            this.prevDurationMs = prevDurationMs;
            this.prevPlaybackInstanceId = prevPlaybackInstanceId;
            this.prevItem = prevItem;
        }
    }
}
