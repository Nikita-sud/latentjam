/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryCatalogFolderOrderTest {

    private fun track(
        id: String,
        file: String? = null,
        title: String? = null,
        disc: Int? = null,
        number: Int? = null,
        folder: String = "Music/Album",
    ) = TrackDescriptor(
        id = TrackId(id),
        title = title ?: id,
        fileName = file,
        discNumber = disc,
        trackNumber = number,
        folderPath = folder,
    )

    private fun List<TrackDescriptor>.ids() = map { it.id.value }

    @Test
    fun anAlbumFolderPlaysByDiscThenTrackNumberNotByTitle() {
        val tracks = listOf(
            track("b", title = "Alpha", disc = 2, number = 1),
            track("c", title = "Zulu", disc = 1, number = 2),
            track("a", title = "Mike", disc = 1, number = 1),
        )
        assertEquals(listOf("a", "c", "b"), LibraryCatalog.inFolderOrder(tracks).ids())
    }

    @Test
    fun untaggedFilesListAsAFileExplorerDoesWithNumbersByValue() {
        val tracks = listOf(
            track("ten", file = "10 Outro.mp3", title = "Outro"),
            track("two", file = "2 Intro.mp3", title = "Intro"),
            track("one", file = "01 Prelude.mp3", title = "Prelude"),
        )
        assertEquals(listOf("one", "two", "ten"), LibraryCatalog.inFolderOrder(tracks).ids())
    }

    @Test
    fun numberedTracksComeBeforeUnnumberedOnesAndAMissingDiscCountsAsOne() {
        val tracks = listOf(
            track("loose", file = "00 bonus.mp3"),
            track("d2", disc = 2, number = 1),
            track("noDisc", number = 2),
            track("d1", disc = 1, number = 1),
        )
        assertEquals(listOf("d1", "noDisc", "d2", "loose"), LibraryCatalog.inFolderOrder(tracks).ids())
    }

    @Test
    fun theFlatFolderListUsesTheSameOrder() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("10", file = "Track 10.flac", title = "A"),
                track("9", file = "Track 9.flac", title = "B"),
            ),
        )
        assertEquals(listOf("9", "10"), catalog.folders.single().tracks.ids())
    }

    @Test
    fun naturalComparisonCountsDigitRunsByValueAndIgnoresCase() {
        assertTrue(compareNatural("2 Intro", "10 Outro") < 0)
        assertTrue(compareNatural("track02", "Track2") != 0)
        assertEquals(0, compareNatural("same", "same"))
        assertTrue(compareNatural("abc", "ABD") < 0)
        assertTrue(compareNatural("disc 1 - 9", "disc 1 - 10") < 0)
        assertTrue(compareNatural("song", "song 2") < 0)
    }
}
