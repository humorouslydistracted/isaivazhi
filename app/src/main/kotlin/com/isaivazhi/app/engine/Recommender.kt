package com.isaivazhi.app.engine

import com.isaivazhi.app.NativeAccelerator
import kotlin.math.pow
import kotlin.math.sqrt
import java.util.Random as JavaRandom

/**
 * Recommender — picks the next K songs to play based on similarity to a
 * query song's embedding.
 *
 * Backed by sqlite-vec via [EmbeddingDbFacade] (or NativeAccelerator NEON
 * fallback) for the top-K relevance step, then an MMR rerank applies
 * diversity penalty `(1 - lambda) * maxRedundancy` over a 3K candidate set.
 *
 * `lambda = (1 - adventurous)^1.5` (power-curve mapping):
 *   - adventurous=0.0 → lambda=1.0 → pure relevance, top-K = first K by sim
 *   - adventurous=0.8 (default) → lambda≈0.089 (was 0.20 with linear mapping)
 *   - adventurous=1.0 → lambda=0.0 → pure diversity (sim ignored)
 *
 * The 1.5 exponent keeps the boundary semantics intact while making the
 * 0.70–0.85 practical range less sensitive: a 5% slider move changes lambda
 * by ~0.03–0.04 instead of a flat 0.05, reducing the drastic reshuffling
 * users observed with the original linear mapping.
 *
 * The original formula the Capacitor build settled on in batch #3 (after
 * the earlier inversion was caught) was linear; the power curve was added
 * to fix slider over-sensitivity without changing the conceptual range.
 */
class Recommender(
    private val embeddingDb: EmbeddingDbFacade,
) {

    /**
     * Push #67: blend weight schedule. Capacitor parity (`_blendWeights`
     * in engine.js:653). Mode controls which entry point we're building
     * for; the weights ramp based on how many session listens have
     * accumulated so the first-of-session recs are current-song-heavy,
     * and as the session grows the weights shift toward session+profile.
     */
    data class BlendWeights(
        val wCurrent: Float,
        val wSession: Float,
        val wProfile: Float,
    )

    fun blendWeights(
        mode: String,
        nListened: Int,
        hasCurrent: Boolean,
        hasSession: Boolean,
        hasProfile: Boolean,
    ): BlendWeights {
        val w = BlendWeightLogic.compute(mode, nListened, hasCurrent, hasSession, hasProfile)
        return BlendWeights(w.wCurrent, w.wSession, w.wProfile)
    }

    /**
     * Push #67: build a blended query vector that mixes the current
     * song's embedding with the session vector (last-N qualified
     * listens) and the profile vector (top-30 long-term centroid).
     * Used as the query for `recommendUpcoming` so the recommender
     * reflects all three time scales.
     *
     * Capacitor parity (`_buildBlendedVec` in engine.js).
     */
    suspend fun buildBlendedVec(
        currentSongHash: String?,
        sessionListened: List<com.isaivazhi.app.engine.SessionEngine.ListenedEntry>,
        profileVec: FloatArray?,
        library: List<Song>,
        mode: String = "play",
        sessionBias: Float = 0.5f,
    ): Triple<FloatArray?, BlendWeights, String> {
        val byFilename = library.associateBy { it.filename }
        // Build session vec weighted by listen depth (fraction). Songs heard
        // more completely pull the session centroid harder than songs barely
        // past the 0.30 gate. If the same song was heard multiple times this
        // session, its fractions are summed as its total weight.
        val sessionWeightsByHash = LinkedHashMap<String, Float>()
        for (entry in sessionListened) {
            if (entry.fraction < 0.30f) continue
            val hash = byFilename[entry.filename]?.contentHash ?: continue
            sessionWeightsByHash[hash] = (sessionWeightsByHash[hash] ?: 0f) + entry.fraction
        }
        val currentVec = if (!currentSongHash.isNullOrBlank()) {
            embeddingDb.getVecsByHashes(listOf(currentSongHash))[currentSongHash]
        } else null
        val sessionVecs = if (sessionWeightsByHash.isNotEmpty()) {
            embeddingDb.getVecsByHashes(sessionWeightsByHash.keys.toList())
        } else emptyMap()
        val sessionVec = if (sessionVecs.isNotEmpty())
            weightedAvgVec(sessionVecs, sessionWeightsByHash)
        else null
        val hasCurrent = currentVec != null
        val hasSession = sessionVec != null
        val hasProfile = profileVec != null && profileVec.isNotEmpty()
        val nListened = sessionListened.size
        var w = blendWeights(mode, nListened, hasCurrent, hasSession, hasProfile)
        // Apply sessionBias to bias session vs profile (Capacitor parity).
        if (w.wSession > 0 && w.wProfile > 0) {
            val sb = sessionBias.coerceIn(0f, 0.95f)
            val rest = w.wSession + w.wProfile
            w = BlendWeights(w.wCurrent, rest * sb, rest * (1f - sb))
        }
        if (!hasCurrent && !hasSession && !hasProfile) return Triple(null, w, "empty")
        // Choose a dim from any available vec.
        val dim = currentVec?.size ?: sessionVec?.size ?: profileVec?.size ?: 0
        if (dim == 0) return Triple(null, w, "no_dim")
        val out = FloatArray(dim)
        fun add(v: FloatArray?, wgt: Float) {
            if (v == null || wgt <= 0f || v.size != dim) return
            for (i in 0 until dim) out[i] += v[i] * wgt
        }
        add(currentVec, w.wCurrent)
        add(sessionVec, w.wSession)
        add(profileVec, w.wProfile)
        // L2 normalize.
        var n2 = 0f
        for (i in 0 until dim) n2 += out[i] * out[i]
        val inv = if (n2 > 0f) 1f / sqrt(n2) else 0f
        for (i in 0 until dim) out[i] *= inv
        val label = when {
            w.wSession > 0f && w.wProfile > 0f -> "session+profile"
            w.wSession > 0f -> "session"
            w.wProfile > 0f -> "profile"
            else -> "current"
        }
        return Triple(out, w, label)
    }

    private fun avgVec(vecs: List<FloatArray>): FloatArray? {
        if (vecs.isEmpty()) return null
        val dim = vecs[0].size
        if (dim == 0) return null
        val out = FloatArray(dim)
        var count = 0
        for (v in vecs) {
            if (v.size != dim) continue
            for (i in 0 until dim) out[i] += v[i]
            count++
        }
        if (count == 0) return null
        for (i in 0 until dim) out[i] /= count
        // Normalize.
        var n2 = 0f
        for (i in 0 until dim) n2 += out[i] * out[i]
        val inv = if (n2 > 0f) 1f / sqrt(n2) else 0f
        for (i in 0 until dim) out[i] *= inv
        return out
    }

    /**
     * Weighted average of embedding vectors keyed by content hash.
     * [weights] maps contentHash → scalar weight (e.g. cumulative fraction).
     * Hashes missing from [vecs] are skipped. Result is L2-normalised.
     */
    private fun weightedAvgVec(
        vecs: Map<String, FloatArray>,
        weights: Map<String, Float>,
    ): FloatArray? {
        val dim = vecs.values.firstOrNull()?.size ?: return null
        if (dim == 0) return null
        val out = FloatArray(dim)
        var totalWeight = 0f
        for ((hash, w) in weights) {
            if (w <= 0f) continue
            val v = vecs[hash] ?: continue
            if (v.size != dim) continue
            for (i in 0 until dim) out[i] += v[i] * w
            totalWeight += w
        }
        if (totalWeight <= 0f) return null
        for (i in 0 until dim) out[i] /= totalWeight
        var n2 = 0f
        for (i in 0 until dim) n2 += out[i] * out[i]
        val inv = if (n2 > 0f) 1f / sqrt(n2) else 0f
        for (i in 0 until dim) out[i] *= inv
        return out
    }

    /**
     * Push #67: query the embedding DB by an arbitrary unit-norm vector.
     * Used by the blended query in [recommendUpcoming]. Brute-force
     * cosine over the library (NEON-accelerated batch dot product);
     * acceptable for ~2.5k library size where it runs in <50ms.
     */
    suspend fun nearestNeighborsForVector(
        queryVec: FloatArray,
        library: List<Song>,
        k: Int,
        excludeFilenames: Set<String> = emptySet(),
    ): List<ScoredSong> {
        val embedded = library.asSequence()
            .filter { !it.contentHash.isNullOrBlank() && it.filePath != null }
            .filter { it.filename !in excludeFilenames }
            .distinctBy { it.contentHash!! }
            .toList()
        if (embedded.isEmpty()) return emptyList()
        val hashes = embedded.mapNotNull { it.contentHash }
        val vecs = embeddingDb.getVecsByHashes(hashes)
        if (vecs.isEmpty()) return emptyList()
        val dim = queryVec.size
        val scored = ArrayList<ScoredSong>(embedded.size)
        for (song in embedded) {
            val v = vecs[song.contentHash ?: continue] ?: continue
            if (v.size != dim) continue
            var dot = 0f
            var vn = 0f
            for (i in 0 until dim) { dot += queryVec[i] * v[i]; vn += v[i] * v[i] }
            val norm = if (vn > 0f) sqrt(vn) else 1f
            scored += ScoredSong(song, dot / norm)
        }
        scored.sortByDescending { it.similarity }
        return scored.take(k)
    }

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
        /** Thumbs-down list — always excluded from Up Next (not scaled by Negative guard). */
        dislikedFilenames: Set<String> = emptySet(),
        /** Mild negatives excluded when Negative guard slider is raised. */
        softExcludedFilenames: Set<String> = emptySet(),
        // Push #67: blended query vector for current+session+profile.
        // When non-null, candidates are ranked against this vec instead
        // of the current song's neighbors. Caller is responsible for
        // building it via [buildBlendedVec].
        blendedQueryVec: FloatArray? = null,
    ): List<Song> {
        val lambda = (1f - adventurous).coerceIn(0f, 1f).pow(1.5f)
        val policyExcludes = RecommendationPolicy.unionExcludes(
            hardBlockedFilenames, softExcludedFilenames, dislikedFilenames,
        )
        val excludeHashes = buildList {
            currentSong.contentHash?.takeIf { it.isNotBlank() }?.let { add(it) }
        }

        // Step 1: top-K*3 relevance candidates. When a blended query
        // vector is supplied (Push #67), rank against it; otherwise use
        // the current-song-only path via sqlite-vec.
        val overFetch = (k * 3).coerceAtLeast(k + 10)
        val candidates: List<EmbeddingDbFacade.NnResult> = if (blendedQueryVec != null) {
            val scored = nearestNeighborsForVector(
                queryVec = blendedQueryVec,
                library = library,
                k = overFetch,
                excludeFilenames = extraExcludeFilenames + policyExcludes + currentSong.filename,
            )
            // Adapt to NnResult shape so the rest of the pipeline is shared.
            scored.map { ss ->
                EmbeddingDbFacade.NnResult(
                    contentHash = ss.song.contentHash ?: "",
                    filepath = ss.song.filePath ?: "",
                    filename = ss.song.filename,
                    similarity = ss.similarity,
                )
            }
        } else {
            embeddingDb.nearestNeighborsForFilename(
                queryFilename = currentSong.filename,
                k = overFetch,
                excludeHashes = excludeHashes,
            )
        }
        if (candidates.isEmpty()) return emptyList()

        // Step 2: load candidate vectors in one batch for MMR redundancy
        // scoring. ~150 vectors × 2 KB = 300 KB over the bridge — small.
        val byFilename = library.associateBy { it.filename }
        val byHash: Map<String, Song> = library
            .filter { it.contentHash != null }
            .associateBy { it.contentHash!! }

        val viable = candidates.mapNotNull { row ->
            if (row.filename in extraExcludeFilenames) return@mapNotNull null
            if (row.filename in policyExcludes) return@mapNotNull null
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
    /**
     * Push #67 — Capacitor parity (`_softRefreshQueue` in engine.js:910).
     * Three-zone re-rank after a qualified listen so the upcoming queue
     * reflects updated taste without yanking the user's immediate next
     * picks. Replaces the queue-exhaust-only refresh from push #45.
     *
     * Zones:
     *   [0..4]   frozen      — untouched.
     *   [5..14]  stable      — same songs, re-sorted by NEW blended sim.
     *   [15..]   fluid       — replaced with fresh recommendations.
     *
     * Returns the new tail (positions 0..end of current queue).
     */
    suspend fun softRefreshUpcomingTail(
        currentSong: Song,
        upcoming: List<Song>,
        library: List<Song>,
        blendedQueryVec: FloatArray,
        adventurous: Float = 0.8f,
        extraExcludeFilenames: Set<String> = emptySet(),
        hardBlockedFilenames: Set<String> = emptySet(),
        dislikedFilenames: Set<String> = emptySet(),
        softExcludedFilenames: Set<String> = emptySet(),
        frozenZoneSize: Int = 5,
        stableZoneEnd: Int = 15,
    ): List<Song> {
        val zones = SoftRefreshPlanner.split(upcoming, frozenZoneSize, stableZoneEnd)
        if (!zones.hasRefreshableTail) return upcoming
        val frozenZone = zones.frozen
        val stableSrc = zones.stable
        val fluidSrc = zones.fluid

        // Re-rank stable zone by blended-vec similarity.
        val stableHashes = stableSrc.mapNotNull { it.contentHash }
        val stableVecs = if (stableHashes.isNotEmpty()) embeddingDb.getVecsByHashes(stableHashes) else emptyMap()
        val dim = blendedQueryVec.size
        val policyExcludes = RecommendationPolicy.unionExcludes(
            hardBlockedFilenames, softExcludedFilenames, dislikedFilenames,
        )
        val stableSorted: List<Song> = if (stableVecs.isEmpty()) stableSrc else {
            stableSrc.map { song ->
                val v = song.contentHash?.let { stableVecs[it] }
                val sim = if (v != null && v.size == dim) {
                    var dot = 0f; var n = 0f
                    for (i in 0 until dim) { dot += blendedQueryVec[i] * v[i]; n += v[i] * v[i] }
                    val norm = if (n > 0f) sqrt(n) else 1f
                    dot / norm
                } else -1f
                song to sim
            }.sortedByDescending { it.second }.map { it.first }
        }
        // Rebuild fluid zone with fresh recommendations against the
        // blended vector, excluding frozen+stable+current.
        val excludeFns = (frozenZone + stableSorted).map { it.filename }.toMutableSet().also {
            it += currentSong.filename
            it += extraExcludeFilenames
        }
        val fluidTarget = (upcoming.size - frozenZone.size - stableSorted.size).coerceAtLeast(20)
        val fluidNew = runCatching {
            recommendUpcoming(
                currentSong = currentSong,
                library = library,
                k = fluidTarget,
                adventurous = adventurous,
                extraExcludeFilenames = excludeFns,
                hardBlockedFilenames = hardBlockedFilenames,
                dislikedFilenames = dislikedFilenames,
                softExcludedFilenames = softExcludedFilenames,
                blendedQueryVec = blendedQueryVec,
            )
        }.getOrDefault(fluidSrc)
        return RecommendationRefreshFinalizer.finalizeSoftRefresh(
            frozenZone = frozenZone,
            stableCandidates = stableSorted,
            fluidCandidates = fluidNew,
            currentFilename = currentSong.filename,
            policyExcludes = policyExcludes,
            cooldownFilenames = extraExcludeFilenames,
        ).finalTail
    }

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
        dislikedFilenames: Set<String> = emptySet(),
        softExcludedFilenames: Set<String> = emptySet(),
        // Push #72: optional blended query vector. When non-null, the
        // recommender ranks against the blend (current + session + profile)
        // instead of only the current song's neighbors.
        blendedQueryVec: FloatArray? = null,
    ): List<Song> {
        val recs = recommendUpcoming(
            currentSong, library, k, adventurous,
            extraExcludeFilenames = extraExcludeFilenames,
            hardBlockedFilenames = hardBlockedFilenames,
            dislikedFilenames = dislikedFilenames,
            softExcludedFilenames = softExcludedFilenames,
            blendedQueryVec = blendedQueryVec,
        )
        val head = listOf(currentSong)
        val tail = if (recs.isNotEmpty()) {
            recs
        } else {
            val policyExcludes = RecommendationPolicy.unionExcludes(
                hardBlockedFilenames, softExcludedFilenames, dislikedFilenames,
            )
            val excludes = extraExcludeFilenames + policyExcludes + currentSong.filename
            library.asSequence()
                .filter { it.filePath != null && it.filename !in excludes }
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
        return applyDislikedFilter(out, dislikedFilenames)
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
        // Push #64: build a LARGER pool (effSourceCount * 4) so the diversity
        // picker has room to find anchors that span different audio
        // "moods" in embedding space. Capacitor parity: engine-analytics.js
        // `getBecauseYouPlayed` iteratively picks anchors that maximize the
        // minimum cosine distance to already-picked anchors.
        val poolFromEvents: List<Song> = recentEvents
            .asSequence()
            .filter { it.fractionPlayed >= 0.3f }
            .filter { it.filename !in dislikedFilenames }
            .filter { it.filename !in hardBlockedFilenames }
            .mapNotNull { byFilename[it.filename] }
            .filter { it.filePath != null }
            .distinctBy { it.filename }
            .take((effSourceCount * 4).coerceAtLeast(effSourceCount * 2))
            .toList()
        var anchors: List<Song> = pickDiverseAnchors(poolFromEvents, effSourceCount, randomize)
        // Fallback: stats-based anchor pool when no qualifying recent events.
        if (anchors.isEmpty() && statsFallback.isNotEmpty()) {
            val statsPool = statsFallback.entries.asSequence()
                .filter { it.value.plays > 0 && it.value.avgFraction >= 0.3f }
                .filter { it.key !in dislikedFilenames }
                .filter { it.key !in hardBlockedFilenames }
                .sortedByDescending { it.value.plays * it.value.avgFraction }
                .mapNotNull { byFilename[it.key] }
                .filter { it.filePath != null }
                .take((effSourceCount * 4).coerceAtLeast(effSourceCount * 2))
                .toList()
            anchors = pickDiverseAnchors(statsPool, effSourceCount, randomize)
        }
        if (anchors.isEmpty()) return emptyList()
        return anchors.map { src ->
            val sims = recommendScored(src, library, k = kPerSource, adventurous = tuning.adventurous)
                .filter { it.song.filename !in hardBlockedFilenames }
            src to applyDislikedFilter(sims, dislikedFilenames)
        }
    }

    /**
     * Push #64: greedy max-min-distance anchor picker for the
     * "Because you played X" sections.
     *
     * Algorithm (Capacitor `getBecauseYouPlayed` parity):
     *   1. Anchor 0 = first song in [pool] (most-recent / most-played).
     *   2. For each subsequent anchor, pick the candidate whose minimum
     *      cosine distance to all already-picked anchors is maximal.
     *   3. When [randomize] = true, shuffle [pool] first so anchor 0
     *      varies between pull-to-refresh calls.
     *
     * Cosine distance is computed via batch dot products. Songs without a
     * loadable embedding are kept on the candidate list but treated as
     * "infinitely far" from picked anchors so they're picked last — that
     * way pre-embedding states still produce some output.
     */
    private suspend fun pickDiverseAnchors(
        pool: List<Song>,
        count: Int,
        randomize: Boolean,
    ): List<Song> {
        if (pool.isEmpty() || count <= 0) return emptyList()
        if (pool.size <= count) return pool
        val ordered = if (randomize) pool.shuffled() else pool
        val hashes = ordered.mapNotNull { it.contentHash?.takeIf { h -> h.isNotBlank() } }.distinct()
        val vecs = runCatching { embeddingDb.getVecsByHashes(hashes) }.getOrDefault(emptyMap())
        // Map each pool song to its (optional) unit-norm vector.
        val songVec: List<Pair<Song, FloatArray?>> = ordered.map { song ->
            val v = song.contentHash?.let { vecs[it] }
            song to v?.let { normalize(it) }
        }
        val picked = ArrayList<Pair<Song, FloatArray?>>(count)
        picked.add(songVec.first())
        while (picked.size < count) {
            var bestIdx = -1
            var bestMinDot = Float.MAX_VALUE  // smaller dot = farther in cosine.
            for ((i, cand) in songVec.withIndex()) {
                if (picked.any { it.first.filename == cand.first.filename }) continue
                val cv = cand.second
                if (cv == null) {
                    // No vector — treat as already-far (pick last).
                    if (bestIdx == -1) bestIdx = i
                    continue
                }
                var maxDotToPicked = -Float.MAX_VALUE
                for ((_, pv) in picked) {
                    if (pv == null) continue
                    var dot = 0f
                    for (d in cv.indices) dot += cv[d] * pv[d]
                    if (dot > maxDotToPicked) maxDotToPicked = dot
                }
                if (maxDotToPicked < bestMinDot) {
                    bestMinDot = maxDotToPicked
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break
            picked.add(songVec[bestIdx])
        }
        return picked.map { it.first }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val inv = if (s > 0f) 1f / sqrt(s) else 0f
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] * inv
        return out
    }

    /** Always drop thumbs-down songs from legacy section/card lists. */
    private fun applyDislikedFilter(
        list: List<ScoredSong>,
        dislikedFilenames: Set<String>,
    ): List<ScoredSong> {
        if (dislikedFilenames.isEmpty()) return list
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

    /**
     * Push #64 — Capacitor parity For You: builds a single "profile vector"
     * as the weighted average of the user's top-30 played-and-completed
     * songs' embeddings, then returns the songs whose embeddings sit
     * closest to that centroid. Replaces the anchor-based approach in
     * `forYou()` for cases where the user has enough listening evidence;
     * `forYou()` is kept as the lighter fallback.
     *
     * Capacitor reference: engine-analytics.js:316-356 (`profileVec`
     * derivation + nearest neighbors + shuffle).
     *
     * Algorithm:
     *  1. Rank songs by `plays × avgFraction` descending, keep top 30.
     *  2. Pull their embedding vectors via [EmbeddingDbFacade.getVecsByHashes].
     *  3. Compute weighted average; L2-normalize.
     *  4. Compute cosine similarity from profileVec to every other
     *     embedded library song.
     *  5. Take top (k × 6) as pool; shuffle when [randomize] = true.
     *  6. Return top k.
     */
    suspend fun forYouByProfileVector(
        library: List<Song>,
        stats: Map<String, com.isaivazhi.app.engine.HistoryEngine.Stats>,
        embeddedFilepaths: Set<String>,
        dislikedFilenames: Set<String> = emptySet(),
        hardBlockedFilenames: Set<String> = emptySet(),
        k: Int = 12,
        randomize: Boolean = false,
    ): List<ScoredSong> {
        val byFilename = library.associateBy { it.filename }
        val topPlays = stats.entries.asSequence()
            .filter { it.value.plays > 0 && it.value.avgFraction >= 0.3f }
            .filter { it.key !in dislikedFilenames }
            .filter { it.key !in hardBlockedFilenames }
            .sortedByDescending { it.value.plays * it.value.avgFraction }
            .take(30)
            .toList()
        if (topPlays.isEmpty()) return emptyList()
        val anchorSongs = topPlays.mapNotNull { byFilename[it.key] }
            .filter { !it.contentHash.isNullOrBlank() && it.filePath in embeddedFilepaths }
        if (anchorSongs.isEmpty()) return emptyList()
        val anchorWeights = topPlays.associate { it.key to (it.value.plays * it.value.avgFraction) }
        val anchorHashes = anchorSongs.mapNotNull { it.contentHash }.distinct()
        val vecsByHash = embeddingDb.getVecsByHashes(anchorHashes)
        if (vecsByHash.isEmpty()) return emptyList()

        val dim = vecsByHash.values.first().size
        val profileVec = FloatArray(dim)
        var totalWeight = 0f
        for (song in anchorSongs) {
            val v = vecsByHash[song.contentHash ?: continue] ?: continue
            val w = anchorWeights[song.filename] ?: continue
            for (d in 0 until dim) profileVec[d] += v[d] * w
            totalWeight += w
        }
        if (totalWeight <= 0f) return emptyList()
        var n2 = 0f
        for (d in 0 until dim) { profileVec[d] /= totalWeight; n2 += profileVec[d] * profileVec[d] }
        val inv = if (n2 > 0f) 1f / sqrt(n2) else 0f
        for (d in 0 until dim) profileVec[d] *= inv

        // Build candidate pool: every embedded song that's NOT one of the
        // anchors and not disliked/hard-blocked. De-dupe by contentHash so
        // duplicate file copies don't dominate.
        val anchorFilenames = anchorSongs.map { it.filename }.toHashSet()
        val candidates = library.asSequence()
            .filter { it.filePath != null && it.filePath in embeddedFilepaths }
            .filter { !it.contentHash.isNullOrBlank() }
            .filter { it.filename !in anchorFilenames }
            .filter { it.filename !in dislikedFilenames }
            .filter { it.filename !in hardBlockedFilenames }
            .distinctBy { it.contentHash!! }
            .toList()
        if (candidates.isEmpty()) return emptyList()
        val candHashes = candidates.mapNotNull { it.contentHash }
        val candVecs = embeddingDb.getVecsByHashes(candHashes)
        if (candVecs.isEmpty()) return emptyList()

        // Cosine similarity = dot(profileVec, candVec) / |candVec|
        // (profileVec is unit-norm). Use the existing flat-array NEON batch
        // path when the pool is big enough to make it worthwhile.
        val scored = ArrayList<ScoredSong>(candidates.size)
        for (song in candidates) {
            val v = candVecs[song.contentHash ?: continue] ?: continue
            if (v.size != dim) continue
            var dot = 0f
            var vn = 0f
            for (d in 0 until dim) { dot += profileVec[d] * v[d]; vn += v[d] * v[d] }
            val norm = if (vn > 0f) sqrt(vn) else 1f
            scored += ScoredSong(song, dot / norm)
        }
        if (scored.isEmpty()) return emptyList()
        scored.sortByDescending { it.similarity }

        val poolSize = (k * 6).coerceAtMost(scored.size)
        val pool = scored.subList(0, poolSize).toList()
        return if (randomize && pool.size > k) pool.shuffled().take(k) else pool.take(k)
    }

    /**
     * Push #64 — Capacitor parity Unexplored Sounds: real k-means clustering
     * on embedding vectors instead of `filename.hashCode() % N` bucketing.
     * Each returned cluster is a musically coherent group of songs the user
     * has barely engaged with.
     *
     * Capacitor reference: engine-analytics.js:540-625 (`getUnexploredClusters`).
     *
     * Algorithm:
     *  1. Load embeddings for every eligible (embedded + filePath != null)
     *     song into a flat row-major float[].
     *  2. Seeded RNG (seed=42) picks [kClusters] initial centroid indices.
     *  3. Lloyd's iterations (max 20, early-exit on stable assignment).
     *  4. Score each cluster by Σ(plays × avgFraction) / size ascending.
     *  5. Take the lowest-engagement [displayClusters] clusters; within
     *     each, drop songs in [playedFilenames] and shuffle/cap at
     *     [kPerCluster].
     *
     * Falls back to [unexploredClusters] (hash-bucket) when there aren't
     * enough eligible songs for meaningful clustering.
     */
    suspend fun unexploredClustersKMeans(
        library: List<Song>,
        stats: Map<String, com.isaivazhi.app.engine.HistoryEngine.Stats>,
        playedFilenames: Set<String>,
        embeddedFilepaths: Set<String>,
        kClusters: Int = 15,
        displayClusters: Int = 3,
        kPerCluster: Int = 8,
        seed: Long = 42L,
    ): List<List<Song>> {
        val eligible = library.asSequence()
            .filter { it.filePath != null && it.filePath in embeddedFilepaths }
            .filter { !it.contentHash.isNullOrBlank() }
            .distinctBy { it.contentHash!! }
            .toList()
        if (eligible.size < kClusters * 2) {
            return unexploredClusters(library, playedFilenames, embeddedFilepaths, kPerCluster, displayClusters)
        }
        val hashes = eligible.mapNotNull { it.contentHash }
        val vecsByHash = embeddingDb.getVecsByHashes(hashes)
        if (vecsByHash.size < kClusters * 2) {
            return unexploredClusters(library, playedFilenames, embeddedFilepaths, kPerCluster, displayClusters)
        }
        // Build a parallel filtered list keeping only songs whose vector we got.
        val keptSongs = eligible.filter { vecsByHash[it.contentHash] != null }
        val n = keptSongs.size
        val dim = vecsByHash.values.first().size
        if (n < kClusters * 2 || dim <= 0) {
            return unexploredClusters(library, playedFilenames, embeddedFilepaths, kPerCluster, displayClusters)
        }

        // Row-major flat array of L2-normalized vectors for cosine-as-dot.
        val flat = FloatArray(n * dim)
        for (i in 0 until n) {
            val src = vecsByHash[keptSongs[i].contentHash]!!
            var s = 0f
            val srcLen = minOf(dim, src.size)
            for (j in 0 until srcLen) s += src[j] * src[j]
            val invN = if (s > 0f) 1f / sqrt(s) else 0f
            val base = i * dim
            for (j in 0 until srcLen) flat[base + j] = src[j] * invN
        }

        val rng = JavaRandom(seed)
        val initIdx = (0 until n).toMutableList().also {
            for (i in it.size - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val tmp = it[i]; it[i] = it[j]; it[j] = tmp
            }
        }.subList(0, kClusters)
        // centroids[c] is a flat dim-length float array.
        val centroids = Array(kClusters) { c ->
            val base = initIdx[c] * dim
            FloatArray(dim).also { System.arraycopy(flat, base, it, 0, dim) }
        }
        val assignments = IntArray(n)
        val scratch = FloatArray(n)
        val maxIter = 20
        for (iter in 0 until maxIter) {
            var changes = 0
            // For each centroid c, batch-dot against the pool; track best
            // (centroid, similarity) per row.
            val bestDot = FloatArray(n) { -Float.MAX_VALUE }
            val bestC = IntArray(n)
            for (c in 0 until kClusters) {
                val used = NativeAccelerator.dotProductBatch(centroids[c], flat, n, dim, scratch)
                if (!used) {
                    for (i in 0 until n) {
                        var d = 0f
                        val base = i * dim
                        val cv = centroids[c]
                        for (j in 0 until dim) d += cv[j] * flat[base + j]
                        scratch[i] = d
                    }
                }
                for (i in 0 until n) {
                    val s = scratch[i]
                    if (s > bestDot[i]) { bestDot[i] = s; bestC[i] = c }
                }
            }
            for (i in 0 until n) {
                if (assignments[i] != bestC[i]) { assignments[i] = bestC[i]; changes++ }
            }
            if (changes == 0 && iter > 0) break
            // Recompute centroids = mean of assigned rows, then L2-normalize.
            val sums = Array(kClusters) { FloatArray(dim) }
            val counts = IntArray(kClusters)
            for (i in 0 until n) {
                val a = assignments[i]
                counts[a]++
                val base = i * dim
                val s = sums[a]
                for (j in 0 until dim) s[j] += flat[base + j]
            }
            for (c in 0 until kClusters) {
                if (counts[c] == 0) continue
                val s = sums[c]
                for (j in 0 until dim) s[j] /= counts[c]
                var nrm = 0f
                for (j in 0 until dim) nrm += s[j] * s[j]
                val invN = if (nrm > 0f) 1f / sqrt(nrm) else 0f
                for (j in 0 until dim) s[j] *= invN
                centroids[c] = s
            }
        }

        // Score clusters by engagement; lowest first.
        data class ClusterStat(val cluster: Int, val score: Float, val songs: List<Song>)
        val byCluster = HashMap<Int, MutableList<Song>>(kClusters)
        for (i in 0 until n) byCluster.getOrPut(assignments[i]) { mutableListOf() }.add(keptSongs[i])
        val clusterScores = byCluster.entries.mapNotNull { (cid, songs) ->
            if (songs.size < 3) return@mapNotNull null
            var total = 0f
            for (s in songs) {
                val st = stats[s.filename] ?: continue
                total += st.plays * st.avgFraction
            }
            ClusterStat(cid, total / songs.size, songs)
        }.sortedBy { it.score }

        val outRng = JavaRandom(seed)
        val out = ArrayList<List<Song>>(displayClusters)
        for (cs in clusterScores) {
            if (out.size >= displayClusters) break
            val unplayed = cs.songs.filter { it.filename !in playedFilenames }
            if (unplayed.isEmpty()) continue
            // Stable-shuffle so repeated calls in the same session pull the
            // same picks; pull-to-refresh callers can pass a fresh rng if
            // they want variety.
            val shuffled = unplayed.toMutableList().also {
                for (i in it.size - 1 downTo 1) {
                    val j = outRng.nextInt(i + 1)
                    val tmp = it[i]; it[i] = it[j]; it[j] = tmp
                }
            }
            out += shuffled.take(kPerCluster)
        }
        android.util.Log.i(
            "Recommender",
            "kmeans: n=$n dim=$dim kClusters=$kClusters → displayed=${out.size}/${clusterScores.size} (scores=${clusterScores.take(displayClusters).map { "%.3f".format(it.score) }})"
        )
        return out
    }

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
