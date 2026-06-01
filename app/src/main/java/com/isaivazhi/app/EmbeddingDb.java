package com.isaivazhi.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import io.requery.android.database.sqlite.SQLiteCustomExtension;
import io.requery.android.database.sqlite.SQLiteDatabase;
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQLite-backed storage for stable embeddings, backed by requery's
 * sqlite-android (so we can load the sqlite-vec native extension).
 *
 * Replaces the legacy local_embeddings.bin + local_embeddings_meta.json
 * files as the source of truth. The portable local_embeddings.json mirror
 * is still written periodically by EmbeddingDbManager.exportLegacyMirror.
 *
 * All public methods MUST be called from a background thread. The
 * EmbeddingDbManager owns the HandlerThread that runs them.
 */
final class EmbeddingDb {

    private static final String TAG = "EmbeddingDb";
    private static final String DB_NAME = "embeddings.db";

    private static final String T_EMB = "embeddings";
    private static final String T_PATH = "embedding_path_index";

    private static volatile EmbeddingDb sInstance;

    private final Context appCtx;
    private final String dbPath;
    private volatile SQLiteDatabase database;
    private volatile boolean vecExtensionLoaded = false;

    /**
     * In-memory snapshot used by the NativeAccelerator (NEON) fallback path
     * when sqlite-vec is unavailable. Caches the flattened embedding matrix
     * + metadata so each KNN call doesn't re-read 5 MB from SQLite through
     * a 2 MB CursorWindow — that was the root cause of the periodic ~3 s
     * frame stalls observed after a few minutes of use (logs.txt 2026-05-12).
     *
     * Invalidated when the embedding row count changes (insert/delete/migrate).
     */
    private volatile FallbackSnapshot fallbackSnapshot;

    private static final class FallbackSnapshot {
        final int dim;
        final int rowCount;
        final float[] flat;          // rowCount * dim
        final String[] contentHashes;
        final String[] filepaths;
        final String[] filenames;

        FallbackSnapshot(int dim, int rowCount, float[] flat,
                         String[] contentHashes, String[] filepaths, String[] filenames) {
            this.dim = dim;
            this.rowCount = rowCount;
            this.flat = flat;
            this.contentHashes = contentHashes;
            this.filepaths = filepaths;
            this.filenames = filenames;
        }
    }

    static EmbeddingDb get(Context ctx) {
        EmbeddingDb local = sInstance;
        if (local == null) {
            synchronized (EmbeddingDb.class) {
                local = sInstance;
                if (local == null) {
                    local = new EmbeddingDb(ctx.getApplicationContext());
                    sInstance = local;
                }
            }
        }
        return local;
    }

    private EmbeddingDb(Context ctx) {
        this.appCtx = ctx;
        File extDir = ctx.getExternalFilesDir(null);
        if (extDir == null) extDir = ctx.getFilesDir();
        if (!extDir.exists()) extDir.mkdirs();
        this.dbPath = new File(extDir, DB_NAME).getAbsolutePath();
    }

    private SQLiteDatabase open() {
        SQLiteDatabase local = database;
        if (local != null && local.isOpen()) return local;
        synchronized (this) {
            local = database;
            if (local != null && local.isOpen()) return local;

            int openFlags = SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.OPEN_READWRITE;
            // sqlite-vec lives in jniLibs/<abi>/libsqlite_vec.so. Android
            // extracts native libs at install time so the absolute path under
            // nativeLibraryDir works for load_extension.
            File so = new File(appCtx.getApplicationInfo().nativeLibraryDir, "libsqlite_vec.so");
            List<SQLiteCustomExtension> exts = new ArrayList<>();
            if (so.exists()) {
                exts.add(new SQLiteCustomExtension(so.getAbsolutePath(), "sqlite3_vec_init"));
            } else {
                Log.w(TAG, "sqlite-vec .so not present at " + so.getAbsolutePath()
                        + " — opening DB without extension; KNN will use NativeAccelerator fallback");
            }
            SQLiteDatabaseConfiguration cfg = new SQLiteDatabaseConfiguration(
                    dbPath, openFlags,
                    Collections.emptyList(), Collections.emptyList(), exts);
            try {
                local = SQLiteDatabase.openDatabase(cfg, null, null);
            } catch (Throwable t) {
                // If extension load aborts open, retry without it so the DB
                // still works for storage/mutation.
                Log.w(TAG, "openDatabase with extension failed: " + t.getMessage()
                        + " — retrying without extension");
                cfg = new SQLiteDatabaseConfiguration(
                        dbPath, openFlags,
                        Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
                local = SQLiteDatabase.openDatabase(cfg, null, null);
            }
            ensureSchema(local);
            probeVecExtension(local);
            database = local;
            return local;
        }
    }

    private void ensureSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_EMB + " ("
                + "content_hash TEXT PRIMARY KEY NOT NULL, "
                + "filepath TEXT NOT NULL DEFAULT '', "
                + "filename TEXT NOT NULL DEFAULT '', "
                + "artist TEXT NOT NULL DEFAULT '', "
                + "album TEXT NOT NULL DEFAULT '', "
                + "timestamp INTEGER NOT NULL DEFAULT 0, "
                + "dim INTEGER NOT NULL DEFAULT 0, "
                + "vec BLOB NOT NULL"
                + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_emb_filepath ON " + T_EMB + " (filepath)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_emb_filename ON " + T_EMB + " (filename)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + T_PATH + " ("
                + "filepath TEXT PRIMARY KEY NOT NULL, "
                + "content_hash TEXT NOT NULL"
                + ")");
    }

    private void probeVecExtension(SQLiteDatabase db) {
        try {
            Cursor c = db.rawQuery("SELECT vec_version()", null);
            try {
                if (c.moveToFirst()) {
                    Log.i(TAG, "sqlite-vec loaded: " + c.getString(0));
                    vecExtensionLoaded = true;
                }
            } finally {
                c.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "sqlite-vec probe failed: " + t.getMessage()
                    + " — KNN will use NativeAccelerator fallback");
            vecExtensionLoaded = false;
        }
    }

    boolean isVecExtensionLoaded() {
        // Force open if needed so the probe runs.
        try { open(); } catch (Throwable ignored) {}
        return vecExtensionLoaded;
    }

    private SQLiteDatabase getWritableDatabase() { return open(); }
    private SQLiteDatabase getReadableDatabase() { return open(); }

    // --- Embeddings ---

    void upsert(EmbeddingEntity e) {
        SQLiteDatabase db = getWritableDatabase();
        // Push #52: clear stale rows for the same physical file before
        // inserting the new one. The table's PRIMARY KEY is content_hash,
        // not filepath — without this guard, re-embedding a file with a
        // changed decode/hash pipeline produces a new row and leaves the
        // old one behind (the duplicate accumulation pattern push #51
        // had to clean up). Scoped to non-empty filepath + non-empty
        // hash so legacy/empty-path rows aren't accidentally wiped.
        clearStaleRowsForSameFile(db, e);
        ContentValues cv = embToContentValues(e);
        db.insertWithOnConflict(T_EMB, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        invalidateFallbackSnapshot();
    }

    int upsertAll(List<EmbeddingEntity> list) {
        if (list == null || list.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int n = 0;
            for (EmbeddingEntity e : list) {
                // Push #52: same per-file dedupe guard as upsert(). Cheap
                // inside the transaction; runs once per row.
                clearStaleRowsForSameFile(db, e);
                ContentValues cv = embToContentValues(e);
                db.insertWithOnConflict(T_EMB, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
            db.setTransactionSuccessful();
            return n;
        } finally {
            db.endTransaction();
            invalidateFallbackSnapshot();
        }
    }

    /**
     * Push #52: delete any existing T_EMB rows whose filepath matches
     * the incoming row but content_hash differs. The CONFLICT_REPLACE on
     * insert handles the identical-hash case; this method handles the
     * scenario where a re-embed produced a different hash for the same
     * file. Together they enforce "at most one embedding row per
     * filepath" going forward, without touching legacy rows that
     * predate this guard (those get cleaned by the user via the AI
     * page's Duplicates section).
     */
    private void clearStaleRowsForSameFile(SQLiteDatabase db, EmbeddingEntity e) {
        if (e == null) return;
        String filepath = e.filepath != null ? e.filepath : "";
        String newHash = e.contentHash != null ? e.contentHash : "";
        if (filepath.isEmpty() || newHash.isEmpty()) return;
        db.delete(T_EMB,
                "filepath = ? AND content_hash != ?",
                new String[]{filepath, newHash});
    }

    int deleteByHashes(List<String> hashes) {
        if (hashes == null || hashes.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String[] args = new String[1];
            int removed = 0;
            for (String h : hashes) {
                args[0] = h;
                removed += db.delete(T_EMB, "content_hash = ?", args);
                db.delete(T_PATH, "content_hash = ?", args);
            }
            db.setTransactionSuccessful();
            return removed;
        } finally {
            db.endTransaction();
            invalidateFallbackSnapshot();
        }
    }

    int deleteByFilepaths(List<String> filepaths) {
        if (filepaths == null || filepaths.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        invalidateFallbackSnapshot();
        db.beginTransaction();
        try {
            String[] args = new String[1];
            int removed = 0;
            for (String fp : filepaths) {
                args[0] = fp;
                Cursor c = db.query(T_PATH, new String[]{"content_hash"},
                        "filepath = ?", args, null, null, null);
                try {
                    while (c.moveToNext()) {
                        String h = c.getString(0);
                        db.delete(T_EMB, "content_hash = ?", new String[]{h});
                    }
                } finally {
                    c.close();
                }
                removed += db.delete(T_EMB, "filepath = ?", args);
                db.delete(T_PATH, "filepath = ?", args);
            }
            db.setTransactionSuccessful();
            return removed;
        } finally {
            db.endTransaction();
        }
    }

    int deleteByFilenames(List<String> filenames) {
        if (filenames == null || filenames.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        invalidateFallbackSnapshot();
        db.beginTransaction();
        try {
            String[] args = new String[1];
            int removed = 0;
            for (String fn : filenames) {
                args[0] = fn;
                removed += db.delete(T_EMB, "filename = ?", args);
            }
            db.setTransactionSuccessful();
            return removed;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Push #50: rows whose filename appears more than once in the
     * embeddings table. Schema's PRIMARY KEY is content_hash, so two
     * physically different files (or two embedder versions of the same
     * file) can share a filename. Used by the AI page's Duplicates
     * section so the user can pick which row to keep based on
     * filepath, type, timestamp, etc.
     */
    static final class DuplicateRow {
        String contentHash;
        String filepath;
        String filename;
        String artist;
        String album;
        long timestamp;
        int dim;
    }

    /**
     * Push #61: groups of filepaths that map to the same content_hash in
     * the path index (T_PATH). These are TRUE audio duplicates: two
     * different files (different paths) with byte-identical first-30s
     * audio. The T_EMB embeddings table can only hold one row per
     * content_hash (PK constraint), so CONFLICT_REPLACE during embedding
     * keeps just one filepath in T_EMB.filepath — the others remain
     * recorded in T_PATH but disappear from T_EMB. Walking T_PATH is
     * the only way to surface the full set.
     *
     * Each entry: "<content_hash>|<filepath1>|<filepath2>|...".
     * Only includes content_hashes with COUNT(filepath) > 1.
     */
    static final class AudioDupGroup {
        String contentHash;
        java.util.List<String> filepaths;
    }

    java.util.List<AudioDupGroup> getAudioDuplicateGroups() {
        SQLiteDatabase db = getReadableDatabase();
        java.util.LinkedHashMap<String, AudioDupGroup> byHash = new java.util.LinkedHashMap<>();
        Cursor c = db.rawQuery(
                "SELECT content_hash, filepath FROM " + T_PATH +
                        " WHERE content_hash IN (" +
                        "  SELECT content_hash FROM " + T_PATH +
                        "  WHERE filepath != ''" +
                        "  GROUP BY content_hash HAVING COUNT(*) > 1)" +
                        " ORDER BY content_hash ASC, filepath ASC",
                null);
        try {
            while (c.moveToNext()) {
                String hash = c.getString(0);
                String fp = c.getString(1);
                if (hash == null || hash.isEmpty() || fp == null || fp.isEmpty()) continue;
                AudioDupGroup g = byHash.get(hash);
                if (g == null) {
                    g = new AudioDupGroup();
                    g.contentHash = hash;
                    g.filepaths = new java.util.ArrayList<>();
                    byHash.put(hash, g);
                }
                g.filepaths.add(fp);
            }
        } finally {
            c.close();
        }
        return new java.util.ArrayList<>(byHash.values());
    }

    /**
     * Push #61: remove the path-index entry for [filepath] only. Used as
     * a cleanup step after the user deletes a file from the Audio
     * Duplicates section — the OS handles the file + MediaStore row,
     * but the T_PATH entry would otherwise linger and keep showing the
     * dead filepath in the duplicate group. T_EMB row is NOT touched
     * here; if T_EMB.filepath happens to match, it becomes Stale and
     * the existing Stale-removal flow handles it.
     */
    int removePathIndexEntry(String filepath) {
        if (filepath == null || filepath.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(T_PATH, "filepath = ?", new String[]{filepath});
    }

    /**
     * Push #51: filepath-based duplicate detection (replaces push #50's
     * filename grouping which produced false positives across folders).
     *
     * A duplicate row is one whose `filepath` is shared with at least one
     * other row in the table — same physical file embedded more than once
     * (typically because re-embedding produced a different content_hash
     * than a previous run did). Rows with empty filepath (legacy JSON
     * imports without path data) are excluded; those surface in the
     * Stale list instead since we can't reliably tie them to a file on
     * the current device.
     *
     * Ordered by filepath ASC so a single SELECT walks each group's
     * rows contiguously; within a group rows are timestamp DESC (newest
     * first) with content_hash ASC tiebreak for stable rendering.
     */
    List<DuplicateRow> getDuplicateRows() {
        SQLiteDatabase db = getReadableDatabase();
        List<DuplicateRow> out = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT content_hash, filepath, filename, artist, album, timestamp, dim FROM " + T_EMB +
                        " WHERE filepath != '' AND filepath IN (" +
                        "  SELECT filepath FROM " + T_EMB +
                        "  WHERE filepath != ''" +
                        "  GROUP BY filepath HAVING COUNT(*) > 1)" +
                        " ORDER BY filepath ASC, timestamp DESC, content_hash ASC",
                null);
        try {
            while (c.moveToNext()) {
                DuplicateRow d = new DuplicateRow();
                d.contentHash = c.getString(0);
                d.filepath = c.getString(1);
                d.filename = c.getString(2);
                d.artist = c.getString(3);
                d.album = c.getString(4);
                d.timestamp = c.getLong(5);
                d.dim = c.getInt(6);
                out.add(d);
            }
        } finally {
            c.close();
        }
        return out;
    }

    int deleteAllEmbeddings() {
        SQLiteDatabase db = getWritableDatabase();
        int n = db.delete(T_EMB, null, null);
        invalidateFallbackSnapshot();
        return n;
    }

    List<EmbeddingEntity> getAll() {
        SQLiteDatabase db = getReadableDatabase();
        List<EmbeddingEntity> out = new ArrayList<>();
        Cursor c = db.query(T_EMB,
                new String[]{"content_hash","filepath","filename","artist","album","timestamp","dim","vec"},
                null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                EmbeddingEntity e = new EmbeddingEntity();
                e.contentHash = c.getString(0);
                e.filepath = c.getString(1);
                e.filename = c.getString(2);
                e.artist = c.getString(3);
                e.album = c.getString(4);
                e.timestamp = c.getLong(5);
                e.dim = c.getInt(6);
                e.vec = c.getBlob(7);
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Bugfix 2026-06-01k: bulk-load every embedding row directly into JVM
     * heap maps via a single Cursor. Bypasses the JSON/base64 path used by
     * getVecsByHashes AND bypasses Song.contentHash (which is null in the
     * Song objects returned by LibraryCache.loadOrScan on cold start - the
     * 06-01j log showed VEC_HEAP_WARMED size=0 in 0ms because
     * prewarmFromLibrary skipped every song). Decodes vec bytes straight
     * into float[] in one pass.
     */
    int loadAllVecsIntoHeap(
            java.util.Map<String, float[]> vecOut,
            java.util.Map<String, String> hashToFilenameOut,
            java.util.Map<String, String> hashToFilepathOut,
            java.util.Map<String, String> filenameToHashOut
    ) {
        SQLiteDatabase db = getReadableDatabase();
        int count = 0;
        Cursor c = db.query(T_EMB,
                new String[]{"content_hash","filename","filepath","vec","dim"},
                null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                String hash = c.getString(0);
                if (hash == null || hash.isEmpty()) continue;
                String filename = c.getString(1);
                String filepath = c.getString(2);
                byte[] vecBytes = c.getBlob(3);
                int dim = c.getInt(4);
                if (vecBytes == null || dim <= 0 || vecBytes.length != dim * 4) continue;
                float[] vec = new float[dim];
                java.nio.ByteBuffer.wrap(vecBytes)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer()
                        .get(vec);
                vecOut.put(hash, vec);
                hashToFilepathOut.put(hash, filepath != null ? filepath
                        : (filename != null ? filename : ""));
                if (filename != null) {
                    hashToFilenameOut.put(hash, filename);
                    if (!filename.isEmpty()) filenameToHashOut.put(filename, hash);
                }
                count++;
            }
        } finally {
            c.close();
        }
        return count;
    }

    int count() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + T_EMB, null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    /** Lightweight: just the filenames of every embedded row. Used by the AI page to compute "pending" lists. */
    java.util.Set<String> getAllFilenames() {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT filename FROM " + T_EMB, null);
        try { while (c.moveToNext()) out.add(c.getString(0)); } finally { c.close(); }
        return out;
    }

    /**
     * Push #56 diagnostic: returns DB-side filepath info for every row whose
     * filename matches the argument. Used by the one-shot Pending diagnostic
     * to surface the exact filepath strings stored in T_EMB and T_PATH so
     * we can compare them against MediaStore.DATA — which is the only way to
     * see why a song that's clearly embedded still shows up as "Pending".
     *
     * For each T_EMB row matching `filename`:
     *   - "emb:<content_hash[..8]>:<T_EMB.filepath>"
     *   - "path:<content_hash[..8]>:<T_PATH.filepath>"  (one line per T_PATH row sharing that hash)
     */
    java.util.List<String> diagnoseByFilename(String filename) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (filename == null || filename.isEmpty()) return out;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_EMB,
                new String[]{"content_hash", "filepath"},
                "filename = ?", new String[]{filename}, null, null, null);
        try {
            while (c.moveToNext()) {
                String hash = c.getString(0);
                String fp = c.getString(1);
                String hashShort = hash != null && hash.length() > 8 ? hash.substring(0, 8) : (hash != null ? hash : "");
                out.add("emb:" + hashShort + ":\"" + (fp != null ? fp : "") + "\"");
                if (hash != null && !hash.isEmpty()) {
                    Cursor pc = db.query(T_PATH, new String[]{"filepath"},
                            "content_hash = ?", new String[]{hash}, null, null, null);
                    try {
                        while (pc.moveToNext()) {
                            String pfp = pc.getString(0);
                            out.add("path:" + hashShort + ":\"" + (pfp != null ? pfp : "") + "\"");
                        }
                    } finally { pc.close(); }
                }
            }
        } finally { c.close(); }
        return out;
    }

    /**
     * Push #53: filepaths of every embedded row. The AI page's Pending
     * computation uses this instead of filenames — MediaStore's
     * DISPLAY_NAME can drift from the on-disk filename (character
     * normalization, trimming, edge cases) which made songs look pending
     * even after they were embedded.
     *
     * Push #55: UNION across T_EMB.filepath AND T_PATH.filepath. Legacy
     * JSON imports (Capacitor-era local_embeddings.json) often populate
     * only the `_path_index` map, not a per-entry filepath field — so the
     * resulting T_EMB rows have `filepath = ""`, while the canonical
     * filepath data lives in T_PATH. The previous query only looked at
     * T_EMB and missed those rows, leaving legacy-imported songs stuck
     * in Pending forever even though their embeddings were in the DB.
     *
     * The UNION + DISTINCT folds both data sources into one Set. New
     * embeds (which populate T_EMB.filepath directly) are unaffected.
     */
    java.util.Set<String> getAllFilepaths() {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT DISTINCT filepath FROM (" +
                        "  SELECT filepath FROM " + T_EMB + " WHERE filepath != ''" +
                        "  UNION " +
                        "  SELECT filepath FROM " + T_PATH + " WHERE filepath != ''" +
                        ")",
                null);
        try { while (c.moveToNext()) out.add(c.getString(0)); } finally { c.close(); }
        return out;
    }

    /** Returns the raw Float32 LE vec bytes for a song by filename, or null. */
    byte[] getVecByFilename(String filename) {
        if (filename == null || filename.isEmpty()) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_EMB, new String[]{"vec"},
                "filename = ?", new String[]{filename}, null, null, null, "1");
        try { return c.moveToFirst() ? c.getBlob(0) : null; } finally { c.close(); }
    }

    /**
     * Batch fetch: returns {hash → vec bytes} for the supplied content
     * hashes. Used by the MMR rerank to load candidate vectors in one SQL
     * round-trip instead of one query per candidate.
     */
    java.util.Map<String, byte[]> getVecsByHashes(java.util.List<String> hashes) {
        java.util.HashMap<String, byte[]> out = new java.util.HashMap<>();
        if (hashes == null || hashes.isEmpty()) return out;
        SQLiteDatabase db = getReadableDatabase();
        // SQLite has a default 999-arg limit on IN clauses; chunk safely.
        final int CHUNK = 500;
        for (int start = 0; start < hashes.size(); start += CHUNK) {
            int end = Math.min(start + CHUNK, hashes.size());
            int n = end - start;
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) placeholders.append(",");
                placeholders.append("?");
            }
            String[] args = new String[n];
            for (int i = 0; i < n; i++) args[i] = hashes.get(start + i);
            Cursor c = db.rawQuery(
                    "SELECT content_hash, vec FROM " + T_EMB + " WHERE content_hash IN (" + placeholders + ")",
                    args);
            try {
                while (c.moveToNext()) {
                    out.put(c.getString(0), c.getBlob(1));
                }
            } finally {
                c.close();
            }
        }
        return out;
    }

    /** Returns the raw Float32 LE vec bytes for a song by content hash, or null. */
    byte[] getVecByHash(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_EMB, new String[]{"vec"},
                "content_hash = ?", new String[]{hash}, null, null, null, "1");
        try { return c.moveToFirst() ? c.getBlob(0) : null; } finally { c.close(); }
    }

    int probeDim() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT dim FROM " + T_EMB + " LIMIT 1", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    long fileSizeBytes() {
        File f = new File(dbPath);
        return f.exists() ? f.length() : 0L;
    }

    // --- Path index ---

    void upsertPath(EmbeddingPathIndexEntity p) {
        if (p == null || p.filepath == null || p.filepath.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("filepath", p.filepath);
        cv.put("content_hash", p.contentHash != null ? p.contentHash : "");
        db.insertWithOnConflict(T_PATH, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    int upsertPaths(List<EmbeddingPathIndexEntity> list) {
        if (list == null || list.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int n = 0;
            for (EmbeddingPathIndexEntity p : list) {
                if (p == null || p.filepath == null || p.filepath.isEmpty()) continue;
                ContentValues cv = new ContentValues();
                cv.put("filepath", p.filepath);
                cv.put("content_hash", p.contentHash != null ? p.contentHash : "");
                db.insertWithOnConflict(T_PATH, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
            db.setTransactionSuccessful();
            return n;
        } finally {
            db.endTransaction();
        }
    }

    int deleteAllPaths() {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(T_PATH, null, null);
    }

    List<EmbeddingPathIndexEntity> getAllPaths() {
        SQLiteDatabase db = getReadableDatabase();
        List<EmbeddingPathIndexEntity> out = new ArrayList<>();
        Cursor c = db.query(T_PATH, new String[]{"filepath","content_hash"},
                null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                EmbeddingPathIndexEntity p = new EmbeddingPathIndexEntity();
                p.filepath = c.getString(0);
                p.contentHash = c.getString(1);
                out.add(p);
            }
        } finally {
            c.close();
        }
        return out;
    }

    // --- Atomic replace (one-shot migration) ---

    void replaceAll(List<EmbeddingEntity> ents, List<EmbeddingPathIndexEntity> paths) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(T_EMB, null, null);
            db.delete(T_PATH, null, null);
            if (ents != null) {
                for (EmbeddingEntity e : ents) {
                    ContentValues cv = embToContentValues(e);
                    db.insertWithOnConflict(T_EMB, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            if (paths != null) {
                for (EmbeddingPathIndexEntity p : paths) {
                    if (p == null || p.filepath == null || p.filepath.isEmpty()) continue;
                    ContentValues cv = new ContentValues();
                    cv.put("filepath", p.filepath);
                    cv.put("content_hash", p.contentHash != null ? p.contentHash : "");
                    db.insertWithOnConflict(T_PATH, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            invalidateFallbackSnapshot();
        }
    }

    private static ContentValues embToContentValues(EmbeddingEntity e) {
        ContentValues cv = new ContentValues();
        cv.put("content_hash", e.contentHash != null ? e.contentHash : "");
        cv.put("filepath", e.filepath != null ? e.filepath : "");
        cv.put("filename", e.filename != null ? e.filename : "");
        cv.put("artist", e.artist != null ? e.artist : "");
        cv.put("album", e.album != null ? e.album : "");
        cv.put("timestamp", e.timestamp);
        cv.put("dim", e.dim);
        cv.put("vec", e.vec != null ? e.vec : new byte[0]);
        return cv;
    }

    // --- Nearest-neighbor search ---

    static final class NnResult {
        final String contentHash;
        final String filepath;
        final String filename;
        final double similarity;

        NnResult(String contentHash, String filepath, String filename, double similarity) {
            this.contentHash = contentHash;
            this.filepath = filepath;
            this.filename = filename;
            this.similarity = similarity;
        }
    }

    List<NnResult> nearestNeighbors(byte[] queryBytes, int k, java.util.Set<String> excludeHashes) {
        if (queryBytes == null || queryBytes.length == 0) return new ArrayList<>();
        if (k <= 0) return new ArrayList<>();
        if (isVecExtensionLoaded()) {
            try {
                return sqliteVecNearestNeighbors(queryBytes, k, excludeHashes);
            } catch (Throwable t) {
                Log.w(TAG, "sqlite-vec query failed, falling back to NativeAccelerator: " + t.getMessage());
            }
        }
        return nativeAccelNearestNeighbors(queryBytes, k, excludeHashes);
    }

    private List<NnResult> sqliteVecNearestNeighbors(byte[] queryBytes, int k, java.util.Set<String> excludeHashes) {
        SQLiteDatabase db = getReadableDatabase();
        String hex = bytesToHex(queryBytes);
        StringBuilder excludeClause = new StringBuilder();
        if (excludeHashes != null && !excludeHashes.isEmpty()) {
            excludeClause.append(" WHERE content_hash NOT IN (");
            boolean first = true;
            for (String h : excludeHashes) {
                if (h == null || h.isEmpty()) continue;
                if (!first) excludeClause.append(",");
                excludeClause.append("'").append(h.replace("'", "''")).append("'");
                first = false;
            }
            excludeClause.append(")");
        }
        int limit = Math.max(k * 2 + 5, k);
        String sql = "SELECT content_hash, filepath, filename, "
                + "vec_distance_cosine(vec, x'" + hex + "') AS d "
                + "FROM embeddings"
                + excludeClause.toString()
                + " ORDER BY d ASC LIMIT " + limit;
        Cursor c = db.rawQuery(sql, null);
        List<NnResult> out = new ArrayList<>();
        try {
            while (c.moveToNext() && out.size() < k) {
                double d = c.getDouble(3);
                double sim = 1.0 - d;
                out.add(new NnResult(c.getString(0), c.getString(1), c.getString(2), sim));
            }
        } finally {
            c.close();
        }
        return out;
    }

    private List<NnResult> nativeAccelNearestNeighbors(byte[] queryBytes, int k, java.util.Set<String> excludeHashes) {
        FallbackSnapshot snap = ensureFallbackSnapshot();
        if (snap == null || snap.rowCount == 0 || queryBytes.length != snap.dim * 4) return new ArrayList<>();

        int dim = snap.dim;
        float[] query = new float[dim];
        java.nio.ByteBuffer qb = java.nio.ByteBuffer.wrap(queryBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < dim; i++) query[i] = qb.getFloat();

        float[] sims = new float[snap.rowCount];
        boolean usedNative = false;
        if (NativeAccelerator.isAvailable()) {
            usedNative = NativeAccelerator.dotProductBatch(query, snap.flat, snap.rowCount, dim, sims);
        }
        if (!usedNative) {
            for (int i = 0; i < snap.rowCount; i++) {
                float s = 0f;
                int base = i * dim;
                for (int j = 0; j < dim; j++) s += query[j] * snap.flat[base + j];
                sims[i] = s;
            }
        }
        List<NnResult> best = new ArrayList<>();
        for (int i = 0; i < snap.rowCount; i++) {
            String h = snap.contentHashes[i];
            if (excludeHashes != null && excludeHashes.contains(h)) continue;
            NnResult cand = new NnResult(h, snap.filepaths[i], snap.filenames[i], sims[i]);
            int pos = 0;
            while (pos < best.size() && best.get(pos).similarity >= cand.similarity) pos++;
            if (pos < k) {
                best.add(pos, cand);
                if (best.size() > k) best.remove(best.size() - 1);
            }
        }
        return best;
    }

    /**
     * Build (or return cached) flat snapshot of the entire embeddings table
     * for the NativeAccelerator fallback path. Loading is one-time per
     * cache miss; subsequent KNN calls reuse the same arrays without
     * touching SQLite. Saves ~3 MB of cursor I/O per call.
     */
    private FallbackSnapshot ensureFallbackSnapshot() {
        FallbackSnapshot s = fallbackSnapshot;
        if (s != null) return s;
        synchronized (this) {
            s = fallbackSnapshot;
            if (s != null) return s;
            s = loadFallbackSnapshot();
            fallbackSnapshot = s;
            return s;
        }
    }

    private FallbackSnapshot loadFallbackSnapshot() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_EMB,
                new String[]{"content_hash", "filepath", "filename", "dim", "vec"},
                null, null, null, null, null);
        List<String> hashes = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> filenames = new ArrayList<>();
        int dim = 0;
        java.util.ArrayList<float[]> rows = new java.util.ArrayList<>();
        try {
            while (c.moveToNext()) {
                int rowDim = c.getInt(3);
                byte[] vec = c.getBlob(4);
                if (rowDim <= 0 || vec == null || vec.length != rowDim * 4) continue;
                if (dim == 0) dim = rowDim;
                else if (dim != rowDim) continue;
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(vec).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                float[] row = new float[rowDim];
                for (int i = 0; i < rowDim; i++) row[i] = bb.getFloat();
                rows.add(row);
                hashes.add(c.getString(0));
                paths.add(c.getString(1));
                filenames.add(c.getString(2));
            }
        } finally {
            c.close();
        }
        if (rows.isEmpty() || dim == 0) {
            return new FallbackSnapshot(0, 0, new float[0],
                    new String[0], new String[0], new String[0]);
        }
        float[] flat = new float[rows.size() * dim];
        for (int i = 0; i < rows.size(); i++) {
            System.arraycopy(rows.get(i), 0, flat, i * dim, dim);
        }
        Log.i(TAG, "loaded fallback snapshot: rows=" + rows.size() + " dim=" + dim);
        return new FallbackSnapshot(
                dim, rows.size(), flat,
                hashes.toArray(new String[0]),
                paths.toArray(new String[0]),
                filenames.toArray(new String[0]));
    }

    /** Drop the cached snapshot so the next KNN call rebuilds it. */
    void invalidateFallbackSnapshot() {
        fallbackSnapshot = null;
    }

    private static String bytesToHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] HEX = {'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'};
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            hex[i * 2] = HEX[b >>> 4];
            hex[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(hex);
    }
}
