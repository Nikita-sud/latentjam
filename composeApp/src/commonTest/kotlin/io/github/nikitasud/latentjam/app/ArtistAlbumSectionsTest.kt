/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class ArtistAlbumSectionsTest {

    private fun track(
        id: String,
        album: String?,
        year: Int? = null,
        originalYear: Int? = null,
        trackNumber: Int? = null,
        folderPath: String? = null,
    ) = TrackDescriptor(
        id = TrackId(id),
        title = id,
        artist = "Artist",
        album = album,
        year = year,
        originalYear = originalYear,
        trackNumber = trackNumber,
        folderPath = folderPath,
    )

    /** An artist's tracks as the catalog hands them over: by title, not by album. */
    private val discography = listOf(
        track("a-second", album = "Debut", year = 1999, trackNumber = 2),
        track("b-first", album = "Debut", year = 1999, trackNumber = 1),
        track("c-single", album = "Comeback", year = 2021, trackNumber = 1),
        track("d-remaster", album = "The Classic", year = 2011, originalYear = 2003, trackNumber = 1),
        track("e-loose", album = null),
    )

    private fun titles(order: SortChoice<AlbumSort>) =
        artistAlbumSections(discography, order, unknownAlbum = "Unknown album").map { it.title }

    @Test
    fun theDefaultIsTheDiscographyNewestFirstWithUndatedLast() {
        assertContentEquals(
            listOf("Comeback", "The Classic", "Debut", "Unknown album"),
            titles(DEFAULT_ARTIST_ALBUM_SORT),
        )
        assertContentEquals(
            listOf("Debut", "The Classic", "Comeback", "Unknown album"),
            titles(SortChoice(AlbumSort.YEAR, SongSortDirection.ASCENDING)),
        )
    }

    @Test
    fun titleOrderShelvesArticlesAndKeepsTheUnknownAlbumLast() {
        assertContentEquals(
            listOf("The Classic", "Comeback", "Debut", "Unknown album"),
            titles(SortChoice(AlbumSort.TITLE, SongSortDirection.ASCENDING)),
        )
        assertContentEquals(
            listOf("Debut", "Comeback", "The Classic", "Unknown album"),
            titles(SortChoice(AlbumSort.TITLE, SongSortDirection.DESCENDING)),
        )
    }

    @Test
    fun tracksInsideAnAlbumKeepDiscAndTrackOrderUnderEverySort() {
        for (sort in listOf(AlbumSort.YEAR, AlbumSort.TITLE)) for (direction in SongSortDirection.entries) {
            val debut = artistAlbumSections(discography, SortChoice(sort, direction), "Unknown album")
                .single { it.title == "Debut" }
            assertContentEquals(listOf("b-first", "a-second"), debut.tracks.map { it.id.value })
        }
    }

    @Test
    fun datedLooseTracksStayLastInEveryOrder() {
        val tracks = listOf(
            track("old-1", album = "Debut", year = 1999, trackNumber = 1),
            track("new-1", album = "Comeback", year = 2021, trackNumber = 1),
            // Dated between the two albums: by year alone this chapter would sit mid-discography.
            track("loose-1", album = null, year = 2010),
            track("loose-2", album = null, year = 2010),
        )
        val orders = listOf(
            SortChoice(AlbumSort.YEAR, SongSortDirection.DESCENDING),
            SortChoice(AlbumSort.YEAR, SongSortDirection.ASCENDING),
            SortChoice(AlbumSort.TITLE, SongSortDirection.ASCENDING),
            SortChoice(AlbumSort.TITLE, SongSortDirection.DESCENDING),
        )
        for (order in orders) {
            val sections = artistAlbumSections(tracks, order, "Unknown album")
            assertEquals("Unknown album", sections.last().title, order.toString())
            assertEquals(null, sections.last().railTitle, order.toString())
        }
        assertContentEquals(
            listOf("Comeback", "Debut", "Unknown album"),
            artistAlbumSections(tracks, DEFAULT_ARTIST_ALBUM_SORT, "Unknown album").map { it.title },
        )
        assertContentEquals(
            listOf("Debut", "Comeback", "Unknown album"),
            artistAlbumSections(tracks, SortChoice(AlbumSort.YEAR, SongSortDirection.ASCENDING), "Unknown album")
                .map { it.title },
        )
    }

    @Test
    fun twoReleasesSharingATitleAreDatedSeparately() {
        val tracks = listOf(
            track("blue-1", album = "Weezer", year = 1994, trackNumber = 1, folderPath = "Weezer/Blue"),
            track("green-1", album = "Weezer", year = 2001, trackNumber = 1, folderPath = "Weezer/Green"),
            track("pinkerton-1", album = "Pinkerton", year = 1996, trackNumber = 1, folderPath = "Weezer/Pinkerton"),
        )
        val sections = artistAlbumSections(tracks, DEFAULT_ARTIST_ALBUM_SORT, "Unknown album")
        assertContentEquals(
            listOf("green-1", "pinkerton-1", "blue-1"),
            sections.map { it.tracks.single().id.value },
        )
        // The rail indexes the raw album title; the unknown album's stays null.
        assertEquals("Weezer", sections.first().railTitle)
    }
}
