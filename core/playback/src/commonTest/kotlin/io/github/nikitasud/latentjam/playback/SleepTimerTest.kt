/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    @Test
    fun `countdown pauses once and turns itself off`() = runTest {
        val playback = FakePlayback()
        val timer = SleepTimerController(playback, backgroundScope) { testScheduler.currentTime }

        timer.startCountdown(1)
        runCurrent()
        assertEquals(SleepTimerState.Countdown(1), timer.state.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, playback.pauseCalls)
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test
    fun `replacing countdown prevents old timer firing`() = runTest {
        val playback = FakePlayback()
        val timer = SleepTimerController(playback, backgroundScope) { testScheduler.currentTime }

        timer.startCountdown(1)
        timer.startCountdown(2)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, playback.pauseCalls)
        assertEquals(SleepTimerState.Countdown(1), timer.state.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, playback.pauseCalls)
    }

    @Test
    fun `end of track follows the exact queue entry and stops after a skip`() = runTest {
        val first = TrackDescriptor(TrackId("one"))
        val second = TrackDescriptor(TrackId("two"))
        val playback = FakePlayback(
            initial = NowPlaying(
                track = first,
                isPlaying = true,
                queue = listOf(first, second),
                queueIndex = 0,
            ),
        )
        val timer = SleepTimerController(playback, backgroundScope) { testScheduler.currentTime }

        timer.startAtEndOfTrack()
        runCurrent()
        assertEquals(SleepTimerState.EndOfTrack, timer.state.value)

        playback.mutableState.value = playback.mutableState.value.copy(track = second, queueIndex = 1)
        runCurrent()
        assertEquals(1, playback.pauseCalls)
        assertEquals(SleepTimerState.Off, timer.state.value)
    }

    @Test
    fun `end of track stops repeat one when the playhead wraps`() = runTest {
        val track = TrackDescriptor(TrackId("loop"))
        val playback = FakePlayback(
            initial = NowPlaying(
                track = track,
                isPlaying = true,
                repeatMode = RepeatMode.ONE,
                positionMs = 118_900,
                durationMs = 120_000,
                queue = listOf(track),
                queueIndex = 0,
            ),
        )
        val timer = SleepTimerController(playback, backgroundScope) { testScheduler.currentTime }

        timer.startAtEndOfTrack()
        runCurrent()
        playback.mutableState.value = playback.mutableState.value.copy(positionMs = 50)
        runCurrent()

        assertEquals(1, playback.pauseCalls)
        assertEquals(SleepTimerState.Off, timer.state.value)
    }
}

private class FakePlayback(initial: NowPlaying = NowPlaying()) : PlaybackController {
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<NowPlaying> = mutableState
    var pauseCalls = 0

    override suspend fun pause() {
        pauseCalls++
        mutableState.value = mutableState.value.copy(isPlaying = false)
    }

    override suspend fun setSmartLibrary(tracks: List<TrackDescriptor>) = Unit
    override suspend fun setSmartQueueLength(length: Int) = Unit
    override suspend fun invalidateSmartFuture() = Unit
    override suspend fun play(tracks: List<TrackDescriptor>, startIndex: Int) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun next() = Unit
    override suspend fun previous() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun playAt(queueIndex: Int) = Unit
    override suspend fun cycleShuffleMode(): ShuffleMode = ShuffleMode.OFF
    override suspend fun setShuffleMode(mode: ShuffleMode) = Unit
    override suspend fun retainQueue(trackIds: Set<TrackId>) = Unit
    override suspend fun restoreQueue(
        tracks: List<TrackDescriptor>,
        startIndex: Int,
        positionMs: Long,
        sourceTracks: List<TrackDescriptor>?,
    ) = Unit
    override suspend fun cycleRepeatMode(): RepeatMode = RepeatMode.OFF
    override suspend fun playNext(track: TrackDescriptor) = Unit
    override suspend fun addToQueue(track: TrackDescriptor) = Unit
    override suspend fun moveQueueItem(from: Int, to: Int) = Unit
    override suspend fun removeQueueItem(index: Int) = Unit
}
