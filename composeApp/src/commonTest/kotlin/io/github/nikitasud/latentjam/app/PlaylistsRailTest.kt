/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.AutoPlaylistKind
import io.github.nikitasud.latentjam.library.AutoPlaylists
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The rail at the top of the Playlists page (PlaylistsTab). */
internal class PlaylistsRailTest {
    @Test
    fun theRailShowsTheFourPlaylistsAndNoSuggestionLists() {
        val day = 24L * 60 * 60 * 1000
        val unheard = TrackDescriptor(id = TrackId("unheard"), addedAtMs = 9 * day)
        val oldFavourite = TrackDescriptor(id = TrackId("old"), addedAtMs = day)
        // A library with an unheard track and a long-rested favourite: 0.9.2 put "Never played" and
        // "Rediscover" on the rail for it.
        val auto = AutoPlaylists.build(
            tracks = listOf(unheard, oldFavourite),
            playCounts = mapOf(oldFavourite.id to 20),
            lastPlayedAtMs = mapOf(oldFavourite.id to day),
            favorites = listOf(oldFavourite.id),
        )

        val rail = featuredAutoPlaylists(auto)

        assertEquals(
            listOf(
                AutoPlaylistKind.FAVORITES,
                AutoPlaylistKind.RECENTLY_ADDED,
                AutoPlaylistKind.MOST_PLAYED,
                AutoPlaylistKind.RECENTLY_PLAYED,
            ),
            rail.map { it.kind },
        )
    }

    @Test
    fun anEmptyLibraryStillShowsTheFourCards() {
        assertEquals(4, featuredAutoPlaylists(emptyList()).size)
        assertTrue(featuredAutoPlaylists(emptyList()).all { it.tracks.isEmpty() })
    }
}
