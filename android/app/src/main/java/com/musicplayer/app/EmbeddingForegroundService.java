package com.musicplayer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that keeps the embedding process alive when the screen is off.
 * Android kills bare threads in the background and throttles MediaCodec access.
 * A foreground service with a persistent notification prevents this.
 */
public class EmbeddingForegroundService extends Service {

    private static final String TAG = "EmbeddingFgService";
    private static final String CHANNEL_ID = "embedding_service";
    private static final int NOTIFICATION_ID = 2;

    public static final String ACTION_START = "com.musicplayer.app.EMBEDDING_START";
    public static final String ACTION_STOP = "com.musicplayer.app.EMBEDDING_STOP";
    public static final String EXTRA_PATHS = "paths";

    private EmbeddingService embeddingService;
    private PowerManager.WakeLock wakeLock;

    // Static reference so MusicBridgePlugin can communicate with the running service
    static EmbeddingForegroundService instance;
    static EmbeddingService.EmbeddingCallback pluginCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();

        // Acquire a partial wake lock to keep CPU running during embedding
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "musicplayer:embedding");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            if (embeddingService != null) {
                embeddingService.cancelEmbedding();
            }
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            ArrayList<String> paths = intent.getStringArrayListExtra(EXTRA_PATHS);
            if (paths == null || paths.isEmpty()) {
                stopSelf();
                return START_NOT_STICKY;
            }

            // Start foreground with notification
            startForeground(NOTIFICATION_ID, buildNotification("Preparing...", 0, paths.size()));
            wakeLock.acquire(4 * 60 * 60 * 1000L); // 4 hour max

            startEmbedding(paths);
        }

        return START_NOT_STICKY;
    }

    private void startEmbedding(List<String> paths) {
        if (embeddingService == null) {
            embeddingService = new EmbeddingService(getApplicationContext());
        }

        embeddingService.embedSongs(paths, new EmbeddingService.EmbeddingCallback() {
            @Override
            public void onProgress(String filename, String filepath, int current, int total) {
                updateNotification("Embedding " + current + "/" + total + ": " + filename, current, total);
                if (pluginCallback != null) {
                    pluginCallback.onProgress(filename, filepath, current, total);
                }
            }

            @Override
            public void onSongComplete(String filename, String filepath, float[] embedding, String contentHash) {
                if (pluginCallback != null) {
                    pluginCallback.onSongComplete(filename, filepath, embedding, contentHash);
                }
            }

            @Override
            public void onComplete(int totalProcessed, int failed) {
                Log.i(TAG, "Embedding complete: " + totalProcessed + " processed, " + failed + " failed");
                // Show completion in notification briefly before stopping
                String summary = totalProcessed + " embedded" + (failed > 0 ? ", " + failed + " failed" : "") + " — done!";
                updateNotification(summary, totalProcessed, totalProcessed + failed);
                if (pluginCallback != null) {
                    pluginCallback.onComplete(totalProcessed, failed);
                }
                // Delay cleanup so user can see the completion notification
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> cleanup(), 3000);
            }

            @Override
            public void onError(String message, String filepath) {
                if (pluginCallback != null) {
                    pluginCallback.onError(message, filepath);
                }
            }
        });
    }

    public void cancelEmbedding() {
        if (embeddingService != null) {
            embeddingService.cancelEmbedding();
        }
    }

    private void cleanup() {
        // Release ONNX model (~271MB) from memory
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

    @Override
    public void onDestroy() {
        instance = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- Notification ---

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AI Embedding",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows progress while embedding songs on device");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, int progress, int total) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingOpen = PendingIntent.getActivity(
                this, 0, openIntent,
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
            builder.setProgress(total, progress, false);
        }

        return builder.build();
    }

    private void updateNotification(String text, int progress, int total) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress, total));
    }
}
