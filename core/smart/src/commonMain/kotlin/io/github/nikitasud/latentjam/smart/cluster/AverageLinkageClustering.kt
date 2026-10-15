/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Average-linkage regions on centered unit directions, independent of the 2D map. */
internal object AverageLinkageClustering {
    const val METADATA_WEIGHT: Float = 0.25f
    const val DISTANCE_CUT: Double = 0.75
    private const val MIN_DIRECTION_NORM: Double = 1e-12

    // The exact hierarchy's triangular DoubleArray uses at most 36 MB. Never allocate an
    // unbounded quadratic matrix or silently truncate the population.
    const val MAX_TRACKS: Int = 3_000

    /**
     * Largest library [cluster] accepts. Above [MAX_TRACKS] the hierarchy runs over micro-clusters
     * seeded by a stable sample of [MAX_TRACKS] tracks (see [clusterSampled]).
     */
    const val MAX_LIBRARY_TRACKS: Int = LibraryLayout.MAX_TRACKS

    /** Random-projection width and shortlist for the sampled path's nearest-seed search. */
    internal const val PROJECTION_DIM: Int = 128
    internal const val PROJECTION_CANDIDATES: Int = 32
    private const val PROJECTION_SEED: Long = 0x5EED20261015L

    /**
     * @param exactLimit usable population that still gets the exact hierarchy. Production uses
     *   [MAX_TRACKS]; replays lower it to compare the sampled path with the exact one.
     */
    fun cluster(
        space: LibraryVectorSpace,
        minSize: Int = TrackClustering.MIN_CLUSTER_SIZE,
        isActive: () -> Boolean = { true },
        exactLimit: Int = MAX_TRACKS,
    ): List<TrackCluster> {
        require(minSize > 0) { "Minimum cluster size must be positive" }
        require(exactLimit in 1..MAX_TRACKS) { "Exact hierarchy limit must be within the memory limit" }
        require(space.size <= MAX_LIBRARY_TRACKS) { "Average linkage population exceeds memory limit" }
        if (space.size < minSize) return emptyList()
        checkActive(isActive)
        val rows = space.takeRowsForRegions(METADATA_WEIGHT)
        require(rows.all(Float::isFinite)) { "Library vector space contains a non-finite row" }
        TrackClustering.center(rows, space.size, space.dim, isActive)
        // A direction at or too close to the corpus mean provides no clustering evidence.
        // Use the same norm threshold as assign: centering leaves tiny residuals unnormalized.
        val usable = (0 until space.size).filter { row ->
            checkActive(isActive)
            directionNorm(rows, row, space.dim) > MIN_DIRECTION_NORM
        }
        if (usable.size < minSize) return emptyList()
        if (usable.size > exactLimit) {
            return clusterSampled(rows, space.size, space.dim, usable, space.trackIds, exactLimit, minSize, isActive)
        }
        val matrix = if (usable.size == space.size) rows else FloatArray(usable.size * space.dim).also { out ->
            usable.forEachIndexed { into, from ->
                checkActive(isActive)
                rows.copyInto(out, into * space.dim, from * space.dim, (from + 1) * space.dim)
            }
        }
        val ids = if (usable.size == space.size) space.trackIds else usable.map(space.trackIds::get)
        val labels = assign(matrix, ids.size, space.dim, DISTANCE_CUT, isActive)
        checkActive(isActive)
        // The hierarchy's distance cut determines admission. K-means rival-centroid margins
        // would impose a second, different clustering criterion and discard valid subregions.
        return TrackClustering.collectRegions(matrix, ids.size, space.dim, labels, ids, minSize, isActive)
    }

    /**
     * Average linkage for a library above the exact limit, without its quadratic matrix.
     *
     * [sampleSize] tracks, chosen by a hash of their id so the choice is stable as a library
     * grows, seed micro-clusters. Every other track joins its nearest seed, unless even that one
     * is farther than [DISTANCE_CUT] -- exact linkage could then merge it only through an
     * unsampled neighbour, so it stays alone and outside the named regions. The hierarchy then runs
     * over the weighted micro-clusters. For centered unit directions the average pairwise cosine
     * distance between two groups is exactly `1 - S_a . S_b / (|a| |b|)`, where `S` sums a group's
     * directions, so the only approximation is that a micro-cluster is never split.
     *
     * Nearest seeds are found among [PROJECTION_CANDIDATES] candidates ranked in a fixed
     * [PROJECTION_DIM]-dimensional random projection, then compared exactly. Rows are reordered in
     * place (sample first) rather than copied.
     *
     * Replays against the exact hierarchy: docs/map-scaling-10k-2026-10-15.md.
     */
    private fun clusterSampled(
        rows: FloatArray,
        size: Int,
        dim: Int,
        usable: List<Int>,
        trackIds: List<TrackId>,
        sampleSize: Int,
        minSize: Int,
        isActive: () -> Boolean,
    ): List<TrackCluster> {
        val n = usable.size
        val keys = LongArray(n) { stableKey(trackIds[usable[it]].value) }
        val sampled = BooleanArray(n)
        (0 until n).sortedWith(compareBy<Int> { keys[it] }.thenBy { it }).take(sampleSize).forEach { sampled[it] = true }
        val unusable = BooleanArray(size) { true }.also { flags -> usable.forEach { flags[it] = false } }
        // order[new] = old: sample, then the other usable rows, both in input order; unusable last.
        val order = IntArray(size)
        var next = 0
        for (position in 0 until n) if (sampled[position]) order[next++] = usable[position]
        for (position in 0 until n) if (!sampled[position]) order[next++] = usable[position]
        for (row in 0 until size) if (unusable[row]) order[next++] = row
        permuteRows(rows, dim, order, isActive)
        val ids = List(n) { trackIds[order[it]] }
        val m = sampleSize

        val projected = project(rows, n, dim, isActive)
        val micro = IntArray(n) { if (it < m) it else -1 }
        val candidates = IntArray(PROJECTION_CANDIDATES)
        val candidateScores = FloatArray(PROJECTION_CANDIDATES)
        for (row in m until n) {
            checkActive(isActive)
            var kept = 0
            val p = row * PROJECTION_DIM
            for (seed in 0 until m) {
                val q = seed * PROJECTION_DIM
                var score = 0f
                for (d in 0 until PROJECTION_DIM) score += projected[p + d] * projected[q + d]
                if (kept == PROJECTION_CANDIDATES && score <= candidateScores[kept - 1]) continue
                // Insertion into a short descending list; equal scores keep the earlier seed first.
                var slot = if (kept < PROJECTION_CANDIDATES) kept++ else kept - 1
                while (slot > 0 && candidateScores[slot - 1] < score) {
                    candidateScores[slot] = candidateScores[slot - 1]
                    candidates[slot] = candidates[slot - 1]
                    slot--
                }
                candidateScores[slot] = score
                candidates[slot] = seed
            }
            var best = -1
            var bestDot = Double.NEGATIVE_INFINITY
            for (c in 0 until kept) {
                val seed = candidates[c]
                var dot = 0f
                for (d in 0 until dim) dot += rows[row * dim + d] * rows[seed * dim + d]
                if (dot > bestDot || (dot.toDouble() == bestDot && seed < best)) {
                    bestDot = dot.toDouble()
                    best = seed
                }
            }
            micro[row] = if (best < 0 || 1.0 - bestDot > DISTANCE_CUT) -1 else best
        }

        val sums = FloatArray(m * dim)
        val sizes = IntArray(m)
        for (row in 0 until n) {
            val group = micro[row]
            if (group < 0) continue
            sizes[group]++
            val target = group * dim
            val source = row * dim
            for (d in 0 until dim) sums[target + d] += rows[source + d]
        }
        val distances = Distances(m)
        for (i in 1 until m) {
            checkActive(isActive)
            val bi = i * dim
            for (j in 0 until i) {
                var dot = 0.0
                val bj = j * dim
                for (d in 0 until dim) dot += sums[bi + d].toDouble() * sums[bj + d]
                distances[i, j] = (1.0 - dot / (sizes[i].toDouble() * sizes[j])).coerceIn(0.0, 2.0)
            }
        }
        val microLabels = link(distances, sizes, m, DISTANCE_CUT, isActive)
        var alone = m
        val labels = IntArray(n) { if (micro[it] < 0) alone++ else microLabels[micro[it]] }
        checkActive(isActive)
        return TrackClustering.collectRegions(rows, n, dim, labels, ids, minSize, isActive)
    }

    /** Fixed Gaussian projection of each row to [PROJECTION_DIM] values; seeded, so deterministic. */
    private fun project(rows: FloatArray, n: Int, dim: Int, isActive: () -> Boolean): FloatArray {
        var state = PROJECTION_SEED
        fun uniform(): Double {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return ((state ushr 11).toDouble() + 0.5) / (1L shl 53).toDouble()
        }
        // Box-Muller in pairs; one row of the matrix per output dimension.
        val matrix = FloatArray(dim * PROJECTION_DIM)
        var index = 0
        while (index < matrix.size) {
            val radius = sqrt(-2.0 * ln(uniform()))
            val angle = 2.0 * PI * uniform()
            matrix[index++] = (radius * cos(angle)).toFloat()
            if (index < matrix.size) matrix[index++] = (radius * sin(angle)).toFloat()
        }
        val out = FloatArray(n * PROJECTION_DIM)
        for (row in 0 until n) {
            checkActive(isActive)
            val source = row * dim
            for (p in 0 until PROJECTION_DIM) {
                val column = p * dim
                var sum = 0f
                for (d in 0 until dim) sum += rows[source + d] * matrix[column + d]
                out[row * PROJECTION_DIM + p] = sum
            }
        }
        return out
    }

    /** Moves row `order[i]` to row `i` for a full permutation, with one row of scratch. */
    private fun permuteRows(rows: FloatArray, dim: Int, order: IntArray, isActive: () -> Boolean) {
        val placed = BooleanArray(order.size)
        val held = FloatArray(dim)
        for (start in order.indices) {
            if (placed[start] || order[start] == start) continue
            checkActive(isActive)
            rows.copyInto(held, 0, start * dim, (start + 1) * dim)
            var target = start
            while (true) {
                placed[target] = true
                val source = order[target]
                if (source == start) {
                    held.copyInto(rows, target * dim)
                    break
                }
                rows.copyInto(rows, target * dim, source * dim, (source + 1) * dim)
                target = source
            }
        }
    }

    /** FNV-1a over the id's UTF-16 units, then a 64-bit finalizer; identical on every platform. */
    internal fun stableKey(value: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (char in value) {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        hash = (hash xor (hash ushr 33)) * -0xae502812aa7333L
        hash = (hash xor (hash ushr 33)) * -0x3b314601e57a13adL
        return hash xor (hash ushr 33)
    }

    /** Nearest-neighbor chain UPGMA: quadratic linkage work after pairwise cosine distances. */
    internal fun assign(
        rows: FloatArray,
        n: Int,
        dim: Int,
        distanceCut: Double = DISTANCE_CUT,
        isActive: () -> Boolean = { true },
    ): IntArray {
        // Rows beyond n may remain: the sampled path passes its reordered library matrix.
        require(n in 0..MAX_TRACKS && dim > 0 && rows.size >= n * dim)
        require(distanceCut.isFinite() && distanceCut in 0.0..2.0)
        for (index in 0 until n * dim) require(rows[index].isFinite())
        if (n == 0) return IntArray(0)
        val norms = DoubleArray(n) { row ->
            directionNorm(rows, row, dim).also {
                require(it > MIN_DIRECTION_NORM) { "A zero row has no direction" }
            }
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
        return link(distances, IntArray(n) { 1 }, n, distanceCut, isActive)
    }

    /**
     * Nearest-neighbor chain over [n] initial clusters of [sizes] members whose average pairwise
     * distances are [distances]. Consumes both arrays.
     */
    private fun link(
        distances: Distances,
        sizes: IntArray,
        n: Int,
        distanceCut: Double,
        isActive: () -> Boolean,
    ): IntArray {
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

    private fun directionNorm(rows: FloatArray, row: Int, dim: Int): Double {
        var sum = 0.0
        for (d in 0 until dim) {
            val value = rows[row * dim + d].toDouble()
            sum += value * value
        }
        return sqrt(sum)
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
