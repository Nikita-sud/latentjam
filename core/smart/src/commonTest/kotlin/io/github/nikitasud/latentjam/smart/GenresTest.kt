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
        // "Dirty Harry": five Vorbis GENRE fields joined canonically. Electronic and Hip Hop
        // resolve to their families; the set is what the chain intersects on.
        val families = Genres.families("Electronic; Rock; Trip Hop; Alternative Rock; Hip Hop")
        assertTrue("dance" in families)
        assertTrue("rock" in families)
        assertTrue("rap" in families)
    }

    @Test
    fun familiesOfASingleTagEqualsItsNormalization() {
        // The chain's parity depends on this degradation: single-genre libraries behave
        // exactly as before the multi-genre change.
        assertEquals(setOf(Genres.normalize("Phonk")), Genres.families("Phonk"))
        assertEquals(emptySet<String>(), Genres.families(null))
        assertEquals(emptySet<String>(), Genres.families("<unknown>"))
    }
}
