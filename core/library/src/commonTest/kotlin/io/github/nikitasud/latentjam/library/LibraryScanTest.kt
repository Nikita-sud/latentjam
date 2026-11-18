/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryScanTest {
    private fun track(id: String) = TrackDescriptor(id = TrackId(id))

    @Test
    fun whatAScanFoundAndDoesNotListIsNotShown() {
        val known = listOf(track("a"), track("hidden"), track("b"), track("in-a-folder-switched-off"))
        val shown = listOf(track("a"), track("b"))
        assertEquals(setOf(TrackId("hidden"), TrackId("in-a-folder-switched-off")), notShownIds(known, shown))
        assertEquals(emptySet(), notShownIds(known, known))
        assertEquals(known.map { it.id }.toSet(), notShownIds(known, emptyList()))
        // A song that is gone is in neither list, and so is not kept as one that is only out of sight.
        assertEquals(emptySet(), notShownIds(shown, shown + track("deleted")))
    }
}
