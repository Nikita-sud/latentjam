/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.IndexStore
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
     * Minimum correspondence points before [alignToPrevious] attempts a similarity fit.
     *
     * Two points is an exact-isometry problem with two equally valid solutions: both the direct
     * and the x-mirrored hypothesis in [applyTransform] reconstruct a 2-point overlap with residual
     * 0, so the tie-break that picks between them has nothing but floating-point noise to go on --
     * and whichever it picks gets applied to every point in the layout. Three points breaks the tie
     * for any non-degenerate (non-collinear) overlap, which is what real t-SNE output produces.
     */
    private const val MIN_ALIGNMENT_OVERLAP: Int = 3

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
        // Below 3 points, both the reduction and t-SNE are degenerate anyway (see their own n < 3
        // guards); short-circuit before space.takeRows() so this path never touches the one-shot
        // matrix at all.
        if (n < 3) return ids.map { id -> LayoutPoint(id, 0.5f, 0.5f) }
        val dim = space.dim
        val rows = space.takeRows()

        val reduced = reduceForEmbedding(rows, n, dim)
        val reducedDim = reduced.size / n

        val warm = warmStart(ids, previous, n)
        var embedded = Tsne.embed(reduced, n, reducedDim, SEED, warm)

        if (warm != null) {
            embedded = alignToPrevious(ids, embedded, previous, n)
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
     * Aligns [embedded] to [previous] via [applyTransform], or leaves it untouched when there
     * aren't enough correspondence points to trust the fit -- see [MIN_ALIGNMENT_OVERLAP].
     *
     * Visibility is `internal`, not `private`, so [LibraryLayoutTest] can pin the overlap == 2
     * boundary directly: forcing that exact tie-break through [compute] would depend on t-SNE's
     * convergence, which a test cannot reliably steer.
     */
    internal fun alignToPrevious(
        ids: List<TrackId>,
        embedded: FloatArray,
        previous: Map<TrackId, FloatArray>,
        n: Int,
    ): FloatArray {
        val overlap = ids.withIndex().filter { previous.containsKey(it.value) }
        if (overlap.size < MIN_ALIGNMENT_OVERLAP) return embedded
        val candidate = FloatArray(overlap.size * 2)
        val reference = FloatArray(overlap.size * 2)
        overlap.forEachIndexed { slot, (row, id) ->
            candidate[slot * 2] = embedded[row * 2]
            candidate[slot * 2 + 1] = embedded[row * 2 + 1]
            val stored = previous.getValue(id)
            reference[slot * 2] = stored[0]
            reference[slot * 2 + 1] = stored[1]
        }
        return applyTransform(embedded, candidate, reference, overlap.size, n)
    }

    /**
     * Pre-reduction for [Tsne.embed]: mean-center (the only precondition [Pca.reduce] documents),
     * unit-normalize so PCA sees per-track *directions* rather than magnitude, reduce, then
     * unit-normalize the *reduced* rows so [Tsne.embed]'s own precondition holds too.
     *
     * Both normalize steps are needed and neither substitutes for the other:
     *
     * - The pre-PCA one guards [Pca.reduce] itself. [rows] arrives already unit-normalized (fusion
     *   guarantees that -- see `LibraryVectorFusion.copyNormalized`), but [center] does not preserve
     *   it: subtracting a shared per-dimension mean from a set of unit vectors leaves each row with
     *   a magnitude that depends on how far its *direction* sits from the corpus-mean direction, not
     *   on anything meaningful about the track -- a track whose fused vector happens to point near
     *   the library's average direction shrinks much more than one that points away from it. Left
     *   uncorrected, [Pca.reduce]'s leading components chase that centering-induced magnitude spread
     *   instead of the cosine structure the rest of this pipeline is built on -- changing *which* 50
     *   components come out, not merely their scale. Re-normalizing after centering removes that
     *   artifact without undoing the centering [Pca.reduce] still requires.
     * - The post-PCA one guards [Tsne.embed]. [Pca.reduce] is a partial orthogonal projection, which
     *   does not preserve vector norm except for a row that happens to lie entirely inside the
     *   retained subspace -- so even unit-length input generally comes out with a row-dependent norm
     *   less than one, and squared Euclidean distance between such rows is no longer a monotone
     *   function of their cosine distance. Normalizing has to happen again on the array
     *   [Tsne.embed] actually receives.
     *
     * Visibility is `internal`, not `private`, so [LibraryLayoutTest] can assert directly on the
     * array handed to [Tsne.embed] rather than only on `compute`'s final, twice-removed output.
     *
     * @param rows row-major `n x dim`, mutated in place by centering and the first normalize
     * @return row-major `n x COMPONENTS` (fewer if `dim` or `n` is smaller), unit-normalized per row
     */
    internal fun reduceForEmbedding(rows: FloatArray, n: Int, dim: Int): FloatArray {
        prepareForPca(rows, n, dim)
        val reduced = Pca.reduce(rows, n, dim, COMPONENTS, SEED)
        val reducedDim = reduced.size / n
        unitize(reduced, n, reducedDim)
        return reduced
    }

    /**
     * Centers [rows], then unit-normalizes each row, so [Pca.reduce] sees per-track direction
     * relative to the library's centroid rather than per-track magnitude. See [reduceForEmbedding]
     * for why both steps -- and this ordering -- matter.
     *
     * Mutates [rows] in place, satisfying [Pca.reduce]'s own documented precondition (mean-centered
     * input) since centering runs first. Strictly, the per-row `unitize` that follows is a nonlinear
     * reweighting and reintroduces a nonzero mean -- but only because it rescales each row by a
     * *different* factor (that row's own inverse norm): if every row happened to share the same
     * post-centering norm, dividing by one common scalar would preserve the exact zero mean [center]
     * just established instead. So whatever mean `unitize` reintroduces is driven entirely by how
     * much the rows' norms vary after centering, not by anything unbounded, and is left uncorrected
     * rather than spending a second centering pass on it.
     *
     * Visibility is `internal`, not `private`, so [LibraryLayoutTest] can assert directly that the
     * rows handed to [Pca.reduce] are unit-norm, rather than trusting this ran from downstream
     * output alone.
     */
    internal fun prepareForPca(rows: FloatArray, n: Int, dim: Int) {
        center(rows, n, dim)
        unitize(rows, n, dim)
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
     * Callers must supply at least [MIN_ALIGNMENT_OVERLAP] correspondence points ([alignToPrevious]
     * enforces this). Below that, both hypotheses reconstruct the overlap exactly, so the residual
     * comparison this function relies on to choose between them carries no signal.
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

    /**
     * Fits [embedded] into the 0..1 box with a single, uniform scale for both axes, so this step
     * is a rigid-plus-uniform-scale map and cannot undo the rotation [applyTransform] just spent an
     * exact isometry recovering.
     *
     * An independent per-axis scale (the old behaviour) is not rotation-invariant: two recomputes
     * that agree on orientation upstream can still render at different x:y aspect ratios whenever
     * the raw embedding's spans shift even slightly, which is the generic case whenever the track
     * set changes at all. It also lies about the shape of the space -- a cluster that is round in
     * the embedding can render as an ellipse merely because its bounding box wasn't square.
     *
     * The longer axis fills its full 0..1 range; the shorter axis is centered in the leftover room
     * rather than pinned to the edge, so a single-file line of points (or, in the fully degenerate
     * case, a single shared position on one axis) lands in the middle of the box instead of its
     * corner.
     *
     * Visibility is `internal`, not `private`, so [LibraryLayoutTest] can assert aspect-ratio
     * preservation directly against a hand-built, deliberately elongated `embedded` array, rather
     * than depend on t-SNE happening to produce an elongated embedding.
     */
    internal fun normalize(ids: List<TrackId>, embedded: FloatArray, n: Int): List<LayoutPoint> {
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
        val spanX = maxX - minX
        val spanY = maxY - minY
        val scale = maxOf(spanX, spanY).takeIf { it > 1e-6f } ?: 1f
        // Half of whatever room the shorter axis doesn't need, so it centers instead of hugging 0.
        val offsetX = (scale - spanX) / 2f
        val offsetY = (scale - spanY) / 2f
        return ids.mapIndexed { row, id ->
            LayoutPoint(
                trackId = id,
                x = ((embedded[row * 2] - minX + offsetX) / scale).coerceIn(0f, 1f),
                y = ((embedded[row * 2 + 1] - minY + offsetY) / scale).coerceIn(0f, 1f),
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

/**
 * Reads whatever layout is stored, current or not.
 *
 * Always returns a map rather than a nullable, because a stale layout is not a miss — it is the
 * warm start. Ask [LibraryLayout.covers] whether it can also be drawn as-is.
 */
public suspend fun IndexStore.loadLayout(): Map<TrackId, FloatArray> =
    load(LibraryLayout.VERSION).orEmpty()

/** Replaces the stored layout. Positions persist as 2-float vectors, so no new format is needed. */
public suspend fun IndexStore.saveLayout(points: List<LayoutPoint>) {
    save(LibraryLayout.VERSION, points.associate { it.trackId to floatArrayOf(it.x, it.y) })
}
