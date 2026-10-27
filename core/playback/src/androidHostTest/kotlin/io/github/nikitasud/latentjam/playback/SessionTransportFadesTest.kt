/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class SessionTransportFadesTest {

    /**
     * Just enough of an ExoPlayer: a queue of ids, a playhead, the transport flags and the events the
     * envelope listens to. Seeks land at once, as ExoPlayer masks them.
     */
    private class FakeExo {
        val ids = mutableListOf("a", "b", "c", "d")
        var index = 0
        var positionMs = 10_000L
        var playWhenReady = true
        var playbackState = Player.STATE_READY
        var volume = 1f
        val listeners = mutableListOf<Player.Listener>()

        /** Transport commands that reached the player, with their arguments. */
        val calls = mutableListOf<String>()

        val isPlaying get() = playWhenReady && playbackState == Player.STATE_READY

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "isPlaying" -> isPlaying
                "getPlayWhenReady" -> playWhenReady
                "getPlaybackState" -> playbackState
                "getCurrentPosition" -> positionMs
                "getCurrentMediaItemIndex" -> index
                "getMediaItemCount" -> ids.size
                "getMediaItemAt" -> MediaItem.Builder().setMediaId(ids[args[0] as Int]).build()
                "hasNextMediaItem" -> index + 1 < ids.size
                "getNextMediaItemIndex" -> if (index + 1 < ids.size) index + 1 else C.INDEX_UNSET
                "hasPreviousMediaItem" -> index > 0
                "getPreviousMediaItemIndex" -> if (index > 0) index - 1 else C.INDEX_UNSET
                "getMaxSeekToPreviousPosition" -> 3_000L
                "getVolume" -> volume
                "setVolume" -> volume = args[0] as Float
                "addListener" -> listeners += args[0] as Player.Listener
                "removeListener" -> listeners -= args[0] as Player.Listener
                "play" -> changePlayWhenReady(true)
                "pause" -> changePlayWhenReady(false)
                "prepare" -> calls += "prepare"
                "seekToNextMediaItem", "seekToNext" -> if (index + 1 < ids.size) moveTo(index + 1, 0L, method.name)
                "seekToPrevious" -> {
                    if (index > 0 && positionMs <= 3_000L) moveTo(index - 1, 0L, "seekToPrevious") else seekWithin(0L, "seekToPrevious")
                }
                "seekToPreviousMediaItem" -> if (index > 0) moveTo(index - 1, 0L, method.name)
                "seekTo" -> if (args.size == 1) {
                    seekWithin(args[0] as Long, "seekTo(${args[0]})")
                } else {
                    moveTo(args[0] as Int, args[1] as Long, "seekTo(${args[0]}, ${args[1]})")
                }
                "seekToDefaultPosition" -> if (args.isNullOrEmpty()) {
                    seekWithin(0L, "seekToDefaultPosition")
                } else {
                    moveTo(args[0] as Int, 0L, "seekToDefaultPosition(${args[0]})")
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as Player

        fun changePlayWhenReady(value: Boolean) {
            calls += if (value) "play" else "pause"
            if (playWhenReady == value) return
            val wasPlaying = isPlaying
            playWhenReady = value
            listeners.toList().forEach {
                it.onPlayWhenReadyChanged(value, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            }
            if (wasPlaying != isPlaying) listeners.toList().forEach { it.onIsPlayingChanged(isPlaying) }
        }

        fun moveTo(target: Int, position: Long, call: String) {
            calls += call
            val changed = target != index
            index = target
            positionMs = position
            if (changed) {
                listeners.toList().forEach {
                    it.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                }
            }
        }

        fun seekWithin(position: Long, call: String) {
            calls += call
            positionMs = position
        }

        /** A row added at the end, as the app's SMART top-up does. */
        fun append(id: String) {
            ids += id
            listeners.toList().forEach {
                it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            }
        }

        /** The track ran out and the player moved on by itself (gapless or a crossfade). */
        fun advanceByItself() {
            index++
            positionMs = 0L
            listeners.toList().forEach {
                it.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            }
        }
    }

    private class Rig(scope: TestScope) {
        val exo = FakeExo()
        val announced = mutableListOf<Pair<StartCause, Int>>()
        val published = mutableListOf<Boolean>()
        var fromApp = false
        var noCaller = false
        val fades = SessionTransportFades(
            player = exo.player,
            scope = scope.backgroundScope,
            nowMs = { scope.currentTime },
            announce = { cause, index -> announced += cause to index },
            publishPausePending = { published += it },
        )
        val session = TransportFadingPlayer(exo.player, fades) { !fromApp && !noCaller }
    }

    @Test
    fun anOutsidePauseLetsTheSoundLeaveBeforeThePlayerStops() = runTest {
        val rig = Rig(this)
        rig.session.pause()
        assertTrue(rig.exo.playWhenReady, "the player keeps playing while the sound leaves")
        assertTrue(rig.fades.pausePending)
        assertEquals(listOf(true), rig.published)
        advanceTimeBy(80L)
        assertTrue(rig.exo.volume in 0.01f..0.99f, "mid-fade, got ${rig.exo.volume}")
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(rig.exo.playWhenReady)
        assertFalse(rig.fades.pausePending)
        assertEquals(false, rig.published.last())
        assertEquals(1f, rig.exo.volume, "full level again for the next start")
    }

    @Test
    fun aSecondPauseDuringTheFadeKeepsItAndPlayTurnsItBack() = runTest {
        val rig = Rig(this)
        rig.session.pause()
        advanceTimeBy(60L)
        rig.session.pause()
        assertTrue(rig.exo.playWhenReady, "a double PAUSE from a car does not cut the fade")
        rig.session.play()
        assertFalse(rig.fades.pausePending)
        assertTrue(rig.exo.volume in 0.01f..0.99f, "turns back from the gain reached, not from silence")
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(rig.exo.playWhenReady, "the pause never lands")
        assertEquals(1f, rig.exo.volume)
    }

    @Test
    fun aRedundantPlayNeverDipsTheMusic() = runTest {
        val rig = Rig(this)
        rig.session.play()
        assertEquals(1f, rig.exo.volume)
        // Nor does it cut short a change on its way.
        rig.session.seekToNext()
        advanceTimeBy(75L)
        rig.session.play()
        assertTrue(rig.exo.volume < 1f)
        assertEquals(0, rig.exo.index)
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, rig.exo.index)
    }

    @Test
    fun aPausedRowResumesRisingAndAStartFromTheOpeningPlaysAtFull() = runTest {
        val rig = Rig(this)
        rig.exo.changePlayWhenReady(false)
        rig.session.play()
        assertEquals(0f, rig.exo.volume, "a resume starts from silence")
        advanceTimeBy(110L)
        assertTrue(rig.exo.volume in 0.01f..0.99f)
        advanceTimeBy(200L)
        assertEquals(1f, rig.exo.volume)

        rig.exo.changePlayWhenReady(false)
        rig.fromApp = true
        rig.session.setMediaItems(listOf(MediaItem.Builder().setMediaId("x").build()), 0, 0L)
        rig.exo.positionMs = 0L
        rig.session.play()
        assertEquals(1f, rig.exo.volume, "a new queue plays its opening as it is")
    }

    @Test
    fun outsideNextsWaitForTheSilenceAndTwoQuickOnesSkipTwoRows() = runTest {
        val rig = Rig(this)
        rig.session.seekToNext()
        runCurrent()
        assertEquals(0, rig.exo.index, "not before the sound has left")
        advanceTimeBy(100L)
        rig.session.seekToNextMediaItem()
        advanceTimeBy(60L)
        runCurrent()
        assertEquals(2, rig.exo.index)
        assertTrue(rig.exo.volume < 0.2f)
        advanceTimeBy(200L)
        assertEquals(1f, rig.exo.volume)
        assertEquals(emptyList(), rig.announced, "the history reads an outside Next from the seek")
    }

    @Test
    fun aPauseDuringAChangeRunsTheChangeFirst() = runTest {
        val rig = Rig(this)
        rig.session.seekToNext()
        advanceTimeBy(50L)
        rig.session.pause()
        assertEquals(1, rig.exo.index)
        assertTrue(rig.fades.pausePending)
        advanceTimeBy(200L)
        runCurrent()
        assertFalse(rig.exo.playWhenReady)
    }

    @Test
    fun pausedEveryCommandRunsAtOnce() = runTest {
        val rig = Rig(this)
        rig.exo.changePlayWhenReady(false)
        rig.session.seekToNext()
        rig.session.seekTo(5_000L)
        assertEquals(1, rig.exo.index)
        assertEquals(5_000L, rig.exo.positionMs)
        assertEquals(1f, rig.exo.volume)
    }

    @Test
    fun aNextWithNowhereToGoDoesNotDip() = runTest {
        val rig = Rig(this)
        rig.exo.index = 3
        rig.session.seekToNext()
        assertEquals(1f, rig.exo.volume)
        advanceTimeBy(500L)
        assertEquals(1f, rig.exo.volume)
        assertEquals(3, rig.exo.index)
    }

    @Test
    fun twoQuickPreviousPressesPastTheThresholdRestartThenGoBack() = runTest {
        val rig = Rig(this)
        rig.exo.index = 2
        rig.session.seekToPrevious()
        advanceTimeBy(50L)
        rig.session.seekToPrevious()
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(1, rig.exo.index)
        assertEquals(0L, rig.exo.positionMs)
    }

    @Test
    fun theFirstPreviousDecidesAtThePress() = runTest {
        val rig = Rig(this)
        rig.exo.index = 2
        rig.exo.positionMs = 2_950L
        rig.session.seekToPrevious()
        // The playhead runs on while the sound leaves, past the 3 s threshold.
        rig.exo.positionMs = 3_100L
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, rig.exo.index, "still the row before, as pressed")
    }

    @Test
    fun nextThenPreviousComesBack() = runTest {
        val rig = Rig(this)
        rig.exo.index = 1
        rig.session.seekToNext()
        advanceTimeBy(40L)
        rig.session.seekToPrevious()
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, rig.exo.index)
    }

    @Test
    fun anAutomaticAdvanceDuringTheFadeTakesThePlaceOfTheNext() = runTest {
        val rig = Rig(this)
        rig.session.seekToNext()
        advanceTimeBy(100L)
        rig.exo.advanceByItself()
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, rig.exo.index, "the listener already got the next track")
        advanceTimeBy(200L)
        assertEquals(1f, rig.exo.volume, "and the sound comes back")
    }

    @Test
    fun aPickFindsItsRowByIdAfterAnInsertAndIsDroppedWhenTheRowLeft() = runTest {
        val rig = Rig(this)
        rig.session.seekTo(2, 0L)
        rig.exo.ids.add(0, "new")
        advanceTimeBy(200L)
        runCurrent()
        assertEquals("c", rig.exo.ids[rig.exo.index])

        rig.session.seekToDefaultPosition(0)
        rig.exo.ids.remove("new")
        val before = rig.exo.index
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(before, rig.exo.index)
    }

    @Test
    fun aQueueInstalledDuringAPendingPauseDoesNotStartByItself() = runTest {
        val rig = Rig(this)
        rig.session.pause()
        advanceTimeBy(40L)
        rig.session.setMediaItems(listOf(MediaItem.Builder().setMediaId("x").build()))
        assertFalse(rig.exo.playWhenReady)
        assertFalse(rig.fades.pausePending)
        assertEquals(1f, rig.exo.volume)
    }

    @Test
    fun theLoadedQueueStandInTouchesNothing() = runTest {
        val rig = Rig(this)
        rig.session.pause()
        rig.session.setMediaItems(listOf(KeepLoadedQueue))
        assertTrue(rig.fades.pausePending, "a voice request for the loaded queue keeps the pause")
    }

    @Test
    fun theAppsOwnCommandsNeverWaitButComeAfterWhatWasAsked() = runTest {
        val rig = Rig(this)
        rig.session.seekToNext()
        advanceTimeBy(50L)
        rig.fromApp = true
        rig.session.seekTo(2_000L)
        assertEquals(1, rig.exo.index, "the queued Next ran first")
        assertEquals(2_000L, rig.exo.positionMs)
        advanceTimeBy(200L)
        assertEquals(1f, rig.exo.volume, "and the sound came back")

        rig.session.pause()
        assertFalse(rig.exo.playWhenReady, "the app's plain pause lands at once")
    }

    @Test
    fun withoutACallerEverythingRunsAtOnce() = runTest {
        val rig = Rig(this)
        rig.noCaller = true
        rig.session.pause()
        assertFalse(rig.exo.playWhenReady)
        assertFalse(rig.fades.pausePending)
    }

    @Test
    fun theAppsOpsWaitForTheFadeAndTellTheHistoryAtTheChange() = runTest {
        val rig = Rig(this)
        rig.fades.next(deferrable = true, appOp = true) { rig.exo.player.seekToNextMediaItem() }
        rig.fades.next(deferrable = true, appOp = true) { rig.exo.player.seekToNextMediaItem() }
        assertEquals(emptyList(), rig.announced)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(listOf(StartCause.SKIP_NEXT to 1, StartCause.SKIP_NEXT to 2), rig.announced)

        rig.fades.pick(0, 0L, deferrable = true, mediaId = "a", appOp = true)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(StartCause.USER_PICK to 0, rig.announced.last())
        assertEquals(0, rig.exo.index)
    }

    /** The tail wait is SMART's: it plans the row after the tail. */
    private fun smartTest(body: suspend TestScope.() -> Unit) = runTest {
        AndroidShuffleModeRegistry.set(ShuffleMode.SMART)
        try {
            body()
        } finally {
            AndroidShuffleModeRegistry.set(ShuffleMode.OFF)
        }
    }

    @Test
    fun outsideSmartANextAtTheTailWaitsForNothing() = runTest {
        val rig = Rig(this)
        rig.exo.index = 3
        rig.fades.next(deferrable = true, appOp = true) { rig.exo.player.seekToNextMediaItem() }
        advanceTimeBy(200L)
        runCurrent()
        // Added to the queue by hand: not where the earlier Next was going.
        rig.exo.append("e")
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(3, rig.exo.index)
    }

    @Test
    fun aQueueEditByTheListenerDropsTheNextWaitingAtTheTail() = smartTest {
        val rig = Rig(this)
        rig.exo.index = 3
        rig.fades.next(deferrable = true, appOp = true) { rig.exo.player.seekToNextMediaItem() }
        advanceTimeBy(200L)
        runCurrent()
        rig.fades.dropNextAtTail()
        rig.exo.append("e")
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(3, rig.exo.index)
    }

    @Test
    fun theAppsNextBehindAPickOfTheLastRowWaitsForThePlannedRow() = smartTest {
        val rig = Rig(this)
        rig.fades.pick(3, 0L, deferrable = true, mediaId = "d", appOp = true)
        rig.fades.next(deferrable = true, appOp = true) { rig.exo.player.seekToNextMediaItem() }
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(3, rig.exo.index, "nothing after the picked row yet")
        assertEquals(StartCause.USER_PICK to 3, rig.announced.last())
        advanceTimeBy(500L)
        rig.exo.append("e")
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(4, rig.exo.index, "the Next lands on the row planned after the pick")
        assertEquals(StartCause.SKIP_NEXT to 4, rig.announced.last())
    }

    @Test
    fun aRowQueuedLongAfterANextAtTheTailIsNotSkippedTo() = smartTest {
        val rig = Rig(this)
        rig.exo.index = 3
        rig.fades.next(deferrable = true, appOp = true) { rig.exo.player.seekToNextMediaItem() }
        advanceTimeBy(200L)
        runCurrent()
        // SMART had nothing; the listener queues a song by hand a minute later.
        advanceTimeBy(60_000L)
        rig.exo.append("e")
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(3, rig.exo.index)
    }

    @Test
    fun twoQuickAppNextsAtTheTailWaitForThePlannedRow() = smartTest {
        val rig = Rig(this)
        rig.exo.index = 2
        rig.fades.next(deferrable = true, appOp = true) { rig.exo.player.seekToNextMediaItem() }
        rig.fades.next(deferrable = true, appOp = true) { rig.exo.player.seekToNextMediaItem() }
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(3, rig.exo.index)
        rig.exo.append("e")
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(4, rig.exo.index)
    }

    @Test
    fun aPauseDropsTheNextWaitingAtTheTail() = smartTest {
        val rig = Rig(this)
        rig.exo.index = 3
        rig.fades.next(deferrable = true, appOp = true) { rig.exo.player.seekToNextMediaItem() }
        rig.session.pause()
        advanceTimeBy(200L)
        runCurrent()
        rig.exo.append("e")
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(3, rig.exo.index)
        assertFalse(rig.exo.playWhenReady)
    }

    @Test
    fun aSystemPauseDuringThePicksFadeKeepsThePickedRowPaused() = runTest {
        val rig = Rig(this)
        rig.fades.pick(2, 0L, deferrable = true, mediaId = "c", appOp = true)
        advanceTimeBy(60L)
        // Becoming noisy: ExoPlayer pauses itself, not through the session player.
        rig.exo.changePlayWhenReady(false)
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(2, rig.exo.index, "the row is still picked")
        assertFalse(rig.exo.playWhenReady, "but the unplug's pause holds")
    }

    @Test
    fun theBaseVolumeScalesTheEnvelope() = runTest {
        val rig = Rig(this)
        rig.session.volume = 0.5f
        assertEquals(0.5f, rig.exo.volume)
        assertEquals(0.5f, rig.session.volume)
        rig.session.pause()
        advanceTimeBy(80L)
        assertTrue(rig.exo.volume < 0.5f)
        rig.session.play()
        advanceTimeBy(500L)
        assertEquals(0.5f, rig.exo.volume)
        rig.session.mute()
        assertEquals(0f, rig.exo.volume)
        rig.session.unmute()
        assertEquals(0.5f, rig.exo.volume)
    }
}
