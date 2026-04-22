package com.musicplayer.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@CapacitorPlugin(name = "MusicBridge")
public class MusicBridgePlugin extends Plugin {
    private static final String ART_CACHE_KEY_PREFIX = "art_v2::";

    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "opus", "mp3", "flac", "aac", "wav", "m4a", "ogg", "wma"
    ));

    private EmbeddingService embeddingService;

    @Override
    public void load() {
        MusicPlaybackService.pluginRef = this;
    }

    @PluginMethod
    public void hasStoragePermission(PluginCall call) {
        boolean granted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            granted = Environment.isExternalStorageManager();
        } else {
            granted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        JSObject ret = new JSObject();
        ret.put("granted", granted);
        call.resolve(ret);
    }

    @PluginMethod
    public void getAppDataDir(PluginCall call) {
        // Returns app-private external storage path — no special permissions needed,
        // survives app updates, works on GrapheneOS and all Android variants
        File dir = getContext().getExternalFilesDir(null);
        if (dir == null) dir = getContext().getFilesDir(); // fallback to internal
        if (!dir.exists()) dir.mkdirs();
        JSObject ret = new JSObject();
        ret.put("path", dir.getAbsolutePath());

        // List files in data dir for debugging
        JSArray fileList = new JSArray();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                fileList.put(f.getName() + " (" + f.length() + " bytes)");
            }
        }
        ret.put("files", fileList);
        ret.put("artCacheDir", new File(getContext().getCacheDir(), "albumart").getAbsolutePath());
        call.resolve(ret);
    }

    /**
     * Called from MusicPlaybackService when a media button / notification action fires.
     */
    void onMediaAction(String action) {
        onMediaAction(action, null);
    }

    void onMediaAction(String action, JSObject extra) {
        JSObject data = new JSObject();
        if (extra != null) {
            java.util.Iterator<String> keys = extra.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                data.put(key, extra.opt(key));
            }
        }
        data.put("action", action);
        notifyListeners("mediaAction", data);
    }

    /** Emit audio time update to JS */
    void emitAudioTimeUpdate(double position, double duration, boolean isPlaying, long playedMs, long durationMs, long playbackInstanceId) {
        JSObject data = new JSObject();
        data.put("position", position);
        data.put("duration", duration);
        data.put("isPlaying", isPlaying);
        data.put("playedMs", playedMs);
        data.put("durationMs", durationMs);
        data.put("playbackInstanceId", playbackInstanceId);
        notifyListeners("audioTimeUpdate", data);
    }

    /** Emit play state change to JS */
    void emitAudioPlayStateChanged(boolean isPlaying) {
        JSObject data = new JSObject();
        data.put("isPlaying", isPlaying);
        notifyListeners("audioPlayStateChanged", data);
    }

    /** Emit audio error to JS */
    void emitAudioError(String error) {
        JSObject data = new JSObject();
        data.put("error", error);
        notifyListeners("audioError", data);
    }

    /** Emit audio error with a path payload (used for file-missing reconciliation). */
    void emitAudioError(String error, String path) {
        JSObject data = new JSObject();
        data.put("error", error);
        if (path != null) data.put("path", path);
        notifyListeners("audioError", data);
    }

    /** Native queue autonomously changed the current item. */
    void emitQueueCurrentChanged(JSObject data) {
        notifyListeners("queueCurrentChanged", data);
    }

    /** Queue contents changed (items added/replaced). */
    void emitQueueChanged(JSObject data) {
        notifyListeners("queueChanged", data);
    }

    /** Queue exhausted (loop off, last item ended). */
    void emitQueueEnded() {
        notifyListeners("queueEnded", new JSObject());
    }

    // ===== ON-DEVICE EMBEDDING =====

    @PluginMethod
    public void stopEmbedding(PluginCall call) {
        // Stop via foreground service
        if (EmbeddingForegroundService.instance != null) {
            EmbeddingForegroundService.instance.cancelEmbedding();
        } else if (embeddingService != null) {
            embeddingService.cancelEmbedding();
        }
        call.resolve(new JSObject().put("status", "cancelled"));
    }

    @PluginMethod
    public void embedNewSongs(PluginCall call) {
        try {
            JSArray pathsArray = call.getArray("paths");
            if (pathsArray == null || pathsArray.length() == 0) {
                call.resolve(new JSObject().put("status", "no_songs"));
                return;
            }

            ArrayList<String> paths = new ArrayList<>();
            for (int i = 0; i < pathsArray.length(); i++) {
                paths.add(pathsArray.getString(i));
            }

            // Resolve immediately — progress comes via events
            call.resolve(new JSObject().put("status", "started").put("count", paths.size()));

            // Set up the callback that the foreground service will use to notify JS
            final MusicBridgePlugin self = this;
            EmbeddingForegroundService.pluginCallback = new EmbeddingService.EmbeddingCallback() {
                @Override
                public void onProgress(String filename, String filepath, int current, int total) {
                    JSObject data = new JSObject();
                    data.put("filename", filename);
                    data.put("filepath", filepath);
                    data.put("current", current);
                    data.put("total", total);
                    self.notifyListeners("embeddingProgress", data);
                }

                @Override
                public void onSongComplete(String filename, String filepath, float[] embedding, String contentHash) {
                    try {
                        JSObject data = new JSObject();
                        data.put("filename", filename);
                        data.put("filepath", filepath);
                        data.put("contentHash", contentHash);
                        JSArray embArray = new JSArray();
                        for (float v : embedding) embArray.put((double) v);
                        data.put("embedding", embArray);
                        self.notifyListeners("embeddingSongComplete", data);
                    } catch (Exception e) { /* ignore */ }
                }

                @Override
                public void onComplete(int totalProcessed, int failed) {
                    JSObject data = new JSObject();
                    data.put("processed", totalProcessed);
                    data.put("failed", failed);
                    self.notifyListeners("embeddingComplete", data);
                    EmbeddingForegroundService.pluginCallback = null;
                }

                @Override
                public void onError(String message, String filepath) {
                    JSObject data = new JSObject();
                    data.put("error", message);
                    if (filepath != null) data.put("filepath", filepath);
                    self.notifyListeners("embeddingError", data);
                }
            };

            // Start foreground service with wake lock + notification
            Intent intent = new Intent(getContext(), EmbeddingForegroundService.class);
            intent.setAction(EmbeddingForegroundService.ACTION_START);
            intent.putStringArrayListExtra(EmbeddingForegroundService.EXTRA_PATHS, paths);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }

        } catch (Exception e) {
            call.reject("Embedding failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void embedSingleSong(PluginCall call) {
        String path = call.getString("path");
        if (path == null) {
            call.reject("No path provided");
            return;
        }

        new Thread(() -> {
            try {
                if (embeddingService == null) {
                    embeddingService = new EmbeddingService(getContext());
                }

                float[] embedding = embeddingService.embedSingle(path);
                if (embedding == null) {
                    JSObject ret = new JSObject();
                    ret.put("success", false);
                    ret.put("error", "Failed to generate embedding");
                    getActivity().runOnUiThread(() -> call.resolve(ret));
                    return;
                }

                JSObject ret = new JSObject();
                ret.put("success", true);
                JSArray embArray = new JSArray();
                for (float v : embedding) embArray.put((double) v);
                ret.put("embedding", embArray);
                getActivity().runOnUiThread(() -> call.resolve(ret));

            } catch (Exception e) {
                getActivity().runOnUiThread(() ->
                    call.reject("Embedding failed: " + e.getMessage()));
            } finally {
                // Release ONNX model (~271MB) after single embedding
                if (embeddingService != null) {
                    embeddingService.release();
                    embeddingService = null;
                }
            }
        }).start();
    }

    // ===== SCAN =====

    @PluginMethod
    public void scanAudioFiles(PluginCall call) {
        try {
            JSArray results = new JSArray();
            Set<String> seenPaths = new HashSet<>();
            Set<String> scanRoots = new HashSet<>();
            ContentResolver resolver = getContext().getContentResolver();
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

            String[] projection = {
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_MODIFIED
            };

            Cursor cursor = resolver.query(uri, projection, null, null,
                    MediaStore.Audio.Media.DATE_MODIFIED + " DESC");

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String path = cursor.getString(0);
                    String displayName = cursor.getString(1);
                    String title = cursor.getString(2);
                    String artist = cursor.getString(3);
                    String album = cursor.getString(4);
                    long duration = cursor.getLong(5);
                    long dateModified = cursor.getLong(6);

                    if (path == null) continue;

                    // Skip short audio (ringtones, notifications, system sounds)
                    if (duration < 30000) continue; // < 30 seconds

                    // Skip system audio directories (Alarms, Ringtones, Notifications)
                    String pathLower = path.toLowerCase();
                    if (pathLower.contains("/alarms/") || pathLower.contains("/ringtones/") ||
                        pathLower.contains("/notifications/") || pathLower.contains("/android/media/")) continue;

                    String ext = "";
                    int dotIndex = path.lastIndexOf('.');
                    if (dotIndex > 0) {
                        ext = path.substring(dotIndex + 1).toLowerCase();
                    }
                    if (!AUDIO_EXTENSIONS.contains(ext)) continue;

                    seenPaths.add(path);
                    File parent = new File(path).getParentFile();
                    if (parent != null) scanRoots.add(parent.getAbsolutePath());

                    String filename = path;
                    int slashIndex = path.lastIndexOf('/');
                    if (slashIndex >= 0) {
                        filename = path.substring(slashIndex + 1);
                    }

                    JSObject song = new JSObject();
                    song.put("path", path);
                    song.put("filename", filename);
                    song.put("title", title != null ? title : filename);
                    song.put("artist", artist != null ? artist : "Unknown");
                    song.put("album", album != null ? album : "Unknown");
                    song.put("duration", duration);
                    song.put("dateModified", dateModified);

                    // Check if album art is already cached
                    File artFile = new File(getArtCacheDir(), artCacheKey(path) + ".jpg");
                    if (artFile.exists()) {
                        song.put("artPath", artFile.getAbsolutePath());
                    }

                    results.put(song);
                }
                cursor.close();
            }

            // MediaStore can miss a few files intermittently. Re-scan the same discovered
            // folders directly from the filesystem and merge any audio files missing above.
            for (String rootPath : scanRoots) {
                collectAudioFilesRecursive(new File(rootPath), seenPaths, results);
            }

            JSObject ret = new JSObject();
            ret.put("songs", results);
            ret.put("count", results.length());
            call.resolve(ret);

        } catch (Exception e) {
            call.reject("Scan failed: " + e.getMessage());
        }
    }

    private void collectAudioFilesRecursive(File dir, Set<String> seenPaths, JSArray results) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            try {
                if (file.isDirectory()) {
                    collectAudioFilesRecursive(file, seenPaths, results);
                    continue;
                }

                String path = file.getAbsolutePath();
                if (seenPaths.contains(path)) continue;

                String pathLower = path.toLowerCase();
                if (pathLower.contains("/alarms/") || pathLower.contains("/ringtones/") ||
                    pathLower.contains("/notifications/") || pathLower.contains("/android/media/")) continue;

                String ext = "";
                int dotIndex = path.lastIndexOf('.');
                if (dotIndex > 0) ext = path.substring(dotIndex + 1).toLowerCase();
                if (!AUDIO_EXTENSIONS.contains(ext)) continue;

                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                String title = null;
                String artist = null;
                String album = null;
                long duration = 0;
                try {
                    mmr.setDataSource(path);
                    title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (durationStr != null) duration = Long.parseLong(durationStr);
                } catch (Exception e) {
                    continue;
                } finally {
                    try { mmr.release(); } catch (Exception e) { /* ignore */ }
                }

                if (duration < 30000) continue;

                String filename = file.getName();
                JSObject song = new JSObject();
                song.put("path", path);
                song.put("filename", filename);
                song.put("title", title != null ? title : filename);
                song.put("artist", artist != null ? artist : "Unknown");
                song.put("album", album != null ? album : "Unknown");
                song.put("duration", duration);
                song.put("dateModified", file.lastModified() / 1000);

                File artFile = new File(getArtCacheDir(), artCacheKey(path) + ".jpg");
                if (artFile.exists()) {
                    song.put("artPath", artFile.getAbsolutePath());
                }

                results.put(song);
                seenPaths.add(path);
            } catch (Exception e) {
                // Skip unreadable files and continue scanning.
            }
        }
    }

    // ===== ALBUM ART =====

    private File getArtCacheDir() {
        File dir = new File(getContext().getCacheDir(), "albumart");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String artCacheKey(String filePath) {
        // Use a hash of the file path as the cache key
        return Integer.toHexString((ART_CACHE_KEY_PREFIX + filePath).hashCode());
    }

    @PluginMethod
    public void getAlbumArtUri(PluginCall call) {
        String filePath = call.getString("path");
        if (filePath == null) { call.reject("No path"); return; }

        File cacheFile = new File(getArtCacheDir(), artCacheKey(filePath) + ".jpg");
        if (cacheFile.exists()) {
            JSObject ret = new JSObject();
            ret.put("uri", cacheFile.getAbsolutePath());
            ret.put("exists", true);
            call.resolve(ret);
            return;
        }

        // Extract on demand
        new Thread(() -> {
            String result = extractAndCacheArt(filePath);
            JSObject ret = new JSObject();
            ret.put("exists", result != null);
            ret.put("uri", result != null ? result : "");
            call.resolve(ret);
        }).start();
    }

    @PluginMethod
    public void extractAlbumArtBatch(PluginCall call) {
        // Extract art for all given paths in background, notify progress
        JSArray pathsArray = call.getArray("paths");
        if (pathsArray == null) { call.reject("No paths"); return; }

        call.resolve(new JSObject().put("status", "started"));

        final MusicBridgePlugin self = this;
        new Thread(() -> {
            File cacheDir = getArtCacheDir();
            int extracted = 0;
            int total = pathsArray.length();
            for (int i = 0; i < total; i++) {
                try {
                    String path = pathsArray.getString(i);
                    File cacheFile = new File(cacheDir, artCacheKey(path) + ".jpg");
                    if (cacheFile.exists()) continue; // already cached
                    String result = extractAndCacheArt(path);
                    if (result != null) extracted++;
                } catch (Exception e) { /* skip */ }
            }
            // Notify JS that batch art extraction is done
            JSObject data = new JSObject();
            data.put("extracted", extracted);
            data.put("total", total);
            self.notifyListeners("albumArtReady", data);
        }).start();
    }

    private String extractAndCacheArt(String filePath) {
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(filePath);
            byte[] artBytes = mmr.getEmbeddedPicture();
            mmr.release();

            if (artBytes == null || artBytes.length == 0) return null;

            // Decode and scale to thumbnail
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length, opts);

            // Keep cached artwork large enough for the full player, then let CSS
            // scale it down elsewhere.
            int size = Math.max(opts.outWidth, opts.outHeight);
            int sampleSize = 1;
            while (size / sampleSize > 1536) sampleSize *= 2;

            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sampleSize;
            Bitmap bmp = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length, opts);
            if (bmp == null) return null;

            // Scale to a square large enough for full-screen rendering on high-DPI phones.
            Bitmap thumb = Bitmap.createScaledBitmap(bmp, 960, 960, true);
            if (thumb != bmp) bmp.recycle();

            File outFile = new File(getArtCacheDir(), artCacheKey(filePath) + ".jpg");
            FileOutputStream fos = new FileOutputStream(outFile);
            thumb.compress(Bitmap.CompressFormat.JPEG, 92, fos);
            fos.close();
            thumb.recycle();

            return outFile.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    // ===== FILE READ =====

    @PluginMethod
    public void readTextFile(PluginCall call) {
        String path = call.getString("path");
        if (path == null) {
            call.reject("No path provided");
            return;
        }

        try {
            File file = new File(path);
            if (!file.exists()) {
                Log.d("MusicBridge", "readTextFile: file not found: " + path);
                JSObject ret = new JSObject();
                ret.put("exists", false);
                ret.put("content", "");
                call.resolve(ret);
                return;
            }

            long fileSize = file.length();
            Log.d("MusicBridge", "readTextFile: " + path + " (" + fileSize + " bytes)");

            FileInputStream fis = new FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder((int) Math.min(fileSize, Integer.MAX_VALUE));
            char[] buffer = new char[8192];
            int charsRead;
            while ((charsRead = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, charsRead);
            }
            reader.close();

            String content = sb.toString();
            Log.d("MusicBridge", "readTextFile: read " + content.length() + " chars");

            JSObject ret = new JSObject();
            ret.put("exists", true);
            ret.put("content", content);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e("MusicBridge", "readTextFile failed: " + e.getMessage(), e);
            call.reject("Read failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void writeTextFile(PluginCall call) {
        String path = call.getString("path");
        String content = call.getString("content");
        if (path == null || content == null) {
            call.reject("Path and content required");
            return;
        }
        try {
            File file = new File(path);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Write failed: " + e.getMessage());
        }
    }

    // ===== BINARY FILE I/O (for embedding cache) =====

    @PluginMethod
    public void readBinaryFile(PluginCall call) {
        String path = call.getString("path");
        if (path == null) { call.reject("No path provided"); return; }
        try {
            File file = new File(path);
            if (!file.exists()) {
                JSObject ret = new JSObject();
                ret.put("exists", false);
                call.resolve(ret);
                return;
            }
            FileInputStream fis = new FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = fis.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            fis.close();
            JSObject ret = new JSObject();
            ret.put("exists", true);
            ret.put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
            ret.put("size", bytes.length);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Read failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void writeBinaryFile(PluginCall call) {
        String path = call.getString("path");
        String base64Data = call.getString("data");
        if (path == null || base64Data == null) { call.reject("Path and data required"); return; }
        try {
            byte[] bytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP);
            File file = new File(path);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.close();
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Write failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getFileModified(PluginCall call) {
        String path = call.getString("path");
        if (path == null) { call.reject("No path provided"); return; }
        File file = new File(path);
        JSObject ret = new JSObject();
        ret.put("exists", file.exists());
        ret.put("lastModified", file.exists() ? file.lastModified() : 0);
        call.resolve(ret);
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        String path = call.getString("path");
        if (path == null) { call.reject("No path provided"); return; }
        try {
            File file = new File(path);
            JSObject ret = new JSObject();
            ret.put("success", !file.exists() || file.delete());
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Delete failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void renameFile(PluginCall call) {
        String from = call.getString("from");
        String to = call.getString("to");
        if (from == null || to == null) { call.reject("From and to required"); return; }
        try {
            File src = new File(from);
            File dest = new File(to);
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (dest.exists()) dest.delete();
            boolean ok = src.renameTo(dest);
            if (!ok) {
                call.reject("Rename failed");
                return;
            }
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Rename failed: " + e.getMessage());
        }
    }

    /**
     * Convert local_embeddings.json to binary cache (bin + meta.json) natively.
     * This avoids passing 16MB+ JSON through the Capacitor bridge which can silently fail.
     * Called from JS when JSON exists but binary cache doesn't.
     */
    @PluginMethod
    public void convertEmbeddingsJsonToBinary(PluginCall call) {
        new Thread(() -> {
            try {
                File dir = getContext().getExternalFilesDir(null);
                if (dir == null) dir = getContext().getFilesDir();
                File jsonFile = new File(dir, "local_embeddings.json");
                if (!jsonFile.exists()) {
                    JSObject ret = new JSObject();
                    ret.put("success", false);
                    ret.put("reason", "json_not_found");
                    call.resolve(ret);
                    return;
                }

                Log.d("MusicBridge", "Converting local_embeddings.json (" + jsonFile.length() + " bytes) to binary cache...");

                // Read and parse JSON
                FileInputStream fis = new FileInputStream(jsonFile);
                byte[] jsonBytes = new byte[(int) jsonFile.length()];
                int offset = 0;
                while (offset < jsonBytes.length) {
                    int read = fis.read(jsonBytes, offset, jsonBytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                fis.close();

                JSONObject root = new JSONObject(new String(jsonBytes, "UTF-8"));
                jsonBytes = null; // free memory

                // Extract entries
                JSONObject pathIndex = root.optJSONObject("_path_index");
                if (pathIndex == null) pathIndex = new JSONObject();

                java.util.List<String> keys = new ArrayList<>();
                java.util.List<String> filepaths = new ArrayList<>();
                java.util.List<String> contentHashes = new ArrayList<>();
                java.util.List<Long> timestamps = new ArrayList<>();
                java.util.List<String> filenames = new ArrayList<>();
                java.util.List<String> artists = new ArrayList<>();
                java.util.List<String> albums = new ArrayList<>();
                int dim = 0;

                java.util.Iterator<String> it = root.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    if ("_path_index".equals(key)) continue;
                    JSONObject entry = root.optJSONObject(key);
                    if (entry == null) continue;
                    JSONArray emb = entry.optJSONArray("embedding");
                    if (emb == null || emb.length() == 0) continue;
                    if (dim == 0) dim = emb.length();
                    keys.add(key);
                    filepaths.add(entry.optString("filepath", ""));
                    contentHashes.add(entry.optString("content_hash", entry.optString("contentHash", "")));
                    timestamps.add(entry.optLong("timestamp", 0));
                    filenames.add(entry.optString("filename", ""));
                    artists.add(entry.optString("artist", ""));
                    albums.add(entry.optString("album", ""));
                }

                if (keys.isEmpty() || dim == 0) {
                    JSObject ret = new JSObject();
                    ret.put("success", false);
                    ret.put("reason", "no_entries");
                    call.resolve(ret);
                    return;
                }

                // Pack embeddings into float array → byte array
                int totalFloats = keys.size() * dim;
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(totalFloats * 4);
                buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < keys.size(); i++) {
                    String key = keys.get(i);
                    JSONArray emb = root.getJSONObject(key).getJSONArray("embedding");
                    for (int j = 0; j < dim; j++) {
                        buf.putFloat((float) emb.getDouble(j));
                    }
                }
                byte[] binBytes = buf.array();

                // Write .bin file
                File binFile = new File(dir, "local_embeddings.bin");
                FileOutputStream binOut = new FileOutputStream(binFile);
                binOut.write(binBytes);
                binOut.close();

                // Build and write meta JSON
                JSONObject meta = new JSONObject();
                meta.put("dim", dim);
                meta.put("pathIndex", pathIndex);
                JSONArray entriesArr = new JSONArray();
                for (int i = 0; i < keys.size(); i++) {
                    JSONObject e = new JSONObject();
                    e.put("key", keys.get(i));
                    e.put("filepath", filepaths.get(i));
                    e.put("contentHash", contentHashes.get(i));
                    e.put("timestamp", timestamps.get(i));
                    e.put("filename", filenames.get(i));
                    e.put("artist", artists.get(i));
                    e.put("album", albums.get(i));
                    entriesArr.put(e);
                }
                meta.put("entries", entriesArr);

                File metaFile = new File(dir, "local_embeddings_meta.json");
                FileOutputStream metaOut = new FileOutputStream(metaFile);
                metaOut.write(meta.toString().getBytes("UTF-8"));
                metaOut.close();

                // Touch bin file to be newer than json (so cache is considered fresh)
                binFile.setLastModified(jsonFile.lastModified() + 1000);

                Log.d("MusicBridge", "Binary cache created: " + keys.size() + " entries, " + dim + "d, " + binBytes.length + " bytes");

                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("entries", keys.size());
                ret.put("dim", dim);
                call.resolve(ret);

            } catch (Exception e) {
                Log.e("MusicBridge", "convertEmbeddingsJsonToBinary failed", e);
                call.reject("Conversion failed: " + e.getMessage());
            }
        }).start();
    }

    // ===== NATIVE AUDIO PLAYBACK =====

    @PluginMethod
    public void playAudio(PluginCall call) {
        String path = call.getString("path");
        if (path == null) {
            call.reject("No path provided");
            return;
        }
        String title = call.getString("title", "Music Player");
        String artist = call.getString("artist", "");
        long seekTo = call.hasOption("seekTo") ? (long)(call.getFloat("seekTo", 0f) * 1000) : 0;

        // Start/ensure service is running, then play
        Intent intent = new Intent(getContext(), MusicPlaybackService.class);
        intent.setAction(MusicPlaybackService.ACTION_UPDATE);
        intent.putExtra("title", title);
        intent.putExtra("artist", artist);
        intent.putExtra("isPlaying", true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }

        // Wait for service to be ready, then play — retry up to 1s
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] retries = {0};
        final int maxRetries = 10; // 10 × 100ms = 1s max wait
        Runnable tryPlay = new Runnable() {
            @Override
            public void run() {
                if (MusicPlaybackService.instance != null) {
                    MusicPlaybackService.instance.currentTitle = title;
                    MusicPlaybackService.instance.currentArtist = artist;
                    MusicPlaybackService.instance.beginPlaybackInstance();
                    MusicPlaybackService.instance.playFile(path, seekTo);
                    call.resolve();
                } else if (retries[0] < maxRetries) {
                    retries[0]++;
                    handler.postDelayed(this, 100);
                } else {
                    call.reject("Playback service not available after 1s");
                }
            }
        };
        handler.post(tryPlay);
    }

    @PluginMethod
    public void pauseAudio(PluginCall call) {
        if (MusicPlaybackService.instance != null) {
            MusicPlaybackService.instance.pausePlayback();
        }
        call.resolve();
    }

    @PluginMethod
    public void resumeAudio(PluginCall call) {
        if (MusicPlaybackService.instance != null) {
            MusicPlaybackService.instance.resumePlayback();
        }
        call.resolve();
    }

    @PluginMethod
    public void seekAudio(PluginCall call) {
        float positionSec = call.getFloat("position", 0f);
        if (MusicPlaybackService.instance != null) {
            MusicPlaybackService.instance.seekTo((long)(positionSec * 1000));
        }
        call.resolve();
    }

    @PluginMethod
    public void getAudioState(PluginCall call) {
        JSObject ret = new JSObject();
        if (MusicPlaybackService.instance != null) {
            ret.put("position", MusicPlaybackService.instance.getCurrentPosition() / 1000.0);
            ret.put("duration", MusicPlaybackService.instance.getDuration() / 1000.0);
            ret.put("playedMs", MusicPlaybackService.instance.getAccumulatedPlayedMsSnapshot());
            ret.put("durationMs", MusicPlaybackService.instance.getDurationMsSnapshot());
            ret.put("isPlaying", MusicPlaybackService.instance.isCurrentlyPlaying());
            ret.put("completedState", MusicPlaybackService.instance.isPlaybackCompletedState());
            ret.put("currentPlaybackInstanceId", MusicPlaybackService.instance.getCurrentPlaybackInstanceId());
            String filePath = MusicPlaybackService.instance.getCurrentFilePath();
            ret.put("filePath", filePath != null ? filePath : "");
            ret.put("title", MusicPlaybackService.instance.getCurrentTitle());
            ret.put("artist", MusicPlaybackService.instance.getCurrentArtist());
            ret.put("album", MusicPlaybackService.instance.getCurrentAlbum());
            if (filePath != null) {
                File f = new File(filePath);
                ret.put("filename", f.getName());
            } else {
                ret.put("filename", "");
            }
        } else {
            ret.put("position", 0);
            ret.put("duration", 0);
            ret.put("playedMs", 0);
            ret.put("durationMs", 0);
            ret.put("isPlaying", false);
            ret.put("completedState", false);
            ret.put("currentPlaybackInstanceId", 0);
            ret.put("filePath", "");
            ret.put("title", "");
            ret.put("artist", "");
            ret.put("album", "");
            ret.put("filename", "");
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void getRecentPlaybackTransitions(PluginCall call) {
        int limit = call.getInt("limit", 12);
        JSObject ret = new JSObject();
        ret.put("items", MusicPlaybackService.getRecentPlaybackTransitions(getContext(), limit));
        call.resolve(ret);
    }

    @PluginMethod
    public void setLooping(PluginCall call) {
        boolean loop = call.getBoolean("loop", false);
        if (MusicPlaybackService.instance != null) {
            MusicPlaybackService.instance.setLooping(loop);
        }
        call.resolve();
    }

    // ===== NATIVE QUEUE =====

    private List<MusicPlaybackService.QueueItem> parseQueueItems(PluginCall call) {
        List<MusicPlaybackService.QueueItem> out = new ArrayList<>();
        JSArray arr = call.getArray("items");
        if (arr == null) return out;
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int songId = o.optInt("songId", -1);
                String path = o.optString("filePath", null);
                if (path == null || path.isEmpty()) continue;
                String title = o.optString("title", "");
                String artist = o.optString("artist", "");
                String album = o.optString("album", "");
                out.add(new MusicPlaybackService.QueueItem(songId, path, title, artist, album));
            }
        } catch (Exception e) {
            Log.e("MusicBridge", "parseQueueItems failed", e);
        }
        return out;
    }

    private void ensureServiceStarted() {
        Intent intent = new Intent(getContext(), MusicPlaybackService.class);
        intent.setAction(MusicPlaybackService.ACTION_UPDATE);
        intent.putExtra("isPlaying", true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
    }

    private void withService(Runnable action, PluginCall call) {
        if (MusicPlaybackService.instance != null) {
            action.run();
            call.resolve();
            return;
        }
        ensureServiceStarted();
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] retries = {0};
        final int maxRetries = 10;
        Runnable tryRun = new Runnable() {
            @Override
            public void run() {
                if (MusicPlaybackService.instance != null) {
                    action.run();
                    call.resolve();
                } else if (retries[0] < maxRetries) {
                    retries[0]++;
                    handler.postDelayed(this, 100);
                } else {
                    call.reject("Playback service not available");
                }
            }
        };
        handler.post(tryRun);
    }

    @PluginMethod
    public void setQueue(PluginCall call) {
        final List<MusicPlaybackService.QueueItem> items = parseQueueItems(call);
        final int startIndex = call.getInt("startIndex", 0);
        final long seekTo = call.hasOption("seekTo") ? (long)(call.getFloat("seekTo", 0f) * 1000) : 0;
        withService(() -> MusicPlaybackService.instance.setQueue(items, startIndex, seekTo), call);
    }

    @PluginMethod
    public void replaceUpcoming(PluginCall call) {
        final List<MusicPlaybackService.QueueItem> items = parseQueueItems(call);
        withService(() -> MusicPlaybackService.instance.replaceUpcoming(items), call);
    }

    @PluginMethod
    public void insertAfterCurrent(PluginCall call) {
        final List<MusicPlaybackService.QueueItem> items = parseQueueItems(call);
        withService(() -> MusicPlaybackService.instance.insertAfterCurrent(items), call);
    }

    @PluginMethod
    public void appendToQueue(PluginCall call) {
        final List<MusicPlaybackService.QueueItem> items = parseQueueItems(call);
        withService(() -> MusicPlaybackService.instance.appendToQueue(items), call);
    }

    @PluginMethod
    public void clearQueueAfterCurrent(PluginCall call) {
        if (MusicPlaybackService.instance != null) {
            MusicPlaybackService.instance.clearQueueAfterCurrent();
        }
        call.resolve();
    }

    @PluginMethod
    public void playIndex(PluginCall call) {
        final int index = call.getInt("index", 0);
        if (MusicPlaybackService.instance != null) {
            MusicPlaybackService.instance.playIndex(index);
        }
        call.resolve();
    }

    @PluginMethod
    public void nextTrack(PluginCall call) {
        float prevFraction = call.getFloat("prevFraction", -1f);
        String action = call.getString("action", "user_next");
        if (MusicPlaybackService.instance != null) {
            MusicPlaybackService.instance.nextTrack(action, prevFraction);
        }
        call.resolve();
    }

    @PluginMethod
    public void prevTrack(PluginCall call) {
        float prevFraction = call.getFloat("prevFraction", -1f);
        if (MusicPlaybackService.instance != null) {
            MusicPlaybackService.instance.prevTrack(prevFraction);
        }
        call.resolve();
    }

    @PluginMethod
    public void setLoopMode(PluginCall call) {
        int mode = call.getInt("mode", 2);
        if (MusicPlaybackService.instance != null) {
            MusicPlaybackService.instance.setLoopMode(mode);
        }
        call.resolve();
    }

    @PluginMethod
    public void getQueueState(PluginCall call) {
        if (MusicPlaybackService.instance != null) {
            call.resolve(MusicPlaybackService.instance.getQueueSnapshot());
        } else {
            JSObject ret = new JSObject();
            ret.put("currentIndex", -1);
            ret.put("length", 0);
            ret.put("isPlaying", false);
            ret.put("items", new JSArray());
            call.resolve(ret);
        }
    }

    // ===== PLAYBACK SERVICE CONTROL =====

    @PluginMethod
    public void startPlaybackService(PluginCall call) {
        String title = call.getString("title", "Music Player");
        String artist = call.getString("artist", "");
        boolean isPlaying = call.getBoolean("isPlaying", true);

        Intent intent = new Intent(getContext(), MusicPlaybackService.class);
        intent.setAction(MusicPlaybackService.ACTION_UPDATE);
        intent.putExtra("title", title);
        intent.putExtra("artist", artist);
        intent.putExtra("isPlaying", isPlaying);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }

        call.resolve();
    }

    @PluginMethod
    public void updatePlaybackState(PluginCall call) {
        String title = call.getString("title");
        String artist = call.getString("artist");
        String album = call.getString("album");
        boolean isPlaying = call.getBoolean("isPlaying", false);

        Intent intent = new Intent(getContext(), MusicPlaybackService.class);
        intent.setAction(MusicPlaybackService.ACTION_UPDATE);
        if (title != null) intent.putExtra("title", title);
        if (artist != null) intent.putExtra("artist", artist);
        if (album != null) intent.putExtra("album", album);
        intent.putExtra("isPlaying", isPlaying);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }
        } catch (Exception e) {
            // Service may not be running yet
        }

        call.resolve();
    }

    @PluginMethod
    public void deleteAudioFile(PluginCall call) {
        String path = call.getString("path");
        if (path == null || path.isEmpty()) {
            call.reject("path required");
            return;
        }
        try {
            File f = new File(path);
            boolean fsDeleted = false;
            if (f.exists()) {
                try { fsDeleted = f.delete(); } catch (SecurityException se) { fsDeleted = false; }
            }

            // Also remove the MediaStore row so the song disappears from the library immediately.
            boolean mediaStoreDeleted = false;
            try {
                ContentResolver resolver = getContext().getContentResolver();
                Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                int rows = resolver.delete(
                        collection,
                        MediaStore.Audio.Media.DATA + "=?",
                        new String[]{ path }
                );
                mediaStoreDeleted = rows > 0;
            } catch (Exception mse) {
                Log.w("MusicBridge", "MediaStore delete failed: " + mse.getMessage());
            }

            JSObject ret = new JSObject();
            ret.put("fsDeleted", fsDeleted);
            ret.put("mediaStoreDeleted", mediaStoreDeleted);
            ret.put("stillExists", new File(path).exists());
            if (!fsDeleted && !mediaStoreDeleted && new File(path).exists()) {
                call.reject("Delete failed — file still present. MANAGE_EXTERNAL_STORAGE may be required.");
                return;
            }
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Delete error: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopPlaybackService(PluginCall call) {
        Intent intent = new Intent(getContext(), MusicPlaybackService.class);
        intent.setAction(MusicPlaybackService.ACTION_STOP);
        try {
            getContext().startService(intent);
        } catch (Exception e) {
            // ignore
        }
        call.resolve();
    }
}
