package com.isaivazhi.app;

/**
 * Plain in-memory representation of one (filepath → contentHash) mapping
 * from the embedding_path_index table. Mirrors what `_path_index` carried in
 * the legacy JSON meta.
 */
public final class EmbeddingPathIndexEntity {
    public String filepath = "";
    public String contentHash = "";
}
