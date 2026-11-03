/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenresTest {

    @Test
    fun `aliases match tokens instead of fragments inside unrelated words`() {
        assertEquals("chiptune", Genres.normalize("Chiptune"))
        assertEquals("trapeze", Genres.normalize("Trapeze"))
        assertEquals("popular", Genres.normalize("Popular"))
    }

    @Test
    fun `punctuation separated aliases still resolve and alias priority is stable`() {
        assertEquals("rap", Genres.normalize("Hip-Hop"))
        assertEquals("rap", Genres.normalize("Brazilian Phonk"))
        assertEquals("rock", Genres.normalize("Pop / Rock"))
    }

    @Test
    fun `compound dance and pop names join their families without substring matching`() {
        for (genre in listOf("Eurodance", "HI-NRG", "Hi NRG", "Hard Trance")) {
            assertEquals("dance", Genres.normalize(genre), genre)
        }
        assertEquals("pop", Genres.normalize("Europop"))
        assertEquals("eurodancer", Genres.normalize("Eurodancer"))
        assertEquals("transcendental", Genres.normalize("Transcendental"))
        assertEquals("rock", Genres.normalize("Eurodance Rock"))
    }

    @Test
    fun familiesOfAJoinedTagCarryEveryValue() {
        // "Dirty Harry": five Vorbis GENRE fields joined canonically. Every value adds its families
        // (Trip Hop is electronic and hip hop); the set is what the chain compares by overlap.
        val families = Genres.families("Electronic; Rock; Trip Hop; Alternative Rock; Hip Hop")
        assertEquals(setOf("electronic", "rock", "hiphop"), families)
        // The Map names worlds in the coarse naming vocabulary, value by value.
        val named = Genres.nameFamilies("Electronic; Rock; Trip Hop; Alternative Rock; Hip Hop")
        assertTrue("dance" in named)
        assertTrue("rock" in named)
        assertTrue("rap" in named)
    }

    @Test
    fun familiesOfASingleTagEqualsItsNormalization() {
        // The naming vocabulary of one value is its normalization; the chain's set carries the parent too.
        assertEquals(setOf(Genres.normalize("Phonk")), Genres.nameFamilies("Phonk"))
        assertEquals(setOf("hiphop"), Genres.families("Phonk"))
        assertEquals(setOf("metal", "rock"), Genres.families("Metalcore"))
        assertEquals(emptySet<String>(), Genres.families(null))
        assertEquals(emptySet<String>(), Genres.families("<unknown>"))
        assertEquals(emptySet<String>(), Genres.nameFamilies("<unknown>"))
    }
}
