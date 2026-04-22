package com.isaivazhi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MusicPlaybackServiceRobolectricTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences(MusicPlaybackService.RECOVERY_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void toggleCurrentFavoriteInStorage_addsFavoriteAndRemovesDislike() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("favorites", "{\"filenames\":[\"alpha.opus\"]}")
                .putString("disliked_songs", "[\"beta.opus\",\"gamma.opus\"]")
                .commit();

        MusicPlaybackService service = buildService();
        setField(service, "currentFilePath", "/mock/device/beta.opus");
        setField(service, "currentTitle", "Beta");
        setField(service, "currentArtist", "Artist Two");
        setField(service, "currentAlbum", "Album Two");

        JSObject result = invokeJsObject(service, "toggleCurrentFavoriteInStorage");

        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("isFavorite"));
        assertTrue(result.getBoolean("unDisliked"));
        assertEquals("beta.opus", result.getString("filename"));

        JSONObject favoritesPayload = new JSONObject(prefs.getString("favorites", ""));
        JSONArray favorites = favoritesPayload.getJSONArray("filenames");
        assertEquals(2, favorites.length());
        assertEquals("alpha.opus", favorites.getString(0));
        assertEquals("beta.opus", favorites.getString(1));

        JSONArray dislikes = new JSONArray(prefs.getString("disliked_songs", "[]"));
        assertEquals(1, dislikes.length());
        assertEquals("gamma.opus", dislikes.getString(0));
    }

    @Test
    public void toggleCurrentFavoriteInStorage_removesExistingFavoriteWithoutTouchingOtherDislikes() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("favorites", "{\"filenames\":[\"alpha.opus\",\"beta.opus\"]}")
                .putString("disliked_songs", "[\"gamma.opus\"]")
                .commit();

        MusicPlaybackService service = buildService();
        setField(service, "currentFilePath", "/mock/device/beta.opus");

        JSObject result = invokeJsObject(service, "toggleCurrentFavoriteInStorage");

        assertTrue(result.getBoolean("ok"));
        assertFalse(result.getBoolean("isFavorite"));
        assertFalse(result.getBoolean("unDisliked"));

        JSONObject favoritesPayload = new JSONObject(prefs.getString("favorites", ""));
        JSONArray favorites = favoritesPayload.getJSONArray("filenames");
        assertEquals(1, favorites.length());
        assertEquals("alpha.opus", favorites.getString(0));

        JSONArray dislikes = new JSONArray(prefs.getString("disliked_songs", "[]"));
        assertEquals(1, dislikes.length());
        assertEquals("gamma.opus", dislikes.getString(0));
    }

    @Test
    public void clearSavedPlaybackState_removesPlaybackStatePreference() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE);
        prefs.edit().putString("playback_state", "{\"currentFilename\":\"beta.opus\"}").commit();

        MusicPlaybackService service = buildService();
        invokeVoid(service, "clearSavedPlaybackState");

        assertNull(prefs.getString("playback_state", null));
    }

    @Test
    public void getRecentPlaybackTransitions_returnsLatestRequestedItems() throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(MusicPlaybackService.RECOVERY_PREFS_NAME, Context.MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        arr.put(new JSONObject().put("action", "a").put("order", 1));
        arr.put(new JSONObject().put("action", "b").put("order", 2));
        arr.put(new JSONObject().put("action", "c").put("order", 3));
        arr.put(new JSONObject().put("action", "d").put("order", 4));
        arr.put(new JSONObject().put("action", "e").put("order", 5));
        prefs.edit().putString(MusicPlaybackService.RECOVERY_TRANSITIONS_KEY, arr.toString()).commit();

        JSArray recent = MusicPlaybackService.getRecentPlaybackTransitions(context, 3);

        assertEquals(3, recent.length());
        assertEquals("c", recent.getJSONObject(0).getString("action"));
        assertEquals("d", recent.getJSONObject(1).getString("action"));
        assertEquals("e", recent.getJSONObject(2).getString("action"));
    }

    private MusicPlaybackService buildService() {
        return Robolectric.buildService(MusicPlaybackService.class).create().get();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = MusicPlaybackService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static JSObject invokeJsObject(Object target, String methodName) throws Exception {
        Method method = MusicPlaybackService.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (JSObject) method.invoke(target);
    }

    private static void invokeVoid(Object target, String methodName) throws Exception {
        Method method = MusicPlaybackService.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }
}
