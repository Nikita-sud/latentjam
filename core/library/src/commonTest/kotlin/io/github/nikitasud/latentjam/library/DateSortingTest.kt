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
import kotlin.time.TimeSource

/** Issue #31: the Date added and Date modified orders of the Tracks tab and the Folders tab. */
internal class DateSortingTest {
    private fun track(
        id: String,
        title: String?,
        added: Long? = null,
        modified: Long? = null,
        folder: String? = "Download",
        file: String? = null,
        number: Int? = null,
    ) = TrackDescriptor(
        id = TrackId(id), title = title, addedAtMs = added, modifiedAtMs = modified, folderPath = folder,
        fileName = file ?: "$id.mp3", trackNumber = number,
    )

    private val tracks = listOf(
        track("a", "Beta", added = 300, modified = 10),
        track("b", "Alpha", added = 100, modified = 30),
        track("c", "Gamma", added = null, modified = 20),
        track("d", "Delta", added = 300, modified = null),
        track("e", "Alpha", added = null, modified = null),
        track("f", null, added = 200, modified = 20),
    )

    private fun List<TrackDescriptor>.ids() = map { it.id.value }

    @Test
    fun dateAddedRunsNewestOrOldestFirstWithUnknownDatesLast() {
        // 300 twice: Beta and Delta A–Z. Unknown: Alpha before Gamma.
        assertEquals(listOf("a", "d", "f", "b", "e", "c"), SongSorting.sort(tracks, SongSort.RECENT, SongSortDirection.DESCENDING).ids())
        assertEquals(listOf("b", "f", "a", "d", "e", "c"), SongSorting.sort(tracks, SongSort.RECENT, SongSortDirection.ASCENDING).ids())
    }

    @Test
    fun dateModifiedRunsNewestOrOldestFirstWithUnknownDatesLast() {
        // 20 twice: Gamma before the untitled track, which sorts after every title.
        assertEquals(listOf("b", "c", "f", "a", "e", "d"), SongSorting.sort(tracks, SongSort.MODIFIED, SongSortDirection.DESCENDING).ids())
        assertEquals(listOf("a", "c", "f", "b", "e", "d"), SongSorting.sort(tracks, SongSort.MODIFIED, SongSortDirection.ASCENDING).ids())
        assertEquals(SongSortDirection.DESCENDING, SongSort.MODIFIED.defaultDirection)
    }

    @Test
    fun aDateOrderIsOneSectionWithoutLetters() {
        val sections = SongSorting.sections(tracks, SongSort.MODIFIED, SongSortDirection.DESCENDING)
        assertEquals(1, sections.size)
        assertEquals("", sections.single().bucket)
    }

    @Test
    fun aFoldersTracksFollowTheChosenDateAndFolderOrderOnTies() {
        val folder = listOf(
            track("1", "One", modified = 5, number = 2),
            track("2", "Two", modified = 9, number = 1),
            track("3", "Three", modified = null, number = 3),
            track("4", "Four", modified = 5, number = 1),
        )
        val newest = FolderTree.build(folder, FolderSort.MODIFIED.trackOrder(SongSortDirection.DESCENDING))
        // 9, then the two at 5 in folder order (track number 1 before 2), then the undated one.
        assertEquals(listOf("2", "4", "1", "3"), newest.root.folders.single().tracks.ids())
        val oldest = FolderTree.build(folder, FolderSort.MODIFIED.trackOrder(SongSortDirection.ASCENDING))
        assertEquals(listOf("4", "1", "2", "3"), oldest.root.folders.single().tracks.ids())
        // Name keeps folder order, as before: track 1 (by file name), track 2, track 3.
        assertEquals(listOf("2", "4", "1", "3"), FolderTree.build(folder).root.folders.single().tracks.ids())
        assertEquals(LibraryCatalog.inFolderOrder(folder), FolderTree.build(folder, FolderSort.NAME.trackOrder(SongSortDirection.DESCENDING)).root.folders.single().tracks)
    }

    @Test
    fun playOrderKeepsFoldersByPathAndOrdersEachFoldersTracksByDate() {
        val library = listOf(
            track("x1", "X1", added = 1, folder = "B"),
            track("x2", "X2", added = 3, folder = "B"),
            track("y1", "Y1", added = 9, folder = "A"),
            track("y2", "Y2", added = 2, folder = "A"),
        )
        val order = FolderSort.RECENT.trackOrder(SongSortDirection.DESCENDING)
        assertEquals(listOf("y1", "y2", "x2", "x1"), FolderTree.inPlayOrder(library, trackOrder = order).ids())
        assertEquals(listOf("y1", "y2", "x2", "x1"), FolderTree.build(library, order).root.allTracks().ids())
    }

    @Test
    fun tenThousandTracksSortByDateQuickly() {
        val library = (0 until 10_000).map { i ->
            track(
                "t$i", "Song ${(i * 7919) % 10_000}",
                added = if (i % 17 == 0) null else 1_700_000_000_000L + (i * 104_729L) % 9_000_000L,
                modified = if (i % 13 == 0) null else 1_700_000_000_000L + (i * 15_485_863L) % 9_000_000L,
                folder = "F${i % 40}",
            )
        }
        val clock = TimeSource.Monotonic
        val start = clock.markNow()
        repeat(3) {
            SongSorting.sort(library, SongSort.MODIFIED, SongSortDirection.DESCENDING)
            SongSorting.sort(library, SongSort.RECENT, SongSortDirection.ASCENDING)
            FolderTree.build(library, FolderSort.MODIFIED.trackOrder(SongSortDirection.DESCENDING))
        }
        val elapsed = start.elapsedNow()
        val sorted = SongSorting.sort(library, SongSort.MODIFIED, SongSortDirection.DESCENDING)
        val dated = sorted.takeWhile { it.modifiedAtMs != null }
        assertEquals(library.count { it.modifiedAtMs != null }, dated.size)
        assertTrue(dated.zipWithNext().all { (a, b) -> a.modifiedAtMs!! >= b.modifiedAtMs!! })
        // Three rounds of three orders: well under a second each on a debug host; the bound only catches a quadratic slip.
        assertTrue(elapsed.inWholeMilliseconds < 15_000, "took $elapsed")
    }
}
