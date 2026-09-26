/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertContentEquals

internal class LibraryCatalogAlbumOrderTest {

    private fun track(
        id: String,
        title: String,
        album: String?,
        trackNumber: Int? = null,
        discNumber: Int? = null,
    ) = TrackDescriptor(
        id = TrackId(id),
        title = title,
        artist = "Artist",
        album = album,
        trackNumber = trackNumber,
        discNumber = discNumber,
    )

    @Test
    fun albumTracksFollowTheirTrackNumbersNotTheirTitles() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", "Zapata", album = "Record", trackNumber = 1),
                track("2", "Bulls on Parade", album = "Record", trackNumber = 3),
                track("3", "Mic Check", album = "Record", trackNumber = 2),
            ),
        )
        assertContentEquals(
            listOf("Zapata", "Mic Check", "Bulls on Parade"),
            catalog.albums.single().tracks.map { it.title },
        )
    }

    @Test
    fun discsPlayInOrderAndAnUntaggedDiscCountsAsTheFirst() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", "Second disc opener", album = "Set", trackNumber = 1, discNumber = 2),
                track("2", "First disc closer", album = "Set", trackNumber = 9, discNumber = 1),
                track("3", "Half-tagged opener", album = "Set", trackNumber = 1),
            ),
        )
        assertContentEquals(
            listOf("Half-tagged opener", "First disc closer", "Second disc opener"),
            catalog.albums.single().tracks.map { it.title },
        )
    }

    @Test
    fun unnumberedTracksFollowTheNumberedOnesByTitle() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", "Bonus B", album = "Record"),
                track("2", "Bonus A", album = "Record"),
                track("3", "Last", album = "Record", trackNumber = 2),
                track("4", "First", album = "Record", trackNumber = 1),
            ),
        )
        assertContentEquals(
            listOf("First", "Last", "Bonus A", "Bonus B"),
            catalog.albums.single().tracks.map { it.title },
        )
    }

    @Test
    fun albumsShelveLeadingArticlesUnderTheNextWord() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", "One", album = "The Battle of Los Angeles"),
                track("2", "Two", album = "The Battle of Los Angeles"),
                track("3", "Three", album = "Evil Empire"),
                track("4", "Four", album = "Evil Empire"),
                track("5", "Five", album = "Abbey Road"),
                track("6", "Six", album = "Abbey Road"),
            ),
        )
        assertContentEquals(
            listOf("Abbey Road", "The Battle of Los Angeles", "Evil Empire"),
            catalog.albums.map { it.title },
        )
    }

    @Test
    fun realAlbumsComeBeforeSingleTrackAlbums() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", "One", album = "Aardvark Single"),
                track("2", "Two", album = "Zebra Album"),
                track("3", "Three", album = "Zebra Album"),
                track("4", "Four", album = "Beta Album"),
                track("5", "Five", album = "Beta Album"),
                track("6", "Six", album = "Middle Single"),
            ),
        )
        // Multi-track albums first, alphabetical inside each block.
        assertContentEquals(
            listOf("Beta Album", "Zebra Album", "Aardvark Single", "Middle Single"),
            catalog.albums.map { it.title },
        )
    }
}
