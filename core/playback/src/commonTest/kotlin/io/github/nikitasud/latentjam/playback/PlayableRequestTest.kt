/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `a search with no words asks for anything`() {
        // How a voice assistant sends "play music on LatentJam".
        assertTrue(asksForAnything(listOf(RequestedRow(mediaId = "", searchQuery = ""))))
    }

    @Test
    fun `a search of nothing but spaces asks for anything too`() {
        assertTrue(asksForAnything(listOf(RequestedRow(mediaId = "", searchQuery = " \t "))))
    }

    @Test
    fun `a search with words asks for what they find`() {
        assertFalse(asksForAnything(listOf(RequestedRow(mediaId = "", searchQuery = "holy diver"))))
    }

    @Test
    fun `a row that names a track asks for that track whatever it searches for`() {
        assertFalse(asksForAnything(listOf(RequestedRow(mediaId = "22", searchQuery = ""))))
    }

    @Test
    fun `a row that does not search asks for the track it names even when it names none`() {
        assertFalse(asksForAnything(listOf(RequestedRow(mediaId = "", searchQuery = null))))
    }

    @Test
    fun `a row that carries its own audio plays that audio`() {
        val complete = RequestedRow(mediaId = "", searchQuery = "", carriesAudio = true)
        assertFalse(asksForAnything(listOf(complete)))
    }

    @Test
    fun `a request for no rows asks for an empty queue and not for anything`() {
        assertFalse(asksForAnything(emptyList()))
    }

    @Test
    fun `a request asks for anything only when every row does`() {
        val anything = RequestedRow(mediaId = "", searchQuery = "")
        val track = RequestedRow(mediaId = "22", searchQuery = null)
        val blank = RequestedRow(mediaId = " ", searchQuery = " ")
        assertFalse(asksForAnything(listOf(anything, track)))
        assertTrue(asksForAnything(listOf(anything, blank)))
    }
}
