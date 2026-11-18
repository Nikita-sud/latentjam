/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ExpandedPickTest {

    private val library = listOf("a", "b", "c", "d", "e")
    private val playlist = listOf("d", "b", "e")

    private fun pick(
        requested: List<RequestedRow>,
        fromApp: Boolean = false,
        mode: ShuffleMode = ShuffleMode.OFF,
        listed: List<String>? = playlist,
        search: (String) -> List<String> = { error("no search asked for") },
    ): ExpandedPick<String>? = expandedPick(fromApp, requested, mode, listed, library, idOf = { it }, search = search)

    private fun row(id: String) = RequestedRow(mediaId = id, searchQuery = null)

    @Test
    fun aRowTappedInTheCarPlaysTheListItWasShownInFromThatRow() {
        val queue = checkNotNull(pick(listOf(row("b"))))

        assertEquals(playlist, queue.rows)
        assertEquals(1, queue.startIndex)
    }

    @Test
    fun aTrackTheShownListDoesNotHoldPlaysFromItsPlaceAmongAllTracks() {
        val queue = checkNotNull(pick(listOf(row("c"))))

        assertEquals(library, queue.rows)
        assertEquals(2, queue.startIndex)

        // Nothing was listed for this controller at all: the same.
        assertEquals(library, checkNotNull(pick(listOf(row("c")), listed = null)).rows)
    }

    @Test
    fun aSpokenRequestPlaysEveryMatchBestFirst() {
        val spoken = RequestedRow(mediaId = "", searchQuery = "some artist")

        val queue = checkNotNull(pick(listOf(spoken), search = { words -> listOf("e", "a").takeIf { words == "some artist" }.orEmpty() }))

        assertEquals(listOf("e", "a"), queue.rows)
        assertEquals(0, queue.startIndex)
    }

    @Test
    fun aRequestFromTheAppsOwnControllerIsNeverChanged() {
        // One row with no audio of its own is exactly what would be expanded for any other controller.
        assertNull(pick(listOf(row("b")), fromApp = true))
        assertNull(pick(listOf(RequestedRow("", "some artist")), fromApp = true, search = { listOf("e", "a") }))
        for (mode in ShuffleMode.entries) assertNull(pick(listOf(row("b")), fromApp = true, mode = mode))
    }

    @Test
    fun inSmartTheTrackPlaysAloneAndSmartBuildsOnIt() {
        assertNull(pick(listOf(row("b")), mode = ShuffleMode.SMART))
        assertNull(pick(listOf(RequestedRow("", "some artist")), mode = ShuffleMode.SMART, search = { listOf("e", "a") }))
        // Shuffled, the list is the queue: the player starts on the pick and shuffles the rest.
        assertEquals(playlist, checkNotNull(pick(listOf(row("b")), mode = ShuffleMode.ON)).rows)
    }

    @Test
    fun aRequestThatIsNotOneBareRowPlaysAsItCame() {
        assertNull(pick(emptyList()))
        assertNull(pick(listOf(row("b"), row("d"))))
        assertNull(pick(listOf(RequestedRow(mediaId = "b", searchQuery = null, carriesAudio = true))))
    }

    @Test
    fun aTrackNoListHoldsAndAListOfThatTrackAlonePlayAsTheyCame() {
        assertNull(pick(listOf(row("unknown"))))
        assertNull(expandedPick(false, listOf(row("a")), ShuffleMode.OFF, null, listOf("a"), idOf = { it }, search = { emptyList() }))
        assertNull(pick(listOf(RequestedRow("", "one match")), search = { listOf("a") }))
        assertNull(pick(listOf(RequestedRow("", " ")), search = { listOf("a", "b") }))
    }

    @Test
    fun anUnknownIdWithWordsFallsBackToTheWordsAsThePlainPathDoes() {
        val queue = checkNotNull(pick(listOf(RequestedRow("stale-id", "some artist")), search = { listOf("e", "a") }))

        assertEquals(listOf("e", "a"), queue.rows)
    }
}
