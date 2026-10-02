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
import kotlin.test.assertNull

internal class AlbumSortingTest {

    private var nextTrackId = 0

    private fun track(
        year: Int? = null,
        originalYear: Int? = null,
        addedAtMs: Long? = null,
    ) = TrackDescriptor(
        id = TrackId("t${nextTrackId++}"),
        year = year,
        originalYear = originalYear,
        addedAtMs = addedAtMs,
    )

    private fun album(
        key: String,
        title: String? = key,
        artist: String? = "Artist",
        tracks: List<TrackDescriptor> = listOf(track()),
    ) = AlbumGroup(key = key, title = title, artist = artist, artworkUri = null, tracks = tracks)

    private fun List<AlbumGroup>.keys(): List<String> = map { it.key }

    // releaseYear

    @Test
    fun releaseYearIsNullWhenNoTrackHasAYear() {
        assertNull(LibraryCatalog.releaseYear(emptyList()))
        assertNull(LibraryCatalog.releaseYear(listOf(track(), track())))
    }

    @Test
    fun releaseYearIsTheYearMostTracksAgreeOn() {
        // A bonus track tagged with its reissue year does not move the album.
        val tracks = listOf(track(year = 1997), track(year = 1997), track(year = 2009))
        assertEquals(1997, LibraryCatalog.releaseYear(tracks))
    }

    @Test
    fun releaseYearTieGoesToTheEarliestYear() {
        val tracks = listOf(track(year = 2004), track(year = 1999), track(year = 2004), track(year = 1999))
        assertEquals(1999, LibraryCatalog.releaseYear(tracks))
    }

    @Test
    fun releaseYearPrefersTheOriginalYearOfARemaster() {
        val tracks = listOf(
            track(year = 2011, originalYear = 1973),
            track(year = 2011, originalYear = 1973),
            track(year = 2011),
        )
        assertEquals(1973, LibraryCatalog.releaseYear(tracks))
    }

    @Test
    fun releaseYearIgnoresTracksWithoutAYear() {
        assertEquals(1985, LibraryCatalog.releaseYear(listOf(track(), track(), track(year = 1985))))
    }

    // Default directions

    @Test
    fun defaultDirectionsAreAToZAndNewestFirst() {
        assertEquals(SongSortDirection.ASCENDING, AlbumSort.TITLE.defaultDirection)
        assertEquals(SongSortDirection.ASCENDING, AlbumSort.ARTIST.defaultDirection)
        assertEquals(SongSortDirection.DESCENDING, AlbumSort.YEAR.defaultDirection)
        assertEquals(SongSortDirection.DESCENDING, AlbumSort.RECENT.defaultDirection)
    }

    // TITLE

    @Test
    fun titleSortShelvesArticlesAndKeepsMissingTitlesLast() {
        val albums = listOf(
            album("zen", title = "Zen"),
            album("none", title = null),
            album("wall", title = "The Wall"),
            album("abbey", title = "Abbey Road"),
            album("perfect", title = "A Perfect Circle"),
        )
        assertContentEquals(
            listOf("abbey", "perfect", "wall", "zen", "none"),
            AlbumSorting.sort(albums, AlbumSort.TITLE).keys(),
        )
        assertContentEquals(
            listOf("zen", "wall", "perfect", "abbey", "none"),
            AlbumSorting.sort(albums, AlbumSort.TITLE, SongSortDirection.DESCENDING).keys(),
        )
    }

    @Test
    fun titleAscendingIsTheAlbumsTabsHistoricOrder() {
        // Same title: artist decides, then the album key — the order the tab has always shown.
        val albums = listOf(
            album("greatest-2", title = "Greatest Hits", artist = "Queen"),
            album("greatest-1", title = "Greatest Hits", artist = "ABBA"),
            album("greatest-0", title = "Greatest Hits", artist = "Queen"),
            album("alpha", title = "Alpha"),
        )
        assertContentEquals(
            listOf("alpha", "greatest-1", "greatest-0", "greatest-2"),
            AlbumSorting.sort(albums, AlbumSort.TITLE).keys(),
        )
    }

    // ARTIST

    @Test
    fun artistSortOrdersByArtistThenTitleAndKeepsMissingArtistsLast() {
        val albums = listOf(
            album("queen-b", title = "Innuendo", artist = "Queen"),
            album("unknown", title = "Demo", artist = null),
            album("beatles", title = "Revolver", artist = "The Beatles"),
            album("queen-a", title = "A Night at the Opera", artist = "Queen"),
            album("abba", title = "Arrival", artist = "ABBA"),
        )
        assertContentEquals(
            listOf("abba", "beatles", "queen-b", "queen-a", "unknown"),
            AlbumSorting.sort(albums, AlbumSort.ARTIST).keys(),
        )
        // Reversal flips the artists only; one artist's albums keep their A–Z tie order.
        assertContentEquals(
            listOf("queen-b", "queen-a", "beatles", "abba", "unknown"),
            AlbumSorting.sort(albums, AlbumSort.ARTIST, SongSortDirection.DESCENDING).keys(),
        )
    }

    // YEAR

    @Test
    fun yearSortIsNewestFirstByDefaultWithUndatedAlbumsLastEitherWay() {
        val albums = listOf(
            album("y1994", tracks = listOf(track(year = 1994))),
            album("undated", tracks = listOf(track())),
            album("y2020", tracks = listOf(track(year = 2020))),
            album("y1971", tracks = listOf(track(year = 1971))),
        )
        assertContentEquals(
            listOf("y2020", "y1994", "y1971", "undated"),
            AlbumSorting.sort(albums, AlbumSort.YEAR).keys(),
        )
        assertContentEquals(
            listOf("y1971", "y1994", "y2020", "undated"),
            AlbumSorting.sort(albums, AlbumSort.YEAR, SongSortDirection.ASCENDING).keys(),
        )
    }

    @Test
    fun yearSortFilesARemasterUnderItsOriginalYear() {
        val albums = listOf(
            album("remaster", tracks = listOf(track(year = 2011, originalYear = 1973))),
            album("y1990", tracks = listOf(track(year = 1990))),
        )
        assertContentEquals(
            listOf("remaster", "y1990"),
            AlbumSorting.sort(albums, AlbumSort.YEAR, SongSortDirection.ASCENDING).keys(),
        )
    }

    @Test
    fun yearSortResolvesDisagreeingTrackYearsByTheMode() {
        val albums = listOf(
            // Mostly 2001, one track tagged 1980: files under 2001.
            album("mostly2001", tracks = listOf(track(year = 2001), track(year = 1980), track(year = 2001))),
            // A tie between 1995 and 2005 goes to 1995.
            album("tied", tracks = listOf(track(year = 2005), track(year = 1995))),
            album("y2000", tracks = listOf(track(year = 2000))),
        )
        assertContentEquals(
            listOf("tied", "y2000", "mostly2001"),
            AlbumSorting.sort(albums, AlbumSort.YEAR, SongSortDirection.ASCENDING).keys(),
        )
    }

    @Test
    fun sameYearAlbumsTieByTitleThenArtistThenKeyInBothDirections() {
        val albums = listOf(
            album("k2", title = "Bravo", artist = "Zed", tracks = listOf(track(year = 1999))),
            album("k1", title = "Bravo", artist = "Zed", tracks = listOf(track(year = 1999))),
            album("k3", title = "Bravo", artist = "Ann", tracks = listOf(track(year = 1999))),
            album("k4", title = "The Alpha", artist = "Zed", tracks = listOf(track(year = 1999))),
        )
        val expected = listOf("k4", "k3", "k1", "k2")
        assertContentEquals(expected, AlbumSorting.sort(albums, AlbumSort.YEAR).keys())
        assertContentEquals(
            expected,
            AlbumSorting.sort(albums, AlbumSort.YEAR, SongSortDirection.ASCENDING).keys(),
        )
        // Total order: the input permutation does not matter.
        assertContentEquals(expected, AlbumSorting.sort(albums.reversed(), AlbumSort.YEAR).keys())
    }

    // RECENT

    @Test
    fun recentSortUsesEachAlbumsNewestTrackAndKeepsUndatedLast() {
        val albums = listOf(
            album("old", tracks = listOf(track(addedAtMs = 100), track(addedAtMs = 200))),
            album("undated", tracks = listOf(track())),
            // One track added late makes the whole album recent.
            album("topped-up", tracks = listOf(track(addedAtMs = 50), track(addedAtMs = 900))),
            album("mid", tracks = listOf(track(addedAtMs = 500))),
        )
        assertContentEquals(
            listOf("topped-up", "mid", "old", "undated"),
            AlbumSorting.sort(albums, AlbumSort.RECENT).keys(),
        )
        assertContentEquals(
            listOf("old", "mid", "topped-up", "undated"),
            AlbumSorting.sort(albums, AlbumSort.RECENT, SongSortDirection.ASCENDING).keys(),
        )
    }

    // Sections

    @Test
    fun titleAndArtistSortsBucketByInitialInSortOrder() {
        val albums = listOf(
            album("b", title = "Bravo", artist = "Zed"),
            album("a", title = "The Alpha", artist = "Yann"),
            album("c", title = "Charlie", artist = "Abe"),
        )
        assertContentEquals(
            listOf("A", "B", "C"),
            AlbumSorting.sections(albums, AlbumSort.TITLE).map { it.bucket },
        )
        assertContentEquals(
            listOf("C", "B", "A"),
            AlbumSorting.sections(albums, AlbumSort.TITLE, SongSortDirection.DESCENDING).map { it.bucket },
        )
        val byArtist = AlbumSorting.sections(albums, AlbumSort.ARTIST)
        assertContentEquals(listOf("A", "Y", "Z"), byArtist.map { it.bucket })
        assertContentEquals(listOf("c", "a", "b"), byArtist.flatMap { it.albums }.keys())
    }

    @Test
    fun yearAndRecentSortsHaveOneUnlabeledSection() {
        val albums = listOf(
            album("a", tracks = listOf(track(year = 1990, addedAtMs = 1))),
            album("b", tracks = listOf(track(year = 2000, addedAtMs = 2))),
        )
        for (sort in listOf(AlbumSort.YEAR, AlbumSort.RECENT)) {
            val sections = AlbumSorting.sections(albums, sort)
            assertEquals(1, sections.size)
            assertEquals("", sections.single().bucket)
            assertContentEquals(listOf("b", "a"), sections.single().albums.keys())
        }
        assertEquals(emptyList(), AlbumSorting.sections(emptyList(), AlbumSort.YEAR))
    }
}
