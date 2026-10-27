/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * iOS keeps a track's deadline clock running while the app is suspended: a timeout across a suspension is
 * retryable, never the remembered "timed out".
 */
internal class SuspendedTimeoutTest {

    private val budget = 90.seconds
    private val timedOut: Result<FloatArray> = Result.failure(SmartEngineException(analysisTimedOut(budget)))

    private fun errorOf(result: Result<FloatArray>): EngineError? =
        (result.exceptionOrNull() as? SmartEngineException)?.error

    @Test
    fun aTimeoutWithTheAppInTheForegroundThroughoutStaysRemembered() {
        val result = timeoutUnlessSuspended(timedOut, budget, transitionsBefore = 2, transitionsAfter = 2, inBackgroundAfter = false)
        assertSame(timedOut.exceptionOrNull(), result.exceptionOrNull())
    }

    @Test
    fun aTimeoutAcrossALeaveAndReturnIsRetryable() {
        val result = timeoutUnlessSuspended(timedOut, budget, transitionsBefore = 2, transitionsAfter = 4, inBackgroundAfter = false)
        assertEquals(EngineError.AudioUnavailable::class, errorOf(result)!!::class)
    }

    @Test
    fun aTimeoutFiringBeforeTheReturnIsSeenIsRetryable() {
        // The track started in the background; the timer woke on resume before the return was delivered.
        val result = timeoutUnlessSuspended(timedOut, budget, transitionsBefore = 1, transitionsAfter = 1, inBackgroundAfter = true)
        assertEquals(EngineError.AudioUnavailable::class, errorOf(result)!!::class)
    }

    @Test
    fun otherResultsAreUnchangedAcrossASuspension() {
        val invalid: Result<FloatArray> = Result.failure(SmartEngineException(EngineError.InvalidAudio("Audio is not decodable")))
        val success = Result.success(floatArrayOf(1f))
        assertSame(invalid.exceptionOrNull(), timeoutUnlessSuspended(invalid, budget, 0, 2, inBackgroundAfter = true).exceptionOrNull())
        assertSame(success.getOrNull(), timeoutUnlessSuspended(success, budget, 0, 2, inBackgroundAfter = true).getOrNull())
    }
}
