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
 *  4. Tracks under {@link #SHORT_TRACK_SECONDS}: no edge trim; leading and
 *     trailing silence is cut instead, only as many windows as fit with at
 *     most 50% overlap are used, and audio shorter than one window is
 *     looped to fill it rather than padded with silence.
 */
public final class EmbeddingWindowConfig {

    private EmbeddingWindowConfig() {}

    public static final String STRATEGY = "stratified_v2";

    public static final int[] ALLOWED_SPLIT_COUNTS = {3, 5, 7};

    public static final double EDGE_TRIM_FRACTION = 0.08;
    public static final double EDGE_TRIM_MAX_SECONDS = 30.0;
    /** Below this, tracks are decoded whole: no edge trim, silence trimmed, fewer and looped windows. */
    public static final double SHORT_TRACK_SECONDS = 30.0;

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

    /**
     * Span that windows are placed in. Songs of {@link #SHORT_TRACK_SECONDS}
     * or more: the song minus the trimmed edges. Shorter tracks have too
     * little audio to give any up, so they use the audible span instead
     * (leading/trailing silence removed, see {@link #audibleRange}).
     */
    public static double[] placementRange(double durationSec, double windowSec,
                                          double audibleStartSec, double audibleEndSec) {
        if (durationSec >= SHORT_TRACK_SECONDS) return usableRange(durationSec, windowSec);
        double lo = Math.max(0.0, Math.min(audibleStartSec, durationSec));
        double hi = Math.max(lo, Math.min(audibleEndSec, durationSec));
        if (hi - lo <= 0.0) return new double[] {0.0, Math.max(0.0, durationSec)};
        return new double[] {lo, hi};
    }

    /**
     * Windows that fit {@code rangeSec} with at most 50% overlap, capped at
     * the configured split count: 1 below 15 s, 2 below 20 s, 3 below 25 s…
     */
    public static int effectiveWindowCount(double rangeSec, double windowSec, int splitCount) {
        int n = normalizeSplitCount(splitCount);
        if (!(rangeSec > windowSec)) return 1;
        int fit = 1 + (int) Math.floor((rangeSec - windowSec) / (windowSec / 2.0));
        return Math.max(1, Math.min(n, fit));
    }

    private static double clampToRange(double start, double lo, double hi, double windowSec) {
        double max = Math.max(lo, hi - windowSec);
        return Math.max(lo, Math.min(start, max));
    }

    /** {@link #planWindowStarts(double, double, int, long, double, double)} with the whole song audible. */
    public static double[] planWindowStarts(double durationSec, double windowSec, int splitCount, long seed) {
        return planWindowStarts(durationSec, windowSec, splitCount, seed, 0.0, durationSec);
    }

    /**
     * Window start times (seconds). The result can be shorter than the split
     * count for short tracks. When the placement range is shorter than one
     * window there is a single start at the range start; the caller fills
     * the window by looping that range ({@link #cutWindow}).
     */
    public static double[] planWindowStarts(double durationSec, double windowSec, int splitCount, long seed,
                                            double audibleStartSec, double audibleEndSec) {
        double[] range = placementRange(durationSec, windowSec, audibleStartSec, audibleEndSec);
        double lo = range[0];
        double hi = range[1];
        double len = hi - lo;
        if (!(len > windowSec)) return new double[] {lo};
        int n = effectiveWindowCount(len, windowSec, splitCount);
        double section = len / n;
        double slack = section - windowSec;
        double[] out = new double[n];
        if (!(slack > 0)) {
            // Sections narrower than a window: spread evenly from the first
            // to the last possible start so neighbours overlap at most 50%.
            double span = len - windowSec;
            for (int i = 0; i < n; i++) {
                double start = n == 1 ? lo + span / 2.0 : lo + i * span / (n - 1);
                out[i] = clampToRange(start, lo, hi, windowSec);
            }
            return out;
        }
        for (int i = 0; i < n; i++) {
            double start = lo + i * section + unitFor(seed, i) * slack;
            out[i] = clampToRange(start, lo, hi, windowSec);
        }
        return out;
    }

    public static double[] alternativeStarts(double durationSec, double windowSec, int splitCount, long seed, int index) {
        return alternativeStarts(durationSec, windowSec, splitCount, seed, index, 0.0, durationSec);
    }

    /**
     * Other places to try for window {@code index} if it turns out silent:
     * the middle, end and start of its own section (whichever differ from
     * the planned start by at least a second). Empty when the section has
     * no room to move.
     */
    public static double[] alternativeStarts(double durationSec, double windowSec, int splitCount, long seed, int index,
                                             double audibleStartSec, double audibleEndSec) {
        double[] range = placementRange(durationSec, windowSec, audibleStartSec, audibleEndSec);
        double lo = range[0];
        double hi = range[1];
        double len = hi - lo;
        if (!(len > windowSec)) return new double[0];
        int n = effectiveWindowCount(len, windowSec, splitCount);
        if (index < 0 || index >= n) return new double[0];
        double section = len / n;
        double slack = section - windowSec;
        if (slack < 1.0) return new double[0];
        double planned = planWindowStarts(durationSec, windowSec, splitCount, seed, audibleStartSec, audibleEndSec)[index];
        double secStart = lo + index * section;
        double[] candidates = {secStart + slack / 2.0, secStart + slack, secStart};
        double[] tmp = new double[candidates.length];
        int count = 0;
        for (double c : candidates) {
            double s = clampToRange(c, lo, hi, windowSec);
            if (Math.abs(s - planned) >= 1.0) tmp[count++] = s;
        }
        double[] out = new double[count];
        System.arraycopy(tmp, 0, out, 0, count);
        return out;
    }

    /**
     * [start, end] seconds between the first and last 100 ms frame that is
     * not silent. The whole clip when nothing is audible.
     */
    public static double[] audibleRange(float[] samples, int validLength, int sampleRate) {
        int len = Math.min(validLength, samples.length);
        double total = len / (double) sampleRate;
        int frame = Math.max(1, (int) Math.round(sampleRate * SILENT_FRAME_SECONDS));
        int frames = len / frame;
        double threshold2 = (double) SILENT_FRAME_RMS * SILENT_FRAME_RMS;
        int first = -1;
        int last = -1;
        for (int f = 0; f < frames; f++) {
            double sum = 0.0;
            int base = f * frame;
            for (int i = 0; i < frame; i++) {
                double x = samples[base + i];
                sum += x * x;
            }
            if (sum / frame >= threshold2) {
                if (first < 0) first = f;
                last = f;
            }
        }
        if (first < 0) return new double[] {0.0, total};
        double end = (last == frames - 1) ? total : (last + 1) * (double) frame / sampleRate;
        return new double[] {first * (double) frame / sampleRate, end};
    }

    /**
     * One window of {@code windowSamples} starting at {@code startSec}. When
     * the placement range [{@code rangeStartSec}, {@code rangeEndSec}] is
     * shorter than a window, that range is repeated to fill it instead of
     * padding with silence.
     */
    public static float[] cutWindow(float[] audio, int validLength, int sampleRate, double startSec,
                                    double rangeStartSec, double rangeEndSec, int windowSamples) {
        int len = Math.min(validLength, audio.length);
        float[] out = new float[windowSamples];
        if (len <= 0) return out;
        int lo = Math.max(0, Math.min(len - 1, (int) Math.round(rangeStartSec * sampleRate)));
        int hi = Math.max(lo + 1, Math.min(len, (int) Math.round(rangeEndSec * sampleRate)));
        if (hi - lo < windowSamples) {
            int seg = hi - lo;
            for (int i = 0; i < windowSamples; i += seg) {
                System.arraycopy(audio, lo, out, i, Math.min(seg, windowSamples - i));
            }
            return out;
        }
        int start = (int) Math.round(startSec * sampleRate);
        start = Math.max(0, Math.min(start, len - windowSamples));
        System.arraycopy(audio, start, out, 0, windowSamples);
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
