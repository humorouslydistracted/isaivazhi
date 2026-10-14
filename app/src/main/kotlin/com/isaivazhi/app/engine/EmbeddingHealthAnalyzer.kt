package com.isaivazhi.app.engine

import kotlin.math.abs

/**
 * Library-wide embedding health check, done entirely from the stored
 * vectors (no audio is decoded).
 *
 * For every song we find its top-[k] neighbours and count how often each
 * song appears in somebody else's list ("k-occurrence"). With k = 50 the
 * average is exactly 50, so:
 *  - occurrence 0          → orphan: never anyone's neighbour, so Up Next
 *                            built from similarity basically never reaches it.
 *  - occurrence ≤ k/10     → low exposure (discovery slot candidates).
 *  - occurrence ≥ 5·k      → hub: shows up in a huge share of lists.
 *
 * The same counts are computed in raw space (what the recommender uses
 * today) and centred space ("Sharper similarity"), so the effect of that
 * setting is visible before turning it on.
 *
 * Suspects are songs whose vector may be wrong:
 *  - invalid: zero / NaN vector.
 *  - near-identical cluster: ≥ [NEAR_DUP_MIN_OTHERS] other songs at
 *    cosine ≥ [NEAR_DUP_SIM]. Different songs should never be that close;
 *    a cluster usually means the audio failed to decode the same way.
 *  - outlier: its closest neighbour is far less similar than is typical for
 *    this library (median − 4·MAD of nearest-neighbour similarity). Usually
 *    just an unusual song; sometimes a bad decode or a silent intro.
 */
class EmbeddingHealthAnalyzer(
    /** Same contract as NativeAccelerator.dotProductBatch; return false to use the Kotlin loop. */
    private val batchDot: (query: FloatArray, vectors: FloatArray, count: Int, dim: Int, out: FloatArray) -> Boolean =
        { _, _, _, _, _ -> false },
) {
    companion object {
        const val DEFAULT_K = 50
        const val NEAR_DUP_SIM = 0.995f
        const val NEAR_DUP_MIN_OTHERS = 2
        const val OUTLIER_MADS = 4f
        fun lowExposureMax(k: Int) = k / 10
        fun hubMin(k: Int) = k * 5
    }

    /** Declaration order is the display order: outliers are often just unusual songs, so they come last. */
    enum class SuspectReason { INVALID_VECTOR, NEAR_IDENTICAL_CLUSTER, OUTLIER }

    data class Suspect(
        val key: String,
        val reason: SuspectReason,
        val nearestSimilarity: Float,
        val nearIdenticalCount: Int,
    )

    data class SpaceResult(
        /** key → number of other songs' top-k lists containing it. */
        val occurrence: Map<String, Int>,
    ) {
        fun orphans(): List<String> = occurrence.filterValues { it == 0 }.keys.toList()
        fun lowExposure(k: Int): Set<String> =
            occurrence.filterValues { it <= lowExposureMax(k) }.keys
        /** Orphans and low-exposure songs together, fewest lists first. */
        fun hardToReach(k: Int): List<String> =
            occurrence.entries.filter { it.value <= lowExposureMax(k) }.sortedBy { it.value }.map { it.key }
        fun hubs(k: Int): List<String> = occurrence.filterValues { it >= hubMin(k) }.keys.toList()
        fun mostSurfaced(limit: Int): List<String> =
            occurrence.entries.sortedByDescending { it.value }.take(limit).map { it.key }
        val maxOccurrence: Int get() = occurrence.values.maxOrNull() ?: 0
    }

    data class Report(
        val k: Int,
        val songCount: Int,
        val raw: SpaceResult,
        val centered: SpaceResult,
        val suspects: List<Suspect>,
        /** key → similarity of its closest other song (raw space). */
        val nearestSimilarity: Map<String, Float>,
        val medianNearestSimilarity: Float,
    )

    /**
     * [vectors] maps a stable key (content hash) to its raw embedding.
     * [onProgress] receives a 0..1 fraction; [isCancelled] is polled
     * between rows so a coroutine can stop early.
     */
    fun analyze(
        vectors: Map<String, FloatArray>,
        k: Int = DEFAULT_K,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Report {
        val dim = vectors.values.firstOrNull()?.size ?: 0
        val invalid = ArrayList<String>()
        val keys = ArrayList<String>(vectors.size)
        val rows = ArrayList<FloatArray>(vectors.size)
        for ((key, v) in vectors) {
            if (v.size != dim || dim == 0 || !isFinite(v) || isZero(v)) {
                invalid += key
                continue
            }
            keys += key
            rows += v
        }
        val n = keys.size
        val kEff = minOf(k, (n - 1).coerceAtLeast(0))
        val rawFlat = flatten(rows.map { EmbeddingSpace.unit(it) }, dim)
        val mean = EmbeddingSpace.libraryMean(rows, dim) ?: FloatArray(dim)
        val centeredFlat = flatten(rows.map { EmbeddingSpace.centeredUnit(it, mean) }, dim)

        val nearest = FloatArray(n)
        val nearDup = IntArray(n)
        val rawOcc = pass(rawFlat, n, dim, kEff, nearest, nearDup, { onProgress(it * 0.5f) }, isCancelled)
        val cenOcc = pass(centeredFlat, n, dim, kEff, null, null, { onProgress(0.5f + it * 0.5f) }, isCancelled)
        onProgress(1f)

        val median = median(nearest)
        val mad = median(FloatArray(n) { abs(nearest[it] - median) })
        val outlierFloor = median - OUTLIER_MADS * mad
        val suspects = ArrayList<Suspect>()
        for (key in invalid) suspects += Suspect(key, SuspectReason.INVALID_VECTOR, 0f, 0)
        for (i in 0 until n) {
            when {
                nearDup[i] >= NEAR_DUP_MIN_OTHERS ->
                    suspects += Suspect(keys[i], SuspectReason.NEAR_IDENTICAL_CLUSTER, nearest[i], nearDup[i])
                n >= 20 && mad > 0f && nearest[i] < outlierFloor ->
                    suspects += Suspect(keys[i], SuspectReason.OUTLIER, nearest[i], nearDup[i])
            }
        }
        suspects.sortWith(
            compareBy<Suspect> { it.reason.ordinal }
                .thenByDescending { it.nearIdenticalCount }
                .thenBy { it.nearestSimilarity },
        )
        return Report(
            k = kEff,
            songCount = n + invalid.size,
            raw = SpaceResult(keys.indices.associate { keys[it] to rawOcc[it] }),
            centered = SpaceResult(keys.indices.associate { keys[it] to cenOcc[it] }),
            suspects = suspects,
            nearestSimilarity = keys.indices.associate { keys[it] to nearest[it] },
            medianNearestSimilarity = median,
        )
    }

    private fun pass(
        flat: FloatArray,
        n: Int,
        dim: Int,
        k: Int,
        nearestOut: FloatArray?,
        nearDupOut: IntArray?,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): IntArray {
        val occ = IntArray(n)
        if (n == 0 || k == 0) return occ
        val query = FloatArray(dim)
        val scores = FloatArray(n)
        val heapIdx = IntArray(k)
        val heapVal = FloatArray(k)
        val reportEvery = (n / 50).coerceAtLeast(1)
        for (i in 0 until n) {
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("embedding health cancelled")
            System.arraycopy(flat, i * dim, query, 0, dim)
            if (!batchDot(query, flat, n, dim, scores)) {
                for (j in 0 until n) {
                    var d = 0f
                    val base = j * dim
                    for (t in 0 until dim) d += query[t] * flat[base + t]
                    scores[j] = d
                }
            }
            var size = 0
            var best = -Float.MAX_VALUE
            var dups = 0
            for (j in 0 until n) {
                if (j == i) continue
                val s = scores[j]
                if (s > best) best = s
                if (s >= NEAR_DUP_SIM) dups++
                if (size < k) {
                    heapIdx[size] = j; heapVal[size] = s; size++
                    if (size == k) buildMinHeap(heapIdx, heapVal, size)
                } else if (s > heapVal[0]) {
                    heapIdx[0] = j; heapVal[0] = s
                    siftDown(heapIdx, heapVal, 0, size)
                }
            }
            for (h in 0 until size) occ[heapIdx[h]]++
            nearestOut?.set(i, if (best == -Float.MAX_VALUE) 0f else best)
            nearDupOut?.set(i, dups)
            if (i % reportEvery == 0) onProgress(i.toFloat() / n)
        }
        return occ
    }

    private fun buildMinHeap(idx: IntArray, v: FloatArray, size: Int) {
        for (i in size / 2 - 1 downTo 0) siftDown(idx, v, i, size)
    }

    private fun siftDown(idx: IntArray, v: FloatArray, start: Int, size: Int) {
        var i = start
        while (true) {
            val l = 2 * i + 1
            val r = l + 1
            var m = i
            if (l < size && v[l] < v[m]) m = l
            if (r < size && v[r] < v[m]) m = r
            if (m == i) return
            val ti = idx[i]; idx[i] = idx[m]; idx[m] = ti
            val tv = v[i]; v[i] = v[m]; v[m] = tv
            i = m
        }
    }

    private fun flatten(rows: List<FloatArray>, dim: Int): FloatArray {
        val out = FloatArray(rows.size * dim)
        for ((i, r) in rows.withIndex()) System.arraycopy(r, 0, out, i * dim, dim)
        return out
    }

    private fun isFinite(v: FloatArray): Boolean = v.all { it.isFinite() }
    private fun isZero(v: FloatArray): Boolean = v.all { it == 0f }

    private fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val s = values.copyOf().also { it.sort() }
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2f
    }
}
