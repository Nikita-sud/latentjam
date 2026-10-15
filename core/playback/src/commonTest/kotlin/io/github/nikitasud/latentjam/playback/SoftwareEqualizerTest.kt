/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class SoftwareEqualizerTest {
    private fun amplitude(settings: SoftwareEqualizerSettings, frequency: Double, sampleRate: Int = 48000): Double {
        val processor = SoftwareEqualizer(sampleRate, 2, settings)
        val frame = FloatArray(2)
        var energy = 0.0
        repeat(sampleRate / 2) { index ->
            frame[0] = (0.1 * sin(2 * PI * frequency * index / sampleRate)).toFloat()
            frame[1] = 0f
            processor.process(frame)
            assertEquals(0f, frame[1], "Channels must not leak into one another")
            if (index >= sampleRate / 4) energy += frame[0] * frame[0]
        }
        return sqrt(energy / (sampleRate / 4))
    }

    @Test fun disabledAndFlatCurvesLeaveSamplesUnchanged() {
        for (enabled in listOf(false, true)) {
            val dsp = SoftwareEqualizer(48000, 2, SoftwareEqualizerSettings(enabled))
            val random = Random(42)
            val input = List(2000) { floatArrayOf(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1) }
            repeat(input.size + dsp.latencyFrames) { index ->
                val frame = input.getOrNull(index)?.copyOf() ?: FloatArray(2)
                dsp.process(frame)
                if (index >= dsp.latencyFrames) {
                    assertTrue(input[index - dsp.latencyFrames].contentEquals(frame))
                }
            }
        }
    }

    @Test fun aSingleBandChangesRelativeToneByTheRequestedDecibels() {
        for (gain in listOf(-1500, -1200, -600, 600, 1200, 1500)) {
            val curve = SoftwareEqualizerSettings(true, List(10) { if (it == 5) gain else 0 })
            val ratio = 20 * log10(amplitude(curve, 1000.0) / amplitude(curve, 100.0))
            assertTrue(abs(ratio - gain / 100.0) < 0.35, "gain=$gain, measured=$ratio dB")
        }
    }

    @Test fun bassBoostIsAnAudibleLowShelf() {
        val settings = SoftwareEqualizerSettings(enabled = true, bass = 1000)
        val difference = 20 * log10(amplitude(settings, 30.0) / amplitude(settings, 2000.0))
        assertTrue(difference in 8.0..9.3, "Measured shelf=$difference dB")
    }

    @Test fun everyNonFlatPresetChangesItsIntendedFrequencyRange() {
        val bass = SoftwareEqualizerSettings().withPreset(1)
        assertTrue(amplitude(bass, 62.0) > amplitude(bass, 2000.0) * 1.7)
        val treble = SoftwareEqualizerSettings().withPreset(2)
        assertTrue(amplitude(treble, 8000.0) > amplitude(treble, 125.0) * 1.7)
        val vocal = SoftwareEqualizerSettings().withPreset(3)
        assertTrue(amplitude(vocal, 1000.0) > amplitude(vocal, 62.0) * 1.7)
        val electronic = SoftwareEqualizerSettings().withPreset(4)
        assertTrue(amplitude(electronic, 62.0) > amplitude(electronic, 500.0) * 1.4)
    }

    @Test fun maximumBassRaisesBassWithoutCollapsingTheUnboostedMidrange() {
        val flat = SoftwareEqualizerSettings(enabled = true)
        val boost = flat.copy(bass = 1000)
        val stacked = flat.withPreset(1).copy(bass = 1000)
        for (settings in listOf(boost, stacked)) {
            val midDb = 20 * log10(amplitude(settings, 1000.0) / amplitude(flat, 1000.0))
            val bassDb = 20 * log10(amplitude(settings, 40.0) / amplitude(flat, 40.0))
            assertTrue(midDb > -2.1, "The headroom policy must not turn quiet midrange down by 9–17 dB: $midDb")
            assertTrue(bassDb > 6.5, "Bass must increase in absolute level on an unclipped signal: $bassDb")
        }
    }

    @Test fun aBandAboveTheInputNyquistDoesNotTurnDownTheAudio() {
        val flat = SoftwareEqualizerSettings(enabled = true)
        val unavailableBand = flat.copy(levels = List(10) { if (it == 9) 1200 else 0 })
        assertEquals(amplitude(flat, 1000.0, 8000), amplitude(unavailableBand, 1000.0, 8000))
    }

    @Test fun selectingAPresetEnablesItAndClearsThePreviousBassBoost() {
        val preset = SoftwareEqualizerSettings(bass = 1000).withPreset(3)
        assertTrue(preset.enabled)
        assertEquals(0, preset.bass)
        assertEquals(3, preset.preset)
        assertEquals(preset, preset.withPreset(-1))
    }

    @Test fun liveEditsAndBypassTakeEffectWithoutRecreatingTheProcessor() {
        val settings = SoftwareEqualizerSettings(true, List(10) { if (it == 5) -1200 else 0 })
        val dsp = SoftwareEqualizer(48000, 1, SoftwareEqualizerSettings())
        val frame = FloatArray(1)
        dsp.update(settings)
        var last = 0f
        repeat(4800) { i ->
            frame[0] = (0.1 * sin(2 * PI * 1000 * i / 48000)).toFloat()
            dsp.process(frame)
            assertTrue(abs(frame[0] - last) < 0.03f, "An edit must not produce a discontinuity")
            last = frame[0]
        }
        dsp.update(SoftwareEqualizerSettings())
        repeat(1600) { frame[0] = 0.1f; dsp.process(frame) }
        assertEquals(0.1f, frame[0])
    }

    @Test fun extremeCurvesStayFiniteAndBoundedAcrossSampleRatesAndChannelLayouts() {
        for (rate in listOf(8000, 16000, 44100, 48000, 96000, 192000)) {
            for (channels in listOf(1, 2, 6)) {
                val dsp = SoftwareEqualizer(rate, channels, SoftwareEqualizerSettings(true, List(10) { 1200 }, 1000))
                val frame = FloatArray(channels)
                val random = Random(3)
                repeat(4000) { index ->
                    if (index == 2000) dsp.update(SoftwareEqualizerSettings(true, List(10) { -1200 }))
                    for (c in frame.indices) frame[c] = if (index < 40) 1f else random.nextFloat() * 2 - 1
                    dsp.process(frame)
                    assertTrue(frame.all { it.isFinite() && abs(it) <= 1f })
                }
            }
        }
    }
}
