/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.nikitasud.latentjam.smart.TrackId
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
