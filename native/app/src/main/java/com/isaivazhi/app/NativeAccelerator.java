package com.isaivazhi.app;

import android.util.Log;

/**
 * JNI wrapper for native dot-product operations using ARM NEON SIMD.
 *
 * The native library (libisaivazhi_native.so) is loaded once at class
 * initialization. If loading fails (missing ABI variant, NDK build skipped,
 * stripped APK, etc.), the wrapper transparently degrades — every call
 * returns false and callers fall back to their own Java implementation.
 *
 * No Android API level checks: the .so is built for arm64-v8a, armeabi-v7a,
 * x86, and x86_64, so any device that can run the APK can load it. The
 * loadLibrary call itself is the only feature gate.
 */
public final class NativeAccelerator {
    private static final String TAG = "NativeAccelerator";
    private static final boolean LIBRARY_LOADED;
    private static final boolean NEON_ACTIVE;

    static {
        boolean loaded = false;
        boolean neon = false;
        try {
            System.loadLibrary("isaivazhi_native");
            loaded = true;
            try {
                neon = hasNeonNative() == 1;
            } catch (Throwable t) {
                // Symbol missing or ABI mismatch — keep loaded=true but mark NEON inactive.
                Log.w(TAG, "hasNeonNative() failed: " + t.getMessage());
            }
            Log.i(TAG, "Native acceleration loaded (NEON=" + neon + ")");
        } catch (Throwable t) {
            // UnsatisfiedLinkError, SecurityException, anything else.
            // Java fallback will be used. This is not a fatal error.
            Log.w(TAG, "Native library not available, using Java fallback: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        LIBRARY_LOADED = loaded;
        NEON_ACTIVE = neon;
    }

    public static boolean isAvailable() {
        return LIBRARY_LOADED;
    }

    public static boolean isNeonActive() {
        return NEON_ACTIVE;
    }

    /**
     * Compute dot products of {@code query} against each row of {@code vectors}
     * (count rows × dim columns, row-major). Writes count floats into {@code output}.
     *
     * @return true on success, false if native is unavailable or the call failed.
     *         Caller should fall back to a Java implementation when this returns false.
     */
    public static boolean dotProductBatch(
            float[] query, float[] vectors, int count, int dim, float[] output) {
        if (!LIBRARY_LOADED) return false;
        if (query == null || vectors == null || output == null) return false;
        if (count <= 0 || dim <= 0) return false;
        if (query.length != dim) return false;
        if ((long) count * dim > vectors.length) return false;
        if (output.length < count) return false;

        try {
            dotProductBatchNative(query, vectors, count, dim, output);
            return true;
        } catch (Throwable t) {
            // UnsatisfiedLinkError can happen if the library is partial.
            // OutOfMemoryError if pinning fails. ArrayIndexOutOfBoundsException
            // would only happen if our pre-checks are wrong, but catch it anyway.
            Log.w(TAG, "dotProductBatch native call failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static native void dotProductBatchNative(
            float[] query, float[] vectors, int count, int dim, float[] output);

    private static native int hasNeonNative();

    private NativeAccelerator() {
        // Utility class — no instances.
    }
}
