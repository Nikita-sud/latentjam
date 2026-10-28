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

internal class AutoPlaylistsTest {

    private val a = TrackDescriptor(id = TrackId("a"))
    private val b = TrackDescriptor(id = TrackId("b"))

    @Test
    fun favoritesKeepTheirOwnOrderAndDropDeletedTracks() {
        val derived = AutoPlaylists.build(
            tracks = listOf(a, b),
            playCounts = emptyMap(),
            lastPlayedAtMs = emptyMap(),
            favorites = listOf(TrackId("b"), TrackId("deleted"), TrackId("a")),
        )
        val favorites = derived.single { it.kind == AutoPlaylistKind.FAVORITES }
        assertEquals(listOf(b, a), favorites.tracks)
    }

    @Test
    fun emptyFavoritesProduceNoPlaylist() {
        val derived = AutoPlaylists.build(
            tracks = listOf(a),
            playCounts = emptyMap(),
            lastPlayedAtMs = emptyMap(),
        )
        assertTrue(derived.none { it.kind == AutoPlaylistKind.FAVORITES })
    }

    @Test
    fun suggestionListsAreNotPlaylists() {
        // Never played and forgotten favourites are For You's; the playlists are the four kinds.
        val day = 24L * 60 * 60 * 1000
        val derived = AutoPlaylists.build(
            tracks = listOf(a, b),
            playCounts = mapOf(a.id to 12),
            lastPlayedAtMs = mapOf(a.id to day),
            favorites = listOf(a.id),
        )
        assertEquals(
            listOf(AutoPlaylistKind.FAVORITES, AutoPlaylistKind.MOST_PLAYED, AutoPlaylistKind.RECENTLY_PLAYED),
            derived.map { it.kind },
        )
        assertEquals(
            listOf("FAVORITES", "RECENTLY_ADDED", "MOST_PLAYED", "RECENTLY_PLAYED"),
            AutoPlaylistKind.entries.map { it.name },
        )
    }
}
