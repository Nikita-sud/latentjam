/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a region may be named after: the members of playlists and albums that still exist. */
internal class WorldNamingGroupsTest {

    private fun track(
        id: String,
        artist: String? = null,
        albumArtist: String? = null,
        album: String? = null,
        artists: List<String> = emptyList(),
    ) = TrackDescriptor(
        id = TrackId(id),
        artist = artist,
        albumArtist = albumArtist,
        album = album,
        artists = artists,
    )

    private fun world(name: String, tracks: List<TrackDescriptor>) = LibraryWorld(name = name, tracks = tracks)

    private fun idsOf(tracks: List<TrackDescriptor>): Set<TrackId> = tracks.mapTo(HashSet()) { it.id }

    @Test
    fun deadPlaylistEntriesDoNotCount() {
        // From a real library: "Anime" stored 459 ids, 161 of them still files. Counted whole, the
        // dead ids sank the playlist's overlap with a region it describes exactly.
        val region = (1..20).map { track("t$it") }
        val elsewhere = (1..10).map { track("e$it") }
        val dead = (1..300).map { TrackId("gone$it") }
        val library = idsOf(region + elsewhere)
        val playlists = listOf(
            "Anime" to region.map { it.id } + dead,
            "Broad" to region.take(14).map { it.id } + elsewhere.map { it.id },
        )

        val naming = WorldNamingGroups.of(library, playlists, albums = emptyList())
        val renamed = LibraryWorlds.namedAfterGroups(listOf(world("Discovery mix", region)), naming).single()

        assertEquals(20, naming.playlists.first { it.first == "Anime" }.second.size)
        assertEquals("Anime", renamed.name)
        assertEquals(LibraryWorldNameSource.PLAYLIST, renamed.nameSource)
        // Counted with its dead entries the broad playlist would have won instead.
        assertEquals(
            "Broad",
            LibraryWorlds.namedAfterGroups(
                listOf(world("Discovery mix", region)),
                playlists.map { (name, ids) -> name to ids.toSet() },
            ).single().name,
        )
    }

    @Test
    fun groupsWithNothingLeftAreDropped() {
        val kept = (1..3).map { track("k$it", artist = "Band", album = "Album") }
        val naming = WorldNamingGroups.of(
            library = idsOf(kept.take(1)),
            playlists = listOf("Gone" to listOf(TrackId("x"), TrackId("y")), " " to kept.map { it.id }),
            albums = listOf("Album" to kept, null to kept),
        )
        assertTrue(naming.playlists.isEmpty())
        // One live track is not an album to name a region after.
        assertTrue(naming.albums.isEmpty())
    }
}
