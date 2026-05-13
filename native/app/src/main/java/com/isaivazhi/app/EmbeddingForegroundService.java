package com.isaivazhi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service that hosts the embedding pipeline in the dedicated :ai process.
 * The main process binds via Messenger to receive progress / completion events.
 */
public class EmbeddingForegroundService extends Service {

    private static final String TAG = "EmbeddingFgService";
    // Push #49: bumped from "embedding_service" → "embedding_service_v2".
    // The old channel was created with IMPORTANCE_LOW which Android hides
    // from the lockscreen and demotes from the status bar. Channels can't
    // be mutated after creation, so we change the id; the orphaned v1
    // channel stays in OS settings but is unused.
    private static final String CHANNEL_ID = "embedding_service_v2";
    private static final int NOTIFICATION_ID = 2;
    private static final String ACTIVE_BATCH_FILE = "active_embedding_batch.json";

    public static final String ACTION_START = "com.isaivazhi.app.EMBEDDING_START";
    public static final String ACTION_STOP = "com.isaivazhi.app.EMBEDDING_STOP";
    // Push #40 Tier 3H: re-posted by onTaskRemoved so the service keeps
    // running after the user swipes the app from recents. Routes through
    // the persisted-batch resumption path.
    public static final String ACTION_RESUME = "com.isaivazhi.app.EMBEDDING_RESUME";
    public static final String EXTRA_PATHS = "paths";
    public static final String EXTRA_PLAYBACK_ACTIVE = "playbackActive";

    private final CopyOnWriteArrayList<Messenger> clients = new CopyOnWriteArrayList<>();
    private final Messenger commandMessenger = new Messenger(new IncomingHandler(Looper.getMainLooper()));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EmbeddingService embeddingService;
    private EmbeddingSimilarityIndex similarityIndex;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean embeddingInProgress = false;
    private volatile boolean playbackActiveHint = false;
    private volatile long playbackCooldownUntilElapsedMs = 0L;
    private volatile String throttleReason = "";
    private int currentStep = 0;
    private int totalSteps = 0;
    private int processedCount = 0;
    private int failedCount = 0;
    private String currentFilename = "";
    private String currentFilepath = "";
    // Push #43: latest init-step text so the user sees the actual stuck
    // step ("Extracting audio model" / "Starting NPU/GPU" / "Falling back
    // to CPU") in the foreground notification and the AI banner.
    private volatile String initStepText = "";
    // Push #43: songs the user added (via per-row Embed or another Embed
    // Pending tap) while a batch was already in flight. Drained at the
    // end of the current batch into a new internal embedSongs call.
    private final ArrayList<String> pendingAdditional = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        similarityIndex = new EmbeddingSimilarityIndex(getApplicationContext());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "isaivazhi:embedding");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "onStartCommand: action=" + action + " flags=" + flags + " startId=" + startId);

        if (ACTION_STOP.equals(action)) {
            clearPersistedBatch();
            cancelEmbedding();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            ArrayList<String> paths = intent.getStringArrayListExtra(EXTRA_PATHS);
            playbackActiveHint = intent.getBooleanExtra(EXTRA_PLAYBACK_ACTIVE, playbackActiveHint);
            if (paths == null || paths.isEmpty()) {
                if (!embeddingInProgress) stopSelf();
                return embeddingInProgress ? START_STICKY : START_NOT_STICKY;
            }
            // Push #43: if a batch is already running, APPEND the new
            // paths to the pending-additions queue instead of overwriting
            // the active batch + early-returning (the previous behavior
            // silently dropped the user's add-one-while-running tap).
            // Persistence keeps both lists so a restart resumes both.
            if (embeddingInProgress) {
                synchronized (pendingAdditional) {
                    for (String p : paths) {
                        if (p != null && !p.isEmpty() && !pendingAdditional.contains(p)) {
                            pendingAdditional.add(p);
                        }
                    }
                    Log.i(TAG, "Queued " + paths.size() + " song(s) to run after current batch; pending="
                            + pendingAdditional.size());
                }
                persistActiveBatchCombined();
                // Bump totalSteps so the progress bar and notification
                // reflect that more songs are coming.
                totalSteps += paths.size();
                broadcastStatus();
                return START_STICKY;
            }
            persistActiveBatch(paths);
            // Push #40 Tier 2D: post the foreground notification IMMEDIATELY
            // with "Warming up audio model" + indeterminate progress.
            beginForegroundWarmingUp(paths.size());
            startEmbedding(paths);
            return START_STICKY;
        }

        // Push #40 Tier 3H: ACTION_RESUME — fired by onTaskRemoved when
        // the user swipes the app from recents. Routes through the same
        // persisted-batch resumption path as a cold restart.
        if (ACTION_RESUME.equals(action) || action == null) {
            ArrayList<String> persisted = loadPersistedBatch();
            if (!embeddingInProgress && persisted != null && !persisted.isEmpty()) {
                Log.i(TAG, "Resuming persisted embedding batch (" + persisted.size()
                        + " songs) after action=" + action);
                beginForegroundWarmingUp(persisted.size());
                startEmbedding(persisted);
                return START_STICKY;
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return commandMessenger.getBinder();
    }

    @Override
    public void onDestroy() {
        clients.clear();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    private void startEmbedding(List<String> paths) {
        if (embeddingInProgress) {
            Log.i(TAG, "Embedding already in progress; keeping current batch");
            broadcastStatus();
            return;
        }

        if (embeddingService == null) {
            embeddingService = new EmbeddingService(getApplicationContext(), new EmbeddingService.PlaybackActivityProvider() {
                @Override
                public boolean isPlaybackActive() {
                    return playbackActiveHint;
                }

                @Override
                public long getCooldownUntilElapsedMs() {
                    return playbackCooldownUntilElapsedMs;
                }

                @Override
                public String getThrottleReason() {
                    return throttleReason;
                }
            });
            // Push #43: receive init-step events so the foreground
            // notification + status broadcast surface the real stuck step.
            embeddingService.setInitProgressListener((label, text) -> {
                initStepText = text != null ? text : "";
                Log.i(TAG, "initStep: " + label + " — " + initStepText);
                updateWarmingUpNotification(initStepText);
                broadcastStatus();
            });
        }

        embeddingInProgress = true;
        currentStep = 0;
        totalSteps = paths != null ? paths.size() : 0;
        processedCount = 0;
        failedCount = 0;
        currentFilename = "";
        currentFilepath = "";
        broadcastStatus();

        embeddingService.embedSongs(paths, new EmbeddingService.EmbeddingCallback() {
            @Override
            public void onProgress(String filename, String filepath, int current, int total) {
                currentFilename = filename != null ? filename : "";
                currentFilepath = filepath != null ? filepath : "";
                currentStep = current;
                totalSteps = total;
                updateNotification("Embedding " + current + "/" + total + ": " + currentFilename, current, total);

                Bundle data = new Bundle();
                data.putString(EmbeddingCommandContract.KEY_FILENAME, currentFilename);
                data.putString(EmbeddingCommandContract.KEY_FILE_PATH, currentFilepath);
                data.putInt(EmbeddingCommandContract.KEY_CURRENT, current);
                data.putInt(EmbeddingCommandContract.KEY_TOTAL, total);
                data.putInt(EmbeddingCommandContract.KEY_PROCESSED, processedCount);
                data.putInt(EmbeddingCommandContract.KEY_FAILED, failedCount);
                broadcastMessage(EmbeddingCommandContract.MSG_PROGRESS, data);
            }

            @Override
            public void onSongComplete(String filename, String filepath, String contentHash) {
                processedCount++;
                Bundle data = new Bundle();
                data.putString(EmbeddingCommandContract.KEY_FILENAME, filename);
                data.putString(EmbeddingCommandContract.KEY_FILE_PATH, filepath);
                data.putString(EmbeddingCommandContract.KEY_CONTENT_HASH, contentHash);
                data.putInt(EmbeddingCommandContract.KEY_PROCESSED, processedCount);
                data.putInt(EmbeddingCommandContract.KEY_FAILED, failedCount);
                broadcastMessage(EmbeddingCommandContract.MSG_SONG_COMPLETE, data);
            }

            @Override
            public void onComplete(int totalProcessed, int failed) {
                Log.i(TAG, "Embedding complete: " + totalProcessed + " processed, " + failed + " failed");
                embeddingInProgress = false;
                processedCount = totalProcessed;
                failedCount = failed;
                currentStep = totalProcessed + failed;
                currentFilename = "";
                currentFilepath = "";

                // Push #43: drain pending-additions queue if the user
                // queued more songs while the batch was running. Start a
                // new internal batch instead of cleaning up. This loops
                // until pendingAdditional is empty.
                ArrayList<String> additional;
                synchronized (pendingAdditional) {
                    additional = new ArrayList<>(pendingAdditional);
                    pendingAdditional.clear();
                }
                if (!additional.isEmpty()) {
                    Log.i(TAG, "Draining " + additional.size() + " queued song(s) into a follow-up batch");
                    clearPersistedBatch();
                    persistActiveBatch(additional);
                    // Re-emit progress so banner doesn't flash "done" then
                    // immediately restart at 0/N.
                    Bundle bridge = new Bundle();
                    bridge.putInt(EmbeddingCommandContract.KEY_PROCESSED, totalProcessed);
                    bridge.putInt(EmbeddingCommandContract.KEY_FAILED, failed);
                    bridge.putInt(EmbeddingCommandContract.KEY_TOTAL, totalProcessed + additional.size());
                    broadcastMessage(EmbeddingCommandContract.MSG_STATUS, bridge);
                    startEmbedding(additional);
                    return;
                }

                clearPersistedBatch();
                String summary = totalProcessed + " embedded" + (failed > 0 ? ", " + failed + " failed" : "") + " - done!";
                updateNotification(summary, totalProcessed + failed, totalProcessed + failed);

                Bundle data = new Bundle();
                data.putInt(EmbeddingCommandContract.KEY_PROCESSED, totalProcessed);
                data.putInt(EmbeddingCommandContract.KEY_FAILED, failed);
                broadcastMessage(EmbeddingCommandContract.MSG_COMPLETE, data);

                mainHandler.postDelayed(EmbeddingForegroundService.this::cleanup, 3000L);
            }

            @Override
            public void onError(String message, String filepath) {
                if (filepath != null && !filepath.isEmpty()) {
                    failedCount++;
                }
                Bundle data = new Bundle();
                data.putString(EmbeddingCommandContract.KEY_ERROR, message != null ? message : "Unknown embedding error");
                if (filepath != null) data.putString(EmbeddingCommandContract.KEY_FILE_PATH, filepath);
                data.putInt(EmbeddingCommandContract.KEY_FAILED, failedCount);
                broadcastMessage(EmbeddingCommandContract.MSG_ERROR, data);
            }
        });
    }

    private void cancelEmbedding() {
        if (embeddingService != null) {
            embeddingService.cancelEmbedding();
        } else {
            cleanup();
        }
    }

    private void beginForeground(int total, String text) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(4 * 60 * 60 * 1000L);
            Log.i(TAG, "WakeLock acquired (4 h budget)");
        }
        startForeground(NOTIFICATION_ID, buildNotification(text, 0, total));
    }

    /**
     * Push #40 Tier 2D: post an indeterminate "warming up" notification
     * immediately on onStartCommand. Replaces beginForeground(N, "Preparing")
     * which set a deterministic 0/N progress that looked dead until first
     * onProgress() arrived. Now the user always sees a live spinner inside
     * the system shade within ~50 ms of tapping Embed Pending.
     */
    private void beginForegroundWarmingUp(int total) {
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(4 * 60 * 60 * 1000L);
            Log.i(TAG, "WakeLock acquired (4 h budget)");
        }
        totalSteps = total;
        startForeground(NOTIFICATION_ID, buildWarmingUpNotification(total));
    }

    /**
     * Push #40 Tier 3H: keep the service alive when the user removes the
     * task from recents. Default Android behavior + dataSync foreground
     * services is to kill child services with the task. Here we re-post a
     * RESUME intent so the :ai process stays up via persisted batch.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "onTaskRemoved: in-progress=" + embeddingInProgress
                + " total=" + totalSteps + " processed=" + processedCount);
        if (embeddingInProgress) {
            try {
                Intent restart = new Intent(this, EmbeddingForegroundService.class)
                        .setAction(ACTION_RESUME);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restart);
                } else {
                    startService(restart);
                }
                Log.i(TAG, "onTaskRemoved: reposted ACTION_RESUME to keep batch alive");
            } catch (Throwable t) {
                Log.w(TAG, "onTaskRemoved: restart intent failed", t);
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    private void cleanup() {
        embeddingInProgress = false;
        currentStep = 0;
        totalSteps = 0;
        currentFilename = "";
        currentFilepath = "";

        if (embeddingService != null) {
            embeddingService.release();
            embeddingService = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(true);
        stopSelf();
    }

    private void broadcastStatus() {
        Bundle data = buildStatusBundle();
        broadcastMessage(EmbeddingCommandContract.MSG_STATUS, data);
    }

    private Bundle buildStatusBundle() {
        Bundle data = new Bundle();
        data.putBoolean(EmbeddingCommandContract.KEY_IN_PROGRESS, embeddingInProgress);
        data.putBoolean(EmbeddingCommandContract.KEY_PLAYBACK_ACTIVE, playbackActiveHint);
        data.putLong(EmbeddingCommandContract.KEY_PLAYBACK_COOLDOWN_UNTIL_ELAPSED_MS, playbackCooldownUntilElapsedMs);
        data.putString(EmbeddingCommandContract.KEY_THROTTLE_REASON, throttleReason);
        data.putString(EmbeddingCommandContract.KEY_FILENAME, currentFilename);
        data.putString(EmbeddingCommandContract.KEY_FILE_PATH, currentFilepath);
        data.putInt(EmbeddingCommandContract.KEY_CURRENT, currentStep);
        data.putInt(EmbeddingCommandContract.KEY_TOTAL, totalSteps);
        data.putInt(EmbeddingCommandContract.KEY_PROCESSED, processedCount);
        data.putInt(EmbeddingCommandContract.KEY_FAILED, failedCount);
        // Expose active ONNX backend so JS can show "nnapi+fp16 | nnapi | cpu" on the
        // AI page. Reported as empty until the EmbeddingService session is built.
        String backend = embeddingService != null ? embeddingService.getActiveBackend() : "";
        data.putString(EmbeddingCommandContract.KEY_ACTIVE_BACKEND, backend != null ? backend : "");
        // Push #43: latest init-step text so the AI-page banner can show
        // the user what's actually happening during "warming up".
        data.putString(EmbeddingCommandContract.KEY_INIT_STEP_TEXT, initStepText != null ? initStepText : "");
        return data;
    }

    private void broadcastMessage(int what, Bundle data) {
        for (Messenger client : clients) {
            sendMessage(client, what, data);
        }
    }

    private void sendMessage(Messenger client, int what, Bundle data) {
        if (client == null) return;
        Message msg = Message.obtain(null, what);
        msg.setData(data != null ? new Bundle(data) : new Bundle());
        try {
            client.send(msg);
        } catch (RemoteException e) {
            clients.remove(client);
        }
    }

    private void registerClient(Messenger client) {
        if (client == null) return;
        if (!clients.contains(client)) {
            clients.add(client);
        }
        sendMessage(client, EmbeddingCommandContract.MSG_STATUS, buildStatusBundle());
    }

    private void unregisterClient(Messenger client) {
        if (client == null) return;
        clients.remove(client);
    }

    private final class IncomingHandler extends Handler {
        IncomingHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EmbeddingCommandContract.MSG_REGISTER_CLIENT:
                    registerClient(msg.replyTo);
                    return;
                case EmbeddingCommandContract.MSG_UNREGISTER_CLIENT:
                    unregisterClient(msg.replyTo);
                    return;
                case EmbeddingCommandContract.MSG_REQUEST_STATUS:
                    EmbeddingForegroundService.this.sendMessage(
                            msg.replyTo,
                            EmbeddingCommandContract.MSG_STATUS,
                            buildStatusBundle()
                    );
                    return;
                case EmbeddingCommandContract.MSG_SET_PLAYBACK_ACTIVE:
                    Bundle data = msg.getData();
                    playbackActiveHint = data != null
                            && data.getBoolean(EmbeddingCommandContract.KEY_PLAYBACK_ACTIVE, false);
                    long cooldownUntil = data != null
                            ? data.getLong(EmbeddingCommandContract.KEY_PLAYBACK_COOLDOWN_UNTIL_ELAPSED_MS, 0L)
                            : 0L;
                    playbackCooldownUntilElapsedMs = Math.max(cooldownUntil, playbackActiveHint ? SystemClock.elapsedRealtime() : 0L);
                    throttleReason = data != null ? data.getString(EmbeddingCommandContract.KEY_THROTTLE_REASON, "") : "";
                    broadcastStatus();
                    return;
                case EmbeddingCommandContract.MSG_FIND_NEAREST:
                    handleFindNearest(msg.replyTo, msg.getData() != null ? new Bundle(msg.getData()) : new Bundle());
                    return;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private void handleFindNearest(Messenger replyTo, Bundle request) {
        new Thread(() -> {
            Bundle result;
            if (similarityIndex == null) {
                result = new Bundle();
                result.putInt(
                        EmbeddingCommandContract.KEY_REQUEST_ID,
                        request.getInt(EmbeddingCommandContract.KEY_REQUEST_ID, 0)
                );
                result.putString(EmbeddingCommandContract.KEY_ERROR, "Similarity index unavailable");
            } else {
                result = similarityIndex.findNearest(request);
            }
            sendMessage(replyTo, EmbeddingCommandContract.MSG_NEAREST_RESULT, result);
        }, "EmbeddingSimilarityWorker").start();
    }

    /**
     * Push #43: write the union of the current active batch (from disk)
     * + the pending-additions queue so a process restart resumes all
     * songs the user has queued. Called when the user adds songs mid-batch.
     */
    private void persistActiveBatchCombined() {
        ArrayList<String> combined = loadPersistedBatch();
        if (combined == null) combined = new ArrayList<>();
        synchronized (pendingAdditional) {
            for (String p : pendingAdditional) {
                if (p != null && !p.isEmpty() && !combined.contains(p)) combined.add(p);
            }
        }
        persistActiveBatch(combined);
    }

    private void persistActiveBatch(List<String> paths) {
        try {
            JSONArray arr = new JSONArray();
            for (String path : paths) {
                if (path != null && !path.isEmpty()) arr.put(path);
            }
            JSONObject payload = new JSONObject();
            payload.put("paths", arr);
            File file = new File(getEmbeddingDataDir(), ACTIVE_BATCH_FILE);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception e) {
            Log.w(TAG, "persistActiveBatch failed", e);
        }
    }

    private ArrayList<String> loadPersistedBatch() {
        try {
            File file = new File(getEmbeddingDataDir(), ACTIVE_BATCH_FILE);
            if (!file.exists()) return null;
            byte[] data = new byte[(int) file.length()];
            FileInputStream fis = new FileInputStream(file);
            int read = fis.read(data);
            fis.close();
            if (read <= 0) return null;
            JSONObject payload = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONArray arr = payload.optJSONArray("paths");
            if (arr == null || arr.length() == 0) return null;
            ArrayList<String> paths = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String path = arr.optString(i, "");
                if (!path.isEmpty()) paths.add(path);
            }
            return paths.isEmpty() ? null : paths;
        } catch (Exception e) {
            Log.w(TAG, "loadPersistedBatch failed", e);
            return null;
        }
    }

    private void clearPersistedBatch() {
        try {
            File file = new File(getEmbeddingDataDir(), ACTIVE_BATCH_FILE);
            if (file.exists()) file.delete();
        } catch (Exception e) {
            Log.w(TAG, "clearPersistedBatch failed", e);
        }
    }

    private File getEmbeddingDataDir() {
        File extDir = getApplicationContext().getExternalFilesDir(null);
        return extDir != null ? extDir : getApplicationContext().getFilesDir();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Push #49: IMPORTANCE_DEFAULT so the notification renders on
            // the lockscreen and the status bar icon shows during the
            // batch. Sound and vibration are explicitly disabled at the
            // channel level so DEFAULT doesn't actually play anything.
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AI Embedding",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Shows progress while embedding songs on device");
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setVibrationPattern(null);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildWarmingUpNotification(int total) {
        return buildWarmingUpNotification("Warming up audio model (" + total + " songs queued)");
    }

    /** Push #43: build the warming-up notification with arbitrary text so
     *  init-step events ("Extracting audio model…" / "Starting NPU/GPU…")
     *  can update the user-visible content. */
    private Notification buildWarmingUpNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingOpen = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle("AI Embedding")
                .setContentText(text)
                .setContentIntent(pendingOpen)
                .setOngoing(true)
                .setSilent(true)
                // Push #49: lockscreen + status bar visibility. The channel
                // already disables sound/vibration, so DEFAULT priority is
                // safe and ensures the row is visible on the lockscreen.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setProgress(0, 0, true)
                .build();
    }

    /** Push #43: swap the warm-up notification text without rebuilding
     *  the whole flow. Called by the init-step listener. */
    private void updateWarmingUpNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildWarmingUpNotification(text));
        }
    }

    private Notification buildNotification(String text, int progress, int total) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingOpen = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle("AI Embedding")
                .setContentText(text)
                .setContentIntent(pendingOpen)
                .setOngoing(true)
                .setSilent(true)
                // Push #49: lockscreen + status bar visibility.
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (total > 0) {
            builder.setProgress(total, Math.min(progress, total), false);
        }

        return builder.build();
    }

    private void updateNotification(String text, int progress, int total) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text, progress, total));
        }
    }
}
