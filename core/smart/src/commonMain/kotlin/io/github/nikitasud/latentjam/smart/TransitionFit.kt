/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How naturally one track follows another, read from the analysis SMART already keeps. The player
 * sizes the crossfade between them with it (issue #25): a long blend between two tracks that
 * belong together, a short one across a change.
 *
 * Both measures are relative to the listener's own library, so they mean the same whatever the
 * encoder's raw cosine scale is (it drifts between encoder versions, and is almost never negative).
 */
public data class TransitionFit(
    /**
     * The share of the library that sounds further from the outgoing track than the incoming one
     * does: near 1 for one of its closest tracks, about 0.5 for a typical one, near 0 for an outlier.
     */
    public val closeness: Float,
    /**
     * How far apart the two tracks' energies are against the library's own spread: 0 alike, 1 at
     * [ENERGY_GAP_FULL_SPREADS] interquartile ranges or more. Null when either energy is unknown.
     */
    public val energyGap: Float?,
)

/** An energy gap this many of the library's interquartile ranges wide counts as a full change of pace. */
internal const val ENERGY_GAP_FULL_SPREADS: Double = 2.0

/** Fewer comparison tracks than this cannot place a pair in the library, so no fit is given. */
internal const val TRANSITION_MIN_SAMPLE: Int = 16

/** At most this many library tracks are compared per transition: a few hundred dot products. */
internal const val TRANSITION_SAMPLE_SIZE: Int = 256

/** The share of [others] that are less similar to the outgoing track than [target] is. */
internal fun closenessPercentile(target: Float, others: FloatArray): Float? {
    if (others.size < TRANSITION_MIN_SAMPLE || !target.isFinite()) return null
    var below = 0
    var ties = 0
    for (value in others) {
        if (value < target) below++ else if (value == target) ties++
    }
    return ((below + ties * 0.5f) / others.size).coerceIn(0f, 1f)
}

/** [ENERGY_GAP_FULL_SPREADS]-scaled distance between two energies; null when the spread is unknown. */
internal fun energyGap(a: Double?, b: Double?, interquartileRange: Double?): Float? {
    if (a == null || b == null || interquartileRange == null) return null
    if (!a.isFinite() || !b.isFinite() || !(interquartileRange > 0.0)) return null
    return (abs(a - b) / (ENERGY_GAP_FULL_SPREADS * interquartileRange)).toFloat().coerceIn(0f, 1f)
}

/** The interquartile range of [values]; null for fewer than [TRANSITION_MIN_SAMPLE]. */
internal fun interquartileRange(values: DoubleArray): Double? {
    if (values.size < TRANSITION_MIN_SAMPLE) return null
    val sorted = values.sortedArray()
    fun at(q: Double): Double = sorted[((sorted.size - 1) * q).toInt()]
    return (at(0.75) - at(0.25)).takeIf { it > 0.0 }
}

internal fun transitionCosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size || a.isEmpty()) return Float.NaN
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i].toDouble() * b[i]
        na += a[i].toDouble() * a[i]
        nb += b[i].toDouble() * b[i]
    }
    if (!(na > 0.0) || !(nb > 0.0)) return Float.NaN
    return (dot / sqrt(na * nb)).toFloat()
}
