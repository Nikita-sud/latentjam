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
    fun neverPlayedListsUnplayedTracksNewestAdditionFirst() {
        val oldUnheard = TrackDescriptor(id = TrackId("old"), addedAtMs = 1_000)
        val newUnheard = TrackDescriptor(id = TrackId("new"), addedAtMs = 9_000)
        val played = TrackDescriptor(id = TrackId("played"), addedAtMs = 5_000)

        val derived = AutoPlaylists.build(
            tracks = listOf(oldUnheard, played, newUnheard),
            playCounts = mapOf(played.id to 2),
            lastPlayedAtMs = mapOf(played.id to 8_000),
        )

        val neverPlayed = derived.single { it.kind == AutoPlaylistKind.NEVER_PLAYED }
        assertEquals(listOf(newUnheard, oldUnheard), neverPlayed.tracks)
    }

    @Test
    fun fullyPlayedLibraryProducesNoNeverPlayedPlaylist() {
        val derived = AutoPlaylists.build(
            tracks = listOf(a),
            playCounts = mapOf(a.id to 1),
            lastPlayedAtMs = mapOf(a.id to 1_000),
        )
        assertTrue(derived.none { it.kind == AutoPlaylistKind.NEVER_PLAYED })
    }

    @Test
    fun rediscoverSurfacesOnceLovedTracksThatRested() {
        val day = 24L * 60 * 60 * 1000
        val now = 400 * day
        val restedLoved = TrackDescriptor(id = TrackId("rested-loved"))
        val restedHearted = TrackDescriptor(id = TrackId("rested-hearted"))
        val freshLoved = TrackDescriptor(id = TrackId("fresh-loved"))
        val restedCasual = TrackDescriptor(id = TrackId("rested-casual"))
        val neverPlayedHearted = TrackDescriptor(id = TrackId("never-played-hearted"))

        val derived = AutoPlaylists.build(
            tracks = listOf(restedCasual, freshLoved, restedHearted, restedLoved, neverPlayedHearted),
            playCounts = mapOf(
                restedLoved.id to 12,
                freshLoved.id to 12,
                restedCasual.id to 1,
                restedHearted.id to 1,
            ),
            lastPlayedAtMs = mapOf(
                restedLoved.id to now - 100 * day,
                freshLoved.id to now - 2 * day,
                restedCasual.id to now - 100 * day,
                restedHearted.id to now - 60 * day,
            ),
            favorites = listOf(restedHearted.id, neverPlayedHearted.id),
            nowMs = now,
        )

        val rediscover = derived.single { it.kind == AutoPlaylistKind.REDISCOVER }
        assertEquals(listOf(restedLoved, restedHearted), rediscover.tracks)
    }

    @Test
    fun rediscoverNeedsAClockToJudgeRest() {
        val day = 24L * 60 * 60 * 1000
        val derived = AutoPlaylists.build(
            tracks = listOf(a),
            playCounts = mapOf(a.id to 10),
            lastPlayedAtMs = mapOf(a.id to 1 * day),
        )
        assertTrue(derived.none { it.kind == AutoPlaylistKind.REDISCOVER })
    }
}
