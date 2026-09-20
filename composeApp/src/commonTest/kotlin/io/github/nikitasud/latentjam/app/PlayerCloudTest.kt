/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.MonotonicFrameClock
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerCloudTest {
    private fun close(a: Color, b: Color) =
        abs(a.red - b.red) < 0.02f && abs(a.green - b.green) < 0.02f && abs(a.blue - b.blue) < 0.02f

    @Test
    fun aFullTurnComesBackToTheSameColour() {
        val colour = Color(0.31f, 0.67f, 0.69f)
        assertTrue(close(colour, rotateHue(colour, 360f)))
        assertTrue(close(colour, rotateHue(rotateHue(colour, 28f), -28f)))
    }

    @Test
    fun greyHasNoHueToRotate() {
        val grey = Color(0.5f, 0.5f, 0.5f)
        assertEquals(grey, rotateHue(grey, 90f))
    }

    @Test
    fun rotatingRedByAThirdOfTheWheelGivesGreen() {
        assertTrue(close(Color(0f, 1f, 0f), rotateHue(Color(1f, 0f, 0f), 120f)))
    }

    @Test
    fun liftingForADarkSurfaceBrightensOnlyWhatWouldVanishOnBlack() {
        val nearBlackViolet = Color(0.12f, 0.06f, 0.18f)
        val lifted = liftForDarkSurface(nearBlackViolet)
        val lightness = (maxOf(lifted.red, lifted.green, lifted.blue) + minOf(lifted.red, lifted.green, lifted.blue)) / 2f
        assertTrue(lightness >= 0.44f)
        assertTrue(lifted.blue > lifted.green) // still violet
        val bright = Color(0.2f, 0.8f, 0.85f)
        assertTrue(close(bright, liftForDarkSurface(bright)))
        val grey = liftForDarkSurface(Color(0.1f, 0.1f, 0.1f))
        assertTrue(close(Color(0.45f, 0.45f, 0.45f), grey))
    }

    @Test
    fun cloudNeverRequestsMoreThanTwentyAnimationUpdatesPerSecondAndStopsOnCancel() = runTest {
        var frameRequests = 0
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                frameRequests++
                // An immediately available frame is stricter than even a 144 Hz display.
                return onFrame(testScheduler.currentTime * 1_000_000L)
            }
        }
        val elapsed = mutableListOf<Float>()
        val job = launch(clock) { animatePlayerCloud { elapsed += it } }
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(20, elapsed.size)
        assertEquals(21, frameRequests) // Includes the initial time anchor.
        assertTrue(abs(elapsed.sum() - 1f) < 0.001f)

        job.cancel()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(20, elapsed.size)
        assertEquals(21, frameRequests)
    }

    @Test
    fun returningFromASuspendedFrameClockDoesNotJumpThroughBackgroundTime() = runTest {
        var backgroundTimeNanos = 0L
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
                onFrame(testScheduler.currentTime * 1_000_000L + backgroundTimeNanos)
        }
        val elapsed = mutableListOf<Float>()
        val job = launch(clock) { animatePlayerCloud { elapsed += it } }
        runCurrent()
        backgroundTimeNanos = 30_000_000_000L
        advanceTimeBy(50L)
        runCurrent()
        assertTrue(elapsed.single() in 0f..0.1f)
        job.cancel()
    }
}
