package com.isaivazhi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;

import android.media.MediaMetadataRetriever;
import android.graphics.BitmapFactory;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

public class MusicPlaybackService extends Service {

    private static final String CHANNEL_ID = "music_playback";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "MusicPlaybackService";
    private static final long NAV_DEBOUNCE_MS = 320L;
    public static final String RECOVERY_PREFS_NAME = "playback_recovery_v1";
    public static final String RECOVERY_TRANSITIONS_KEY = "recent_transitions";
    private static final int MAX_RECOVERY_TRANSITIONS = 24;

    public static final String ACTION_PLAY = "com.isaivazhi.app.PLAY";
    public static final String ACTION_PAUSE = "com.isaivazhi.app.PAUSE";
    public static final String ACTION_NEXT = "com.isaivazhi.app.NEXT";
    public static final String ACTION_PREV = "com.isaivazhi.app.PREV";
    public static final String ACTION_UPDATE = "com.isaivazhi.app.UPDATE";
    public static final String ACTION_STOP = "com.isaivazhi.app.STOP";
    public static final String ACTION_DISMISS = "com.isaivazhi.app.DISMISS";
    public static final String ACTION_TOGGLE_FAVORITE = "com.isaivazhi.app.FAVORITE";
    private static final String CAPACITOR_STORAGE_PREFS = "CapacitorStorage";
    private static final String PREF_KEY_PLAYBACK_STATE = "playback_state";
    private static final String PREF_KEY_FAVORITES = "favorites";
    private static final String PREF_KEY_DISLIKED_SONGS = "disliked_songs";

    private MediaSessionCompat mediaSession;
    private PowerManager.WakeLock wakeLock;
    private boolean isPlaying = false;
    String currentTitle = "IsaiVazhi";
    String currentArtist = "";
    String currentAlbum = "";
    String currentFilePath = null;
    private Bitmap cachedAlbumArt = null;
    private String cachedAlbumArtPathKey = null;
    private String cachedAlbumArtTitleKey = null;
    private long currentDuration = 0;   // ms
    private long lastNextCommandAtMs = 0L;
    private long lastPrevCommandAtMs = 0L;
    private long lastNotificationProgressPushAtMs = 0L;

    // ===== NATIVE QUEUE =====
    // Queue is the authoritative source of truth for playback. Native advances
    // autonomously on completion / BT skip / notification skip — JS does NOT
    // need to be alive for playback to continue.

    public static final int LOOP_OFF = 0;
    public static final int LOOP_ONE = 1;
    public static final int LOOP_ALL = 2;

    static class QueueItem {
        int songId;
        String filePath;
        String title;
        String artist;
        String album;

        QueueItem(int songId, String filePath, String title, String artist, String album) {
            this.songId = songId;
            this.filePath = filePath;
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
        }
    }

    private final Object queueLock = new Object();
    private ArrayList<QueueItem> queue = new ArrayList<>();
    private int currentIndex = -1;
    private int loopMode = LOOP_ALL;
    private long lastKnownPositionMs = 0;
    private long lastKnownDurationMs = 0;
    private long accumulatedPlayedMs = 0;
    private long lastProgressSampleMs = -1;
    private long currentPlaybackInstanceId = 0L;
    private boolean autoSkippingBrokenFile = false;
    private boolean playbackCompletedState = false;
    // True once MediaPlayer.onPrepared has fired for the current track and start()
    // was called. False during playFile setup and until prepareAsync completes.
    // Used to distinguish prepare-time errors (do NOT auto-advance) from
    // playback-time errors (auto-advance to recover from a broken file).
    private boolean currentTrackStarted = false;

    // Native audio player
    private MediaPlayer mediaPlayer;
    private Handler positionHandler;
    private Runnable positionRunnable;

    // Audio focus
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;
    private boolean pausedByFocusLoss = false;
    private float originalVolume = 1.0f;

    static MusicPlaybackService instance;
    static MusicBridgePlugin pluginRef;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        createNotificationChannel();
        setupMediaSession();
        currentTitle = getAppDisplayName();
        updateMediaSessionMetadata();
        updatePlaybackState();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "musicplayer:playback");
        wakeLock.setReferenceCounted(false);

        // Audio focus
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes focusAttrs = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(focusAttrs)
                    .setOnAudioFocusChangeListener(this::onAudioFocusChange)
                    .build();
        }

        // Position update handler — emits position to JS every 250ms
        positionHandler = new Handler(Looper.getMainLooper());
        positionRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && isPlaying) {
                    emitTimeUpdate();
                    positionHandler.postDelayed(this, 250);
                }
            }
        };
    }

    // ===== NATIVE AUDIO PLAYBACK =====

    void playFile(String filePath, long seekToMs) {
        try {
            currentFilePath = filePath;
            playbackCompletedState = false;
            currentTrackStarted = false;
            resetPlayedProgress(seekToMs);
            Log.d(TAG, "playFile: index=" + currentIndex + " seekMs=" + seekToMs + " path=" + summarizePath(filePath));

            // Request audio focus before playing
            if (!requestAudioFocus()) {
                Log.w(TAG, "playFile: audio focus denied index=" + currentIndex + " path=" + summarizePath(filePath));
                emitError("Could not obtain audio focus");
                return;
            }
            pausedByFocusLoss = false;

            if (mediaPlayer != null) {
                // Detach terminal listeners before reusing the same player so an
                // intentional queue switch does not also fire a stale error or
                // completion callback from the previous track.
                stopPositionUpdates();
                mediaPlayer.setOnPreparedListener(null);
                mediaPlayer.setOnCompletionListener(null);
                mediaPlayer.setOnErrorListener(null);
                mediaPlayer.reset();
            } else {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build());
                mediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            }

            // Use FileInputStream to handle paths with special characters
            File file = new File(filePath);
            FileInputStream fis = new FileInputStream(file);
            mediaPlayer.setDataSource(fis.getFD());
            fis.close();

            mediaPlayer.setOnPreparedListener(mp -> {
                currentDuration = mp.getDuration();
                lastKnownDurationMs = currentDuration;
                playbackCompletedState = false;
                if (seekToMs > 0 && seekToMs < currentDuration) {
                    mp.seekTo((int) seekToMs);
                }
                mp.start();
                isPlaying = true;
                currentTrackStarted = true;
                lastKnownPositionMs = seekToMs > 0 ? seekToMs : 0;
                lastProgressSampleMs = lastKnownPositionMs;
                Log.d(TAG, "onPrepared: started index=" + currentIndex
                        + " title=" + safeTitle(currentTitle)
                        + " durationMs=" + currentDuration
                        + " queueLength=" + queue.size());
                updateMediaSessionMetadata();
                updatePlaybackState();
                updateNotification();
                startPositionUpdates();
                emitPlayStateChange(true);
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                // Capture final position for bookkeeping before the state resets.
                noteProgressSample(currentDuration);
                lastKnownPositionMs = currentDuration;
                playbackCompletedState = true;
                isPlaying = false;
                stopPositionUpdates();
                Log.d(TAG, "onCompletion: index=" + currentIndex
                        + " title=" + safeTitle(currentTitle)
                        + " queueLength=" + queue.size()
                        + " loopMode=" + loopMode);
                // Native auto-advance — does NOT depend on JS being alive.
                advanceAuto();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                isPlaying = false;
                playbackCompletedState = false;
                boolean wasStarted = currentTrackStarted;
                currentTrackStarted = false;
                stopPositionUpdates();
                Log.e(TAG, "onError: index=" + currentIndex
                        + " title=" + safeTitle(currentTitle)
                        + " what=" + what + " extra=" + extra
                        + " queueLength=" + queue.size()
                        + " wasStarted=" + wasStarted);
                emitError("MediaPlayer error: " + what + "/" + extra + (wasStarted ? "" : " (during prepare)"));
                if (wasStarted) {
                    // Error mid-playback — skip past a broken file so the queue doesn't get stuck.
                    advanceAuto();
                } else {
                    // Error before onPrepared/start — most likely a transient prepare or
                    // seek-before-prepare issue (e.g. after cold start when the previous
                    // MediaPlayer state was stale). Do NOT advanceAuto; that silently
                    // skipped the user's current song. Keep currentIndex on the same
                    // track so the user can retry by tapping play again.
                    Log.w(TAG, "onError before start: keeping currentIndex=" + currentIndex
                            + " (user can retry)");
                    updatePlaybackState();
                    updateNotification();
                    emitPlayStateChange(false);
                }
                return true;
            });

            mediaPlayer.prepareAsync();

        } catch (Exception e) {
            Log.e(TAG, "playFile failed synchronously: index=" + currentIndex
                    + " title=" + safeTitle(currentTitle)
                    + " path=" + summarizePath(filePath)
                    + " queueLength=" + queue.size(), e);
            // File-not-found gets a stable "File not found" prefix + path payload
            // so the JS layer can reconcile the library (remove the song, surface
            // an orphaned embedding for consent-gated removal). Still auto-skip so
            // the queue doesn't get stuck on a broken entry.
            boolean missing = (e instanceof java.io.FileNotFoundException);
            if (missing) {
                emitError("File not found: " + filePath, filePath);
            } else {
                emitError("Play failed: " + e.getMessage());
            }
            handleSynchronousPlaybackFailure("sync_play_failed", e.getMessage());
        }
    }

    void pausePlayback() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            stopPositionUpdates();
            updatePlaybackState();
            updateNotification();
            emitPlayStateChange(false);
        }
    }

    void resumePlayback() {
        // MediaPlayer may be null if the service was recreated. If we have a
        // queue, the simplest recovery is to restart playback of the current
        // queue item from its known position (or from zero if we lost it).
        if (mediaPlayer == null) {
            synchronized (queueLock) {
                if (currentIndex >= 0 && currentIndex < queue.size()) {
                    Log.d(TAG, "resumePlayback: recreating player from queue index=" + currentIndex
                            + " lastKnownPositionMs=" + lastKnownPositionMs);
                    playCurrentFromQueue(lastKnownPositionMs);
                    return;
                }
            }
            if (currentFilePath != null) {
                Log.d(TAG, "resumePlayback: recreating player from raw path lastKnownPositionMs=" + lastKnownPositionMs
                        + " path=" + summarizePath(currentFilePath));
                playFile(currentFilePath, lastKnownPositionMs);
            }
            return;
        }
        if (playbackCompletedState || isTrackAtOrPastEnd()) {
            synchronized (queueLock) {
                Log.d(TAG, "resumePlayback: completed/stale state detected, rebuilding current item"
                        + " currentIndex=" + currentIndex
                        + " queueLength=" + queue.size()
                        + " completed=" + playbackCompletedState
                        + " lastKnownPositionMs=" + lastKnownPositionMs
                        + " lastKnownDurationMs=" + lastKnownDurationMs);
                if (currentIndex >= 0 && currentIndex < queue.size()) {
                    playCurrentFromQueue(0);
                    return;
                }
            }
            if (currentFilePath != null) {
                playFile(currentFilePath, 0);
            }
            return;
        }
        if (!mediaPlayer.isPlaying()) {
            try {
                if (!requestAudioFocus()) {
                    emitError("Could not obtain audio focus");
                    return;
                }
                pausedByFocusLoss = false;
                mediaPlayer.start();
                isPlaying = true;
                updatePlaybackState();
                updateNotification();
                startPositionUpdates();
                emitPlayStateChange(true);
            } catch (Exception e) {
                emitError("Resume failed: " + e.getMessage());
            }
        }
    }

    void seekTo(long positionMs) {
        if (mediaPlayer != null) {
            lastKnownPositionMs = Math.max(0, positionMs);
            lastProgressSampleMs = lastKnownPositionMs;
            playbackCompletedState = false;
            mediaPlayer.seekTo((int) positionMs);
            updatePlaybackState();
            emitTimeUpdate();
        }
    }

    long getCurrentPosition() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (Exception e) {
                return lastKnownPositionMs;
            }
        }
        return lastKnownPositionMs;
    }

    long getDuration() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getDuration();
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    String getCurrentFilePath() {
        return currentFilePath;
    }

    String getCurrentTitle() {
        return currentTitle;
    }

    String getCurrentArtist() {
        return currentArtist;
    }

    String getCurrentAlbum() {
        return currentAlbum;
    }

    boolean isPlaybackCompletedState() {
        return playbackCompletedState;
    }

    boolean isCurrentlyPlaying() {
        return mediaPlayer != null && isPlaying;
    }

    private boolean isTrackAtOrPastEnd() {
        long durationMs = currentDuration > 0 ? currentDuration : lastKnownDurationMs;
        if (durationMs <= 0) return false;
        long positionMs = lastKnownPositionMs;
        if (mediaPlayer != null) {
            try {
                positionMs = Math.max(positionMs, mediaPlayer.getCurrentPosition());
            } catch (Exception e) {
                // Ignore state errors and fall back to the persisted position.
            }
        }
        return positionMs >= Math.max(0, durationMs - 500);
    }

    void setLooping(boolean loop) {
        if (mediaPlayer != null) {
            mediaPlayer.setLooping(loop);
        }
    }

    // ===== QUEUE MANAGEMENT =====
    //
    // Queue operations are the only entry point for playback once the service
    // is running. `playFile` remains a low-level helper but should not be
    // called directly by the plugin — use setQueue / nextTrack / etc instead.

    void setQueue(List<QueueItem> items, int startIndex) {
        setQueue(items, startIndex, 0);
    }

    void setQueue(List<QueueItem> items, int startIndex, long startSeekMs) {
        synchronized (queueLock) {
            queue = new ArrayList<>(items == null ? new ArrayList<QueueItem>() : items);
            Log.d(TAG, "setQueue: size=" + queue.size()
                    + " startIndex=" + startIndex
                    + " startSeekMs=" + startSeekMs);
            if (queue.isEmpty()) {
                currentIndex = -1;
                currentPlaybackInstanceId = 0L;
                Log.d(TAG, "setQueue: empty queue, stopping playback");
                stopAudioAndCleanup();
                return;
            }
            currentIndex = clampIndex(startIndex, queue.size());
            beginPlaybackInstance();
            playCurrentFromQueue(Math.max(0, startSeekMs));
            emitQueueChanged();
        }
    }

    void replaceUpcoming(List<QueueItem> items) {
        synchronized (queueLock) {
            int oldLen = queue.size();
            if (currentIndex < 0 || currentIndex >= queue.size()) {
                // Nothing currently playing — treat like setQueue.
                Log.d(TAG, "replaceUpcoming: no active current item, fallback setQueue size="
                        + (items != null ? items.size() : 0));
                setQueue(items, 0);
                return;
            }
            ArrayList<QueueItem> rebuilt = new ArrayList<>();
            // Keep history + current in place so the user can still go back.
            for (int i = 0; i <= currentIndex; i++) rebuilt.add(queue.get(i));
            if (items != null) {
                for (QueueItem it : items) rebuilt.add(it);
            }
            queue = rebuilt;
            Log.d(TAG, "replaceUpcoming: oldLen=" + oldLen + " newLen=" + queue.size()
                    + " currentIndex=" + currentIndex
                    + " appended=" + (items != null ? items.size() : 0));
            emitQueueChanged();
        }
    }

    void insertAfterCurrent(List<QueueItem> items) {
        synchronized (queueLock) {
            if (currentIndex < 0 || queue.isEmpty()) {
                setQueue(items, 0);
                return;
            }
            if (items != null) {
                queue.addAll(currentIndex + 1, items);
            }
            emitQueueChanged();
        }
    }

    void appendToQueue(List<QueueItem> items) {
        synchronized (queueLock) {
            if (queue.isEmpty()) {
                setQueue(items, 0);
                return;
            }
            if (items != null) {
                queue.addAll(items);
            }
            emitQueueChanged();
        }
    }

    void clearQueueAfterCurrent() {
        synchronized (queueLock) {
            if (currentIndex < 0 || currentIndex >= queue.size() - 1) return;
            ArrayList<QueueItem> rebuilt = new ArrayList<>();
            for (int i = 0; i <= currentIndex; i++) rebuilt.add(queue.get(i));
            queue = rebuilt;
            emitQueueChanged();
        }
    }

    void playIndex(int index) {
        synchronized (queueLock) {
            if (index < 0 || index >= queue.size()) return;
            int prevIdx = currentIndex;
            float prevFraction = computePrevFraction();
            long prevPlaybackInstanceId = currentPlaybackInstanceId;
            Log.d(TAG, "playIndex: prevIdx=" + prevIdx + " newIndex=" + index + " prevFraction=" + prevFraction);
            currentIndex = index;
            beginPlaybackInstance();
            playCurrentFromQueue(0);
            emitCurrentChanged("user_jump", prevIdx, prevFraction, prevPlaybackInstanceId);
        }
    }

    /** User-driven next (in-app button, BT skip, notification skip). */
    void nextTrack(String action, float prevFractionOverride) {
        synchronized (queueLock) {
            if (queue.isEmpty()) return;
            if (shouldBlockRapidNav("next")) return;
            int prevIdx = currentIndex;
            float prevFraction = prevFractionOverride >= 0
                    ? prevFractionOverride
                    : computePrevFraction();
            long prevPlaybackInstanceId = currentPlaybackInstanceId;
            String resolvedAction = (action != null && !action.isEmpty()) ? action : "user_next";
            int newIndex;
            if (currentIndex + 1 < queue.size()) {
                newIndex = currentIndex + 1;
            } else if (loopMode == LOOP_ALL) {
                newIndex = 0;
            } else {
                // No more items — emit end event, stop playback.
                Log.d(TAG, "nextTrack: queue ended prevIdx=" + prevIdx
                        + " prevFraction=" + prevFraction
                        + " queueLength=" + queue.size()
                        + " loopMode=" + loopMode);
                emitCurrentChanged(resolvedAction, prevIdx, prevFraction, prevPlaybackInstanceId);
                handleQueueEndedState("user_next_end");
                emitQueueEnded();
                return;
            }
            Log.d(TAG, "nextTrack: prevIdx=" + prevIdx + " newIndex=" + newIndex
                    + " prevFraction=" + prevFraction + " queueLength=" + queue.size()
                    + " action=" + resolvedAction);
            currentIndex = newIndex;
            beginPlaybackInstance();
            playCurrentFromQueue(0);
            emitCurrentChanged(resolvedAction, prevIdx, prevFraction, prevPlaybackInstanceId);
        }
    }

    /** User-driven prev. */
    void prevTrack(float prevFractionOverride) {
        synchronized (queueLock) {
            if (queue.isEmpty()) return;
            if (shouldBlockRapidNav("prev")) return;
            int prevIdx = currentIndex;
            float prevFraction = prevFractionOverride >= 0
                    ? prevFractionOverride
                    : computePrevFraction();
            long prevPlaybackInstanceId = currentPlaybackInstanceId;
            // If more than 3s into the track, restart it instead of going back —
            // same pattern as every mainstream music player.
            long pos = getCurrentPosition();
            if (pos > 3000 && currentIndex >= 0) {
                Log.d(TAG, "prevTrack: restarting current index=" + currentIndex + " posMs=" + pos);
                seekTo(0);
                return;
            }
            if (currentIndex > 0) {
                currentIndex--;
            } else {
                // At start of queue — just restart current.
                Log.d(TAG, "prevTrack: at queue start, restarting current index=" + currentIndex);
                seekTo(0);
                return;
            }
            Log.d(TAG, "prevTrack: prevIdx=" + prevIdx + " newIndex=" + currentIndex
                    + " prevFraction=" + prevFraction + " queueLength=" + queue.size());
            beginPlaybackInstance();
            playCurrentFromQueue(0);
            emitCurrentChanged("user_prev", prevIdx, prevFraction, prevPlaybackInstanceId);
        }
    }

    /** Auto-advance on natural completion. Never waits for JS. */
    private void advanceAuto() {
        synchronized (queueLock) {
            if (queue.isEmpty()) {
                Log.d(TAG, "advanceAuto: empty queue, emitting queueEnded");
                handleQueueEndedState("auto_empty_queue");
                emitQueueEnded();
                return;
            }
            int prevIdx = currentIndex;
            float prevFraction = computePrevFraction();
            long prevPlaybackInstanceId = currentPlaybackInstanceId;
            if (loopMode == LOOP_ONE && currentIndex >= 0) {
                // Replay same item.
                Log.d(TAG, "advanceAuto: loop one replay index=" + currentIndex);
                beginPlaybackInstance();
                playCurrentFromQueue(0);
                emitCurrentChanged("auto_loop_one", prevIdx, prevFraction, prevPlaybackInstanceId);
                return;
            }
            int newIndex;
            if (currentIndex + 1 < queue.size()) {
                newIndex = currentIndex + 1;
            } else if (loopMode == LOOP_ALL) {
                newIndex = 0;
            } else {
                // Loop off and queue exhausted — emit final event, stop service.
                Log.d(TAG, "advanceAuto: queue exhausted prevIdx=" + prevIdx
                        + " queueLength=" + queue.size() + " loopMode=" + loopMode);
                emitCurrentChanged("queue_end", prevIdx, prevFraction, prevPlaybackInstanceId);
                handleQueueEndedState("auto_queue_end");
                emitQueueEnded();
                return;
            }
            Log.d(TAG, "advanceAuto: prevIdx=" + prevIdx + " newIndex=" + newIndex
                    + " queueLength=" + queue.size() + " loopMode=" + loopMode);
            currentIndex = newIndex;
            beginPlaybackInstance();
            playCurrentFromQueue(0);
            emitCurrentChanged("auto_advance", prevIdx, prevFraction, prevPlaybackInstanceId);
        }
    }

    void setLoopMode(int mode) {
        if (mode < 0 || mode > 2) return;
        loopMode = mode;
        // Drive MediaPlayer.setLooping to match LOOP_ONE for seamless single-track loop.
        if (mediaPlayer != null) {
            try { mediaPlayer.setLooping(mode == LOOP_ONE); } catch (Exception e) { /* ignore */ }
        }
    }

    int getLoopMode() {
        return loopMode;
    }

    /** Returns a snapshot of the queue state for JS reconciliation. */
    JSObject getQueueSnapshot() {
        JSObject ret = new JSObject();
        synchronized (queueLock) {
            ret.put("currentIndex", currentIndex);
            ret.put("length", queue.size());
            ret.put("loopMode", loopMode);
            ret.put("isPlaying", isCurrentlyPlaying());
            ret.put("currentPlaybackInstanceId", currentPlaybackInstanceId);
            JSArray items = new JSArray();
            for (QueueItem it : queue) {
                JSObject o = new JSObject();
                o.put("songId", it.songId);
                o.put("filePath", it.filePath);
                o.put("title", it.title);
                o.put("artist", it.artist);
                o.put("album", it.album);
                items.put(o);
            }
            ret.put("items", items);
        }
        return ret;
    }

    private void playCurrentFromQueue(long seekMs) {
        if (currentIndex < 0 || currentIndex >= queue.size()) {
            Log.w(TAG, "playCurrentFromQueue: invalid currentIndex=" + currentIndex + " queueLength=" + queue.size());
            return;
        }
        QueueItem it = queue.get(currentIndex);
        Log.d(TAG, "playCurrentFromQueue: index=" + currentIndex
                + " title=" + safeTitle(it.title)
                + " seekMs=" + seekMs
                + " queueLength=" + queue.size()
                + " path=" + summarizePath(it.filePath));
        currentTitle = it.title;
        currentArtist = it.artist;
        currentAlbum = it.album;
        playFile(it.filePath, seekMs);
    }

    private int clampIndex(int idx, int size) {
        if (size <= 0) return -1;
        if (idx < 0) return 0;
        if (idx >= size) return size - 1;
        return idx;
    }

    private void resetPlayedProgress(long initialPositionMs) {
        accumulatedPlayedMs = 0;
        lastProgressSampleMs = Math.max(0, initialPositionMs);
    }

    private long nextPlaybackInstanceId() {
        long now = System.currentTimeMillis();
        if (now <= currentPlaybackInstanceId) now = currentPlaybackInstanceId + 1L;
        return now;
    }

    void beginPlaybackInstance() {
        currentPlaybackInstanceId = nextPlaybackInstanceId();
    }

    long getCurrentPlaybackInstanceId() {
        return currentPlaybackInstanceId;
    }

    long getAccumulatedPlayedMsSnapshot() {
        long dur = currentDuration > 0 ? currentDuration : lastKnownDurationMs;
        return Math.min(accumulatedPlayedMs, dur > 0 ? dur : accumulatedPlayedMs);
    }

    long getDurationMsSnapshot() {
        return currentDuration > 0 ? currentDuration : lastKnownDurationMs;
    }

    private void noteProgressSample(long positionMs) {
        long safePos = Math.max(0, positionMs);
        if (lastProgressSampleMs >= 0) {
            long delta = safePos - lastProgressSampleMs;
            if (delta > 0 && delta <= 2000) {
                accumulatedPlayedMs += delta;
            }
        }
        lastProgressSampleMs = safePos;
        lastKnownPositionMs = safePos;
    }

    private float computePrevFraction() {
        long dur = currentDuration > 0 ? currentDuration : lastKnownDurationMs;
        if (dur <= 0) return 0f;
        long pos = playbackCompletedState && dur > 0 ? dur : getCurrentPosition();
        noteProgressSample(pos);
        float f = accumulatedPlayedMs / (float) dur;
        if (f < 0f) return 0f;
        if (f > 1f) return 1f;
        return f;
    }

    private SharedPreferences getRecoveryPrefs() {
        return getSharedPreferences(RECOVERY_PREFS_NAME, MODE_PRIVATE);
    }

    private void rememberTransition(String action, int prevIndex, float prevFraction, long prevPlayed, long prevDur, long prevPlaybackInstanceId, long nextPlaybackInstanceId) {
        if (prevPlaybackInstanceId <= 0L) return;
        try {
            QueueItem prevItem = null;
            QueueItem currentItem = null;
            synchronized (queueLock) {
                if (prevIndex >= 0 && prevIndex < queue.size()) prevItem = queue.get(prevIndex);
                if (currentIndex >= 0 && currentIndex < queue.size()) currentItem = queue.get(currentIndex);
            }

            JSONObject event = new JSONObject();
            event.put("eventId", "native_" + prevPlaybackInstanceId + "_" + System.currentTimeMillis());
            event.put("timestamp", System.currentTimeMillis());
            event.put("action", action != null ? action : "");
            event.put("prevIndex", prevIndex);
            event.put("prevFraction", prevFraction);
            event.put("prevPlayedMs", prevPlayed);
            event.put("prevDurationMs", prevDur);
            event.put("prevPlaybackInstanceId", prevPlaybackInstanceId);
            event.put("currentPlaybackInstanceId", nextPlaybackInstanceId);

            if (prevItem != null) {
                event.put("prevSongId", prevItem.songId);
                event.put("prevFilePath", prevItem.filePath);
                event.put("prevFilename", summarizePath(prevItem.filePath));
                event.put("prevTitle", prevItem.title);
                event.put("prevArtist", prevItem.artist);
                event.put("prevAlbum", prevItem.album);
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

    static JSArray getRecentPlaybackTransitions(Context context, int limit) {
        JSArray out = new JSArray();
        if (context == null) return out;
        try {
            SharedPreferences prefs = context.getSharedPreferences(RECOVERY_PREFS_NAME, Context.MODE_PRIVATE);
            String raw = prefs.getString(RECOVERY_TRANSITIONS_KEY, null);
            if (raw == null || raw.isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            int desired = Math.max(1, Math.min(40, limit));
            int start = Math.max(0, arr.length() - desired);
            for (int i = start; i < arr.length(); i++) {
                out.put(arr.getJSONObject(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "getRecentPlaybackTransitions failed", e);
        }
        return out;
    }

    private boolean shouldBlockRapidNav(String kind) {
        long now = SystemClock.elapsedRealtime();
        if ("prev".equals(kind)) {
            if (lastPrevCommandAtMs > 0 && (now - lastPrevCommandAtMs) < NAV_DEBOUNCE_MS) {
                Log.d(TAG, "shouldBlockRapidNav: blocked duplicate prev");
                return true;
            }
            lastPrevCommandAtMs = now;
            return false;
        }
        if (lastNextCommandAtMs > 0 && (now - lastNextCommandAtMs) < NAV_DEBOUNCE_MS) {
            Log.d(TAG, "shouldBlockRapidNav: blocked duplicate next");
            return true;
        }
        lastNextCommandAtMs = now;
        return false;
    }

    private void resetNavDebounce() {
        lastNextCommandAtMs = 0L;
        lastPrevCommandAtMs = 0L;
    }

    private void emitCurrentChanged(String action, int prevIndex, float prevFraction, long prevPlaybackInstanceId) {
        QueueItem it = null;
        QueueItem prevItem = null;
        synchronized (queueLock) {
            if (prevIndex >= 0 && prevIndex < queue.size()) {
                prevItem = queue.get(prevIndex);
            }
            if (currentIndex >= 0 && currentIndex < queue.size()) {
                it = queue.get(currentIndex);
            }
        }
        long prevDur = currentDuration > 0 ? currentDuration : lastKnownDurationMs;
        long prevPlayed = Math.min(accumulatedPlayedMs, prevDur > 0 ? prevDur : accumulatedPlayedMs);
        JSObject data = new JSObject();
        data.put("action", action);
        data.put("prevIndex", prevIndex);
        data.put("prevFraction", prevFraction);
        data.put("prevPlayedMs", prevPlayed);
        data.put("prevDurationMs", prevDur);
        data.put("prevPlaybackInstanceId", prevPlaybackInstanceId);
        data.put("currentPlaybackInstanceId", currentPlaybackInstanceId);
        if (prevItem != null) {
            data.put("prevSongId", prevItem.songId);
            data.put("prevFilePath", prevItem.filePath);
            data.put("prevFilename", summarizePath(prevItem.filePath));
            data.put("prevTitle", prevItem.title);
        }
        data.put("newIndex", currentIndex);
        data.put("queueLength", queue.size());
        if (it != null) {
            data.put("songId", it.songId);
            data.put("filePath", it.filePath);
            data.put("title", it.title);
            data.put("artist", it.artist);
            data.put("album", it.album);
        }
        rememberTransition(action, prevIndex, prevFraction, prevPlayed, prevDur, prevPlaybackInstanceId, currentPlaybackInstanceId);
        if (pluginRef != null) {
            pluginRef.emitQueueCurrentChanged(data);
        }
    }

    private void emitQueueChanged() {
        if (pluginRef == null) return;
        JSObject data = new JSObject();
        synchronized (queueLock) {
            data.put("length", queue.size());
            data.put("currentIndex", currentIndex);
        }
        pluginRef.emitQueueChanged(data);
    }

    private void emitQueueEnded() {
        if (pluginRef == null) return;
        pluginRef.emitQueueEnded();
    }

    private void handleSynchronousPlaybackFailure(String reason, String errorMessage) {
        synchronized (queueLock) {
            if (autoSkippingBrokenFile) {
                Log.w(TAG, "handleSynchronousPlaybackFailure: already skipping reason=" + reason);
                return;
            }
            if (queue.isEmpty() || currentIndex < 0 || currentIndex >= queue.size()) {
                Log.w(TAG, "handleSynchronousPlaybackFailure: no valid queue item reason=" + reason);
                handleQueueEndedState(reason + "_no_valid_item");
                emitQueueEnded();
                return;
            }
            autoSkippingBrokenFile = true;
            try {
                int originalIndex = currentIndex;
                int size = queue.size();
                for (int attempts = 1; attempts < size; attempts++) {
                    int candidate = originalIndex + attempts;
                    if (candidate >= size) {
                        if (loopMode == LOOP_ALL) {
                            candidate = candidate % size;
                        } else {
                            break;
                        }
                    }
                    if (candidate == originalIndex) break;
                    QueueItem next = queue.get(candidate);
                    Log.w(TAG, "handleSynchronousPlaybackFailure: skipping broken index=" + originalIndex
                            + " -> candidate=" + candidate
                            + " title=" + safeTitle(next.title)
                            + " reason=" + reason
                            + " error=" + errorMessage);
                    long prevPlaybackInstanceId = currentPlaybackInstanceId;
                    currentIndex = candidate;
                    beginPlaybackInstance();
                    playCurrentFromQueue(0);
                    emitCurrentChanged("auto_skip_broken", originalIndex, 0f, prevPlaybackInstanceId);
                    return;
                }
                Log.e(TAG, "handleSynchronousPlaybackFailure: no playable fallback originalIndex=" + originalIndex
                        + " queueLength=" + queue.size() + " reason=" + reason);
                handleQueueEndedState(reason + "_queue_exhausted");
                emitQueueEnded();
            } finally {
                autoSkippingBrokenFile = false;
            }
        }
    }

    private void handleQueueEndedState(String reason) {
        Log.d(TAG, "handleQueueEndedState: reason=" + reason
                + " currentIndex=" + currentIndex
                + " queueLength=" + queue.size());
        isPlaying = false;
        playbackCompletedState = false;
        stopPositionUpdates();
        lastKnownPositionMs = 0;
        currentDuration = 0;
        lastKnownDurationMs = 0;
        accumulatedPlayedMs = 0;
        currentPlaybackInstanceId = 0L;
        lastProgressSampleMs = -1;
        resetNavDebounce();
        abandonAudioFocus();
        updatePlaybackState();
        updateNotification();
        emitPlayStateChange(false);
    }

    private String summarizePath(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "(none)";
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }

    private String safeTitle(String title) {
        return title == null || title.isEmpty() ? "(untitled)" : title;
    }

    private QueueItem getCurrentQueueItem() {
        synchronized (queueLock) {
            if (currentIndex >= 0 && currentIndex < queue.size()) {
                return queue.get(currentIndex);
            }
        }
        return null;
    }

    private String getCurrentFilename() {
        QueueItem currentItem = getCurrentQueueItem();
        if (currentItem != null && currentItem.filePath != null && !currentItem.filePath.isEmpty()) {
            return summarizePath(currentItem.filePath);
        }
        if (currentFilePath != null && !currentFilePath.isEmpty()) {
            return summarizePath(currentFilePath);
        }
        return null;
    }

    private SharedPreferences getCapacitorStoragePrefs() {
        return getSharedPreferences(CAPACITOR_STORAGE_PREFS, MODE_PRIVATE);
    }

    private void clearSavedPlaybackState() {
        getCapacitorStoragePrefs().edit().remove(PREF_KEY_PLAYBACK_STATE).apply();
    }

    private boolean isCurrentFavorite() {
        String filename = getCurrentFilename();
        if (filename == null || filename.isEmpty() || "(none)".equals(filename)) return false;
        try {
            String raw = getCapacitorStoragePrefs().getString(PREF_KEY_FAVORITES, null);
            if (raw == null || raw.isEmpty()) return false;
            JSONObject payload = new JSONObject(raw);
            JSONArray filenames = payload.optJSONArray("filenames");
            if (filenames == null) return false;
            for (int i = 0; i < filenames.length(); i++) {
                if (filename.equalsIgnoreCase(filenames.optString(i, ""))) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isCurrentFavorite failed", e);
        }
        return false;
    }

    private JSObject buildCurrentMediaPayload() {
        JSObject data = new JSObject();
        QueueItem currentItem = getCurrentQueueItem();
        if (currentItem != null) {
            data.put("songId", currentItem.songId);
            data.put("filePath", currentItem.filePath);
            data.put("filename", summarizePath(currentItem.filePath));
            data.put("title", currentItem.title);
            data.put("artist", currentItem.artist);
            data.put("album", currentItem.album);
        } else {
            String filename = getCurrentFilename();
            data.put("songId", -1);
            data.put("filePath", currentFilePath != null ? currentFilePath : "");
            data.put("filename", filename != null ? filename : "");
            data.put("title", currentTitle != null ? currentTitle : "");
            data.put("artist", currentArtist != null ? currentArtist : "");
            data.put("album", currentAlbum != null ? currentAlbum : "");
        }
        return data;
    }

    private JSObject toggleCurrentFavoriteInStorage() {
        JSObject result = buildCurrentMediaPayload();
        String filename = getCurrentFilename();
        if (filename == null || filename.isEmpty() || "(none)".equals(filename)) {
            result.put("ok", false);
            return result;
        }
        try {
            SharedPreferences prefs = getCapacitorStoragePrefs();
            String raw = prefs.getString(PREF_KEY_FAVORITES, null);
            ArrayList<String> filenames = new ArrayList<>();
            if (raw != null && !raw.isEmpty()) {
                JSONObject payload = new JSONObject(raw);
                JSONArray stored = payload.optJSONArray("filenames");
                if (stored != null) {
                    for (int i = 0; i < stored.length(); i++) {
                        String value = stored.optString(i, "");
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
                JSONArray dislikes = new JSONArray(rawDislikes);
                for (int i = 0; i < dislikes.length(); i++) {
                    String value = dislikes.optString(i, "");
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
            JSONObject payload = new JSONObject();
            payload.put("filenames", favoritesOut);

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(PREF_KEY_FAVORITES, payload.toString());
            if (isFavorite || rawDislikes != null) {
                editor.putString(PREF_KEY_DISLIKED_SONGS, dislikesOut.toString());
            }
            editor.apply();

            result.put("ok", true);
            result.put("filename", filename);
            result.put("isFavorite", isFavorite);
            result.put("unDisliked", unDisliked);
        } catch (Exception e) {
            Log.w(TAG, "toggleCurrentFavoriteInStorage failed", e);
            result.put("ok", false);
        }
        return result;
    }

    private void rememberStopTransition(String action) {
        if (currentPlaybackInstanceId <= 0L) return;
        int prevIndex;
        synchronized (queueLock) {
            prevIndex = currentIndex;
        }
        float prevFraction = computePrevFraction();
        long prevDur = currentDuration > 0 ? currentDuration : lastKnownDurationMs;
        long prevPlayed = Math.min(accumulatedPlayedMs, prevDur > 0 ? prevDur : accumulatedPlayedMs);
        rememberTransition(action, prevIndex, prevFraction, prevPlayed, prevDur, currentPlaybackInstanceId, 0L);
    }

    private RemoteViews buildNotificationRemoteViews(
            boolean expanded,
            Bitmap art,
            PendingIntent contentIntent,
            PendingIntent favoriteIntent,
            PendingIntent prevIntent,
            PendingIntent playPauseIntent,
            PendingIntent nextIntent,
            PendingIntent dismissIntent,
            int playPauseIcon,
            boolean isFavorite
    ) {
        RemoteViews views = new RemoteViews(
                getPackageName(),
                expanded ? R.layout.notification_player_expanded : R.layout.notification_player_compact
        );
        long durationMs = currentDuration > 0 ? currentDuration : lastKnownDurationMs;
        long positionMs = getCurrentPosition();
        if (durationMs > 0) {
            positionMs = Math.max(0, Math.min(positionMs, durationMs));
        } else {
            positionMs = Math.max(0, positionMs);
        }
        int progress = durationMs > 0 ? (int) Math.max(0, Math.min(1000L, (positionMs * 1000L) / durationMs)) : 0;
        String displayTitle = (currentTitle != null && !currentTitle.isEmpty()) ? currentTitle : getAppDisplayName();
        String displayText = (currentAlbum != null && !currentAlbum.isEmpty() && !"Unknown".equalsIgnoreCase(currentAlbum))
                ? currentArtist + " \u00B7 " + currentAlbum
                : currentArtist;

        views.setImageViewBitmap(R.id.notification_art, art);
        views.setTextViewText(R.id.notification_title, displayTitle);
        views.setTextViewText(R.id.notification_artist, displayText != null ? displayText : "");
        views.setTextViewText(R.id.notification_elapsed, formatDurationMs(positionMs));
        views.setTextViewText(R.id.notification_duration, formatDurationMs(durationMs));
        views.setProgressBar(R.id.notification_progress, 1000, progress, false);
        views.setImageViewResource(R.id.notification_favorite, isFavorite
                ? R.drawable.ic_notification_favorite
                : R.drawable.ic_notification_favorite_border);
        views.setImageViewResource(R.id.notification_close, R.drawable.ic_notification_close);
        views.setImageViewResource(R.id.notification_prev, android.R.drawable.ic_media_previous);
        views.setImageViewResource(R.id.notification_play_pause, playPauseIcon);
        views.setImageViewResource(R.id.notification_next, android.R.drawable.ic_media_next);

        views.setOnClickPendingIntent(R.id.notification_root, contentIntent);
        views.setOnClickPendingIntent(R.id.notification_favorite, favoriteIntent);
        views.setOnClickPendingIntent(R.id.notification_prev, prevIntent);
        views.setOnClickPendingIntent(R.id.notification_play_pause, playPauseIntent);
        views.setOnClickPendingIntent(R.id.notification_next, nextIntent);
        views.setOnClickPendingIntent(R.id.notification_close, dismissIntent);
        return views;
    }

    private String formatDurationMs(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    // ===== AUDIO FOCUS =====

    private boolean requestAudioFocus() {
        if (audioManager == null) return false;
        if (hasAudioFocus) return true;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            result = audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            result = audioManager.requestAudioFocus(
                    this::onAudioFocusChange,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        Log.d(TAG, "requestAudioFocus: granted=" + hasAudioFocus);
        return hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (audioManager == null || !hasAudioFocus) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(this::onAudioFocusChange);
        }
        hasAudioFocus = false;
    }

    private void onAudioFocusChange(int focusChange) {
        Log.d(TAG, "onAudioFocusChange: change=" + focusChange
                + " isPlaying=" + isPlaying
                + " pausedByFocusLoss=" + pausedByFocusLoss);
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                hasAudioFocus = true;
                // Regained focus — restore volume and resume if we paused for focus
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(originalVolume, originalVolume);
                }
                if (pausedByFocusLoss && mediaPlayer != null) {
                    resumePlayback();
                    pausedByFocusLoss = false;
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                hasAudioFocus = false;
                // Permanent loss (another app took over) — pause
                pausedByFocusLoss = false; // don't auto-resume
                pausePlayback();
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                hasAudioFocus = false;
                // Temporary loss (phone call, notification) — pause, resume when regained
                if (isPlaying) {
                    pausePlayback();
                    pausedByFocusLoss = true;
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Can duck — lower volume instead of pausing
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(0.2f, 0.2f);
                }
                break;
        }
    }

    private void startPositionUpdates() {
        positionHandler.removeCallbacks(positionRunnable);
        positionHandler.post(positionRunnable);
    }

    private void stopPositionUpdates() {
        positionHandler.removeCallbacks(positionRunnable);
    }

    private void emitTimeUpdate() {
        if (mediaPlayer != null) {
            try {
                int positionMs = mediaPlayer.getCurrentPosition();
                int durationMs = mediaPlayer.getDuration();
                noteProgressSample(positionMs);
                long safePlayed = Math.min(accumulatedPlayedMs, durationMs > 0 ? durationMs : accumulatedPlayedMs);
                if (pluginRef != null) {
                    pluginRef.emitAudioTimeUpdate(
                        positionMs / 1000.0,
                        durationMs / 1000.0,
                        isPlaying,
                        safePlayed,
                        durationMs,
                        currentPlaybackInstanceId
                    );
                }
                long now = SystemClock.elapsedRealtime();
                if (isPlaying && (now - lastNotificationProgressPushAtMs) >= 1000L) {
                    lastNotificationProgressPushAtMs = now;
                    updateNotification();
                }
            } catch (Exception e) { /* ignore */ }
        }
    }

    private void emitPlayStateChange(boolean playing) {
        if (pluginRef != null) {
            pluginRef.emitAudioPlayStateChanged(playing);
        }
    }

    private void emitError(String msg) {
        if (pluginRef != null) {
            pluginRef.emitAudioError(msg);
        }
    }

    private void emitError(String msg, String path) {
        if (pluginRef != null) {
            pluginRef.emitAudioError(msg, path);
        }
    }

    // ===== NOTIFICATION / MEDIA SESSION =====

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Music Playback",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Controls for music playback");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, getAppDisplayName());
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setSessionActivity(createContentIntent());
        mediaSession.setActive(true);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                resumePlayback();
            }

            @Override
            public void onPause() {
                pausePlayback();
            }

            @Override
            public void onSkipToNext() {
                // Native advances autonomously — works even if JS is suspended.
                nextTrack("user_next", -1f);
            }

            @Override
            public void onSkipToPrevious() {
                prevTrack(-1f);
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo(pos);
            }

            @Override
            public void onCustomAction(String action, android.os.Bundle extras) {
                if (!handleCustomTransportAction(action)) {
                    super.onCustomAction(action, extras);
                }
            }
        });
    }

    private void notifyPlugin(String action) {
        notifyPlugin(action, null);
    }

    private void notifyPlugin(String action, JSObject extra) {
        if (pluginRef != null) {
            pluginRef.onMediaAction(action, extra);
        }
    }

    private boolean handleCustomTransportAction(String action) {
        if (action == null) return false;
        switch (action) {
            case ACTION_TOGGLE_FAVORITE:
                JSObject favoriteResult = toggleCurrentFavoriteInStorage();
                updatePlaybackState();
                updateNotification();
                notifyPlugin("favorite", favoriteResult);
                return true;
            case ACTION_DISMISS:
                rememberStopTransition("user_dismiss");
                clearSavedPlaybackState();
                notifyPlugin("dismiss", buildCurrentMediaPayload());
                stopAudioAndCleanup();
                return true;
            default:
                return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                if (handleCustomTransportAction(action)) {
                    return ACTION_DISMISS.equals(action) ? START_NOT_STICKY : START_STICKY;
                }
                switch (action) {
                    case ACTION_PLAY:
                        resumePlayback();
                        break;
                    case ACTION_PAUSE:
                        pausePlayback();
                        break;
                    case ACTION_NEXT:
                        nextTrack("user_next", -1f);
                        break;
                    case ACTION_PREV:
                        prevTrack(-1f);
                        break;
                    case ACTION_UPDATE:
                        String title = intent.getStringExtra("title");
                        String artist = intent.getStringExtra("artist");
                        String album = intent.getStringExtra("album");
                        if (title != null) currentTitle = title;
                        if (artist != null) currentArtist = artist;
                        if (album != null) currentAlbum = album;
                        // isPlaying is driven by MediaPlayer now, but update for notification
                        boolean intentPlaying = intent.getBooleanExtra("isPlaying", isPlaying);
                        // Only update metadata and notification
                        updateMediaSessionMetadata();
                        updatePlaybackState();
                        break;
                    case ACTION_STOP:
                        stopAudioAndCleanup();
                        return START_NOT_STICKY;
                }
            }
        }

        // Ensure notification is up
        updateNotification();

        return START_STICKY;
    }

    private void stopAudioAndCleanup() {
        if (mediaPlayer != null) {
            stopPositionUpdates();
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) { /* ignore */ }
            mediaPlayer = null;
            isPlaying = false;
        }
        playbackCompletedState = false;
        accumulatedPlayedMs = 0;
        currentPlaybackInstanceId = 0L;
        lastProgressSampleMs = -1;
        lastNotificationProgressPushAtMs = 0L;
        cachedAlbumArt = null;
        cachedAlbumArtPathKey = null;
        cachedAlbumArtTitleKey = null;
        resetNavDebounce();
        abandonAudioFocus();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (mediaSession != null) mediaSession.setActive(false);
        stopForeground(true);
        stopSelf();
    }

    /**
     * Called when user swipes app from recents.
     * If playing, keep the service alive so music continues.
     * If paused, clean up.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!isPlaying) {
            stopAudioAndCleanup();
        }
        // If playing, service stays alive with MediaPlayer running
        super.onTaskRemoved(rootIntent);
    }

    private void updateMediaSessionMetadata() {
        Bitmap art = getAlbumArt();
        String displayTitle = (currentTitle != null && !currentTitle.isEmpty())
                ? currentTitle
                : getAppDisplayName();
        String displaySubtitle = currentArtist;
        if (currentAlbum != null && !currentAlbum.isEmpty() && !"Unknown".equalsIgnoreCase(currentAlbum)) {
            displaySubtitle = (displaySubtitle == null || displaySubtitle.isEmpty())
                    ? currentAlbum
                    : currentArtist + " \u00B7 " + currentAlbum;
        }
        String displayDescription = (displaySubtitle != null && !displaySubtitle.isEmpty())
                ? displaySubtitle
                : getAppDisplayName();
        MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displaySubtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, displayDescription)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentFilePath != null ? currentFilePath : displayTitle)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art);
        if (currentDuration > 0) {
            b.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration);
        }
        mediaSession.setMetadata(b.build());
    }

    private void updatePlaybackState() {
        int state = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        long pos = 0;
        if (mediaPlayer != null) {
            try { pos = mediaPlayer.getCurrentPosition(); } catch (Exception e) { /* ignore */ }
        }
        boolean isFavorite = isCurrentFavorite();
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SEEK_TO |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .addCustomAction(
                        ACTION_TOGGLE_FAVORITE,
                        isFavorite ? "Unfavorite" : "Favorite",
                        isFavorite ? R.drawable.ic_notification_favorite : R.drawable.ic_notification_favorite_border)
                .addCustomAction(
                        ACTION_DISMISS,
                        "Close",
                        R.drawable.ic_notification_close)
                .setState(state, pos, isPlaying ? 1.0f : 0f);
        mediaSession.setPlaybackState(builder.build());
    }

    private void updateNotification() {
        Notification notification = buildNotification();

        if (isPlaying) {
            startForeground(NOTIFICATION_ID, notification);
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(10 * 60 * 60 * 1000L);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, notification);
            }
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    private Notification buildNotification() {
        PendingIntent contentIntent = createContentIntent();

        PendingIntent favoritePI = createActionIntent(ACTION_TOGGLE_FAVORITE, 4);
        PendingIntent prevPI = createActionIntent(ACTION_PREV, 1);
        PendingIntent playPausePI = createActionIntent(isPlaying ? ACTION_PAUSE : ACTION_PLAY, 2);
        PendingIntent nextPI = createActionIntent(ACTION_NEXT, 3);
        PendingIntent dismissPI = createActionIntent(ACTION_DISMISS, 5);
        PendingIntent deleteIntent = dismissPI;

        String playPauseLabel = isPlaying ? "Pause" : "Play";
        int playPauseIcon = isPlaying
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play;
        boolean isFavorite = isCurrentFavorite();
        String favoriteLabel = isFavorite ? "Unfavorite" : "Favorite";
        int favoriteIcon = isFavorite
                ? R.drawable.ic_notification_favorite
                : R.drawable.ic_notification_favorite_border;
        String displayTitle = (currentTitle != null && !currentTitle.isEmpty())
                ? currentTitle
                : getAppDisplayName();
        String displayText = (currentAlbum != null && !currentAlbum.isEmpty() && !"Unknown".equalsIgnoreCase(currentAlbum))
                ? currentArtist + " \u00B7 " + currentAlbum
                : currentArtist;

        Bitmap art = getAlbumArt();
        RemoteViews compactView = buildNotificationRemoteViews(false, art, contentIntent, favoritePI, prevPI, playPausePI, nextPI, dismissPI, playPauseIcon, isFavorite);
        RemoteViews expandedView = buildNotificationRemoteViews(true, art, contentIntent, favoritePI, prevPI, playPausePI, nextPI, dismissPI, playPauseIcon, isFavorite);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(displayTitle)
                .setContentText(displayText)
                .setSubText(getAppDisplayName())
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOnlyAlertOnce(true)
                .setLargeIcon(art)
                .setContentIntent(contentIntent)
                .setDeleteIntent(deleteIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .setShowWhen(false)
                .setCustomContentView(compactView)
                .setCustomBigContentView(expandedView)
                .addAction(favoriteIcon, favoriteLabel, favoritePI)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevPI)
                .addAction(playPauseIcon, playPauseLabel, playPausePI)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPI)
                .addAction(R.drawable.ic_notification_close, "Close", dismissPI)
                .setStyle(new androidx.media.app.NotificationCompat.DecoratedMediaCustomViewStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(1, 2, 3));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return builder.build();
    }

    private PendingIntent createActionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MusicPlaybackService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent createContentIntent() {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private String getAppDisplayName() {
        try {
            String label = getString(R.string.app_name);
            return (label != null && !label.isEmpty()) ? label : "IsaiVazhi";
        } catch (Exception e) {
            return "IsaiVazhi";
        }
    }

    private Bitmap getAlbumArt() {
        String pathKey = currentFilePath != null ? currentFilePath : "";
        String titleKey = currentTitle != null ? currentTitle : "";
        if (cachedAlbumArt != null
                && pathKey.equals(cachedAlbumArtPathKey)
                && titleKey.equals(cachedAlbumArtTitleKey)) {
            return cachedAlbumArt;
        }
        // Try to extract embedded album art from the audio file
        if (currentFilePath != null) {
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                mmr.setDataSource(currentFilePath);
                byte[] artBytes = mmr.getEmbeddedPicture();
                mmr.release();
                if (artBytes != null && artBytes.length > 0) {
                    Bitmap raw = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length);
                    if (raw != null) {
                        // Scale to reasonable size for notification
                        cachedAlbumArt = Bitmap.createScaledBitmap(raw, 512, 512, true);
                        cachedAlbumArtPathKey = pathKey;
                        cachedAlbumArtTitleKey = titleKey;
                        return cachedAlbumArt;
                    }
                }
            } catch (Exception e) {
                // Fall through to placeholder
            }
        }
        cachedAlbumArt = createPlaceholderArt(currentTitle);
        cachedAlbumArtPathKey = pathKey;
        cachedAlbumArtTitleKey = titleKey;
        return cachedAlbumArt;
    }

    private Bitmap createPlaceholderArt(String title) {
        int size = 256;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.parseColor("#1a1a26"));

        Paint paint = new Paint();
        paint.setColor(Color.parseColor("#e84545"));
        paint.setTextSize(120);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setAntiAlias(true);

        String initial = (title != null && !title.isEmpty())
                ? title.substring(0, 1).toUpperCase() : "M";
        canvas.drawText(initial, size / 2f, size / 2f + 40, paint);

        return bitmap;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopPositionUpdates();
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) { /* ignore */ }
            mediaPlayer = null;
        }
        abandonAudioFocus();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        instance = null;
        super.onDestroy();
    }
}
