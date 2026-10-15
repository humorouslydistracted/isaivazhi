package com.isaivazhi.app;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides which stored paths belong to songs that were moved, so the path
 * index stops listing the old location next to the new one.
 *
 * A stored path is "moved away" only when all of these hold:
 *  - it is not in the current library,
 *  - the file no longer exists on disk,
 *  - another path with the same content hash IS in the library.
 *
 * Paths whose file still exists (genuine duplicates, excluded folders) are
 * never touched, and nothing here deletes an embedding.
 */
public final class MovedPathHealer {

    private MovedPathHealer() {}

    public static final class Move {
        public final String contentHash;
        public final String oldPath;
        public final String newPath;
        /** True when the embedding row itself pointed at [oldPath]. */
        public final boolean embeddingRepointed;

        Move(String contentHash, String oldPath, String newPath, boolean embeddingRepointed) {
            this.contentHash = contentHash;
            this.oldPath = oldPath;
            this.newPath = newPath;
            this.embeddingRepointed = embeddingRepointed;
        }
    }

    /**
     * @param indexPathsByHash content hash → every path the path index holds for it
     * @param embeddingPathByHash content hash → the embedding row's own filepath
     * @param libraryPaths filepaths of every song currently in the library
     * @param fileExists disk check, injectable for tests
     */
    public static List<Move> plan(
            Map<String, List<String>> indexPathsByHash,
            Map<String, String> embeddingPathByHash,
            Set<String> libraryPaths,
            Predicate<String> fileExists
    ) {
        List<Move> out = new ArrayList<>();
        Set<String> hashes = new LinkedHashSet<>(indexPathsByHash.keySet());
        hashes.addAll(embeddingPathByHash.keySet());
        for (String hash : hashes) {
            if (hash == null || hash.isEmpty()) continue;
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            List<String> indexed = indexPathsByHash.get(hash);
            if (indexed != null) paths.addAll(indexed);
            String embPath = embeddingPathByHash.get(hash);
            if (embPath != null && !embPath.isEmpty()) paths.add(embPath);
            paths.remove("");
            paths.remove(null);
            if (paths.size() < 2) continue;

            List<String> present = new ArrayList<>();
            for (String p : paths) if (libraryPaths.contains(p)) present.add(p);
            if (present.isEmpty()) continue;
            Collections.sort(present);

            for (String p : paths) {
                if (libraryPaths.contains(p) || fileExists.test(p)) continue;
                out.add(new Move(hash, p, pickNewPath(p, present), p.equals(embPath)));
            }
        }
        return out;
    }

    /** Prefer a present copy with the same file name; otherwise the first in path order. */
    static String pickNewPath(String oldPath, List<String> present) {
        String name = new File(oldPath).getName();
        for (String p : present) {
            if (new File(p).getName().equalsIgnoreCase(name)) return p;
        }
        return present.get(0);
    }
}
