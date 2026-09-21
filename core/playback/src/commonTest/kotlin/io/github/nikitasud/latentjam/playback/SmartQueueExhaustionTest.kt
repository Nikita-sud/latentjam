/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class SmartQueueExhaustionTest {

    @Test
    fun `a queue that ran dry resumes at the first row appended after it ended`() {
        assertEquals(
            12,
            smartResumeIndexAfterExhaustion(
                queueSizeBefore = 12,
                queueSizeAfter = 15,
                playbackEnded = true,
                playWhenReady = true,
            ),
        )
    }

    @Test
    fun `an abstaining recommender that appended nothing leaves the transport alone`() {
        assertNull(
            smartResumeIndexAfterExhaustion(
                queueSizeBefore = 12,
                queueSizeAfter = 12,
                playbackEnded = true,
                playWhenReady = true,
            ),
        )
    }

    @Test
    fun `a top-up while playback is still running never seeks`() {
        assertNull(
            smartResumeIndexAfterExhaustion(
                queueSizeBefore = 12,
                queueSizeAfter = 15,
                playbackEnded = false,
                playWhenReady = true,
            ),
        )
    }

    @Test
    fun `a paused transport is not restarted by a top-up`() {
        // Launch restore parks a finished session at its last row with playback intent off.
        // Growing that queue must not start audio the listener never asked for.
        assertNull(
            smartResumeIndexAfterExhaustion(
                queueSizeBefore = 12,
                queueSizeAfter = 15,
                playbackEnded = true,
                playWhenReady = false,
            ),
        )
    }

    @Test
    fun `an empty queue has nothing to resume`() {
        assertNull(
            smartResumeIndexAfterExhaustion(
                queueSizeBefore = 0,
                queueSizeAfter = 0,
                playbackEnded = true,
                playWhenReady = true,
            ),
        )
    }
}

internal class SmartContinuationBudgetTest {

    /** A queue of rows, each marked continuation (`c`) or recommendation (`r`). */
    private fun mayContinue(rows: String, currentIndex: Int): Boolean = smartMayContinue(
        queueSize = rows.length,
        currentIndex = currentIndex,
        isContinuation = { index -> rows[index] == 'c' },
    )

    @Test
    fun `an all-recommendation queue may always continue`() {
        assertEquals(true, mayContinue("rrrrr", currentIndex = 0))
    }

    @Test
    fun `the budget fills up and then holds`() {
        assertEquals(true, mayContinue("rcc", currentIndex = 0))
        assertEquals(false, mayContinue("rccc", currentIndex = 0))
        assertEquals(false, mayContinue("rcccc", currentIndex = 0))
    }

    @Test
    fun `continuations already heard do not count, so a long session never runs out of budget`() {
        // Three continuations behind the playhead; nothing queued ahead of it.
        assertEquals(true, mayContinue("cccr", currentIndex = 3))
    }

    @Test
    fun `the row being played is behind the playhead, not ahead of it`() {
        assertEquals(true, mayContinue("ccc", currentIndex = 2))
        assertEquals(false, mayContinue("cccc", currentIndex = 0))
    }

    @Test
    fun `a queue with no current row counts its whole future`() {
        assertEquals(false, mayContinue("ccc", currentIndex = -1))
    }

    @Test
    fun `an empty queue is not a reason to refuse`() {
        assertEquals(true, mayContinue("", currentIndex = -1))
    }
}
