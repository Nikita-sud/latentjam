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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmResamplerTest {
    private fun tone(rate: Int, hz: Double, frames: Int, amplitude: Double = 0.5, channels: Int = 2) =
        ShortArray(frames * channels) { i ->
            val frame = i / channels
            (amplitude * 32767 * sin(2 * PI * hz * frame / rate) * if (i % channels == 1) -1 else 1).toInt().toShort()
        }

    /** Feeds [input] in chunks of [chunk] frames, pulling as it goes, then ends; all output. */
    private fun resample(input: ShortArray, from: Int, to: Int, chunk: Int, channels: Int = 2): ShortArray {
        val resampler = PcmResampler(from, to, channels)
        val out = ArrayList<Short>()
        val buffer = ShortArray(8192 * channels)
        fun drain() {
            while (true) {
                val got = resampler.pull(buffer, 8192)
                if (got == 0) return
                for (i in 0 until got * channels) out += buffer[i]
            }
        }
        var frame = 0
        val frames = input.size / channels
        while (frame < frames) {
            val count = minOf(chunk, frames - frame)
            resampler.push(input, frame * channels, count)
            drain()
            frame += count
        }
        resampler.end()
        drain()
        return out.toShortArray()
    }

    private fun errorDb(actual: ShortArray, expected: ShortArray, skip: Int): Double {
        var error = 0.0
        var signal = 0.0
        for (i in skip until minOf(actual.size, expected.size) - skip) {
            val e = expected[i].toDouble()
            error += (actual[i] - e) * (actual[i] - e)
            signal += e * e
        }
        return 10 * log10(error / signal)
    }

    @Test
    fun theOutputDoesNotDependOnHowTheInputIsCut() {
        val input = tone(44_100, 997.0, 20_000)
        val whole = resample(input, 44_100, 48_000, chunk = 20_000)
        assertContentEquals(whole, resample(input, 44_100, 48_000, chunk = 1_152))
        assertContentEquals(whole, resample(input, 44_100, 48_000, chunk = 37))
    }

    @Test
    fun theOutputLastsAsLongAsTheInput() {
        assertEquals(2 * 21_769, resample(tone(44_100, 440.0, 20_000), 44_100, 48_000, chunk = 4096).size)
        assertEquals(2 * 10_000, resample(tone(96_000, 440.0, 20_000), 96_000, 48_000, chunk = 4096).size)
    }

    @Test
    fun aToneArrivesCleanAtTheNewRate() {
        val up = resample(tone(44_100, 1_000.0, 44_100), 44_100, 48_000, chunk = 1_024)
        assertTrue(errorDb(up, tone(48_000, 1_000.0, 48_000), skip = 400) < -70.0, "44.1 → 48 kHz")
        val down = resample(tone(48_000, 15_000.0, 48_000), 48_000, 44_100, chunk = 960)
        assertTrue(errorDb(down, tone(44_100, 15_000.0, 44_100), skip = 400) < -60.0, "48 → 44.1 kHz")
    }

    @Test
    fun aToneAboveTheNewNyquistIsRemovedNotFoldedBack() {
        val down = resample(tone(96_000, 30_000.0, 48_000), 96_000, 48_000, chunk = 2_048)
        val rms = sqrt(down.drop(800).dropLast(800).sumOf { it.toDouble() * it } / (down.size - 1_600))
        assertTrue(20 * log10(rms / (0.5 * 32767 / sqrt(2.0))) < -70.0, "aliased rms $rms")
    }

    @Test
    fun aConstantStaysExactlyConstant() {
        val input = ShortArray(2 * 10_000) { 12_345 }
        val out = resample(input, 44_100, 48_000, chunk = 512)
        assertTrue(out.drop(200).dropLast(200).all { abs(it - 12_345) <= 1 })
    }
}
