/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PlayableRequestTest {

    private val a = TrackId("a")
    private val b = TrackId("b")
    private val c = TrackId("c")

    @Test
    fun `a request that can play in full is passed on as it came`() {
        assertEquals(
            PlayableRequest(listOf(a, b, c), startIndex = 1, startPositionMs = 5_000L),
            playableRequest(listOf(a, b, c), startIndex = 1, startPositionMs = 5_000L),
        )
    }

    @Test
    fun `a request with nothing that can play is refused`() {
        // Play from search with no match, an unknown id, or no library to look in yet.
        assertNull(playableRequest(listOf<TrackId?>(null)))
        assertNull(playableRequest(listOf<TrackId?>(null, null), startIndex = 1, startPositionMs = 0L))
    }

    @Test
    fun `a request for no rows at all still asks for an empty queue`() {
        assertEquals(
            PlayableRequest(emptyList<TrackId>(), startIndex = null, startPositionMs = null),
            playableRequest(emptyList<TrackId?>()),
        )
    }

    @Test
    fun `the start follows its row past the rows left out`() {
        // Row 3 is b. The two rows before it that cannot play leave b at row 1.
        assertEquals(
            PlayableRequest(listOf(a, b, c), startIndex = 1, startPositionMs = 5_000L),
            playableRequest(listOf(null, a, null, b, c), startIndex = 3, startPositionMs = 5_000L),
        )
    }

    @Test
    fun `a start that cannot play moves on to the next row that can and starts it from the beginning`() {
        // Row 2 cannot play, so b, the next row that can, starts instead.
        assertEquals(
            PlayableRequest(listOf(a, b, c), startIndex = 1, startPositionMs = null),
            playableRequest(listOf(a, null, null, b, c), startIndex = 2, startPositionMs = 5_000L),
        )
    }

    @Test
    fun `a start with nothing playable after it moves back to the last row that can play`() {
        assertEquals(
            PlayableRequest(listOf(a, b), startIndex = 1, startPositionMs = null),
            playableRequest(listOf(a, null, b, null, null), startIndex = 3, startPositionMs = 5_000L),
        )
    }

    @Test
    fun `a start past the end of the request lands on its last row`() {
        // A controller that keeps its position gets the playing queue's row number from Media3,
        // which can lie past the end of the list it sends.
        assertEquals(
            PlayableRequest(listOf(a), startIndex = 0, startPositionMs = null),
            playableRequest(listOf(a), startIndex = 4, startPositionMs = 5_000L),
        )
    }

    @Test
    fun `a request that leaves the start to the player still does`() {
        assertEquals(
            PlayableRequest(listOf(a, b), startIndex = null, startPositionMs = null),
            playableRequest(listOf(a, null, b)),
        )
    }
}
