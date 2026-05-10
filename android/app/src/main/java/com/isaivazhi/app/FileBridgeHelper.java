package com.isaivazhi.app;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * File I/O bridge: text + binary read/write, modified-time, delete, rename,
 * plus the embedding JSON→binary cache converter.
 * Pure helper — returns JSObject results; the plugin façade owns PluginCall handling.
 */
final class FileBridgeHelper {
    private static final String TAG = "MusicBridge";

    private FileBridgeHelper() {}

    static JSObject readTextFile(String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) {
            Log.d(TAG, "readTextFile: file not found: " + path);
            JSObject ret = new JSObject();
            ret.put("exists", false);
            ret.put("content", "");
            return ret;
        }

        long fileSize = file.length();
        Log.d(TAG, "readTextFile: " + path + " (" + fileSize + " bytes)");

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
        Log.d(TAG, "readTextFile: read " + content.length() + " chars");

        JSObject ret = new JSObject();
        ret.put("exists", true);
        ret.put("content", content);
        return ret;
    }

    static JSObject writeTextFile(String path, String content) throws Exception {
        File file = new File(path);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
        JSObject ret = new JSObject();
        ret.put("success", true);
        return ret;
    }

    static JSObject readBinaryFile(String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) {
            JSObject ret = new JSObject();
            ret.put("exists", false);
            return ret;
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
        ret.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        ret.put("size", bytes.length);
        return ret;
    }

    static JSObject writeBinaryFile(String path, String base64Data) throws Exception {
        byte[] bytes = Base64.decode(base64Data, Base64.NO_WRAP);
        File file = new File(path);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(bytes);
        fos.close();
        JSObject ret = new JSObject();
        ret.put("success", true);
        return ret;
    }

    static JSObject getFileModified(String path) {
        File file = new File(path);
        JSObject ret = new JSObject();
        ret.put("exists", file.exists());
        ret.put("lastModified", file.exists() ? file.lastModified() : 0);
        return ret;
    }

    static JSObject deleteFile(String path) {
        File file = new File(path);
        JSObject ret = new JSObject();
        ret.put("success", !file.exists() || file.delete());
        return ret;
    }

    /** Returns null on success (rename ok); a non-null string is the failure reason. */
    static String renameFile(String from, String to) throws Exception {
        File src = new File(from);
        File dest = new File(to);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (dest.exists()) dest.delete();
        return src.renameTo(dest) ? null : "Rename failed";
    }

    /**
     * Convert local_embeddings.json to binary cache (bin + meta.json) natively.
     * Avoids passing 16MB+ JSON through the Capacitor bridge which can silently fail.
     * Result keys: success (bool), reason (string, on failure), entries (int), dim (int).
     */
    static JSObject convertEmbeddingsJsonToBinary(Context ctx) throws Exception {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        File jsonFile = new File(dir, "local_embeddings.json");
        if (!jsonFile.exists()) {
            JSObject ret = new JSObject();
            ret.put("success", false);
            ret.put("reason", "json_not_found");
            return ret;
        }

        Log.d(TAG, "Converting local_embeddings.json (" + jsonFile.length() + " bytes) to binary cache...");

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

        JSONObject pathIndex = root.optJSONObject("_path_index");
        if (pathIndex == null) pathIndex = new JSONObject();

        List<String> keys = new ArrayList<>();
        List<String> filepaths = new ArrayList<>();
        List<String> contentHashes = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();
        List<String> filenames = new ArrayList<>();
        List<String> artists = new ArrayList<>();
        List<String> albums = new ArrayList<>();
        int dim = 0;

        Iterator<String> it = root.keys();
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
            return ret;
        }

        int totalFloats = keys.size() * dim;
        ByteBuffer buf = ByteBuffer.allocate(totalFloats * 4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            JSONArray emb = root.getJSONObject(key).getJSONArray("embedding");
            for (int j = 0; j < dim; j++) {
                buf.putFloat((float) emb.getDouble(j));
            }
        }
        byte[] binBytes = buf.array();

        File binFile = new File(dir, "local_embeddings.bin");
        FileOutputStream binOut = new FileOutputStream(binFile);
        binOut.write(binBytes);
        binOut.close();

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

        binFile.setLastModified(jsonFile.lastModified() + 1000);

        Log.d(TAG, "Binary cache created: " + keys.size() + " entries, " + dim + "d, " + binBytes.length + " bytes");

        JSObject ret = new JSObject();
        ret.put("success", true);
        ret.put("entries", keys.size());
        ret.put("dim", dim);
        return ret;
    }
}
