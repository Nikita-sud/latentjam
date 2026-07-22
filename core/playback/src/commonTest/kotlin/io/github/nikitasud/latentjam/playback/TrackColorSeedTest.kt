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
    fun `HSL primaries convert to opaque ARGB`() {
        assertEquals(0xFFFF0000.toInt(), TrackColorSeed(0f, 1f).toArgb())
        assertEquals(0xFF00FF00.toInt(), TrackColorSeed(120f, 1f).toArgb())
        assertEquals(0xFF0000FF.toInt(), TrackColorSeed(240f, 1f).toArgb())
    }
}
