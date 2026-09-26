/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import io.github.nikitasud.latentjam.smart.TrackId
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class AndroidPlaybackStartsTest {

    /** A queue change is read without consulting the player, so any call on it fails the test. */
    private val untouchedPlayer = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ -> error("unexpected Player.${method.name}") } as Player

    @Test
    fun aQueueSetFromOutsideTheAppPlaysItsStartAsTheListenersPick() {
        // Android Auto's browse tree and voice requests send one item and leave the start row unset.
        val picked = MediaItem.Builder().setMediaId("picked-in-the-car").build()

        AndroidPlaybackStarts.announceExternalQueue(listOf(picked), C.INDEX_UNSET, shuffled = false)
        AndroidPlaybackStarts.onMediaItemTransition(
            untouchedPlayer,
            picked,
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        )

        val start = AndroidPlaybackStarts.current.value
        assertEquals(TrackId("picked-in-the-car"), start?.trackId)
        assertEquals(StartCause.USER_PICK, start?.cause)
    }

    @Test
    fun aQueueSetFromOutsideTheAppTellsTheAppItsOwnSourceNoLongerApplies() {
        var told = 0
        MediaBrowseRegistry.onExternalQueue = { told++ }
        try {
            // Shuffle drew the start row, so no start can be named; the queue is still not the app's.
            val rows = listOf("outside-a", "outside-b").map { MediaItem.Builder().setMediaId(it).build() }
            AndroidPlaybackStarts.announceExternalQueue(rows, C.INDEX_UNSET, shuffled = true)

            assertEquals(1, told)
        } finally {
            MediaBrowseRegistry.onExternalQueue = null
        }
    }

    @Test
    fun tappingThePlayingRowRecordsItsReplayWithoutAMediaItemTransition() {
        val item = MediaItem.Builder().setMediaId("replayed-queue-row").build()
        AndroidPlaybackStarts.onMediaItemTransition(untouchedPlayer, item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        val before = assertNotNull(AndroidPlaybackStarts.current.value)
        AndroidPlaybackStarts.announce(StartCause.USER_PICK, TrackId(item.mediaId))

        AndroidPlaybackStarts.onPositionDiscontinuity(
            position(item, 40_000), position(item, 0), Player.DISCONTINUITY_REASON_SEEK,
        )

        val replay = assertNotNull(AndroidPlaybackStarts.current.value)
        assertEquals(StartCause.USER_PICK, replay.cause)
        assertTrue(replay.sequence > before.sequence)
        // The announcement was consumed. A later ordinary seek is still this same instance.
        AndroidPlaybackStarts.onPositionDiscontinuity(
            position(item, 10_000), position(item, 0), Player.DISCONTINUITY_REASON_SEEK,
        )
        assertEquals(replay, AndroidPlaybackStarts.current.value)
    }

    @Test
    fun anOrdinarySeekDoesNotStartANewPlaybackInstance() {
        val item = MediaItem.Builder().setMediaId("seek-within-row").build()
        AndroidPlaybackStarts.onMediaItemTransition(untouchedPlayer, item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        val before = AndroidPlaybackStarts.current.value
        AndroidPlaybackStarts.onPositionDiscontinuity(
            position(item, 40_000), position(item, 0), Player.DISCONTINUITY_REASON_SEEK,
        )
        assertEquals(before, AndroidPlaybackStarts.current.value)
    }

    @Test
    fun aPickBetweenRowsWaitsForTheTransitionBeforeRecording() {
        val previous = MediaItem.Builder().setMediaId("previous-row").build()
        val picked = MediaItem.Builder().setMediaId("next-picked-row").build()
        AndroidPlaybackStarts.onMediaItemTransition(untouchedPlayer, previous, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        val before = AndroidPlaybackStarts.current.value
        AndroidPlaybackStarts.announce(StartCause.USER_PICK, TrackId(picked.mediaId))
        AndroidPlaybackStarts.onPositionDiscontinuity(
            position(previous, 40_000), position(picked, 0, row = 1), Player.DISCONTINUITY_REASON_SEEK,
        )
        assertEquals(before, AndroidPlaybackStarts.current.value)
        val player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, _ ->
            if (method.name == "getCurrentTimeline") Timeline.EMPTY else error("unexpected Player.${method.name}")
        } as Player
        AndroidPlaybackStarts.onMediaItemTransition(
            player, picked, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
        )
        assertEquals(TrackId(picked.mediaId), AndroidPlaybackStarts.current.value?.trackId)
        assertEquals(StartCause.USER_PICK, AndroidPlaybackStarts.current.value?.cause)
    }

    private fun position(item: MediaItem, ms: Long, row: Int = 0) = Player.PositionInfo(
        /* windowUid = */ null,
        /* mediaItemIndex = */ row,
        /* mediaItem = */ item,
        /* periodUid = */ null,
        /* periodIndex = */ row,
        /* positionMs = */ ms,
        /* contentPositionMs = */ ms,
        /* adGroupIndex = */ C.INDEX_UNSET,
        /* adIndexInAdGroup = */ C.INDEX_UNSET,
    )
}
