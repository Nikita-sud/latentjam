/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.history.PickSurface
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.PlaybackStart
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.playback.StartCause
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** For You rows start at once and get one SMART continuation, under repeat and quick taps alike. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ForYouPlaybackTest {
    private fun track(id: String) = TrackDescriptor(id = TrackId(id), audioUri = "content://media/$id")

    private val row = listOf(track("r1"), track("r2"), track("r3"))
    private val continuation = listOf(track("c1"), track("c2"))
    private val library = row + continuation
    private val plan: suspend (TrackDescriptor) -> List<TrackId> = { continuation.map { it.id } }

    @Test
    fun aRowGetsItsContinuationAfterIt() = runTest {
        val playback = FakePlayback()
        playFromForYou(playback, row, 1, library, plan = plan)
        assertEquals(row + continuation, playback.state.value.queue)
    }

    @Test
    fun withSmartSwitchedOffTheRowPlaysAsItselfAndNothingIsPlanned() = runTest {
        val playback = FakePlayback()
        var planned = false
        playFromForYou(playback, row, 1, library, continuation = { false }) { planned = true; continuation.map { it.id } }
        assertEquals(row, playback.state.value.queue)
        assertEquals(0, playback.appends)
        assertEquals(false, planned)
    }

    @Test
    fun smartSwitchedOffWhileTheContinuationPlansAppendsNothing() = runTest {
        val playback = FakePlayback()
        var smartOn = true
        playFromForYou(playback, row, 0, library, continuation = { smartOn }) {
            // The listener opens Settings and switches SMART off while the planner works.
            smartOn = false
            continuation.map { it.id }
        }
        assertEquals(row, playback.state.value.queue)
        assertEquals(0, playback.appends)
    }

    @Test
    fun repeatAllLoopsTheRowWithoutAContinuation() = runTest {
        val playback = FakePlayback(RepeatMode.ALL)
        playFromForYou(playback, row, 0, library, plan = plan)
        assertEquals(row, playback.state.value.queue)
    }

    @Test
    fun repeatAllTurnedOnWhileTheContinuationPlansStillWins() = runTest {
        val playback = FakePlayback()
        val planned = CompletableDeferred<List<TrackId>>()
        val launcher = ForYouPlaybackLauncher()
        launcher.launch(this) { isCurrent ->
            playFromForYou(playback, row, 0, library, isCurrent) { planned.await() }
        }
        runCurrent()
        playback.repeat(RepeatMode.ALL)
        planned.complete(continuation.map { it.id })
        runCurrent()
        assertEquals(row, playback.state.value.queue)
    }

    @Test
    fun twoQuickTapsOnTheSameRowAppendOneContinuation() = runTest {
        val playback = FakePlayback()
        val firstPlan = CompletableDeferred<List<TrackId>>()
        val secondPlan = CompletableDeferred<List<TrackId>>()
        val launcher = ForYouPlaybackLauncher()
        launcher.launch(this) { isCurrent ->
            playFromForYou(playback, row, 0, library, isCurrent) { firstPlan.await() }
        }
        runCurrent()
        launcher.launch(this) { isCurrent ->
            playFromForYou(playback, row, 2, library, isCurrent) { secondPlan.await() }
        }
        runCurrent()
        // The first tap's plan arrives late: it no longer owns the queue.
        firstPlan.complete(continuation.map { it.id })
        runCurrent()
        assertEquals(row, playback.state.value.queue)
        secondPlan.complete(continuation.map { it.id })
        runCurrent()
        assertEquals(row + continuation, playback.state.value.queue)
        assertEquals(1, playback.appends)
    }

    @Test
    fun aStaleTapCannotAppendEvenOnceItsPlanIsReady() = runTest {
        val playback = FakePlayback()
        val launcher = ForYouPlaybackLauncher()
        var firstCurrent: () -> Boolean = { true }
        launcher.launch(this) { isCurrent -> firstCurrent = isCurrent }
        runCurrent()
        launcher.launch(this) { }
        runCurrent()
        playFromForYou(playback, row, 0, library, firstCurrent, plan = plan)
        assertEquals(row, playback.state.value.queue)
    }

    @Test
    fun aRowTappedInSmartModeIsMarkedAsAForYouPickToo() = runTest {
        PersonalSignals.reset()
        val playback = FakePlayback(shuffleMode = ShuffleMode.SMART)
        val before = PersonalSignals.pickCount
        playFromForYou(playback, row, 1, library, plan = plan)
        assertEquals(before + 1, PersonalSignals.pickCount)
        assertEquals(row, playback.state.value.queue)
        assertEquals(0, playback.appends)
    }

    // A start that plans first (the hero card, SMART from a Map region or a mix).

    private val seed = track("seed")
    private val other = listOf(track("o1"), track("o2"))
    private val songs = (listOf(seed) + continuation + other).associateBy { it.id }

    @Test
    fun aPlannedStartPlaysItsSeedAndThePlanWhenNothingIntervenes() = runTest {
        PersonalSignals.reset()
        val playback = FakePlayback()
        var marked = 0
        val started = playPlannedFrom(playback, seed, { songs }, resumeAtMs = 90_000L, beforePlay = { marked++ }) {
            continuation.map { it.id }
        }
        assertTrue(started)
        assertEquals(listOf(seed) + continuation, playback.state.value.queue)
        assertEquals(mapOf(TrackId("c1") to 1, TrackId("c2") to 2), playback.lastPlanPositions)
        assertEquals(listOf(90_000L), playback.seeks)
        assertEquals(1, marked)
    }

    @Test
    fun aPickMadeWhileAStartPlansIsNotReplaced() = runTest {
        PersonalSignals.reset()
        val playback = FakePlayback()
        val planned = CompletableDeferred<List<TrackId>>()
        var marked = 0
        var started: Boolean? = null
        launch {
            started = playPlannedFrom(playback, seed, { songs }, resumeAtMs = 90_000L, beforePlay = { marked++ }) { planned.await() }
        }
        runCurrent()
        // The planner works; the listener taps a song in a list.
        PersonalSignals.pick(PickSurface.COLLECTION)
        playback.play(other, 0)
        planned.complete(continuation.map { it.id })
        runCurrent()
        assertEquals(false, started)
        assertEquals(other, playback.state.value.queue)
        assertEquals(emptyList(), playback.seeks)
        assertEquals(0, marked, "neither the queue's source nor the pick surface is rewritten")
    }

    @Test
    fun aStartTheListenerMadeElsewhereWhileItPlansIsNotReplacedEither() = runTest {
        PersonalSignals.reset()
        val playback = FakePlayback()
        val planned = CompletableDeferred<List<TrackId>>()
        launch { playPlannedFrom(playback, seed, { songs }) { planned.await() } }
        runCurrent()
        // A start no screen marked (a voice command, the notification): only the player reports it.
        playback.play(other, 1)
        planned.complete(continuation.map { it.id })
        runCurrent()
        assertEquals(other, playback.state.value.queue)
    }

    @Test
    fun aTrackThatFollowedByItselfWhileAStartPlansDoesNotStopIt() = runTest {
        PersonalSignals.reset()
        val playback = FakePlayback()
        playback.play(other, 0)
        val planned = CompletableDeferred<List<TrackId>>()
        launch { playPlannedFrom(playback, seed, { songs }) { planned.await() } }
        runCurrent()
        playback.advanceBy(StartCause.AUTO_ADVANCE)
        planned.complete(continuation.map { it.id })
        runCurrent()
        assertEquals(listOf(seed) + continuation, playback.state.value.queue)
    }

    @Test
    fun aPickMadeBeforeTheTapAndReportedAfterItDoesNotStopTheStart() = runTest {
        PersonalSignals.reset()
        // Android: a start is reported by the service some time after it was asked for.
        val playback = FakePlayback(reportsStartsLate = true)
        PersonalSignals.pick(PickSurface.COLLECTION)
        playback.play(other, 0)
        val planned = CompletableDeferred<List<TrackId>>()
        launch { playPlannedFrom(playback, seed, { songs }) { planned.await() } }
        runCurrent()
        playback.reportPendingStart()
        planned.complete(continuation.map { it.id })
        runCurrent()
        assertEquals(listOf(seed) + continuation, playback.state.value.queue)
    }

    @Test
    fun aNewerForYouStartStopsAPlannedOne() = runTest {
        PersonalSignals.reset()
        val playback = FakePlayback()
        val planned = CompletableDeferred<List<TrackId>>()
        var current = true
        launch { playPlannedFrom(playback, seed, { songs }, isCurrent = { current }) { planned.await() } }
        runCurrent()
        current = false
        planned.complete(continuation.map { it.id })
        runCurrent()
        assertEquals(emptyList(), playback.state.value.queue)
    }

    @Test
    fun ofTwoPlannedStartsTheLaterTapIsTheOneThatPlays() = runTest {
        PersonalSignals.reset()
        val playback = FakePlayback()
        val launcher = ForYouPlaybackLauncher()
        val firstPlan = CompletableDeferred<List<TrackId>>()
        val secondPlan = CompletableDeferred<List<TrackId>>()
        val second = track("second")
        // "SMART from here" on one Map region, then, before anything sounds, on another (or on the hero, or a mix).
        launcher.launch(this) { isCurrent ->
            playPlannedFrom(playback, seed, { songs }, isCurrent, beforePlay = { PersonalSignals.pick(PickSurface.COLLECTION) }) {
                firstPlan.await()
            }
        }
        runCurrent()
        launcher.launch(this) { isCurrent ->
            playPlannedFrom(playback, second, { songs }, isCurrent, beforePlay = { PersonalSignals.pick(PickSurface.COLLECTION) }) {
                secondPlan.await()
            }
        }
        runCurrent()
        // The first plan is ready first. Started bare, it played, marked its pick, and the later tap then took that
        // mark for other music the listener had started and gave up.
        firstPlan.complete(continuation.map { it.id })
        runCurrent()
        secondPlan.complete(emptyList())
        runCurrent()
        assertEquals(listOf(second), playback.state.value.queue)
    }

    @Test
    fun withSmartSwitchedOffAPlannedStartPlaysItsOtherWayAndResumes() = runTest {
        PersonalSignals.reset()
        val playback = FakePlayback()
        var planned = false
        val started = playPlannedFrom(
            playback, seed, { songs }, resumeAtMs = 5_000L, smart = { false },
            withoutSmart = { playLibraryFrom(playback, seed, listOf(other[0], seed, other[1])) },
        ) { planned = true; continuation.map { it.id } }
        assertTrue(started)
        assertFalse(planned)
        assertEquals(listOf(other[0], seed, other[1]), playback.state.value.queue)
        assertEquals(1, playback.state.value.queueIndex)
        assertEquals(listOf(5_000L), playback.seeks)
    }

    private class FakePlayback(
        repeatMode: RepeatMode = RepeatMode.OFF,
        private val reportsStartsLate: Boolean = false,
        shuffleMode: ShuffleMode = ShuffleMode.OFF,
    ) : PlaybackController {
        private val mutableState = MutableStateFlow(NowPlaying(repeatMode = repeatMode, shuffleMode = shuffleMode))
        override val state: StateFlow<NowPlaying> = mutableState
        var appends = 0

        fun repeat(mode: RepeatMode) {
            mutableState.value = mutableState.value.copy(repeatMode = mode)
        }

        val seeks = ArrayList<Long>()
        var lastPlanPositions: Map<TrackId, Int> = emptyMap()
        private var sequence = 0L
        private var revision = 0L
        private var pendingStart: PlaybackStart? = null

        /** Counts requests only where starts are reported late, as on Android; elsewhere it stays 0, as on iOS. */
        override val userPlaybackRevision: Long get() = if (reportsStartsLate) revision else 0L

        override suspend fun play(
            tracks: List<TrackDescriptor>,
            startIndex: Int,
            smartPlanPositions: Map<TrackId, Int>,
        ) {
            revision++
            lastPlanPositions = smartPlanPositions
            val start = PlaybackStart(++sequence, tracks[startIndex].id, StartCause.USER_PICK)
            mutableState.value = mutableState.value.copy(queue = tracks, queueIndex = startIndex)
            if (reportsStartsLate) pendingStart = start else mutableState.value = mutableState.value.copy(playbackStart = start)
        }

        fun reportPendingStart() {
            pendingStart?.let { mutableState.value = mutableState.value.copy(playbackStart = it) }
            pendingStart = null
        }

        /** The queue moves on by itself. */
        fun advanceBy(cause: StartCause) {
            val now = mutableState.value
            val index = now.queueIndex + 1
            mutableState.value = now.copy(queueIndex = index, playbackStart = PlaybackStart(++sequence, now.queue[index].id, cause))
        }

        override suspend fun appendSmartPlan(tracks: List<TrackDescriptor>, smartPlanPositions: Map<TrackId, Int>) {
            appends++
            mutableState.value = mutableState.value.copy(queue = mutableState.value.queue + tracks)
        }

        override suspend fun setSmartLibrary(tracks: List<TrackDescriptor>) = Unit
        override suspend fun setSmartQueueLength(length: Int) = Unit
        override suspend fun invalidateSmartFuture() = Unit
        override suspend fun togglePlayPause() = Unit
        override suspend fun pause() = Unit
        override suspend fun next() = Unit
        override suspend fun previous() = Unit
        override suspend fun seekTo(positionMs: Long) { seeks += positionMs }
        override suspend fun playAt(queueIndex: Int) = Unit
        override suspend fun cycleShuffleMode() = ShuffleMode.OFF
        override suspend fun setShuffleMode(mode: ShuffleMode) = Unit
        override suspend fun restoreQueue(
            tracks: List<TrackDescriptor>,
            startIndex: Int,
            positionMs: Long,
            sourceTracks: List<TrackDescriptor>?,
            smartContinuationIds: Set<TrackId>,
            smartPlanPositions: Map<TrackId, Int>,
        ) = Unit
        override suspend fun cycleRepeatMode() = RepeatMode.OFF
        override suspend fun retainQueue(trackIds: Set<TrackId>) = Unit
        override suspend fun refreshTracks(tracks: List<TrackDescriptor>) = Unit
        override suspend fun playNext(track: TrackDescriptor) = Unit
        override suspend fun addToQueue(track: TrackDescriptor) = Unit
        override suspend fun moveQueueItem(from: Int, to: Int) = Unit
        override suspend fun removeQueueItem(index: Int) = Unit
    }
}
