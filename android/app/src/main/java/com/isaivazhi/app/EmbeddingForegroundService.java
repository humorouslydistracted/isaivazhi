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
    private static final String CHANNEL_ID = "embedding_service";
    private static final int NOTIFICATION_ID = 2;
    private static final String ACTIVE_BATCH_FILE = "active_embedding_batch.json";

    public static final String ACTION_START = "com.isaivazhi.app.EMBEDDING_START";
    public static final String ACTION_STOP = "com.isaivazhi.app.EMBEDDING_STOP";
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

        if (ACTION_STOP.equals(action)) {
            clearPersistedBatch();
            cancelEmbedding();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            ArrayList<String> paths = intent.getStringArrayListExtra(EXTRA_PATHS);
            playbackActiveHint = intent.getBooleanExtra(EXTRA_PLAYBACK_ACTIVE, playbackActiveHint);
            if (paths == null || paths.isEmpty()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            persistActiveBatch(paths);
            beginForeground(paths.size(), "Preparing...");
            startEmbedding(paths);
            return START_STICKY;
        }

        ArrayList<String> persisted = loadPersistedBatch();
        if (!embeddingInProgress && persisted != null && !persisted.isEmpty()) {
            Log.i(TAG, "Resuming persisted embedding batch after service restart");
            beginForeground(persisted.size(), "Resuming embedding...");
            startEmbedding(persisted);
            return START_STICKY;
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
        }
        startForeground(NOTIFICATION_ID, buildNotification(text, 0, total));
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
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AI Embedding",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows progress while embedding songs on device");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
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
                .setPriority(NotificationCompat.PRIORITY_LOW);

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
