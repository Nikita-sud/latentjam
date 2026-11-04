/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.FolderSort
import io.github.nikitasud.latentjam.library.PlaylistSort
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #31: the new date orders persist like every other sort, and the Folders tab's pill keeps its two settings in step. */
class DateSortSettingsTest {
    @Test
    fun theTracksDateOrdersPersistByName() {
        for (sort in listOf(SongSort.RECENT, SongSort.MODIFIED)) for (direction in SongSortDirection.entries) {
            val choice = SortChoice(sort, direction)
            assertEquals(choice, songSortFromPersisted(encodeSortChoice(choice)))
        }
        assertEquals("modified:descending", encodeSortChoice(SortChoice(SongSort.MODIFIED, SongSortDirection.DESCENDING)))
        // A field without a direction takes the newest first.
        assertEquals(SortChoice(SongSort.MODIFIED, SongSortDirection.DESCENDING), songSortFromPersisted("modified"))
    }

    @Test
    fun theFolderOrderPersistsAndAnythingUnknownIsName() {
        for (sort in FolderSort.entries) for (direction in SongSortDirection.entries) {
            val choice = SortChoice(sort, direction)
            assertEquals(choice, folderSortFromPersisted(encodeSortChoice(choice)))
        }
        assertEquals(DEFAULT_FOLDER_SORT, folderSortFromPersisted(null))
        assertEquals(DEFAULT_FOLDER_SORT, folderSortFromPersisted("size:ascending"))
    }

    @Test
    fun aDateOrderKeepsFolderNamesAToZAndNameKeepsItsDirection() {
        assertEquals(SongSortDirection.ASCENDING, folderNamesDirection(SortChoice(FolderSort.MODIFIED, SongSortDirection.DESCENDING)))
        assertEquals(SongSortDirection.ASCENDING, folderNamesDirection(SortChoice(FolderSort.RECENT, SongSortDirection.ASCENDING)))
        assertEquals(SongSortDirection.DESCENDING, folderNamesDirection(SortChoice(FolderSort.NAME, SongSortDirection.DESCENDING)))
        // Name shows the folder-name direction a backup restored; a date order shows its own.
        assertEquals(
            SortChoice(FolderSort.NAME, SongSortDirection.DESCENDING),
            shownFolderSort(SortChoice(FolderSort.NAME, SongSortDirection.ASCENDING), SongSortDirection.DESCENDING),
        )
        val dated = SortChoice(FolderSort.RECENT, SongSortDirection.DESCENDING)
        assertEquals(dated, shownFolderSort(dated, SongSortDirection.ASCENDING))
    }

    @Test
    fun dateOrdersSayNewestOrOldestFirst() {
        for (sort in listOf<Enum<*>>(SongSort.RECENT, SongSort.MODIFIED, FolderSort.RECENT, FolderSort.MODIFIED, AlbumSort.RECENT, PlaylistSort.RECENT)) {
            assertTrue(isDateSort(sort), "$sort")
        }
        for (sort in listOf<Enum<*>>(SongSort.TITLE, SongSort.ARTIST, FolderSort.NAME, AlbumSort.YEAR, PlaylistSort.PLAYS)) {
            assertFalse(isDateSort(sort), "$sort")
        }
    }
}
