/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
