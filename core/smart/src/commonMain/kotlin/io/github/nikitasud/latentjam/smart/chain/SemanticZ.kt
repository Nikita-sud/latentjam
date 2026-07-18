/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.math.sqrt

/**
 * Pool-relative semantic similarity z-scores for the SMART chain.
 *
 * Why z-scores rather than raw cosines: for a niche seed (Moldovan estradá, anime) the true
 * in-cluster candidates are semantic OUTLIERS within the retrieved pool — their descriptor
 * similarity to the seed sits 2–3σ above the pool mean — while the audio scorer confidently demotes
 * them, scoring the whole niche near its tanh floor because it was trained mostly on the big
 * clusters. A raw cosine term small enough not to disturb those big clusters is far too small to
 * close that gap (~2.6 chain-score points, measured offline).
 *
 * Normalising per pool makes the term adaptive: in a homogeneous pool (disco seed → all-disco pool)
 * similarities cluster tightly, z stays near zero and ranking is untouched; in a mixed pool the
 * in-cluster outliers get the boost exactly where it is needed.
 */
internal object SemanticZ {

    /** Symmetric clip, so one spectacular similarity can't dominate the whole chain score. */
    const val Z_CLIP = 3.0f

    /**
     * Floor on the pool similarity spread. A near-uniform pool (σ→0) would otherwise turn noise
     * into huge z-scores; below this spread the differences aren't meaningful signal.
     */
    const val STD_FLOOR = 0.05f

    /** Minimum pool members holding a vector for the statistics to be usable at all. */
    const val MIN_VALID = 10

    /**
     * Per-space z-scores of cosine(ref, candidate) over the pool.
     *
     * Returns `NaN` at positions whose candidate has no vector, so [combine] can weight by
     * per-candidate availability; returns `null` when the reference has no vector or fewer than
     * [MIN_VALID] candidates do — callers treat that as "this space contributes nothing".
     */
    fun poolZ(
        matrix: FloatArray?,
        has: BooleanArray?,
        dim: Int,
        refRow: Int,
        poolRows: IntArray,
    ): FloatArray? {
        if (matrix == null || has == null) return null
        if (refRow < 0 || refRow >= has.size || !has[refRow]) return null
        val n = poolRows.size
        val sims = FloatArray(n)
        val valid = BooleanArray(n)
        var validCount = 0
        val refBase = refRow * dim
        for (k in 0 until n) {
            val row = poolRows[k]
            if (row < 0 || row >= has.size || !has[row]) continue
            var dot = 0f
            val base = row * dim
            for (d in 0 until dim) dot += matrix[refBase + d] * matrix[base + d]
            sims[k] = dot
            valid[k] = true
            validCount++
        }
        if (validCount < MIN_VALID) return null

        var mean = 0.0
        for (k in 0 until n) if (valid[k]) mean += sims[k]
        mean /= validCount
        var varSum = 0.0
        for (k in 0 until n) if (valid[k]) {
            val d = sims[k] - mean
            varSum += d * d
        }
        val std = sqrt(varSum / validCount).toFloat().coerceAtLeast(STD_FLOOR)

        val meanF = mean.toFloat()
        return FloatArray(n) { k ->
            if (valid[k]) ((sims[k] - meanF) / std).coerceIn(-Z_CLIP, Z_CLIP) else Float.NaN
        }
    }

    /**
     * Availability-weighted combination of the two semantic spaces (offline nomic descriptors and
     * on-device MiniLM metadata text): per candidate, the mean of the z-scores from the spaces
     * where both reference and candidate have a vector; 0 when neither applies.
     *
     * Averaging beats either alone — descriptors separate Moldovan estradá but see through the
     * "anime" label to per-track mood, while metadata text groups anime OSTs but is noisy on
     * Romanian titles.
     */
    fun combine(zA: FloatArray?, zB: FloatArray?, size: Int): FloatArray =
        FloatArray(size) { k ->
            val a = zA?.get(k)?.takeUnless { it.isNaN() }
            val b = zB?.get(k)?.takeUnless { it.isNaN() }
            when {
                a != null && b != null -> (a + b) / 2f
                a != null -> a
                b != null -> b
                else -> 0f
            }
        }
}
