package com.isaivazhi.app;

import android.webkit.JavascriptInterface;

/**
 * Direct WebView <-> Java bridge that bypasses Capacitor's plugin pipeline.
 *
 * Why this exists (2026-05-11 #12):
 * After 11 build iterations chasing cold-start play-tap latency, the captured
 * logs proved that ANY Capacitor plugin call during the first ~3 seconds of
 * init pays a bridge cold-start tax of 1-4 seconds — regardless of what the
 * plugin method does. Even an empty plugin method returning a cached static
 * field took 4090 ms in v11.
 *
 * Android's WebView has a separate JavaScript bridge (the @JavascriptInterface
 * annotation) that does NOT share Capacitor's plugin executor. Methods on a
 * bound JS interface object are called synchronously from JS (no Promise),
 * dispatched directly into the JS execution thread by the WebView. There is
 * no cold-start tax because there is no plugin pipeline.
 *
 * Usage from JS:
 *   const json = window._native.getCachedInitialState();
 *   // json is a string (or empty string if no cache), available synchronously
 *
 * This interface MUST be added to the WebView AFTER super.onCreate(), because
 * BridgeActivity creates the WebView during onCreate. See MainActivity.onCreate.
 *
 * @JavascriptInterface methods run on a WebView-internal thread (NOT the JS
 * thread). Return values are passed back to JS synchronously. Only String,
 * boolean, and numeric primitives are safe to return.
 */
public class NativeBridgeInterface {

    /**
     * Returns the cached initial playback state JSON read by
     * MainActivity.onCreate from SharedPreferences("CapacitorStorage").
     *
     * Returns "" if no cached state exists (fresh install, or the key hasn't
     * been written yet because savePlaybackState hasn't run this session).
     */
    @JavascriptInterface
    public String getCachedInitialState() {
        String cached = MusicBridgePlugin.getCachedInitialPlaybackStateStatic();
        return cached != null ? cached : "";
    }
}
