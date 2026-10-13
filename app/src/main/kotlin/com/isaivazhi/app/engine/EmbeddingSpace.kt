package com.isaivazhi.app.engine

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Vector-space helpers for the optional "Sharper similarity" mode and the
 * "Discovery slot" mode. Everything here is pure Kotlin so it can be unit
 * tested without Android.
 *
 * Sharper similarity = cosine after subtracting the library mean vector.
 * CLAP embeddings of one person's library all share a large common
 * component, which squeezes every pair into a narrow 0.7–0.95 band and
 * lets a few "average sounding" songs dominate every neighbour list.
 * Centering removes that shared part so ranking follows what actually
 * differs between songs. Stored vectors are never modified.
 */
object EmbeddingSpace {

    /** Mean of the L2-normalised vectors (not itself renormalised). */
    fun libraryMean(vectors: Collection<FloatArray>, dim: Int): FloatArray? {
        if (dim <= 0) return null
        val sum = DoubleArray(dim)
        var count = 0
        for (v in vectors) {
            if (v.size != dim) continue
            var n2 = 0.0
            for (x in v) n2 += x.toDouble() * x
            if (n2 <= 0.0 || n2.isNaN()) continue
            val inv = 1.0 / sqrt(n2)
            for (i in 0 until dim) sum[i] += v[i] * inv
            count++
        }
        if (count == 0) return null
        return FloatArray(dim) { (sum[it] / count).toFloat() }
    }

    /** normalise(normalise(v) − mean); zero vector when degenerate. */
    fun centeredUnit(v: FloatArray, mean: FloatArray): FloatArray {
        val dim = v.size
        if (dim == 0 || mean.size != dim) return FloatArray(dim)
        var n2 = 0f
        for (x in v) n2 += x * x
        if (n2 <= 0f) return FloatArray(dim)
        val inv = 1f / sqrt(n2)
        val out = FloatArray(dim)
        var c2 = 0f
        for (i in 0 until dim) {
            val c = v[i] * inv - mean[i]
            out[i] = c
            c2 += c * c
        }
        if (c2 <= 0f) return FloatArray(dim)
        val cInv = 1f / sqrt(c2)
        for (i in 0 until dim) out[i] *= cInv
        return out
    }

    fun unit(v: FloatArray): FloatArray = EmbeddingSimilarity.normalize(v)

    fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var d = 0f
        for (i in 0 until n) d += a[i] * b[i]
        return d
    }

    /**
     * Scores raw candidate vectors against a query in centred space without
     * materialising a centred copy of every candidate:
     *   c = v̂ − m,  ‖c‖² = 1 − 2·v̂·m + ‖m‖²,  score = (v̂·q_c − m·q_c) / ‖c‖
     * Equal to dot(centeredUnit(query, mean), centeredUnit(v, mean)).
     */
    class CenteredQuery(query: FloatArray, private val mean: FloatArray) {
        private val cq = centeredUnit(query, mean)
        private val meanDotCq = dot(mean, cq)
        private val meanNorm2 = dot(mean, mean)

        fun score(v: FloatArray): Float {
            if (v.size != cq.size) return -1f
            var vq = 0f
            var vm = 0f
            var vv = 0f
            for (i in v.indices) {
                val x = v[i]
                vq += x * cq[i]
                vm += x * mean[i]
                vv += x * x
            }
            if (vv <= 0f) return -1f
            val inv = 1f / sqrt(vv)
            val c2 = 1f - 2f * vm * inv + meanNorm2
            if (c2 <= 1e-12f) return 0f
            return (vq * inv - meanDotCq) / sqrt(c2)
        }
    }

    data class Scored<T>(val item: T, val raw: Float, val centered: Float)

    /**
     * Order [items] by their centred score but hand out the raw similarity
     * values (sorted high → low) by position. The ranking changes; the set
     * of score magnitudes does not. Taste mixing in
     * [UnifiedTasteContract.finalRelevance] and the MMR redundancy term are
     * tuned for raw-cosine magnitudes, so this keeps their balance intact.
     */
    fun <T> calibrateByRank(items: List<Scored<T>>, k: Int = items.size): List<Pair<T, Float>> {
        if (items.isEmpty() || k <= 0) return emptyList()
        val byCentered = items.sortedByDescending { it.centered }
        val rawSorted = items.map { it.raw }.sortedDescending()
        val n = minOf(k, items.size)
        val out = ArrayList<Pair<T, Float>>(n)
        for (i in 0 until n) out += byCentered[i].item to rawSorted[i]
        return out
    }

    /** Value at quantile [q] (0..1) of [values]; linear interpolation. */
    fun quantile(values: FloatArray, q: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf().also { it.sort() }
        val pos = (q.coerceIn(0f, 1f) * (sorted.size - 1))
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }
}

/**
 * Discovery slots: roughly one in every [EVERY] Up Next positions goes to a
 * song that rarely shows up in anyone's neighbour list but is still a
 * reasonable match for the current query.
 */
object DiscoverySlots {
    const val EVERY = 10
    const val MIN_QUERY_QUANTILE = 0.75f
    /** Pick randomly among the best `count * SAMPLE_WIDTH` eligible songs for variety. */
    const val SAMPLE_WIDTH = 3

    fun slotCount(selectedSize: Int, every: Int = EVERY): Int =
        if (every <= 0) 0 else selectedSize / every

    /**
     * [scored] holds the query similarity for every song in the library
     * (used for the quantile floor). Returns up to [count] picks ordered by
     * similarity, chosen from songs passing [isEligible] whose similarity
     * is at or above the [minQuantile] of the whole library.
     */
    fun <T> pick(
        scored: List<Pair<T, Float>>,
        isEligible: (T) -> Boolean,
        count: Int,
        minQuantile: Float = MIN_QUERY_QUANTILE,
        random: Random = Random.Default,
    ): List<T> {
        if (count <= 0 || scored.isEmpty()) return emptyList()
        val floor = EmbeddingSpace.quantile(FloatArray(scored.size) { scored[it].second }, minQuantile)
        val pool = scored.asSequence()
            .filter { it.second >= floor && isEligible(it.first) }
            .sortedByDescending { it.second }
            .take(count * SAMPLE_WIDTH)
            .toList()
        if (pool.size <= count) return pool.map { it.first }
        return pool.indices.shuffled(random).take(count).sorted().map { pool[it].first }
    }

    /**
     * Insert [picks] at positions EVERY-1, 2·EVERY-1, … of [selected]. The
     * result never grows beyond [maxSize]; displaced tail songs drop off.
     */
    fun <T> inject(selected: List<T>, picks: List<T>, maxSize: Int, every: Int = EVERY): List<T> {
        if (picks.isEmpty() || every <= 0) return selected.take(maxSize)
        val out = ArrayList<T>(selected.size + picks.size)
        val pickIter = picks.iterator()
        val seen = HashSet<T>()
        for (song in selected) {
            if (out.size % every == every - 1 && pickIter.hasNext()) {
                val p = pickIter.next()
                if (seen.add(p)) out += p
            }
            if (seen.add(song)) out += song
        }
        return out.take(maxSize)
    }
}
