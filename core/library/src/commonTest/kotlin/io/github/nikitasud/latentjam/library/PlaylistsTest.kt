/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PlaylistsTest {

    private class FakeStore(initial: List<String> = emptyList()) : PlaylistStore {
        var lines: List<String> = initial
        override suspend fun read(): List<String> = lines
        override suspend fun write(lines: List<String>) { this.lines = lines }
    }

    @Test
    fun createAddAndReadBack() = runTest {
        val playlists = DefaultPlaylists(FakeStore())
        val created = playlists.create("Workout")
        playlists.addTracks(created.id, listOf(TrackId("1"), TrackId("2")))

        val stored = playlists.all().single()
        assertEquals("Workout", stored.name)
        assertContentEquals(listOf("1", "2"), stored.trackIds)
    }

    @Test
    fun addingTheSameTrackTwiceIsANoOp() = runTest {
        val playlists = DefaultPlaylists(FakeStore())
        val created = playlists.create("Mix")
        playlists.addTracks(created.id, listOf(TrackId("1")))
        playlists.addTracks(created.id, listOf(TrackId("1"), TrackId("2")))
        assertContentEquals(listOf("1", "2"), playlists.all().single().trackIds)
    }

    @Test
    fun removeAndDelete() = runTest {
        val playlists = DefaultPlaylists(FakeStore())
        val created = playlists.create("Mix")
        playlists.addTracks(created.id, listOf(TrackId("1"), TrackId("2")))
        playlists.removeTrack(created.id, TrackId("1"))
        assertContentEquals(listOf("2"), playlists.all().single().trackIds)

        playlists.delete(created.id)
        assertTrue(playlists.all().isEmpty())
    }

    @Test
    fun renameFallsBackForBlankNames() = runTest {
        val playlists = DefaultPlaylists(FakeStore())
        val created = playlists.create("Mix")
        playlists.rename(created.id, "   ")
        assertEquals("Untitled playlist", playlists.all().single().name)
    }

    @Test
    fun survivesReloadIncludingAwkwardNames() = runTest {
        val store = FakeStore()
        val playlists = DefaultPlaylists(store)
        val created = playlists.create("Rock | Metal, vol.2")
        playlists.addTracks(created.id, listOf(TrackId("7")))

        val reloaded = DefaultPlaylists(store).all().single()
        assertEquals("Rock | Metal, vol.2", reloaded.name)
        assertContentEquals(listOf("7"), reloaded.trackIds)
    }

    @Test
    fun corruptLinesAreSkipped() = runTest {
        val store = FakeStore(initial = listOf("nonsense", ""))
        assertTrue(DefaultPlaylists(store).all().isEmpty())
        assertNull(PlaylistSerializer.parse("a|b|c|d"))
    }

    // ------------------------------------------------------------ auto playlists

    private fun track(id: String, addedAtMs: Long? = null) =
        TrackDescriptor(id = TrackId(id), title = "t$id", addedAtMs = addedAtMs)

    @Test
    fun autoPlaylistsRankByRecencyAndCount() = runTest {
        val tracks = listOf(
            track("1", addedAtMs = 100),
            track("2", addedAtMs = 300),
            track("3", addedAtMs = 200),
        )
        val auto = AutoPlaylists.build(
            tracks = tracks,
            playCounts = mapOf(TrackId("1") to 5, TrackId("3") to 9),
            lastPlayedAtMs = mapOf(TrackId("1") to 50L, TrackId("3") to 90L),
        )
        assertEquals(3, auto.size)
        assertContentEquals(
            listOf("2", "3", "1"),
            auto.first { it.kind == AutoPlaylistKind.RECENTLY_ADDED }.tracks.map { it.id.value },
        )
        assertContentEquals(
            listOf("3", "1"),
            auto.first { it.kind == AutoPlaylistKind.MOST_PLAYED }.tracks.map { it.id.value },
        )
        assertContentEquals(
            listOf("3", "1"),
            auto.first { it.kind == AutoPlaylistKind.RECENTLY_PLAYED }.tracks.map { it.id.value },
        )
    }

    @Test
    fun emptyAutoPlaylistsAreDropped() = runTest {
        val auto = AutoPlaylists.build(
            tracks = listOf(track("1")), // no addedAt, never played
            playCounts = emptyMap(),
            lastPlayedAtMs = emptyMap(),
        )
        assertTrue(auto.isEmpty())
    }
}
