/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals

internal class CrossfadeTest {

    @Test
    fun fadesRiseAtTheStartAndFallAtTheEnd() {
        val duration = 180_000L
        val fade = 4_000L
        assertEquals(0f, crossfadeFactor(0, duration, fade))
        assertEquals(0.5f, crossfadeFactor(2_000, duration, fade))
        assertEquals(1f, crossfadeFactor(4_000, duration, fade))
        assertEquals(1f, crossfadeFactor(90_000, duration, fade))
        assertEquals(0.5f, crossfadeFactor(178_000, duration, fade))
        assertEquals(0f, crossfadeFactor(180_000, duration, fade))
    }

    @Test
    fun zeroFadeMeansFullVolumeEverywhere() {
        assertEquals(1f, crossfadeFactor(0, 180_000, 0))
        assertEquals(1f, crossfadeFactor(179_999, 180_000, 0))
    }

    @Test
    fun aShortTrackStillGetsBothSlopes() {
        // 6 s track with a 12 s fade: each slope shrinks to 3 s.
        assertEquals(0f, crossfadeFactor(0, 6_000, 12_000))
        assertEquals(1f, crossfadeFactor(3_000, 6_000, 12_000))
        assertEquals(0f, crossfadeFactor(6_000, 6_000, 12_000))
    }

    @Test
    fun unknownDurationFadesInButNeverMutesMidPlay() {
        assertEquals(0.5f, crossfadeFactor(2_000, 0, 4_000))
        assertEquals(1f, crossfadeFactor(3_600_000, 0, 4_000))
    }

    @Test
    fun sanitizerClampsToTheSliderRange() {
        assertEquals(0, sanitizeCrossfadeSeconds(-3))
        assertEquals(7, sanitizeCrossfadeSeconds(7))
        assertEquals(MAX_CROSSFADE_SECONDS, sanitizeCrossfadeSeconds(99))
    }

    @Test
    fun schedulerSleepsThroughConstantGainButTicksInsideBothFades() {
        val duration = 180_000L
        val fade = 4_000L

        assertEquals(GAIN_RAMP_TICK_MS, crossfadeUpdateDelayMs(2_000, duration, fade))
        assertEquals(86_000L, crossfadeUpdateDelayMs(90_000, duration, fade))
        assertEquals(GAIN_RAMP_TICK_MS, crossfadeUpdateDelayMs(178_000, duration, fade))
        assertEquals(500L, crossfadeUpdateDelayMs(8_000, durationMs = 0, fadeMs = fade))
    }

    @Test
    fun normalizationGainMovesSmoothlyInDbAndEndsExactlyAtTarget() {
        assertEquals(1f, normalizationRampVolume(1f, 0.25f, elapsedMs = 0))
        assertEquals(0.5f, normalizationRampVolume(1f, 0.25f, elapsedMs = 200))
        assertEquals(0.25f, normalizationRampVolume(1f, 0.25f, elapsedMs = 400))
        assertEquals(1f, normalizationRampVolume(0.25f, 1f, elapsedMs = 400))
    }
}
