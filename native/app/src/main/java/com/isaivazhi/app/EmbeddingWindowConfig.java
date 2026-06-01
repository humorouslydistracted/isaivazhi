package com.isaivazhi.app;

/**
 * CLAP 10 s window center positions for configurable split counts (3, 5, 7).
 * Must stay in sync with tools/embeddings/embedding_config.py.
 */
public final class EmbeddingWindowConfig {

    private EmbeddingWindowConfig() {}

    public static final int[] ALLOWED_SPLIT_COUNTS = {3, 5, 7};

    private static final float[] POSITIONS_3 = {0.20f, 0.50f, 0.80f};

    public static int normalizeSplitCount(int splitCount) {
        if (splitCount == 3 || splitCount == 5 || splitCount == 7) {
            return splitCount;
        }
        return 3;
    }

    public static float[] windowPositions(int splitCount) {
        int n = normalizeSplitCount(splitCount);
        if (n == 3) {
            return POSITIONS_3.clone();
        }
        float[] out = new float[n];
        for (int k = 0; k < n; k++) {
            out[k] = (k + 1f) / (n + 1f);
        }
        return out;
    }

    public static String positionsLabel(int splitCount) {
        int n = normalizeSplitCount(splitCount);
        return n + " × 10 s per song";
    }
}
