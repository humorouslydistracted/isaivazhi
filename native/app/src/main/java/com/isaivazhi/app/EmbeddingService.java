package com.isaivazhi.app;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.providers.NNAPIFlags;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * On-device audio embedding using ONNX Runtime.
 *
 * Pipeline per song:
 *   1. Decode audio to raw PCM (via MediaCodec)
 *   2. Convert to mono float32
 *   3. Resample to 48kHz
 *   4. Extract 3 x 10-second windows (at 20%, 50%, 80%)
 *   5. Run CLAP HTSAT-base encoder via ONNX
 *   6. Average window embeddings + L2 normalize
 *   7. Save to pending_embeddings.json
 *
 * The ONNX model (clap_audio_encoder.onnx) is loaded from app assets.
 * Results are staged in pending_embeddings.json and later promoted to the
 * stable binary store by the JS layer.
 */
public class EmbeddingService {

    private static final String TAG = "EmbeddingService";
    private static final String ONNX_MODEL_FILENAME = "clap_audio_encoder.onnx";
    private static final String PENDING_EMBEDDINGS_FILE = "pending_embeddings.json";
    private String dataDir; // set in constructor — app-private external storage

    private static final int TARGET_SAMPLE_RATE = 48000;
    private static final int WINDOW_SECONDS = 10;
    private static final int WINDOW_SAMPLES = TARGET_SAMPLE_RATE * WINDOW_SECONDS; // 480,000
    private volatile float[] windowPositions = EmbeddingWindowConfig.windowPositions(3);
    private volatile int splitCount = 3;
    private static final int EMBEDDING_DIM = 512;
    private static final int DEFAULT_ORT_THREADS = 2;
    private static final int PLAYBACK_FRIENDLY_ORT_THREADS = 1;
    private static final int ACTIVE_PLAYBACK_YIELD_MS = 120;
    private static final int COOLDOWN_YIELD_MS = 450;
    private static final int POWER_SAVE_YIELD_MS = 350;
    private static final int THERMAL_MODERATE_YIELD_MS = 700;
    private static final int THERMAL_SEVERE_YIELD_MS = 2000;
    private static final int MAX_RESPONSIVE_SLEEP_SLICE_MS = 120;

    private OrtEnvironment ortEnv;
    private OrtSession ortSession;
    private Context context;
    private final PlaybackActivityProvider playbackActivityProvider;
    private String modelFilePath;
    private int currentOrtThreads = 0;
    private boolean isInitialized = false;
    private volatile boolean cancelled = false;

    // Backend tracking — set during ensureOrtSessionForCurrentPolicy().
    // One of: "nnapi+fp16", "nnapi", "cpu", "cpu_fallback".
    // Once NNAPI fails to create a session, nnapiPermanentlyDisabled stays true so
    // we don't repeat the slow failed-init path on every policy switch.
    private volatile String activeBackend = "cpu";
    private volatile boolean nnapiPermanentlyDisabled = false;

    public String getActiveBackend() { return activeBackend; }

    /** 3, 5, or 7 — must match tools/embeddings/embedding_config.py. */
    public void setSplitCount(int count) {
        splitCount = EmbeddingWindowConfig.normalizeSplitCount(count);
        windowPositions = EmbeddingWindowConfig.windowPositions(splitCount);
        Log.i(TAG, "splitCount=" + splitCount + " windows=" + windowPositions.length);
    }

    public int getSplitCount() {
        return splitCount;
    }

    // Callback for progress/completion
    public interface PlaybackActivityProvider {
        boolean isPlaybackActive();

        default long getCooldownUntilElapsedMs() {
            return 0L;
        }

        default String getThrottleReason() {
            return "";
        }
    }

    /**
     * Push #43: granular init-step progress so the foreground notification
     * + status banner show the actual stuck step (extracting model /
     * waiting on NNAPI / falling back to CPU) instead of a black-box
     * "Warming up audio model". Eliminates the "stuck for minutes with no
     * info" experience.
     */
    public interface InitProgressListener {
        /** stepLabel examples: "extracting_model", "model_ready",
         *  "session_nnapi_fp16", "session_nnapi", "session_cpu",
         *  "session_ready", "session_failed". User-visible text is built
         *  by the listener. */
        void onInitStep(String stepLabel, String userVisibleText);
    }

    private volatile InitProgressListener initProgressListener = null;
    public void setInitProgressListener(InitProgressListener l) { this.initProgressListener = l; }
    private void emitInitStep(String label, String text) {
        Log.i(TAG, "init-step: " + label + " — " + text);
        InitProgressListener l = initProgressListener;
        if (l != null) {
            try { l.onInitStep(label, text); } catch (Throwable ignored) {}
        }
    }

    public interface EmbeddingCallback {
        void onProgress(String filename, String filepath, int current, int total);
        void onSongComplete(String filename, String filepath, String contentHash);
        void onComplete(int totalProcessed, int failed);
        void onError(String message, String filepath);
    }

    public EmbeddingService(Context context) {
        this(context, null);
    }

    public EmbeddingService(Context context, PlaybackActivityProvider playbackActivityProvider) {
        this.context = context;
        this.playbackActivityProvider = playbackActivityProvider;
        // Use app-private external storage — no permissions needed, works on GrapheneOS
        File extDir = context.getExternalFilesDir(null);
        if (extDir == null) extDir = context.getFilesDir();
        this.dataDir = extDir.getAbsolutePath();
    }

    /**
     * Initialize ONNX Runtime and load the model from assets.
     */
    private String lastError = "";

    public String getLastError() { return lastError; }

    // Push #40 Tier 2E: 30 s timeout per ONNX init step. ONNX Runtime
    // session creation has historically hung indefinitely on devices with
    // buggy NNAPI drivers — the user sees "warming up" forever with no
    // diagnostic. Wrapping each attempt in a Future.get(30, SECONDS)
    // bounds the wait and lets us fall through to CPU on hang.
    private static final long ORT_INIT_TIMEOUT_MS = 30_000L;

    public synchronized boolean initialize() {
        long t0 = SystemClock.elapsedRealtime();
        try {
            // Push #40 Tier 2F: trace every checkpoint so the next time
            // a user reports "stuck on warming up", logcat shows exactly
            // which step is hung — model extraction, env, NNAPI, etc.
            Log.i(TAG, "init: start (cancelled=" + cancelled + ")");
            if (ortEnv == null) {
                long ts = SystemClock.elapsedRealtime();
                ortEnv = OrtEnvironment.getEnvironment();
                Log.i(TAG, "init: OrtEnvironment.getEnvironment() ok in "
                        + (SystemClock.elapsedRealtime() - ts) + " ms");
            } else {
                Log.i(TAG, "init: OrtEnvironment already created — reuse");
            }

            // ONNX models with external data need filesystem paths (not asset streams).
            // Extract both .onnx and .onnx.data from assets to internal storage.
            File modelDir = new File(context.getFilesDir(), "onnx_model");
            if (!modelDir.exists()) modelDir.mkdirs();

            File modelFile = new File(modelDir, ONNX_MODEL_FILENAME);
            File dataFile = new File(modelDir, ONNX_MODEL_FILENAME + ".data");

            // Push #43: surface model extraction progress. The 273 MB
            // `.data` file can take 10-60 s to copy from APK assets to
            // internal storage on first launch — the user sees "Warming
            // up" with no info during that. Now they see "Extracting
            // audio model (273 MB)…".
            boolean needsExtract = !(modelFile.exists() && modelFile.length() > 0
                    && dataFile.exists() && dataFile.length() > 0);
            if (needsExtract) {
                emitInitStep("extracting_model", "Extracting audio model (~273 MB)…");
            }
            long tExtract = SystemClock.elapsedRealtime();
            extractAssetIfNeeded(ONNX_MODEL_FILENAME, modelFile);
            extractAssetIfNeeded(ONNX_MODEL_FILENAME + ".data", dataFile);
            Log.i(TAG, "init: assets ready in " + (SystemClock.elapsedRealtime() - tExtract) + " ms "
                    + "(graph=" + modelFile.length() + " B, weights=" + dataFile.length() + " B)");
            emitInitStep("model_ready", "Audio model ready, starting accelerator…");

            modelFilePath = modelFile.getAbsolutePath();
            boolean ok = ensureOrtSessionForCurrentPolicy();
            Log.i(TAG, "init: " + (ok ? "ok" : "failed")
                    + " — total " + (SystemClock.elapsedRealtime() - t0) + " ms, backend=" + activeBackend);
            return ok;

        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, "init: FAILED after " + (SystemClock.elapsedRealtime() - t0) + " ms", e);
            return false;
        }
    }

    private synchronized boolean ensureOrtSessionForCurrentPolicy() {
        try {
            if (ortEnv == null || modelFilePath == null) {
                return initialize();
            }

            int desiredThreads = getDesiredOrtThreads();
            if (isInitialized && ortSession != null && currentOrtThreads == desiredThreads) {
                return true;
            }

            if (ortSession != null) {
                try {
                    ortSession.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing old ONNX session before policy switch", e);
                }
                ortSession = null;
            }

            // Attempt NNAPI hardware acceleration (Android 8.1+ / API 27+).
            // NNAPI delegates ops to the device's neural accelerator (NPU/GPU/DSP);
            // unsupported ops fall back to CPU automatically inside ONNX Runtime.
            // Push #40 Tier 2E + 2F: each session-creation attempt runs in a
            // bounded Future so a hung NNAPI driver can't lock the service
            // forever. On timeout or failure with NNAPI, we mark NNAPI
            // permanently disabled and fall through to CPU.
            OrtSession.SessionOptions opts = buildSessionOptions(desiredThreads, /* tryNnapi */ !nnapiPermanentlyDisabled);
            String attemptedBackend = activeBackend;
            Log.i(TAG, "session: createSession start backend=" + attemptedBackend
                    + " threads=" + desiredThreads
                    + " modelPath=" + modelFilePath);
            // Push #43: surface the backend attempt to the user.
            emitInitStep("session_" + attemptedBackend.replace("+", "_"),
                    "Starting " + (attemptedBackend.startsWith("nnapi")
                            ? "NPU/GPU (" + attemptedBackend + ")…"
                            : "CPU…"));

            try {
                long tSess = SystemClock.elapsedRealtime();
                OrtSession.SessionOptions optsRef = opts;
                ortSession = createSessionWithTimeout(modelFilePath, optsRef, ORT_INIT_TIMEOUT_MS);
                Log.i(TAG, "session: createSession ok backend=" + attemptedBackend
                        + " in " + (SystemClock.elapsedRealtime() - tSess) + " ms");
            } catch (Throwable t) {
                Log.w(TAG, "session: createSession failed backend=" + attemptedBackend
                        + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
                if (attemptedBackend.startsWith("nnapi")) {
                    nnapiPermanentlyDisabled = true;
                    Log.w(TAG, "session: nnapiPermanentlyDisabled=true — retrying CPU-only");
                    emitInitStep("session_cpu_fallback",
                            "NPU/GPU unavailable — falling back to CPU…");
                    try {
                        opts.close();
                    } catch (Throwable ignored) {
                    }
                    opts = buildSessionOptions(desiredThreads, /* tryNnapi */ false);
                    long tCpu = SystemClock.elapsedRealtime();
                    ortSession = createSessionWithTimeout(modelFilePath, opts, ORT_INIT_TIMEOUT_MS);
                    Log.i(TAG, "session: CPU fallback ok in "
                            + (SystemClock.elapsedRealtime() - tCpu) + " ms");
                } else {
                    throw t;
                }
            }

            isInitialized = true;
            currentOrtThreads = desiredThreads;
            Log.i(TAG, "session: READY threads=" + desiredThreads
                    + " backend=" + activeBackend
                    + " throttleReason=" + getCurrentThrottleReason());
            emitInitStep("session_ready",
                    "Ready — using " + (activeBackend.startsWith("nnapi")
                            ? "NPU/GPU (" + activeBackend + ")"
                            : "CPU"));
            return true;
        } catch (Throwable e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, "Failed to prepare ONNX session", e);
            emitInitStep("session_failed",
                    "Backend init failed: " + e.getClass().getSimpleName());
            isInitialized = false;
            currentOrtThreads = 0;
            return false;
        }
    }

    /**
     * Push #40 Tier 2E: run ortEnv.createSession on a worker thread with a
     * hard timeout. If the call doesn't return within `timeoutMs`, we
     * cancel the future and throw TimeoutException. On NNAPI this commonly
     * happens with vendor driver bugs — the user sees "warming up" forever
     * otherwise. The session creation is still synchronous from the
     * service's perspective; we just bound the wait.
     */
    private OrtSession createSessionWithTimeout(String path, OrtSession.SessionOptions opts, long timeoutMs) throws Throwable {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "OrtSessionInit");
            th.setDaemon(true);
            return th;
        });
        try {
            Future<OrtSession> fut = exec.submit((Callable<OrtSession>) () -> ortEnv.createSession(path, opts));
            try {
                return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                fut.cancel(true);
                throw new TimeoutException("ortEnv.createSession exceeded " + timeoutMs + " ms");
            } catch (ExecutionException ee) {
                throw ee.getCause() != null ? ee.getCause() : ee;
            }
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * Build SessionOptions for the desired thread count. If tryNnapi is true and the
     * device API level supports NNAPI, attempts to add NNAPI execution provider with
     * FP16 enabled, falling back to NNAPI without FP16, then to CPU-only on any failure.
     * Sets {@link #activeBackend} to one of: "nnapi+fp16", "nnapi", "cpu".
     */
    private OrtSession.SessionOptions buildSessionOptions(int desiredThreads, boolean tryNnapi) throws Exception {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(desiredThreads);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

        String backend = "cpu";
        Log.i(TAG, "buildSessionOptions: tryNnapi=" + tryNnapi
                + " sdk=" + Build.VERSION.SDK_INT + " threads=" + desiredThreads);
        if (tryNnapi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // First try with FP16 — fastest path on devices that support it.
            long t1 = SystemClock.elapsedRealtime();
            try {
                EnumSet<NNAPIFlags> flags = EnumSet.of(NNAPIFlags.USE_FP16);
                opts.addNnapi(flags);
                backend = "nnapi+fp16";
                Log.i(TAG, "buildSessionOptions: NNAPI+FP16 attached in "
                        + (SystemClock.elapsedRealtime() - t1) + " ms");
            } catch (Throwable e1) {
                Log.w(TAG, "buildSessionOptions: NNAPI+FP16 failed after "
                        + (SystemClock.elapsedRealtime() - t1) + " ms ("
                        + e1.getClass().getSimpleName() + ": " + e1.getMessage()
                        + ") — trying NNAPI without FP16");
                long t2 = SystemClock.elapsedRealtime();
                try {
                    opts.addNnapi();
                    backend = "nnapi";
                    Log.i(TAG, "buildSessionOptions: NNAPI (FP32) attached in "
                            + (SystemClock.elapsedRealtime() - t2) + " ms");
                } catch (Throwable e2) {
                    Log.w(TAG, "buildSessionOptions: NNAPI not available after "
                            + (SystemClock.elapsedRealtime() - t2) + " ms ("
                            + e2.getClass().getSimpleName() + ": " + e2.getMessage()
                            + ") — falling back to CPU");
                    // backend stays "cpu" — opts already configured CPU-only.
                }
            }
        } else {
            Log.i(TAG, "buildSessionOptions: skipping NNAPI (tryNnapi=" + tryNnapi
                    + " sdk>=27?" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) + ")");
        }

        activeBackend = backend;
        Log.i(TAG, "buildSessionOptions: activeBackend=" + activeBackend);
        return opts;
    }

    private void extractAssetIfNeeded(String assetName, File destFile) throws Exception {
        if (destFile.exists() && destFile.length() > 0) {
            Log.i(TAG, "Asset already extracted: " + assetName + " (" + destFile.length() + " bytes)");
            return;
        }
        Log.i(TAG, "Extracting asset: " + assetName + " to " + destFile.getAbsolutePath());
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buf = new byte[65536];
            int len;
            long total = 0;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
                total += len;
            }
            Log.i(TAG, "Extracted " + assetName + ": " + total + " bytes");
        }
    }

    /**
     * Release ONNX resources.
     */
    public synchronized void release() {
        try {
            if (ortSession != null) ortSession.close();
            if (ortEnv != null) ortEnv.close();
        } catch (Exception e) {
            Log.e(TAG, "Error releasing ONNX: " + e.getMessage());
        }
        isInitialized = false;
        currentOrtThreads = 0;
        modelFilePath = null;
    }

    /**
     * Embed a list of audio files in background.
     * Results are saved to pending_embeddings.json incrementally.
     */
    public void cancelEmbedding() {
        cancelled = true;
        Log.i(TAG, "Embedding cancellation requested");
    }

    public void embedSongs(List<String> filePaths, EmbeddingCallback callback) {
        cancelled = false;
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

            if (!initialize()) {
                callback.onError("Failed to initialize ONNX Runtime: " + lastError, null);
                return;
            }

            // Load existing pending embeddings so interrupted runs can resume safely.
            JSONObject localEmbeddings = loadLocalEmbeddings();
            try {
                localEmbeddings.put("_split_count", splitCount);
            } catch (Exception ignored) {}
            int processed = 0;
            int failed = 0;

            for (int i = 0; i < filePaths.size(); i++) {
                if (cancelled) {
                    Log.i(TAG, "Embedding cancelled at " + (i) + "/" + filePaths.size());
                    writeLocalEmbeddings(localEmbeddings);
                    callback.onComplete(processed, failed);
                    return;
                }

                String path = filePaths.get(i);
                String filename = new File(path).getName();

                callback.onProgress(filename, path, i + 1, filePaths.size());

                if (isPathAlreadyEmbedded(localEmbeddings, path)) {
                    Log.i(TAG, "Skipping already-embedded path during resume: " + filename);
                    continue;
                }

                try {
                    // Decode audio (memory-capped) — retry once on failure
                    if (!yieldForPolicy("decode:" + filename)) {
                        writeLocalEmbeddings(localEmbeddings);
                        callback.onComplete(processed, failed);
                        return;
                    }
                    // Push #45e: window-aware decode. Replaces the
                    // previous full-song decode (up to 360 s of PCM,
                    // ~100 MB) with 4 small `seekTo` ranges: first 30 s
                    // for the content hash, plus 10 s each at 20/50/80 %
                    // for the inference windows. Peak memory drops from
                    // ~100 MB to ~12 MB; decode time drops from ~minutes
                    // to ~10-15 s on a typical Pixel. No more OOM at
                    // 107 MB on long songs.
                    DecodeResult dec = decodeAudioWindowAware(path);
                    if (dec == null || dec.windows == null || dec.windows.isEmpty()) {
                        Log.w(TAG, "Decode failed, retrying after GC: " + filename);
                        System.gc();
                        if (!sleepResponsive(1000)) {
                            writeLocalEmbeddings(localEmbeddings);
                            callback.onComplete(processed, failed);
                            return;
                        }
                        dec = decodeAudioWindowAware(path);
                    }
                    if (dec == null || dec.windows == null || dec.windows.isEmpty()) {
                        Log.w(TAG, "Decode failed on retry: " + filename);
                        callback.onError("Decode failed: " + filename, path);
                        failed++;
                        continue;
                    }

                    // Content hash from the first-30s chunk (matches the
                    // Python embedder's identity hash for rename-proof
                    // matching across machines).
                    String contentHash = computeContentHash(dec.first30s);
                    List<float[]> windows = dec.windows;

                    // Run ONNX inference. Batch all windows in a single forward pass when
                    // possible — ~2x faster than 3 separate sessions for typical songs.
                    // Single-window short songs use the same code path; batch=1 just runs once.
                    List<float[]> windowEmbeddings;
                    boolean inferenceSetupFailed = false;
                    if (!yieldForPolicy("inference:" + filename)) {
                        writeLocalEmbeddings(localEmbeddings);
                        callback.onComplete(processed, failed);
                        return;
                    }
                    if (!ensureOrtSessionForCurrentPolicy()) {
                        callback.onError("Failed to prepare ONNX Runtime: " + lastError, path);
                        failed++;
                        inferenceSetupFailed = true;
                        windowEmbeddings = Collections.emptyList();
                    } else {
                        windowEmbeddings = runInferenceBatch(windows);
                    }
                    // Release window arrays now that inference (or its fallback) is done.
                    for (int w = 0; w < windows.size(); w++) windows.set(w, null);
                    windows = null;
                    if (!yieldForPolicy("inference_complete:" + filename)) {
                        writeLocalEmbeddings(localEmbeddings);
                        callback.onComplete(processed, failed);
                        return;
                    }

                    if (inferenceSetupFailed) {
                        continue;
                    }

                    if (windowEmbeddings.isEmpty()) {
                        Log.w(TAG, "No embeddings produced for: " + filename);
                        callback.onError("Inference failed: " + filename, path);
                        failed++;
                        continue;
                    }

                    // Average embeddings and L2 normalize
                    float[] avgEmbedding = averageEmbeddings(windowEmbeddings);
                    windowEmbeddings = null;
                    l2Normalize(avgEmbedding);

                    // Save to pending embeddings with filepath
                    saveEmbedding(localEmbeddings, filename, path, avgEmbedding, contentHash);
                    callback.onSongComplete(filename, path, contentHash);
                    processed++;

                    Log.i(TAG, "Embedded: " + filename + " (" + (i + 1) + "/" + filePaths.size() + ")");

                } catch (Exception e) {
                    Log.e(TAG, "Error embedding " + filename, e);
                    callback.onError("Song failed: " + filename + " — " + e.getClass().getSimpleName() + ": " + e.getMessage(), path);
                    failed++;
                }

                // Avoid periodic explicit GC; it is a stop-the-world pause.
                // The retry-time GC above remains only for actual decode pressure.
                if (!yieldForPolicy("song_complete:" + filename)) {
                    writeLocalEmbeddings(localEmbeddings);
                    callback.onComplete(processed, failed);
                    return;
                }

                // Save incrementally every 5 songs
                if ((i + 1) % 5 == 0 || (i + 1) == filePaths.size()) {
                    writeLocalEmbeddings(localEmbeddings);
                }
            }

            // Final save
            writeLocalEmbeddings(localEmbeddings);
            callback.onComplete(processed, failed);

            Log.i(TAG, "Embedding complete: " + processed + " processed, " + failed + " failed");

        }, "EmbeddingWorker").start();
    }

    private boolean isPlaybackActive() {
        try {
            if (playbackActivityProvider != null) {
                return playbackActivityProvider.isPlaybackActive();
            }
            // Kotlin port: legacy MusicPlaybackService is removed. The provider
            // path (above) is the only one used in production — fallback now
            // safely returns false (assume not playing → no throttle).
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private long getPlaybackCooldownUntilElapsedMs() {
        try {
            return playbackActivityProvider != null ? playbackActivityProvider.getCooldownUntilElapsedMs() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private boolean isInPlaybackCooldown() {
        return getPlaybackCooldownUntilElapsedMs() > SystemClock.elapsedRealtime();
    }

    private String getCurrentThrottleReason() {
        try {
            String reason = playbackActivityProvider != null ? playbackActivityProvider.getThrottleReason() : "";
            if (reason != null && !reason.isEmpty()) return reason;
        } catch (Exception e) {
            // ignore
        }
        if (isInPlaybackCooldown()) return "playback_cooldown";
        if (isPlaybackActive()) return "playback_active";
        if (getDevicePressureDelayMs() > 0) return "device_pressure";
        return "normal";
    }

    private int getDesiredOrtThreads() {
        return (isPlaybackActive() || isInPlaybackCooldown() || getDevicePressureDelayMs() > 0)
                ? PLAYBACK_FRIENDLY_ORT_THREADS
                : DEFAULT_ORT_THREADS;
    }

    private int getDevicePressureDelayMs() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            int delay = 0;
            if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && pm.isPowerSaveMode()) {
                delay = Math.max(delay, POWER_SAVE_YIELD_MS);
            }
            if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int thermalStatus = pm.getCurrentThermalStatus();
                if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                    delay = Math.max(delay, THERMAL_SEVERE_YIELD_MS);
                } else if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                    delay = Math.max(delay, THERMAL_MODERATE_YIELD_MS);
                }
            }
            return delay;
        } catch (Exception e) {
            return 0;
        }
    }

    private int getPolicyYieldMs() {
        int delay = getDevicePressureDelayMs();
        long cooldownRemaining = getPlaybackCooldownUntilElapsedMs() - SystemClock.elapsedRealtime();
        if (cooldownRemaining > 0L) {
            delay = Math.max(delay, COOLDOWN_YIELD_MS);
        }
        if (isPlaybackActive()) {
            delay = Math.max(delay, ACTIVE_PLAYBACK_YIELD_MS);
        }
        return delay;
    }

    private boolean yieldForPolicy(String stage) {
        if (cancelled) return false;

        int delayMs = getPolicyYieldMs();
        if (delayMs <= 0) {
            Thread.yield();
            return !cancelled;
        }

        Log.d(TAG, "Yielding " + delayMs + "ms before/after " + stage
                + " (reason=" + getCurrentThrottleReason() + ")");
        return sleepResponsive(delayMs);
    }

    private boolean sleepResponsive(int totalMs) {
        int remaining = Math.max(0, totalMs);
        while (remaining > 0 && !cancelled) {
            int slice = Math.min(remaining, MAX_RESPONSIVE_SLEEP_SLICE_MS);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= slice;
        }
        return !cancelled;
    }

    /**
     * Embed a single song synchronously. Returns the embedding or null on failure.
     */
    public float[] embedSingle(String filePath) {
        if (!initialize()) return null;

        try {
            float[] audio = decodeAudio(filePath);
            if (audio == null || audio.length == 0) return null;

            List<float[]> windows = extractWindows(audio, windowPositions);
            List<float[]> embeddings = new ArrayList<>();

            for (float[] window : windows) {
                float[] emb = runInference(window);
                if (emb != null) embeddings.add(emb);
            }

            if (embeddings.isEmpty()) return null;

            float[] avg = averageEmbeddings(embeddings);
            l2Normalize(avg);
            return avg;

        } catch (Exception e) {
            Log.e(TAG, "embedSingle failed: " + e.getMessage());
            return null;
        }
    }

    // ===== AUDIO DECODING =====

    /**
     * Decode audio file to mono float32 samples at 48kHz.
     * Memory-optimized: only decodes up to MAX_DECODE_SECONDS to avoid OOM
     * on long songs. We only need ~80% of the song for the last window.
     */
    private static final int MAX_DECODE_SECONDS = 360; // 6 minutes max (enough for 80% window of a 7-min song)

    private float[] decodeAudio(String filePath) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;

        try {
            extractor.setDataSource(filePath);

            // Find audio track
            int audioTrack = -1;
            int sampleRate = 0;
            int channels = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    break;
                }
            }

            if (audioTrack < 0) {
                Log.w(TAG, "No audio track found in: " + filePath);
                return null;
            }

            extractor.selectTrack(audioTrack);
            MediaFormat format = extractor.getTrackFormat(audioTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            // Cap decoded samples to avoid OOM on long songs
            int maxMonoSamples = MAX_DECODE_SECONDS * sampleRate;
            int maxRawSamples = maxMonoSamples * channels;

            // Start with a small buffer (60s) and grow as needed — avoids
            // wasting 100MB on short songs that only need 20MB
            int initialSize = Math.min(maxRawSamples, sampleRate * channels * 60);
            float[] rawBuf = new float[initialSize];
            int totalSamples = 0;
            boolean inputDone = false;
            boolean outputDone = false;

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (!outputDone && totalSamples < maxRawSamples) {
                // Feed input
                if (!inputDone) {
                    int inputIdx = codec.dequeueInputBuffer(10000);
                    if (inputIdx >= 0) {
                        ByteBuffer inputBuf = codec.getInputBuffer(inputIdx);
                        int sampleSize = extractor.readSampleData(inputBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inputIdx, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                // Read output
                int outputIdx = codec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputIdx >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }

                    ByteBuffer outputBuf = codec.getOutputBuffer(outputIdx);
                    if (outputBuf != null && bufferInfo.size > 0) {
                        outputBuf.position(bufferInfo.offset);
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size);

                        int numSamples = bufferInfo.size / 2;
                        // Grow buffer if needed (grow by 50% to reduce copies)
                        if (totalSamples + numSamples > rawBuf.length) {
                            int newSize = Math.min(maxRawSamples, rawBuf.length + rawBuf.length / 2);
                            if (newSize > rawBuf.length) {
                                float[] newBuf = new float[newSize];
                                System.arraycopy(rawBuf, 0, newBuf, 0, totalSamples);
                                rawBuf = newBuf;
                            } else {
                                // Already at max — stop decoding
                                codec.releaseOutputBuffer(outputIdx, false);
                                break;
                            }
                        }

                        for (int i = 0; i < numSamples && totalSamples < maxRawSamples; i++) {
                            short s = (short) (outputBuf.get() & 0xFF | (outputBuf.get() << 8));
                            rawBuf[totalSamples++] = s / 32768.0f;
                        }
                    }

                    codec.releaseOutputBuffer(outputIdx, false);
                }
            }

            // Stop codec before processing to free native media buffers
            codec.stop();
            codec.release();
            codec = null;
            extractor.release();
            extractor = null;

            // Convert to mono in-place where possible to minimize peak memory
            float[] monoSamples;
            if (channels > 1) {
                int monoLen = totalSamples / channels;
                // Reuse rawBuf if possible (mono fits in existing allocation)
                for (int i = 0; i < monoLen; i++) {
                    float sum = 0;
                    for (int c = 0; c < channels; c++) {
                        sum += rawBuf[i * channels + c];
                    }
                    rawBuf[i] = sum / channels;
                }
                monoSamples = rawBuf; // reuse buffer, only first monoLen samples are valid
                totalSamples = monoLen;
            } else {
                monoSamples = rawBuf;
            }

            // Resample to 48kHz if needed
            float[] result;
            if (sampleRate != TARGET_SAMPLE_RATE) {
                double ratio = (double) TARGET_SAMPLE_RATE / sampleRate;
                int outputLength = (int) (totalSamples * ratio);
                result = new float[outputLength];
                for (int i = 0; i < outputLength; i++) {
                    double srcIdx = i / ratio;
                    int idx0 = (int) srcIdx;
                    int idx1 = Math.min(idx0 + 1, totalSamples - 1);
                    float frac = (float) (srcIdx - idx0);
                    result[i] = monoSamples[idx0] * (1 - frac) + monoSamples[idx1] * frac;
                }
            } else if (totalSamples < monoSamples.length) {
                // Trim to actual size
                result = new float[totalSamples];
                System.arraycopy(monoSamples, 0, result, 0, totalSamples);
            } else {
                result = monoSamples;
            }

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Decode error: " + e.getMessage());
            return null;
        } finally {
            // Release in finally as safety net (may already be released above)
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception e) { /* ignore */ }
            try {
                if (extractor != null) extractor.release();
            } catch (Exception e) { /* ignore */ }
        }
    }

    /**
     * Push #45e: window-aware decode. Returns the 30-second prefix (for
     * content hashing) AND the 3 ten-second inference windows in one
     * pass with multiple MediaExtractor.seekTo() ranges. Replaces the
     * previous full-song decode which loaded up to 360 s of PCM into
     * RAM and OOM'd on long songs.
     */
    private static final class DecodeResult {
        final float[] first30s;
        final List<float[]> windows;
        DecodeResult(float[] first30s, List<float[]> windows) {
            this.first30s = first30s;
            this.windows = windows;
        }
    }

    private DecodeResult decodeAudioWindowAware(String filePath) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(filePath);
            int audioTrack = -1;
            int sampleRate = 0;
            int channels = 0;
            long durationUs = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = format.getLong(MediaFormat.KEY_DURATION);
                    }
                    break;
                }
            }
            if (audioTrack < 0) {
                Log.w(TAG, "decodeAudioWindowAware: no audio track in " + filePath);
                return null;
            }
            extractor.selectTrack(audioTrack);
            MediaFormat format = extractor.getTrackFormat(audioTrack);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            long windowDurUs = (long) WINDOW_SECONDS * 1_000_000L;
            long hashDurUs = 30L * 1_000_000L;

            // Special case: song shorter than one window. Decode all,
            // pad to WINDOW_SAMPLES, use as both hash + single window.
            if (durationUs > 0 && durationUs < windowDurUs) {
                float[] all = decodeRange(extractor, codec, sampleRate, channels, 0L, durationUs);
                if (all == null) return null;
                float[] padded = new float[WINDOW_SAMPLES];
                System.arraycopy(all, 0, padded, 0, Math.min(all.length, WINDOW_SAMPLES));
                List<float[]> wins = new ArrayList<>();
                wins.add(padded);
                return new DecodeResult(all, wins);
            }

            // 1. First 30 s for the content hash.
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec.flush();
            float[] first30s = decodeRange(extractor, codec, sampleRate, channels, 0L, hashDurUs);

            // 2. Ten-second windows at configured center positions.
            float[] positions = windowPositions;
            List<float[]> windows = new ArrayList<>(positions.length);
            for (float pos : positions) {
                long centerUs = (long) (durationUs * pos);
                long startUs = Math.max(0L, centerUs - windowDurUs / 2);
                long endUs = Math.min(durationUs, startUs + windowDurUs);
                if (endUs - startUs < windowDurUs) {
                    // Shift back if too close to the song's end.
                    startUs = Math.max(0L, endUs - windowDurUs);
                }
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                codec.flush();
                float[] win = decodeRange(extractor, codec, sampleRate, channels, startUs, startUs + windowDurUs);
                if (win == null) return null;
                float[] fixed = new float[WINDOW_SAMPLES];
                System.arraycopy(win, 0, fixed, 0, Math.min(win.length, WINDOW_SAMPLES));
                windows.add(fixed);
            }
            return new DecodeResult(first30s != null ? first30s : new float[0], windows);
        } catch (Exception e) {
            Log.e(TAG, "decodeAudioWindowAware error: " + e.getMessage(), e);
            return null;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * Decode the slice `[startUs, endUs]` from `extractor` (already seeked
     * + `codec.flush()`-ed by caller) into mono 48 kHz float32. Used by
     * decodeAudioWindowAware to fetch just the prefix and the 3 windows
     * without decoding the whole song.
     */
    private float[] decodeRange(MediaExtractor extractor, MediaCodec codec,
                                int sampleRate, int channels,
                                long startUs, long endUs) {
        try {
            long durUs = Math.max(0L, endUs - startUs);
            // Worst-case raw buffer: target duration at source rate × channels,
            // plus a 1-second slack for codec output that overshoots endUs.
            int maxRawSamples = (int) ((durUs / 1_000_000.0) * sampleRate * channels)
                + sampleRate * channels;
            float[] rawBuf = new float[Math.max(maxRawSamples, sampleRate * channels)];
            int totalRaw = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        int sz = (inBuf != null) ? extractor.readSampleData(inBuf, 0) : -1;
                        long pts = extractor.getSampleTime();
                        if (sz < 0 || (pts >= 0 && pts > endUs)) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, pts, 0);
                            extractor.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0 && info.presentationTimeUs <= endUs) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        int numSamples = info.size / 2;
                        for (int i = 0; i < numSamples; i++) {
                            if (totalRaw >= rawBuf.length) {
                                // grow if we underestimated
                                float[] grown = new float[rawBuf.length * 2];
                                System.arraycopy(rawBuf, 0, grown, 0, totalRaw);
                                rawBuf = grown;
                            }
                            short s = (short) (outBuf.get() & 0xFF | (outBuf.get() << 8));
                            rawBuf[totalRaw++] = s / 32768.0f;
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if (info.presentationTimeUs > endUs) break;
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // No more output coming; we're done.
                    break;
                }
            }
            // Mono conversion (in-place reuse of rawBuf).
            if (channels > 1) {
                int monoLen = totalRaw / channels;
                for (int i = 0; i < monoLen; i++) {
                    float sum = 0;
                    for (int c = 0; c < channels; c++) sum += rawBuf[i * channels + c];
                    rawBuf[i] = sum / channels;
                }
                totalRaw = monoLen;
            }
            // Resample to 48 kHz (linear interp — matches existing path).
            if (sampleRate != TARGET_SAMPLE_RATE) {
                double ratio = (double) TARGET_SAMPLE_RATE / sampleRate;
                int outLen = (int) (totalRaw * ratio);
                float[] out = new float[outLen];
                for (int i = 0; i < outLen; i++) {
                    double srcIdx = i / ratio;
                    int idx0 = (int) srcIdx;
                    int idx1 = Math.min(idx0 + 1, totalRaw - 1);
                    float frac = (float) (srcIdx - idx0);
                    out[i] = rawBuf[idx0] * (1 - frac) + rawBuf[idx1] * frac;
                }
                return out;
            }
            // Trim to actual size.
            float[] out = new float[totalRaw];
            System.arraycopy(rawBuf, 0, out, 0, totalRaw);
            return out;
        } catch (Exception e) {
            Log.e(TAG, "decodeRange error: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Convert interleaved multi-channel audio to mono by averaging channels.
     */
    private float[] toMono(float[] samples, int channels) {
        int monoLength = samples.length / channels;
        float[] mono = new float[monoLength];
        for (int i = 0; i < monoLength; i++) {
            float sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += samples[i * channels + c];
            }
            mono[i] = sum / channels;
        }
        return mono;
    }

    /**
     * Resample audio using linear interpolation.
     * Good enough for embedding purposes — not audiophile quality, but
     * produces near-identical embeddings to librosa's resampling.
     */
    private float[] resample(float[] input, int fromRate, int toRate) {
        if (fromRate == toRate) return input;

        double ratio = (double) toRate / fromRate;
        int outputLength = (int) (input.length * ratio);
        float[] output = new float[outputLength];

        for (int i = 0; i < outputLength; i++) {
            double srcIdx = i / ratio;
            int idx0 = (int) srcIdx;
            int idx1 = Math.min(idx0 + 1, input.length - 1);
            float frac = (float) (srcIdx - idx0);
            output[i] = input[idx0] * (1 - frac) + input[idx1] * frac;
        }

        return output;
    }

    // ===== WINDOW EXTRACTION =====

    /**
     * Extract 10-second windows at specified positions (20%, 50%, 80%).
     * For short songs (< 10s), returns a single zero-padded window.
     */
    private List<float[]> extractWindows(float[] audio, float[] positions) {
        List<float[]> windows = new ArrayList<>();

        if (audio.length <= WINDOW_SAMPLES) {
            // Short song: pad and return single window
            float[] padded = new float[WINDOW_SAMPLES];
            System.arraycopy(audio, 0, padded, 0, Math.min(audio.length, WINDOW_SAMPLES));
            windows.add(padded);
            return windows;
        }

        for (float pos : positions) {
            int center = (int) (audio.length * pos);
            int start = Math.max(0, center - WINDOW_SAMPLES / 2);
            if (start + WINDOW_SAMPLES > audio.length) {
                start = audio.length - WINDOW_SAMPLES;
            }

            float[] window = new float[WINDOW_SAMPLES];
            System.arraycopy(audio, start, window, 0, WINDOW_SAMPLES);
            windows.add(window);
        }

        return windows;
    }

    // ===== ONNX INFERENCE =====

    /**
     * Run ONNX inference on a single 10-second window.
     * Input: float[480000] (48kHz mono)
     * Output: float[512] (embedding, not yet averaged/normalized)
     */
    private float[] runInference(float[] window) {
        try {
            if (!ensureOrtSessionForCurrentPolicy()) {
                return null;
            }
            // Create input tensor: shape [1, 480000]
            float[][] input2d = new float[1][WINDOW_SAMPLES];
            System.arraycopy(window, 0, input2d[0], 0, WINDOW_SAMPLES);

            OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, input2d);

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("waveform", inputTensor);

            // Run inference
            OrtSession.Result result = ortSession.run(inputs);

            // Get output: shape [1, 512]
            float[][] output = (float[][]) result.get(0).getValue();
            float[] embedding = output[0];

            // Cleanup
            inputTensor.close();
            result.close();

            return embedding;

        } catch (Exception e) {
            Log.e(TAG, "Inference error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Run ONNX inference on N windows in a single batched forward pass.
     * Input: List of N float[WINDOW_SAMPLES] arrays.
     * Output: List of N float[EMBEDDING_DIM] embeddings (same order, not normalized).
     *
     * For typical 3-window songs this is ~2x faster than 3 separate runInference()
     * calls because session setup, tensor allocation, and NPU dispatch happen once
     * instead of three times. Falls through to runInference() per window if the batch
     * call fails (e.g., NNAPI driver doesn't handle batch dim &gt; 1 for some op).
     */
    private List<float[]> runInferenceBatch(List<float[]> windows) {
        if (windows == null || windows.isEmpty()) return Collections.emptyList();
        if (windows.size() == 1) {
            // No batching benefit; use single-window path which already exists.
            float[] emb = runInference(windows.get(0));
            return emb != null ? Collections.singletonList(emb) : Collections.emptyList();
        }

        OnnxTensor inputTensor = null;
        OrtSession.Result result = null;
        try {
            if (!ensureOrtSessionForCurrentPolicy()) {
                return Collections.emptyList();
            }
            int batch = windows.size();
            // Create batched input tensor: shape [batch, WINDOW_SAMPLES]
            float[][] input2d = new float[batch][WINDOW_SAMPLES];
            for (int i = 0; i < batch; i++) {
                float[] w = windows.get(i);
                if (w == null || w.length != WINDOW_SAMPLES) {
                    Log.w(TAG, "Batch inference: window " + i + " has wrong shape, falling back to per-window");
                    return runInferencePerWindow(windows);
                }
                System.arraycopy(w, 0, input2d[i], 0, WINDOW_SAMPLES);
            }

            inputTensor = OnnxTensor.createTensor(ortEnv, input2d);
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("waveform", inputTensor);

            result = ortSession.run(inputs);

            // Output: shape [batch, EMBEDDING_DIM]
            float[][] output = (float[][]) result.get(0).getValue();
            if (output == null || output.length != batch) {
                Log.w(TAG, "Batch inference: unexpected output shape, falling back to per-window");
                return runInferencePerWindow(windows);
            }

            List<float[]> embeddings = new ArrayList<>(batch);
            for (int i = 0; i < batch; i++) {
                if (output[i] == null || output[i].length != EMBEDDING_DIM) {
                    Log.w(TAG, "Batch inference: row " + i + " has wrong dim, falling back to per-window");
                    return runInferencePerWindow(windows);
                }
                embeddings.add(output[i]);
            }
            return embeddings;

        } catch (Throwable e) {
            Log.w(TAG, "Batch inference failed (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + ") — falling back to per-window inference");
            return runInferencePerWindow(windows);
        } finally {
            if (inputTensor != null) {
                try { inputTensor.close(); } catch (Throwable ignored) {}
            }
            if (result != null) {
                try { result.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Per-window fallback path used when batched inference is unavailable. */
    private List<float[]> runInferencePerWindow(List<float[]> windows) {
        List<float[]> embeddings = new ArrayList<>(windows.size());
        for (int i = 0; i < windows.size(); i++) {
            float[] w = windows.get(i);
            if (w == null) continue;
            float[] emb = runInference(w);
            if (emb != null) embeddings.add(emb);
        }
        return embeddings;
    }

    // ===== EMBEDDING MATH =====

    private float[] averageEmbeddings(List<float[]> embeddings) {
        float[] avg = new float[EMBEDDING_DIM];
        for (float[] emb : embeddings) {
            for (int i = 0; i < EMBEDDING_DIM; i++) {
                avg[i] += emb[i];
            }
        }
        float scale = 1.0f / embeddings.size();
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            avg[i] *= scale;
        }
        return avg;
    }

    private void l2Normalize(float[] vec) {
        float norm = 0;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-8f) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= norm;
            }
        }
    }

    // ===== CONTENT HASH =====

    /**
     * Compute hash from first 30 seconds of audio samples.
     * Matches the Python embedder's content hash for rename-proof identity.
     */
    private String computeContentHash(float[] audio) {
        try {
            int hashSamples = Math.min(audio.length, 30 * TARGET_SAMPLE_RATE);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Convert to int16 bytes (matching Python: (y * 32767).astype(np.int16))
            byte[] bytes = new byte[hashSamples * 2];
            for (int i = 0; i < hashSamples; i++) {
                short s = (short) Math.max(-32768, Math.min(32767, audio[i] * 32767));
                bytes[i * 2] = (byte) (s & 0xFF);
                bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }

            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) { // first 16 hex chars
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();

        } catch (Exception e) {
            return "unknown";
        }
    }

    // ===== LOCAL STORAGE =====

    private JSONObject loadLocalEmbeddings() {
        try {
            File file = new File(dataDir, PENDING_EMBEDDINGS_FILE);
            if (!file.exists()) return new JSONObject();

            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            return new JSONObject(new String(data, "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void saveEmbedding(JSONObject localEmbeddings, String filename,
                               String filepath, float[] embedding, String contentHash) {
        try {
            JSONObject entry = new JSONObject();
            JSONArray embArray = new JSONArray();
            for (float v : embedding) embArray.put(v);
            entry.put("embedding", embArray);
            entry.put("content_hash", contentHash);
            entry.put("filepath", filepath);
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("filename", filename);

            // Store by content hash as primary key
            localEmbeddings.put(contentHash, entry);
            // Also store filename→hash mapping for quick lookup
            if (!localEmbeddings.has("_path_index")) {
                localEmbeddings.put("_path_index", new JSONObject());
            }
            localEmbeddings.getJSONObject("_path_index").put(filepath, contentHash);
        } catch (Exception e) {
            Log.e(TAG, "Error saving embedding: " + e.getMessage());
        }
    }

    private void writeLocalEmbeddings(JSONObject localEmbeddings) {
        try {
            File dir = new File(dataDir);
            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, PENDING_EMBEDDINGS_FILE);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(localEmbeddings.toString().getBytes("UTF-8"));
            fos.close();

            Log.i(TAG, "Pending embeddings saved: " + localEmbeddings.length() + " entries");
        } catch (Exception e) {
            Log.e(TAG, "Error writing pending embeddings: " + e.getMessage());
        }
    }

    private boolean isPathAlreadyEmbedded(JSONObject localEmbeddings, String filepath) {
        try {
            if (localEmbeddings == null || filepath == null || filepath.isEmpty()) return false;
            JSONObject pathIndex = localEmbeddings.optJSONObject("_path_index");
            if (pathIndex == null) return false;
            String contentHash = pathIndex.optString(filepath, "");
            if (contentHash.isEmpty()) return false;
            JSONObject existing = localEmbeddings.optJSONObject(contentHash);
            return existing != null && existing.has("embedding");
        } catch (Exception e) {
            return false;
        }
    }
}
