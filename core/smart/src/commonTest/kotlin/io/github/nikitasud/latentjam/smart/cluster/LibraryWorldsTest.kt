/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a region of the library ends up being called.
 *
 * The rule that matters is not which name wins but that the name and the tracks beneath it come
 * from the same selection: a row that says one genre and shows another is the single failure this
 * surface has already been burned by.
 */
class LibraryWorldsTest {

    private val dim = 8
    private val random = Random(19)

    private fun vector(angle: Double, spread: Double = 0.04): FloatArray =
        FloatArray(dim) { d ->
            val base = when (d) {
                0 -> cos(angle)
                1 -> sin(angle)
                else -> 0.0
            }
            (base + if (spread > 0.0) random.nextDouble(-spread, spread) else 0.0).toFloat()
        }

    private class Corpus {
        val tracks = mutableListOf<TrackDescriptor>()
        val vectors = mutableMapOf<TrackId, FloatArray>()
    }

    private fun corpus(build: Corpus.() -> Unit): Corpus = Corpus().apply(build)

    private fun Corpus.add(
        id: String,
        angle: Double,
        title: String? = "T$id",
        artist: String? = null,
        genre: String? = null,
        spread: Double = 0.04,
    ) {
        val trackId = TrackId(id)
        tracks += TrackDescriptor(id = trackId, title = title, artist = artist, genre = genre)
        vectors[trackId] = vector(angle, spread)
    }

    private fun Corpus.discover(k: Int = 2, minSize: Int = 4) =
        LibraryWorlds.discover(tracks, vectors, dim, k = k, minSize = minSize)

    @Test
    fun `a world is named after the genre its members share`() {
        val library = corpus {
            repeat(10) { add("rap$it", angle = 0.3, genre = "Hip-Hop", artist = "Artist$it") }
            repeat(10) { add("rock$it", angle = 3.5, genre = "Hard Rock", artist = "Band$it") }
        }
        val worlds = library.discover()
        assertEquals(setOf("Hip-Hop", "Hard Rock"), worlds.map { it.name }.toSet())
    }

    @Test
    fun `spellings of one genre are counted as one, and the cover supplies the words`() {
        val library = corpus {
            // Trap, Phonk and Hip-Hop are one family. Counted separately none of the three reaches
            // the share a claim needs, so a rule that grouped by raw tag would find nothing to say
            // about a region that is unambiguously rap.
            repeat(5) { add("a$it", angle = 0.3, genre = "Hip-Hop", artist = "Artist$it") }
            repeat(5) { add("b$it", angle = 0.3, genre = "Phonk", artist = "Other$it") }
            repeat(5) { add("c$it", angle = 0.3, genre = "Trap", artist = "Third$it") }
        }
        val world = library.discover(k = 1).single()
        assertEquals(15, world.tracks.size)
        // The internal family key is "rap"; what the listener reads is the tag on the record shown.
        assertEquals(world.representative.genre, world.name)
        assertTrue(world.name in setOf("Hip-Hop", "Phonk", "Trap"), "unexpected label ${world.name}")
    }

    @Test
    fun `a genre the cover does not share is not claimed for the world`() {
        val library = corpus {
            // One track sits exactly at the centre and will be the medoid; it is tagged unlike the
            // nineteen around it.
            add("centre", angle = 0.3, spread = 0.0, genre = "Ambient", artist = "Alone", title = "Drift")
            repeat(19) { add("rock$it", angle = 0.3, genre = "Hard Rock", artist = "Band$it") }
            repeat(12) { add("other$it", angle = 3.5, genre = "Disco", artist = "Other$it") }
        }
        val world = library.discover().first { it.tracks.any { track -> track.id.value == "centre" } }
        // The fixture is only meaningful if the odd track really is the one on the cover.
        assertEquals("centre", world.representative.id.value)
        // Nineteen of twenty are Hard Rock, but the record on the cover is not, and the cover is
        // what the row actually says. Announcing a genre the art contradicts is the one failure
        // this surface has already paid for.
        assertTrue(world.name != "Hard Rock", "the label contradicted the cover")
        assertEquals("Drift", world.name)
    }

    @Test
    fun `a genre only a minority shares is not claimed`() {
        val library = corpus {
            repeat(3) { add("tagged$it", angle = 0.3, genre = "Jazz", artist = "Artist$it") }
            repeat(12) { add("untagged$it", angle = 0.3, genre = null, artist = "Artist$it") }
        }
        val world = library.discover(k = 1).single()
        assertTrue(world.name != "Jazz", "three tags in fifteen tracks named the whole world")
    }

    @Test
    fun `with no shared genre a world falls back to its dominant artist`() {
        val library = corpus {
            repeat(12) { add("own$it", angle = 0.3, artist = "The Same Band", genre = null) }
            repeat(3) { add("guest$it", angle = 0.3, artist = "Guest$it", genre = null) }
        }
        val world = library.discover(k = 1).single()
        assertEquals("The Same Band", world.name)
    }

    @Test
    fun `with nothing shared a world is named after the track at its centre`() {
        val library = corpus {
            repeat(12) { add("t$it", angle = 0.3, title = "Song $it", artist = "Artist$it", genre = null) }
        }
        val world = library.discover(k = 1).single()
        // Named after the medoid, so the words and the cover are the same track by construction.
        assertEquals(world.representative.title, world.name)
    }

    @Test
    fun `the biggest world leads, and every world keeps its medoid first`() {
        val library = corpus {
            repeat(20) { add("big$it", angle = 0.3, genre = "Disco", artist = "Artist$it") }
            repeat(6) { add("small$it", angle = 3.5, genre = "Techno", artist = "Other$it") }
        }
        val worlds = library.discover()
        assertEquals(listOf("Disco", "Techno"), worlds.map { it.name })
        // The representative is taken from position 0 rather than recomputed, so the cover on a
        // card and the track SMART is seeded from cannot come apart.
        assertTrue(worlds.all { it.representative == it.tracks.first() })
    }

    @Test
    fun `a world nothing can be named after is not offered`() {
        val library = corpus {
            repeat(12) { add("t$it", angle = 0.3, title = null, artist = null, genre = null) }
        }
        assertTrue(library.discover(k = 1).isEmpty(), "an unnameable region became a card")
    }

    @Test
    fun `tracks the index has not reached yet are absent, not pooled together`() {
        val library = corpus {
            repeat(10) { add("known$it", angle = 0.3, genre = "Disco", artist = "Artist$it") }
            repeat(10) { add("known2$it", angle = 3.4, genre = "Techno", artist = "Other$it") }
        }
        // Ten tracks the encoder has not gotten to. Pooled, they would look like a real region.
        val unindexed = (1..10).map { index ->
            TrackDescriptor(id = TrackId("cold$index"), title = "Cold $index", genre = "Ska")
        }
        val worlds = LibraryWorlds.discover(library.tracks + unindexed, library.vectors, dim, k = 2)
        val named = worlds.flatMap { it.tracks }.map { it.id }
        assertTrue(unindexed.none { it.id in named }, "an unindexed track was placed in a world")
        assertNull(worlds.firstOrNull { it.name == "Ska" })
    }

    @Test
    fun `an empty library has no worlds`() {
        assertTrue(LibraryWorlds.discover(emptyList(), emptyMap(), dim).isEmpty())
        val library = corpus { repeat(6) { add("t$it", angle = 0.3, genre = "Disco") } }
        assertTrue(LibraryWorlds.discover(library.tracks, emptyMap(), dim).isEmpty())
    }
}
