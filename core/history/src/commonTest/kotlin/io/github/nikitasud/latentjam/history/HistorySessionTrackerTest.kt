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

    // Origin: who chose the track and from where. The player reports how a playback instance began
    // through a numbered signal, and it may show the new track a moment before that signal
    // describes it, so a session binds only to a signal for its own track that no earlier session
    // already claimed.

    private fun started(sequence: Long, track: TrackId, start: ListenStart?) =
        ListenStartSignal(sequence = sequence, trackId = track, start = start)

    @Test
    fun theSessionRecordsHowItsTrackStartedAndWhereItCameFrom() {
        val tracker = HistorySessionTracker()
        val autoA = started(1, a, ListenStart.AUTO_ADVANCE)
        for ((position, now) in listOf(0L to 1_000L, 90_000L to 91_000L)) {
            tracker.onSnapshot(
                a, position, 200_000, "SMART", nowMs = now,
                start = autoA, smartPlanPosition = 3, parentId = "playlist:p",
            )
        }

        val event = assertNotNull(tracker.onSnapshot(b, 0, 100_000, "SMART", nowMs = 92_000))
        assertEquals(
            ListenOrigin(ListenStart.AUTO_ADVANCE, smartPlanPosition = 3, parentId = "playlist:p"),
            event.origin,
        )
    }

    @Test
    fun aStartReportedJustAfterTheTrackChangeStillBelongsToTheNewTrack() {
        val tracker = HistorySessionTracker()
        val pickedA = started(1, a, ListenStart.USER_PICK)
        tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 1_000, start = pickedA)
        tracker.onSnapshot(a, 60_000, 200_000, "OFF", nowMs = 61_000, start = pickedA)
        // B is already current while the signal still describes A's start.
        val finishedA = assertNotNull(tracker.onSnapshot(b, 0, 100_000, "OFF", nowMs = 62_000, start = pickedA))
        tracker.onSnapshot(b, 500, 100_000, "OFF", nowMs = 62_500, start = started(2, b, ListenStart.SKIP_NEXT))

        assertEquals(ListenStart.USER_PICK, finishedA.origin?.start)
        assertEquals(ListenStart.SKIP_NEXT, tracker.flush()?.origin?.start)
    }

    @Test
    fun aRepeatDoesNotInheritTheStartOfThePlayBeforeIt() {
        val tracker = HistorySessionTracker()
        val pickedA = started(1, a, ListenStart.USER_PICK)
        tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 1_000, start = pickedA)
        tracker.onSnapshot(a, 190_000, 200_000, "OFF", nowMs = 191_000, start = pickedA)
        // Same track, back at zero: a new listen, still showing the old signal for a moment.
        val first = assertNotNull(tracker.onSnapshot(a, 0, 200_000, "OFF", nowMs = 201_000, start = pickedA))
        tracker.onSnapshot(a, 1_000, 200_000, "OFF", nowMs = 202_000, start = started(2, a, ListenStart.REPEAT))

        assertEquals(ListenStart.USER_PICK, first.origin?.start)
        assertEquals(ListenStart.REPEAT, tracker.flush()?.origin?.start)
    }

    @Test
    fun aSessionKeepsTheFirstStartItBoundTo() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "SMART", nowMs = 1_000, start = started(1, a, ListenStart.USER_PICK))
        // The player re-installed the same row mid-listen (a mode switch); nothing restarted.
        tracker.onSnapshot(a, 30_000, 200_000, "OFF", nowMs = 31_000, start = started(2, a, null))

        assertEquals(ListenStart.USER_PICK, tracker.flush()?.origin?.start)
    }

    @Test
    fun anUnreportedStartIsUnknownButTheRestOfTheOriginIsKept() {
        val tracker = HistorySessionTracker()
        val stale = started(7, b, ListenStart.USER_PICK)
        for ((position, now) in listOf(0L to 1_000L, 50_000L to 51_000L)) {
            tracker.onSnapshot(
                a, position, 200_000, "SMART", nowMs = now,
                start = stale, smartPlanPosition = 2, parentId = "album:k",
            )
        }

        assertEquals(
            ListenOrigin(start = null, smartPlanPosition = 2, parentId = "album:k"),
            tracker.flush()?.origin,
        )
    }

    @Test
    fun theParentAndPlanPositionAreThoseOfTheMomentTheTrackStarted() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "SMART", nowMs = 1_000, smartPlanPosition = 2, parentId = "playlist:p")
        tracker.onSnapshot(a, 50_000, 200_000, "SMART", nowMs = 51_000, smartPlanPosition = null, parentId = "album:q")

        assertEquals(ListenOrigin(smartPlanPosition = 2, parentId = "playlist:p"), tracker.flush()?.origin)
    }

    @Test
    fun anOriginTheLogCouldNotReloadIsNeverRecorded() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 200_000, "SMART", nowMs = 1_000, smartPlanPosition = 0, parentId = "")
        tracker.onSnapshot(a, 50_000, 200_000, "SMART", nowMs = 51_000, smartPlanPosition = 0, parentId = "")

        val event = assertNotNull(tracker.flush())
        assertEquals(ListenOrigin(), event.origin)
        assertEquals(event, ListenEvent.parse(event.serialize()), "the recorded line must reload")
    }
}
