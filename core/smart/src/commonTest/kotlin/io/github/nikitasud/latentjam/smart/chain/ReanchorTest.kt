/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the tail-exhaustion re-anchor's pure pieces: the fused-cosine trigger metric (equal-weight
 * audio+descriptor, audio-only when a descriptor is missing), the exhaustion predicate, and the
 * effective-seed blend that drifts the seed-anchored terms toward the chain centroid once the niche
 * is spent. Full-chain behavior (byte-identity of healthy seeds, tail drift of niche seeds) is
 * validated by the parity fixture; these tests pin the math the chain and the offline harness share.
 */
class ReanchorTest {

    // 1. Fused cosine: equal-weight blend of the two centered-space cosines; audio-only fallback
    //    when either side lacks a descriptor (descCos == null).
    @Test
    fun fusedCos_blendAndAudioFallback() {
        assertEquals(0.6f, Reanchor.fusedCos(0.6f, null), 0f)
        assertEquals(0.4f, Reanchor.fusedCos(0.6f, 0.2f), 1e-6f)
        assertEquals(-0.1f, Reanchor.fusedCos(0.3f, -0.5f), 1e-6f)
    }

    // 2. Exhaustion predicate: the niche is spent below the min in-niche count (validated at 3).
    @Test
    fun reanchorExhausted_belowMinTriggers() {
        assertTrue(Reanchor.reanchorExhausted(0))
        assertTrue(Reanchor.reanchorExhausted(2))
        assertFalse(Reanchor.reanchorExhausted(3))
        assertFalse(Reanchor.reanchorExhausted(9))
    }

    // 3. Effective-seed blend: keep·seed + (1−keep)·centroid, L2-normalized. Orthogonal seed and
    //    centroid → the direction tilts toward the centroid (0.6 weight) and stays unit-norm.
    @Test
    fun reanchorBlend_normalizedTowardCentroid() {
        val seed = floatArrayOf(1f, 0f)
        val centroid = floatArrayOf(0f, 1f)
        val eff = Reanchor.reanchorBlend(seed, centroid, 0.4f)
        val norm = sqrt(0.16f + 0.36f)
        assertEquals(0.4f / norm, eff[0], 1e-5f)
        assertEquals(0.6f / norm, eff[1], 1e-5f)
        assertEquals(1f, sqrt(eff[0] * eff[0] + eff[1] * eff[1]), 1e-5f)
        // Centroid dominates (0.6 > 0.4), so the blend leans centroid-ward.
        assertTrue(eff[1] > eff[0])

        // Seed == centroid → the blend is exactly the seed (no drift, unit-norm).
        val same = Reanchor.reanchorBlend(seed, seed.copyOf(), 0.4f)
        assertEquals(1f, same[0], 1e-6f)
        assertEquals(0f, same[1], 1e-6f)
    }
}
