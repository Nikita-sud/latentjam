/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class LoudnessTest {

    private fun tone(amplitude: Float, samples: Int = 16_000) = FloatArray(samples) { amplitude }

    @Test
    fun aConstantToneMeasuresItsExactPower() {
        // amplitude 0.5 -> mean square 0.25 -> 10*log10(0.25) ~ -6.02 dB
        val db = Loudness.measureDb(listOf(Loudness.Window(tone(0.5f))))!!
        assertTrue(abs(db - (-6.02f)) < 0.05f, "got $db")
    }

    @Test
    fun trailingDecoderPaddingDoesNotDiluteTheMeasurement() {
        val padded = FloatArray(32_000) { if (it < 16_000) 0.5f else 0f }
        val db = Loudness.measureDb(listOf(Loudness.Window(padded, validSamples = 16_000)))!!
        assertTrue(abs(db - (-6.02f)) < 0.05f, "got $db")
    }

    @Test
    fun genuineTrailingSilenceContributesToTheMeasuredWindow() {
        val toneThenSilence = FloatArray(80_000) { if (it < 16_000) 0.5f else 0f }

        val db = Loudness.measureDb(listOf(Loudness.Window(toneThenSilence)))!!

        // 20% of the window has power 0.25: mean square 0.05 -> -13.01 dBFS.
        assertTrue(abs(db - (-13.01f)) < 0.05f, "got $db")
    }

    @Test
    fun silenceAndTinyWindowsAreNotMeasurements() {
        assertNull(Loudness.measureDb(emptyList()))
        assertNull(Loudness.measureDb(listOf(Loudness.Window(FloatArray(32_000)))))
        assertNull(Loudness.measureDb(listOf(Loudness.Window(tone(0.5f, samples = 100)))))
    }

    @Test
    fun loudTracksAttenuateAndQuietTracksStayUntouched() {
        // 6 dB above target -> half amplitude.
        val attenuated = Loudness.normalizationVolume(loudnessDb = -8f, targetDb = -14f)
        assertTrue(abs(attenuated - 0.5f) < 0.01f, "got $attenuated")
        assertEquals(1f, Loudness.normalizationVolume(loudnessDb = -20f, targetDb = -14f))
        assertEquals(1f, Loudness.normalizationVolume(loudnessDb = -14f, targetDb = -14f))
    }

    @Test
    fun corruptMeasurementsCannotMuteOrBlastPlayback() {
        assertEquals(1f, Loudness.normalizationVolume(Float.NaN))
        assertEquals(Loudness.MIN_VOLUME, Loudness.normalizationVolume(loudnessDb = 60f))
    }
}
