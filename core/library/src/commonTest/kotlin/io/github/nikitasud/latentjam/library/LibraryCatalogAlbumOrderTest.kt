/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class LibraryCatalogAlbumOrderTest {

    private fun track(
        id: String,
        title: String,
        album: String?,
        trackNumber: Int? = null,
        discNumber: Int? = null,
        folderPath: String? = null,
        year: Int? = null,
    ) = TrackDescriptor(
        id = TrackId(id),
        title = title,
        artist = "Artist",
        album = album,
        trackNumber = trackNumber,
        discNumber = discNumber,
        folderPath = folderPath,
        year = year,
    )

    private fun LibraryCatalog.albumTitles(): List<List<String?>> = albums.map { album -> album.tracks.map { it.title } }

    @Test
    fun twoReleasesWithOneTitleAndArtistStayTwoAlbums() {
        // Weezer's Blue and Green albums are both called "Weezer" and both start at track one.
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", "My Name Is Jonas", album = "Weezer", trackNumber = 1, folderPath = "Music/Weezer/Blue"),
                track("2", "Don't Let Go", album = "Weezer", trackNumber = 1, folderPath = "Music/Weezer/Green"),
                track("3", "No One Else", album = "Weezer", trackNumber = 2, folderPath = "Music/Weezer/Blue"),
                track("4", "Photograph", album = "Weezer", trackNumber = 2, folderPath = "Music/Weezer/Green"),
            ),
        )
        assertEquals(
            setOf(listOf("My Name Is Jonas", "No One Else"), listOf("Don't Let Go", "Photograph")),
            catalog.albumTitles().toSet(),
        )
        assertEquals(2, catalog.albums.map { it.key }.toSet().size)
    }

    @Test
    fun releasesSharingAFolderSeparateByYear() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", "My Name Is Jonas", album = "Weezer", trackNumber = 1, folderPath = "Music", year = 1994),
                track("2", "Don't Let Go", album = "Weezer", trackNumber = 1, folderPath = "Music", year = 2001),
                track("3", "No One Else", album = "Weezer", trackNumber = 2, folderPath = "Music", year = 1994),
                track("4", "Photograph", album = "Weezer", trackNumber = 2, folderPath = "Music", year = 2001),
            ),
        )
        assertEquals(
            setOf(listOf("My Name Is Jonas", "No One Else"), listOf("Don't Let Go", "Photograph")),
            catalog.albumTitles().toSet(),
        )
    }

    @Test
    fun aTwoDiscSetStoredPerDiscStaysOneAlbum() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", "Disc two opener", album = "Set", trackNumber = 1, discNumber = 2, folderPath = "Set/CD2"),
                track("2", "Disc one opener", album = "Set", trackNumber = 1, discNumber = 1, folderPath = "Set/CD1"),
                track("3", "Disc one closer", album = "Set", trackNumber = 2, discNumber = 1, folderPath = "Set/CD1"),
            ),
        )
        assertEquals(
            listOf(listOf("Disc one opener", "Disc one closer", "Disc two opener")),
            catalog.albumTitles(),
        )
    }

    @Test
    fun anArtistPageSeparatesTheReleasesTheAlbumListSeparates() {
        val weezer = listOf(
            track("1", "My Name Is Jonas", album = "Weezer", trackNumber = 1, folderPath = "Blue"),
            track("2", "Don't Let Go", album = "Weezer", trackNumber = 1, folderPath = "Green"),
        )
        assertEquals(2, LibraryCatalog.separateReleases(weezer).size)
        val oneRecord = listOf(track("1", "A", album = "Record", trackNumber = 1), track("2", "B", album = "Record", trackNumber = 2))
        assertEquals(listOf(oneRecord), LibraryCatalog.separateReleases(oneRecord))
    }

    @Test
    fun copiesThatNothingTellsApartStayOneAlbum() {
        val catalog = LibraryCatalog.build(
            listOf(
                track("1", "Song", album = "Record", trackNumber = 1, folderPath = "Music", year = 2001),
                track("2", "Song", album = "Record", trackNumber = 1, folderPath = "Music", year = 2001),
            ),
        )
        assertEquals(1, catalog.albums.size)
    }

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
    fun unnumberedAlbumTracksUseTheSameTitleOrderAsTheAlphabetRail() {
        val tracks = listOf(
            track("1", "The Beginning", album = "Record"),
            track("2", "End", album = "Record"),
            track("3", "A Middle", album = "Record"),
            track("4", "(Opening)", album = "Record"),
        )
        val expected = listOf("The Beginning", "End", "A Middle", "(Opening)")
        assertContentEquals(expected, LibraryCatalog.build(tracks).albums.single().tracks.map { it.title })
        assertContentEquals(expected, LibraryCatalog.inAlbumOrder(tracks).map { it.title })
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
