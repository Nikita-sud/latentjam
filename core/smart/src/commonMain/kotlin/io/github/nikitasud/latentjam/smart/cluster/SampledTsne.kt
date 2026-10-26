/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Supplies one unit-normalized fused row at a time, so the full matrix need not be held. */
internal fun interface LayoutRowSource {
    suspend fun read(row: Int, out: FloatArray)
}

/**
 * "Sample and place" layout for very large libraries (prototype, see
 * docs/map-sample-place-2026-10-16.md).
 *
 * A stable sample, chosen by [AverageLinkageClustering.stableKey] like sampled regions, is laid
 * out with [BarnesHutTsne]. The PCA basis is fitted on the sample, so every row is reduced one at
 * a time. Every other track is placed at the perplexity-weighted mean of its [PLACE_NEIGHBORS]
 * nearest sampled tracks in the reduced space, then optionally refined for a few iterations with
 * the sample held fixed (as in openTSNE's transform).
 */
internal object SampledTsne {

    const val SAMPLE_SIZE: Int = 3000
    const val PLACE_NEIGHBORS: Int = 10
    const val REFINE_ITERATIONS: Int = 0
    private const val ROW_POLL = 256

    /** Rows whose ids sort first by stable key, in ascending row order. */
    fun sampleRows(ids: List<TrackId>, size: Int): IntArray {
        if (size >= ids.size) return IntArray(ids.size) { it }
        val keys = LongArray(ids.size) { AverageLinkageClustering.stableKey(ids[it].value) }
        return ids.indices.sortedWith(compareBy<Int> { keys[it] }.thenBy { it })
            .take(size).sorted().toIntArray()
    }

    /**
     * Mean over every row, then each row centered and re-normalized as
     * [LibraryLayout.reduceForEmbedding] does with the whole matrix, reduced to PCA-50 and
     * normalized again. With [sample] null the basis comes from every row through
     * [Pca.reduceStreamed] (bit-identical to the held-matrix path); otherwise it is fitted on the
     * sample's rows only. Null when cancelled.
     *
     * @return row-major `n x k`
     */
    suspend fun reduce(
        source: LayoutRowSource,
        n: Int,
        dim: Int,
        sample: IntArray?,
        components: Int,
        seed: Int,
        /** Collects the mean, the basis and the raw rows' hashes for the Map's refresh state. */
        capture: ReductionCapture? = null,
        isActive: () -> Boolean,
    ): FloatArray? {
        val row = FloatArray(dim)
        // Float sums in row order, exactly as LibraryLayout's in-place centering accumulates.
        val mean = FloatArray(dim)
        for (i in 0 until n) {
            if (i % ROW_POLL == 0 && !isActive()) return null
            source.read(i, row)
            for (d in 0 until dim) mean[d] += row[d]
        }
        for (d in 0 until dim) mean[d] /= n
        val hashes = capture?.let { LongArray(n) }
        val prepared: suspend (Int, FloatArray) -> Unit = { i, out ->
            source.read(i, out)
            // Every pass overwrites, so the hash kept is the final projection pass's raw row.
            if (hashes != null) hashes[i] = MapRefreshRows.hash(out, dim)
            centerAndUnitize(out, mean, dim)
        }
        if (sample == null) {
            val out = Pca.reduceStreamed(prepared, n, dim, components, seed, isActive, basisOut = capture?.let { c -> { c.basis = it } })
                ?: return null
            if (capture != null) { capture.mean = mean; capture.hashes = hashes }
            val k = out.size / n
            for (i in 0 until n) unitize(out, i * k, k)
            return out
        }
        val s = sample.size
        var sampleMatrix: FloatArray? = FloatArray(s * dim)
        for ((slot, i) in sample.withIndex()) {
            if (slot % ROW_POLL == 0 && !isActive()) return null
            prepared(i, row)
            row.copyInto(sampleMatrix!!, slot * dim)
        }
        val basis = Pca.basis(sampleMatrix!!, s, dim, components, seed, isActive) ?: return null
        sampleMatrix = null
        val k = basis.size / dim
        val out = FloatArray(n * k)
        for (i in 0 until n) {
            if (i % ROW_POLL == 0 && !isActive()) return null
            prepared(i, row)
            Pca.project(row, dim, basis, k, out, i * k)
            unitize(out, i * k, k)
        }
        return out
    }

    /**
     * @param reduced row-major `n x dim`, unit rows (from [reduce])
     * @param initial warm start for all `n` rows, or null; only the sample's rows are used
     * @return row-major `n x 2`, or null when cancelled
     */
    fun embed(
        reduced: FloatArray,
        n: Int,
        dim: Int,
        sample: IntArray,
        seed: Int,
        initial: FloatArray?,
        isActive: () -> Boolean,
        refineIterations: Int = REFINE_ITERATIONS,
        refineRate: Float = 1f,
        neighbours: Int = PLACE_NEIGHBORS,
        onPhase: (String) -> Unit = {},
        fullIterations: Int = 0,
        sampleIterations: Int = Tsne.ITERATIONS,
    ): FloatArray? {
        val s = sample.size
        val sampleReduced = FloatArray(s * dim)
        for ((slot, i) in sample.withIndex()) reduced.copyInto(sampleReduced, slot * dim, i * dim, (i + 1) * dim)
        val sampleInitial = initial?.let { warm ->
            FloatArray(s * 2).also { out ->
                for ((slot, i) in sample.withIndex()) {
                    out[slot * 2] = warm[i * 2]
                    out[slot * 2 + 1] = warm[i * 2 + 1]
                }
            }
        }
        val ys = BarnesHutTsne.embed(
            sampleReduced, s, dim, seed, sampleInitial, isActive,
            iterations = sampleIterations,
            exaggerationIterations = minOf(Tsne.EXAGGERATION_ITERATIONS, sampleIterations / 4),
        )
        if (!isActive()) return null
        onPhase("sample-embedded")

        val y = FloatArray(n * 2)
        val inSample = BooleanArray(n)
        for ((slot, i) in sample.withIndex()) {
            inSample[i] = true
            y[i * 2] = ys[slot * 2]
            y[i * 2 + 1] = ys[slot * 2 + 1]
        }
        val placed = (0 until n).filter { !inSample[it] }.toIntArray()
        val k = minOf(neighbours, s)
        val index = IntArray(placed.size * k)
        val weight = FloatArray(placed.size * k)
        val bestIndex = IntArray(k)
        val bestDistance = FloatArray(k)
        val conditional = FloatArray(k)
        val target = ln(maxOf(2f, k / 3f))
        for ((p, i) in placed.withIndex()) {
            if (p % ROW_POLL == 0 && !isActive()) return null
            nearest(reduced, i, dim, sampleReduced, s, k, bestIndex, bestDistance)
            calibrate(bestDistance, k, target, conditional)
            var x = 0f
            var yy = 0f
            for (m in 0 until k) {
                val slot = bestIndex[m]
                index[p * k + m] = slot
                weight[p * k + m] = conditional[m]
                x += conditional[m] * ys[slot * 2]
                yy += conditional[m] * ys[slot * 2 + 1]
            }
            y[i * 2] = x
            y[i * 2 + 1] = yy
        }

        onPhase("placed")
        if (refineIterations > 0 && placed.isNotEmpty()) {
            val tree = BarnesHutTsne.QuadTree(s)
            tree.build(ys)
            val repulsion = FloatArray(2)
            val velocity = FloatArray(placed.size * 2)
            for (iteration in 0 until refineIterations) {
                if (!isActive()) return null
                for ((p, i) in placed.withIndex()) {
                    val ix = y[i * 2]
                    val iy = y[i * 2 + 1]
                    var ax = 0f
                    var ay = 0f
                    for (m in 0 until k) {
                        val slot = index[p * k + m]
                        val dx = ix - ys[slot * 2]
                        val dy = iy - ys[slot * 2 + 1]
                        val force = weight[p * k + m] / (1f + dx * dx + dy * dy)
                        ax += force * dx
                        ay += force * dy
                    }
                    val z = tree.repelAt(ix, iy, -1, ys, repulsion, 0).toFloat().coerceAtLeast(1e-12f)
                    var gx = 4f * (ax - repulsion[0] / z)
                    var gy = 4f * (ay - repulsion[1] / z)
                    val norm = sqrt(gx * gx + gy * gy)
                    if (norm > MAX_GRAD) {
                        gx *= MAX_GRAD / norm
                        gy *= MAX_GRAD / norm
                    }
                    velocity[p * 2] = 0.5f * velocity[p * 2] - refineRate * gx
                    velocity[p * 2 + 1] = 0.5f * velocity[p * 2 + 1] - refineRate * gy
                    y[i * 2] = ix + velocity[p * 2]
                    y[i * 2 + 1] = iy + velocity[p * 2 + 1]
                }
            }
        }
        if (fullIterations > 0) {
            // Every row, placed rows included, with full sparse affinities; no exaggeration and
            // late momentum, since the start is already globally arranged.
            val refined = BarnesHutTsne.embed(
                reduced, n, dim, seed, y, isActive,
                iterations = fullIterations,
                exaggerationIterations = 0,
                scheduleOffset = Tsne.ITERATIONS,
            )
            if (!isActive()) return null
            onPhase("full-refined")
            return refined
        }
        return y
    }

    private const val MAX_GRAD = 4f

    /** [k] nearest sample rows to `reduced[row]`, ascending by squared distance, ties by slot. */
    private fun nearest(
        reduced: FloatArray,
        row: Int,
        dim: Int,
        sample: FloatArray,
        s: Int,
        k: Int,
        bestIndex: IntArray,
        bestDistance: FloatArray,
    ) {
        var filled = 0
        val base = row * dim
        for (slot in 0 until s) {
            val b = slot * dim
            var sum = 0f
            for (d in 0 until dim) {
                val delta = reduced[base + d] - sample[b + d]
                sum += delta * delta
            }
            if (filled == k && sum >= bestDistance[k - 1]) continue
            var position = if (filled < k) filled++ else k - 1
            while (position > 0 && bestDistance[position - 1] > sum) {
                bestDistance[position] = bestDistance[position - 1]
                bestIndex[position] = bestIndex[position - 1]
                position--
            }
            bestDistance[position] = sum
            bestIndex[position] = slot
        }
    }

    /** Gaussian conditional over [k] squared distances with perplexity `exp(target)`. */
    private fun calibrate(distance: FloatArray, k: Int, target: Float, out: FloatArray) {
        var low = 0f
        var high = Float.MAX_VALUE
        var beta = 1f
        val offset = distance[0]
        for (step in 0 until Tsne.PERPLEXITY_STEPS) {
            var sum = 0f
            var entropySum = 0f
            for (m in 0 until k) {
                val d = distance[m] - offset
                val value = exp(-d * beta)
                out[m] = value
                sum += value
                entropySum += d * value
            }
            if (sum <= 0f) sum = 1e-12f
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
        var total = 0f
        for (m in 0 until k) total += out[m]
        for (m in 0 until k) out[m] /= total
    }

    internal fun centerAndUnitize(row: FloatArray, mean: FloatArray, dim: Int) {
        for (d in 0 until dim) row[d] -= mean[d]
        unitize(row, 0, dim)
    }

    internal fun unitize(values: FloatArray, offset: Int, dim: Int) {
        var norm = 0f
        for (d in 0 until dim) norm += values[offset + d] * values[offset + d]
        norm = sqrt(norm)
        if (norm < 1e-12f) return
        for (d in 0 until dim) values[offset + d] /= norm
    }
}
