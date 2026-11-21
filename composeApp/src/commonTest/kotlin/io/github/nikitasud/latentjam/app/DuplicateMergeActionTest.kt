/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultFavorites
import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.history.FavoritesStore
import io.github.nikitasud.latentjam.history.DefaultListeningHistory
import io.github.nikitasud.latentjam.history.HistoryStore
import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.library.DefaultPlaylists
import io.github.nikitasud.latentjam.library.PlaylistStore
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class DuplicateMergeActionTest {
    private class PlaylistMemory : PlaylistStore {
        var lines = emptyList<String>()
        override suspend fun read() = lines
        override suspend fun write(lines: List<String>) { this.lines = lines }
    }

    private class FavoritesMemory : FavoritesStore {
        var ids = emptyList<String>()
        override suspend fun read() = ids
        override suspend fun write(ids: List<String>) { this.ids = ids }
    }

    private fun track(id: String, uri: String? = "content://media/external/audio/media/$id") =
        TrackDescriptor(id = TrackId(id), audioUri = uri)

    private class MemoryHistoryStore : HistoryStore {
        val lines = mutableListOf<String>()
        override suspend fun append(line: String) { lines += line }
        override suspend fun readAll(): List<String> = lines.toList()
        override suspend fun clear() { lines.clear() }
    }

    private fun listen(id: String, startedAt: Long, completed: Boolean = true) = ListenEvent(
        trackId = TrackId(id),
        startedAtMs = startedAt,
        playedMs = if (completed) 200_000 else 10_000,
        trackDurationMs = 200_000,
        completed = completed,
        skipped = !completed,
    )

    private suspend fun historyOf(vararg events: ListenEvent): DefaultListeningHistory =
        DefaultListeningHistory(MemoryHistoryStore()).also { history -> events.forEach { history.record(it) } }

    private fun selection(keep: TrackDescriptor, other: TrackDescriptor): Pair<DuplicateGroup, DuplicateCopy> {
        val group = describeDuplicateGroup(listOf(keep, other), emptyMap(), emptySet(), emptyMap())
        return group to group.copy(keep.id)!!
    }

    @Test
    fun deletionAvailabilityFollowsTheDiscardedCopiesSource() {
        val imported = track("Imported/song.mp3", "file:///Documents/Imported/song.mp3")
        val music = track("ios-media:123", "ipod-library://item/item.m4a?id=123")
        assertTrue(canDeleteDuplicateFiles(listOf(selection(music, imported))))
        assertFalse(canDeleteDuplicateFiles(listOf(selection(imported, music))))
        assertFalse(canDeleteDuplicateFiles(emptyList()))
        assertFalse(canDeleteDuplicateFiles(listOf(selection(imported, track("missing", null)))))
        assertFalse(canDeleteTrack(track("blank", " ")))
    }

    @Test
    fun unavailableDeleteCannotRewritePlaylistsOrFavorites() = runTest {
        val keep = track("Imported/keep.mp3", "file:///Documents/Imported/keep.mp3")
        val other = track("ios-media:9", "ipod-library://item/item.m4a?id=9")
        val playlists = DefaultPlaylists(PlaylistMemory())
        playlists.create("Mix", listOf(other.id))
        val favorites = DefaultFavorites(FavoritesMemory()).apply { replace(listOf(other.id)) }
        assertFailsWith<IllegalArgumentException> {
            performDuplicateMerge(
                listOf(selection(keep, other)), true, playlists, favorites, historyOf(),
                onHideTracks = { error("must not hide") },
                onDeleteTracks = { error("must not delete") },
                onDataChanged = { error("must not mutate") },
            )
        }
        assertEquals(listOf(other.id.value), playlists.all().single().trackIds)
        assertEquals(listOf(other.id), favorites.all())
    }

    @Test
    fun nativeRequestFailureIsReportedAfterPublishingSafeReferences() = runTest {
        val keep = track("1")
        val other = track("2")
        val playlists = DefaultPlaylists(PlaylistMemory())
        playlists.create("Mix", listOf(other.id))
        val favorites = DefaultFavorites(FavoritesMemory()).apply { replace(listOf(other.id)) }
        var published = false
        val rejected = IllegalArgumentException("Native delete request rejected")
        val failure = assertFailsWith<IllegalArgumentException> {
            performDuplicateMerge(
                listOf(selection(keep, other)), true, playlists, favorites, historyOf(),
                onHideTracks = { error("deletion must not hide files") },
                onDeleteTracks = {
                    assertTrue(published)
                    assertEquals(listOf(other), it)
                    throw rejected
                },
                onDataChanged = { published = true },
            )
        }
        assertSame(rejected, failure)
        assertEquals(listOf(keep.id.value), playlists.all().single().trackIds)
        assertEquals(listOf(keep.id), favorites.all())
    }

    @Test
    fun bulkHideRunsOnceAfterEveryGroupsReferencesAreSafe() = runTest {
        val first = selection(track("1"), track("2"))
        val second = selection(track("3"), track("4"))
        val playlists = DefaultPlaylists(PlaylistMemory())
        playlists.create("Mix", listOf(TrackId("2"), TrackId("4")))
        val favorites = DefaultFavorites(FavoritesMemory()).apply { replace(listOf(TrackId("4"))) }
        val events = mutableListOf<String>()
        performDuplicateMerge(
            listOf(first, second), false, playlists, favorites, historyOf(),
            onHideTracks = { copies ->
                assertEquals(listOf(TrackId("2"), TrackId("4")), copies.map { it.id })
                assertEquals(listOf("1", "3"), playlists.all().single().trackIds)
                assertEquals(listOf(TrackId("3")), favorites.all())
                events += "hide"
            },
            onDeleteTracks = { error("must not delete") },
            onDataChanged = { events += "publish" },
        )
        assertEquals(listOf("hide", "publish"), events)
    }

    @Test
    fun partiallyRewrittenMembershipsArePublishedAfterFavoritesConflict() = runTest {
        val keep = track("1")
        val other = track("2")
        val playlists = DefaultPlaylists(PlaylistMemory())
        playlists.create("Mix", listOf(other.id))
        val backing = DefaultFavorites(FavoritesMemory()).apply { replace(listOf(other.id)) }
        val favorites = object : Favorites by backing {
            override suspend fun replaceIfUnchanged(expected: List<TrackId>, replacement: List<TrackId>) = false
        }
        var published = false
        assertFailsWith<IllegalStateException> {
            performDuplicateMerge(
                listOf(selection(keep, other)), false, playlists, favorites, historyOf(),
                onHideTracks = { error("must keep files on conflict") },
                onDeleteTracks = { error("must not delete") },
                onDataChanged = {
                    assertEquals(listOf(keep.id.value), playlists.all().single().trackIds)
                    published = true
                },
            )
        }
        assertTrue(published)
        assertEquals(listOf(other.id), favorites.all())
    }

    @Test
    fun cancellationIsPreservedAfterPublishingDurableChanges() = runTest {
        val cancelled = CancellationException("leaving settings")
        var published = false
        val failure = assertFailsWith<CancellationException> {
            performDuplicateMerge(
                listOf(selection(track("1"), track("2"))), false,
                DefaultPlaylists(PlaylistMemory()), DefaultFavorites(FavoritesMemory()), historyOf(),
                onHideTracks = { throw cancelled },
                onDeleteTracks = { error("must not delete") },
                onDataChanged = { published = true },
            )
        }
        assertSame(cancelled, failure)
        assertTrue(published)
    }

    @Test
    fun leavingDuringFinalRefreshCannotOpenANativeDeletePrompt() = runTest {
        var published = false
        var deleteRequested = false
        val operation = launch {
            val operationContext = currentCoroutineContext()
            performDuplicateMerge(
                listOf(selection(track("1"), track("2"))), true,
                DefaultPlaylists(PlaylistMemory()), DefaultFavorites(FavoritesMemory()), historyOf(),
                onHideTracks = { error("must not hide") },
                onDeleteTracks = { deleteRequested = true },
                onDataChanged = {
                    operationContext.cancel()
                    published = true
                },
            )
        }
        operation.join()
        assertTrue(operation.isCancelled)
        assertTrue(published)
        assertFalse(deleteRequested)
    }

    @Test
    fun committedHidePublishesLibraryEvenWhenSettingsCloses() = runTest {
        val events = mutableListOf<String>()
        val operation = launch {
            val operationContext = currentCoroutineContext()
            hideTracksAndRefresh(
                hide = {
                    events += "persist hidden ids"
                    operationContext.cancel()
                },
                refresh = {
                    yield()
                    events += "publish library"
                },
            )
        }
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(listOf("persist hidden ids", "publish library"), events)
    }

    @Test
    fun alreadyCancelledActionDoesNotStartHiding() = runTest {
        var hidden = false
        val operation = launch {
            currentCoroutineContext().cancel()
            hideTracksAndRefresh(
                hide = { hidden = true },
                refresh = { error("must not refresh") },
            )
        }
        operation.join()
        assertTrue(operation.isCancelled)
        assertFalse(hidden)
    }

    // Issue #40: the listens of the copies that go become the kept copy's.

    @Test
    fun listensFollowTheKeptCopiesBeforeAnyCopyIsHidden() = runTest {
        val first = selection(track("1"), track("2"))
        val second = selection(track("3"), track("4"))
        val history = historyOf(listen("1", 100), listen("2", 200), listen("4", 300), listen("4", 400, completed = false))
        val events = mutableListOf<String>()
        performDuplicateMerge(
            listOf(first, second), false, DefaultPlaylists(PlaylistMemory()), DefaultFavorites(FavoritesMemory()), history,
            onHideTracks = {
                assertEquals(setOf(TrackId("1"), TrackId("3")), history.stats().keys)
                events += "hide"
            },
            onDeleteTracks = { error("must not delete") },
            onDataChanged = { events += "publish" },
            onListensMoved = { events += "history" },
        )
        assertEquals(listOf("hide", "history", "publish"), events)
        assertEquals(2, history.stats().getValue(TrackId("1")).plays)
        val third = history.stats().getValue(TrackId("3"))
        assertEquals(2, third.plays)
        assertEquals(1, third.skips)
        assertEquals(listOf(100L, 200L, 300L, 400L), history.allEvents().map { it.startedAtMs })
    }

    @Test
    fun listensFollowTheKeptCopyBeforeAFileIsDeleted() = runTest {
        val keep = track("1")
        val other = track("2")
        val history = historyOf(listen("2", 200))
        val events = mutableListOf<String>()
        performDuplicateMerge(
            listOf(selection(keep, other)), true, DefaultPlaylists(PlaylistMemory()), DefaultFavorites(FavoritesMemory()), history,
            onHideTracks = { error("deletion must not hide files") },
            onDeleteTracks = { events += "delete" },
            onDataChanged = { events += "publish" },
            onListensMoved = { events += "history" },
        )
        // The delete request is the last step, made after the listens were moved and announced.
        assertEquals(listOf("history", "publish", "delete"), events)
        assertEquals(listOf(keep.id), history.allEvents().map { it.trackId })
    }

    @Test
    fun copiesThatWereNeverPlayedChangeNoHistory() = runTest {
        val history = historyOf(listen("1", 100), listen("9", 200))
        val before = history.allEvents()
        performDuplicateMerge(
            listOf(selection(track("1"), track("2"))), false,
            DefaultPlaylists(PlaylistMemory()), DefaultFavorites(FavoritesMemory()), history,
            onHideTracks = {},
            onDeleteTracks = { error("must not delete") },
            onDataChanged = {},
            onListensMoved = { error("no listen moved") },
        )
        assertEquals(before, history.allEvents())
    }

    @Test
    fun listensMovedBeforeALaterGroupFailedAreStillAnnounced() = runTest {
        val playlists = DefaultPlaylists(PlaylistMemory())
        val backing = DefaultFavorites(FavoritesMemory()).apply { replace(listOf(TrackId("4"))) }
        val favorites = object : Favorites by backing {
            override suspend fun replaceIfUnchanged(expected: List<TrackId>, replacement: List<TrackId>) = false
        }
        val history = historyOf(listen("2", 200), listen("4", 300))
        var announced = 0
        assertFailsWith<IllegalStateException> {
            performDuplicateMerge(
                listOf(selection(track("1"), track("2")), selection(track("3"), track("4"))), false, playlists, favorites, history,
                onHideTracks = { error("must keep files on conflict") },
                onDeleteTracks = { error("must not delete") },
                onDataChanged = {},
                onListensMoved = { announced++ },
            )
        }
        // The first group's listens moved; the second group's merge was refused and its copy keeps its own.
        assertEquals(1, announced)
        assertEquals(listOf("1", "4"), history.allEvents().map { it.trackId.value })
    }
}
