/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndexingEtaTest {

    @Test
    fun `no estimate before any track has finished`() {
        // One sample is noise. Showing nothing beats showing a number that the
        // next chunk immediately contradicts.
        assertNull(IndexingEta(startedAtMs = 0).remainingMs(done = 0, total = 100, nowMs = 5_000))
    }

    @Test
    fun `no estimate once the work is done`() {
        val eta = IndexingEta(startedAtMs = 0)
        assertNull(eta.remainingMs(done = 100, total = 100, nowMs = 10_000))
        // Defensive: a caller that overshoots should not produce a negative ETA.
        assertNull(eta.remainingMs(done = 120, total = 100, nowMs = 10_000))
    }

    @Test
    fun `no estimate when no time has passed`() {
        // A clock that has not moved would divide by zero and report infinity.
        assertNull(IndexingEta(startedAtMs = 5_000).remainingMs(done = 10, total = 100, nowMs = 5_000))
        assertNull(IndexingEta(startedAtMs = 9_000).remainingMs(done = 10, total = 100, nowMs = 5_000))
    }

    @Test
    fun `extrapolates from the whole run so far`() {
        // 10 of 100 in 10s = 1s each, so 90 remain -> 90s.
        val eta = IndexingEta(startedAtMs = 0)
        assertEquals(90_000L, eta.remainingMs(done = 10, total = 100, nowMs = 10_000))
        // Halfway, having slowed to 2s each: 50 remain -> 100s.
        assertEquals(100_000L, eta.remainingMs(done = 50, total = 100, nowMs = 100_000))
    }

    @Test
    fun `is unaffected by where the clock starts`() {
        // Elapsed time, not absolute time — a device clock in the far future
        // must not change the estimate.
        val late = IndexingEta(startedAtMs = 1_700_000_000_000)
        assertEquals(90_000L, late.remainingMs(done = 10, total = 100, nowMs = 1_700_000_010_000))
    }

    @Test
    fun `minutes round up and never reach zero`() {
        // "1 minute left" that takes 90 seconds is a worse lie than "2".
        assertEquals(2, IndexingEta.minutesFrom(90_000))
        assertEquals(1, IndexingEta.minutesFrom(60_000))
        // Anything still running has at least a minute on the label; "0 min
        // left" next to a moving bar reads as stuck.
        assertEquals(1, IndexingEta.minutesFrom(1))
        assertEquals(1, IndexingEta.minutesFrom(0))
    }
}
