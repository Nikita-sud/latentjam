/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Several playlist files picked at once (#21): one offer, one playlist per file. */
internal class PlaylistM3uImportOfferTest {
    private fun track(id: String) = TrackDescriptor(id = TrackId(id), audioUri = "content://media/external/audio/media/$id")

    private val rock = M3uImport("2000s rock", entryCount = 3, matchedEntryCount = 2, tracks = listOf(track("1"), track("2")))
    private val fun2000 = M3uImport("2000 Fun", entryCount = 2, matchedEntryCount = 2, tracks = listOf(track("2"), track("3")))
    private val nothingFound = M3uImport("Elsewhere", entryCount = 4, matchedEntryCount = 0, tracks = emptyList())

    @Test
    fun oneFileKeepsItsOwnNameAndWording() {
        val offer = M3uImportOffer(rock)

        assertTrue(offer.isSingleFile)
        assertEquals("2000s rock", offer.name)
        assertEquals(listOf(rock), offer.playlists)
    }

    @Test
    fun severalFilesAddUpTheirRowsAndEachBecomesItsOwnPlaylist() {
        val offer = M3uImportOffer(listOf(rock, nothingFound, fun2000))

        assertFalse(offer.isSingleFile)
        assertNull(offer.name)
        assertEquals(9, offer.entryCount)
        assertEquals(4, offer.matchedEntryCount)
        // A file none of whose rows was found makes no empty playlist, but its rows still count.
        assertEquals(listOf("2000s rock", "2000 Fun"), offer.playlists.map { it.name })
    }

    @Test
    fun favoritesTakeEveryFoundTrackOnceInTheFilesOrder() {
        val offer = M3uImportOffer(listOf(rock, fun2000))

        assertEquals(listOf(TrackId("1"), TrackId("2"), TrackId("3")), offer.favoriteIds)
    }

    @Test
    fun anOfferNeedsAFile() {
        assertFailsWith<IllegalArgumentException> { M3uImportOffer(emptyList<M3uImport>()) }
    }
}
