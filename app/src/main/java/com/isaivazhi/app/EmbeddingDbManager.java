package com.isaivazhi.app;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-process facade over EmbeddingDb that
 *   (a) runs every database call on a dedicated HandlerThread so the JS thread
 *       (and the Capacitor plugin-executor thread) never blocks on disk I/O,
 *   (b) performs a one-time migration from the legacy local_embeddings.bin +
 *       local_embeddings_meta.json files into the SQLite store, and
 *   (c) writes a periodic local_embeddings.json mirror for Colab interchange.
 *
 * Every public method returns immediately; results land in the supplied
 * Callback on the executor's thread. Callers convert back to JS callbacks via
 * the plugin layer.
 */
public final class EmbeddingDbManager {

    private static final String TAG = "EmbeddingDbManager";
    private static final String LEGACY_BIN = "local_embeddings.bin";
    private static final String LEGACY_META = "local_embeddings_meta.json";
    private static final String LEGACY_JSON = "local_embeddings.json";
    /** Single-file portable store (IVZ1): vectors + embedded metadata. */
    public static final String EMBEDDINGS_BIN = "isaivazhi_embeddings.bin";
    private static final byte[] IVZ_MAGIC_BYTES = new byte[]{'I', 'V', 'Z', '1'};
    private static final int IVZ_VERSION = 1;
    private static final int IVZ_HEADER_SIZE = 20;
    // Read-optimized binary snapshot of the SQLite store. Written atomically
    // by loadAllSnapshot so JS can pull vectors via convertFileSrc + fetch
    // (~85 ms for 5 MB) instead of a multi-MB base64 string over the JSI
    // bridge (~8–13 s observed). The snapshot file is regenerated on every
    // loadAllSnapshot call; an embDbExportBinSnapshot plugin method is also
    // exposed for callers that want to force a refresh.
    private static final String SNAPSHOT_BIN = "embeddings_snapshot.bin";

    private static volatile EmbeddingDbManager sInstance;

    public static EmbeddingDbManager get(Context appContext) {
        EmbeddingDbManager local = sInstance;
        if (local == null) {
            synchronized (EmbeddingDbManager.class) {
                local = sInstance;
                if (local == null) {
                    local = new EmbeddingDbManager(appContext.getApplicationContext());
                    sInstance = local;
                }
            }
        }
        return local;
    }

    private final Context appContext;
    private final EmbeddingDb db;
    private final HandlerThread workerThread;
    private final Handler worker;
    private final AtomicBoolean migrationChecked = new AtomicBoolean(false);

    private EmbeddingDbManager(Context appContext) {
        this.appContext = appContext;
        this.db = EmbeddingDb.get(appContext);
        this.workerThread = new HandlerThread("EmbeddingDbWorker", Thread.NORM_PRIORITY - 1);
        this.workerThread.start();
        this.worker = new Handler(workerThread.getLooper());
    }

    public interface Callback<T> {
        void onResult(T result, Throwable error);
    }

    private <T> void run(Op<T> op, Callback<T> cb) {
        worker.post(() -> {
            T result = null;
            Throwable error = null;
            try {
                result = op.execute();
            } catch (Throwable t) {
                error = t;
                Log.e(TAG, "Db op failed", t);
            }
            if (cb != null) cb.onResult(result, error);
        });
    }

    private interface Op<T> {
        T execute() throws Exception;
    }

    private File getDataDir() {
        File extDir = appContext.getExternalFilesDir(null);
        return extDir != null ? extDir : appContext.getFilesDir();
    }

    // --- Migration ---

    /**
     * Idempotent migration from local_embeddings.bin + local_embeddings_meta.json
     * into the SQLite store. Runs at most once per process; subsequent calls
     * resolve to {migrated:false, reason:"already_checked"} after the first
     * pass returns.
     */
    public void migrateFromLegacyIfNeeded(Callback<JSONObject> cb) {
        run(() -> {
            JSONObject result = new JSONObject();
            int existingCount = db.count();
            if (existingCount > 0) {
                migrationChecked.set(true);
                result.put("migrated", false);
                result.put("reason", "db_already_populated");
                result.put("rowCount", existingCount);
                return result;
            }
            if (migrationChecked.get()) {
                result.put("migrated", false);
                result.put("reason", "already_checked_this_process");
                return result;
            }
            migrationChecked.set(true);

            File dir = getDataDir();
            File ivzFile = new File(dir, EMBEDDINGS_BIN);
            File binFile = new File(dir, LEGACY_BIN);
            File metaFile = new File(dir, LEGACY_META);
            File jsonFile = new File(dir, LEGACY_JSON);

            if (ivzFile.exists() && isIvzFile(ivzFile)) {
                int inserted = ingestIvzFile(ivzFile);
                result.put("migrated", true);
                result.put("source", "ivz");
                result.put("rowCount", inserted);
                return result;
            }
            if (binFile.exists() && metaFile.exists()) {
                int inserted = ingestBinaryStore(binFile, metaFile);
                result.put("migrated", true);
                result.put("source", "binary");
                result.put("rowCount", inserted);
                return result;
            }
            if (jsonFile.exists()) {
                int inserted = ingestLegacyJson(jsonFile);
                result.put("migrated", true);
                result.put("source", "legacy_json");
                result.put("rowCount", inserted);
                return result;
            }
            result.put("migrated", false);
            result.put("reason", "no_legacy_files");
            result.put("rowCount", 0);
            return result;
        }, cb);
    }

    private int ingestBinaryStore(File binFile, File metaFile) throws Exception {
        String metaText = readUtf8(metaFile);
        JSONObject meta = new JSONObject(metaText);
        int dim = meta.optInt("dim", 0);
        JSONArray entryArr = meta.optJSONArray("entries");
        if (dim <= 0 || entryArr == null) return 0;

        byte[] raw = readBytes(binFile);
        if (raw.length < entryArr.length() * dim * 4) {
            throw new IllegalStateException("binary store shorter than meta declares");
        }

        List<EmbeddingEntity> entities = new ArrayList<>(entryArr.length());
        for (int i = 0; i < entryArr.length(); i++) {
            JSONObject e = entryArr.optJSONObject(i);
            if (e == null) continue;
            EmbeddingEntity ent = new EmbeddingEntity();
            ent.contentHash = e.optString("key", e.optString("contentHash", ""));
            if (ent.contentHash.isEmpty()) continue;
            ent.filepath = e.optString("filepath", "");
            ent.filename = e.optString("filename", "");
            ent.artist = e.optString("artist", "");
            ent.album = e.optString("album", "");
            ent.timestamp = e.optLong("timestamp", 0L);
            ent.dim = dim;
            ent.vec = sliceVecBytes(raw, i, dim);
            entities.add(ent);
        }

        List<EmbeddingPathIndexEntity> paths = new ArrayList<>();
        JSONObject pathIndex = meta.optJSONObject("pathIndex");
        if (pathIndex != null) {
            Iterator<String> keys = pathIndex.keys();
            while (keys.hasNext()) {
                String fp = keys.next();
                String hash = pathIndex.optString(fp, "");
                if (fp.isEmpty() || hash.isEmpty()) continue;
                EmbeddingPathIndexEntity row = new EmbeddingPathIndexEntity();
                row.filepath = fp;
                row.contentHash = hash;
                paths.add(row);
            }
        }

        db.replaceAll(entities, paths);
        Log.i(TAG, "Migrated " + entities.size() + " embeddings from binary store");
        return entities.size();
    }

    private int ingestLegacyJson(File jsonFile) throws Exception {
        JSONObject root = new JSONObject(readUtf8(jsonFile));
        List<EmbeddingEntity> entities = new ArrayList<>();
        List<EmbeddingPathIndexEntity> paths = new ArrayList<>();
        int dim = 0;

        JSONObject pathIndex = root.optJSONObject("_path_index");
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("_path_index".equals(key)) continue;
            JSONObject row = root.optJSONObject(key);
            if (row == null) continue;
            JSONArray vecArr = row.optJSONArray("embedding");
            if (vecArr == null || vecArr.length() == 0) continue;
            if (dim == 0) dim = vecArr.length();
            else if (dim != vecArr.length()) continue;

            EmbeddingEntity ent = new EmbeddingEntity();
            ent.contentHash = row.optString("contentHash", row.optString("content_hash", key));
            ent.filepath = row.optString("filepath", "");
            ent.filename = row.optString("filename", "");
            ent.artist = row.optString("artist", "");
            ent.album = row.optString("album", "");
            ent.timestamp = row.optLong("timestamp", 0L);
            ent.dim = dim;
            ent.vec = floatArrayToBytes(vecArr);
            entities.add(ent);
        }

        if (pathIndex != null) {
            Iterator<String> pks = pathIndex.keys();
            while (pks.hasNext()) {
                String fp = pks.next();
                String hash = pathIndex.optString(fp, "");
                if (fp.isEmpty() || hash.isEmpty()) continue;
                EmbeddingPathIndexEntity p = new EmbeddingPathIndexEntity();
                p.filepath = fp;
                p.contentHash = hash;
                paths.add(p);
            }
        }

        db.replaceAll(entities, paths);
        Log.i(TAG, "Migrated " + entities.size() + " embeddings from legacy JSON");
        return entities.size();
    }

    private static boolean isIvzFile(File f) throws Exception {
        byte[] head = new byte[4];
        FileInputStream fis = new FileInputStream(f);
        try {
            int n = fis.read(head);
            if (n < 4) return false;
        } finally {
            fis.close();
        }
        for (int i = 0; i < 4; i++) {
            if (head[i] != IVZ_MAGIC_BYTES[i]) return false;
        }
        return true;
    }

    private int ingestIvzFile(File ivzFile) throws Exception {
        byte[] data = readBytes(ivzFile);
        if (data.length < IVZ_HEADER_SIZE) {
            throw new IllegalStateException("IVZ file too short");
        }
        ByteBuffer hdr = ByteBuffer.wrap(data, 0, IVZ_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        hdr.get(magic);
        for (int i = 0; i < 4; i++) {
            if (magic[i] != IVZ_MAGIC_BYTES[i]) {
                throw new IllegalStateException("bad IVZ magic");
            }
        }
        int version = hdr.getInt();
        if (version != IVZ_VERSION) {
            throw new IllegalStateException("unsupported IVZ version " + version);
        }
        int dim = hdr.getInt();
        int rowCount = hdr.getInt();
        int metaLen = hdr.getInt();
        if (dim <= 0 || rowCount < 0 || metaLen < 0) {
            throw new IllegalStateException("invalid IVZ header");
        }
        int metaStart = IVZ_HEADER_SIZE;
        int metaEnd = metaStart + metaLen;
        int vecStart = metaEnd;
        int vecBytes = rowCount * dim * 4;
        if (data.length < vecStart + vecBytes) {
            throw new IllegalStateException("IVZ vector blob truncated");
        }
        String metaText = new String(data, metaStart, metaLen, StandardCharsets.UTF_8);
        JSONObject meta = new JSONObject(metaText);
        JSONArray entryArr = meta.optJSONArray("entries");
        if (entryArr == null) return 0;

        byte[] raw = new byte[vecBytes];
        System.arraycopy(data, vecStart, raw, 0, vecBytes);

        List<EmbeddingEntity> entities = new ArrayList<>(entryArr.length());
        for (int i = 0; i < entryArr.length(); i++) {
            JSONObject e = entryArr.optJSONObject(i);
            if (e == null) continue;
            EmbeddingEntity ent = new EmbeddingEntity();
            ent.contentHash = e.optString("contentHash", e.optString("key", ""));
            if (ent.contentHash.isEmpty()) continue;
            ent.filepath = e.optString("filepath", "");
            ent.filename = e.optString("filename", "");
            ent.artist = e.optString("artist", "");
            ent.album = e.optString("album", "");
            ent.timestamp = e.optLong("timestamp", 0L);
            ent.dim = dim;
            ent.vec = sliceVecBytes(raw, i, dim);
            entities.add(ent);
        }

        List<EmbeddingPathIndexEntity> paths = new ArrayList<>();
        JSONObject pathIndex = meta.optJSONObject("pathIndex");
        if (pathIndex != null) {
            Iterator<String> keys = pathIndex.keys();
            while (keys.hasNext()) {
                String fp = keys.next();
                String hash = pathIndex.optString(fp, "");
                if (fp.isEmpty() || hash.isEmpty()) continue;
                EmbeddingPathIndexEntity row = new EmbeddingPathIndexEntity();
                row.filepath = fp;
                row.contentHash = hash;
                paths.add(row);
            }
        }

        db.replaceAll(entities, paths);
        int splitCount = meta.optInt("splitCount", 3);
        if (splitCount != 3 && splitCount != 5 && splitCount != 7) {
            splitCount = 3;
        }
        Log.i(TAG, "Migrated " + entities.size() + " embeddings from IVZ file (splitCount=" + splitCount + ")");
        lastIngestSplitCount = splitCount;
        return entities.size();
    }

    /** Split count from the most recent IVZ ingest; 3 if unknown. */
    private volatile int lastIngestSplitCount = 3;

    public int getLastIngestSplitCount() {
        return lastIngestSplitCount;
    }

    /**
     * Writes SQLite contents to isaivazhi_embeddings.bin (IVZ1). Read-only
     * from the DB's perspective; does not touch heap caches.
     */
    public void exportEmbeddingsBin(int splitCount, Callback<JSONObject> cb) {
        run(() -> {
            File dir = getDataDir();
            File dst = new File(dir, EMBEDDINGS_BIN);
            return writeIvzFile(dst, splitCount);
        }, cb);
    }

    private JSONObject writeIvzFile(File dst, int splitCount) throws Exception {
        splitCount = EmbeddingWindowConfig.normalizeSplitCount(splitCount);
        List<EmbeddingEntity> rows = db.getAll();
        int dim = 0;
        for (EmbeddingEntity r : rows) {
            if (r.dim > 0) {
                dim = r.dim;
                break;
            }
        }
        JSONArray entries = new JSONArray();
        int rowBytes = dim * 4;
        byte[] concat = new byte[rows.size() * rowBytes];
        int wrote = 0;
        for (EmbeddingEntity r : rows) {
            if (r.vec == null || r.dim != dim || dim <= 0) continue;
            System.arraycopy(r.vec, 0, concat, wrote * rowBytes, rowBytes);
            JSONObject e = new JSONObject();
            e.put("contentHash", r.contentHash);
            e.put("filepath", r.filepath);
            e.put("filename", r.filename);
            e.put("artist", r.artist);
            e.put("album", r.album);
            e.put("timestamp", r.timestamp);
            entries.put(e);
            wrote++;
        }
        int writtenBytes = wrote * rowBytes;
        byte[] finalBlob;
        if (writtenBytes == concat.length) {
            finalBlob = concat;
        } else {
            finalBlob = new byte[writtenBytes];
            System.arraycopy(concat, 0, finalBlob, 0, writtenBytes);
        }

        JSONObject pathIndex = new JSONObject();
        for (EmbeddingPathIndexEntity p : db.getAllPaths()) {
            pathIndex.put(p.filepath, p.contentHash);
        }
        JSONObject meta = new JSONObject();
        meta.put("entries", entries);
        meta.put("pathIndex", pathIndex);
        meta.put("splitCount", splitCount);
        byte[] metaBytes = meta.toString().getBytes(StandardCharsets.UTF_8);

        ByteBuffer hdr = ByteBuffer.allocate(IVZ_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put(IVZ_MAGIC_BYTES);
        hdr.putInt(IVZ_VERSION);
        hdr.putInt(dim);
        hdr.putInt(wrote);
        hdr.putInt(metaBytes.length);

        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(hdr.array());
            fos.write(metaBytes);
            fos.write(finalBlob);
            fos.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) {
            throw new IOException("rename failed for IVZ export");
        }

        JSONObject out = new JSONObject();
        out.put("rowCount", wrote);
        out.put("bytes", dst.length());
        out.put("path", dst.getAbsolutePath());
        out.put("splitCount", splitCount);
        Log.i(TAG, "Exported IVZ " + wrote + " rows, " + dst.length() + " bytes, splitCount=" + splitCount);
        return out;
    }

    /**
     * Re-ingest portable backup (IVZ .bin preferred, legacy JSON fallback).
     */
    public void forceReimportEmbeddings(Callback<JSONObject> cb) {
        run(() -> {
            JSONObject result = new JSONObject();
            File dir = getDataDir();
            File ivzFile = new File(dir, EMBEDDINGS_BIN);
            File jsonFile = new File(dir, LEGACY_JSON);

            if (ivzFile.exists() && isIvzFile(ivzFile)) {
                int inserted = ingestIvzFile(ivzFile);
                result.put("reimported", true);
                result.put("source", "ivz");
                result.put("rowCount", inserted);
                result.put("splitCount", lastIngestSplitCount);
                return result;
            }
            if (jsonFile.exists()) {
                Log.w(TAG, "forceReimport: using legacy JSON (slow); convert to .bin with json_to_ivz.py");
                int inserted = ingestLegacyJson(jsonFile);
                result.put("reimported", true);
                result.put("source", "legacy_json");
                result.put("rowCount", inserted);
                result.put("splitCount", lastIngestSplitCount);
                return result;
            }
            result.put("reimported", false);
            result.put("reason", "no_portable_file");
            result.put("rowCount", db.count());
            return result;
        }, cb);
    }

    private static byte[] sliceVecBytes(byte[] raw, int rowIndex, int dim) {
        int byteOffset = rowIndex * dim * 4;
        byte[] out = new byte[dim * 4];
        System.arraycopy(raw, byteOffset, out, 0, dim * 4);
        return out;
    }

    private static byte[] floatArrayToBytes(JSONArray arr) {
        ByteBuffer bb = ByteBuffer.allocate(arr.length() * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < arr.length(); i++) {
            bb.putFloat((float) arr.optDouble(i, 0.0));
        }
        return bb.array();
    }

    // --- Mutation ---

    /**
     * Insert or update one embedding row. `vecBase64` is the little-endian
     * Float32 byte payload, base64-encoded for the JS bridge.
     */
    public void upsertOne(
            String contentHash, String filepath, String filename,
            String artist, String album, long timestamp, int dim, String vecBase64,
            Callback<Integer> cb
    ) {
        run(() -> {
            EmbeddingEntity ent = new EmbeddingEntity();
            ent.contentHash = contentHash != null ? contentHash : "";
            if (ent.contentHash.isEmpty()) throw new IllegalArgumentException("contentHash required");
            ent.filepath = filepath != null ? filepath : "";
            ent.filename = filename != null ? filename : "";
            ent.artist = artist != null ? artist : "";
            ent.album = album != null ? album : "";
            ent.timestamp = timestamp;
            ent.dim = dim;
            ent.vec = vecBase64 != null && !vecBase64.isEmpty() ? Base64.decode(vecBase64, Base64.NO_WRAP) : new byte[0];
            if (ent.vec.length != dim * 4) {
                throw new IllegalArgumentException("vec byte length " + ent.vec.length + " != dim*4 (" + dim * 4 + ")");
            }
            EmbeddingPathIndexEntity p = null;
            if (!ent.filepath.isEmpty()) {
                p = new EmbeddingPathIndexEntity();
                p.filepath = ent.filepath;
                p.contentHash = ent.contentHash;
            }
            db.upsert(ent);
            if (p != null) db.upsertPath(p);
            return 1;
        }, cb);
    }

    /**
     * Insert or update many rows in one transaction.
     *
     * `entries` is a JSONArray of objects:
     *   { contentHash, filepath, filename, artist, album, timestamp, dim, vecBase64 }
     */
    public void upsertBatch(JSONArray entries, Callback<Integer> cb) {
        run(() -> {
            int n = entries.length();
            List<EmbeddingEntity> ents = new ArrayList<>(n);
            List<EmbeddingPathIndexEntity> paths = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                JSONObject row = entries.optJSONObject(i);
                if (row == null) continue;
                EmbeddingEntity ent = new EmbeddingEntity();
                ent.contentHash = row.optString("contentHash", "");
                if (ent.contentHash.isEmpty()) continue;
                ent.filepath = row.optString("filepath", "");
                ent.filename = row.optString("filename", "");
                ent.artist = row.optString("artist", "");
                ent.album = row.optString("album", "");
                ent.timestamp = row.optLong("timestamp", 0L);
                ent.dim = row.optInt("dim", 0);
                String b64 = row.optString("vecBase64", "");
                ent.vec = b64.isEmpty() ? new byte[0] : Base64.decode(b64, Base64.NO_WRAP);
                if (ent.dim <= 0 || ent.vec.length != ent.dim * 4) continue;
                ents.add(ent);
                if (!ent.filepath.isEmpty()) {
                    EmbeddingPathIndexEntity p = new EmbeddingPathIndexEntity();
                    p.filepath = ent.filepath;
                    p.contentHash = ent.contentHash;
                    paths.add(p);
                }
            }
            if (!ents.isEmpty()) db.upsertAll(ents);
            if (!paths.isEmpty()) db.upsertPaths(paths);
            return ents.size();
        }, cb);
    }

    public void deleteByHashes(JSONArray hashes, Callback<Integer> cb) {
        run(() -> {
            List<String> list = jsonArrayToStringList(hashes);
            if (list.isEmpty()) return 0;
            // db.deleteByHashes removes both the embedding row and its path-index entry
            // in one transaction, so no separate path mop-up call is needed.
            return db.deleteByHashes(list);
        }, cb);
    }

    public void deleteByFilepaths(JSONArray filepaths, Callback<Integer> cb) {
        run(() -> {
            List<String> list = jsonArrayToStringList(filepaths);
            if (list.isEmpty()) return 0;
            // db.deleteByFilepaths handles both the embedding rows and the path-index
            // entries in one transaction.
            return db.deleteByFilepaths(list);
        }, cb);
    }

    public void deleteByFilenames(JSONArray filenames, Callback<Integer> cb) {
        run(() -> {
            List<String> list = jsonArrayToStringList(filenames);
            if (list.isEmpty()) return 0;
            return db.deleteByFilenames(list);
        }, cb);
    }

    /**
     * Push #50: list duplicate-filename rows for the AI page's Duplicates
     * section. Returns { rows: [ { contentHash, filepath, filename, artist,
     * album, timestamp, dim } ... ] }, ordered by filename then newest first.
     */
    public void getDuplicateRows(Callback<JSONObject> cb) {
        run(() -> {
            JSONArray arr = new JSONArray();
            for (EmbeddingDb.DuplicateRow d : db.getDuplicateRows()) {
                JSONObject o = new JSONObject();
                o.put("contentHash", d.contentHash != null ? d.contentHash : "");
                o.put("filepath", d.filepath != null ? d.filepath : "");
                o.put("filename", d.filename != null ? d.filename : "");
                o.put("artist", d.artist != null ? d.artist : "");
                o.put("album", d.album != null ? d.album : "");
                o.put("timestamp", d.timestamp);
                o.put("dim", d.dim);
                arr.put(o);
            }
            JSONObject out = new JSONObject();
            out.put("rows", arr);
            return out;
        }, cb);
    }

    // Push #51: dedupeByFilename SQL was removed — the Duplicates section now
    // computes a per-hash kill list in the UI (so policy can be "keep newest"
    // for present files and "remove all" for missing files) and routes
    // through the existing deleteByHashes path.

    // --- Read ---

    /**
     * Snapshot of the entire embeddings table, suitable for JS to rebuild its
     * in-memory `localEmbeddings` map at startup.
     *
     * Returns:
     *   { dim, count,
     *     entries: [{contentHash, filepath, filename, artist, album, timestamp}],
     *     pathIndex: {...},
     *     snapshotBinPath: absolute file path,
     *     snapshotBinBytes: size,
     *     // ONLY when the file write failed and we fell back to inline:
     *     vecBase64: concatenated Float32 LE bytes as base64
     *   }
     *
     * The file path lets JS read the 5 MB vector blob via
     * `convertFileSrc + fetch` (~85 ms wall-clock) instead of pushing the
     * same data through the JSI bridge as base64 (~8–13 s observed on the
     * 23:17 capture). Inline base64 stays as a fallback for the rare case
     * where the file write fails (read-only volume, no space, etc.).
     */
    public void loadAllSnapshot(Callback<JSONObject> cb) {
        run(() -> {
            List<EmbeddingEntity> rows = db.getAll();
            JSONObject out = new JSONObject();
            out.put("count", rows.size());
            int dim = 0;
            for (EmbeddingEntity r : rows) {
                if (r.dim > 0) { dim = r.dim; break; }
            }
            out.put("dim", dim);

            if (rows.isEmpty() || dim <= 0) {
                out.put("entries", new JSONArray());
                out.put("snapshotBinPath", "");
                out.put("snapshotBinBytes", 0);
                out.put("pathIndex", new JSONObject());
                return out;
            }

            // Build concatenated vec blob.
            int rowBytes = dim * 4;
            byte[] concat = new byte[rows.size() * rowBytes];
            JSONArray entries = new JSONArray();
            int wrote = 0;
            for (int i = 0; i < rows.size(); i++) {
                EmbeddingEntity r = rows.get(i);
                if (r.vec == null || r.vec.length != rowBytes) {
                    // Mixed-dim or corrupt row — skip vec, also skip entry to
                    // keep blob and entries[] index-aligned for the JS slicer.
                    continue;
                }
                System.arraycopy(r.vec, 0, concat, wrote * rowBytes, rowBytes);
                JSONObject e = new JSONObject();
                e.put("contentHash", r.contentHash);
                e.put("filepath", r.filepath);
                e.put("filename", r.filename);
                e.put("artist", r.artist);
                e.put("album", r.album);
                e.put("timestamp", r.timestamp);
                entries.put(e);
                wrote++;
            }
            out.put("entries", entries);

            JSONObject pathIndex = new JSONObject();
            for (EmbeddingPathIndexEntity p : db.getAllPaths()) {
                pathIndex.put(p.filepath, p.contentHash);
            }
            out.put("pathIndex", pathIndex);

            // Trim concat to the actual byte count actually written so a
            // mid-store dim mismatch doesn't trailing-zero the bin file.
            int written = wrote * rowBytes;
            byte[] finalBlob;
            if (written == concat.length) {
                finalBlob = concat;
            } else {
                finalBlob = new byte[written];
                System.arraycopy(concat, 0, finalBlob, 0, written);
            }

            // Atomic write: tmp + rename. JS readers see either the prior
            // valid file or the fresh one — never a half-written buffer.
            File dir = getDataDir();
            File tmpBin = new File(dir, SNAPSHOT_BIN + ".tmp");
            File finalBin = new File(dir, SNAPSHOT_BIN);
            try {
                FileOutputStream fos = new FileOutputStream(tmpBin);
                try {
                    fos.write(finalBlob);
                    fos.flush();
                    fos.getFD().sync();
                } finally {
                    fos.close();
                }
                if (finalBin.exists()) {
                    // delete first because rename-replace is not atomic on
                    // every Android filesystem flavor (e.g., sdcardfs).
                    finalBin.delete();
                }
                if (!tmpBin.renameTo(finalBin)) {
                    throw new IOException("rename " + tmpBin + " -> " + finalBin + " failed");
                }
                out.put("snapshotBinPath", finalBin.getAbsolutePath());
                out.put("snapshotBinBytes", finalBin.length());
            } catch (Exception writeErr) {
                Log.w(TAG, "snapshot.bin write failed, falling back to inline base64: " + writeErr.getMessage());
                out.put("snapshotBinPath", "");
                out.put("snapshotBinBytes", 0);
                out.put("vecBase64", Base64.encodeToString(finalBlob, Base64.NO_WRAP));
            }
            return out;
        }, cb);
    }

    /**
     * Explicit refresh hook. Same work as loadAllSnapshot but discards the
     * entries metadata — useful for callers (e.g., a future appPaused hook)
     * that want to keep the snapshot.bin warm on disk without paying the
     * JSON wire cost for the entries array.
     */
    public void exportBinSnapshot(Callback<JSONObject> cb) {
        run(() -> {
            List<EmbeddingEntity> rows = db.getAll();
            int dim = 0;
            for (EmbeddingEntity r : rows) {
                if (r.dim > 0) { dim = r.dim; break; }
            }
            JSONObject out = new JSONObject();
            out.put("count", rows.size());
            out.put("dim", dim);
            if (rows.isEmpty() || dim <= 0) {
                out.put("snapshotBinPath", "");
                out.put("snapshotBinBytes", 0);
                return out;
            }
            int rowBytes = dim * 4;
            byte[] concat = new byte[rows.size() * rowBytes];
            int wrote = 0;
            for (int i = 0; i < rows.size(); i++) {
                EmbeddingEntity r = rows.get(i);
                if (r.vec == null || r.vec.length != rowBytes) continue;
                System.arraycopy(r.vec, 0, concat, wrote * rowBytes, rowBytes);
                wrote++;
            }
            int written = wrote * rowBytes;
            byte[] finalBlob = (written == concat.length) ? concat : java.util.Arrays.copyOf(concat, written);
            File dir = getDataDir();
            File tmpBin = new File(dir, SNAPSHOT_BIN + ".tmp");
            File finalBin = new File(dir, SNAPSHOT_BIN);
            FileOutputStream fos = new FileOutputStream(tmpBin);
            try {
                fos.write(finalBlob);
                fos.flush();
                fos.getFD().sync();
            } finally { fos.close(); }
            if (finalBin.exists()) finalBin.delete();
            if (!tmpBin.renameTo(finalBin)) {
                throw new IOException("rename failed");
            }
            out.put("snapshotBinPath", finalBin.getAbsolutePath());
            out.put("snapshotBinBytes", finalBin.length());
            return out;
        }, cb);
    }

    /**
     * Top-k nearest neighbors of the supplied query vector. Uses
     * sqlite-vec's vec_distance_cosine when the extension is loaded; falls
     * back to NativeAccelerator (NEON SIMD) over an in-memory snapshot
     * otherwise. Runs on the worker thread — JS is not blocked on the scan.
     */
    public void nearestNeighbors(String queryVecBase64, int k,
                                 List<String> excludeHashes,
                                 Callback<JSONObject> cb) {
        run(() -> {
            JSONObject out = new JSONObject();
            byte[] queryBytes = (queryVecBase64 != null && !queryVecBase64.isEmpty())
                    ? Base64.decode(queryVecBase64, Base64.NO_WRAP)
                    : new byte[0];
            java.util.Set<String> excludeSet = new java.util.HashSet<>();
            if (excludeHashes != null) {
                for (String h : excludeHashes) if (h != null && !h.isEmpty()) excludeSet.add(h);
            }
            List<EmbeddingDb.NnResult> rows = db.nearestNeighbors(queryBytes, k, excludeSet);
            JSONArray arr = new JSONArray();
            for (EmbeddingDb.NnResult r : rows) {
                JSONObject row = new JSONObject();
                row.put("contentHash", r.contentHash);
                row.put("filepath", r.filepath);
                row.put("filename", r.filename);
                // Round to 4 decimal places for log readability; full precision
                // is rarely needed for ranking decisions in JS-land.
                row.put("similarity", Math.round(r.similarity * 10000.0) / 10000.0);
                arr.put(row);
            }
            out.put("results", arr);
            out.put("usedExtension", db.isVecExtensionLoaded());
            out.put("count", rows.size());
            return out;
        }, cb);
    }

    /**
     * Ingest any pending_embeddings.json the EmbeddingService wrote since the
     * last call. Returns rowCount + clears the pending file on success.
     *
     * Called from EmbeddingEngine after MSG_COMPLETE so newly-embedded songs
     * land in SQLite as soon as a batch finishes. Race-safe: the embedding
     * service writes pending_embeddings.json atomically; this reader copes
     * with the file being mid-write by returning a zero row count and
     * leaving the file in place for the next attempt.
     */
    public void recoverPendingIfAny(Callback<JSONObject> cb) {
        run(() -> {
            JSONObject out = new JSONObject();
            File dir = getDataDir();
            File pendingFile = new File(dir, "pending_embeddings.json");
            if (!pendingFile.exists() || pendingFile.length() == 0) {
                out.put("recovered", 0);
                out.put("reason", "no_pending_file");
                return out;
            }
            try {
                JSONObject root = new JSONObject(readUtf8(pendingFile));
                java.util.List<EmbeddingEntity> entities = new java.util.ArrayList<>();
                java.util.List<EmbeddingPathIndexEntity> paths = new java.util.ArrayList<>();
                int dim = 0;
                JSONObject pathIndex = root.optJSONObject("_path_index");
                java.util.Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if ("_path_index".equals(key)) continue;
                    JSONObject row = root.optJSONObject(key);
                    if (row == null) continue;
                    JSONArray vecArr = row.optJSONArray("embedding");
                    if (vecArr == null || vecArr.length() == 0) continue;
                    if (dim == 0) dim = vecArr.length();
                    else if (dim != vecArr.length()) continue;
                    EmbeddingEntity ent = new EmbeddingEntity();
                    ent.contentHash = row.optString("contentHash", row.optString("content_hash", key));
                    if (ent.contentHash.isEmpty()) continue;
                    ent.filepath = row.optString("filepath", "");
                    ent.filename = row.optString("filename", "");
                    ent.artist = row.optString("artist", "");
                    ent.album = row.optString("album", "");
                    ent.timestamp = row.optLong("timestamp", System.currentTimeMillis());
                    ent.dim = dim;
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(dim * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < dim; i++) bb.putFloat((float) vecArr.optDouble(i, 0.0));
                    ent.vec = bb.array();
                    entities.add(ent);
                    if (!ent.filepath.isEmpty()) {
                        EmbeddingPathIndexEntity p = new EmbeddingPathIndexEntity();
                        p.filepath = ent.filepath;
                        p.contentHash = ent.contentHash;
                        paths.add(p);
                    }
                }
                if (pathIndex != null) {
                    java.util.Iterator<String> pks = pathIndex.keys();
                    while (pks.hasNext()) {
                        String fp = pks.next();
                        String hash = pathIndex.optString(fp, "");
                        if (fp.isEmpty() || hash.isEmpty()) continue;
                        EmbeddingPathIndexEntity p = new EmbeddingPathIndexEntity();
                        p.filepath = fp;
                        p.contentHash = hash;
                        paths.add(p);
                    }
                }
                if (!entities.isEmpty()) db.upsertAll(entities);
                if (!paths.isEmpty()) db.upsertPaths(paths);
                // Clear the pending file by truncating to an empty marker
                // (preserves the file so the embedding service knows it was
                // consumed; same convention as the Capacitor app).
                java.io.FileOutputStream fos = new java.io.FileOutputStream(pendingFile);
                try { fos.write("{\"_path_index\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                finally { fos.close(); }
                out.put("recovered", entities.size());
                out.put("totalRows", db.count());
                Log.i(TAG, "Recovered " + entities.size() + " pending embeddings into SQLite");
                return out;
            } catch (Exception e) {
                Log.w(TAG, "recoverPendingIfAny failed: " + e.getMessage());
                out.put("recovered", 0);
                out.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                return out;
            }
        }, cb);
    }

    /**
     * Top-k nearest neighbors of the song identified by `queryFilename`.
     * Reads the vector from SQLite on the worker thread and uses the same
     * sqlite-vec / NativeAccelerator path as the explicit-vector variant.
     */
    public void nearestNeighborsForFilename(String queryFilename, int k,
                                            List<String> excludeHashes,
                                            Callback<JSONObject> cb) {
        run(() -> {
            JSONObject out = new JSONObject();
            byte[] queryBytes = db.getVecByFilename(queryFilename);
            if (queryBytes == null || queryBytes.length == 0) {
                out.put("results", new JSONArray());
                out.put("count", 0);
                out.put("usedExtension", db.isVecExtensionLoaded());
                out.put("error", "query_song_not_embedded");
                return out;
            }
            java.util.Set<String> excludeSet = new java.util.HashSet<>();
            if (excludeHashes != null) {
                for (String h : excludeHashes) if (h != null && !h.isEmpty()) excludeSet.add(h);
            }
            List<EmbeddingDb.NnResult> rows = db.nearestNeighbors(queryBytes, k, excludeSet);
            JSONArray arr = new JSONArray();
            for (EmbeddingDb.NnResult r : rows) {
                JSONObject row = new JSONObject();
                row.put("contentHash", r.contentHash);
                row.put("filepath", r.filepath);
                row.put("filename", r.filename);
                row.put("similarity", Math.round(r.similarity * 10000.0) / 10000.0);
                arr.put(row);
            }
            out.put("results", arr);
            out.put("usedExtension", db.isVecExtensionLoaded());
            out.put("count", rows.size());
            return out;
        }, cb);
    }

    /**
     * Lightweight: returns filenames in the DB as a JSON array. The AI
     * management page intersects this with the library to compute "pending".
     */
    public void getAllFilenames(Callback<JSONObject> cb) {
        run(() -> {
            JSONObject out = new JSONObject();
            JSONArray arr = new JSONArray();
            for (String fn : db.getAllFilenames()) arr.put(fn);
            out.put("filenames", arr);
            out.put("count", arr.length());
            return out;
        }, cb);
    }

    /**
     * Push #56 diagnostic: for the supplied filename, returns the DB-side
     * filepath strings stored in T_EMB and T_PATH. Used by MainActivity to
     * log a side-by-side comparison against MediaStore.DATA on first AI-page
     * open so we can spot prefix/encoding mismatches that keep songs stuck
     * in Pending despite being correctly embedded.
     */
    public void diagnoseByFilename(String filename, Callback<JSONObject> cb) {
        run(() -> {
            JSONObject out = new JSONObject();
            JSONArray arr = new JSONArray();
            for (String line : db.diagnoseByFilename(filename)) arr.put(line);
            out.put("lines", arr);
            out.put("count", arr.length());
            return out;
        }, cb);
    }

    /**
     * Push #61: list "audio duplicate" groups — content_hashes that have
     * more than one filepath in T_PATH. Each group includes the truncated
     * hash + the list of filepaths sharing it.
     */
    public void getAudioDuplicateGroups(Callback<JSONObject> cb) {
        run(() -> {
            JSONObject out = new JSONObject();
            JSONArray arr = new JSONArray();
            for (EmbeddingDb.AudioDupGroup g : db.getAudioDuplicateGroups()) {
                JSONObject o = new JSONObject();
                o.put("contentHash", g.contentHash != null ? g.contentHash : "");
                JSONArray fps = new JSONArray();
                if (g.filepaths != null) for (String fp : g.filepaths) fps.put(fp);
                o.put("filepaths", fps);
                arr.put(o);
            }
            out.put("groups", arr);
            out.put("groupCount", arr.length());
            return out;
        }, cb);
    }

    /** Push #61: remove T_PATH entry for a single filepath. */
    public void removePathIndexEntry(String filepath, Callback<Integer> cb) {
        run(() -> db.removePathIndexEntry(filepath), cb);
    }

    /** Push #53: filepaths of every embedded row (excluding empty). */
    public void getAllFilepaths(Callback<JSONObject> cb) {
        run(() -> {
            JSONObject out = new JSONObject();
            JSONArray arr = new JSONArray();
            for (String fp : db.getAllFilepaths()) arr.put(fp);
            out.put("filepaths", arr);
            out.put("count", arr.length());
            return out;
        }, cb);
    }

    /**
     * Bugfix 2026-06-01k: bulk-load every embedding row into the supplied
     * heap maps via a single Cursor on the worker thread. See
     * EmbeddingDb.loadAllVecsIntoHeap. Callback returns rows loaded.
     */
    public void loadAllVecsIntoHeap(
            java.util.Map<String, float[]> vecOut,
            java.util.Map<String, String> hashToFilenameOut,
            java.util.Map<String, String> hashToFilepathOut,
            java.util.Map<String, String> filenameToHashOut,
            Callback<Integer> cb
    ) {
        run(() -> db.loadAllVecsIntoHeap(vecOut, hashToFilenameOut,
                hashToFilepathOut, filenameToHashOut), cb);
    }

    /**
     * Batch fetch of vec bytes by content hash. Returns a JSONObject of
     * {hash: base64-encoded little-endian Float32 bytes}. The Kotlin
     * recommender decodes these for MMR redundancy scoring.
     */
    public void getVecsByHashes(java.util.List<String> hashes, Callback<JSONObject> cb) {
        run(() -> {
            JSONObject out = new JSONObject();
            if (hashes == null || hashes.isEmpty()) {
                out.put("count", 0);
                out.put("vectors", new JSONObject());
                return out;
            }
            java.util.Map<String, byte[]> map = db.getVecsByHashes(hashes);
            JSONObject vectors = new JSONObject();
            for (java.util.Map.Entry<String, byte[]> e : map.entrySet()) {
                vectors.put(e.getKey(), Base64.encodeToString(e.getValue(), Base64.NO_WRAP));
            }
            out.put("count", map.size());
            out.put("vectors", vectors);
            return out;
        }, cb);
    }

    public void stats(Callback<JSONObject> cb) {
        run(() -> {
            JSONObject out = new JSONObject();
            out.put("count", db.count());
            out.put("dim", db.probeDim());
            out.put("dbSizeBytes", db.fileSizeBytes());
            out.put("vecExtensionLoaded", db.isVecExtensionLoaded());
            return out;
        }, cb);
    }

    // --- Legacy JSON mirror (Colab interchange) ---

    /**
     * Writes the current SQLite contents to local_embeddings.json in the
     * EXACT shape the legacy mirror used, so external tools
     * (colab_embedding_generator.py, local_embedding_generator.py) keep
     * working. Should be called periodically (e.g., on app pause), NOT on
     * every mutation. Runs on the worker thread.
     */
    public void exportLegacyMirror(Callback<JSONObject> cb) {
        run(() -> {
            List<EmbeddingEntity> rows = db.getAll();
            JSONObject root = new JSONObject();
            JSONObject pathIndex = new JSONObject();
            for (EmbeddingPathIndexEntity p : db.getAllPaths()) {
                pathIndex.put(p.filepath, p.contentHash);
            }
            root.put("_path_index", pathIndex);

            for (EmbeddingEntity r : rows) {
                if (r.vec == null || r.dim <= 0) continue;
                JSONArray vec = bytesToFloatArray(r.vec, r.dim);
                JSONObject row = new JSONObject();
                row.put("embedding", vec);
                row.put("filepath", r.filepath);
                row.put("filename", r.filename);
                row.put("artist", r.artist);
                row.put("album", r.album);
                row.put("contentHash", r.contentHash);
                row.put("content_hash", r.contentHash);
                row.put("timestamp", r.timestamp);
                root.put(r.contentHash, row);
            }

            File dir = getDataDir();
            File tmp = new File(dir, LEGACY_JSON + ".tmp");
            File dst = new File(dir, LEGACY_JSON);
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            } finally {
                fos.close();
            }
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) {
                throw new IOException("rename failed for legacy JSON export");
            }

            JSONObject out = new JSONObject();
            out.put("rowCount", rows.size());
            out.put("bytes", dst.length());
            return out;
        }, cb);
    }

    private static JSONArray bytesToFloatArray(byte[] vec, int dim) throws Exception {
        ByteBuffer bb = ByteBuffer.wrap(vec).order(ByteOrder.LITTLE_ENDIAN);
        JSONArray out = new JSONArray();
        for (int i = 0; i < dim; i++) {
            out.put((double) bb.getFloat());
        }
        return out;
    }

    /**
     * @deprecated Use {@link #forceReimportEmbeddings}; kept for call-site compat.
     */
    @Deprecated
    public void forceReimportLegacyJson(Callback<JSONObject> cb) {
        forceReimportEmbeddings(cb);
    }

    /**
     * Map every current library filepath to an existing embedding row by
     * matching basename / filename / artist+album. Fixes imported backups
     * whose stored paths differ from this install's MediaStore paths.
     *
     * Input: JSONArray of { filepath, filename, artist, album }.
     */
    public void relinkLibraryPaths(JSONArray librarySongs, Callback<JSONObject> cb) {
        run(() -> {
            JSONObject result = new JSONObject();
            if (librarySongs == null || librarySongs.length() == 0) {
                result.put("relinked", 0);
                result.put("alreadyLinked", 0);
                result.put("unmatched", 0);
                return result;
            }

            java.util.Set<String> embeddedPaths = db.getAllFilepaths();
            List<EmbeddingEntity> allEmb = db.getAll();
            List<EmbeddingPathIndexEntity> allPaths = db.getAllPaths();

            java.util.Map<String, List<RelinkCandidate>> byKey = new java.util.HashMap<>();
            java.util.Map<String, RelinkCandidate> byHash = new java.util.HashMap<>();

            for (EmbeddingEntity e : allEmb) {
                if (e.contentHash == null || e.contentHash.isEmpty()) continue;
                RelinkCandidate c = new RelinkCandidate();
                c.contentHash = e.contentHash;
                c.filename = e.filename != null ? e.filename : "";
                c.artist = e.artist != null ? e.artist : "";
                c.album = e.album != null ? e.album : "";
                byHash.put(c.contentHash, c);
                addRelinkCandidate(byKey, baseName(e.filepath), c);
                addRelinkCandidate(byKey, normName(e.filename), c);
            }
            for (EmbeddingPathIndexEntity p : allPaths) {
                if (p.filepath == null || p.filepath.isEmpty()) continue;
                if (p.contentHash == null || p.contentHash.isEmpty()) continue;
                RelinkCandidate c = byHash.get(p.contentHash);
                if (c == null) {
                    c = new RelinkCandidate();
                    c.contentHash = p.contentHash;
                    byHash.put(c.contentHash, c);
                }
                addRelinkCandidate(byKey, baseName(p.filepath), c);
            }

            List<EmbeddingPathIndexEntity> toUpsert = new ArrayList<>();
            int alreadyLinked = 0;
            int relinked = 0;
            int unmatched = 0;

            for (int i = 0; i < librarySongs.length(); i++) {
                JSONObject row = librarySongs.optJSONObject(i);
                if (row == null) continue;
                String fp = row.optString("filepath", "");
                if (fp.isEmpty()) continue;
                if (embeddedPaths.contains(fp)) {
                    alreadyLinked++;
                    continue;
                }

                String artist = row.optString("artist", "");
                String album = row.optString("album", "");
                String filename = row.optString("filename", "");

                List<RelinkCandidate> cands = byKey.get(baseName(fp));
                if (cands == null || cands.isEmpty()) {
                    cands = byKey.get(normName(filename));
                }
                if (cands == null || cands.isEmpty()) {
                    unmatched++;
                    continue;
                }

                RelinkCandidate best = pickRelinkCandidate(cands, artist, album, filename);
                if (best == null) {
                    unmatched++;
                    continue;
                }

                EmbeddingPathIndexEntity p = new EmbeddingPathIndexEntity();
                p.filepath = fp;
                p.contentHash = best.contentHash;
                toUpsert.add(p);
                embeddedPaths.add(fp);
                relinked++;
            }

            int upserted = db.upsertPaths(toUpsert);
            result.put("relinked", relinked);
            result.put("upserted", upserted);
            result.put("alreadyLinked", alreadyLinked);
            result.put("unmatched", unmatched);
            Log.i(TAG, "relinkLibraryPaths: relinked=" + relinked
                    + " alreadyLinked=" + alreadyLinked + " unmatched=" + unmatched);
            return result;
        }, cb);
    }

    /** filepath → content_hash for every indexed row (T_PATH + T_EMB.filepath). */
    public void getPathIndexMap(Callback<JSONObject> cb) {
        run(() -> {
            JSONObject paths = new JSONObject();
            for (EmbeddingPathIndexEntity p : db.getAllPaths()) {
                if (p.filepath != null && !p.filepath.isEmpty()
                        && p.contentHash != null && !p.contentHash.isEmpty()) {
                    paths.put(p.filepath, p.contentHash);
                }
            }
            for (EmbeddingEntity e : db.getAll()) {
                if (e.filepath != null && !e.filepath.isEmpty()
                        && e.contentHash != null && !e.contentHash.isEmpty()) {
                    paths.put(e.filepath, e.contentHash);
                }
            }
            JSONObject out = new JSONObject();
            out.put("paths", paths);
            return out;
        }, cb);
    }

    private static final class RelinkCandidate {
        String contentHash;
        String filename;
        String artist;
        String album;
    }

    private static void addRelinkCandidate(
            java.util.Map<String, List<RelinkCandidate>> map,
            String key,
            RelinkCandidate c
    ) {
        if (key == null || key.isEmpty() || c == null
                || c.contentHash == null || c.contentHash.isEmpty()) return;
        List<RelinkCandidate> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }
        for (RelinkCandidate existing : list) {
            if (c.contentHash.equals(existing.contentHash)) return;
        }
        list.add(c);
    }

    private static RelinkCandidate pickRelinkCandidate(
            List<RelinkCandidate> cands,
            String artist,
            String album,
            String filename
    ) {
        if (cands == null || cands.isEmpty()) return null;
        if (cands.size() == 1) return cands.get(0);
        String na = normName(artist);
        String nal = normName(album);
        String nf = normName(filename);
        RelinkCandidate best = null;
        int bestScore = Integer.MIN_VALUE;
        for (RelinkCandidate c : cands) {
            int score = 0;
            if (!na.isEmpty() && na.equals(normName(c.artist))) score += 3;
            if (!nal.isEmpty() && nal.equals(normName(c.album))) score += 3;
            if (!nf.isEmpty() && nf.equals(normName(c.filename))) score += 2;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best != null ? best : cands.get(0);
    }

    private static String normName(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String baseName(String path) {
        if (path == null || path.isEmpty()) return "";
        return normName(new File(path).getName());
    }

    private static List<String> jsonArrayToStringList(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String readUtf8(File f) throws Exception {
        return new String(readBytes(f), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File f) throws Exception {
        byte[] data = new byte[(int) f.length()];
        FileInputStream fis = new FileInputStream(f);
        int off = 0;
        while (off < data.length) {
            int r = fis.read(data, off, data.length - off);
            if (r < 0) break;
            off += r;
        }
        fis.close();
        return data;
    }
}
