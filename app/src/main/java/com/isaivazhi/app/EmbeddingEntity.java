package com.isaivazhi.app;

/**
 * Plain in-memory representation of one stable-embedding row.
 *
 * `vec` is the raw little-endian Float32 byte payload (dim * 4 bytes).
 * Storing as BLOB skips all base64 / JSON.stringify work on every mutation.
 */
public final class EmbeddingEntity {
    public String contentHash = "";
    public String filepath = "";
    public String filename = "";
    public String artist = "";
    public String album = "";
    public long timestamp = 0L;
    public int dim = 0;
    public byte[] vec = new byte[0];
    /** Split count requested when this vector was made; 0 = not known (embedded before tracking). */
    public int splitCount = 0;
    /** Clips actually averaged (fewer than [splitCount] for short songs); 0 = not known. */
    public int clipCount = 0;
    /** {@link #SOURCE_RECORDED}, {@link #SOURCE_LABELLED}, or "" when unknown. */
    public String splitSource = "";

    public static final String SOURCE_RECORDED = "recorded";
    public static final String SOURCE_LABELLED = "labelled";
}
