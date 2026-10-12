/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.DefaultPlaylists
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.library.PlaylistSort
import io.github.nikitasud.latentjam.library.PlaylistStore
import io.github.nikitasud.latentjam.library.SongSortDirection
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.*

internal class PlaylistTrackReorderTest {
    private fun ids(vararg values: String) = values.map(::TrackId)

    @Test fun movesTheChosenDuplicateAcrossUnavailableEntries() {
        val stored = listOf("missing-start", "a", "missing-middle", "b", "a", "c", "missing-end")
        assertEquals(ids("missing-start", "a", "a", "missing-middle", "b", "c", "missing-end"),
            playlistOrderAfterMove(stored, ids("a", "b", "a", "c"), 2, 0))
        assertEquals(ids("missing-start", "missing-middle", "b", "a", "c", "a", "missing-end"),
            playlistOrderAfterMove(stored, ids("a", "b", "a", "c"), 0, 3))
    }

    @Test fun everyMovePreservesMembershipAndHiddenRelativeOrder() {
        val stored = listOf("hidden1", "a", "hidden2", "b", "a", "hidden3", "c", "hidden4")
        val visible = ids("a", "b", "a", "c")
        for (from in visible.indices) for (to in visible.indices) {
            if (from == to) continue
            val result = assertNotNull(playlistOrderAfterMove(stored, visible, from, to)).map { it.value }
            assertEquals(stored.groupingBy { it }.eachCount(), result.groupingBy { it }.eachCount())
            assertEquals(stored.filter { it.startsWith("hidden") }, result.filter { it.startsWith("hidden") })
            val expected = visible.toMutableList().apply { add(to, removeAt(from)) }
            assertEquals(expected, result.filterNot { it.startsWith("hidden") }.map(::TrackId))
        }
    }

    @Test fun staleSortedIncompleteAndInvalidMovesAreRejected() {
        val stored = listOf("a", "missing", "b", "a")
        assertNull(playlistOrderAfterMove(stored, ids("a", "a", "b"), 0, 1))
        assertNull(playlistOrderAfterMove(stored, ids("a", "b"), 0, 1))
        assertNull(playlistOrderAfterMove(stored, ids("a", "b", "a", "new"), 0, 1))
        assertNull(playlistOrderAfterMove(stored, ids("a", "b", "a"), -1, 1))
        assertNull(playlistOrderAfterMove(stored, ids("a", "b", "a"), 0, 3))
        assertNull(playlistOrderAfterMove(stored, ids("a", "b", "a"), 1, 1))
    }

    @Test fun durableReorderSurvivesReloadAndRejectsStaleEdits() = runTest {
        var saved = emptyList<String>()
        var failWrite = false
        val store = object : PlaylistStore {
            override suspend fun read() = saved
            override suspend fun write(lines: List<String>) {
                if (failWrite) error("disk full")
                saved = lines
            }
        }
        val playlists = DefaultPlaylists(store)
        val stored = listOf("a", "missing", "b", "a", "c")
        playlists.replaceAll(listOf(Playlist("p", "Mix", stored)))
        val replacement = assertNotNull(playlistOrderAfterMove(stored, ids("a", "b", "a", "c"), 2, 0))
        failWrite = true
        assertFailsWith<IllegalStateException> { playlists.replaceTracksIfUnchanged("p", stored.map(::TrackId), replacement) }
        assertEquals(stored, playlists.all().single().trackIds)
        assertEquals(stored, DefaultPlaylists(store).all().single().trackIds)
        failWrite = false
        assertTrue(playlists.replaceTracksIfUnchanged("p", stored.map(::TrackId), replacement))
        assertEquals(replacement.map { it.value }, DefaultPlaylists(store).all().single().trackIds)
        assertFalse(playlists.replaceTracksIfUnchanged("p", stored.map(::TrackId), ids("c", "b", "a")))
        assertFalse(playlists.replaceTracksIfUnchanged("deleted", replacement, replacement.reversed()))
    }

    @Test fun switchingBackToCustomKeepsInterleavedDuplicateOccurrences() {
        val a = TrackDescriptor(id = TrackId("a"), title = "A")
        val b = TrackDescriptor(id = TrackId("b"), title = "B")
        val page = CollectionSelection("Mix", null, null, listOf(a, a, b), playlistId = "p")
        assertEquals(listOf(a, b, a), page.inChosenOrder(PlaylistSortOrders(), ids("a", "missing", "b", "a")).tracks)
    }

    @Test fun recentSettingsRetainBothDirectionsAndCustomRemainsDefault() {
        for (direction in listOf("ascending", "descending")) {
            val restored = playlistSortOrdersFromPersisted("LJPS1|recent:descending|1:p=recent:$direction;")
            val expected = SortChoice(PlaylistSort.RECENT,
                if (direction == "ascending") SongSortDirection.ASCENDING else SongSortDirection.DESCENDING)
            assertEquals(expected, restored.forPlaylist("p"))
            assertEquals(DEFAULT_PLAYLIST_TRACK_SORT, restored.forPlaylist("new"))
            assertEquals(DEFAULT_FAVORITES_TRACK_SORT, restored.favorites)
        }
        val old = PlaylistSortOrders(playlists = mapOf("p" to SortChoice(PlaylistSort.RECENT, SongSortDirection.DESCENDING)))
        assertEquals(old, old.normalized())
        assertEquals(old, playlistSortOrdersFromPersisted(encodePlaylistSortOrders(old)))
    }

    @Test fun handleHitAreaTracksLayoutDirectionAndDoesNotCaptureTheRowBody() {
        assertTrue(isReorderHandle(290f, 320, 56f, false))
        assertFalse(isReorderHandle(180f, 320, 56f, false))
        assertTrue(isReorderHandle(30f, 320, 56f, true))
        assertFalse(isReorderHandle(180f, 320, 56f, true))
        assertFalse(isReorderHandle(-1f, 320, 56f, true))
        assertFalse(isReorderHandle(321f, 320, 56f, false))
    }

    @Test fun backupMergeKeepsOccurrenceCountsAndIsIdempotent() {
        val existing = listOf("a", "b", "a")
        val imported = listOf("b", "a", "a", "a", "c", "c")
        val merged = mergePlaylistOccurrences(existing, imported)
        assertEquals(listOf("a", "b", "a", "a", "c", "c"), merged)
        assertEquals(merged, mergePlaylistOccurrences(merged, imported))
    }
}
