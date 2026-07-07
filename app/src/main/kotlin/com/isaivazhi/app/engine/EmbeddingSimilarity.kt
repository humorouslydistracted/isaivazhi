package com.isaivazhi.app.engine

import kotlin.math.sqrt

/**
 * Shared similarity math used by the Similar Songs row across heap, sqlite,
 * and native-fallback paths. Keeping these helpers in one place prevents the
 * paths from drifting onto different ranking formulas.
 */
object EmbeddingSimilarity {

    data class NeighborScore(
        val key: String,
        val similarity: Float,
    )

    @JvmStatic
    fun normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0f
        for (value in vector) sumSquares += value * value
        if (sumSquares <= 0f) return FloatArray(vector.size)
        val invNorm = 1f / sqrt(sumSquares)
        val out = FloatArray(vector.size)
        for (i in vector.indices) out[i] = vector[i] * invNorm
        return out
    }

    @JvmStatic
    fun cosineSimilarity(query: FloatArray, candidate: FloatArray): Float {
        val n = minOf(query.size, candidate.size)
        if (n == 0) return 0f
        var dot = 0f
        var queryNorm = 0f
        var candidateNorm = 0f
        for (i in 0 until n) {
            dot += query[i] * candidate[i]
            queryNorm += query[i] * query[i]
            candidateNorm += candidate[i] * candidate[i]
        }
        val denom = sqrt(queryNorm) * sqrt(candidateNorm)
        return if (denom > 0f) dot / denom else 0f
    }

    /**
     * Simulates the warmed Kotlin heap path: raw vectors are stored in cache,
     * so this path must normalize both sides at ranking time.
     */
    @JvmStatic
    fun rankAsHeapPath(
        query: FloatArray,
        candidates: Map<String, FloatArray>,
        excludeKeys: Set<String>,
        k: Int,
    ): List<NeighborScore> = rankByCosine(query, candidates, excludeKeys, k)

    /**
     * Simulates sqlite-vec ordering where ranking is true cosine distance.
     */
    @JvmStatic
    fun rankAsSqliteVec(
        query: FloatArray,
        candidates: Map<String, FloatArray>,
        excludeKeys: Set<String>,
        k: Int,
    ): List<NeighborScore> = rankByCosine(query, candidates, excludeKeys, k)

    /**
     * Simulates the native fallback after vectors are normalized up front and
     * ranking is done by dot product over unit vectors.
     */
    @JvmStatic
    fun rankAsNormalizedFallback(
        query: FloatArray,
        candidates: Map<String, FloatArray>,
        excludeKeys: Set<String>,
        k: Int,
    ): List<NeighborScore> {
        val normalizedQuery = normalize(query)
        val normalizedCandidates = LinkedHashMap<String, FloatArray>(candidates.size)
        for ((key, value) in candidates) {
            normalizedCandidates[key] = normalize(value)
        }
        return rankByNormalizedDot(normalizedQuery, normalizedCandidates, excludeKeys, k)
    }

    private fun rankByCosine(
        query: FloatArray,
        candidates: Map<String, FloatArray>,
        excludeKeys: Set<String>,
        k: Int,
    ): List<NeighborScore> {
        if (k <= 0 || query.isEmpty() || candidates.isEmpty()) return emptyList()
        return candidates.asSequence()
            .filter { (key, value) -> key !in excludeKeys && value.size == query.size }
            .map { (key, value) -> NeighborScore(key, cosineSimilarity(query, value)) }
            .sortedByDescending { it.similarity }
            .take(k)
            .toList()
    }

    private fun rankByNormalizedDot(
        normalizedQuery: FloatArray,
        normalizedCandidates: Map<String, FloatArray>,
        excludeKeys: Set<String>,
        k: Int,
    ): List<NeighborScore> {
        if (k <= 0 || normalizedQuery.isEmpty() || normalizedCandidates.isEmpty()) return emptyList()
        return normalizedCandidates.asSequence()
            .filter { (key, value) -> key !in excludeKeys && value.size == normalizedQuery.size }
            .map { (key, value) ->
                var dot = 0f
                for (i in normalizedQuery.indices) dot += normalizedQuery[i] * value[i]
                NeighborScore(key, dot)
            }
            .sortedByDescending { it.similarity }
            .take(k)
            .toList()
    }
}
