/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One discovered region of the library.
 *
 * [members] are ordered center-outward, so index 0 is the **medoid** — the member closest to the
 * cluster's centre, and therefore the one thing that best stands for the whole. Callers must take
 * their representative from position 0 rather than recomputing it: a cover picked one way and a
 * seed picked another will eventually disagree, and a card whose art and behaviour disagree reads
 * as a bug rather than as a choice.
 */
public data class TrackCluster(
    public val members: List<TrackId>,
    /**
     * Final-centroid evidence for [members], in exactly the same order.
     *
     * Empty is retained as the compatibility value for callers that construct a cluster directly.
     * Clusters returned by [TrackClustering] always carry one entry per member.
     */
    public val memberships: List<TrackClusterMembership> = emptyList(),
) {
    init {
        require(members.isNotEmpty()) { "A cluster with no members is not a cluster" }
        require(memberships.isEmpty() || memberships.map { it.trackId } == members) {
            "Cluster memberships must be empty or align exactly with members"
        }
    }

    /** The most central member — the cluster's representative. */
    public val medoid: TrackId get() = members.first()

    public val size: Int get() = members.size
}

/**
 * How confidently one track belongs to its assigned cluster.
 *
 * [centroidSimilarity] is cosine similarity to the centroid recomputed from the final membership.
 * [assignmentMargin] subtracts the closest other non-empty centroid's cosine from that value. A
 * small margin identifies a boundary track that k-means had to assign somewhere but that does not
 * confidently belong in either resulting mix.
 */
public data class TrackClusterMembership(
    public val trackId: TrackId,
    public val centroidSimilarity: Float,
    public val assignmentMargin: Float,
)

/**
 * Spherical k-means over track embeddings.
 *
 * Pure functions over a flat, row-major [FloatArray] and its dimension: no coroutines, no Android,
 * no clock, no I/O. That is not modesty about the algorithm — it is what makes the rules below
 * testable, and rules that decide what a listener is shown do not stay correct otherwise.
 *
 * Two properties are load-bearing and easy to lose:
 *
 * 1. **Centering.** Raw embedding cosines are dominated by a shared mean direction, so before
 *    centering everything looks similar to everything and k-means finds arbitrary slices of one
 *    blob. Subtracting the corpus mean and renormalising is what turns cosine into a signal.
 * 2. **Determinism.** Seeding is randomised, so the seed is fixed. A section that regroups the
 *    library on every visit is indistinguishable from a broken one, and repeated exposure is what
 *    turns a suggestion into a play.
 */
public object TrackClustering {

    /**
     * How many regions to look for.
     *
     * Eight is not tuned — it is bounded by the row it feeds: attention falls off sharply past the
     * first few cards, so finding more than can be shown only produces losers.
     */
    public const val DEFAULT_K: Int = 8

    /** Lloyd passes. Assignment converges well inside this on libraries of any realistic size. */
    public const val DEFAULT_ITERATIONS: Int = 20

    /**
     * Below this a cluster is noise, not a region — three tracks that happen to sit together say
     * nothing about a library, and offering them as a "world" overclaims.
     */
    public const val MIN_CLUSTER_SIZE: Int = 4

    /**
     * Fixed k-means++ seed. Any value works; that it never changes is the point.
     *
     * Changing it regroups every existing library, so treat it as data rather than as a constant.
     */
    public const val SEED: Int = 0x1A7E27

    /**
     * Rows shorter than this are dropped rather than clustered.
     *
     * An all-but-zero embedding sits at the origin of the centered space and is therefore equally
     * close to everything, which lets it attach to whichever cluster is scanned first and drags
     * that centroid towards nothing in particular.
     */
    public const val DEGENERATE_NORM_EPS: Float = 1e-3f

    /**
     * Groups [ids] by their embeddings.
     *
     * Ids with no usable vector are **excluded**, not defaulted to zeros: a zero row would look
     * equally similar to every cluster and would quietly gather all the untagged tracks into one
     * meaningless region at the origin. On a library whose index is still filling, "fewer clusters"
     * is the honest answer and "one cluster of everything unindexed" is not.
     *
     * @param ids the tracks to consider, in a stable caller-defined order — ties break towards the
     *   front of this list, so the same library always yields the same grouping
     * @param vectors embeddings by id; entries of the wrong [dim] are ignored
     * @return clusters of at least [minSize] members, largest first, each ordered medoid-first
     */
    public fun cluster(
        ids: List<TrackId>,
        vectors: Map<TrackId, FloatArray>,
        dim: Int,
        k: Int = DEFAULT_K,
        minSize: Int = MIN_CLUSTER_SIZE,
        iterations: Int = DEFAULT_ITERATIONS,
    ): List<TrackCluster> {
        require(dim > 0) { "Embedding dimension must be positive, got $dim" }
        require(iterations > 0) { "Iteration count must be positive, got $iterations" }
        if (k <= 0 || ids.isEmpty()) return emptyList()

        val usable = ArrayList<TrackId>(ids.size)
        val seen = HashSet<TrackId>(ids.size)
        for (id in ids) {
            val vector = vectors[id] ?: continue
            if (vector.size != dim) continue
            if (!vector.all(Float::isFinite)) continue
            val length = norm(vector)
            if (!length.isFinite() || length < DEGENERATE_NORM_EPS) continue
            // A caller may hand us the same track twice (two rows of one file); clustering it twice
            // would let it vote twice for its own centroid.
            if (seen.add(id)) usable.add(id)
        }
        val n = usable.size
        if (n < minSize) return emptyList()

        val rows = FloatArray(n * dim)
        for (i in 0 until n) {
            val vector = vectors.getValue(usable[i])
            val scale = 1f / norm(vector).coerceAtLeast(1e-12f)
            for (d in 0 until dim) rows[i * dim + d] = vector[d] * scale
        }
        center(rows, n, dim)

        val assignment = assign(rows, n, dim, k = k.coerceAtMost(n), iterations = iterations)
        return collect(rows, n, dim, assignment, usable, minSize)
    }

    /**
     * Clusters a one-shot matrix produced by [LibraryVectorFusion] without copying it.
     *
     * The matrix is private and already normalized, dimension-checked, and de-duplicated. Consuming
     * it here saves one complete 1,344-float row per track at the exact moment model snapshots and
     * k-means would otherwise put the most pressure on a low-memory phone.
     */
    internal fun cluster(
        space: LibraryVectorSpace,
        k: Int = DEFAULT_K,
        minSize: Int = MIN_CLUSTER_SIZE,
        iterations: Int = DEFAULT_ITERATIONS,
    ): List<TrackCluster> {
        require(iterations > 0) { "Iteration count must be positive, got $iterations" }
        if (k <= 0 || space.size < minSize) return emptyList()
        val rows = space.takeRows()
        require(rows.all(Float::isFinite)) { "Library vector space contains a non-finite row" }
        val n = space.size
        val dim = space.dim
        center(rows, n, dim)
        val assignment = assign(rows, n, dim, k = k.coerceAtMost(n), iterations = iterations)
        return collect(rows, n, dim, assignment, space.trackIds, minSize)
    }

    /**
     * Subtracts the corpus mean and renormalises, in place.
     *
     * The one step that cannot be skipped: see the class note. Rows that land exactly on the mean
     * stay at the origin rather than being scaled to infinity — with real embeddings that does not
     * happen, and the guard costs nothing.
     */
    internal fun center(rows: FloatArray, n: Int, dim: Int) {
        if (n == 0) return
        val mean = FloatArray(dim)
        for (i in 0 until n) {
            val base = i * dim
            for (d in 0 until dim) mean[d] += rows[base + d]
        }
        for (d in 0 until dim) mean[d] /= n
        for (i in 0 until n) {
            val base = i * dim
            var sumSq = 0.0
            for (d in 0 until dim) {
                val v = rows[base + d] - mean[d]
                rows[base + d] = v
                sumSq += v.toDouble() * v
            }
            val length = sqrt(sumSq).toFloat()
            if (length < 1e-12f) continue
            for (d in 0 until dim) rows[base + d] /= length
        }
    }

    /**
     * Lloyd's algorithm on the unit sphere: assign each row to the centroid it points most towards,
     * then move each centroid to the mean of its rows and renormalise.
     *
     * Rows are unit-length, so maximising the dot product is minimising Euclidean distance — the
     * two formulations agree and the dot is cheaper.
     *
     * @return the centroid index each row was assigned to
     */
    private fun assign(rows: FloatArray, n: Int, dim: Int, k: Int, iterations: Int): IntArray {
        val centroids = seedCentroids(rows, n, dim, k)
        val assignment = IntArray(n) { -1 }
        val sums = FloatArray(k * dim)
        val counts = IntArray(k)

        repeat(iterations) {
            var moved = false
            for (i in 0 until n) {
                var best = 0
                var bestDot = Float.NEGATIVE_INFINITY
                for (c in 0 until k) {
                    val dot = dot(rows, i, centroids, c, dim)
                    // Strictly greater, so an exact tie goes to the lower centroid index and the
                    // outcome does not depend on iteration order.
                    if (dot > bestDot) {
                        bestDot = dot
                        best = c
                    }
                }
                if (assignment[i] != best) {
                    assignment[i] = best
                    moved = true
                }
            }
            // Nothing changed hands, so the centroids cannot move either.
            if (!moved) return assignment

            sums.fill(0f)
            counts.fill(0)
            for (i in 0 until n) {
                val c = assignment[i]
                counts[c]++
                val from = i * dim
                val into = c * dim
                for (d in 0 until dim) sums[into + d] += rows[from + d]
            }
            for (c in 0 until k) {
                // An emptied centroid keeps its old position rather than collapsing to the origin,
                // where it would sit at cosine zero and so out-attract every real centroid that a
                // row points away from.
                if (counts[c] == 0) continue
                val base = c * dim
                var sumSq = 0.0
                for (d in 0 until dim) {
                    val v = sums[base + d] / counts[c]
                    centroids[base + d] = v
                    sumSq += v.toDouble() * v
                }
                val length = sqrt(sumSq).toFloat()
                if (length < 1e-12f) continue
                for (d in 0 until dim) centroids[base + d] /= length
            }
        }
        return assignment
    }

    /**
     * k-means++ seeding: the first centre uniformly at random, each next one drawn with probability
     * proportional to its squared distance from the nearest centre already chosen.
     *
     * Plain random seeding routinely drops two centres inside one dense genre and leaves a whole
     * region unrepresented — which on this surface is not a slightly worse score but a missing row.
     */
    private fun seedCentroids(rows: FloatArray, n: Int, dim: Int, k: Int): FloatArray {
        val random = Random(SEED)
        val centroids = FloatArray(k * dim)
        val first = random.nextInt(n)
        copyRow(rows, first, centroids, 0, dim)

        val nearest = FloatArray(n) { squaredDistance(rows, it, centroids, 0, dim) }
        for (c in 1 until k) {
            var total = 0.0
            for (d in nearest) total += d.toDouble()
            val pick = if (total <= 0.0) {
                // Every remaining row already coincides with a chosen centre; step along
                // deterministically rather than drawing from an empty distribution.
                (first + c) % n
            } else {
                var target = random.nextDouble() * total
                var chosen = n - 1
                for (i in 0 until n) {
                    target -= nearest[i]
                    if (target <= 0.0) {
                        chosen = i
                        break
                    }
                }
                chosen
            }
            copyRow(rows, pick, centroids, c, dim)
            for (i in 0 until n) {
                val distance = squaredDistance(rows, i, centroids, c, dim)
                if (distance < nearest[i]) nearest[i] = distance
            }
        }
        return centroids
    }

    /**
     * Turns an assignment into clusters: drops the ones too small to mean anything, orders each
     * one's members center-outward, and orders the clusters themselves largest first.
     *
     * The centroid is recomputed from the final membership rather than reused from the last Lloyd
     * pass, which lags one assignment behind — so "closest to the centre" is measured against the
     * centre of the tracks actually in the cluster.
     */
    private fun collect(
        rows: FloatArray,
        n: Int,
        dim: Int,
        assignment: IntArray,
        ids: List<TrackId>,
        minSize: Int,
    ): List<TrackCluster> {
        val buckets = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until n) buckets.getOrPut(assignment[i]) { mutableListOf() }.add(i)

        // Recompute every non-empty final centroid before scoring any member. Rival margins must be
        // measured against the same final assignment as own-cluster similarity; reusing the last
        // Lloyd centroids would leave the two measurements one assignment out of step.
        val centroidCount = (assignment.maxOrNull() ?: -1) + 1
        val centroids = FloatArray(centroidCount * dim)
        val counts = IntArray(centroidCount)
        for (row in 0 until n) {
            val cluster = assignment[row]
            counts[cluster]++
            val source = row * dim
            val target = cluster * dim
            for (d in 0 until dim) centroids[target + d] += rows[source + d]
        }
        for (cluster in 0 until centroidCount) {
            if (counts[cluster] == 0) continue
            val base = cluster * dim
            var sumSq = 0.0
            for (d in 0 until dim) {
                val value = centroids[base + d] / counts[cluster]
                centroids[base + d] = value
                sumSq += value.toDouble() * value
            }
            val length = sqrt(sumSq).toFloat()
            if (length < 1e-12f) continue
            for (d in 0 until dim) centroids[base + d] /= length
        }

        return buckets.values
            .filter { it.size >= minSize }
            .map { rowsInCluster ->
                val cluster = assignment[rowsInCluster.first()]
                // Descending dot is ascending distance: the medoid lands at index 0. The sort is
                // stable, so rows equidistant from the centre keep the caller's original order.
                rowsInCluster
                    .map { row ->
                        val ownSimilarity = dot(rows, row, centroids, cluster, dim)
                        var rivalSimilarity = Float.NEGATIVE_INFINITY
                        for (rival in 0 until centroidCount) {
                            if (rival == cluster || counts[rival] == 0) continue
                            rivalSimilarity = maxOf(
                                rivalSimilarity,
                                dot(rows, row, centroids, rival, dim),
                            )
                        }
                        val margin = if (rivalSimilarity == Float.NEGATIVE_INFINITY) {
                            // The maximum possible cosine gap, used only for a one-cluster
                            // experiment where there is no decision boundary to be uncertain about.
                            2f
                        } else {
                            ownSimilarity - rivalSimilarity
                        }
                        row to TrackClusterMembership(
                            trackId = ids[row],
                            centroidSimilarity = ownSimilarity,
                            assignmentMargin = margin,
                        )
                    }
                    .sortedByDescending { (_, membership) -> membership.centroidSimilarity }
            }
            // Largest first is only a default; a caller who knows the listener re-ranks these by
            // how much time was actually spent in each.
            .sortedWith(
                compareByDescending<List<Pair<Int, TrackClusterMembership>>> { it.size }
                    .thenBy { it.first().first },
            )
            .map { ranked ->
                TrackCluster(
                    members = ranked.map { (_, membership) -> membership.trackId },
                    memberships = ranked.map { (_, membership) -> membership },
                )
            }
    }

    private fun copyRow(from: FloatArray, fromRow: Int, into: FloatArray, intoRow: Int, dim: Int) {
        from.copyInto(into, intoRow * dim, fromRow * dim, fromRow * dim + dim)
    }

    private fun dot(a: FloatArray, rowA: Int, b: FloatArray, rowB: Int, dim: Int): Float {
        var sum = 0f
        val baseA = rowA * dim
        val baseB = rowB * dim
        for (d in 0 until dim) sum += a[baseA + d] * b[baseB + d]
        return sum
    }

    /** Squared Euclidean distance. Both operands are unit-length, so this is `2 - 2·cos`. */
    private fun squaredDistance(a: FloatArray, rowA: Int, b: FloatArray, rowB: Int, dim: Int): Float =
        (2f - 2f * dot(a, rowA, b, rowB, dim)).coerceAtLeast(0f)

    private fun norm(vector: FloatArray): Float {
        var sumSq = 0.0
        for (x in vector) sumSq += x.toDouble() * x
        return sqrt(sumSq).toFloat()
    }
}
