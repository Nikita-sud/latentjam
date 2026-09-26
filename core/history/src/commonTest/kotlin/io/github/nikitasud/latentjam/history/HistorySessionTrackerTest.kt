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
    fun selectingThePlayingTrackAgainStartsANewListenBeforeTheCompletionThreshold() {
        val tracker = HistorySessionTracker()
        val auto = started(1, a, ListenStart.AUTO_ADVANCE)
        tracker.onSnapshot(a, 0, 240_000, "SMART", 1_000, start = auto,
            smartPlanPosition = 3, parentId = "playlist:old")
        tracker.onSnapshot(a, 40_000, 240_000, "SMART", 41_000, start = auto)

        val pick = started(2, a, ListenStart.USER_PICK)
        val first = assertNotNull(tracker.onSnapshot(a, 0, 240_000, "OFF", 42_000,
            start = pick, parentId = "album:new"))
        assertEquals(ListenOrigin(ListenStart.AUTO_ADVANCE, 3, "playlist:old"), first.origin)
        assertEquals(40_000, first.playedMs)
        tracker.onSnapshot(a, 60_000, 240_000, "OFF", 102_000, start = pick)
        val replay = assertNotNull(tracker.flush())
        assertEquals(ListenOrigin(ListenStart.USER_PICK, parentId = "album:new"), replay.origin)
        assertEquals("OFF", replay.shuffleMode)
        assertEquals(42_000, replay.startedAtMs)
        assertEquals(60_000, replay.listenedMs)
    }

    @Test
    fun aReplaySignalCanArriveAfterThePlayheadReset() {
        val tracker = HistorySessionTracker()
        val auto = started(1, a, ListenStart.AUTO_ADVANCE)
        tracker.onSnapshot(a, 0, 240_000, "SMART", 1_000, start = auto)
        tracker.onSnapshot(a, 40_000, 240_000, "SMART", 41_000, start = auto)
        assertNull(tracker.onSnapshot(a, 0, 240_000, "OFF", 42_000, start = auto))
        val pick = started(2, a, ListenStart.USER_PICK)
        assertNotNull(tracker.onSnapshot(a, 500, 240_000, "OFF", 42_500,
            start = pick, parentId = "album:new"))
        assertNull(tracker.onSnapshot(a, 1_000, 240_000, "OFF", 43_000, start = pick))
        assertEquals(ListenOrigin(ListenStart.USER_PICK, parentId = "album:new"), tracker.flush()?.origin)
    }

    @Test
    fun aRepeatSignalAheadOfThePlayheadDoesNotRecordTwoRestarts() {
        val tracker = HistorySessionTracker()
        val pick = started(1, a, ListenStart.USER_PICK)
        tracker.onSnapshot(a, 0, 240_000, "OFF", 1_000, start = pick)
        tracker.onSnapshot(a, 239_000, 240_000, "OFF", 240_000, start = pick)
        val repeat = started(2, a, ListenStart.REPEAT)
        assertNull(tracker.onSnapshot(a, 239_000, 240_000, "OFF", 240_001, start = repeat))
        assertNotNull(tracker.onSnapshot(a, 0, 240_000, "OFF", 241_000, start = repeat))
        assertNull(tracker.onSnapshot(a, 1_000, 240_000, "OFF", 242_000, start = repeat))
        val replay = assertNotNull(tracker.flush())
        assertEquals(ListenStart.REPEAT, replay.origin?.start)
        assertEquals(1_000, replay.playedMs)
        assertFalse(replay.completed)
    }

    @Test
    fun aReplaySignalDuringTheOpeningSecondsDoesNotCountTheSameIntervalTwice() {
        val tracker = HistorySessionTracker()
        val firstStart = started(1, a, ListenStart.USER_PICK)
        tracker.onSnapshot(a, 0, 240_000, "OFF", 0, start = firstStart)
        tracker.onSnapshot(a, 1_000, 240_000, "OFF", 1_000, start = firstStart)
        // The service has announced the restart, but the controller still shows its old position.
        val replay = started(2, a, ListenStart.USER_PICK)
        val first = assertNotNull(tracker.onSnapshot(a, 2_000, 240_000, "OFF", 2_000, start = replay))

        assertEquals(2_000, first.listenedMs)
    }

    @Test
    fun anExplicitReplayWithUnknownDurationStartsANewListen() {
        val tracker = HistorySessionTracker()
        val pick = started(1, a, ListenStart.USER_PICK)
        tracker.onSnapshot(a, 0, 0, "OFF", 1_000, start = pick)
        tracker.onSnapshot(a, 2_000, 0, "OFF", 3_000, start = pick)
        val replay = started(2, a, ListenStart.USER_PICK)
        assertNotNull(tracker.onSnapshot(a, 0, 0, "OFF", 4_000, start = replay))
        assertEquals(4_000, tracker.flush()?.startedAtMs)
    }

    @Test
    fun anOrdinarySeekToZeroKeepsItsListeningSessionAndOrigin() {
        val tracker = HistorySessionTracker()
        val pick = started(1, a, ListenStart.USER_PICK)
        tracker.onSnapshot(a, 0, 240_000, "OFF", 1_000, start = pick)
        tracker.onSnapshot(a, 40_000, 240_000, "OFF", 41_000, start = pick)
        assertNull(tracker.onSnapshot(a, 0, 240_000, "OFF", 42_000, start = pick))
        assertEquals(1_000, tracker.flush()?.startedAtMs)
    }

    @Test
    fun aListenResumedAfterAFlushStillSplitsOnTheNextSelectionOfThePlayingTrack() {
        val tracker = HistorySessionTracker()
        val pick = started(1, a, ListenStart.USER_PICK)
        tracker.onSnapshot(a, 0, 240_000, "SMART", 1_000, start = pick, smartPlanPosition = 3)
        tracker.onSnapshot(a, 4_000, 240_000, "SMART", 5_000, isPlaying = false, start = pick, smartPlanPosition = 3)
        // The app was left while paused.
        val firstHalf = assertNotNull(tracker.flush(6_000))
        assertEquals(ListenOrigin(ListenStart.USER_PICK), firstHalf.origin)

        // Back in the app, the same playback resumes where it stopped: no new start is reported.
        assertNull(tracker.onSnapshot(a, 4_000, 240_000, "SMART", 60_000, start = pick, smartPlanPosition = 3))
        tracker.onSnapshot(a, 10_000, 240_000, "SMART", 66_000, start = pick, smartPlanPosition = 3)
        // Then the listener taps the playing row.
        val tap = started(2, a, ListenStart.USER_PICK)
        val resumed = assertNotNull(
            tracker.onSnapshot(a, 0, 240_000, "SMART", 67_000, start = tap, smartPlanPosition = 3),
            "selecting the playing track starts a new listen after a resume too",
        )
        assertEquals(60_000, resumed.startedAtMs)
        // The resumed half began nobody knows how, and SMART still did not pick it.
        assertEquals(ListenOrigin(), resumed.origin)

        tracker.onSnapshot(a, 5_000, 240_000, "SMART", 72_000, start = tap, smartPlanPosition = 3)
        val replay = assertNotNull(tracker.flush())
        assertEquals(67_000, replay.startedAtMs)
        assertEquals(ListenOrigin(ListenStart.USER_PICK), replay.origin)
    }

    @Test
    fun theSameTrackPickedAgainAfterAFlushIsOneListenEvenBeforeItsStartIsReported() {
        val tracker = HistorySessionTracker()
        val pick = started(1, a, ListenStart.USER_PICK)
        tracker.onSnapshot(a, 0, 240_000, "OFF", 1_000, start = pick)
        tracker.onSnapshot(a, 30_000, 240_000, "OFF", 31_000, isPlaying = false, start = pick)
        assertNotNull(tracker.flush(32_000))

        // Picked again later: the player shows it from zero a moment before it reports the new start.
        assertNull(tracker.onSnapshot(a, 0, 240_000, "OFF", 90_000, start = pick))
        val again = started(2, a, ListenStart.USER_PICK)
        assertNull(tracker.onSnapshot(a, 500, 240_000, "OFF", 90_500, start = again))
        tracker.onSnapshot(a, 20_000, 240_000, "OFF", 110_000, start = again)

        val listen = assertNotNull(tracker.flush())
        assertEquals(90_000, listen.startedAtMs)
        assertEquals(ListenStart.USER_PICK, listen.origin?.start)
    }

    @Test
    fun restartingAnEarlyFlushedListenDoesNotInventAnExtraListen() {
        for (pausedAt in listOf(0L, 500L, 1_000L)) {
            val tracker = HistorySessionTracker()
            val pick = started(1, a, ListenStart.USER_PICK)
            tracker.onSnapshot(a, 0, 240_000, "OFF", 1_000, start = pick)
            tracker.onSnapshot(a, pausedAt, 240_000, "OFF", 1_000 + pausedAt,
                isPlaying = false, start = pick)
            assertNotNull(tracker.flush(3_000))

            // A new selection starts at zero before its new start signal reaches the controller.
            assertNull(tracker.onSnapshot(a, 0, 240_000, "OFF", 60_000, start = pick))
            val again = started(2, a, ListenStart.USER_PICK)
            assertNull(tracker.onSnapshot(a, 500, 240_000, "OFF", 60_500, start = again),
                "a delayed start after pausing at $pausedAt must bind the already-open listen")
            tracker.onSnapshot(a, 20_000, 240_000, "OFF", 80_000, start = again)

            val replay = assertNotNull(tracker.flush())
            assertEquals(60_000, replay.startedAtMs)
            assertEquals(20_000, replay.listenedMs)
            assertEquals(ListenStart.USER_PICK, replay.origin?.start)
        }
    }

    @Test
    fun aGenuineEarlyResumeStillRecognizesTheNextExplicitReplay() {
        for (pausedAt in listOf(0L, 500L, 1_000L)) {
            val tracker = HistorySessionTracker()
            val pick = started(1, a, ListenStart.USER_PICK)
            tracker.onSnapshot(a, 0, 240_000, "SMART", 1_000, start = pick, smartPlanPosition = 3)
            tracker.onSnapshot(a, pausedAt, 240_000, "SMART", 1_000 + pausedAt,
                isPlaying = false, start = pick, smartPlanPosition = 3)
            assertNotNull(tracker.flush(3_000))

            assertNull(tracker.onSnapshot(a, pausedAt, 240_000, "SMART", 60_000,
                start = pick, smartPlanPosition = 3))
            assertNull(tracker.onSnapshot(a, pausedAt + 2_000, 240_000, "SMART", 62_000,
                start = pick, smartPlanPosition = 3))
            val again = started(2, a, ListenStart.USER_PICK)
            val resumed = assertNotNull(tracker.onSnapshot(a, 0, 240_000, "SMART", 63_000,
                start = again, smartPlanPosition = 3))
            assertEquals(60_000, resumed.startedAtMs)
            assertEquals(ListenOrigin(), resumed.origin)
            assertEquals(ListenOrigin(ListenStart.USER_PICK), tracker.flush()?.origin)
        }
    }

    @Test
    fun leavingTwiceWhilePausedPreservesTheFlushedPlaybackIdentity() {
        val tracker = HistorySessionTracker()
        val pick = started(1, a, ListenStart.USER_PICK)
        tracker.onSnapshot(a, 0, 240_000, "SMART", 1_000, start = pick, smartPlanPosition = 3)
        tracker.onSnapshot(a, 4_000, 240_000, "SMART", 5_000,
            isPlaying = false, start = pick, smartPlanPosition = 3)
        assertNotNull(tracker.flush(6_000))

        // Reopen without playing, then leave again. There is no new listen to flush.
        assertNull(tracker.onSnapshot(a, 4_000, 240_000, "SMART", 10_000,
            isPlaying = false, start = pick, smartPlanPosition = 3))
        assertNull(tracker.flush(11_000))
        assertNull(tracker.flush(12_000))
        tracker.onSnapshot(a, 4_000, 240_000, "SMART", 60_000, start = pick, smartPlanPosition = 3)
        tracker.onSnapshot(a, 10_000, 240_000, "SMART", 66_000, start = pick, smartPlanPosition = 3)

        val again = started(2, a, ListenStart.USER_PICK)
        val resumed = assertNotNull(tracker.onSnapshot(a, 0, 240_000, "SMART", 67_000,
            start = again, smartPlanPosition = 3))
        assertEquals(60_000, resumed.startedAtMs)
        assertEquals(ListenOrigin(), resumed.origin)
        val replay = assertNotNull(tracker.flush())
        assertEquals(67_000, replay.startedAtMs)
        assertEquals(ListenOrigin(ListenStart.USER_PICK), replay.origin)
    }

    @Test
    fun selectingAPlannedQueueRowMakesTheListenTheUsersPick() {
        val tracker = HistorySessionTracker()
        tracker.onSnapshot(a, 0, 240_000, "SMART", 1_000,
            smartPlanPosition = 3, parentId = "playlist:p")
        tracker.onSnapshot(a, 500, 240_000, "SMART", 1_500,
            start = started(1, a, ListenStart.USER_PICK), smartPlanPosition = 3)
        assertEquals(ListenOrigin(ListenStart.USER_PICK, parentId = "playlist:p"), tracker.flush()?.origin)
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
