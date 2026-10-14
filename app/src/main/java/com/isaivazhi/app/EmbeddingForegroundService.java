package com.isaivazhi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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
    // Queue of songs still to embed in the current run. Worked through
    // CHUNK_SIZE songs at a time; each chunk is removed once it finishes.
    private static final String ACTIVE_BATCH_FILE = "active_embedding_batch.json";
    // Append-only list (one path per line) of songs finished in the chunk
    // currently running. If the run is interrupted mid-chunk, these are
    // skipped on resume and only the unfinished songs are retried.
    private static final String ACTIVE_BATCH_DONE_FILE = "active_embedding_batch.done";
    private static final int CHUNK_SIZE = 5;
    // Re-armed at the start of every chunk, so the CPU stays awake for as
    // long as chunks keep running instead of a fixed budget for the batch.
    private static final long CHUNK_WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long WORKER_BUSY_RETRY_MS = 250L;

    public static final String ACTION_START = "com.isaivazhi.app.EMBEDDING_START";
    public static final String ACTION_STOP = "com.isaivazhi.app.EMBEDDING_STOP";
    // Push #40 Tier 3H: re-posted by onTaskRemoved so the service keeps
    // running after the user swipes the app from recents. Routes through
    // the persisted-batch resumption path.
    public static final String ACTION_RESUME = "com.isaivazhi.app.EMBEDDING_RESUME";
    public static final String EXTRA_PATHS = "paths";
    // Name of a file (in the embedding data dir) holding the song paths for
    // ACTION_START. Large libraries must use this instead of EXTRA_PATHS:
    // thousands of paths exceed the Binder transaction limit, and Android
    // then silently never delivers the start command to this service.
    public static final String EXTRA_REQUEST_FILE = "requestFile";
    private static final String START_REQUEST_PREFIX = "embed_start_request_";
    public static final String EXTRA_SPLIT_COUNT = "splitCount";
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
    private int batchSplitCount = 3;
    private volatile boolean stopRequested = false;
    private List<String> currentChunk = new ArrayList<>();

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
            stopRequested = true;
            clearPersistedBatch();
            cancelEmbedding();
            return START_NOT_STICKY;
        }

        // Every ACTION_START / ACTION_RESUME arrives via startForegroundService,
        // so startForeground must be called on every path, or Android kills
        // this process ~10 s later. If Android refuses (e.g. the 6-hour daily
        // dataSync limit), keep the queue on disk and stop instead of crashing.
        if (!embeddingInProgress && !safeStartForeground(buildWarmingUpNotification("Preparing to embed…"))) {
            if (ACTION_START.equals(action)) {
                ArrayList<String> paths = intent.getStringArrayListExtra(EXTRA_PATHS);
                String requestFile = intent.getStringExtra(EXTRA_REQUEST_FILE);
                if ((paths == null || paths.isEmpty()) && requestFile != null) {
                    paths = readStartRequest(requestFile);
                }
                if (paths != null && !paths.isEmpty()) appendToQueue(paths);
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            ArrayList<String> paths = intent.getStringArrayListExtra(EXTRA_PATHS);
            String requestFile = intent.getStringExtra(EXTRA_REQUEST_FILE);
            if ((paths == null || paths.isEmpty()) && requestFile != null) {
                paths = readStartRequest(requestFile);
            }
            batchSplitCount = EmbeddingWindowConfig.normalizeSplitCount(
                    intent.getIntExtra(EXTRA_SPLIT_COUNT, batchSplitCount));
            playbackActiveHint = intent.getBooleanExtra(EXTRA_PLAYBACK_ACTIVE, playbackActiveHint);
            if (paths == null || paths.isEmpty()) {
                if (!embeddingInProgress) {
                    stopForeground(true);
                    stopSelf();
                }
                return embeddingInProgress ? START_STICKY : START_NOT_STICKY;
            }
            // Songs added while a run is in flight join the end of its queue.
            if (embeddingInProgress) {
                int added = appendToQueue(paths);
                Log.i(TAG, "Queued " + added + " more song(s) onto the running batch");
                totalSteps += added;
                broadcastStatus();
                return START_STICKY;
            }
            clearBatchDoneFile();
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
            if (!embeddingInProgress && ACTION_RESUME.equals(action)) {
                clearPersistedBatch();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        if (!embeddingInProgress) {
            stopForeground(true);
            stopSelf();
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

    /** Starts a run over the queue already persisted in {@link #ACTIVE_BATCH_FILE}. */
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
                // Every chunk re-checks the (already warm) model; only the
                // first one should replace the progress notification.
                if (processedCount + failedCount == 0) {
                    updateWarmingUpNotification(initStepText);
                }
                broadcastStatus();
            });
        }
        embeddingService.setSplitCount(batchSplitCount);

        stopRequested = false;
        embeddingInProgress = true;
        currentStep = 0;
        totalSteps = paths != null ? paths.size() : 0;
        processedCount = 0;
        failedCount = 0;
        currentFilename = "";
        currentFilepath = "";
        broadcastStatus();

        runNextChunk();
    }

    /** Main thread only. Takes the next CHUNK_SIZE songs off the queue and embeds them. */
    private void runNextChunk() {
        if (stopRequested) {
            finishRun();
            return;
        }
        // A run stopped by onTimeout leaves its worker finishing the current
        // song; starting another worker beside it would run two batches at once.
        if (embeddingService.isWorkerRunning()) {
            mainHandler.postDelayed(this::runNextChunk, WORKER_BUSY_RETRY_MS);
            return;
        }
        ArrayList<String> queue = loadPersistedBatch();
        if (queue == null || queue.isEmpty()) {
            finishRun();
            return;
        }
        ArrayList<String> chunk = new ArrayList<>(queue.subList(0, Math.min(CHUNK_SIZE, queue.size())));
        currentChunk = chunk;
        if (wakeLock != null) wakeLock.acquire(CHUNK_WAKELOCK_TIMEOUT_MS);
        Log.i(TAG, "Chunk start: " + chunk.size() + " song(s), " + queue.size() + " left in queue");

        embeddingService.embedSongs(chunk, new EmbeddingService.EmbeddingCallback() {
            @Override
            public void onProgress(String filename, String filepath, int current, int total) {
                currentFilename = filename != null ? filename : "";
                currentFilepath = filepath != null ? filepath : "";
                int overall = processedCount + failedCount + 1;
                currentStep = overall;
                if (totalSteps < overall) totalSteps = overall;
                updateNotification("Embedding " + overall + "/" + totalSteps + ": " + currentFilename, overall, totalSteps);

                Bundle data = new Bundle();
                data.putString(EmbeddingCommandContract.KEY_FILENAME, currentFilename);
                data.putString(EmbeddingCommandContract.KEY_FILE_PATH, currentFilepath);
                data.putInt(EmbeddingCommandContract.KEY_CURRENT, overall);
                data.putInt(EmbeddingCommandContract.KEY_TOTAL, totalSteps);
                data.putInt(EmbeddingCommandContract.KEY_PROCESSED, processedCount);
                data.putInt(EmbeddingCommandContract.KEY_FAILED, failedCount);
                broadcastMessage(EmbeddingCommandContract.MSG_PROGRESS, data);
            }

            @Override
            public void onSongComplete(String filename, String filepath, String contentHash) {
                processedCount++;
                markBatchPathDone(filepath);
                Bundle data = new Bundle();
                data.putString(EmbeddingCommandContract.KEY_FILENAME, filename);
                data.putString(EmbeddingCommandContract.KEY_FILE_PATH, filepath);
                data.putString(EmbeddingCommandContract.KEY_CONTENT_HASH, contentHash);
                data.putInt(EmbeddingCommandContract.KEY_PROCESSED, processedCount);
                data.putInt(EmbeddingCommandContract.KEY_FAILED, failedCount);
                broadcastMessage(EmbeddingCommandContract.MSG_SONG_COMPLETE, data);
            }

            @Override
            public void onComplete(int chunkProcessed, int chunkFailed) {
                mainHandler.post(() -> onChunkComplete(chunk));
            }

            @Override
            public void onError(String message, String filepath) {
                boolean perSong = filepath != null && !filepath.isEmpty();
                if (perSong) {
                    failedCount++;
                }
                Bundle data = new Bundle();
                data.putString(EmbeddingCommandContract.KEY_ERROR, message != null ? message : "Unknown embedding error");
                if (filepath != null) data.putString(EmbeddingCommandContract.KEY_FILE_PATH, filepath);
                data.putInt(EmbeddingCommandContract.KEY_FAILED, failedCount);
                broadcastMessage(EmbeddingCommandContract.MSG_ERROR, data);
                if (!perSong) {
                    // Backend init failed; embedSongs returns without onComplete.
                    mainHandler.post(() -> finishRun());
                }
            }
        });
    }

    /** Main thread only. Drops the finished chunk from the queue and moves on. */
    private void onChunkComplete(List<String> chunk) {
        if (!embeddingInProgress) return;
        if (stopRequested) {
            finishRun();
            return;
        }
        ArrayList<String> queue = loadPersistedBatch();
        if (queue == null) queue = new ArrayList<>();
        queue.removeAll(new java.util.HashSet<>(chunk));
        clearBatchDoneFile();
        if (queue.isEmpty()) {
            clearPersistedBatch();
        } else {
            persistActiveBatch(queue);
        }
        currentChunk = new ArrayList<>();
        runNextChunk();
    }

    /** Main thread only. Ends the run and reports totals for every chunk. */
    private void finishRun() {
        if (!embeddingInProgress) return;
        int totalProcessed = processedCount;
        int failed = failedCount;
        Log.i(TAG, "Embedding complete: " + totalProcessed + " processed, " + failed + " failed");
        embeddingInProgress = false;
        currentStep = totalProcessed + failed;
        currentFilename = "";
        currentFilepath = "";
        currentChunk = new ArrayList<>();
        clearPersistedBatch();

        String summary = totalProcessed + " embedded" + (failed > 0 ? ", " + failed + " failed" : "") + " - done!";
        updateNotification(summary, totalProcessed + failed, totalProcessed + failed);

        Bundle data = new Bundle();
        data.putInt(EmbeddingCommandContract.KEY_PROCESSED, totalProcessed);
        data.putInt(EmbeddingCommandContract.KEY_FAILED, failed);
        broadcastMessage(EmbeddingCommandContract.MSG_COMPLETE, data);

        mainHandler.postDelayed(() -> {
            if (!embeddingInProgress) cleanup();
        }, 3000L);
    }

    /**
     * Android 15+ caps dataSync foreground services at 6 h per day. Stop the
     * current chunk but keep the queue on disk, so the app resumes the
     * remaining songs the next time it is opened.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        Log.w(TAG, "onTimeout: foreground time limit reached; pausing with queue kept on disk");
        stopRequested = true;
        embeddingInProgress = false;
        if (embeddingService != null) embeddingService.cancelEmbedding();
        Bundle data = new Bundle();
        data.putString(EmbeddingCommandContract.KEY_ERROR,
                "Paused: Android's background time limit was reached. Open the app later to continue.");
        data.putInt(EmbeddingCommandContract.KEY_FAILED, failedCount);
        broadcastMessage(EmbeddingCommandContract.MSG_ERROR, data);
        // Not cleanup(): the worker may still be inside inference, so the ONNX
        // session must not be released under it. The system only requires the
        // service to leave the foreground promptly.
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        stopSelf();
    }

    private void cancelEmbedding() {
        if (embeddingService != null) {
            embeddingService.cancelEmbedding();
        } else {
            cleanup();
        }
    }

    /** startForeground that reports instead of crashing the :ai process when Android refuses. */
    private boolean safeStartForeground(Notification notification) {
        try {
            startForeground(NOTIFICATION_ID, notification);
            return true;
        } catch (Throwable t) {
            String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            Log.w(TAG, "startForeground refused: " + detail, t);
            String message = detail.contains("Time limit")
                    ? "Android's daily background limit for embedding was reached. Keep the app open and tap Embed to continue."
                    : "Android did not allow embedding to start (" + detail + "). Open the app and tap Embed again.";
            Bundle data = new Bundle();
            data.putString(EmbeddingCommandContract.KEY_ERROR, message);
            data.putInt(EmbeddingCommandContract.KEY_FAILED, failedCount);
            broadcastMessage(EmbeddingCommandContract.MSG_ERROR, data);
            return false;
        }
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
        safeStartForeground(buildWarmingUpNotification(total));
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

    private int appendToQueue(List<String> paths) {
        ArrayList<String> queue = loadPersistedBatch();
        if (queue == null) queue = new ArrayList<>();
        java.util.Set<String> present = new java.util.HashSet<>(queue);
        present.addAll(currentChunk);
        int added = 0;
        for (String p : paths) {
            if (p != null && !p.isEmpty() && present.add(p)) {
                queue.add(p);
                added++;
            }
        }
        if (added > 0) persistActiveBatch(queue);
        return added;
    }

    private void persistActiveBatch(List<String> paths) {
        try {
            JSONArray arr = new JSONArray();
            for (String path : paths) {
                if (path != null && !path.isEmpty()) arr.put(path);
            }
            JSONObject payload = new JSONObject();
            payload.put("paths", arr);
            payload.put("splitCount", batchSplitCount);
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
            batchSplitCount = EmbeddingWindowConfig.normalizeSplitCount(
                    payload.optInt("splitCount", batchSplitCount));
            JSONArray arr = payload.optJSONArray("paths");
            if (arr == null || arr.length() == 0) return null;
            java.util.Set<String> done = loadBatchDonePaths();
            ArrayList<String> paths = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String path = arr.optString(i, "");
                if (!path.isEmpty() && !done.contains(path)) paths.add(path);
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
        clearBatchDoneFile();
    }

    private void markBatchPathDone(String filepath) {
        if (filepath == null || filepath.isEmpty() || filepath.indexOf('\n') >= 0) return;
        try (FileOutputStream fos = new FileOutputStream(
                new File(getEmbeddingDataDir(), ACTIVE_BATCH_DONE_FILE), true)) {
            fos.write((filepath + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "markBatchPathDone failed", e);
        }
    }

    private java.util.Set<String> loadBatchDonePaths() {
        java.util.Set<String> done = new java.util.HashSet<>();
        try {
            File file = new File(getEmbeddingDataDir(), ACTIVE_BATCH_DONE_FILE);
            if (!file.exists()) return done;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) done.add(line);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "loadBatchDonePaths failed", e);
        }
        return done;
    }

    private void clearBatchDoneFile() {
        try {
            File file = new File(getEmbeddingDataDir(), ACTIVE_BATCH_DONE_FILE);
            if (file.exists()) file.delete();
        } catch (Exception e) {
            Log.w(TAG, "clearBatchDoneFile failed", e);
        }
    }

    private File getEmbeddingDataDir() {
        return embeddingDataDir(getApplicationContext());
    }

    private static File embeddingDataDir(Context context) {
        File extDir = context.getExternalFilesDir(null);
        return extDir != null ? extDir : context.getFilesDir();
    }

    /** True when a run was interrupted and still has songs queued on disk. */
    public static boolean hasQueuedBatch(Context context) {
        File file = new File(embeddingDataDir(context), ACTIVE_BATCH_FILE);
        return file.exists() && file.length() > 0;
    }

    /**
     * Writes the song paths for an ACTION_START to a uniquely named file and
     * returns its name, to be passed as {@link #EXTRA_REQUEST_FILE}. Each
     * request gets its own file so rapid back-to-back starts can't overwrite
     * one another before the service reads them.
     */
    public static String writeStartRequest(Context context, List<String> paths) throws Exception {
        JSONArray arr = new JSONArray();
        for (String path : paths) {
            if (path != null && !path.isEmpty()) arr.put(path);
        }
        JSONObject payload = new JSONObject();
        payload.put("paths", arr);
        File dir = embeddingDataDir(context);
        if (!dir.exists()) dir.mkdirs();
        String name = START_REQUEST_PREFIX + System.nanoTime() + ".json";
        File tmp = new File(dir, name + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dir, name))) {
            tmp.delete();
            throw new java.io.IOException("could not rename " + tmp.getName());
        }
        return name;
    }

    private ArrayList<String> readStartRequest(String name) {
        if (!name.startsWith(START_REQUEST_PREFIX) || name.contains("/")) {
            Log.w(TAG, "Ignoring unexpected start request file name: " + name);
            return null;
        }
        File file = new File(getEmbeddingDataDir(), name);
        try {
            if (!file.exists()) {
                Log.w(TAG, "Start request file missing: " + name);
                return null;
            }
            byte[] data = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                int off = 0;
                while (off < data.length) {
                    int r = fis.read(data, off, data.length - off);
                    if (r < 0) break;
                    off += r;
                }
            }
            JSONArray arr = new JSONObject(new String(data, StandardCharsets.UTF_8)).optJSONArray("paths");
            ArrayList<String> paths = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String path = arr.optString(i, "");
                    if (!path.isEmpty()) paths.add(path);
                }
            }
            Log.i(TAG, "Read start request " + name + " (" + paths.size() + " songs)");
            return paths;
        } catch (Exception e) {
            Log.w(TAG, "readStartRequest failed for " + name, e);
            return null;
        } finally {
            file.delete();
        }
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
