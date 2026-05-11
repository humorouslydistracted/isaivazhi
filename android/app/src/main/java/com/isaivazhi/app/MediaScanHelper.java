package com.isaivazhi.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Audio library scan: MediaStore primary scan + filesystem fallback merge.
 * Pure helper — no Capacitor PluginCall handling here.
 */
final class MediaScanHelper {
    static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "opus", "mp3", "flac", "aac", "wav", "m4a", "ogg", "wma"
    ));

    private MediaScanHelper() {}

    /** Scans audio library and returns a JSObject with {songs: JSArray, count: int}. */
    static JSObject scan(Context ctx) throws Exception {
        JSArray results = new JSArray();
        Set<String> seenPaths = new HashSet<>();
        Set<String> scanRoots = new HashSet<>();
        ContentResolver resolver = ctx.getContentResolver();
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
                String title = cursor.getString(2);
                String artist = cursor.getString(3);
                String album = cursor.getString(4);
                long duration = cursor.getLong(5);
                long dateModified = cursor.getLong(6);

                if (path == null) continue;
                if (duration < 30000) continue;

                String pathLower = path.toLowerCase();
                if (pathLower.contains("/alarms/") || pathLower.contains("/ringtones/") ||
                    pathLower.contains("/notifications/") || pathLower.contains("/android/media/")) continue;

                String ext = "";
                int dotIndex = path.lastIndexOf('.');
                if (dotIndex > 0) ext = path.substring(dotIndex + 1).toLowerCase();
                if (!AUDIO_EXTENSIONS.contains(ext)) continue;

                String filename = path;
                int slashIndex = path.lastIndexOf('/');
                if (slashIndex >= 0) filename = path.substring(slashIndex + 1);

                // Skip Android-trash and other hidden dotfiles. Android's trash mechanism
                // renames files in place to `.trashed-<expiration>-<name>.ext`; the
                // `.pending-<id>-<name>` convention covers in-progress MediaStore writes.
                // Either is unreadable as a normal song and only pollutes the library.
                if (filename.startsWith(".")) continue;

                seenPaths.add(path);
                File parent = new File(path).getParentFile();
                if (parent != null) scanRoots.add(parent.getAbsolutePath());

                JSObject song = new JSObject();
                song.put("path", path);
                song.put("filename", filename);
                song.put("title", title != null ? title : filename);
                song.put("artist", artist != null ? artist : "Unknown");
                song.put("album", album != null ? album : "Unknown");
                song.put("duration", duration);
                song.put("dateModified", dateModified);

                File artFile = AlbumArtHelper.cachedArtFile(ctx, path);
                if (artFile.exists()) {
                    song.put("artPath", artFile.getAbsolutePath());
                }
                results.put(song);
            }
            cursor.close();
        }

        // MediaStore can miss files intermittently. Re-scan discovered folders directly
        // and merge any audio files MediaStore missed.
        for (String rootPath : scanRoots) {
            collectRecursive(ctx, new File(rootPath), seenPaths, results);
        }

        JSObject ret = new JSObject();
        ret.put("songs", results);
        ret.put("count", results.length());
        return ret;
    }

    private static void collectRecursive(Context ctx, File dir, Set<String> seenPaths, JSArray results) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            try {
                if (file.isDirectory()) {
                    collectRecursive(ctx, file, seenPaths, results);
                    continue;
                }

                String path = file.getAbsolutePath();
                if (seenPaths.contains(path)) continue;

                // Skip Android-trash and other hidden dotfiles (e.g. `.trashed-*`, `.pending-*`).
                // MediaStore filters these from its query, but a direct filesystem walk would
                // pick them up since the OS only renames them in place — leading to garbage
                // filenames in the library and embed queue.
                if (file.getName().startsWith(".")) continue;

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

                File artFile = AlbumArtHelper.cachedArtFile(ctx, path);
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
}
