/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.library.defaultDirection
import kotlin.test.Test
import kotlin.test.assertEquals

internal class SortSelectionTest {

    @Test
    fun selectingTheActiveSortTogglesItsDirection() {
        assertEquals(
            SongSortDirection.DESCENDING,
            directionAfterSortSelection(
                currentSort = SongSort.TITLE,
                currentDirection = SongSortDirection.ASCENDING,
                selectedSort = SongSort.TITLE,
                selectedDefault = SongSort.TITLE.defaultDirection,
            ),
        )
        assertEquals(
            SongSortDirection.ASCENDING,
            directionAfterSortSelection(
                currentSort = SongSort.TITLE,
                currentDirection = SongSortDirection.DESCENDING,
                selectedSort = SongSort.TITLE,
                selectedDefault = SongSort.TITLE.defaultDirection,
            ),
        )
    }

    @Test
    fun selectingAnotherSortUsesThatSortsNaturalDefault() {
        assertEquals(
            SongSortDirection.DESCENDING,
            directionAfterSortSelection(
                currentSort = SongSort.TITLE,
                currentDirection = SongSortDirection.DESCENDING,
                selectedSort = SongSort.RECENT,
                selectedDefault = SongSort.RECENT.defaultDirection,
            ),
        )
        assertEquals(
            SongSortDirection.ASCENDING,
            directionAfterSortSelection(
                currentSort = SongSort.RECENT,
                currentDirection = SongSortDirection.ASCENDING,
                selectedSort = SongSort.ARTIST,
                selectedDefault = SongSort.ARTIST.defaultDirection,
            ),
        )
    }

    @Test
    fun albumSortsFollowTheSameRule() {
        val newestFirst = SortChoice(AlbumSort.YEAR, SongSortDirection.DESCENDING)
        // Tapping the active year reverses it; tapping title starts A–Z; tapping year again
        // from title starts newest first, not wherever year was left.
        assertEquals(
            SortChoice(AlbumSort.YEAR, SongSortDirection.ASCENDING),
            newestFirst.afterSelecting(AlbumSort.YEAR, AlbumSort.YEAR.defaultDirection),
        )
        val byTitle = newestFirst.afterSelecting(AlbumSort.TITLE, AlbumSort.TITLE.defaultDirection)
        assertEquals(SortChoice(AlbumSort.TITLE, SongSortDirection.ASCENDING), byTitle)
        assertEquals(newestFirst, byTitle.afterSelecting(AlbumSort.YEAR, AlbumSort.YEAR.defaultDirection))
        assertEquals(
            SortChoice(AlbumSort.RECENT, SongSortDirection.DESCENDING),
            byTitle.afterSelecting(AlbumSort.RECENT, AlbumSort.RECENT.defaultDirection),
        )
    }
}
