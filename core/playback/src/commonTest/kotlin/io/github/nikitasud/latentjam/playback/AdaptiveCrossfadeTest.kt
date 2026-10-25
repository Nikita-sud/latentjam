/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TransitionFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveCrossfadeTest {

    @Test
    fun aTrackThatBelongsGetsTheWholeSlider() {
        assertEquals(8_000L, fittedCrossfadeMs(8_000, TransitionFit(closeness = 0.98f, energyGap = 0f)))
        assertEquals(8_000L, fittedCrossfadeMs(8_000, TransitionFit(closeness = 0.95f, energyGap = null)))
    }

    @Test
    fun aChangeOfSoundGetsTheShortestBlend() {
        assertEquals(2_400L, fittedCrossfadeMs(8_000, TransitionFit(closeness = 0.3f, energyGap = 0f)))
        // Halfway between a typical track and the closest: halfway between the shortest and the slider.
        val mid = fittedCrossfadeMs(8_000, TransitionFit(closeness = 0.725f, energyGap = null))
        assertEquals(5_200L, mid)
    }

    @Test
    fun aChangeOfPaceShortensEvenACloseTrack() {
        val calm = fittedCrossfadeMs(10_000, TransitionFit(closeness = 0.97f, energyGap = 0f))
        val loud = fittedCrossfadeMs(10_000, TransitionFit(closeness = 0.97f, energyGap = 1f))
        assertEquals(10_000L, calm)
        assertEquals(6_500L, loud)
    }

    @Test
    fun anUnknownFitOrAnOffSliderKeepsTheSlider() {
        assertEquals(6_000L, fittedCrossfadeMs(6_000, null))
        assertEquals(0L, fittedCrossfadeMs(0, TransitionFit(closeness = 0.1f, energyGap = 1f)))
    }

    @Test
    fun aShortSliderIsNeverCutBelowASecond() {
        val fitted = fittedCrossfadeMs(2_000, TransitionFit(closeness = 0f, energyGap = 1f))
        assertEquals(1_000L, fitted)
        assertTrue(fittedCrossfadeMs(800, TransitionFit(closeness = 0f, energyGap = 1f)) == 800L)
    }
}
