/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

internal class LoadedQueueKeeperTest {

    /** Every call that reaches the wrapped player, with its arguments. */
    private val calls = mutableListOf<List<Any?>>()

    private val keeper = LoadedQueueKeeper(
        Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            calls += listOf(method.name) + args.orEmpty()
            null
        } as Player,
    )

    @Test
    fun aSetOfTheLoadedQueueLeavesThePlayerAlone() {
        // Media3 picks the setter; the stand-in has no audio, so reaching ExoPlayer would throw.
        keeper.setMediaItem(KeepLoadedQueue)
        keeper.setMediaItem(KeepLoadedQueue, true)
        keeper.setMediaItem(KeepLoadedQueue, 5_000L)
        keeper.setMediaItems(listOf(KeepLoadedQueue))
        keeper.setMediaItems(listOf(KeepLoadedQueue), true)
        keeper.setMediaItems(listOf(KeepLoadedQueue), 0, 5_000L)

        assertEquals(emptyList(), calls)
    }

    @Test
    fun anyOtherQueueReachesThePlayerAsItCame() {
        val queue = listOf(
            MediaItem.Builder().setMediaId("18").build(),
            MediaItem.Builder().setMediaId("22").build(),
        )

        keeper.setMediaItems(queue, 1, 5_000L)
        keeper.setMediaItem(queue[0], true)

        assertEquals<List<List<Any?>>>(
            listOf(
                listOf("setMediaItems", queue, 1, 5_000L),
                listOf("setMediaItem", queue[0], true),
            ),
            calls,
        )
    }
}
