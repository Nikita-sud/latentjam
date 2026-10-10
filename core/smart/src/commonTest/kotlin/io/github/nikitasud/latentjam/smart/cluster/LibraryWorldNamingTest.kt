/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

internal class LibraryWorldNamingTest {

    private fun track(id: String) = TrackDescriptor(id = TrackId(id))
    private fun world(name: String, ids: List<String>) = LibraryWorld(
        name = name,
        tracks = ids.map(::track),
    )

    private fun group(name: String, vararg ids: String) =
        name to ids.mapTo(HashSet(), ::TrackId)

    @Test
    fun aWorldMostlyInsideOnePlaylistTakesItsName() {
        val renamed = LibraryWorlds.namedAfterGroups(
            worlds = listOf(world("Mix 1", listOf("a", "b", "c", "d", "e"))),
            groups = listOf(group("Phonk", "a", "b", "c", "x", "y")),
        )
        assertEquals("Phonk", renamed.single().name)
        assertEquals(LibraryWorldNameSource.PLAYLIST, renamed.single().nameSource)
    }

    @Test
    fun weakOverlapKeepsTheGeneratedName() {
        val renamed = LibraryWorlds.namedAfterGroups(
            worlds = listOf(world("Mix 1", listOf("a", "b", "c", "d", "e"))),
            groups = listOf(group("Phonk", "a", "b")),
        )
        assertEquals("Mix 1", renamed.single().name)
        assertEquals(LibraryWorldNameSource.GENRE, renamed.single().nameSource)
    }

    @Test
    fun onePlaylistNamesOnlyItsBestWorld() {
        // The playlist covers both worlds, but the second is contained more fully; only that
        // one takes the name — two cards with the same title would be indistinguishable.
        val renamed = LibraryWorlds.namedAfterGroups(
            worlds = listOf(
                world("Mix 1", listOf("a", "b", "c", "z", "w")),
                world("Mix 2", listOf("d", "e", "f")),
            ),
            groups = listOf(group("Anime", "a", "b", "c", "d", "e", "f")),
        )
        assertEquals(listOf("Mix 1", "Anime"), renamed.map { it.name })
    }

    @Test
    fun namesDifferingOnlyByWhitespaceAndCaseNameOnlyOneWorld() {
        val renamed = LibraryWorlds.namedAfterGroups(
            worlds = listOf(
                world("Mix 1", listOf("a", "b", "c")),
                world("Mix 2", listOf("d", "e", "f")),
            ),
            groups = listOf(
                group("  Chill  ", "a", "b", "c"),
                group("chill", "d", "e", "f"),
            ),
        )

        assertEquals(listOf("Chill", "Mix 2"), renamed.map { it.name })
    }

    @Test
    fun firstGroupWinsAnEqualContainmentTie() {
        val renamed = LibraryWorlds.namedAfterGroups(
            worlds = listOf(world("Mix 1", listOf("a", "b", "c"))),
            // Callers put listener playlists before album groups; stable input-order tie breaking
            // is what makes the listener's own vocabulary win equal containment.
            groups = listOf(
                group("My playlist", "a", "b", "c"),
                group("Album title", "a", "b", "c"),
            ),
        )

        assertEquals("My playlist", renamed.single().name)
    }

    @Test
    fun aTightPlaylistOutranksABroadOneThatCoversTheRegionMoreFully() {
        // From a real library: the broad playlist covers 46 of the region's 48 tracks (95.8%) but
        // only a tenth of itself, while the tight one covers 35 (72.9%) and all but one of its own.
        // Ranking by containment alone named the region after the broad playlist, so the region a
        // listener knows by the tight playlist's name could not be found under it.
        val region = (1..48).map { "t$it" }
        val broad = region.take(46) + (1..400).map { "a$it" }
        val tight = region.take(35) + listOf("g1")

        val renamed = LibraryWorlds.namedAfterGroups(
            worlds = listOf(world("Anime", region)),
            groups = listOf(group("Anime", *broad.toTypedArray()), group("GTO", *tight.toTypedArray())),
        )

        assertEquals("GTO", renamed.single().name)
    }

    @Test
    fun anAlbumOverHalfTheRegionOutranksAPlaylistOverAllOfIt() {
        // Equal admission (both clear GROUP_NAME_CONTAINMENT), so the ranking decides: half the
        // region shared exactly beats the whole region shared with a much larger playlist.
        val region = (1..40).map { "t$it" }
        val album = region.take(20)
        val playlist = region + (1..160).map { "p$it" }

        val renamed = LibraryWorlds.namedAfterGroups(
            worlds = listOf(world("Rock", region)),
            groups = listOf(group("Everyday", *playlist.toTypedArray()), group("The Album", *album.toTypedArray())),
        )

        assertEquals("The Album", renamed.single().name)
    }

    @Test
    fun aNestedPlaylistNamesItsWorldBeforeTheBroaderPlaylistDoes() {
        // JoJo is wholly inside Anime; Jaccard favours the nested claim even where containment
        // cannot separate them, and the broad name stays free for the broader world.
        val renamed = LibraryWorlds.namedAfterGroups(
            worlds = listOf(
                world("Mix 1", (1..20).map { "j$it" }),
                world("Mix 2", (1..20).map { "a$it" }),
            ),
            groups = listOf(
                group("Anime", *((1..20).map { "j$it" } + (1..20).map { "a$it" }).toTypedArray()),
                group("JoJo", *(1..20).map { "j$it" }.toTypedArray()),
            ),
        )

        assertEquals(listOf("JoJo", "Anime"), renamed.map { it.name })
    }

    @Test
    fun theMostSpecificPlaylistNamesANestedWorld() {
        // Nested curation: every JoJo track also lives in the broader Anime playlist, so a
        // world of JoJo soundtracks is 100% contained in both. The tighter claim is the more
        // informative name — and the broad name stays free for a broader world.
        val renamed = LibraryWorlds.namedAfterGroups(
            worlds = listOf(
                world("Mix 1", listOf("j1", "j2", "j3", "j4")),
                world("Mix 2", listOf("a1", "a2", "a3", "j5")),
            ),
            groups = listOf(
                group("Anime", "a1", "a2", "a3", "a4", "j1", "j2", "j3", "j4", "j5"),
                group("JoJo", "j1", "j2", "j3", "j4", "j5"),
            ),
        )

        assertEquals(listOf("JoJo", "Anime"), renamed.map { it.name })
    }
}
