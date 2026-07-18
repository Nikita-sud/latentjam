/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class HistorySessionTrackerTest {

    private val a = TrackId("a")
    private val b = TrackId("b")

    @Test
    fun emitsNothingWhileSameTrackPlays() {
        val tracker = HistorySessionTracker()
        assertNull(tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 1_000))
        assertNull(tracker.onSnapshot(a, 50_000, 200_000, "OFF", nowMs = 51_000))
    }

    @Test
    fun completedWhenPastThreshold() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "SMART", nowMs = 1_000)
        tracker.onSnapshot(a, 180_000, 200_000, "SMART", nowMs = 181_000)
        val event = assertNotNull(tracker.onSnapshot(b, 0, 100_000, "SMART", nowMs = 182_000))
        assertEquals(a, event.trackId)
        assertTrue(event.completed, "180s of 200s is past the 85% threshold")
        assertFalse(event.skipped)
        assertEquals(180_000, event.playedMs)
        assertEquals("SMART", event.shuffleMode)
        assertEquals(1_000, event.startedAtMs)
    }

    @Test
    fun skippedWhenAbandonedEarly() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 1_000)
        tracker.onSnapshot(a, 8_000, 200_000, "OFF", nowMs = 9_000)
        val event = assertNotNull(tracker.onSnapshot(b, 0, 100_000, "OFF", nowMs = 10_000))
        assertTrue(event.skipped)
        assertFalse(event.completed)
    }

    @Test
    fun partialListenIsNeitherCompletedNorSkipped() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, null, nowMs = 1_000)
        tracker.onSnapshot(a, 90_000, 200_000, null, nowMs = 91_000)
        val event = assertNotNull(tracker.onSnapshot(null, 0, 0, null, nowMs = 92_000))
        assertFalse(event.completed)
        assertFalse(event.skipped)
    }

    @Test
    fun flushEmitsInProgressSessionOnce() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "ON", nowMs = 1_000)
        tracker.onSnapshot(a, 40_000, 200_000, "ON", nowMs = 41_000)
        val event = assertNotNull(tracker.flush())
        assertEquals(a, event.trackId)
        assertNull(tracker.flush(), "second flush must not duplicate")
    }

    @Test
    fun unknownDurationNeverCompletes() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 0, null, nowMs = 1_000)
        tracker.onSnapshot(a, 500_000, 0, null, nowMs = 501_000)
        val event = assertNotNull(tracker.flush())
        assertFalse(event.completed)
        assertNull(event.trackDurationMs)
    }
}
