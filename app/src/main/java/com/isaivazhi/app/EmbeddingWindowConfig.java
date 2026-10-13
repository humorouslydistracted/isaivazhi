package com.isaivazhi.app;

/**
 * CLAP 10 s window placement for configurable split counts (3, 5, 7).
 * Must stay in sync with tools/embeddings/embedding_config.py.
 *
 * Strategy "stratified_v2":
 *  1. Trim the first and last {@link #EDGE_TRIM_FRACTION} of the song (capped
 *     at {@link #EDGE_TRIM_MAX_SECONDS}) — intros, fades, applause and
 *     talking are the usual source of off-sound embeddings.
 *  2. Split what is left into N equal sections and place one window per
 *     section, at an offset inside the section derived from the song's
 *     content hash. Same song → same windows (re-embeds are reproducible),
 *     but different songs are not all sampled at the same fixed fractions.
 *  3. A window that is mostly silent is moved to another spot in its own
 *     section ({@link #alternativeStarts}); the least silent one is kept.
 */
public final class EmbeddingWindowConfig {

    private EmbeddingWindowConfig() {}

    public static final String STRATEGY = "stratified_v2";

    public static final int[] ALLOWED_SPLIT_COUNTS = {3, 5, 7};

    public static final double EDGE_TRIM_FRACTION = 0.08;
    public static final double EDGE_TRIM_MAX_SECONDS = 30.0;

    /** 100 ms frames; a frame below this RMS (~ -48 dBFS) counts as silent. */
    public static final double SILENT_FRAME_SECONDS = 0.1;
    public static final float SILENT_FRAME_RMS = 0.004f;
    /** A window with more than this share of silent frames is moved. */
    public static final float MAX_SILENT_FRACTION = 0.5f;

    private static final float[] POSITIONS_3 = {0.20f, 0.50f, 0.80f};

    public static int normalizeSplitCount(int splitCount) {
        if (splitCount == 3 || splitCount == 5 || splitCount == 7) {
            return splitCount;
        }
        return 3;
    }

    /** Legacy fixed centre positions (before stratified_v2); kept for reference/tools. */
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

    /** Seed from the 16-hex-char content hash; 0 when absent or malformed. */
    public static long seedFromContentHash(String contentHash) {
        if (contentHash == null || contentHash.isEmpty()) return 0L;
        try {
            String hex = contentHash.length() > 16 ? contentHash.substring(0, 16) : contentHash;
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** SplitMix64 finaliser; identical in embedding_config.py. */
    static long splitMix64(long x) {
        long z = x + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform [0, 1) for section {@code index}. */
    static double unitFor(long seed, int index) {
        return (splitMix64(seed + index) >>> 11) * 0x1.0p-53;
    }

    private static double[] usableRange(double durationSec, double windowSec) {
        double trim = Math.min(durationSec * EDGE_TRIM_FRACTION, EDGE_TRIM_MAX_SECONDS);
        double lo = trim;
        double hi = durationSec - trim;
        if (hi - lo < windowSec) {
            lo = 0.0;
            hi = durationSec;
        }
        return new double[] {lo, hi};
    }

    private static double clampStart(double start, double durationSec, double windowSec) {
        double max = Math.max(0.0, durationSec - windowSec);
        return Math.max(0.0, Math.min(start, max));
    }

    /**
     * Window start times (seconds) for a song of {@code durationSec}. Songs no
     * longer than one window get a single window at 0.
     */
    public static double[] planWindowStarts(double durationSec, double windowSec, int splitCount, long seed) {
        if (!(durationSec > windowSec)) return new double[] {0.0};
        int n = normalizeSplitCount(splitCount);
        double[] range = usableRange(durationSec, windowSec);
        double section = (range[1] - range[0]) / n;
        double slack = section - windowSec;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double secStart = range[0] + i * section;
            double start = slack > 0
                    ? secStart + unitFor(seed, i) * slack
                    : secStart + section / 2.0 - windowSec / 2.0;
            out[i] = clampStart(start, durationSec, windowSec);
        }
        return out;
    }

    /**
     * Other places to try for window {@code index} if it turns out silent:
     * the start, middle and end of its own section (whichever differ from
     * the planned start by at least a second). Empty when the section has
     * no room to move.
     */
    public static double[] alternativeStarts(double durationSec, double windowSec, int splitCount, long seed, int index) {
        if (!(durationSec > windowSec)) return new double[0];
        int n = normalizeSplitCount(splitCount);
        if (index < 0 || index >= n) return new double[0];
        double[] range = usableRange(durationSec, windowSec);
        double section = (range[1] - range[0]) / n;
        double slack = section - windowSec;
        if (slack < 1.0) return new double[0];
        double planned = planWindowStarts(durationSec, windowSec, n, seed)[index];
        double secStart = range[0] + index * section;
        double[] candidates = {secStart + slack / 2.0, secStart + slack, secStart};
        double[] tmp = new double[candidates.length];
        int count = 0;
        for (double c : candidates) {
            double s = clampStart(c, durationSec, windowSec);
            if (Math.abs(s - planned) >= 1.0) tmp[count++] = s;
        }
        double[] out = new double[count];
        System.arraycopy(tmp, 0, out, 0, count);
        return out;
    }

    /** Share of 100 ms frames in {@code samples} quieter than {@link #SILENT_FRAME_RMS}. */
    public static float silentFraction(float[] samples, int validLength, int sampleRate) {
        int len = Math.min(validLength, samples.length);
        int frame = Math.max(1, (int) Math.round(sampleRate * SILENT_FRAME_SECONDS));
        int frames = len / frame;
        if (frames == 0) return 1f;
        double threshold2 = (double) SILENT_FRAME_RMS * SILENT_FRAME_RMS;
        int silent = 0;
        for (int f = 0; f < frames; f++) {
            double sum = 0.0;
            int base = f * frame;
            for (int i = 0; i < frame; i++) {
                double x = samples[base + i];
                sum += x * x;
            }
            if (sum / frame < threshold2) silent++;
        }
        return (float) silent / frames;
    }
}
