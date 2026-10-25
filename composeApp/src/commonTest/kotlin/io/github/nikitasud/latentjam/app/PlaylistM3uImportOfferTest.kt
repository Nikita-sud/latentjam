/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
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

    @Test
    fun severalFilesAskForTheLibrarysPathsOnce() = runTest {
        var asked = 0
        val documents = listOf(
            ImportedLocalDocument("#EXTM3U\nMusic/a.mp3\n", "a.m3u"),
            ImportedLocalDocument("#EXTM3U\nMusic/b.mp3\n", "b.m3u"),
            ImportedLocalDocument("#EXTM3U\nMusic/c.mp3\n", "c.m3u"),
        )

        val imports = importM3uFiles(documents, listOf(track("1"))) {
            asked++
            emptyMap()
        }

        assertEquals(listOf("a", "b", "c"), imports.map { it.name })
        assertEquals(1, asked)
    }

    @Test
    fun aFailedPathLookupIsNotRetriedForEveryFile() = runTest {
        var asked = 0
        val documents = List(3) { ImportedLocalDocument("#EXTM3U\nMusic/$it.mp3\n", "$it.m3u") }

        val imports = importM3uFiles(documents, listOf(track("1"))) {
            asked++
            error("MediaStore is unavailable")
        }

        assertEquals(3, imports.size)
        assertEquals(1, asked)
    }

    @Test
    fun aFailurePartWayKeepsAndCountsThePlaylistsAlreadyMade() = runTest {
        val third = M3uImport("Third", entryCount = 1, matchedEntryCount = 1, tracks = listOf(track("4")))
        val made = mutableListOf<String>()

        val result = createM3uPlaylists(M3uImportOffer(listOf(rock, fun2000, third)), "Imported") { name, _ ->
            if (name == "2000 Fun") error("Disk full")
            made += name
        }

        assertEquals(listOf("2000s rock"), made)
        assertEquals(1, result.created)
        assertTrue(result.failed)
    }

    @Test
    fun everyFileBecomesAPlaylistWhenNothingFails() = runTest {
        val nameless = M3uImport(null, entryCount = 1, matchedEntryCount = 1, tracks = listOf(track("4")))
        val made = mutableListOf<Pair<String, List<TrackId>>>()

        val result = createM3uPlaylists(M3uImportOffer(listOf(rock, nothingFound, nameless)), "Imported") { name, ids ->
            made += name to ids
        }

        assertEquals(listOf("2000s rock" to listOf(TrackId("1"), TrackId("2")), "Imported" to listOf(TrackId("4"))), made)
        assertEquals(2, result.created)
        assertFalse(result.failed)
    }
}
