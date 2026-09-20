/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransportFadeTest {
    @Test
    fun fadeOutLeavesFromFullAndArrivesAtSilence() {
        assertEquals(1f, transportFadeFactor(elapsedMs = 0L, durationMs = 160L, fadingOut = true))
        assertEquals(0f, transportFadeFactor(elapsedMs = 160L, durationMs = 160L, fadingOut = true))
        assertEquals(0f, transportFadeFactor(elapsedMs = 5_000L, durationMs = 160L, fadingOut = true))
    }

    @Test
    fun fadeInLeavesFromSilenceAndArrivesAtFull() {
        assertEquals(0f, transportFadeFactor(elapsedMs = 0L, durationMs = 220L, fadingOut = false))
        assertEquals(1f, transportFadeFactor(elapsedMs = 220L, durationMs = 220L, fadingOut = false))
        assertEquals(1f, transportFadeFactor(elapsedMs = 999L, durationMs = 220L, fadingOut = false))
    }

    @Test
    fun theMiddleIsEqualPowerNotHalfGain() {
        val out = transportFadeFactor(elapsedMs = 80L, durationMs = 160L, fadingOut = true)
        val into = transportFadeFactor(elapsedMs = 110L, durationMs = 220L, fadingOut = false)
        assertEquals(0.7071f, out, absoluteTolerance = 0.001f)
        assertEquals(0.7071f, into, absoluteTolerance = 0.001f)
        assertEquals(1f, out * out + into * into, absoluteTolerance = 0.001f)
    }

    @Test
    fun aZeroDurationIsInstantAndNegativeTimeIsTheStart() {
        assertEquals(0f, transportFadeFactor(elapsedMs = 0L, durationMs = 0L, fadingOut = true))
        assertEquals(1f, transportFadeFactor(elapsedMs = 0L, durationMs = 0L, fadingOut = false))
        assertEquals(1f, transportFadeFactor(elapsedMs = -50L, durationMs = 160L, fadingOut = true))
        assertTrue(transportFadeFactor(elapsedMs = 159L, durationMs = 160L, fadingOut = true) > 0f)
    }
}
