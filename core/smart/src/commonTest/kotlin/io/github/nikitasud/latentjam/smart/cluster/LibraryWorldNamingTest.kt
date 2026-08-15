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
