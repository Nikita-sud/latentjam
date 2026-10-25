/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.exp
import kotlin.math.ln

/**
 * Barnes-Hut t-SNE (van der Maaten, JMLR 2014), written from the paper for large libraries.
 *
 * [Tsne] holds every pair in memory and visits every pair in every iteration: about 36 MB and
 * ~50 s on a flagship phone at 3,000 tracks, and 380 MB at 10,000. This variant keeps [Tsne]'s
 * perplexity, exaggeration schedule, learning rate, gains, momentum, recentering and start
 * positions, and changes only the two quadratic parts:
 *
 * - Input affinities use the [NEIGHBORS] nearest rows (three times the perplexity, as in the
 *   paper) instead of all rows. Neighbor search is exact; only the stored matrix is sparse.
 * - Repulsion uses a quadtree rebuilt each iteration. A cell whose side is below [THETA] times its
 *   distance acts as one point at its centre of mass.
 *
 * Memory is linear in n and every iteration costs O(n log n). The result is deterministic for a
 * given input, but it is a different picture from [Tsne]'s; [LibraryLayout] chooses one per size.
 */
internal object BarnesHutTsne {

    /** The paper's default accuracy/speed trade-off. */
    const val THETA: Float = 0.5f

    /** Three times [Tsne.PERPLEXITY], the paper's neighbourhood for sparse affinities. */
    const val NEIGHBORS: Int = 60

    /**
     * Upper bound on this object's own allocations for [n] rows, input excluded: neighbour lists,
     * conditional values, the packed edge list and the final symmetric matrix can coexist
     * (44 bytes per neighbour slot); the quadtree may double once; optimizer state is 40 bytes.
     */
    fun workingBytes(n: Int): Long = 44L * n * NEIGHBORS + 512L * n + 4096L

    /** Stops splitting cells that hold exact duplicates; such a leaf is evaluated point by point. */
    private const val MAX_DEPTH = 32

    /**
     * @param rows row-major `n x dim`, unit-normalized like [Tsne.embed]'s input
     * @param isActive polled before each neighbour row, each bandwidth row and each optimizer
     *   iteration. As with [Tsne.embed], a cancelled call returns a well-formed partial result and
     *   the caller checks [isActive] again before using it.
     * @return row-major `n x 2`
     */
    fun embed(
        rows: FloatArray,
        n: Int,
        dim: Int,
        seed: Int,
        initial: FloatArray? = null,
        isActive: () -> Boolean = { true },
        iterations: Int = Tsne.ITERATIONS,
        exaggerationIterations: Int = Tsne.EXAGGERATION_ITERATIONS,
        scheduleOffset: Int = 0,
    ): FloatArray {
        require(n > 0 && dim > 0) { "Cannot embed an empty matrix" }
        require(rows.size == n * dim) {
            "rows.size was ${rows.size}, expected n * dim = ${n * dim} (n=$n, dim=$dim)"
        }
        if (n < 3) return FloatArray(n * 2)
        return embedWithNeighbours(rows, n, dim, seed, initial, isActive, iterations, exaggerationIterations, scheduleOffset).coordinates
    }

    /** [embed]'s coordinates plus the 60-NN lists it built them from (null when cancelled before them). */
    internal class Embedding(val coordinates: FloatArray, val neighbours: Neighbours?)

    /** [embed], also returning its neighbour lists; the coordinates are [embed]'s, bit for bit. */
    internal fun embedWithNeighbours(
        rows: FloatArray,
        n: Int,
        dim: Int,
        seed: Int,
        initial: FloatArray? = null,
        isActive: () -> Boolean = { true },
        iterations: Int = Tsne.ITERATIONS,
        exaggerationIterations: Int = Tsne.EXAGGERATION_ITERATIONS,
        scheduleOffset: Int = 0,
    ): Embedding {
        if (n < 3) return Embedding(FloatArray(n * 2), null)
        val k = minOf(n - 1, NEIGHBORS)
        val neighbours = nearestNeighbours(rows, n, dim, k, isActive) ?: return Embedding(FloatArray(n * 2), null)
        val p = jointProbabilities(neighbours, n, k, isActive) ?: return Embedding(FloatArray(n * 2), neighbours)
        val y = Tsne.initialPositions(n, seed, initial)
        optimize(p, n, y, isActive, iterations, exaggerationIterations, scheduleOffset)
        return Embedding(y, neighbours)
    }

    /**
     * The gradient descent over sparse affinities [p], in place on [y]. [embed]'s loop, moved here
     * unchanged so a refresh can run it on affinities built from stored neighbour lists.
     */
    internal fun optimize(
        p: SparseAffinities,
        n: Int,
        y: FloatArray,
        isActive: () -> Boolean,
        iterations: Int,
        exaggerationIterations: Int,
        scheduleOffset: Int,
    ) {
        val gains = FloatArray(n * 2) { 1f }
        val velocity = FloatArray(n * 2)
        val grad = FloatArray(n * 2)
        val repulsion = FloatArray(n * 2)
        val tree = QuadTree(n)

        for (iteration in 0 until iterations) {
            if (!isActive()) break
            val scale = if (iteration < exaggerationIterations) Tsne.EXAGGERATION else 1f
            tree.build(y)
            var normalizer = 0.0
            for (i in 0 until n) normalizer += tree.repel(i, y, repulsion)
            val sum = if (normalizer <= 0.0) 1e-12f else normalizer.toFloat()
            for (i in 0 until n) {
                val ix = y[i * 2]
                val iy = y[i * 2 + 1]
                var ax = 0f
                var ay = 0f
                for (edge in p.start[i] until p.start[i + 1]) {
                    val j = p.column[edge]
                    val dx = ix - y[j * 2]
                    val dy = iy - y[j * 2 + 1]
                    val force = p.value[edge] / (1f + dx * dx + dy * dy)
                    ax += force * dx
                    ay += force * dy
                }
                // The exact gradient is 4 * sum_j (scale * p_ij - q_ij) * w_ij * (y_i - y_j),
                // with q_ij = w_ij / Z. Attraction is sparse; repulsion comes from the tree.
                grad[i * 2] = 4f * (scale * ax - repulsion[i * 2] / sum)
                grad[i * 2 + 1] = 4f * (scale * ay - repulsion[i * 2 + 1] / sum)
            }
            Tsne.update(y, n, grad, gains, velocity, iteration + scheduleOffset)
        }
    }

    /** Row-major `n x k`, nearest first; ties are broken by the lower row index. */
    internal class Neighbours(val index: IntArray, val distance: FloatArray)

    /**
     * Exact k nearest neighbours by squared euclidean distance, one max-heap per row.
     * Each pair is measured once and offered to both rows. Returns null when cancelled.
     */
    internal fun nearestNeighbours(
        rows: FloatArray,
        n: Int,
        dim: Int,
        k: Int,
        isActive: () -> Boolean,
    ): Neighbours? {
        val index = IntArray(n * k)
        val distance = FloatArray(n * k)
        val filled = IntArray(n)
        fun offer(row: Int, candidate: Int, value: Float) {
            val base = row * k
            val count = filled[row]
            if (count < k) {
                var slot = count
                filled[row] = count + 1
                while (slot > 0) {
                    val parent = (slot - 1) / 2
                    if (!after(value, candidate, distance[base + parent], index[base + parent])) break
                    distance[base + slot] = distance[base + parent]
                    index[base + slot] = index[base + parent]
                    slot = parent
                }
                distance[base + slot] = value
                index[base + slot] = candidate
                return
            }
            if (!after(distance[base], index[base], value, candidate)) return
            siftDown(index, distance, base, k, value, candidate)
        }
        for (i in 0 until n) {
            if (!isActive()) return null
            val a = i * dim
            for (j in i + 1 until n) {
                val b = j * dim
                var sum = 0f
                for (d in 0 until dim) {
                    val delta = rows[a + d] - rows[b + d]
                    sum += delta * delta
                }
                offer(i, j, sum)
                offer(j, i, sum)
            }
        }
        // Pop each max-heap into ascending order, so a row never depends on how it was filled.
        for (i in 0 until n) {
            val base = i * k
            for (size in k - 1 downTo 1) {
                val value = distance[base + size]
                val candidate = index[base + size]
                distance[base + size] = distance[base]
                index[base + size] = index[base]
                siftDown(index, distance, base, size, value, candidate)
            }
        }
        return Neighbours(index, distance)
    }

    /** Places (value, candidate) at the root of a max-heap of [size] entries and restores order. */
    private fun siftDown(index: IntArray, distance: FloatArray, base: Int, size: Int, value: Float, candidate: Int) {
        var slot = 0
        while (true) {
            val left = 2 * slot + 1
            if (left >= size) break
            val right = left + 1
            var larger = left
            if (right < size && after(distance[base + right], index[base + right], distance[base + left], index[base + left])) {
                larger = right
            }
            if (!after(distance[base + larger], index[base + larger], value, candidate)) break
            distance[base + slot] = distance[base + larger]
            index[base + slot] = index[base + larger]
            slot = larger
        }
        distance[base + slot] = value
        index[base + slot] = candidate
    }

    /** True when (value, candidate) sorts after (other, otherIndex): larger distance, then index. */
    private fun after(value: Float, candidate: Int, other: Float, otherIndex: Int): Boolean =
        value > other || (value == other && candidate > otherIndex)

    /** Symmetric sparse P in CSR form; every pair appears in both of its rows. */
    internal class SparseAffinities(val start: IntArray, val column: IntArray, val value: FloatArray)

    /**
     * Per-row bandwidths over the k neighbours, using [Tsne.affinities]' search, then
     * `p_ij = (p_j|i + p_i|j) / 2n`. Returns null when cancelled.
     */
    internal fun jointProbabilities(
        neighbours: Neighbours,
        n: Int,
        k: Int,
        isActive: () -> Boolean,
    ): SparseAffinities? {
        // As in Tsne.affinities, keep the target well inside what k neighbours can express.
        val effectivePerplexity = minOf(Tsne.PERPLEXITY, maxOf(2f, k / 3f))
        val target = ln(effectivePerplexity)
        val conditional = FloatArray(n * k)
        val row = FloatArray(k)
        for (i in 0 until n) {
            if (!isActive()) return null
            val base = i * k
            var low = 0f
            var high = Float.MAX_VALUE
            var beta = 1f
            var rowSum = 1f
            for (step in 0 until Tsne.PERPLEXITY_STEPS) {
                var sum = 0f
                var entropySum = 0f
                for (s in 0 until k) {
                    val distance = neighbours.distance[base + s]
                    val value = exp(-distance * beta)
                    row[s] = value
                    sum += value
                    entropySum += distance * value
                }
                if (sum <= 0f) sum = 1e-12f
                rowSum = sum
                val error = ln(sum) + beta * entropySum / sum - target
                if (error > 0f) {
                    low = beta
                    beta = if (high == Float.MAX_VALUE) beta * 2f else (beta + high) / 2f
                } else {
                    high = beta
                    beta = (beta + low) / 2f
                }
                if (error < Tsne.PERPLEXITY_TOLERANCE && error > -Tsne.PERPLEXITY_TOLERANCE) break
            }
            for (s in 0 until k) conditional[base + s] = row[s] / rowSum
        }

        // Both directions of every neighbour edge, bucketed by row; then merge duplicates.
        val counts = IntArray(n + 1)
        for (i in 0 until n) {
            for (s in 0 until k) {
                counts[i + 1]++
                counts[neighbours.index[i * k + s] + 1]++
            }
        }
        for (i in 0 until n) counts[i + 1] += counts[i]
        val cursor = counts.copyOf()
        // Column in the high half, Float bits in the low half: sorting groups each column and
        // fixes the order in which a pair's two conditional values are added.
        val packed = LongArray(counts[n])
        for (i in 0 until n) {
            for (s in 0 until k) {
                val j = neighbours.index[i * k + s]
                val bits = conditional[i * k + s].toRawBits().toLong() and 0xFFFFFFFFL
                packed[cursor[i]++] = (j.toLong() shl 32) or bits
                packed[cursor[j]++] = (i.toLong() shl 32) or bits
            }
        }
        val start = IntArray(n + 1)
        var total = 0
        for (i in 0 until n) {
            packed.sort(counts[i], counts[i + 1])
            var previous = -1
            for (e in counts[i] until counts[i + 1]) {
                val column = (packed[e] ushr 32).toInt()
                if (column != previous) total++
                previous = column
            }
            start[i + 1] = total
        }
        val column = IntArray(total)
        val value = FloatArray(total)
        var out = -1
        for (i in 0 until n) {
            var previous = -1
            for (e in counts[i] until counts[i + 1]) {
                val j = (packed[e] ushr 32).toInt()
                val conditionalValue = Float.fromBits((packed[e] and 0xFFFFFFFFL).toInt())
                if (j != previous) {
                    out++
                    column[out] = j
                    value[out] = conditionalValue
                } else {
                    value[out] += conditionalValue
                }
                previous = j
            }
        }
        val denominator = 2f * n
        for (e in 0 until total) value[e] = (value[e] / denominator).coerceAtLeast(1e-12f)
        return SparseAffinities(start, column, value)
    }

    /**
     * Region quadtree over the current 2D positions, stored in flat arrays and rebuilt in place.
     * Each node owns a contiguous range of [order]; children of a node are four consecutive nodes.
     */
    internal class QuadTree(private val n: Int) {
        private val order = IntArray(n)
        private val scratch = IntArray(n)
        private var capacity = 4 * n + 4
        private var minX = FloatArray(capacity)
        private var minY = FloatArray(capacity)
        private var side = FloatArray(capacity)
        private var massX = FloatArray(capacity)
        private var massY = FloatArray(capacity)
        private var first = IntArray(capacity)
        private var last = IntArray(capacity)
        private var child = IntArray(capacity)
        private var depth = IntArray(capacity)
        private var nodes = 0
        private val stack = IntArray(4 * MAX_DEPTH + 8)
        private val pending = IntArray(4 * MAX_DEPTH + 8)
        // Per-split scratch, reused so a rebuild every iteration allocates nothing.
        private val quadrantCounts = IntArray(4)
        private val offsets = IntArray(4)
        private val bounds = IntArray(4)

        fun build(y: FloatArray) {
            var lowX = Float.MAX_VALUE
            var lowY = Float.MAX_VALUE
            var highX = -Float.MAX_VALUE
            var highY = -Float.MAX_VALUE
            for (i in 0 until n) {
                order[i] = i
                lowX = minOf(lowX, y[i * 2])
                highX = maxOf(highX, y[i * 2])
                lowY = minOf(lowY, y[i * 2 + 1])
                highY = maxOf(highY, y[i * 2 + 1])
            }
            val span = maxOf(highX - lowX, highY - lowY)
            nodes = 0
            val root = add(lowX, lowY, span * 1.0001f + 1e-6f, 0, n, 0)
            var top = 0
            pending[top++] = root
            while (top > 0) {
                val node = pending[--top]
                summarize(node, y)
                if (last[node] - first[node] < 2 || depth[node] >= MAX_DEPTH) continue
                val half = side[node] / 2f
                val midX = minX[node] + half
                val midY = minY[node] + half
                val begin = first[node]
                val end = last[node]
                quadrantCounts.fill(0)
                for (m in begin until end) quadrantCounts[quadrant(order[m], y, midX, midY)]++
                offsets[0] = begin
                for (q in 1 until 4) offsets[q] = offsets[q - 1] + quadrantCounts[q - 1]
                offsets.copyInto(bounds)
                for (m in begin until end) {
                    val point = order[m]
                    scratch[offsets[quadrant(point, y, midX, midY)]++] = point
                }
                scratch.copyInto(order, begin, begin, end)
                ensureCapacity(nodes + 4)
                val children = nodes
                for (q in 0 until 4) {
                    add(
                        minX[node] + if (q and 1 == 1) half else 0f,
                        minY[node] + if (q and 2 == 2) half else 0f,
                        half,
                        bounds[q],
                        bounds[q] + quadrantCounts[q],
                        depth[node] + 1,
                    )
                }
                child[node] = children
                // Reverse order keeps the depth-first stack bounded by 3 per level.
                for (q in 3 downTo 0) if (quadrantCounts[q] > 0) pending[top++] = children + q
            }
        }

        /**
         * Adds point [i]'s unnormalized repulsion, `sum_j w_ij^2 (y_i - y_j)`, into [out] and
         * returns its share of the normalizer, `sum_j w_ij`, with `w = 1 / (1 + d^2)`.
         */
        fun repel(i: Int, y: FloatArray, out: FloatArray): Double =
            repelAt(y[i * 2], y[i * 2 + 1], i, y, out, i)

        /**
         * [repel] for a point at ([ix], [iy]) that may not be in the tree: [skip] is the tree row to
         * leave out (-1 for none), and the result goes to `out[slot * 2]`, `out[slot * 2 + 1]`.
         */
        fun repelAt(ix: Float, iy: Float, skip: Int, y: FloatArray, out: FloatArray, slot: Int): Double {
            var rx = 0f
            var ry = 0f
            var z = 0f
            var top = 0
            stack[top++] = 0
            while (top > 0) {
                val node = stack[--top]
                val count = last[node] - first[node]
                if (count == 0) continue
                if (child[node] < 0) {
                    for (m in first[node] until last[node]) {
                        val j = order[m]
                        if (j == skip) continue
                        val dx = ix - y[j * 2]
                        val dy = iy - y[j * 2 + 1]
                        val w = 1f / (1f + dx * dx + dy * dy)
                        z += w
                        rx += w * w * dx
                        ry += w * w * dy
                    }
                    continue
                }
                val dx = ix - massX[node]
                val dy = iy - massY[node]
                val squared = dx * dx + dy * dy
                // A cell containing i can never pass: its centre of mass is within side * sqrt(2).
                if (side[node] * side[node] < THETA * THETA * squared) {
                    val w = 1f / (1f + squared)
                    val weight = count * w
                    z += weight
                    rx += weight * w * dx
                    ry += weight * w * dy
                } else {
                    val children = child[node]
                    for (q in 3 downTo 0) stack[top++] = children + q
                }
            }
            out[slot * 2] = rx
            out[slot * 2 + 1] = ry
            return z.toDouble()
        }

        private fun quadrant(point: Int, y: FloatArray, midX: Float, midY: Float): Int =
            (if (y[point * 2] >= midX) 1 else 0) or (if (y[point * 2 + 1] >= midY) 2 else 0)

        private fun summarize(node: Int, y: FloatArray) {
            var sx = 0.0
            var sy = 0.0
            for (m in first[node] until last[node]) {
                sx += y[order[m] * 2]
                sy += y[order[m] * 2 + 1]
            }
            val count = last[node] - first[node]
            if (count > 0) {
                massX[node] = (sx / count).toFloat()
                massY[node] = (sy / count).toFloat()
            }
        }

        private fun add(x: Float, y: Float, size: Float, begin: Int, end: Int, level: Int): Int {
            ensureCapacity(nodes + 1)
            val node = nodes++
            minX[node] = x
            minY[node] = y
            side[node] = size
            first[node] = begin
            last[node] = end
            child[node] = -1
            depth[node] = level
            return node
        }

        private fun ensureCapacity(required: Int) {
            if (required <= capacity) return
            capacity = maxOf(required, capacity * 2)
            minX = minX.copyOf(capacity)
            minY = minY.copyOf(capacity)
            side = side.copyOf(capacity)
            massX = massX.copyOf(capacity)
            massY = massY.copyOf(capacity)
            first = first.copyOf(capacity)
            last = last.copyOf(capacity)
            child = child.copyOf(capacity)
            depth = depth.copyOf(capacity)
        }
    }
}
