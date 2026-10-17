/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Where a track sits in its library, as two numbers a colour can be made from: an angle around
 * the library and a distance from its middle.
 *
 * The axes are the library's own two leading principal directions, so the angle follows what
 * actually separates this listener's tracks. Every track embedding shares a large common
 * direction; a fixed projection of the raw vector (the old seed summed two halves of it) is
 * dominated by that shared part and gives nearly every track the same hue. Here the mean is
 * removed first, each axis is scaled to unit spread, and the angle is passed through the
 * library's own angle distribution, so the library covers the whole wheel evenly while tracks
 * that sit close together keep close angles (the mapping is monotonic).
 */
public class LatentColorAxes private constructor(
    private val dim: Int,
    private val mean: FloatArray,
    /** `dim x 2`, row-major by dimension. */
    private val basis: FloatArray,
    private val scale: FloatArray,
    /** The fitted tracks' angles, ascending, for the empirical distribution. */
    private val angles: FloatArray,
    /** The fitted tracks' whitened radii, ascending. */
    private val radii: FloatArray,
) {
    /** How many tracks the axes were fitted on. */
    public val fittedOn: Int get() = angles.size

    /**
     * [embedding]'s place: `angle` in `[0, 1)` around the library, `spread` in `[0, 1]` from its
     * middle (0 = the most typical track, 1 = the most unusual). [evenness] blends the angle from
     * the raw geometry (0) to the library's evenly spread distribution (1). Null for a vector of
     * another size or one that is not finite.
     */
    public fun place(embedding: FloatArray, evenness: Float = DEFAULT_EVENNESS): LatentPlace? {
        if (embedding.size != dim) return null
        var x = 0f
        var y = 0f
        for (d in 0 until dim) {
            val value = embedding[d] - mean[d]
            x += value * basis[d * 2]
            y += value * basis[d * 2 + 1]
        }
        x /= scale[0]
        y /= scale[1]
        if (!x.isFinite() || !y.isFinite()) return null
        val raw = atan2(y, x)
        // Fully even spreads a library that is mostly one kind of music across the whole wheel,
        // exaggerating small differences; fully raw leaves a lopsided library in one corner of it.
        val even = cumulative(angles, raw)
        val geometric = (raw / (2f * PI.toFloat()) + 0.5f)
        val angle = evenness * even + (1f - evenness) * geometric
        val spread = cumulative(radii, sqrt(x * x + y * y))
        return LatentPlace(angle = angle.coerceIn(0f, 0.999999f), spread = spread.coerceIn(0f, 1f))
    }

    public companion object {
        /** Fewer tracks than this have no meaningful axes; the caller keeps its fallback colour. */
        public const val MIN_TRACKS: Int = 24

        /** Half way between the library's own geometry and an evenly filled wheel. */
        public const val DEFAULT_EVENNESS: Float = 0.5f

        /**
         * Fits the axes on [rows] (one embedding each, all of one length). Null when there are too
         * few finite rows or the library has no spread at all.
         */
        public fun fit(rows: List<FloatArray>, seed: Int = 0x1A7E): LatentColorAxes? {
            val dim = rows.firstOrNull()?.size ?: return null
            val usable = rows.filter { row -> row.size == dim && row.all { it.isFinite() } }
            val n = usable.size
            if (n < MIN_TRACKS || dim < 2) return null
            val mean = FloatArray(dim)
            for (row in usable) for (d in 0 until dim) mean[d] += row[d]
            for (d in 0 until dim) mean[d] /= n
            val centered = FloatArray(n * dim)
            usable.forEachIndexed { i, row -> for (d in 0 until dim) centered[i * dim + d] = row[d] - mean[d] }
            val basis = Pca.basis(centered, n, dim, components = 2, seed = seed) ?: return null
            if (basis.size < dim * 2) return null
            val projected = FloatArray(n * 2)
            for (i in 0 until n) {
                var x = 0f
                var y = 0f
                for (d in 0 until dim) {
                    val value = centered[i * dim + d]
                    x += value * basis[d * 2]
                    y += value * basis[d * 2 + 1]
                }
                projected[i * 2] = x
                projected[i * 2 + 1] = y
            }
            // A principal direction has no sign of its own. Point each along its longer tail
            // (positive third moment), so refitting a slightly larger library does not flip the
            // wheel round and recolour every track.
            for (c in 0 until 2) {
                var cube = 0.0
                for (i in 0 until n) cube += projected[i * 2 + c].toDouble().let { it * it * it }
                if (cube < 0) {
                    for (d in 0 until dim) basis[d * 2 + c] = -basis[d * 2 + c]
                    for (i in 0 until n) projected[i * 2 + c] = -projected[i * 2 + c]
                }
            }
            val scale = FloatArray(2) { c ->
                var sum = 0.0
                for (i in 0 until n) sum += projected[i * 2 + c].toDouble().let { it * it }
                sqrt(sum / n).toFloat()
            }
            if (scale.any { it <= 1e-9f || !it.isFinite() }) return null
            val angles = FloatArray(n)
            val radii = FloatArray(n)
            for (i in 0 until n) {
                val x = projected[i * 2] / scale[0]
                val y = projected[i * 2 + 1] / scale[1]
                angles[i] = atan2(y, x)
                radii[i] = sqrt(x * x + y * y)
            }
            angles.sort()
            radii.sort()
            return LatentColorAxes(dim, mean, basis, scale, angles, radii)
        }

        /** The share of [sorted] below [value], interpolated between neighbours. */
        private fun cumulative(sorted: FloatArray, value: Float): Float {
            if (sorted.isEmpty()) return 0f
            if (value <= sorted.first()) return 0f
            if (value >= sorted.last()) return 1f
            var low = 0
            var high = sorted.lastIndex
            while (high - low > 1) {
                val middle = (low + high) ushr 1
                if (sorted[middle] <= value) low = middle else high = middle
            }
            val span = sorted[high] - sorted[low]
            val within = if (abs(span) < 1e-12f) 0f else (value - sorted[low]) / span
            return (low + within) / sorted.lastIndex
        }
    }
}

/** See [LatentColorAxes.place]. */
public data class LatentPlace(val angle: Float, val spread: Float)
