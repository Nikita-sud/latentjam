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
        assertEquals(181_000, event.listenedMs)
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

    // A queue restored from a previous run parks a track in the player without anyone choosing to
    // hear it now. Only actual playback may open a session: otherwise closing the app again (or
    // picking something else tomorrow) would log a skip for a track the user never rejected --
    // false negatives written straight into the signal SMART is evaluated against.

    @Test
    fun aPausedTrackOpensNoSession() {
        val tracker = HistorySessionTracker()
        assertNull(tracker.onSnapshot(a, 0, 200_000, "SMART", nowMs = 1_000, isPlaying = false))
        val event = tracker.onSnapshot(b, 0, 100_000, "SMART", nowMs = 500_000, isPlaying = false)
        assertNull(event, "neither track ever played, so there is nothing to record")
    }

    @Test
    fun theSessionOpensWhenTheRestoredTrackFinallyPlays() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "SMART", nowMs = 1_000, isPlaying = false)
        tracker.onSnapshot(a, 0, 200_000, "SMART", nowMs = 900_000, isPlaying = true)
        tracker.onSnapshot(a, 180_000, 200_000, "SMART", nowMs = 1_080_000, isPlaying = true)
        val event = assertNotNull(tracker.onSnapshot(b, 0, 100_000, "SMART", nowMs = 1_081_000))
        assertEquals(a, event.trackId)
        assertTrue(event.completed)
        assertEquals(900_000, event.startedAtMs, "the session began at play, not at restore")
    }

    @Test
    fun pausingMidTrackDoesNotCloseTheSession() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 1_000)
        assertNull(tracker.onSnapshot(a, 50_000, 200_000, "OFF", nowMs = 51_000, isPlaying = false))
        val event = assertNotNull(tracker.onSnapshot(b, 0, 100_000, "OFF", nowMs = 60_000))
        assertEquals(a, event.trackId)
        assertEquals(50_000, event.playedMs)
    }

    @Test
    fun repeatOneWrapClosesTheFirstListenAndStartsAnother() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 1_000)
        tracker.onSnapshot(a, 190_000, 200_000, "OFF", nowMs = 191_000)

        val first = assertNotNull(
            tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 201_000),
        )
        assertEquals(a, first.trackId)
        assertTrue(first.completed)
        assertEquals(190_000, first.playedMs)

        tracker.onSnapshot(a, 40_000, 200_000, "OFF", nowMs = 241_000)
        val second = assertNotNull(tracker.flush())
        assertEquals(201_000, second.startedAtMs)
        assertEquals(40_000, second.playedMs)
    }

    @Test
    fun ordinaryBackwardSeekDoesNotSplitTheSession() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 1_000)
        tracker.onSnapshot(a, 80_000, 200_000, "OFF", nowMs = 81_000)
        assertNull(tracker.onSnapshot(a, 20_000, 200_000, "OFF", nowMs = 82_000))

        val event = assertNotNull(tracker.flush())
        assertEquals(80_000, event.playedMs)
        assertEquals(80_000, event.listenedMs)
        assertEquals(1_000, event.startedAtMs)
    }

    @Test
    fun forwardSeekDoesNotInflateElapsedListeningTime() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 1_000)
        tracker.onSnapshot(a, 180_000, 200_000, "OFF", nowMs = 2_000)

        val event = assertNotNull(tracker.onSnapshot(b, 0, 100_000, "OFF", nowMs = 3_000))
        assertEquals(180_000, event.playedMs, "furthest position still drives completion")
        assertEquals(1_000, event.listenedMs, "the 180-second seek counts only one elapsed second")
    }

    @Test
    fun pausedWallTimeIsNotCounted() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 1_000)
        tracker.onSnapshot(a, 10_000, 200_000, "OFF", nowMs = 11_000, isPlaying = false)
        tracker.onSnapshot(a, 10_000, 200_000, "OFF", nowMs = 111_000, isPlaying = true)
        tracker.onSnapshot(a, 20_000, 200_000, "OFF", nowMs = 121_000, isPlaying = true)

        val event = assertNotNull(tracker.flush())
        assertEquals(20_000, event.listenedMs)
    }

    @Test
    fun replayAfterBackwardSeekAddsOnlyNewForwardListening() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 1_000)
        tracker.onSnapshot(a, 80_000, 200_000, "OFF", nowMs = 81_000)
        tracker.onSnapshot(a, 20_000, 200_000, "OFF", nowMs = 82_000)
        tracker.onSnapshot(a, 70_000, 200_000, "OFF", nowMs = 132_000)

        val event = assertNotNull(tracker.flush())
        assertEquals(80_000, event.playedMs)
        assertEquals(130_000, event.listenedMs)
    }

    @Test
    fun transitionIncludesAtMostOneUnobservedTickerTail() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 1_000)
        tracker.onSnapshot(a, 10_000, 200_000, "OFF", nowMs = 11_000)

        val event = assertNotNull(
            tracker.onSnapshot(b, 0, 200_000, "OFF", nowMs = 11_500),
        )
        assertEquals(10_500, event.listenedMs)

        tracker.onSnapshot(b, 10_000, 200_000, "OFF", nowMs = 21_500)
        val capped = assertNotNull(
            tracker.onSnapshot(null, 0, 0, null, nowMs = 1_000_000),
        )
        assertEquals(11_000, capped.listenedMs, "a stalled clock adds only the one-second cap")
    }

    @Test
    fun queueEndConsumesTheSessionSoALaterTrackCannotRecordItAgain() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 100_000, "OFF", nowMs = 1_000)
        tracker.onSnapshot(a, 50_000, 100_000, "OFF", nowMs = 51_000)

        val ended = assertNotNull(tracker.onSnapshot(null, 0, 0, null, nowMs = 51_500))
        assertEquals(a, ended.trackId)
        assertNull(tracker.onSnapshot(b, 0, 100_000, "OFF", nowMs = 60_000))
    }
}
