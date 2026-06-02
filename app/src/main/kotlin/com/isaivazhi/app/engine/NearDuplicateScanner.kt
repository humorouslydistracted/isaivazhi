package com.isaivazhi.app.engine

import com.isaivazhi.app.NativeAccelerator
import kotlin.math.sqrt

/**
 * Finds clusters of different [content_hash] values whose CLAP embeddings are
 * nearly identical (e.g. same song, different encode/format).
 */
object NearDuplicateScanner {

    const val DEFAULT_THRESHOLD = 0.97f
    const val MAX_REPRESENTATIVES = 5000

    data class ScanInput(
        val hashToVec: Map<String, FloatArray>,
        val hashToFilepaths: Map<String, List<String>>,
    )

    data class NearCluster(
        val representativeHash: String,
        val contentHashes: List<String>,
        val filepaths: List<String>,
        val minSimilarity: Float,
    )

    fun scan(input: ScanInput, threshold: Float = DEFAULT_THRESHOLD): List<NearCluster> {
        val hashes = input.hashToVec.keys
            .filter { h -> h.isNotEmpty() && input.hashToVec[h]?.isNotEmpty() == true }
            .sorted()
        val n = hashes.size
        if (n < 2) return emptyList()
        if (n > MAX_REPRESENTATIVES) return emptyList()

        val dim = input.hashToVec[hashes[0]]!!.size
        val flat = FloatArray(n * dim)
        for (i in hashes.indices) {
            val v = input.hashToVec[hashes[i]] ?: return emptyList()
            if (v.size != dim) return emptyList()
            System.arraycopy(v, 0, flat, i * dim, dim)
        }

        val uf = UnionFind(n)
        val pairSim = HashMap<Long, Float>(n * 4)

        for (i in 0 until n) {
            val subCount = n - i - 1
            if (subCount <= 0) break
            val sims = FloatArray(subCount)
            val queryOffset = i * dim
            val subOffset = (i + 1) * dim
            var usedNative = false
            if (NativeAccelerator.isAvailable()) {
                val query = FloatArray(dim)
                System.arraycopy(flat, queryOffset, query, 0, dim)
                val subVectors = FloatArray(subCount * dim)
                System.arraycopy(flat, subOffset, subVectors, 0, subVectors.size)
                usedNative = NativeAccelerator.dotProductBatch(query, subVectors, subCount, dim, sims)
            }
            if (!usedNative) {
                computeCosineBlock(flat, dim, i, i + 1, n, sims)
            } else {
                // Vectors are L2-normalized at embed time; batch returns dot products.
                // Re-normalize block in case of drift.
                val query = FloatArray(dim)
                System.arraycopy(flat, queryOffset, query, 0, dim)
                var qn = 0f
                for (d in 0 until dim) qn += query[d] * query[d]
                val qInv = if (qn > 1e-8f) 1f / sqrt(qn) else 0f
                for (k in 0 until subCount) {
                    val j = i + 1 + k
                    var vn = 0f
                    for (d in 0 until dim) {
                        val vv = flat[j * dim + d]
                        vn += vv * vv
                    }
                    val vInv = if (vn > 1e-8f) 1f / sqrt(vn) else 0f
                    sims[k] = if (qInv > 0f && vInv > 0f) sims[k] * qInv * vInv else sims[k]
                }
            }
            for (k in 0 until subCount) {
                val sim = sims[k]
                if (sim < threshold) continue
                val j = i + 1 + k
                uf.union(i, j)
                pairSim[i.toLong() * n + j] = sim
            }
        }

        val clusters = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val root = uf.find(i)
            clusters.getOrPut(root) { mutableListOf() }.add(i)
        }

        val out = ArrayList<NearCluster>()
        for (indices in clusters.values) {
            if (indices.size < 2) continue
            val clusterHashes = indices.map { hashes[it] }
            val allPaths = linkedSetOf<String>()
            for (h in clusterHashes) {
                input.hashToFilepaths[h]?.forEach { fp ->
                    if (fp.isNotEmpty()) allPaths.add(fp)
                }
            }
            if (allPaths.size < 2) continue

            var minSim = 1f
            for (a in indices) {
                for (b in indices) {
                    if (a >= b) continue
                    val s = pairSim[a.toLong() * n + b] ?: continue
                    if (s < minSim) minSim = s
                }
            }
            if (minSim > 1f) minSim = threshold

            out += NearCluster(
                representativeHash = clusterHashes.first(),
                contentHashes = clusterHashes,
                filepaths = allPaths.sorted(),
                minSimilarity = minSim,
            )
        }
        return out.sortedByDescending { it.filepaths.size }
    }

    private fun computeCosineBlock(
        flat: FloatArray,
        dim: Int,
        queryRow: Int,
        startRow: Int,
        endRow: Int,
        out: FloatArray,
    ) {
        val qOff = queryRow * dim
        var qn = 0f
        for (d in 0 until dim) {
            val qv = flat[qOff + d]
            qn += qv * qv
        }
        val qInv = if (qn > 1e-8f) 1f / sqrt(qn) else 0f
        var k = 0
        for (j in startRow until endRow) {
            val vOff = j * dim
            var dot = 0f
            var vn = 0f
            for (d in 0 until dim) {
                val qv = flat[qOff + d]
                val vv = flat[vOff + d]
                dot += qv * vv
                vn += vv * vv
            }
            val vInv = if (vn > 1e-8f) 1f / sqrt(vn) else 0f
            out[k++] = if (qInv > 0f && vInv > 0f) dot * qInv * vInv else 0f
        }
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var cur = x
            while (parent[cur] != cur) {
                val next = parent[cur]
                parent[cur] = r
                cur = next
            }
            return r
        }

        fun union(a: Int, b: Int) {
            var ra = find(a)
            var rb = find(b)
            if (ra == rb) return
            if (rank[ra] < rank[rb]) {
                val t = ra
                ra = rb
                rb = t
            }
            parent[rb] = ra
            if (rank[ra] == rank[rb]) rank[ra]++
        }
    }
}
