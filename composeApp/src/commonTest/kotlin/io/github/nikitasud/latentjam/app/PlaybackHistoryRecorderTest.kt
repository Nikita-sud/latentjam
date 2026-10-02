/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.PlaybackStart
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.playback.StartCause
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

internal class PlaybackHistoryRecorderTest {

    private val a = track("a")
    private val b = track("b")
    private val c = track("c")

    @Test
    fun `disabled recording never emits sessions`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = false)

        assertNull(gate.onSnapshot(now(a, 0), enabled = false, nowMs = 1))
        assertNull(gate.onSnapshot(now(a, 90_000), enabled = false, nowMs = 2))
        assertNull(gate.onSnapshot(now(b, 0), enabled = false, nowMs = 3))
        assertNull(gate.onSnapshot(now(c, 0), enabled = false, nowMs = 4))
    }

    @Test
    fun `disabling drops the current session and enabling waits for the next track`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = true)

        assertNull(gate.onSnapshot(now(a, 0), enabled = true, nowMs = 1))
        assertNull(gate.onSnapshot(now(a, 90_000), enabled = true, nowMs = 2))
        assertNull(gate.onSnapshot(now(a, 90_000), enabled = false, nowMs = 3))
        assertNull(gate.onSnapshot(now(a, 95_000), enabled = true, nowMs = 4))
        assertNull(gate.onSnapshot(now(b, 0), enabled = true, nowMs = 5))
        assertNull(gate.onSnapshot(now(b, 60_000), enabled = true, nowMs = 6))

        val event = gate.onSnapshot(now(c, 0), enabled = true, nowMs = 7)
        assertEquals(b.id, event?.trackId)
        assertEquals(60_000, event?.playedMs)
    }

    @Test
    fun `recording enabled from launch keeps the normal transition behavior`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = true)

        assertNull(gate.onSnapshot(now(a, 0), enabled = true, nowMs = 1))
        assertNull(gate.onSnapshot(now(a, 90_000), enabled = true, nowMs = 2))

        val event = gate.onSnapshot(now(b, 0), enabled = true, nowMs = 3)
        assertEquals(a.id, event?.trackId)
        assertEquals(90_000, event?.playedMs)
    }

    @Test
    fun `clearing the queue records the final listen exactly once`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = true)
        gate.onSnapshot(now(a, 0), enabled = true, nowMs = 1_000)
        gate.onSnapshot(now(a, 20_000), enabled = true, nowMs = 21_000)

        val event = gate.onSnapshot(NowPlaying(), enabled = true, nowMs = 21_500)
        assertEquals(a.id, event?.trackId)
        assertEquals(20_500, event?.listenedMs)
        assertNull(gate.onSnapshot(NowPlaying(), enabled = true, nowMs = 22_000))
        assertNull(gate.flush())
    }

    @Test
    fun `playing the same track after an empty queue starts a separate listen`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = true)
        gate.onSnapshot(now(a, 0), enabled = true, nowMs = 1_000)
        gate.onSnapshot(now(a, 20_000), enabled = true, nowMs = 21_000)
        gate.onSnapshot(NowPlaying(), enabled = true, nowMs = 21_500)
        gate.onSnapshot(now(a, 0), enabled = true, nowMs = 100_000)
        gate.onSnapshot(now(a, 5_000), enabled = true, nowMs = 105_000)

        val second = gate.flush()
        assertEquals(100_000, second?.startedAtMs)
        assertEquals(5_000, second?.listenedMs)
    }

    @Test
    fun `each listen records who chose the track and the collection it came from`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = true)
        val pickedA = PlaybackStart(sequence = 1, trackId = a.id, cause = StartCause.USER_PICK)
        val advancedToB = PlaybackStart(sequence = 2, trackId = b.id, cause = StartCause.AUTO_ADVANCE)
        val planned = mapOf(b.id to 1)

        gate.onSnapshot(now(a, 0).copy(playbackStart = pickedA), true, nowMs = 1, parentId = "playlist:p")
        gate.onSnapshot(now(a, 90_000).copy(playbackStart = pickedA), true, nowMs = 2, parentId = "playlist:p")
        val finishedA = gate.onSnapshot(
            now(b, 0).copy(playbackStart = advancedToB, smartPlanPositions = planned),
            true,
            nowMs = 3,
            parentId = "playlist:p",
        )
        gate.onSnapshot(
            now(b, 60_000).copy(playbackStart = advancedToB, smartPlanPositions = planned),
            true,
            nowMs = 4,
            parentId = "playlist:p",
        )

        assertEquals(ListenOrigin(ListenStart.USER_PICK, null, "playlist:p"), finishedA?.origin)
        assertEquals(ListenOrigin(ListenStart.AUTO_ADVANCE, 1, "playlist:p"), gate.flush()?.origin)
    }

    @Test
    fun `every start cause is logged under its own name`() {
        for (cause in StartCause.entries) {
            val gate = PlaybackHistoryGate(initiallyEnabled = true)
            val start = PlaybackStart(sequence = 1, trackId = a.id, cause = cause)
            gate.onSnapshot(now(a, 0).copy(playbackStart = start), true, nowMs = 1, parentId = null)

            assertEquals(cause.name, gate.flush()?.origin?.start?.name)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `one failed append does not stop later sessions from being recorded`() = runTest {
        val playback = FakePlaybackController()
        val history = FailFirstListeningHistory()
        var recordedCallbacks = 0
        val recorder = launchPlaybackHistoryRecorder(
            playback = playback,
            history = history,
            enabled = MutableStateFlow(true),
            onRecorded = { recordedCallbacks++ },
        )
        advanceUntilIdle()

        playback.emit(now(a, 0))
        advanceUntilIdle()
        playback.emit(now(a, 90_000))
        advanceUntilIdle()
        // Finishes A. Its append fails once, exercising the collector's recovery path.
        playback.emit(now(b, 0))
        advanceUntilIdle()

        playback.emit(now(b, 60_000))
        advanceUntilIdle()
        // Finishes B. This must still reach the same process-lifetime collector.
        playback.emit(now(c, 0))
        advanceUntilIdle()

        assertTrue(recorder.isActive)
        assertEquals(listOf(b.id), history.recorded.map(ListenEvent::trackId))
        assertEquals(1, recordedCallbacks, "only durable appends advance the history revision")
        recorder.cancel()
    }

    private fun track(id: String) = TrackDescriptor(id = TrackId(id), title = id.uppercase())

    private fun now(track: TrackDescriptor, positionMs: Long) = NowPlaying(
        track = track,
        // These fixtures model live listening; a session only opens for a PLAYING track
        // (a restored-but-parked queue must not turn into phantom skips).
        isPlaying = true,
        positionMs = positionMs,
        durationMs = 100_000,
    )

    private class FailFirstListeningHistory : ListeningHistory {
        var attempts = 0
        val recorded = mutableListOf<ListenEvent>()

        override suspend fun record(event: ListenEvent) {
            if (attempts++ == 0) error("temporary write failure")
            recorded += event
        }

        override suspend fun stats() = emptyMap<TrackId, io.github.nikitasud.latentjam.history.TrackStats>()
        override suspend fun recentEvents(limit: Int) = recorded.takeLast(limit).reversed()
        override suspend fun replace(events: List<ListenEvent>) {
            recorded.clear()
            recorded += events
        }
        override suspend fun clear() = recorded.clear()
    }

    private class FakePlaybackController : PlaybackController {
        private val mutableState = MutableStateFlow(NowPlaying())
        override val state: StateFlow<NowPlaying> = mutableState

        fun emit(nowPlaying: NowPlaying) {
            mutableState.value = nowPlaying
        }

        override suspend fun setSmartLibrary(tracks: List<TrackDescriptor>) = Unit
        override suspend fun setSmartQueueLength(length: Int) = Unit
        override suspend fun invalidateSmartFuture() = Unit
        override suspend fun play(
            tracks: List<TrackDescriptor>,
            startIndex: Int,
            smartPlanPositions: Map<TrackId, Int>,
        ) = Unit
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

    // The log records a session only when playback moves OFF a track, so the final track of
    // every sitting was silently dropped when the app was backgrounded and killed. flush() is
    // the backgrounding hook: finalize what is in progress WITHOUT waiting for a transition.

    @Test
    fun `flush records the in-progress session and leaves the gate empty`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = true)
        gate.onSnapshot(now(a, 0), enabled = true, nowMs = 1)
        gate.onSnapshot(now(a, 90_000), enabled = true, nowMs = 2)

        val event = gate.flush()
        assertEquals(a.id, event?.trackId)
        assertEquals(90_000, event?.playedMs)
        assertNull(gate.flush(), "a second flush has nothing left to record")
    }

    @Test
    fun `flush is silent while recording is disabled`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = false)
        gate.onSnapshot(now(a, 0), enabled = false, nowMs = 1)
        assertNull(gate.flush())
    }

    @Test
    fun `flush does not disturb the waiting-for-next-track privacy rule`() {
        val gate = PlaybackHistoryGate(initiallyEnabled = false)
        // History enabled mid-track: that track stays ignored until the next one.
        gate.onSnapshot(now(a, 10_000), enabled = true, nowMs = 1)
        assertNull(gate.flush(), "the ignored track must not be recorded by a flush either")
        assertNull(
            gate.onSnapshot(now(a, 50_000), enabled = true, nowMs = 2),
            "and it stays ignored after the flush",
        )
    }
}
