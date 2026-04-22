package com.isaivazhi.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(MusicBridgePlugin.class);
        super.onCreate(savedInstanceState);
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
