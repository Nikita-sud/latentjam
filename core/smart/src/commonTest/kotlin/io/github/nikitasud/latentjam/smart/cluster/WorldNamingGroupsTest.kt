/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a region may be named after: live playlist members, and albums that are one artist's. */
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
    fun aOneComposerSoundtrackStillNamesItsRegion() {
        val score = (1..12).map { track("ost$it", artist = "Yugo Kanno", album = "JoJo OST") }
        val others = (1..4).map { track("other$it", artist = "Someone $it") }
        val region = world("Film Score", score + others)

        val naming = WorldNamingGroups.of(
            library = idsOf(score + others),
            playlists = emptyList(),
            albums = listOf("JoJo OST" to score),
        )
        val renamed = LibraryWorlds.namedAfterGroups(listOf(region), naming).single()

        assertEquals("JoJo OST", renamed.name)
        assertEquals(LibraryWorldNameSource.ALBUM, renamed.nameSource)
    }

    @Test
    fun aPlaceholderAlbumOfUnrelatedArtistsDoesNotNameARegion() {
        // From a real library: 65 tracks by dozens of artists carried the placeholder album
        // "Music", and a region of meme clips was named "Music" after them.
        val bucket = (1..12).map { track("music$it", artist = "Artist $it", album = "Music") } +
            listOf(track("music-unknown", artist = "Unknown", album = "Music"))
        val region = world("Meme & Viral Audio", bucket.take(10))

        val naming = WorldNamingGroups.of(
            library = idsOf(bucket),
            playlists = emptyList(),
            albums = listOf("Music" to bucket),
        )
        val renamed = LibraryWorlds.namedAfterGroups(listOf(region), naming).single()

        assertTrue(naming.albums.isEmpty())
        assertEquals("Meme & Viral Audio", renamed.name)
    }

    @Test
    fun anAlbumIsOneArtistsWhenThatArtistHasHalfOfIt() {
        assertTrue(
            WorldNamingGroups.looksLikeAlbum(
                (1..5).map { track("a$it", artist = "Band") } + (1..5).map { track("b$it", artist = "Guest $it") },
            ),
        )
        assertFalse(
            WorldNamingGroups.looksLikeAlbum(
                (1..4).map { track("a$it", artist = "Band") } + (1..5).map { track("b$it", artist = "Guest $it") },
            ),
        )
    }

    @Test
    fun theAlbumArtistMakesASoundtrackWithManySingersOneRelease() {
        val vocals = (1..8).map { track("v$it", artist = "Singer $it", albumArtist = "Hiroyuki Sawano") }
        assertTrue(WorldNamingGroups.looksLikeAlbum(vocals))
    }

    @Test
    fun aVariousArtistsCompilationIsNotOneArtistsAlbum() {
        val compilation = (1..8).map { track("c$it", artist = "Artist $it", albumArtist = "Various Artists") }
        assertFalse(WorldNamingGroups.looksLikeAlbum(compilation))
        assertFalse(WorldNamingGroups.looksLikeAlbum((1..8).map { track("u$it", artist = "<unknown>") }))
    }

    @Test
    fun featuredGuestsDoNotSplitTheLeadArtist() {
        val album = listOf(
            track("1", artist = "Drake"),
            track("2", artist = "Drake feat. Rihanna"),
            track("3", artist = "Drake (ft. Future)"),
            track("4", artist = "Drake featuring Lil Wayne"),
            track("5", artist = "Drake; 21 Savage"),
            track("6", artist = "Someone Else"),
        )
        assertTrue(WorldNamingGroups.looksLikeAlbum(album))
        assertTrue(
            WorldNamingGroups.looksLikeAlbum(
                (1..4).map { track("t$it", artist = "X & Y", artists = listOf("X", "Y")) },
            ),
        )
    }

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

    @Test
    fun aPlaylistStillWinsAnEqualClaimOverAnAlbum() {
        val region = (1..6).map { track("t$it", artist = "Band", album = "The Album") }
        val naming = WorldNamingGroups.of(
            library = idsOf(region),
            playlists = listOf("Road Trip" to region.map { it.id }),
            albums = listOf("The Album" to region),
        )
        val renamed = LibraryWorlds.namedAfterGroups(listOf(world("Rock", region)), naming).single()

        assertEquals("Road Trip", renamed.name)
        assertEquals(LibraryWorldNameSource.PLAYLIST, renamed.nameSource)
    }
}
