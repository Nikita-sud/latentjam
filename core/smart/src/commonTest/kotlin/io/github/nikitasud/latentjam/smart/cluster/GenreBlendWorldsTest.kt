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

class GenreBlendWorldsTest {
    private fun world(genres: List<String?>): LibraryWorld {
        val tracks = genres.mapIndexed { index, genre ->
            TrackDescriptor(TrackId("synthetic-$index"), title = "Example $index", genre = genre)
        }
        return LibraryWorlds.discover(
            library = tracks,
            vectors = tracks.associate { it.id to floatArrayOf(1f, 0f, 0f, 0f) },
            dim = 4,
            k = 1,
        ).single()
    }

    @Test
    fun `a supported blend names the region without dropping its coherent minority`() {
        val world = world(List(20) { "Jazz" } + List(45) { "Pop" } + List(30) { "Rock" } + List(5) { "Pop; Rock" })

        assertEquals("Pop / Rock", world.name)
        assertEquals(LibraryWorldNameSource.GENRE_BLEND, world.nameSource)
        assertEquals(setOf("pop", "rock"), world.blendFamilies)
        assertEquals(100, world.tracks.size)
        assertEquals(100, world.tracks.map { it.id }.toSet().size)
        assertEquals("Pop", world.representative.genre)
        assertTrue(world.supportsName(TrackDescriptor(TrackId("rock-cover"), genre = "Rock")))
        assertFalse(world.supportsName(TrackDescriptor(TrackId("jazz-cover"), genre = "Jazz")))
    }

    @Test
    fun `overlapping genre votes do not count the same tracks twice`() {
        val world = world(List(15) { "Pop; Rock" } + List(15) { null })
        assertEquals(LibraryWorldNameSource.GENERIC, world.nameSource)
    }

    @Test
    fun `the two genres need their own substantial groups`() {
        val world = world(List(45) { "Pop; Rock" } + List(9) { "Pop" } + List(13) { "Rock" } + List(33) { null })
        assertEquals(LibraryWorldNameSource.GENERIC, world.nameSource)
    }

    @Test
    fun `two styles below combined coverage cannot hide missing evidence`() {
        val world = world(List(6) { "Pop" } + List(6) { "Rock" } + List(6) { "Jazz" } + List(12) { null })
        assertEquals(LibraryWorldNameSource.GENERIC, world.nameSource)
    }

    @Test
    fun `a small secondary style cannot dress up a weak majority`() {
        val world = world(List(51) { "Rap" } + List(2) { "Folk" } + List(47) { null })
        assertEquals(LibraryWorldNameSource.GENERIC, world.nameSource)
    }

    @Test
    fun `a pure genre keeps priority over a blend`() {
        val world = world(List(6) { "Pop" } + List(4) { "Rock" })
        assertEquals("Pop", world.name)
        assertEquals(LibraryWorldNameSource.GENRE, world.nameSource)
    }

    @Test
    fun `each side must meet the minimum support count even in a small library`() {
        assertEquals(LibraryWorldNameSource.GENERIC, world(listOf("Pop", "Pop", "Rock", "Rock")).nameSource)
        assertEquals(LibraryWorldNameSource.GENRE_BLEND, world(List(4) { "Pop" } + List(4) { "Rock" }).nameSource)
    }

    @Test
    fun `equal coverage prefers stable centrality order`() {
        val world = world(List(8) { "Pop" } + List(6) { "Rock" } + List(6) { "Jazz" })
        assertEquals("Pop / Rock", world.name)
    }

    @Test
    fun `a playlist can still override a blend and clears its genre claim`() {
        val original = world(List(4) { "Pop" } + List(4) { "Rock" })
        val renamed = LibraryWorlds.namedAfterGroups(
            listOf(original), listOf("Example playlist" to original.tracks.mapTo(HashSet()) { it.id }),
        ).single()
        assertEquals("Example playlist", renamed.name)
        assertEquals(LibraryWorldNameSource.PLAYLIST, renamed.nameSource)
        assertTrue(renamed.blendFamilies.isEmpty())
    }

    @Test
    fun `an explicit meme genre routes novelty even when the title says nothing`() {
        val world = world(List(4) { "Internet meme" })
        assertEquals(LibraryWorldContent.NOVELTY, world.content)
        assertEquals(LibraryWorldSemanticTitle.MEME_VIRAL_AUDIO, world.semanticTitle)
    }

    @Test
    fun `an explicit sound effects genre stays out of ordinary music regions`() {
        val world = world(List(4) { "Sound Effects" })
        assertEquals(LibraryWorldContent.SOUND_EFFECTS, world.content)
        assertEquals(LibraryWorldSemanticTitle.SOUND_EFFECTS, world.semanticTitle)
    }
}
