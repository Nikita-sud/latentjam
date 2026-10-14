/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.*
import kotlin.test.*

internal class IntegratedLoudnessTest {
    @Test fun calibratedSineAndChannelPowerAgreeOnBothPlatforms() {
        val mono = tone(1)
        val stereo = tone(2)
        val inverted = tone(2, invertRight = true)
        assertEquals(-23f, mono.lufs, 0.15f)
        assertEquals(-20f, stereo.lufs, 0.15f)
        assertEquals(stereo.lufs, inverted.lufs, 0.001f)
        assertEquals(-20f, stereo.truePeakDbtp, 0.15f)
    }

    @Test fun commonInputRatesKeepCalibratedLoudnessWithoutResampling() {
        for (rate in listOf(8000, 11025, 22050, 44100, 48000, 96000, 192000)) {
            val result = tone(2, rate = rate)
            assertEquals(-20f, result.lufs, 0.15f, "$rate Hz")
            assertEquals(-20f, result.truePeakDbtp, 0.15f, "$rate Hz")
        }
    }

    @Test fun blockBoundariesDoNotChangeTheMeasurement() {
        val whole = tone(2, chunk = 4096)
        val odd = tone(2, chunk = 137)
        assertEquals(whole.lufs, odd.lufs, 0.001f)
        assertEquals(whole.truePeakDbtp, odd.truePeakDbtp, 0.001f)
    }

    @Test fun absoluteGateDoesNotCountDigitalSilenceAsQuietMusic() {
        val meter = NativeLoudnessMeter(48000, 2)
        try {
            repeat(20) { assertTrue(meter.add(FloatArray(8192), 4096)) }
            assertNull(meter.finish())
        } finally { meter.close() }
    }

    @Test fun truePeakSeesPeaksBetweenSamples() {
        val measurement = tone(1, frequency = 12000.0, amplitude = 0.9, phase = PI / 4)
        val samplePeakDb = 20 * log10(0.9 / sqrt(2.0))
        assertTrue(measurement.truePeakDbtp > samplePeakDb + 2.5)
        assertEquals((20 * log10(0.9)).toFloat(), measurement.truePeakDbtp, 0.25f)
    }

    @Test fun invalidPcmShortFilesAndUnsupportedLayoutsCannotProduceCacheEntries() {
        for ((rate, channels) in listOf(0 to 2, 48000 to 6, 8000 to 1)) {
            val meter = NativeLoudnessMeter(rate, channels)
            try { assertNull(meter.finish()) } finally { meter.close(); meter.close() }
        }
        val meter = NativeLoudnessMeter(48000, 2)
        try {
            assertFalse(meter.add(floatArrayOf(Float.NaN, 0f), 1))
            assertNull(meter.finish())
        } finally { meter.close() }
    }

    @Test fun gainNeverBoostsAndRespectsSourceTruePeakHeadroom() {
        assertEquals(1f, IntegratedLoudness(-23f, -6f).normalizationVolume())
        assertEquals(10f.pow(-6f / 20), IntegratedLoudness(-8f, -2f).normalizationVolume(), 0.00001f)
        assertEquals(10f.pow(-3f / 20), IntegratedLoudness(-23f, 2f).normalizationVolume(), 0.00001f)
        assertEquals(1f, IntegratedLoudness(Float.NaN, -2f).normalizationVolume())
    }

    @Test fun completeScanGuardRejectsTruncationAndLongOrEmptyInputs() {
        assertTrue(LoudnessScanLimits.complete(48000L * 180, 48000, 180000))
        assertTrue(LoudnessScanLimits.complete(48000L * 180, 48000, null))
        assertFalse(LoudnessScanLimits.complete(48000L * 10, 48000, 180000))
        assertFalse(LoudnessScanLimits.complete(100, 48000, null))
        assertFalse(LoudnessScanLimits.complete(Long.MAX_VALUE, 48000, null))
        assertFalse(LoudnessScanLimits.complete(48000L * 3600 * 7, 48000, null))
    }

    private fun tone(channels: Int, chunk: Int = 4096, invertRight: Boolean = false,
        frequency: Double = 1000.0, amplitude: Double = 0.1, phase: Double = 0.0, rate: Int = 48000): IntegratedLoudness {
        val meter = NativeLoudnessMeter(rate, channels)
        try {
            var position = 0
            val samples = FloatArray(chunk * channels)
            while (position < rate * 3) {
                val frames = minOf(chunk, rate * 3 - position)
                repeat(frames) { frame ->
                    val sample = (amplitude * sin(2 * PI * frequency * (position + frame) / rate + phase)).toFloat()
                    repeat(channels) { c -> samples[frame * channels + c] = if (c == 1 && invertRight) -sample else sample }
                }
                assertTrue(meter.add(samples, frames))
                position += frames
            }
            return assertNotNull(meter.finish()).also { assertNull(meter.finish()) }
        } finally { meter.close() }
    }
}
