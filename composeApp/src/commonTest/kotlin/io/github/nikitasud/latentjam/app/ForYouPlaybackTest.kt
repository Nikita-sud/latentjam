/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
        playFromForYou(playback, row, 0, library, firstCurrent, plan)
        assertEquals(row, playback.state.value.queue)
    }

    private class FakePlayback(repeatMode: RepeatMode = RepeatMode.OFF) : PlaybackController {
        private val mutableState = MutableStateFlow(NowPlaying(repeatMode = repeatMode))
        override val state: StateFlow<NowPlaying> = mutableState
        var appends = 0

        fun repeat(mode: RepeatMode) {
            mutableState.value = mutableState.value.copy(repeatMode = mode)
        }

        override suspend fun play(
            tracks: List<TrackDescriptor>,
            startIndex: Int,
            smartPlanPositions: Map<TrackId, Int>,
        ) {
            mutableState.value = mutableState.value.copy(queue = tracks, queueIndex = startIndex)
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
        override suspend fun seekTo(positionMs: Long) = Unit
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
