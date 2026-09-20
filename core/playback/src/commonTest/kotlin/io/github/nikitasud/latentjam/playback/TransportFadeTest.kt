/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun resumingDuringPauseStartsAtTheAudibleGain() {
        val fade = TransportFadeState()
        fade.beginPause(1_000L)
        val audibleGain = fade.gainAt(1_080L)
        assertEquals(0.7071f, audibleGain, absoluteTolerance = 0.001f)

        fade.beginResume(1_080L)

        assertFalse(fade.pausePending)
        assertEquals(audibleGain, fade.gainAt(1_080L))
        assertTrue(fade.gainAt(1_100L) > audibleGain)
        assertEquals(1f, fade.gainAt(1_300L))
        assertFalse(fade.active)
    }

    @Test
    fun pausingDuringResumeDoesNotJumpBackToFullVolume() {
        val fade = TransportFadeState()
        fade.beginResume(0L)
        val audibleGain = fade.gainAt(55L)

        fade.beginPause(55L)

        assertTrue(fade.pausePending)
        assertEquals(audibleGain, fade.gainAt(55L))
        assertTrue(fade.gainAt(75L) < audibleGain)
        assertEquals(0f, fade.gainAt(215L))
    }

    @Test
    fun pauseIntentSurvivesSilenceUntilTheBackendActuallyPauses() {
        val fade = TransportFadeState()
        fade.beginPause(0L)
        assertEquals(0f, fade.gainAt(200L))
        assertFalse(fade.active)
        assertTrue(fade.pausePending)
        assertEquals(0f, fade.gainAt(500L))

        // A second tap still means resume, even if the gain loop beat the delayed pause job.
        fade.beginResume(500L)
        assertEquals(0f, fade.gainAt(500L))
        assertFalse(fade.pausePending)
        assertEquals(1f, fade.gainAt(720L))
    }

    @Test
    fun replacingOrStoppingPlaybackClearsTheOldEnvelopeAndRestoresGain() {
        val fade = TransportFadeState()
        fade.beginPause(0L)
        fade.gainAt(80L)

        fade.reset()

        assertFalse(fade.active)
        assertFalse(fade.pausePending)
        assertEquals(1f, fade.gainAt(80L))
        assertEquals(1f, fade.gainAt(5_000L))
        // An in-app resume after a real pause starts from silence rather than the old midpoint.
        fade.beginResume(5_000L)
        assertEquals(0f, fade.gainAt(5_000L))
        assertEquals(1f, fade.gainAt(5_220L))
    }

    @Test
    fun resumeWaitsForBufferedAudioBeforeStartingItsRamp() {
        val fade = TransportFadeState()
        fade.beginResume(0L, startImmediately = false)
        assertEquals(0f, fade.gainAt(5_000L))
        assertTrue(fade.active)

        fade.onPlaybackStarted(5_000L)

        assertEquals(0f, fade.gainAt(5_000L))
        assertEquals(0.7071f, fade.gainAt(5_110L), absoluteTolerance = 0.001f)
        assertEquals(1f, fade.gainAt(5_220L))
        assertFalse(fade.active)
    }
}
