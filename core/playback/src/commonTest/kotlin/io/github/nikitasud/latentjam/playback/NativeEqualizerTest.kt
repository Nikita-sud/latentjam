/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class NativeEqualizerTest {
    @Test fun signalsmithMatchesThePreviousProcessorAcrossRatesAndLayouts() {
        for (rate in listOf(8000, 44100, 48000, 96000, 192000)) for (channels in listOf(1, 2, 6)) {
            for (settings in listOf(SoftwareEqualizerSettings(), SoftwareEqualizerSettings().withPreset(3),
                SoftwareEqualizerSettings(true, List(10) { if (it % 2 == 0) 1200 else -1200 }, 1000))) {
                val native = NativeEqualizer(rate, channels, settings)
                val reference = SoftwareEqualizer(rate, channels, settings)
                try {
                    assertEquals(reference.latencyFrames, native.latencyFrames)
                    val random = Random(14)
                    val frame = FloatArray(channels)
                    repeat(8) {
                        val input = FloatArray(512 * channels) { (random.nextFloat() - 0.5f) * 0.6f }
                        val expected = input.copyOf()
                        for (i in 0 until 512) {
                            for (c in 0 until channels) frame[c] = expected[i * channels + c]
                            reference.process(frame)
                            for (c in 0 until channels) expected[i * channels + c] = frame[c]
                        }
                        native.process(input, 512)
                        val error = input.indices.maxOf { abs(input[it] - expected[it]) }
                        assertTrue(error < 0.00002f, "$rate Hz, $channels channels: max error=$error")
                    }
                } finally { native.close() }
            }
        }
    }

    @Test fun liveSettingsAndBypassRemainSmoothAndMatchTheReference() {
        val native = NativeEqualizer(48000, 2, SoftwareEqualizerSettings())
        val reference = SoftwareEqualizer(48000, 2, SoftwareEqualizerSettings())
        val frame = FloatArray(2)
        val random = Random(91)
        var settings = SoftwareEqualizerSettings()
        try {
            repeat(40) { block ->
                if (block == 3) settings = settings.withPreset(1).copy(bass = 1000)
                if (block == 6) settings = settings.withPreset(3)
                if (block == 24) settings = settings.copy(enabled = false)
                native.update(settings); reference.update(settings)
                val input = FloatArray(128 * 2) { (random.nextFloat() - 0.5f) * 0.3f }
                val expected = input.copyOf()
                for (i in 0 until 128) {
                    frame[0] = expected[i * 2]; frame[1] = expected[i * 2 + 1]
                    reference.process(frame)
                    expected[i * 2] = frame[0]; expected[i * 2 + 1] = frame[1]
                }
                native.process(input, 128)
                assertTrue(input.indices.all { abs(input[it] - expected[it]) < 0.00002f }, "Block $block")
            }
        } finally { native.close() }
    }

    @Test fun aNativeStreamCanBeClosedRepeatedlyWithoutLeakingOrReusingItsHandle() {
        repeat(20) {
            val native = NativeEqualizer(48000, 2, SoftwareEqualizerSettings())
            native.close(); native.close()
        }
    }
}
