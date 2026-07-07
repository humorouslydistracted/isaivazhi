package com.isaivazhi.app.engine

import com.isaivazhi.app.NativeAccelerator
import kotlin.math.pow
import kotlin.math.sqrt

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
 *   - adventurous=0.4 (default) → lambda≈0.465
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

    companion object {
        internal fun resolveSimilarCandidates(
            candidates: List<EmbeddingDbFacade.NnResult>,
            library: List<Song>,
            policyExcludes: Set<String>,
            k: Int,
        ): List<ScoredSong> {
            if (candidates.isEmpty() || k <= 0) return emptyList()
            val byFilename = library.associateBy { it.filename }
            val byHash: Map<String, Song> = library
                .filter { it.contentHash != null }
                .associateBy { it.contentHash!! }
            return candidates.asSequence().mapNotNull { row ->
                if (row.filename in policyExcludes) return@mapNotNull null
                val song = byFilename[row.filename]
                    ?: row.contentHash.takeIf { it.isNotBlank() }?.let { byHash[it] }
                    ?: return@mapNotNull null
                if (song.filePath == null) return@mapNotNull null
                if (song.filename in policyExcludes) return@mapNotNull null
                ScoredSong(song, row.similarity)
            }.take(k).toList()
        }
    }

    var traceSink: ((RecommendationGenerationTrace) -> Unit)? = null

    /**
     * Push #67: blend weight schedule. Capacitor parity (`_blendWeights`
     * in engine.js:653). Mode controls which entry point we're building
     * for; the weights ramp based on how many session listens have
     * accumulated so the first-of-session recs can follow the explicit
     * anchor, and as the session grows the weights shift toward
     * session+profile.
     */
    data class BlendWeights(
        val wCurrent: Float,
        val wSession: Float,
        val wProfile: Float,
    )

    data class BlendedQueryDetails(
        val queryVector: FloatArray?,
        val weights: BlendWeights,
        val label: String,
        val currentVector: FloatArray?,
        val sessionVector: FloatArray?,
        val profileVector: FloatArray?,
    )

    fun blendWeights(
        mode: String,
        nListened: Float,
        hasCurrent: Boolean,
        hasSession: Boolean,
        hasProfile: Boolean,
    ): BlendWeights {
        val w = BlendWeightLogic.compute(mode, nListened, hasCurrent, hasSession, hasProfile)
        return BlendWeights(w.wCurrent, w.wSession, w.wProfile)
    }

    /**
     * Push #67: build a blended query vector that mixes the explicit
     * anchor song's embedding with the session vector (last-N qualified
     * listens) and the profile vector (top-30 long-term centroid).
     * Used as the query for `recommendUpcoming` so the recommender
     * reflects all three time scales.
     *
     * Capacitor parity (`_buildBlendedVec` in engine.js).
     */
    suspend fun buildBlendedQuery(
        currentSongHash: String?,
        sessionListened: List<com.isaivazhi.app.engine.SessionEngine.ListenedEntry>,
        profileVec: FloatArray?,
        library: List<Song>,
        mode: String = "play",
        overallScores: Map<String, Float> = emptyMap(),
        currentAnchorEligible: Boolean = true,
        manualBlend: Boolean = false,
        manualCurrentWeight: Float = TasteEngine.DEFAULT_MANUAL_CURRENT_WEIGHT,
        manualSessionWeight: Float = TasteEngine.DEFAULT_MANUAL_SESSION_WEIGHT,
        sessionBias: Float = 0.5f,
    ): BlendedQueryDetails {
        val byFilename = library.associateBy { it.filename }
        // Build session vec weighted by listen depth (fraction). Songs heard
        // more completely pull the session centroid harder than songs barely
        // past the 0.30 gate. If the same song was heard multiple times this
        // session, its fractions are summed as its total weight.
        val sessionWeightsByHash = LinkedHashMap<String, Float>()
        for (entry in sessionListened) {
            val hash = byFilename[entry.filename]?.contentHash ?: continue
            if (entry.contribution <= 0f) continue
            val tasteBoost = 1f + (
                (overallScores[entry.filename] ?: 0f).coerceAtLeast(0f) /
                    UnifiedTasteContract.TASTE_SCORE_NORMALIZER
                )
            sessionWeightsByHash[hash] =
                (sessionWeightsByHash[hash] ?: 0f) + entry.contribution * tasteBoost
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
        val hasCurrent = currentVec != null && currentAnchorEligible
        val hasSession = sessionVec != null
        val hasProfile = profileVec != null && profileVec.isNotEmpty()
        val nListened = sessionListened.sumOf { it.evidenceUnits.toDouble() }.toFloat()
        var w = if (manualBlend) {
            val manual = BlendWeightLogic.computeManual(
                currentWeight = manualCurrentWeight,
                sessionWeight = manualSessionWeight,
                hasCurrent = hasCurrent,
                hasSession = hasSession,
                hasProfile = hasProfile,
            )
            BlendWeights(manual.wCurrent, manual.wSession, manual.wProfile)
        } else {
            blendWeights(mode, nListened, hasCurrent, hasSession, hasProfile)
        }
        // Apply sessionBias to bias session vs profile (Capacitor parity).
        w = BlendWeightLogic.applySessionBias(
            weights = BlendWeightLogic.Weights(w.wCurrent, w.wSession, w.wProfile),
            sessionBias = sessionBias,
            manualBlend = manualBlend,
        ).let { BlendWeights(it.wCurrent, it.wSession, it.wProfile) }
        if (!hasCurrent && !hasSession && !hasProfile) {
            return BlendedQueryDetails(null, w, "empty", currentVec, sessionVec, profileVec)
        }
        // Choose a dim from any available vec.
        val dim = currentVec?.size ?: sessionVec?.size ?: profileVec?.size ?: 0
        if (dim == 0) {
            return BlendedQueryDetails(null, w, "no_dim", currentVec, sessionVec, profileVec)
        }
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
        return BlendedQueryDetails(out, w, label, currentVec, sessionVec, profileVec)
    }

    suspend fun buildBlendedVec(
        currentSongHash: String?,
        sessionListened: List<SessionEngine.ListenedEntry>,
        profileVec: FloatArray?,
        library: List<Song>,
        mode: String = "play",
        sessionBias: Float = 0.5f,
    ): Triple<FloatArray?, BlendWeights, String> {
        val details = buildBlendedQuery(
            currentSongHash = currentSongHash,
            sessionListened = sessionListened,
            profileVec = profileVec,
            library = library,
            mode = mode,
            sessionBias = sessionBias,
        )
        return Triple(details.queryVector, details.weights, details.label)
    }

    suspend fun explainRecommendations(
        songs: List<Song>,
        details: BlendedQueryDetails,
        tasteRows: Map<String, TasteEngine.DecoratedRow>,
        negativeGuard: Float,
        tasteInfluence: Float = TasteEngine.DEFAULT_TASTE_INFLUENCE,
        negativeFilteredCount: Int,
        origin: QueueItemOrigin = QueueItemOrigin.AI,
    ): List<RecommendationExplanation> {
        if (songs.isEmpty()) return emptyList()
        val queueGenerationId = java.util.UUID.randomUUID().toString()
        val hashes = songs.mapNotNull { it.contentHash }.distinct()
        val vectors = if (hashes.isEmpty()) emptyMap() else embeddingDb.getVecsByHashes(hashes)

        fun cosine(left: FloatArray?, right: FloatArray?): Float {
            if (left == null || right == null || left.size != right.size || left.isEmpty()) return 0f
            var dot = 0f
            var leftNorm = 0f
            var rightNorm = 0f
            for (i in left.indices) {
                dot += left[i] * right[i]
                leftNorm += left[i] * left[i]
                rightNorm += right[i] * right[i]
            }
            if (leftNorm <= 0f || rightNorm <= 0f) return 0f
            return dot / sqrt(leftNorm * rightNorm)
        }

        data class Pending(
            val song: Song,
            val current: Float,
            val session: Float,
            val profile: Float,
            val totalScore: Float,
            val tasteAdjustment: Float,
            val relevance: Float,
            val finalRank: Int,
        )

        val pending = songs.mapIndexed { index, song ->
            val vector = song.contentHash?.let { vectors[it] }
            val current = details.weights.wCurrent * cosine(details.currentVector, vector)
            val session = details.weights.wSession * cosine(details.sessionVector, vector)
            val profile = details.weights.wProfile * cosine(details.profileVector, vector)
            val breakdown = tasteRows[song.filename]?.breakdown
            val total = breakdown?.score ?: 0f
            val audioRelevance = details.queryVector?.let { queryVec ->
                cosine(queryVec, vector)
            } ?: (current + session + profile)
            val adjustment = UnifiedTasteContract.tasteAdjustment(
                total,
                negativeGuard,
                tasteInfluence,
            )
            Pending(
                song = song,
                current = current,
                session = session,
                profile = profile,
                totalScore = total,
                tasteAdjustment = adjustment,
                relevance = UnifiedTasteContract.finalRelevance(
                    audioRelevance = audioRelevance,
                    totalScore = total,
                    negativeGuard = negativeGuard,
                    tasteInfluence = tasteInfluence,
                ),
                finalRank = index + 1,
            )
        }
        val relevanceRanks = pending.sortedByDescending { it.relevance }
            .mapIndexed { index, row -> row.song.filename to index + 1 }
            .toMap()

        return pending.map { row ->
            val breakdown = tasteRows[row.song.filename]?.breakdown
            RecommendationExplanation(
                queueGenerationId = queueGenerationId,
                filename = row.song.filename,
                origin = origin,
                currentContribution = row.current,
                sessionContribution = row.session,
                profileContribution = row.profile,
                directScore = breakdown?.directScore ?: 0f,
                similarityScore = breakdown?.let { it.score - it.directScore } ?: 0f,
                totalScore = row.totalScore,
                tasteAdjustment = row.tasteAdjustment,
                relevanceRank = relevanceRanks[row.song.filename] ?: row.finalRank,
                finalRank = row.finalRank,
                negativeFilteredCount = negativeFilteredCount,
            )
        }
    }

    private fun tasteTier(score: Float): String = when {
        score >= 1f -> "strong_positive"
        score > 0.1f -> "positive"
        score < 0f && UnifiedTasteContract.isLearnedNegativeBlocked(score, 1f) -> "hard_negative"
        score < 0f -> "negative"
        else -> "neutral"
    }

    private fun guardDecisionLabel(
        filename: String,
        score: Float,
        policyExcludes: Set<String>,
        negativeGuard: Float,
    ): String = when {
        filename in policyExcludes -> "blocked"
        score < 0f && UnifiedTasteContract.isLearnedNegativeBlocked(score, negativeGuard) -> "guard_block"
        score < 0f -> "guard_pass"
        else -> "eligible"
    }

    private fun emitGenerationTrace(
        reason: String,
        tasteInfluence: Float,
        negativeGuard: Float,
        candidateCount: Int,
        selected: List<Song>,
        audioByFilename: Map<String, Float>,
        preRankByFilename: Map<String, Int>,
        totalTasteScores: Map<String, Float>,
        policyExcludes: Set<String>,
        rejected: List<RecommendationCandidateTrace>,
    ) {
        val sink = traceSink ?: return
        val finalRankByFilename = selected.withIndex().associate { (idx, song) ->
            song.filename to (idx + 1)
        }
        val selectedTraces = selected.map { song ->
            val score = totalTasteScores[song.filename] ?: 0f
            RecommendationCandidateTrace(
                filename = song.filename,
                audioSimilarity = audioByFilename[song.filename] ?: 0f,
                overallScore = score,
                tier = tasteTier(score),
                tasteAffinity = UnifiedTasteContract.tasteAdjustment(
                    score,
                    negativeGuard,
                    tasteInfluence,
                ),
                guardDecision = guardDecisionLabel(song.filename, score, policyExcludes, negativeGuard),
                preDiversityRank = preRankByFilename[song.filename] ?: 0,
                diversityScore = null,
                finalRank = finalRankByFilename[song.filename],
                rejectionReason = null,
            )
        }
        sink(
            RecommendationGenerationTrace(
                reason = reason,
                tasteInfluence = tasteInfluence,
                negativeGuard = negativeGuard,
                candidateCount = candidateCount,
                selectedCount = selected.size,
                candidates = selectedTraces + rejected,
            ),
        )
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
        adventurous: Float = 0.4f,
        extraExcludeFilenames: Set<String> = emptySet(),
        // Push #63: songs excluded by taste policy before MMR.
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
        totalTasteScores: Map<String, Float> = emptyMap(),
        negativeGuard: Float = TasteEngine.DEFAULT_NEGATIVE_STRENGTH,
        tasteInfluence: Float = TasteEngine.DEFAULT_TASTE_INFLUENCE,
        traceReason: String = "up_next",
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
        val rawCandidates: List<EmbeddingDbFacade.NnResult> = if (blendedQueryVec != null) {
            val scored = nearestNeighborsForVector(
                queryVec = blendedQueryVec,
                library = library,
                k = overFetch,
                excludeFilenames = extraExcludeFilenames + policyExcludes + setOf(currentSong.filename),
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
        if (rawCandidates.isEmpty()) {
            emitGenerationTrace(
                reason = traceReason,
                tasteInfluence = tasteInfluence,
                negativeGuard = negativeGuard,
                candidateCount = 0,
                selected = emptyList(),
                audioByFilename = emptyMap(),
                preRankByFilename = emptyMap(),
                totalTasteScores = totalTasteScores,
                policyExcludes = policyExcludes,
                rejected = emptyList(),
            )
            return emptyList()
        }
        val audioByFilename = rawCandidates.associate { it.filename to it.similarity }
        val candidates = rawCandidates
            .map { row ->
                row.copy(
                    similarity = UnifiedTasteContract.finalRelevance(
                        audioRelevance = row.similarity,
                        totalScore = totalTasteScores[row.filename] ?: 0f,
                        negativeGuard = negativeGuard,
                        tasteInfluence = tasteInfluence,
                    )
                )
            }
            .sortedByDescending { it.similarity }
        val preRankByFilename = candidates.withIndex().associate { (idx, row) ->
            row.filename to (idx + 1)
        }

        // Step 2: load candidate vectors in one batch for MMR redundancy
        // scoring. ~150 vectors × 2 KB = 300 KB over the bridge — small.
        val byFilename = library.associateBy { it.filename }
        val byHash: Map<String, Song> = library
            .filter { it.contentHash != null }
            .associateBy { it.contentHash!! }

        val rejectedTraces = ArrayList<RecommendationCandidateTrace>()
        val viable = candidates.mapNotNull { row ->
            if (row.filename in extraExcludeFilenames) {
                rejectedTraces += rejectedTrace(
                    row, audioByFilename, totalTasteScores, policyExcludes,
                    negativeGuard, tasteInfluence, preRankByFilename, "excluded",
                )
                return@mapNotNull null
            }
            if (row.filename in policyExcludes) {
                rejectedTraces += rejectedTrace(
                    row, audioByFilename, totalTasteScores, policyExcludes,
                    negativeGuard, tasteInfluence, preRankByFilename, "policy_block",
                )
                return@mapNotNull null
            }
            val song = byFilename[row.filename]
                ?: row.contentHash.takeIf { it.isNotBlank() }?.let { byHash[it] }
                ?: run {
                    rejectedTraces += rejectedTrace(
                        row, audioByFilename, totalTasteScores, policyExcludes,
                        negativeGuard, tasteInfluence, preRankByFilename, "missing_song",
                    )
                    return@mapNotNull null
                }
            if (song.filePath == null) {
                rejectedTraces += rejectedTrace(
                    row, audioByFilename, totalTasteScores, policyExcludes,
                    negativeGuard, tasteInfluence, preRankByFilename, "no_file",
                )
                return@mapNotNull null
            }
            song to row
        }
        if (viable.isEmpty()) {
            emitGenerationTrace(
                reason = traceReason,
                tasteInfluence = tasteInfluence,
                negativeGuard = negativeGuard,
                candidateCount = candidates.size,
                selected = emptyList(),
                audioByFilename = audioByFilename,
                preRankByFilename = preRankByFilename,
                totalTasteScores = totalTasteScores,
                policyExcludes = policyExcludes,
                rejected = rejectedTraces,
            )
            return emptyList()
        }

        // Pure-relevance fast path: lambda >= 0.95 means MMR penalty is
        // effectively zero, skip the batch vector load entirely.
        val selected = if (lambda >= 0.95f) {
            viable.asSequence().map { it.first }.take(k).toList()
        } else {
            val candidateHashes = viable.map { it.second.contentHash }.filter { it.isNotBlank() }
            val vecs = embeddingDb.getVecsByHashes(candidateHashes)
            if (vecs.isEmpty()) {
                viable.asSequence().map { it.first }.take(k).toList()
            } else {
                val poolEntries = viable.mapNotNull { (song, row) ->
                    val v = vecs[row.contentHash] ?: return@mapNotNull null
                    PoolEntry(song = song, similarity = row.similarity, vec = v)
                }
                if (poolEntries.isEmpty()) {
                    viable.asSequence().map { it.first }.take(k).toList()
                } else {
                    mmrRerank(poolEntries, k, lambda).map { it.song }
                }
            }
        }
        emitGenerationTrace(
            reason = traceReason,
            tasteInfluence = tasteInfluence,
            negativeGuard = negativeGuard,
            candidateCount = candidates.size,
            selected = selected,
            audioByFilename = audioByFilename,
            preRankByFilename = preRankByFilename,
            totalTasteScores = totalTasteScores,
            policyExcludes = policyExcludes,
            rejected = rejectedTraces,
        )
        return selected
    }

    private fun rejectedTrace(
        row: EmbeddingDbFacade.NnResult,
        audioByFilename: Map<String, Float>,
        totalTasteScores: Map<String, Float>,
        policyExcludes: Set<String>,
        negativeGuard: Float,
        tasteInfluence: Float,
        preRankByFilename: Map<String, Int>,
        rejectionReason: String,
    ): RecommendationCandidateTrace {
        val score = totalTasteScores[row.filename] ?: 0f
        return RecommendationCandidateTrace(
            filename = row.filename,
            audioSimilarity = audioByFilename[row.filename] ?: row.similarity,
            overallScore = score,
            tier = tasteTier(score),
            tasteAffinity = UnifiedTasteContract.tasteAdjustment(score, negativeGuard, tasteInfluence),
            guardDecision = guardDecisionLabel(row.filename, score, policyExcludes, negativeGuard),
            preDiversityRank = preRankByFilename[row.filename] ?: 0,
            diversityScore = null,
            finalRank = null,
            rejectionReason = rejectionReason,
        )
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
        adventurous: Float = 0.4f,
        extraExcludeFilenames: Set<String> = emptySet(),
        hardBlockedFilenames: Set<String> = emptySet(),
        dislikedFilenames: Set<String> = emptySet(),
        softExcludedFilenames: Set<String> = emptySet(),
        totalTasteScores: Map<String, Float> = emptyMap(),
        negativeGuard: Float = TasteEngine.DEFAULT_NEGATIVE_STRENGTH,
        tasteInfluence: Float = TasteEngine.DEFAULT_TASTE_INFLUENCE,
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
                song to UnifiedTasteContract.finalRelevance(
                    audioRelevance = sim,
                    totalScore = totalTasteScores[song.filename] ?: 0f,
                    negativeGuard = negativeGuard,
                    tasteInfluence = tasteInfluence,
                )
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
                totalTasteScores = totalTasteScores,
                negativeGuard = negativeGuard,
                tasteInfluence = tasteInfluence,
                traceReason = "soft_refresh",
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

    /**
     * Builds an embedding-driven queue when [currentSong] is embedded; falls
     * back to a shuffled tail of [library] otherwise. Always puts
     * [currentSong] at index 0 so the queue is play-ready.
     */
    suspend fun buildPlayQueue(
        currentSong: Song,
        library: List<Song>,
        k: Int = 50,
        adventurous: Float = 0.4f,
        // Push #63: forwarded into recommendUpcoming.
        extraExcludeFilenames: Set<String> = emptySet(),
        hardBlockedFilenames: Set<String> = emptySet(),
        dislikedFilenames: Set<String> = emptySet(),
        softExcludedFilenames: Set<String> = emptySet(),
        // Push #72: optional blended query vector. When non-null, the
        // recommender ranks against the blend (current + session + profile)
        // instead of only the current song's neighbors.
        blendedQueryVec: FloatArray? = null,
        totalTasteScores: Map<String, Float> = emptyMap(),
        negativeGuard: Float = TasteEngine.DEFAULT_NEGATIVE_STRENGTH,
        tasteInfluence: Float = TasteEngine.DEFAULT_TASTE_INFLUENCE,
    ): List<Song> {
        val recs = recommendUpcoming(
            currentSong, library, k, adventurous,
            extraExcludeFilenames = extraExcludeFilenames,
            hardBlockedFilenames = hardBlockedFilenames,
            dislikedFilenames = dislikedFilenames,
            softExcludedFilenames = softExcludedFilenames,
            blendedQueryVec = blendedQueryVec,
            totalTasteScores = totalTasteScores,
            negativeGuard = negativeGuard,
            tasteInfluence = tasteInfluence,
            traceReason = "play_queue",
        )
        val head = listOf(currentSong)
        val tail = if (recs.isNotEmpty()) {
            recs
        } else {
            val policyExcludes = RecommendationPolicy.unionExcludes(
                hardBlockedFilenames, softExcludedFilenames, dislikedFilenames,
            )
            val excludes = extraExcludeFilenames + policyExcludes + setOf(currentSong.filename)
            library.asSequence()
                .filter { it.filePath != null && it.filename !in excludes }
                .shuffled()
                .take(k)
                .toList()
        }
        return head + tail
    }

    /** Result row with similarity score. */
    data class ScoredSong(val song: Song, val similarity: Float)

    /**
     * Top-k similar to a song with similarity scores. Used by the
     * "Similar" row on Now Playing.
     *
     * Bugfix: this path previously ignored the taste-signal exclusion sets
     * entirely, so a strongly-negative / disliked song (e.g. one the user
     * kept skipping to a negative directScore, or thumbs-downed) would still
     * surface here purely as an embedding neighbor of the current track.
     * We now apply the same Up-Next exclusion policy (hard-block + soft
     * exclude + thumbs-down) and over-fetch so the row still returns up to
     * [k] results after filtering.
     */
    suspend fun recommendScored(
        currentSong: Song,
        library: List<Song>,
        k: Int = 10,
        dislikedFilenames: Set<String> = emptySet(),
        hardBlockedFilenames: Set<String> = emptySet(),
        softExcludedFilenames: Set<String> = emptySet(),
    ): List<ScoredSong> {
        val policyExcludes = RecommendationPolicy.unionExcludes(
            hardBlockedFilenames, softExcludedFilenames, dislikedFilenames,
        )
        val excludeHashes = buildList {
            currentSong.contentHash?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        // Over-fetch when we have exclusions so the row can still return k
        // results after filtering out negatives.
        val fetchK = if (policyExcludes.isEmpty()) k
            else (k + policyExcludes.size).coerceAtMost(k * 4)
        val candidates = embeddingDb.nearestNeighborsForFilename(
            queryFilename = currentSong.filename,
            k = fetchK,
            excludeHashes = excludeHashes,
        )
        return resolveSimilarCandidates(candidates, library, policyExcludes, k)
    }

    private data class PoolEntry(
        val song: Song,
        val similarity: Float,
        val vec: FloatArray,
    )
}
