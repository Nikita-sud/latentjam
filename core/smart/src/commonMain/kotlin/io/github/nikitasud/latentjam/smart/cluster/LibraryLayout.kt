/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt

/** Where one track sits on the Map, in a normalized 0..1 box. */
public data class LayoutPoint(
    public val trackId: TrackId,
    public val x: Float,
    public val y: Float,
)

/**
 * The library's 2-D shape.
 *
 * Projects the same fused space [LibraryWorlds] clusters — so the picture and the regions agree by
 * construction rather than by coincidence — and returns a stable position per track.
 *
 * Pure and deterministic: identical input yields byte-identical output. The Map is a place people
 * learn, and a page that rearranges itself between visits is indistinguishable from a broken one.
 *
 * NOTE: [LibraryVectorSpace] is one-shot. Callers must obtain their own space from
 * `SimilarityEngine.libraryMixFeatures`; passing the instance already consumed by
 * [LibraryWorlds.discover] throws.
 */
public object LibraryLayout {

    /**
     * Layout identity. Part of the cache key, so bumping it invalidates every stored map — which
     * is the point: a different algorithm is a different picture.
     */
    public const val VERSION: String = "tsne-p20-pca50-v1"

    /** Pre-reduction width. Measured: keeping this step is worth ~0.01 on both quality metrics. */
    public const val COMPONENTS: Int = 50

    private const val SEED: Int = 0x1A7E27

    /**
     * @param previous the last stored layout, used as a warm start so an added album nudges the map
     *   rather than redrawing it. Tracks it does not cover start from the centroid of the tracks it
     *   does, so newcomers appear in the middle rather than at a corner.
     */
    public fun compute(
        space: LibraryVectorSpace,
        previous: Map<TrackId, FloatArray> = emptyMap(),
    ): List<LayoutPoint> {
        val ids = space.trackIds
        val n = space.size
        if (n == 0) return emptyList()
        val dim = space.dim
        val rows = space.takeRows()
        if (n < 3) return ids.map { id -> LayoutPoint(id, 0.5f, 0.5f) }

        val reduced = reduceForEmbedding(rows, n, dim)
        val reducedDim = reduced.size / n

        val warm = warmStart(ids, previous, n)
        var embedded = Tsne.embed(reduced, n, reducedDim, SEED, warm)

        if (warm != null) {
            val overlap = ids.withIndex().filter { previous.containsKey(it.value) }
            if (overlap.size >= 2) {
                val candidate = FloatArray(overlap.size * 2)
                val reference = FloatArray(overlap.size * 2)
                overlap.forEachIndexed { slot, (row, id) ->
                    candidate[slot * 2] = embedded[row * 2]
                    candidate[slot * 2 + 1] = embedded[row * 2 + 1]
                    val stored = previous.getValue(id)
                    reference[slot * 2] = stored[0]
                    reference[slot * 2 + 1] = stored[1]
                }
                embedded = applyTransform(embedded, candidate, reference, overlap.size, n)
            }
        }
        return normalize(ids, embedded, n)
    }

    /**
     * Whether [stored] is the layout for exactly [trackIds], and can be shown as-is.
     *
     * When false the stored layout is stale — but still the best available warm start, which is why
     * it is kept under [VERSION] alone rather than under a key that encodes the track set.
     */
    public fun covers(stored: Map<TrackId, FloatArray>, trackIds: List<TrackId>): Boolean =
        stored.size == trackIds.size && stored.keys.containsAll(trackIds)

    /**
     * Pre-reduction for [Tsne.embed]: mean-center (the only precondition [Pca.reduce] documents),
     * reduce, then unit-normalize the *reduced* rows.
     *
     * Unit-normalizing [rows] before [Pca.reduce] would not satisfy [Tsne.embed]'s documented
     * precondition, even though it looks like it should: [Pca.reduce] is a partial orthogonal
     * projection, which does not preserve vector norm except for a row that happens to lie entirely
     * inside the retained subspace. So a unit-normalized input generally comes out of the projection
     * with a row-dependent norm less than one, and squared Euclidean distance between such rows is
     * no longer a monotone function of their cosine distance -- exactly the property [Tsne.embed]
     * needs. Normalizing has to happen on the array [Tsne.embed] actually receives.
     *
     * Visibility is `internal`, not `private`, so [LibraryLayoutTest] can assert directly on the
     * array handed to [Tsne.embed] rather than only on `compute`'s final, twice-removed output.
     *
     * @param rows row-major `n x dim`, mutated in place by centering
     * @return row-major `n x COMPONENTS` (fewer if `dim` or `n` is smaller), unit-normalized per row
     */
    internal fun reduceForEmbedding(rows: FloatArray, n: Int, dim: Int): FloatArray {
        center(rows, n, dim)
        val reduced = Pca.reduce(rows, n, dim, COMPONENTS, SEED)
        val reducedDim = reduced.size / n
        unitize(reduced, n, reducedDim)
        return reduced
    }

    private fun warmStart(
        ids: List<TrackId>,
        previous: Map<TrackId, FloatArray>,
        n: Int,
    ): FloatArray? {
        if (previous.isEmpty()) return null
        var covered = 0
        var cx = 0f
        var cy = 0f
        for (id in ids) {
            val stored = previous[id] ?: continue
            if (stored.size < 2) continue
            covered++
            cx += stored[0]
            cy += stored[1]
        }
        if (covered < 2) return null
        cx /= covered
        cy /= covered
        val out = FloatArray(n * 2)
        ids.forEachIndexed { row, id ->
            val stored = previous[id]
            if (stored != null && stored.size >= 2) {
                out[row * 2] = stored[0]
                out[row * 2 + 1] = stored[1]
            } else {
                out[row * 2] = cx
                out[row * 2 + 1] = cy
            }
        }
        return out
    }

    /**
     * Recovers the similarity transform [LayoutAnchor.align] applied to the overlap and applies it
     * to every row, so newcomers travel with the tracks around them instead of staying in the raw
     * t-SNE frame.
     *
     * [LayoutAnchor.align] is documented to reflect as well as rotate, and a reflection is not
     * representable as a single complex scale-rotation `alignedOverlap = z * candidate`: that model
     * is holomorphic (it can only rotate and scale), while a reflection is anti-holomorphic. Fitting
     * the holomorphic model against reflected data does not recover a transform that reproduces
     * [alignedOverlap] -- it recovers whatever minimizes squared error under a model that cannot
     * represent the true relationship, which is a materially different, wrong transform, not a
     * close approximation. So both hypotheses -- direct and x-mirrored -- are fit, and whichever
     * actually reconstructs [alignedOverlap] from [candidate] (residual ~0, since alignment is an
     * exact isometry on the overlap) is the one applied to the full embedding.
     *
     * Visibility is `internal`, not `private`, solely so [LibraryLayoutTest] can pin this down with
     * a direct regression test: reaching the mirrored branch through [compute] would depend on
     * t-SNE's convergence landing in a particular orientation, which is not something a test can
     * reliably force.
     */
    internal fun applyTransform(
        embedded: FloatArray,
        candidate: FloatArray,
        reference: FloatArray,
        overlap: Int,
        n: Int,
    ): FloatArray {
        val alignedOverlap = LayoutAnchor.align(candidate, reference, overlap)

        var cx = 0f
        var cy = 0f
        var ax = 0f
        var ay = 0f
        for (i in 0 until overlap) {
            cx += candidate[i * 2]
            cy += candidate[i * 2 + 1]
            ax += alignedOverlap[i * 2]
            ay += alignedOverlap[i * 2 + 1]
        }
        cx /= overlap
        cy /= overlap
        ax /= overlap
        ay /= overlap

        val direct = fitSimilarity(candidate, alignedOverlap, overlap, cx, cy, ax, ay, mirror = false)
        val mirrored = fitSimilarity(candidate, alignedOverlap, overlap, cx, cy, ax, ay, mirror = true)
        val useMirror = mirrored.residual < direct.residual
        val chosen = if (useMirror) mirrored else direct
        if (chosen.denominator < 1e-12f) return embedded

        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            var dx = embedded[i * 2] - cx
            val dy = embedded[i * 2 + 1] - cy
            if (useMirror) dx = -dx
            out[i * 2] = ax + dx * chosen.cosScale - dy * chosen.sinScale
            out[i * 2 + 1] = ay + dx * chosen.sinScale + dy * chosen.cosScale
        }
        return out
    }

    private class SimilarityFit(
        val cosScale: Float,
        val sinScale: Float,
        val denominator: Float,
        val residual: Float,
    )

    /**
     * Least-squares complex scale-rotation fitting `target ~= z * source` (or, when [mirror] is
     * set, `target ~= z * mirror-x(source)`), both centered on their respective centroids.
     * [SimilarityFit.residual] is the sum of squared reconstruction error, which is what
     * [applyTransform] uses to tell a genuine fit from a model that cannot represent the data.
     */
    private fun fitSimilarity(
        source: FloatArray,
        target: FloatArray,
        overlap: Int,
        sourceCx: Float,
        sourceCy: Float,
        targetCx: Float,
        targetCy: Float,
        mirror: Boolean,
    ): SimilarityFit {
        var scaleNumerator = 0f
        var sinNumerator = 0f
        var denominator = 0f
        for (i in 0 until overlap) {
            var dx = source[i * 2] - sourceCx
            val dy = source[i * 2 + 1] - sourceCy
            if (mirror) dx = -dx
            val ex = target[i * 2] - targetCx
            val ey = target[i * 2 + 1] - targetCy
            scaleNumerator += dx * ex + dy * ey
            sinNumerator += dx * ey - dy * ex
            denominator += dx * dx + dy * dy
        }
        if (denominator < 1e-12f) return SimilarityFit(0f, 0f, denominator, Float.MAX_VALUE)
        val cosScale = scaleNumerator / denominator
        val sinScale = sinNumerator / denominator

        var residual = 0f
        for (i in 0 until overlap) {
            var dx = source[i * 2] - sourceCx
            val dy = source[i * 2 + 1] - sourceCy
            if (mirror) dx = -dx
            val ex = target[i * 2] - targetCx
            val ey = target[i * 2 + 1] - targetCy
            val px = dx * cosScale - dy * sinScale
            val py = dx * sinScale + dy * cosScale
            val rx = px - ex
            val ry = py - ey
            residual += rx * rx + ry * ry
        }
        return SimilarityFit(cosScale, sinScale, denominator, residual)
    }

    private fun normalize(ids: List<TrackId>, embedded: FloatArray, n: Int): List<LayoutPoint> {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minX = minOf(minX, embedded[i * 2])
            maxX = maxOf(maxX, embedded[i * 2])
            minY = minOf(minY, embedded[i * 2 + 1])
            maxY = maxOf(maxY, embedded[i * 2 + 1])
        }
        val spanX = (maxX - minX).takeIf { it > 1e-6f } ?: 1f
        val spanY = (maxY - minY).takeIf { it > 1e-6f } ?: 1f
        return ids.mapIndexed { row, id ->
            LayoutPoint(
                trackId = id,
                x = ((embedded[row * 2] - minX) / spanX).coerceIn(0f, 1f),
                y = ((embedded[row * 2 + 1] - minY) / spanY).coerceIn(0f, 1f),
            )
        }
    }

    private fun center(rows: FloatArray, n: Int, dim: Int) {
        for (d in 0 until dim) {
            var mean = 0f
            for (i in 0 until n) mean += rows[i * dim + d]
            mean /= n
            for (i in 0 until n) rows[i * dim + d] -= mean
        }
    }

    private fun unitize(rows: FloatArray, n: Int, dim: Int) {
        for (i in 0 until n) {
            var norm = 0f
            for (d in 0 until dim) {
                val value = rows[i * dim + d]
                norm += value * value
            }
            norm = sqrt(norm)
            if (norm < 1e-12f) continue
            for (d in 0 until dim) rows[i * dim + d] /= norm
        }
    }
}
