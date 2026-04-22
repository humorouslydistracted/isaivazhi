package com.musicplayer.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final float[] WINDOW_POSITIONS = {0.20f, 0.50f, 0.80f};
    private static final int EMBEDDING_DIM = 512;

    private OrtEnvironment ortEnv;
    private OrtSession ortSession;
    private Context context;
    private boolean isInitialized = false;
    private volatile boolean cancelled = false;

    // Callback for progress/completion
    public interface EmbeddingCallback {
        void onProgress(String filename, String filepath, int current, int total);
        void onSongComplete(String filename, String filepath, float[] embedding, String contentHash);
        void onComplete(int totalProcessed, int failed);
        void onError(String message, String filepath);
    }

    public EmbeddingService(Context context) {
        this.context = context;
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

    public synchronized boolean initialize() {
        if (isInitialized) return true;

        try {
            Log.i(TAG, "Initializing ONNX Runtime...");
            ortEnv = OrtEnvironment.getEnvironment();
            Log.i(TAG, "ORT environment created");

            // ONNX models with external data need filesystem paths (not asset streams).
            // Extract both .onnx and .onnx.data from assets to internal storage.
            File modelDir = new File(context.getFilesDir(), "onnx_model");
            if (!modelDir.exists()) modelDir.mkdirs();

            File modelFile = new File(modelDir, ONNX_MODEL_FILENAME);
            File dataFile = new File(modelDir, ONNX_MODEL_FILENAME + ".data");

            // Extract if not already done (or if sizes don't match)
            extractAssetIfNeeded(ONNX_MODEL_FILENAME, modelFile);
            extractAssetIfNeeded(ONNX_MODEL_FILENAME + ".data", dataFile);

            Log.i(TAG, "Model files ready: " + modelFile.length() + " bytes (graph), " + dataFile.length() + " bytes (weights)");

            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            // Load from filesystem path so ONNX can find the .data file
            ortSession = ortEnv.createSession(modelFile.getAbsolutePath(), opts);
            isInitialized = true;
            Log.i(TAG, "ONNX session created successfully");
            return true;

        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, "Failed to initialize ONNX", e);
            return false;
        }
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
            if (!initialize()) {
                callback.onError("Failed to initialize ONNX Runtime: " + lastError, null);
                return;
            }

            // Load existing pending embeddings so interrupted runs can resume safely.
            JSONObject localEmbeddings = loadLocalEmbeddings();
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

                try {
                    // Decode audio (memory-capped) — retry once on failure
                    float[] audioSamples = decodeAudio(path);
                    if (audioSamples == null || audioSamples.length == 0) {
                        Log.w(TAG, "Decode failed, retrying after GC: " + filename);
                        System.gc();
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { /* ignore */ }
                        audioSamples = decodeAudio(path);
                    }
                    if (audioSamples == null || audioSamples.length == 0) {
                        Log.w(TAG, "Decode failed on retry: " + filename);
                        callback.onError("Decode failed: " + filename, path);
                        failed++;
                        continue;
                    }

                    // Compute content hash (uses first 30s)
                    String contentHash = computeContentHash(audioSamples);

                    // Extract only the 3 windows we need, then release full audio
                    List<float[]> windows = extractWindows(audioSamples);
                    audioSamples = null; // release large decoded array

                    // Run ONNX inference on each window
                    List<float[]> windowEmbeddings = new ArrayList<>();
                    for (int w = 0; w < windows.size(); w++) {
                        float[] emb = runInference(windows.get(w));
                        if (emb != null) {
                            windowEmbeddings.add(emb);
                        }
                        windows.set(w, null); // release window after inference
                    }
                    windows = null;

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
                    callback.onSongComplete(filename, path, avgEmbedding, contentHash);
                    processed++;

                    Log.i(TAG, "Embedded: " + filename + " (" + (i + 1) + "/" + filePaths.size() + ")");

                } catch (Exception e) {
                    Log.e(TAG, "Error embedding " + filename, e);
                    callback.onError("Song failed: " + filename + " — " + e.getClass().getSimpleName() + ": " + e.getMessage(), path);
                    failed++;
                }

                // GC after every song to reclaim decoded audio memory before next decode
                System.gc();

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

    /**
     * Embed a single song synchronously. Returns the embedding or null on failure.
     */
    public float[] embedSingle(String filePath) {
        if (!initialize()) return null;

        try {
            float[] audio = decodeAudio(filePath);
            if (audio == null || audio.length == 0) return null;

            List<float[]> windows = extractWindows(audio);
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
    private List<float[]> extractWindows(float[] audio) {
        List<float[]> windows = new ArrayList<>();

        if (audio.length <= WINDOW_SAMPLES) {
            // Short song: pad and return single window
            float[] padded = new float[WINDOW_SAMPLES];
            System.arraycopy(audio, 0, padded, 0, Math.min(audio.length, WINDOW_SAMPLES));
            windows.add(padded);
            return windows;
        }

        for (float pos : WINDOW_POSITIONS) {
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
}
