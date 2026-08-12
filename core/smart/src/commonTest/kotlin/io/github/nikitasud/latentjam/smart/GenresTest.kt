/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
