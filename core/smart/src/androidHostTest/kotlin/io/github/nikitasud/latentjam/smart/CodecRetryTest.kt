/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecRetryTest {

    @Test
    fun aRejectionWithNothingElseLiveIsFinalAsIn093() {
        assertNull(codecRetryDelayMs(attempt = 0, contention = false, othersLive = false, leftMs = 3_000L))
    }

    @Test
    fun contentionIsTriedThreeTimesWithinThreeSeconds() {
        val delays = generateSequence(0) { it + 1 }
            .map { codecRetryDelayMs(it, contention = true, othersLive = false, leftMs = 3_000L) }
            .takeWhile { it != null }
            .toList()
        assertEquals(listOf(250L, 750L, 2_000L), delays)
        assertEquals(3_000L, delays.sumOf { it!! })
    }

    @Test
    fun anyFailureWhileAnotherCodecRunsCountsAsContention() {
        assertEquals(250L, codecRetryDelayMs(0, contention = false, othersLive = true, leftMs = 3_000L))
        assertNull(codecRetryDelayMs(3, contention = false, othersLive = true, leftMs = 3_000L))
    }

    @Test
    fun theThreeSecondsAreTheTracksNotEachWindows() {
        val backoff = CodecBackoff()
        // Window one waits 250 + 750 ms and its third try works; window two may wait at most what is left.
        assertTrue(backoff.take(250L))
        assertTrue(backoff.take(750L))
        assertEquals(2_000L, backoff.leftMs)
        assertEquals(250L, codecRetryDelayMs(0, contention = true, othersLive = false, leftMs = backoff.leftMs))
        assertTrue(backoff.take(250L))
        assertEquals(750L, codecRetryDelayMs(1, contention = true, othersLive = false, leftMs = backoff.leftMs))
        assertTrue(backoff.take(750L))
        assertNull(codecRetryDelayMs(2, contention = true, othersLive = false, leftMs = backoff.leftMs), "2 s > 1 s left")
        assertFalse(backoff.take(2_000L))
        assertEquals(1_000L, backoff.leftMs)
    }
}
