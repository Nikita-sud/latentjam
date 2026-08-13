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
}
