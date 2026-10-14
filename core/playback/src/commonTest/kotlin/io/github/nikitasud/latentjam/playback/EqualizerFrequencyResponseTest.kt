/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class EqualizerFrequencyResponseTest {
    @Test fun unknownNativeResponseIsNotPresentedAsAnExactCurve() {
        assertNull(EqualizerState(available = true).frequencyResponse())
    }

    @Test fun bypassIsFlatEvenWhenTheSavedCurveAndBassAreBoosted() {
        val settings = SoftwareEqualizerSettings(false, List(10) { 1200 }, 1000)
        val response = settings.asState().frequencyResponse()!!
        assertEquals(0f, response.headroomDb)
        assertTrue(response.points.all { it.gainDb == 0f })
    }

    @Test fun responseIncludesTheBassShelfAndStaticHeadroom() {
        val settings = SoftwareEqualizerSettings(enabled = true, bass = 1000)
        val response = settings.asState().frequencyResponse()!!
        assertEquals(2f, response.headroomDb)
        assertTrue(response.points.first().gainDb in 6.9f..7.1f)
        assertTrue(response.points.last().gainDb in -2.01f..-1.99f)
        val cut = settings.copy(levels = List(10) { -600 }, bass = 0).asState().frequencyResponse()!!
        assertEquals(0f, cut.headroomDb)
    }

    @Test fun samplingIsLogarithmicAndStopsBelowNyquist() {
        for (rate in listOf(8000, 16000, 44100, 48000, 192000)) {
            val response = SoftwareEqualizerSettings(true, List(10) { 1200 }, 1000).asState().frequencyResponse(rate)!!
            assertEquals(rate, response.sampleRateHz)
            assertEquals(121, response.points.size)
            assertTrue(response.points.all { it.gainDb.isFinite() && it.frequencyHz < rate / 2f })
            val ratios = response.points.zipWithNext { a, b -> b.frequencyHz / a.frequencyHz }
            assertTrue(ratios.all { abs(it - ratios.first()) < 0.00001f })
        }
    }

    @Test fun unsupportedBandsDoNotAffectTheCurveOrHeadroom() {
        val flat = SoftwareEqualizerSettings(enabled = true)
        val high = flat.copy(levels = List(10) { if (it >= 7) 1200 else 0 })
        assertEquals(flat.asState().frequencyResponse(8000), high.asState().frequencyResponse(8000))
    }

    @Test fun analyticResponseMatchesActualProcessedQuietPcmAcrossSampleRates() {
        val settings = SoftwareEqualizerSettings(true, listOf(700, 600, 450, 250, -150, 500, -350, 450, 600, 700), 730)
        for (rate in listOf(44100, 48000)) {
            val design = EqualizerFilterDesign(rate, settings)
            for (frequency in listOf(31.0, 62.0, 500.0, 1000.0, 8000.0, 16000.0)) {
                val dsp = SoftwareEqualizer(rate, 1, settings)
                val frame = FloatArray(1)
                var real = 0.0; var imaginary = 0.0
                val count = rate / 2
                repeat(rate) { index ->
                    frame[0] = (0.001 * sin(2 * PI * frequency * index / rate)).toFloat()
                    dsp.process(frame)
                    if (index >= rate - count) {
                        val phase = 2 * PI * frequency * (index - dsp.latencyFrames) / rate
                        real += frame[0] * cos(phase)
                        imaginary += frame[0] * sin(phase)
                    }
                }
                val amplitude = 2 * sqrt(real * real + imaginary * imaginary) / count
                val measured = 20 * log10(amplitude / 0.001)
                assertTrue(abs(measured - design.responseDb(frequency)) < 0.015,
                    "$rate Hz, $frequency Hz: response=${design.responseDb(frequency)}, PCM=$measured")
                assertEquals(1f, dsp.limiterGain)
            }
        }
    }
}
