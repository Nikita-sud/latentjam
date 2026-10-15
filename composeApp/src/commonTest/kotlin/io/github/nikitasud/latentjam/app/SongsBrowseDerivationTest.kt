/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.library.SongSorting
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SongsBrowseDerivationTest {
    private val songs = listOf(
        TrackDescriptor(TrackId("first"), title = "The Zebra", artist = "Beta", addedAtMs = 5),
        TrackDescriptor(TrackId("equal"), title = "Zebra", artist = "Beta", addedAtMs = 5),
        TrackDescriptor(TrackId("accent"), title = "Альфа", artist = "Alpha", addedAtMs = 7),
        TrackDescriptor(TrackId("number"), title = "12 steps", artist = "Alpha", addedAtMs = 2),
        TrackDescriptor(TrackId("missing")),
    )

    @Test fun allOrdersKeepExistingStableSortAndQueueIdentity() {
        for (sort in SongSort.entries) for (direction in SongSortDirection.entries) {
            val prepared = SongsBrowseDerivation.of(songs, SortChoice(sort, direction))
            assertSame(songs, prepared.source)
            val expected = SongSorting.sections(songs, sort, direction).flatMap { it.tracks }
            assertEquals(expected, prepared.displayOrder)
            for (section in prepared.indexed) section.tracks.forEachIndexed { row, track ->
                assertSame(track, prepared.displayOrder[section.firstTrackGlobalIndex + row])
                val rowIndex = section.emitStartIndex + (if (prepared.indexEnabled) 1 else 0) + row
                assertSame(track, prepared.emittedTracks[rowIndex])
            }
        }
    }

    @Test fun railAnchorsMatchExactlyTheHeadersThatAreEmitted() {
        val prepared = SongsBrowseDerivation.of(songs, DEFAULT_SONG_SORT)
        assertTrue(prepared.indexEnabled)
        for (anchor in prepared.sectionStarts) assertNull(prepared.emittedTracks[anchor])
        assertEquals(prepared.sectionBuckets.size, prepared.emittedTracks.count { it == null })
        assertEquals(songs.size, prepared.emittedTracks.count { it != null })
    }

    @Test fun emptySingleBucketAndRecentListsHaveNoPhantomHeaders() {
        for (source in listOf(emptyList(), songs.take(2), songs)) {
            for (sort in SongSort.entries) {
                val prepared = SongsBrowseDerivation.of(source, SortChoice(sort, SongSortDirection.ASCENDING))
                if (prepared.indexed.size <= 1 || sort == SongSort.RECENT) {
                    assertFalse(prepared.indexEnabled)
                    assertEquals(prepared.displayOrder, prepared.emittedTracks)
                }
            }
        }
    }

    @Test fun metadataRefreshReplacesQueueObjectsEvenWhenIdsAndOrderAreUnchanged() {
        val old = SongsBrowseDerivation.of(songs, DEFAULT_SONG_SORT)
        val changed = songs.map { it.copy(artworkUri = "file:///new-cover.png") }
        val updated = SongsBrowseDerivation.of(changed, DEFAULT_SONG_SORT)
        assertEquals(old.displayOrder.map { it.id }, updated.displayOrder.map { it.id })
        assertTrue(updated.displayOrder.all { it.artworkUri == "file:///new-cover.png" })
        assertNotSame(old.displayOrder.first(), updated.displayOrder.first())
    }

    @Test fun cancelledPreparationKeepsPreviousSnapshotAndLatestChoiceWins() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val original = SongsBrowseDerivation.of(songs, DEFAULT_SONG_SORT)
        var shown = original
        val obsolete = launch {
            shown = prepareSongsBrowse(songs, SortChoice(SongSort.ARTIST, SongSortDirection.ASCENDING), dispatcher)
        }
        assertSame(original, shown)
        obsolete.cancel()
        val latestChoice = SortChoice(SongSort.RECENT, SongSortDirection.DESCENDING)
        val latest = launch { shown = prepareSongsBrowse(songs, latestChoice, dispatcher) }
        assertSame(original, shown)
        runCurrent()
        latest.join()
        assertEquals(latestChoice, shown.choice)
        assertEquals(listOf("accent", "first", "equal", "number", "missing"), shown.displayOrder.map { it.id.value })
        assertTrue(obsolete.isCancelled)
    }

    @Test fun cancellationAfterWorkerFinishesStillCannotPublishOnUi() = runTest {
        val worker = QueuedDispatcher()
        val original = SongsBrowseDerivation.of(songs, DEFAULT_SONG_SORT)
        var shown = original
        val obsolete = launch {
            shown = prepareSongsBrowse(songs, SortChoice(SongSort.ARTIST, SongSortDirection.ASCENDING), worker)
        }
        runCurrent() // The UI coroutine has handed work to the background dispatcher.
        assertSame(original, shown)
        worker.runNext() // Result is computed, but its continuation on the UI is still queued.
        obsolete.cancel()
        runCurrent()
        assertSame(original, shown)
        assertTrue(obsolete.isCancelled)
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runNext() { tasks.removeFirst().run() }
    }

}
