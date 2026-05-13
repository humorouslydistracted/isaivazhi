package com.isaivazhi.app.engine

import com.isaivazhi.app.NativeAccelerator
import kotlin.math.sqrt

/**
 * Recommender — picks the next K songs to play based on similarity to a
 * query song's embedding.
 *
 * Backed by sqlite-vec via [EmbeddingDbFacade] (or NativeAccelerator NEON
 * fallback) for the top-K relevance step, then an MMR rerank applies
 * diversity penalty `(1 - lambda) * maxRedundancy` over a 3K candidate set.
 *
 * `lambda = 1 - adventurous`:
 *   - adventurous=0.0 → lambda=1.0 → pure relevance, top-K = first K by sim
 *   - adventurous=0.8 (default) → lambda=0.2 → 80% diversity penalty
 *   - adventurous=1.0 → lambda=0.0 → pure diversity (sim ignored)
 *
 * The same formula the Capacitor build settled on in batch #3 (after the
 * earlier inversion was caught).
 */
class Recommender(
    private val embeddingDb: EmbeddingDbFacade,
) {

    suspend fun recommendUpcoming(
        currentSong: Song,
        library: List<Song>,
        k: Int = 50,
        adventurous: Float = 0.8f,
        extraExcludeFilenames: Set<String> = emptySet(),
        // Push #63: songs whose taste signal classifies them as strongly
        // negative (top 18% by directNegativeStrength with floor ≥ 1.5).
        // Unconditionally excluded from the upcoming pool. Mirrors
        // Capacitor engine-taste.js:751 — the recommendation policy
        // pass that prunes hard-blocked rows before MMR.
        hardBlockedFilenames: Set<String> = emptySet(),
    ): List<Song> {
        val lambda = (1f - adventurous).coerceIn(0f, 1f)
        val excludeHashes = buildList {
            currentSong.contentHash?.takeIf { it.isNotBlank() }?.let { add(it) }
        }

        // Step 1: top-K*3 relevance candidates from sqlite-vec.
        val overFetch = (k * 3).coerceAtLeast(k + 10)
        val candidates = embeddingDb.nearestNeighborsForFilename(
            queryFilename = currentSong.filename,
            k = overFetch,
            excludeHashes = excludeHashes,
        )
        if (candidates.isEmpty()) return emptyList()

        // Step 2: load candidate vectors in one batch for MMR redundancy
        // scoring. ~150 vectors × 2 KB = 300 KB over the bridge — small.
        val byFilename = library.associateBy { it.filename }
        val byHash: Map<String, Song> = library
            .filter { it.contentHash != null }
            .associateBy { it.contentHash!! }

        val viable = candidates.mapNotNull { row ->
            if (row.filename in extraExcludeFilenames) return@mapNotNull null
            if (row.filename in hardBlockedFilenames) return@mapNotNull null
            val song = byFilename[row.filename]
                ?: row.contentHash.takeIf { it.isNotBlank() }?.let { byHash[it] }
                ?: return@mapNotNull null
            if (song.filePath == null) return@mapNotNull null
            song to row
        }
        if (viable.isEmpty()) return emptyList()

        // Pure-relevance fast path: lambda >= 0.95 means MMR penalty is
        // effectively zero, skip the batch vector load entirely.
        if (lambda >= 0.95f) {
            return viable.asSequence().map { it.first }.take(k).toList()
        }

        val candidateHashes = viable.map { it.second.contentHash }.filter { it.isNotBlank() }
        val vecs = embeddingDb.getVecsByHashes(candidateHashes)
        if (vecs.isEmpty()) {
            // Vector batch fetch failed — fall back to pure relevance.
            return viable.asSequence().map { it.first }.take(k).toList()
        }

        // Step 3: MMR rerank.
        val poolEntries = viable.mapNotNull { (song, row) ->
            val v = vecs[row.contentHash] ?: return@mapNotNull null
            PoolEntry(song = song, similarity = row.similarity, vec = v)
        }
        if (poolEntries.isEmpty()) return viable.asSequence().map { it.first }.take(k).toList()

        return mmrRerank(poolEntries, k, lambda).map { it.song }
    }

    /**
     * Push #53: MMR rerank in O(K · pool · dim) instead of the previous
     * O(K · pool · K · dim) implementation. The old code recomputed
     * `selected.maxOf { sel -> cosine(cand, sel) }` for every (cand, iteration)
     * pair, which scales as K² · pool. For K=50, pool=150 that's ≈ 187,500
     * cosine ops per call — observed at 23 s on-device and triggering ANR
     * via main-thread stalls from GC pressure (see push #53 entry).
     *
     * The fix maintains a running `maxRed[i]` per pool entry. When a new
     * candidate is picked, we update each pool entry's maxRed against the
     * single newly-picked vector (one batched dot product over the whole
     * pool). That's K iterations × one batch op = O(K · pool · dim).
     *
     * NativeAccelerator.dotProductBatch routes through NEON SIMD when the
     * native library is available; a Kotlin loop is used as fallback. The
     * pool is pre-normalized to unit length so cosine ≡ dot.
     */
    private fun mmrRerank(
        pool: List<PoolEntry>,
        k: Int,
        lambda: Float,
    ): List<PoolEntry> {
        val poolSize = pool.size
        if (poolSize == 0) return emptyList()
        val dim = pool[0].vec.size
        if (dim == 0) return pool.take(k)

        // Flatten + L2-normalize the pool into a row-major float[]. Cosine
        // between unit vectors equals their dot product.
        val flat = FloatArray(poolSize * dim)
        for (i in 0 until poolSize) {
            val src = pool[i].vec
            var n2 = 0f
            val srcLen = minOf(dim, src.size)
            for (j in 0 until srcLen) n2 += src[j] * src[j]
            val inv = if (n2 > 0f) 1f / kotlin.math.sqrt(n2) else 0f
            val base = i * dim
            for (j in 0 until srcLen) flat[base + j] = src[j] * inv
        }

        val maxRed = FloatArray(poolSize)
        val taken = BooleanArray(poolSize)
        val scratch = FloatArray(poolSize)
        val pickedQuery = FloatArray(dim)
        val picked = IntArray(k)
        var pickedCount = 0
        val target = minOf(k, poolSize)

        while (pickedCount < target) {
            // Pick the best remaining candidate by MMR score.
            var bestIdx = -1
            var bestScore = -Float.MAX_VALUE
            for (i in 0 until poolSize) {
                if (taken[i]) continue
                val s = lambda * pool[i].similarity - (1f - lambda) * maxRed[i]
                if (s > bestScore) {
                    bestScore = s
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break
            taken[bestIdx] = true
            picked[pickedCount++] = bestIdx

            // Update maxRed[*] using cosine to the newly-picked vector.
            // Copy the pick into a query buffer (NEON path needs a clean
            // contiguous query of length dim).
            val pickedBase = bestIdx * dim
            System.arraycopy(flat, pickedBase, pickedQuery, 0, dim)
            val usedNative = NativeAccelerator.dotProductBatch(
                pickedQuery, flat, poolSize, dim, scratch
            )
            if (!usedNative) {
                // Kotlin fallback dot product loop. Still O(pool · dim)
                // per iteration — about 50× faster than the old K²·pool
                // approach because we don't re-scan the selected set.
                for (i in 0 until poolSize) {
                    if (taken[i]) continue
                    var d = 0f
                    val base = i * dim
                    for (j in 0 until dim) d += pickedQuery[j] * flat[base + j]
                    scratch[i] = d
                }
            }
            for (i in 0 until poolSize) {
                if (taken[i]) continue
                val c = scratch[i]
                if (c > maxRed[i]) maxRed[i] = c
            }
        }
        val out = ArrayList<PoolEntry>(pickedCount)
        for (i in 0 until pickedCount) out += pool[picked[i]]
        return out
    }

    /**
     * Push #42 Tier 3L: soft refresh of the upcoming queue after a
     * qualified listen. Mirrors Capacitor `_softRefreshQueue`.
     *
     * Behavior:
     *   - Frozen zone (positions 0..4 of `oldTail`): untouched.
     *   - Stable zone (positions 5..14): kept but re-sorted by similarity
     *     to [currentSong]. No insertions or deletions.
     *   - Fluid zone (positions 15+): replaced by recommender top-K for
     *     [currentSong], de-duped against frozen+stable.
     *
     * If [oldTail] is shorter than 5 songs, returns it as-is (nothing
     * meaningful to refresh). If shorter than 15, only the frozen zone
     * exists and the rest is appended fresh from recommender.
     */
    suspend fun softRefreshTail(
        currentSong: Song,
        oldTail: List<Song>,
        library: List<Song>,
        adventurous: Float = 0.8f,
    ): List<Song> {
        if (oldTail.size < 5) return oldTail
        val frozenZone = oldTail.subList(0, minOf(5, oldTail.size))
        // Stable zone: positions 5..14 re-sorted by similarity to currentSong.
        // Use sqlite-vec to score the songs in the stable zone, then sort.
        val stableSrc = if (oldTail.size > 5) oldTail.subList(5, minOf(15, oldTail.size)) else emptyList()
        val stableSorted: List<Song> = if (stableSrc.isEmpty()) {
            emptyList()
        } else {
            // Query the recommender for top-K similar; intersect with the
            // current stable zone to derive the new order. Songs that don't
            // appear in the similarity result fall back to their original
            // position.
            val candidates = runCatching {
                embeddingDb.nearestNeighborsForFilename(
                    queryFilename = currentSong.filename,
                    k = 200,
                    excludeHashes = emptyList(),
                )
            }.getOrDefault(emptyList())
            val orderByFilename: Map<String, Int> = candidates
                .withIndex()
                .associate { (i, row) -> row.filename to i }
            stableSrc.sortedBy { orderByFilename[it.filename] ?: Int.MAX_VALUE }
        }
        // Fluid zone: replace 15+ with fresh recommendations excluding
        // frozen + stable + currentSong.
        val excludeFns = (frozenZone + stableSorted).map { it.filename }.toMutableSet()
        excludeFns += currentSong.filename
        val fluidTarget = (oldTail.size - frozenZone.size - stableSorted.size).coerceAtLeast(0)
            .coerceAtLeast(20)  // ensure at least 20 fresh songs even if oldTail was short
        val fluidNew = runCatching {
            recommendUpcoming(
                currentSong = currentSong,
                library = library,
                k = fluidTarget,
                adventurous = adventurous,
                extraExcludeFilenames = excludeFns,
            )
        }.getOrDefault(emptyList())
        return frozenZone + stableSorted + fluidNew
    }

    /**
     * Builds an embedding-driven queue when [currentSong] is embedded; falls
     * back to a shuffled tail of [library] otherwise. Always puts
     * [currentSong] at index 0 so the queue is play-ready.
     */
    suspend fun buildPlayQueue(
        currentSong: Song,
        library: List<Song>,
        k: Int = 50,
        adventurous: Float = 0.8f,
        // Push #63: forwarded into recommendUpcoming.
        extraExcludeFilenames: Set<String> = emptySet(),
        hardBlockedFilenames: Set<String> = emptySet(),
    ): List<Song> {
        val recs = recommendUpcoming(
            currentSong, library, k, adventurous,
            extraExcludeFilenames = extraExcludeFilenames,
            hardBlockedFilenames = hardBlockedFilenames,
        )
        val head = listOf(currentSong)
        val tail = if (recs.isNotEmpty()) {
            recs
        } else {
            library.asSequence()
                .filter { it.filePath != null && it.filename != currentSong.filename }
                .shuffled()
                .take(k)
                .toList()
        }
        return head + tail
    }

    /** Result row with similarity score, used by Discover cards. */
    data class ScoredSong(val song: Song, val similarity: Float)

    /**
     * Top-k similar to a song with similarity scores. Used by the
     * "Because You Played [X]" + "Most Similar" Discover sections.
     */
    suspend fun recommendScored(
        currentSong: Song,
        library: List<Song>,
        k: Int = 10,
        adventurous: Float = 0.2f,
    ): List<ScoredSong> {
        val excludeHashes = buildList {
            currentSong.contentHash?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val candidates = embeddingDb.nearestNeighborsForFilename(
            queryFilename = currentSong.filename,
            k = k,
            excludeHashes = excludeHashes,
        )
        if (candidates.isEmpty()) return emptyList()
        val byFilename = library.associateBy { it.filename }
        val byHash: Map<String, Song> = library
            .filter { it.contentHash != null }
            .associateBy { it.contentHash!! }
        return candidates.mapNotNull { row ->
            val song = byFilename[row.filename]
                ?: row.contentHash.takeIf { it.isNotBlank() }?.let { byHash[it] }
                ?: return@mapNotNull null
            if (song.filePath == null) return@mapNotNull null
            ScoredSong(song, row.similarity)
        }
    }

    /**
     * "For You" — picks from songs similar to the user's top-3 most-played
     * tracks. Interleaves results so the row isn't dominated by one anchor.
     * Empty when the user has no history yet (gracefully hides the section).
     */
    suspend fun forYou(
        library: List<Song>,
        stats: Map<String, com.isaivazhi.app.engine.HistoryEngine.Stats>,
        tuning: com.isaivazhi.app.engine.TasteEngine.Tuning =
            com.isaivazhi.app.engine.TasteEngine.Tuning(),
        dislikedFilenames: Set<String> = emptySet(),
        // Push #63: hard-blocked songs are excluded unconditionally
        // (neither anchors nor candidates), separate from the knob-gated
        // dislikedFilenames filter.
        hardBlockedFilenames: Set<String> = emptySet(),
        k: Int = 12,
        // Push #44: when true, oversample the eligible anchor pool and
        // randomly pick the actual anchors. The auto-fired LE keeps the
        // deterministic top-N pick; pull-to-refresh + AI/Shuffle toggle
        // pass randomize=true so each refresh produces visibly different
        // recommendations instead of returning the identical result set.
        randomize: Boolean = false,
    ): List<ScoredSong> {
        val byFilename = library.associateBy { it.filename }
        // sessionBias controls how many anchors we use:
        //   high (≥0.7) → 1 anchor (laser-focused on most recent / most-played)
        //   mid (0.4–0.7) → 2 anchors
        //   low (<0.4) → 3 anchors (broader profile)
        val anchorCount = when {
            tuning.sessionBias >= 0.7f -> 1
            tuning.sessionBias >= 0.4f -> 2
            else -> 3
        }
        val rankedPool = stats.entries
            .asSequence()
            .filter { it.value.plays > 0 && it.value.avgFraction >= 0.5f }
            .filter { it.key !in dislikedFilenames }
            .filter { it.key !in hardBlockedFilenames }
            .sortedByDescending { it.value.plays * it.value.avgFraction }
            .mapNotNull { byFilename[it.key] }
            .filter { it.filePath != null }
            .toList()
        val anchors = if (randomize && rankedPool.size > anchorCount) {
            // Take top 3x candidates by score, then shuffle and pick anchorCount.
            val poolSize = (anchorCount * 3).coerceAtMost(rankedPool.size)
            rankedPool.take(poolSize).shuffled().take(anchorCount)
        } else {
            rankedPool.take(anchorCount)
        }
        if (anchors.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        anchors.forEach { seen += it.filename }
        val out = ArrayList<ScoredSong>()
        // adventurous knob controls MMR diversity in each anchor's neighbor list.
        val perAnchor = anchors.map {
            recommendScored(it, library, k = k, adventurous = tuning.adventurous)
        }
        var i = 0
        while (out.size < k && perAnchor.any { it.size > i }) {
            for (list in perAnchor) {
                if (out.size >= k) break
                val cand = list.getOrNull(i) ?: continue
                if (cand.song.filename in seen) continue
                if (cand.song.filename in dislikedFilenames) continue
                if (cand.song.filename in hardBlockedFilenames) continue
                seen += cand.song.filename
                out += cand
            }
            i++
        }
        return applyNegativeStrengthFilter(out, dislikedFilenames, tuning.negativeStrength)
    }

    /**
     * "Because you played X" — for each of the user's last 3 distinct played
     * tracks, picks top-similar songs. Matches the Capacitor app's 3-section
     * layout. The recent-listened filter requires fractionPlayed >= 0.3f so
     * skipped tracks don't anchor a section.
     */
    suspend fun becauseYouPlayed(
        library: List<Song>,
        recentEvents: List<com.isaivazhi.app.engine.HistoryEngine.Event>,
        tuning: com.isaivazhi.app.engine.TasteEngine.Tuning =
            com.isaivazhi.app.engine.TasteEngine.Tuning(),
        dislikedFilenames: Set<String> = emptySet(),
        // Push #63: hard-blocked songs are excluded as both anchors and
        // candidates.
        hardBlockedFilenames: Set<String> = emptySet(),
        statsFallback: Map<String, com.isaivazhi.app.engine.HistoryEngine.Stats> = emptyMap(),
        kPerSource: Int = 8,
        sourceCount: Int = 3,
        // Push #44: oversample-then-shuffle the anchor pool on each call
        // so pull-to-refresh shows visibly different recommendations.
        randomize: Boolean = false,
    ): List<Pair<Song, List<ScoredSong>>> {
        val byFilename = library.associateBy { it.filename }
        // sessionBias narrows the source count (high = most recent only,
        // low = a broader window across history).
        val effSourceCount = when {
            tuning.sessionBias >= 0.7f -> 1
            tuning.sessionBias >= 0.4f -> 2
            else -> sourceCount
        }
        // Build an oversized pool, then either take top-N (deterministic)
        // or shuffle + take-N (randomized for pull-to-refresh variety).
        val poolFromEvents: List<Song> = recentEvents
            .asSequence()
            .filter { it.fractionPlayed >= 0.3f }
            .filter { it.filename !in dislikedFilenames }
            .filter { it.filename !in hardBlockedFilenames }
            .mapNotNull { byFilename[it.filename] }
            .filter { it.filePath != null }
            .distinctBy { it.filename }
            .take(if (randomize) (effSourceCount * 3).coerceAtLeast(effSourceCount) else effSourceCount)
            .toList()
        var anchors: List<Song> = if (randomize && poolFromEvents.size > effSourceCount) {
            poolFromEvents.shuffled().take(effSourceCount)
        } else {
            poolFromEvents.take(effSourceCount)
        }
        // Fallback: stats-based anchor pool when no qualifying recent events.
        if (anchors.isEmpty() && statsFallback.isNotEmpty()) {
            val statsPool = statsFallback.entries.asSequence()
                .filter { it.value.plays > 0 && it.value.avgFraction >= 0.3f }
                .filter { it.key !in dislikedFilenames }
                .filter { it.key !in hardBlockedFilenames }
                .sortedByDescending { it.value.plays * it.value.avgFraction }
                .mapNotNull { byFilename[it.key] }
                .filter { it.filePath != null }
                .take(if (randomize) (effSourceCount * 3).coerceAtLeast(effSourceCount) else effSourceCount)
                .toList()
            anchors = if (randomize && statsPool.size > effSourceCount) {
                statsPool.shuffled().take(effSourceCount)
            } else {
                statsPool.take(effSourceCount)
            }
        }
        if (anchors.isEmpty()) return emptyList()
        return anchors.map { src ->
            val sims = recommendScored(src, library, k = kPerSource, adventurous = tuning.adventurous)
                .filter { it.song.filename !in hardBlockedFilenames }
            src to applyNegativeStrengthFilter(sims, dislikedFilenames, tuning.negativeStrength)
        }
    }

    /**
     * Filter disliked filenames out of a result list. negativeStrength
     * controls aggressiveness: <0.3 = no filter; 0.3–0.7 = soft (drop direct
     * matches); >0.7 = hard (drop direct matches + already filtered upstream).
     */
    private fun applyNegativeStrengthFilter(
        list: List<ScoredSong>,
        dislikedFilenames: Set<String>,
        negativeStrength: Float,
    ): List<ScoredSong> {
        if (negativeStrength < 0.3f || dislikedFilenames.isEmpty()) return list
        return list.filter { it.song.filename !in dislikedFilenames }
    }

    /**
     * "Unexplored Sounds" — three labeled pockets of the library the user
     * rarely visits. Each section samples a different segment so the row
     * surfaces real variety. Section labels are picked by the UI layer:
     * "Sound you rarely visit" / "Another pocket of your library" /
     * "Off the beaten path".
     *
     * Implementation: bucket by a stable filename hash into 3 buckets, then
     * within each bucket pick songs that have an embedding but haven't been
     * played (or only barely played, by fraction < 0.3). Stable hashing means
     * the same song lands in the same bucket across refreshes — pull-to-
     * refresh shuffles the picks within a bucket, but the buckets themselves
     * stay coherent.
     */
    fun unexploredClusters(
        library: List<Song>,
        playedFilenames: Set<String>,
        // Push #59: filepath-keyed embedding membership (was filename, which
        // misclassified songs whose MediaStore DISPLAY_NAME differs from the
        // on-disk filename).
        embeddedFilepaths: Set<String>,
        kPerCluster: Int = 8,
        clusters: Int = 3,
    ): List<List<Song>> {
        val pool = library.asSequence()
            .filter { it.filePath != null }
            .filter { it.filePath in embeddedFilepaths }
            .filter { it.filename !in playedFilenames }
            .toList()
        if (pool.isEmpty()) return emptyList()
        val buckets = Array(clusters) { mutableListOf<Song>() }
        for (s in pool) {
            // Stable bucketing — picks the same cluster index for a given
            // filename across calls so refreshes don't reshuffle assignments.
            val h = (s.filename.hashCode().toLong() and 0x7fffffffL).toInt()
            buckets[h % clusters].add(s)
        }
        return buckets.map { bucket -> bucket.shuffled().take(kPerCluster) }
            .filter { it.isNotEmpty() }
    }

    /**
     * Backwards-compat single-list "unexplored" — kept for callers that
     * haven't migrated to the clustered version.
     */
    fun unexplored(
        library: List<Song>,
        playedFilenames: Set<String>,
        // Push #59: filepath-keyed (was filename).
        embeddedFilepaths: Set<String>,
        k: Int = 12,
    ): List<Song> = unexploredClusters(library, playedFilenames, embeddedFilepaths, kPerCluster = k, clusters = 1)
        .flatten()

    private data class PoolEntry(
        val song: Song,
        val similarity: Float,
        val vec: FloatArray,
    )

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }
}
