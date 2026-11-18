/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.playback.NowPlaying
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.PlaybackStart
import io.github.nikitasud.latentjam.playback.RepeatMode
import io.github.nikitasud.latentjam.playback.ShuffleMode
import io.github.nikitasud.latentjam.playback.StartCause
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the stored queue may be put back over one restored without some of its rows. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HeldResumeQueueRestTest {
    private val a = TrackDescriptor(TrackId("a"), title = "A")
    private val b = TrackDescriptor(TrackId("b"), title = "B")

    /** The player as a restore leaves it: paused on the restored row, a start no command announced. */
    private fun restored(reportsRequests: Boolean) = FakePlayback(reportsRequests).apply {
        show(NowPlaying(track = a, queue = listOf(a, b), queueIndex = 0, playbackStart = PlaybackStart(1, a.id, cause = null)))
    }

    @Test
    fun aPausedPlayerNobodyTouchedMayTakeTheStoredQueue() = runTest {
        for (reportsRequests in listOf(true, false)) {
            val playback = FakePlayback(reportsRequests)
            // Taken before the restore, as the hold is made: the restore's own start is nobody's hand.
            val made = playback.resumeHoldMoment()
            playback.show(NowPlaying(track = a, queue = listOf(a, b), queueIndex = 0, playbackStart = PlaybackStart(1, a.id, cause = null)))
            assertEquals(playback.state.value, awaitRestForHeldQueue(playback, made))
            assertTrue(playback.atRestSince(made))
        }
    }

    @Test
    fun aTapStillPreparingItsQueueIsNotRestoredOver() = runTest {
        // Android: the request is counted at once, the player shows nothing of it yet (not playing, no new start).
        val playback = restored(reportsRequests = true)
        val made = ResumeHoldMoment(playback.userPlaybackRevision, startSequence = null)
        playback.requested()
        assertNull(awaitRestForHeldQueue(playback, made))
        assertFalse(playback.atRestSince(made))
    }

    @Test
    fun aRequestMadeWhileTheSettleWaitsForAPauseEndsIt() = runTest {
        val playback = restored(reportsRequests = true)
        val made = ResumeHoldMoment(playback.userPlaybackRevision, startSequence = null)
        // Sound from outside the app's own requests (a headset): the settle waits for a pause.
        playback.show(playback.state.value.copy(isPlaying = true))
        val settle = async { awaitRestForHeldQueue(playback, made) }
        runCurrent()
        assertFalse(settle.isCompleted)
        // Next is pressed: it waits for a SMART plan, and the player is paused meanwhile.
        playback.requested()
        playback.show(playback.state.value.copy(isPlaying = false))
        assertNull(settle.await())
    }

    @Test
    fun aStartTheListenerMadeIsNotRestoredOverWhereThePlayerCountsNoRequests() = runTest {
        // iOS: no request count, but a start is reported at once with its cause.
        for (cause in listOf(StartCause.USER_PICK, StartCause.SKIP_NEXT, StartCause.SKIP_PREVIOUS)) {
            val playback = restored(reportsRequests = false)
            val made = ResumeHoldMoment(playback.userPlaybackRevision, startSequence = null)
            playback.show(playback.state.value.copy(track = b, queueIndex = 1, playbackStart = PlaybackStart(2, b.id, cause)))
            assertNull(awaitRestForHeldQueue(playback, made), "$cause")
        }
    }

    @Test
    fun aPlayerThatMeansToPlayIsNotAtRest() = runTest {
        // Buffering after Play, or a seek: not playing yet, but the transport shows Pause.
        val playback = restored(reportsRequests = false)
        val made = ResumeHoldMoment(0, startSequence = null)
        playback.show(playback.state.value.copy(isPlaying = false, showPauseButton = true))
        val settle = async { awaitRestForHeldQueue(playback, made) }
        runCurrent()
        assertFalse(settle.isCompleted)
        assertFalse(playback.atRestSince(made))
        playback.show(playback.state.value.copy(showPauseButton = false))
        assertEquals(playback.state.value, settle.await())
    }

    @Test
    fun aHoldMadeWhereTheMomentWasNotTakenIsNeverRestoredOver() = runTest {
        // A resume no screen started (a headset, the widget): the listener asked for the music.
        val playback = restored(reportsRequests = true)
        assertNull(awaitRestForHeldQueue(playback, made = null))
        assertFalse(playback.atRestSince(null))
    }

    private class FakePlayback(private val reportsRequests: Boolean) : PlaybackController {
        private val mutableState = MutableStateFlow(NowPlaying())
        override val state: StateFlow<NowPlaying> = mutableState
        private var revision = 0L
        override val userPlaybackRevision: Long get() = if (reportsRequests) revision else 0L

        fun show(now: NowPlaying) { mutableState.value = now }

        /** The listener asked the player for something through this app. */
        fun requested() { revision++ }

        override suspend fun play(tracks: List<TrackDescriptor>, startIndex: Int, smartPlanPositions: Map<TrackId, Int>) = Unit
        override suspend fun appendSmartPlan(tracks: List<TrackDescriptor>, smartPlanPositions: Map<TrackId, Int>) = Unit
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
