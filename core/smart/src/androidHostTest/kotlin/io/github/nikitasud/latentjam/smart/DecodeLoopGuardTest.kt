/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal class DecodeLoopGuardTest {

    @Test
    fun idleBudgetResetsOnlyWhenProgressIsReported() {
        var now = 0L
        val guard = DecodeLoopGuard(
            wallBudgetNanos = 100L,
            idleBudgetNanos = 10L,
            nowNanos = { now },
        )

        now = 9L
        assertEquals(DecodeLoopGuardResult.CONTINUE, guard.observe(cancelled = false))
        assertEquals(
            DecodeLoopGuardResult.CONTINUE,
            guard.observe(cancelled = false, madeProgress = true),
        )
        now = 18L
        assertEquals(DecodeLoopGuardResult.CONTINUE, guard.observe(cancelled = false))
        now = 19L
        assertEquals(DecodeLoopGuardResult.IDLE_TIMEOUT, guard.observe(cancelled = false))
    }

    @Test
    fun wallBudgetIsAbsoluteEvenWhenCodecKeepsMakingProgress() {
        var now = 0L
        val guard = DecodeLoopGuard(
            wallBudgetNanos = 20L,
            idleBudgetNanos = 10L,
            nowNanos = { now },
        )

        now = 9L
        assertEquals(
            DecodeLoopGuardResult.CONTINUE,
            guard.observe(cancelled = false, madeProgress = true),
        )
        now = 18L
        assertEquals(
            DecodeLoopGuardResult.CONTINUE,
            guard.observe(cancelled = false, madeProgress = true),
        )
        now = 20L
        assertEquals(
            DecodeLoopGuardResult.WALL_TIMEOUT,
            guard.observe(cancelled = false, madeProgress = true),
        )
    }

    @Test
    fun cancellationWinsOverTimeouts() {
        var now = 0L
        val guard = DecodeLoopGuard(
            wallBudgetNanos = 10L,
            idleBudgetNanos = 5L,
            nowNanos = { now },
        )

        now = 10L
        assertEquals(
            DecodeLoopGuardResult.CANCELLED,
            guard.observe(cancelled = true),
        )
    }

    @Test
    fun deterministicDecodeOrEmbeddingFailureIsCacheableInvalidAudio() {
        val error = zeroSuccessfulAudioWindowsError(
            backendContractFailure = null,
            unavailableFailure = null,
            invalidAudioFailure = "decoder reached end of stream without PCM",
        )

        val invalid = assertIs<EngineError.InvalidAudio>(error)
        assertEquals("decoder reached end of stream without PCM", invalid.technicalDetail)
    }

    @Test
    fun oneTransientWindowPreventsOtherCropFailureFromBeingCached() {
        val error = zeroSuccessfulAudioWindowsError(
            backendContractFailure = null,
            unavailableFailure = "codec resource temporarily unavailable",
            invalidAudioFailure = "another crop produced no PCM",
        )

        val unavailable = assertIs<EngineError.AudioUnavailable>(error)
        assertEquals("codec resource temporarily unavailable", unavailable.technicalDetail)
    }

    @Test
    fun modelContractViolationRemainsRetryableBackendFailure() {
        val error = zeroSuccessfulAudioWindowsError(
            backendContractFailure = "model returned the wrong dimension",
            unavailableFailure = "codec resource temporarily unavailable",
            invalidAudioFailure = "no PCM",
        )

        val backend = assertIs<EngineError.BackendFailure>(error)
        assertEquals("model returned the wrong dimension", backend.message)
    }

    @Test
    fun missingSourceFailureStaysRetryable() {
        val result = classifyAudioDecodeException(
            stage = AudioDecodeFailureStage.SOURCE_OPEN,
            error = java.io.FileNotFoundException("source disappeared"),
            startMs = 0L,
        )

        val unavailable = assertIs<AudioDecodeResult.Unavailable>(result)
        assertEquals("FileNotFoundException: source disappeared at 0ms", unavailable.detail)
    }

    @Test
    fun securityFailureAfterDescriptorOpenStillStaysRetryable() {
        val result = classifyAudioDecodeException(
            stage = AudioDecodeFailureStage.SOURCE_PARSE,
            error = SecurityException("permission revoked"),
            startMs = 0L,
        )

        val unavailable = assertIs<AudioDecodeResult.Unavailable>(result)
        assertEquals("SecurityException: permission revoked at 0ms", unavailable.detail)
    }

    @Test
    fun parserRejectionAfterReadableDescriptorIsDeterministicInvalidAudio() {
        val result = classifyAudioDecodeException(
            stage = AudioDecodeFailureStage.SOURCE_PARSE,
            error = java.io.IOException("malformed or truncated container"),
            startMs = 2_000L,
        )

        val invalid = assertIs<AudioDecodeResult.InvalidAudio>(result)
        assertEquals(
            "IOException: malformed or truncated container at 2000ms",
            invalid.detail,
        )
    }

    @Test
    fun codecFailureAfterSuccessfulParseStaysRetryable() {
        val result = classifyAudioDecodeException(
            stage = AudioDecodeFailureStage.CODEC,
            error = IllegalStateException("codec resource exhausted"),
            startMs = 5_000L,
        )

        val unavailable = assertIs<AudioDecodeResult.Unavailable>(result)
        assertEquals("IllegalStateException: codec resource exhausted at 5000ms", unavailable.detail)
    }
}
