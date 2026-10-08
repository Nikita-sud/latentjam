/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrackColorSeedTest {

    @Test
    fun `identity colour is stable and track-specific`() {
        assertEquals(identityTrackColorSeed("track-a"), identityTrackColorSeed("track-a"))
        assertNotEquals(identityTrackColorSeed("track-a"), identityTrackColorSeed("track-b"))
    }

    @Test
    fun `latent colour is stable and bounded`() {
        val embedding = floatArrayOf(1f, -0.5f, 0.25f, 0.75f, -1f, 0.4f)
        val seed = latentTrackColorSeed(embedding)
        assertEquals(seed, latentTrackColorSeed(embedding))
        assertTrue(seed.hueDegrees in 0f..360f)
        assertTrue(seed.saturation in 0.35f..0.8f)
    }

    @Test
    fun `a non-finite embedding falls back to the neutral seed`() {
        val embedding = floatArrayOf(1f, -0.5f, 0.25f, 0.75f, -1f, 0.4f)
        val neutral = TrackColorSeed(hueDegrees = 0f, saturation = 0f)
        // A non-finite value anywhere poisons its whole slice sum, so this covers every
        // component of the vector — and the call must return, not throw.
        for (index in embedding.indices) {
            val poisoned = embedding.copyOf()
            poisoned[index] = Float.NaN
            assertEquals(neutral, latentTrackColorSeed(poisoned), "NaN at $index")
            poisoned[index] = Float.POSITIVE_INFINITY
            assertEquals(neutral, latentTrackColorSeed(poisoned), "+infinity at $index")
            poisoned[index] = Float.NEGATIVE_INFINITY
            assertEquals(neutral, latentTrackColorSeed(poisoned), "-infinity at $index")
        }
    }

    @Test
    fun `a finite embedding keeps the colour it had before the guard`() {
        val embedding = floatArrayOf(1f, -0.5f, 0.25f, 0.75f, -1f, 0.4f)
        val seed = latentTrackColorSeed(embedding)
        // Slice sums are x = 0.5, y = 1.0 and z = -0.6, hence the hue atan2(1, 0.5) and the
        // saturation 0.45 + 0.3 * 0.6 / 2.1. The non-finite guard must not move them.
        assertEquals(63.43495f, seed.hueDegrees, absoluteTolerance = 1e-3f)
        assertEquals(0.5357143f, seed.saturation, absoluteTolerance = 1e-3f)
    }

    @Test
    fun `non-finite HSL parts convert to the neutral colour instead of throwing`() {
        val neutral = TrackColorSeed(hueDegrees = 0f, saturation = 0f).toArgb()
        assertEquals(0xFF808080.toInt(), neutral)
        // Each part falls back on its own: a NaN hue can arrive beside a finite saturation,
        // lightness is a caller-supplied argument, and roundToInt() throws on any of them.
        assertEquals(neutral, TrackColorSeed(Float.NaN, Float.NaN).toArgb())
        assertEquals(
            neutral,
            TrackColorSeed(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).toArgb(),
        )
        assertEquals(neutral, TrackColorSeed(Float.NaN, 0f).toArgb(lightness = Float.NaN))
        assertEquals(
            neutral,
            TrackColorSeed(0f, Float.NaN).toArgb(lightness = Float.NEGATIVE_INFINITY),
        )
        assertEquals(
            neutral,
            TrackColorSeed(Float.NaN, Float.NaN).toArgb(lightness = Float.POSITIVE_INFINITY),
        )
    }

    @Test
    fun `HSL primaries convert to opaque ARGB`() {
        assertEquals(0xFFFF0000.toInt(), TrackColorSeed(0f, 1f).toArgb())
        assertEquals(0xFF00FF00.toInt(), TrackColorSeed(120f, 1f).toArgb())
        assertEquals(0xFF0000FF.toInt(), TrackColorSeed(240f, 1f).toArgb())
    }
}
