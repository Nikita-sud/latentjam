/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.math.sqrt

/**
 * Tail-exhaustion re-anchor for the SMART chain.
 *
 * From output position [MIN_IDX] on, when fewer than [NICHE_MIN] still-eligible candidates sit
 * within [NICHE_COS] fused (audio+descriptor) cosine of the ORIGINAL seed, the seed's niche is
 * spent: a niche seed (Moldovan estradá, anime) has exhausted the tracks that truly sound like it,
 * and continuing to anchor the seed-gravity and semantic-seed terms on that empty niche makes the
 * tail thrash. The re-anchor keeps [SEED_KEEP] of the original intent and blends the rest toward the
 * chain's own centroid, so the tail follows where the walk actually went.
 *
 * Hops before [MIN_IDX] are untouched, and the trigger is per-hop, so a chain that never exhausts
 * its niche is byte-identical to one built without this object at all.
 *
 * Only the pure pieces live here — the fused-cosine trigger metric, the exhaustion predicate and the
 * effective-seed blend. The stateful walk-time wiring (niche count, centroid, medoid reference) is
 * in [SmartChain]; these are the parts the offline harness and the chain must compute identically.
 */
internal object Reanchor {

    const val ENABLED = true
    const val MIN_IDX = 8
    const val NICHE_COS = 0.40f
    const val NICHE_MIN = 3
    const val SEED_KEEP = 0.4f

    /**
     * Fused audio+descriptor cosine for the trigger: equal-weight mean of the two centered-space
     * cosines, or audio-only when the candidate or seed lacks a descriptor ([descCos] null).
     */
    fun fusedCos(audioCos: Float, descCos: Float?): Float =
        if (descCos == null) audioCos else 0.5f * (audioCos + descCos)

    /** True when fewer than [NICHE_MIN] eligible candidates remain in the seed's niche. */
    fun reanchorExhausted(onNiche: Int): Boolean = onNiche < NICHE_MIN

    /**
     * Effective seed for an exhausted tail hop: [keep]·seed + (1−[keep])·centroid, L2-normalized.
     * Both inputs are unit centered-audio vectors; the result replaces the seed in the gravity term.
     */
    fun reanchorBlend(seed: FloatArray, centroid: FloatArray, keep: Float): FloatArray {
        val out = FloatArray(seed.size)
        val other = 1f - keep
        for (j in seed.indices) out[j] = keep * seed[j] + other * centroid[j]
        var sumSq = 0.0
        for (x in out) sumSq += x.toDouble() * x
        val norm = sqrt(sumSq).toFloat()
        if (norm > 1e-12f) {
            val inv = 1f / norm
            for (j in out.indices) out[j] *= inv
        }
        return out
    }
}
