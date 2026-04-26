package com.isaivazhi.app;

import android.net.Uri;
import android.os.Bundle;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class PlaybackQueueItem {

    final int songId;
    final String filePath;
    final String title;
    final String artist;
    final String album;

    PlaybackQueueItem(int songId, String filePath, String title, String artist, String album) {
        this.songId = songId;
        this.filePath = filePath != null ? filePath : "";
        this.title = title != null ? title : "";
        this.artist = artist != null ? artist : "";
        this.album = album != null ? album : "";
    }

    Bundle toBundle() {
        Bundle b = new Bundle();
        b.putInt(PlaybackCommandContract.KEY_SONG_ID, songId);
        b.putString(PlaybackCommandContract.KEY_FILE_PATH, filePath);
        b.putString(PlaybackCommandContract.KEY_TITLE, title);
        b.putString(PlaybackCommandContract.KEY_ARTIST, artist);
        b.putString(PlaybackCommandContract.KEY_ALBUM, album);
        return b;
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put(PlaybackCommandContract.KEY_SONG_ID, songId);
        o.put(PlaybackCommandContract.KEY_FILE_PATH, filePath);
        o.put(PlaybackCommandContract.KEY_TITLE, title);
        o.put(PlaybackCommandContract.KEY_ARTIST, artist);
        o.put(PlaybackCommandContract.KEY_ALBUM, album);
        return o;
    }

    MediaItem toMediaItem() {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build();
        return new MediaItem.Builder()
                .setMediaId(mediaId())
                .setUri(Uri.fromFile(new File(filePath)))
                .setMediaMetadata(metadata)
                .build();
    }

    String fileName() {
        if (filePath == null || filePath.isEmpty()) return "";
        return new File(filePath).getName();
    }

    String mediaId() {
        return songId + "::" + filePath;
    }

    static PlaybackQueueItem fromBundle(Bundle b) {
        if (b == null) return null;
        String path = b.getString(PlaybackCommandContract.KEY_FILE_PATH, "");
        if (path.isEmpty()) return null;
        return new PlaybackQueueItem(
                b.getInt(PlaybackCommandContract.KEY_SONG_ID, -1),
                path,
                b.getString(PlaybackCommandContract.KEY_TITLE, ""),
                b.getString(PlaybackCommandContract.KEY_ARTIST, ""),
                b.getString(PlaybackCommandContract.KEY_ALBUM, "")
        );
    }

    static ArrayList<Bundle> toBundleList(List<PlaybackQueueItem> items) {
        ArrayList<Bundle> out = new ArrayList<>();
        if (items == null) return out;
        for (PlaybackQueueItem item : items) {
            if (item != null) out.add(item.toBundle());
        }
        return out;
    }

    static ArrayList<PlaybackQueueItem> fromBundleList(ArrayList<Bundle> bundles) {
        ArrayList<PlaybackQueueItem> out = new ArrayList<>();
        if (bundles == null) return out;
        for (Bundle bundle : bundles) {
            PlaybackQueueItem item = fromBundle(bundle);
            if (item != null) out.add(item);
        }
        return out;
    }

    static String toJsonString(List<PlaybackQueueItem> items) {
        JSONArray arr = new JSONArray();
        if (items == null) return arr.toString();
        for (PlaybackQueueItem item : items) {
            try {
                if (item != null) arr.put(item.toJson());
            } catch (Exception e) {
                // Skip malformed item; the bundle payload still acts as another fallback.
            }
        }
        return arr.toString();
    }

    static ArrayList<PlaybackQueueItem> fromJsonString(String raw) {
        ArrayList<PlaybackQueueItem> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String path = o.optString(PlaybackCommandContract.KEY_FILE_PATH, "");
                if (path.isEmpty()) continue;
                out.add(new PlaybackQueueItem(
                        o.optInt(PlaybackCommandContract.KEY_SONG_ID, -1),
                        path,
                        o.optString(PlaybackCommandContract.KEY_TITLE, ""),
                        o.optString(PlaybackCommandContract.KEY_ARTIST, ""),
                        o.optString(PlaybackCommandContract.KEY_ALBUM, "")
                ));
            }
        } catch (Exception e) {
            // Ignore malformed JSON and let callers handle an empty queue.
        }
        return out;
    }

    static ArrayList<PlaybackQueueItem> fromCommandBundle(Bundle args) {
        Bundle safe = args != null ? args : Bundle.EMPTY;
        ArrayList<PlaybackQueueItem> items = fromBundleList(
                safe.getParcelableArrayList(PlaybackCommandContract.KEY_ITEMS)
        );
        if (!items.isEmpty()) return items;
        return fromJsonString(safe.getString(PlaybackCommandContract.KEY_ITEMS_JSON, ""));
    }

    static List<MediaItem> toMediaItems(List<PlaybackQueueItem> items) {
        ArrayList<MediaItem> out = new ArrayList<>();
        if (items == null) return out;
        for (PlaybackQueueItem item : items) {
            if (item != null) out.add(item.toMediaItem());
        }
        return out;
    }
}
