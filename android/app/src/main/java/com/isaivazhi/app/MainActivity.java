package com.isaivazhi.app;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 2026-05-11 #11: Native critical-path read.
        //
        // Capacitor Preferences plugin calls have been observed to take 2-3+
        // seconds on cold start due to bridge / plugin-executor warmup. That
        // delay landed exactly on the user's first play-tap because JS
        // restorePlaybackStateCritical was waiting on a Preferences.get / file
        // fetch. Bypass it entirely by reading the SharedPreferences directly
        // in onCreate — synchronous, ~5-20 ms, no Capacitor bridge involved.
        // Capacitor Preferences stores under "CapacitorStorage" by default.
        // Stash the JSON in a static field on MusicBridgePlugin so the very
        // first JS bridge call (getCachedInitialState) returns instantly from
        // in-memory cache without any disk I/O or bridge plumbing.
        try {
            SharedPreferences prefs = getSharedPreferences("CapacitorStorage", MODE_PRIVATE);
            String cached = prefs.getString("playback_state_current", null);
            MusicBridgePlugin.cacheInitialPlaybackState(cached);
            Log.i(TAG, "Native critical-path read: " + (cached != null ? cached.length() + " chars" : "null"));
        } catch (Throwable t) {
            Log.w(TAG, "Native critical-path read failed: " + t.getMessage());
            MusicBridgePlugin.cacheInitialPlaybackState(null);
        }

        registerPlugin(MusicBridgePlugin.class);
        super.onCreate(savedInstanceState);

        // 2026-05-11 #12: bind a direct WebView JavascriptInterface that
        // bypasses Capacitor's plugin pipeline entirely. The cached playback
        // state is now accessible from JS as a synchronous global:
        //   window._native.getCachedInitialState()
        // — no Promise, no bridge round-trip, no cold-start tax.
        // v11 proved that even an empty Capacitor PluginMethod call costs
        // 1-4s during the first 3s of init. addJavascriptInterface uses the
        // separate Android WebView JS bridge which doesn't share that tax.
        try {
            if (this.bridge != null && this.bridge.getWebView() != null) {
                this.bridge.getWebView().addJavascriptInterface(new NativeBridgeInterface(), "_native");
                Log.i(TAG, "NativeBridgeInterface attached as window._native");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to attach NativeBridgeInterface: " + t.getMessage());
        }

        requestStoragePermissions();
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.READ_MEDIA_AUDIO,
                                Manifest.permission.POST_NOTIFICATIONS
                        },
                        PERMISSION_REQUEST_CODE);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    // Permission just granted — notify JS to re-scan
                    if (this.bridge != null && this.bridge.getWebView() != null) {
                        this.bridge.getWebView().post(() -> {
                            this.bridge.getWebView().evaluateJavascript(
                                "window.dispatchEvent(new Event('storagePermissionGranted'));", null);
                        });
                    }
                    break;
                }
            }
        }
    }

    /**
     * Override onStop to prevent WebView from pausing audio when app is backgrounded.
     * By default, BridgeActivity pauses the WebView which kills audio playback.
     */
    @Override
    public void onStop() {
        // Do NOT call super.onStop() which pauses the WebView
        // Instead, only do the minimal Activity lifecycle
        try {
            // Keep the bridge alive but prevent WebView pause
            if (this.bridge != null && this.bridge.getWebView() != null) {
                // Intentionally skip WebView.onPause() to keep JS running
            }
        } catch (Exception e) {
            // ignore
        }
        // Call Activity.onStop() directly, skipping BridgeActivity's implementation
        // that would pause the WebView
        try {
            java.lang.reflect.Method onStop = android.app.Activity.class.getDeclaredMethod("onStop");
            onStop.setAccessible(true);
            onStop.invoke(this);
        } catch (Exception e) {
            // Fallback: just call super which may pause WebView
            super.onStop();
        }
    }

    /**
     * Ensure WebView resumes properly when app comes back to foreground.
     */
    @Override
    public void onResume() {
        super.onResume();
        if (this.bridge != null && this.bridge.getWebView() != null) {
            this.bridge.getWebView().onResume();
        }
    }
}
