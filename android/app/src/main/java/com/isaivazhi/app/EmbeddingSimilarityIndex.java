package com.isaivazhi.app;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Lightweight nearest-neighbor reader for the stable embedding store.
 * Runs in :ai so large similarity scans do not occupy the WebView process.
 */
final class EmbeddingSimilarityIndex {
    private static final String TAG = "EmbeddingSimilarityIndex";
    private static final String STABLE_BIN = "local_embeddings.bin";
    private static final String STABLE_META = "local_embeddings_meta.json";

    private final Context appContext;
    private long loadedBinModified = -1L;
    private long loadedMetaModified = -1L;
    private int dim = 0;
    private Entry[] entries = new Entry[0];
    private float[] vectors = new float[0];

    EmbeddingSimilarityIndex(Context context) {
        this.appContext = context.getApplicationContext();
    }

    synchronized Bundle findNearest(Bundle request) {
        Bundle out = new Bundle();
        int requestId = request != null ? request.getInt(EmbeddingCommandContract.KEY_REQUEST_ID, 0) : 0;
        out.putInt(EmbeddingCommandContract.KEY_REQUEST_ID, requestId);

        try {
            ensureLoaded();
            if (entries.length == 0 || dim <= 0) {
                out.putString(EmbeddingCommandContract.KEY_ERROR, "No stable embeddings available");
                return out;
            }

            float[] query = resolveQueryVector(request);
            if (query == null || query.length != dim) {
                out.putString(EmbeddingCommandContract.KEY_ERROR, "Invalid nearest-neighbor query vector");
                return out;
            }

            int topK = Math.max(1, request != null ? request.getInt(EmbeddingCommandContract.KEY_TOP_K, 10) : 10);
            Set<String> excludePaths = new HashSet<>();
            Set<String> excludeHashes = new HashSet<>();
            ArrayList<String> pathList = request != null
                    ? request.getStringArrayList(EmbeddingCommandContract.KEY_EXCLUDE_FILE_PATHS)
                    : null;
            ArrayList<String> hashList = request != null
                    ? request.getStringArrayList(EmbeddingCommandContract.KEY_EXCLUDE_CONTENT_HASHES)
                    : null;
            if (pathList != null) {
                for (String path : pathList) {
                    if (path != null) excludePaths.add(path);
                }
            }
            if (hashList != null) {
                for (String hash : hashList) {
                    if (hash != null) excludeHashes.add(hash);
                }
            }

            ArrayList<Result> best = new ArrayList<>();
            for (int i = 0; i < entries.length; i++) {
                Entry entry = entries[i];
                if (entry == null) continue;
                if (excludePaths.contains(entry.filepath) || excludeHashes.contains(entry.contentHash)) {
                    continue;
                }
                float sim = dot(query, i);
                insertBest(best, new Result(i, sim), topK);
            }

            ArrayList<Bundle> resultBundles = new ArrayList<>();
            for (Result result : best) {
                Entry entry = entries[result.index];
                Bundle item = new Bundle();
                item.putString(EmbeddingCommandContract.KEY_FILE_PATH, entry.filepath);
                item.putString(EmbeddingCommandContract.KEY_CONTENT_HASH, entry.contentHash);
                item.putString(EmbeddingCommandContract.KEY_FILENAME, entry.filename);
                item.putDouble(EmbeddingCommandContract.KEY_SIMILARITY, Math.round(result.similarity * 1000.0) / 1000.0);
                resultBundles.add(item);
            }
            out.putParcelableArrayList(EmbeddingCommandContract.KEY_RESULTS, resultBundles);
            return out;
        } catch (Exception e) {
            Log.e(TAG, "findNearest failed", e);
            out.putString(EmbeddingCommandContract.KEY_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
            return out;
        }
    }

    private void ensureLoaded() throws Exception {
        File dir = getDataDir();
        File binFile = new File(dir, STABLE_BIN);
        File metaFile = new File(dir, STABLE_META);
        if (!binFile.exists() || !metaFile.exists()) {
            entries = new Entry[0];
            vectors = new float[0];
            dim = 0;
            loadedBinModified = binFile.exists() ? binFile.lastModified() : -1L;
            loadedMetaModified = metaFile.exists() ? metaFile.lastModified() : -1L;
            return;
        }

        long binModified = binFile.lastModified();
        long metaModified = metaFile.lastModified();
        if (binModified == loadedBinModified && metaModified == loadedMetaModified && entries.length > 0) {
            return;
        }

        JSONObject meta = new JSONObject(readUtf8(metaFile));
        int nextDim = meta.optInt("dim", 0);
        JSONArray entryArray = meta.optJSONArray("entries");
        if (nextDim <= 0 || entryArray == null) {
            throw new IllegalStateException("Invalid embedding metadata");
        }

        byte[] bytes = readBytes(binFile);
        int expectedFloats = bytes.length / 4;
        if (expectedFloats < entryArray.length() * nextDim) {
            throw new IllegalStateException("Embedding binary is shorter than metadata");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] nextVectors = new float[entryArray.length() * nextDim];
        for (int i = 0; i < nextVectors.length; i++) {
            nextVectors[i] = buffer.getFloat();
        }

        Entry[] nextEntries = new Entry[entryArray.length()];
        for (int i = 0; i < entryArray.length(); i++) {
            JSONObject obj = entryArray.optJSONObject(i);
            if (obj == null) obj = new JSONObject();
            nextEntries[i] = new Entry(
                    obj.optString("filepath", ""),
                    obj.optString("contentHash", obj.optString("key", "")),
                    obj.optString("filename", "")
            );
        }

        dim = nextDim;
        entries = nextEntries;
        vectors = nextVectors;
        loadedBinModified = binModified;
        loadedMetaModified = metaModified;
        Log.i(TAG, "Loaded native similarity index: " + entries.length + " entries, " + dim + "d");
    }

    private float[] resolveQueryVector(Bundle request) {
        if (request == null) return null;
        float[] query = request.getFloatArray(EmbeddingCommandContract.KEY_QUERY_VECTOR);
        if (query != null) return query;

        String queryPath = request.getString(EmbeddingCommandContract.KEY_QUERY_FILE_PATH, "");
        String queryHash = request.getString(EmbeddingCommandContract.KEY_QUERY_CONTENT_HASH, "");
        for (int i = 0; i < entries.length; i++) {
            Entry entry = entries[i];
            if (entry == null) continue;
            boolean pathMatch = !queryPath.isEmpty() && queryPath.equals(entry.filepath);
            boolean hashMatch = !queryHash.isEmpty() && queryHash.equals(entry.contentHash);
            if (pathMatch || hashMatch) {
                float[] out = new float[dim];
                System.arraycopy(vectors, i * dim, out, 0, dim);
                return out;
            }
        }
        return null;
    }

    private float dot(float[] query, int entryIndex) {
        float sum = 0f;
        int offset = entryIndex * dim;
        for (int i = 0; i < dim; i++) {
            sum += query[i] * vectors[offset + i];
        }
        return sum;
    }

    private void insertBest(ArrayList<Result> best, Result candidate, int limit) {
        int pos = 0;
        while (pos < best.size() && best.get(pos).similarity >= candidate.similarity) {
            pos++;
        }
        if (pos < limit) {
            best.add(pos, candidate);
            if (best.size() > limit) {
                best.remove(best.size() - 1);
            }
        }
    }

    private File getDataDir() {
        File extDir = appContext.getExternalFilesDir(null);
        return extDir != null ? extDir : appContext.getFilesDir();
    }

    private static String readUtf8(File file) throws Exception {
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        FileInputStream fis = new FileInputStream(file);
        int offset = 0;
        while (offset < data.length) {
            int read = fis.read(data, offset, data.length - offset);
            if (read < 0) break;
            offset += read;
        }
        fis.close();
        return data;
    }

    private static final class Entry {
        final String filepath;
        final String contentHash;
        final String filename;

        Entry(String filepath, String contentHash, String filename) {
            this.filepath = filepath != null ? filepath : "";
            this.contentHash = contentHash != null ? contentHash : "";
            this.filename = filename != null ? filename : "";
        }
    }

    private static final class Result {
        final int index;
        final float similarity;

        Result(int index, float similarity) {
            this.index = index;
            this.similarity = similarity;
        }
    }
}
