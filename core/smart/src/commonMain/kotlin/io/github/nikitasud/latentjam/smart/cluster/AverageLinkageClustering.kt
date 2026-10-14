/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlinx.coroutines.CancellationException
import kotlin.math.sqrt

/** Average-linkage regions on centered unit directions, independent of the 2D map. */
internal object AverageLinkageClustering {
    const val METADATA_WEIGHT: Float = 0.25f
    const val DISTANCE_CUT: Double = 0.75

    // A triangular DoubleArray uses at most 36 MB. Larger libraries retain the bounded k-means
    // path; never allocate an unbounded quadratic matrix or silently truncate the population.
    const val MAX_TRACKS: Int = 3_000

    fun cluster(
        space: LibraryVectorSpace,
        minSize: Int = TrackClustering.MIN_CLUSTER_SIZE,
        isActive: () -> Boolean = { true },
    ): List<TrackCluster> {
        require(minSize > 0) { "Minimum cluster size must be positive" }
        require(space.size <= MAX_TRACKS) { "Average linkage population exceeds memory limit" }
        if (space.size < minSize) return emptyList()
        checkActive(isActive)
        val rows = space.takeRowsForRegions(METADATA_WEIGHT)
        require(rows.all(Float::isFinite)) { "Library vector space contains a non-finite row" }
        TrackClustering.center(rows, space.size, space.dim)
        // A direction exactly equal to the corpus mean provides no clustering evidence.
        val usable = (0 until space.size).filter { row ->
            (0 until space.dim).any { d -> rows[row * space.dim + d] != 0f }
        }
        if (usable.size < minSize) return emptyList()
        val matrix = if (usable.size == space.size) rows else FloatArray(usable.size * space.dim).also { out ->
            usable.forEachIndexed { into, from ->
                rows.copyInto(out, into * space.dim, from * space.dim, (from + 1) * space.dim)
            }
        }
        val ids = if (usable.size == space.size) space.trackIds else usable.map(space.trackIds::get)
        val labels = assign(matrix, ids.size, space.dim, DISTANCE_CUT, isActive)
        checkActive(isActive)
        // The hierarchy's distance cut determines admission. K-means rival-centroid margins
        // would impose a second, different clustering criterion and discard valid subregions.
        return TrackClustering.collectRegions(matrix, ids.size, space.dim, labels, ids, minSize)
    }

    /** Nearest-neighbor chain UPGMA: quadratic linkage work after pairwise cosine distances. */
    internal fun assign(
        rows: FloatArray,
        n: Int,
        dim: Int,
        distanceCut: Double = DISTANCE_CUT,
        isActive: () -> Boolean = { true },
    ): IntArray {
        require(n in 0..MAX_TRACKS && dim > 0 && rows.size == n * dim)
        require(distanceCut.isFinite() && distanceCut in 0.0..2.0)
        require(rows.all(Float::isFinite))
        if (n == 0) return IntArray(0)
        val norms = DoubleArray(n) { row ->
            var sum = 0.0
            for (d in 0 until dim) { val v = rows[row * dim + d].toDouble(); sum += v * v }
            sqrt(sum).also { require(it > 1e-12) { "A zero row has no direction" } }
        }
        val distances = Distances(n)
        for (i in 1 until n) {
            checkActive(isActive)
            val bi = i * dim
            for (j in 0 until i) {
                var dot = 0.0
                val bj = j * dim
                for (d in 0 until dim) dot += rows[bi + d].toDouble() * rows[bj + d]
                distances[i, j] = (1.0 - dot / (norms[i] * norms[j])).coerceIn(0.0, 2.0)
            }
        }
        val sizes = IntArray(n) { 1 }
        val parent = IntArray(n) { it }
        fun root(index: Int): Int {
            var r = index
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        val chain = IntArray(n)
        var length = 0
        repeat(n - 1) {
            checkActive(isActive)
            if (length == 0) chain[length++] = sizes.indexOfFirst { it > 0 }
            var a: Int
            var b: Int
            var separation: Double
            while (true) {
                a = chain[length - 1]
                // Prefer the previous link in an exact tie; otherwise equal-distance cycles
                // can grow the chain indefinitely. Input order breaks all remaining ties.
                b = if (length > 1) chain[length - 2] else -1
                separation = if (b >= 0) distances[a, b] else Double.POSITIVE_INFINITY
                for (j in 0 until n) {
                    if (j == a || sizes[j] == 0) continue
                    val d = distances[a, j]
                    if (d < separation) { separation = d; b = j }
                }
                if (length > 1 && b == chain[length - 2]) break
                check(length < n) { "Nearest-neighbor chain cycle" }
                chain[length++] = b
            }
            length -= 2
            val keep = minOf(a, b)
            val drop = maxOf(a, b)
            val keepSize = sizes[keep]
            val dropSize = sizes[drop]
            if (separation <= distanceCut) {
                val left = root(keep)
                val right = root(drop)
                parent[maxOf(left, right)] = minOf(left, right)
            }
            for (j in 0 until n) {
                if (sizes[j] == 0 || j == keep || j == drop) continue
                distances[keep, j] =
                    (keepSize * distances[keep, j] + dropSize * distances[drop, j]) / (keepSize + dropSize)
            }
            sizes[keep] += dropSize
            sizes[drop] = 0
            // Do not stop at the first merge above the cut: another part of the chain can still
            // contain a tighter group. Average linkage has no inversions within a subtree.
        }
        val compact = LinkedHashMap<Int, Int>()
        return IntArray(n) { i -> compact.getOrPut(root(i)) { compact.size } }
    }

    private fun checkActive(isActive: () -> Boolean) {
        if (!isActive()) throw CancellationException("Region discovery cancelled")
    }

    private class Distances(n: Int) {
        private val offsets = IntArray(n) { it * (it - 1) / 2 }
        private val values = DoubleArray(n * (n - 1) / 2)
        operator fun get(a: Int, b: Int): Double =
            values[if (a > b) offsets[a] + b else offsets[b] + a]
        operator fun set(a: Int, b: Int, value: Double) {
            values[if (a > b) offsets[a] + b else offsets[b] + a] = value
        }
    }
}
