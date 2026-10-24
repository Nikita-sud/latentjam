/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

internal class StatsMemoryTest {
    private val playlists = listOf("a")
    private val favorites = listOf("b")
    private val inputs = StatsMemory.Inputs(7, 0, 100, playlists, favorites)

    @Test
    fun theSameInputsReuseAResult() {
        assertEquals(inputs, StatsMemory.Inputs(7, 0, 100, playlists, favorites))
    }

    @Test
    fun anyChangeTheResultsDependOnRecomputesThem() {
        assertNotEquals(inputs, StatsMemory.Inputs(8, 0, 100, playlists, favorites), "a new listen")
        assertNotEquals(inputs, StatsMemory.Inputs(7, 1, 100, playlists, favorites), "a retry")
        assertNotEquals(inputs, StatsMemory.Inputs(7, 0, 101, playlists, favorites), "a new day")
        // Equal contents, but published anew: an edit always publishes a new list.
        assertNotEquals(inputs, StatsMemory.Inputs(7, 0, 100, listOf("a"), favorites), "edited playlists")
        assertNotEquals(inputs, StatsMemory.Inputs(7, 0, 100, playlists, listOf("b")), "edited favourites")
    }
}
