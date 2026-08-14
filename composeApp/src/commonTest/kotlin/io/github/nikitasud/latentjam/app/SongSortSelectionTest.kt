/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import kotlin.test.Test
import kotlin.test.assertEquals

internal class SongSortSelectionTest {

    @Test
    fun selectingTheActiveSortTogglesItsDirection() {
        assertEquals(
            SongSortDirection.DESCENDING,
            directionAfterSongSortSelection(
                currentSort = SongSort.TITLE,
                currentDirection = SongSortDirection.ASCENDING,
                selectedSort = SongSort.TITLE,
            ),
        )
        assertEquals(
            SongSortDirection.ASCENDING,
            directionAfterSongSortSelection(
                currentSort = SongSort.TITLE,
                currentDirection = SongSortDirection.DESCENDING,
                selectedSort = SongSort.TITLE,
            ),
        )
    }

    @Test
    fun selectingAnotherSortUsesThatSortsNaturalDefault() {
        assertEquals(
            SongSortDirection.DESCENDING,
            directionAfterSongSortSelection(
                currentSort = SongSort.TITLE,
                currentDirection = SongSortDirection.DESCENDING,
                selectedSort = SongSort.RECENT,
            ),
        )
        assertEquals(
            SongSortDirection.ASCENDING,
            directionAfterSongSortSelection(
                currentSort = SongSort.RECENT,
                currentDirection = SongSortDirection.ASCENDING,
                selectedSort = SongSort.ARTIST,
            ),
        )
    }
}
