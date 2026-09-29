/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AlbumGroup
import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class AlbumRailTest {

    private fun album(key: String, title: String?, artist: String = "Artist", year: Int? = null) = AlbumGroup(
        key = key,
        title = title,
        artist = artist,
        artworkUri = null,
        tracks = listOf(TrackDescriptor(id = TrackId("$key-1"), year = year)),
    )

    @Test
    fun albumsUseOneGlobalAlphabetInsteadOfSeparateAlbumAndSingleBlocks() {
        val ordered = albumRailSections(
            listOf(
                album("z", "Zebra"),
                album("a", "Aardvark"),
                album("b", "(Beta)"),
                album("unknown", null),
            ),
        ).flatMap { it.albums }

        assertContentEquals(
            listOf("Aardvark", "(Beta)", "Zebra", null),
            ordered.map { it.title },
        )
    }

    @Test
    fun fullSpanHeadersProduceExactGridAnchors() {
        val sections = albumRailSections(
            listOf(
                album("b2", "Bravo Two"),
                album("a", "Alpha"),
                album("b1", "Bravo One"),
                album("c", "Charlie"),
            ),
        )

        assertContentEquals(listOf("A", "B", "C"), sections.map { it.bucket })
        // A: header + one card, B: header + two cards, then C's header.
        assertContentEquals(listOf(0, 2, 5), sections.map { it.emitStartIndex })
        assertEquals(listOf("Bravo One", "Bravo Two"), sections[1].albums.map { it.title })
    }

    @Test
    fun theRailFollowsTheChosenSort() {
        val albums = listOf(
            album("a", "Alpha", artist = "Zed", year = 1990),
            album("b", "Bravo", artist = "Abe", year = 2010),
            album("c", "Charlie", artist = "Abe", year = null),
        )

        val byTitleDescending = albumRailSections(albums, SortChoice(AlbumSort.TITLE, SongSortDirection.DESCENDING))
        assertContentEquals(listOf("C", "B", "A"), byTitleDescending.map { it.bucket })

        val byArtist = albumRailSections(albums, SortChoice(AlbumSort.ARTIST, SongSortDirection.ASCENDING))
        assertContentEquals(listOf("A", "Z"), byArtist.map { it.bucket })
        assertContentEquals(listOf(0, 3), byArtist.map { it.emitStartIndex })

        // Years are not letters: one unlabeled section, which the grid shows without a rail.
        val byYear = albumRailSections(albums, SortChoice(AlbumSort.YEAR, SongSortDirection.DESCENDING))
        assertContentEquals(listOf(""), byYear.map { it.bucket })
        assertContentEquals(listOf("b", "a", "c"), byYear.single().albums.map { it.key })
    }
}
