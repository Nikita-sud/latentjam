/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class LookaheadPeakLimiterTest {
    @Test fun quietSignalHasOnlyTheDeclaredDelayAndNoLevelLoss() {
        val limiter = LookaheadPeakLimiter(48000, 2)
        val random = Random(7)
        val input = List(1000) { floatArrayOf(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f) }
        for (index in 0 until input.size + limiter.latencyFrames) {
            val frame = input.getOrNull(index)?.copyOf() ?: FloatArray(2)
            limiter.process(frame)
            val expected = input.getOrNull(index - limiter.latencyFrames) ?: FloatArray(2)
            assertTrue(frame.contentEquals(expected), "Frame $index")
        }
    }

    @Test fun isolatedPeakIsAnticipatedSmoothlyAndStereoRatioIsPreserved() {
        val limiter = LookaheadPeakLimiter(48000, 2)
        val spike = 1000
        var last = 0.1f
        for (index in 0 until 3000) {
            val input = if (index == spike) 5f else 0.1f
            val frame = floatArrayOf(input, -input / 4)
            limiter.process(frame)
            assertTrue(frame.all { abs(it) <= 1f })
            assertEquals(-frame[0] / 4, frame[1], 0.000001f)
            val source = index - limiter.latencyFrames
            if (source in spike - limiter.latencyFrames until spike) {
                assertTrue(abs(frame[0] - last) < 0.001f, "Gain must ramp before the transient")
            }
            if (source == spike) assertTrue(frame[0] > 0.99f, "Peak must be limited without being erased")
            last = frame[0]
        }
    }

    @Test fun strongBassDoesNotAcquireCycleByCycleGainRipple() {
        val limiter = LookaheadPeakLimiter(48000, 1)
        var squaredError = 0.0
        var count = 0
        for (index in 0 until 48000) {
            val frame = floatArrayOf((3 * sin(2 * PI * 40 * index / 48000)).toFloat())
            limiter.process(frame)
            if (index > 12000) {
                val expected = sin(2 * PI * 40 * (index - limiter.latencyFrames) / 48000)
                squaredError += (frame[0] - expected) * (frame[0] - expected)
                count++
            }
        }
        assertTrue(squaredError / count < 0.000001, "Holding peaks must avoid bass waveform distortion")
    }

    @Test fun changingCeilingsStayAttachedToTheirDelayedSamples() {
        val limiter = LookaheadPeakLimiter(48000, 2)
        for (index in 0 until 3000) {
            val ceiling = if (index in 700..1300) 0.8f else 1f
            val frame = floatArrayOf(1f, -1f)
            limiter.process(frame, ceiling)
            val source = index - limiter.latencyFrames
            val expectedCeiling = if (source in 700..1300) 0.8f else 1f
            assertTrue(abs(frame[0]) <= expectedCeiling + 0.000001f)
            assertEquals(-frame[0], frame[1], 0.000001f)
        }
    }

    @Test fun fullGainReturnsAfterAnOverloadAndSilenceStaysSilent() {
        val limiter = LookaheadPeakLimiter(48000, 1)
        val frame = floatArrayOf(8f)
        limiter.process(frame)
        repeat(96000) { frame[0] = 0.1f; limiter.process(frame) }
        assertEquals(0.1f, frame[0])
        repeat(limiter.latencyFrames + 100) { frame[0] = 0f; limiter.process(frame) }
        assertEquals(0f, frame[0])
    }
}
