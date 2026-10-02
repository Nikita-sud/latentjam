/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class AlbumYearTest {

    private var nextTrackId = 0

    private fun track(year: Int? = null, originalYear: Int? = null) = TrackDescriptor(
        id = TrackId("t${nextTrackId++}"),
        year = year,
        originalYear = originalYear,
    )

    @Test
    fun aRemasterShowsTheOriginalYearItSortsUnder() {
        val tracks = List(3) { track(year = 2011, originalYear = 1973) }
        assertEquals("1973", albumYearLabel(tracks))
    }

    @Test
    fun tracksThatDisagreeShowTheYearMostOfThemState() {
        // A bonus track tagged with the reissue year no longer hides the album's year.
        val tracks = listOf(track(year = 1997), track(year = 1997), track(year = 2003))
        assertEquals("1997", albumYearLabel(tracks))
    }

    @Test
    fun anAlbumWithoutAYearShowsNone() {
        assertNull(albumYearLabel(emptyList()))
        assertNull(albumYearLabel(listOf(track(), track())))
    }

    @Test
    fun aCompilationWhoseTracksComeFromManyYearsShowsNone() {
        // A 2005 best-of: the earliest single vote would read 1971, which the album is not.
        val tracks = listOf(1971, 1975, 1980, 1985, 1991).map { track(year = 2005, originalYear = it) }
        assertNull(albumYearLabel(tracks))
        // The Year sort still files it somewhere.
        assertEquals(1971, LibraryCatalog.releaseYear(tracks))
    }

    @Test
    fun aYearHalfTheDatedTracksAgreeOnIsShown() {
        val tracks = listOf(track(year = 1994), track(year = 1994), track(year = 1996), track(year = 1999), track())
        assertEquals("1994", albumYearLabel(tracks))
        assertNull(albumYearLabel(listOf(track(year = 1994), track(year = 1996), track(year = 1999))))
    }

    @Test
    fun theShownYearIsTheYearSortYear() {
        val albums = listOf(
            listOf(track(year = 2011, originalYear = 1973), track(year = 2011)),
            listOf(track(year = 1999), track(year = 2001)),
            listOf(track(), track(year = 1985)),
        )
        for (tracks in albums) {
            val shown = albumYearLabel(tracks) ?: continue
            assertEquals(LibraryCatalog.releaseYear(tracks)?.toString(), shown)
        }
    }
}
