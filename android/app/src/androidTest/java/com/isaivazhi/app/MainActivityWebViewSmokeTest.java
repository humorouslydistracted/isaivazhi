package com.isaivazhi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class MainActivityWebViewSmokeTest {

    @Rule
    public GrantPermissionRule permissionRule = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? GrantPermissionRule.grant(
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            )
            : GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE);

    private Context context;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        seedLibrary();
        seedPreferences();
    }

    @After
    public void tearDown() {
        context.stopService(new Intent(context, MusicPlaybackService.class));
        context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void launchRestoreSearchAndFavoriteFlow_workInAndroidWebView() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            waitForRestoreReady(scenario);

            clickSelector(scenario, ".tab[data-tab=\"songs\"]", 10000);
            waitForCondition(scenario, "songs panel renders",
                    "const panel = document.getElementById('panel-songs'); return !!panel && panel.textContent.includes('Alpha') && panel.textContent.includes('Gamma');",
                    15000);

            setInputValue(scenario, "searchInput", "ga", 10000);
            waitForCondition(scenario, "songs search filters to Gamma",
                    "const panel = document.getElementById('panel-songs'); return !!panel && panel.textContent.includes('Gamma');",
                    10000);

            setInputValue(scenario, "searchInput", "", 10000);
            waitForCondition(scenario, "songs search clears",
                    "const panel = document.getElementById('panel-songs'); return !!panel && panel.textContent.includes('Alpha') && panel.textContent.includes('Beta') && panel.textContent.includes('Gamma');",
                    10000);

            clickSelector(scenario, "#panel-songs .song-item:nth-of-type(3) .song-menu-btn", 10000);
            waitForCondition(scenario, "song popup opens",
                    "const menu = document.querySelector('.song-popup-menu'); return !!menu && menu.textContent.includes('Add to Favorites');",
                    10000);
            clickSelector(scenario, ".song-popup-item[data-action=\"togglefav\"]", 10000);
            waitForCondition(scenario, "favorite toast appears",
                    "const toast = document.getElementById('statusToast'); return !!toast && toast.textContent.includes('Added to favorites');",
                    10000);

            clickSelector(scenario, ".tab[data-tab=\"browse\"]", 10000);
            waitForCondition(scenario, "browse favorites tile updates",
                    "const root = document.getElementById('browse-tiles'); return !!root && root.textContent.includes('Favorites') && root.textContent.includes('2');",
                    10000);
        }
    }

    @Test
    public void notificationFavoriteAction_updatesWebViewAndStorage() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            waitForRestoreReady(scenario);
            primePlaybackServiceForCurrentSong(scenario);
            startPlaybackServiceAction(scenario, MusicPlaybackService.ACTION_TOGGLE_FAVORITE);

            waitForCondition(scenario, "favorite action updates mini-player heart",
                    "const btn = document.getElementById('favBtn'); return !!btn && btn.classList.contains('fav-active') && btn.textContent.includes('\\u2665');",
                    10000);
            clickSelector(scenario, ".tab[data-tab=\"browse\"]", 10000);
            waitForCondition(scenario, "favorite action updates browse favorites tile",
                    "const root = document.getElementById('browse-tiles'); return !!root && root.textContent.includes('Favorites') && root.textContent.includes('2');",
                    10000);

            String favoritesRaw = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
                    .getString("favorites", "");
            assertTrue("Expected beta.opus in favorites after native action", favoritesRaw.contains("beta.opus"));
        }
    }

    @Test
    public void notificationDismissAction_clearsUiAndSavedPlaybackState() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            waitForRestoreReady(scenario);
            primePlaybackServiceForCurrentSong(scenario);
            startPlaybackServiceAction(scenario, MusicPlaybackService.ACTION_DISMISS);

            waitForCondition(scenario, "dismiss action hides player",
                    "const np = document.getElementById('nowPlaying'); return !!np && np.style.display === 'none';",
                    10000);

            String playbackRaw = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
                    .getString("playback_state", null);
            assertTrue("Expected playback_state to be cleared after dismiss", playbackRaw == null);
        }
    }

    private void seedLibrary() throws Exception {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File library = new File(dir, "song_library.json");
        JSONObject payload = new JSONObject();
        payload.put("savedAt", System.currentTimeMillis());

        JSONArray songs = new JSONArray();
        songs.put(song("alpha.opus", "Alpha", "Artist One", "Album One", "/mock-device/music/alpha.opus", 1));
        songs.put(song("beta.opus", "Beta", "Artist Two", "Album Two", "/mock-device/music/beta.opus", 2));
        songs.put(song("gamma.opus", "Gamma", "Artist Three", "Album Three", "/mock-device/music/gamma.opus", 3));
        payload.put("songs", songs);

        try (FileOutputStream out = new FileOutputStream(library, false)) {
            out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private JSONObject song(String filename, String title, String artist, String album, String filePath, int modified) throws Exception {
        JSONObject song = new JSONObject();
        song.put("filename", filename);
        song.put("title", title);
        song.put("artist", artist);
        song.put("album", album);
        song.put("hasEmbedding", false);
        song.put("embeddingIndex", JSONObject.NULL);
        song.put("contentHash", "hash-" + filename);
        song.put("filePath", filePath);
        song.put("artPath", JSONObject.NULL);
        song.put("dateModified", modified);
        return song;
    }

    private void seedPreferences() throws Exception {
        JSONObject favorites = new JSONObject();
        favorites.put("filenames", new JSONArray().put("alpha.opus"));

        JSONObject profile = new JSONObject();
        JSONObject songs = new JSONObject();
        JSONObject beta = new JSONObject();
        beta.put("plays", 2);
        beta.put("skips", 0);
        beta.put("completions", 1);
        beta.put("fracs", new JSONArray().put(0.75));
        beta.put("lastPlayedAt", "2026-04-20T10:00:00.000Z");
        songs.put("beta.opus", beta);
        profile.put("songs", songs);
        profile.put("totalPlays", 2);
        profile.put("totalSkips", 0);

        JSONObject playback = new JSONObject();
        playback.put("currentFilename", "beta.opus");
        playback.put("currentTitle", "Beta");
        playback.put("currentArtist", "Artist Two");
        playback.put("currentAlbum", "Album Two");
        playback.put("currentFilePath", "/mock-device/music/beta.opus");
        playback.put("currentTime", 31);
        playback.put("duration", 120);
        playback.put("historyFilenames", new JSONArray().put("alpha.opus"));
        playback.put("queueFilenames", new JSONArray().put(new JSONObject()
                .put("filename", "gamma.opus")
                .put("similarity", 0.52)));
        playback.put("listenedFilenames", new JSONArray().put(new JSONObject()
                .put("filename", "alpha.opus")
                .put("listen_fraction", 0.5)));
        playback.put("sessionLabel", "Restored session");
        playback.put("recToggle", true);

        context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
                .edit()
                .putString("favorites", favorites.toString())
                .putString("profile_summary_v2", profile.toString())
                .putString("embedding_paused", "true")
                .putString("playback_state", playback.toString())
                .commit();
    }

    private void waitForRestoreReady(ActivityScenario<MainActivity> scenario) {
        waitForCondition(scenario, "mini-player title restore",
                "return !!document.getElementById('npTitle') && document.getElementById('npTitle').textContent.includes('Beta');",
                30000);
        waitForCondition(scenario, "mini-player artist restore",
                "return !!document.getElementById('npArtist') && document.getElementById('npArtist').textContent.includes('Artist Two');",
                30000);
    }

    private void primePlaybackServiceForCurrentSong(ActivityScenario<MainActivity> scenario) {
        waitForServicePluginBridge(10000);
        startPlaybackServiceAction(scenario, MusicPlaybackService.ACTION_UPDATE);
        waitForServiceInstance(10000);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            MusicPlaybackService service = MusicPlaybackService.instance;
            assertNotNull("MusicPlaybackService should be running", service);
            service.currentTitle = "Beta";
            service.currentArtist = "Artist Two";
            service.currentAlbum = "Album Two";
            service.currentFilePath = "/mock-device/music/beta.opus";
            service.beginPlaybackInstance();
        });
    }

    private void startPlaybackServiceAction(ActivityScenario<MainActivity> scenario, String action) {
        Intent intent = new Intent(context, MusicPlaybackService.class);
        intent.setAction(action);
        if (MusicPlaybackService.ACTION_UPDATE.equals(action)) {
            intent.putExtra("title", "Beta");
            intent.putExtra("artist", "Artist Two");
            intent.putExtra("album", "Album Two");
            intent.putExtra("isPlaying", false);
        }
        scenario.onActivity(activity -> activity.startService(intent));
    }

    private void waitForServicePluginBridge(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (MusicPlaybackService.pluginRef != null) return;
            SystemClock.sleep(200);
        }
        throw new AssertionError("Timed out waiting for MusicPlaybackService.pluginRef");
    }

    private void waitForServiceInstance(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (MusicPlaybackService.instance != null) return;
            SystemClock.sleep(200);
        }
        throw new AssertionError("Timed out waiting for MusicPlaybackService.instance");
    }

    private void clickSelector(ActivityScenario<MainActivity> scenario, String cssSelector, long timeoutMs) {
        waitForCondition(scenario, "selector ready: " + cssSelector,
                "return !!document.querySelector(" + js(cssSelector) + ");",
                timeoutMs);
        String result = evaluateJavascript(scenario,
                "(function(){ const el = document.querySelector(" + js(cssSelector) + "); if (!el) return false; el.click(); return true; })();");
        assertEquals("true", result);
    }

    private void setInputValue(ActivityScenario<MainActivity> scenario, String id, String value, long timeoutMs) {
        waitForCondition(scenario, "input ready: " + id,
                "return !!document.getElementById(" + js(id) + ");",
                timeoutMs);
        String result = evaluateJavascript(scenario,
                "(function(){ " +
                        "const el = document.getElementById(" + js(id) + "); " +
                        "if (!el) return false; " +
                        "el.focus(); " +
                        "el.value = " + js(value) + "; " +
                        "el.dispatchEvent(new Event('input', { bubbles: true })); " +
                        "el.dispatchEvent(new Event('change', { bubbles: true })); " +
                        "return true; " +
                        "})();");
        assertEquals("true", result);
    }

    private void waitForCondition(ActivityScenario<MainActivity> scenario, String description, String jsCondition, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                String result = evaluateJavascript(
                        scenario,
                        "(function(){ try { " + jsCondition + " } catch (e) { return false; } })();"
                );
                if ("true".equals(result)) return;
            } catch (Throwable ignored) {
                // Ignore transient DOM/WebView timing while polling.
            }
            SystemClock.sleep(400);
        }
        throw new AssertionError("Timed out waiting for condition: " + description);
    }

    private String evaluateJavascript(ActivityScenario<MainActivity> scenario, String script) {
        AtomicReference<String> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        scenario.onActivity(activity -> {
            try {
                WebView webView = findWebView(activity.getWindow().getDecorView());
                assertNotNull("Could not find WebView in activity", webView);
                webView.post(() -> webView.evaluateJavascript(script, value -> {
                    resultRef.set(value);
                    latch.countDown();
                }));
            } catch (Throwable t) {
                errorRef.set(t);
                latch.countDown();
            }
        });

        try {
            assertTrue("Timed out evaluating javascript", latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while evaluating javascript", e);
        }

        if (errorRef.get() != null) {
            throw new AssertionError("JavaScript evaluation failed", errorRef.get());
        }
        return resultRef.get();
    }

    private WebView findWebView(View root) {
        if (root instanceof WebView) return (WebView) root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            WebView found = findWebView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private String js(String value) {
        return JSONObject.quote(value);
    }
}
