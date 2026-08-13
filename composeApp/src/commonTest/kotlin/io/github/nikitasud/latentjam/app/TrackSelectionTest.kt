/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class TrackSelectionTest {

    private val a = TrackDescriptor(id = TrackId("a"))
    private val b = TrackDescriptor(id = TrackId("b"))
    private val c = TrackDescriptor(id = TrackId("c"))
    private val other = TrackId("other")

    // ---------------------------------------------------------------- selectsAllOf

    @Test
    fun groupReadsSelectedOnlyWhenEveryTrackIsSelected() {
        val group = listOf(a, b)
        assertFalse(emptySet<TrackId>().selectsAllOf(group))
        assertFalse(setOf(a.id).selectsAllOf(group))
        assertTrue(setOf(a.id, b.id).selectsAllOf(group))
        assertTrue(setOf(a.id, b.id, other).selectsAllOf(group))
    }

    @Test
    fun emptyGroupNeverReadsSelected() {
        assertFalse(setOf(a.id).selectsAllOf(emptyList()))
    }

    // ---------------------------------------------------------------- toggleTracks

    @Test
    fun longPressOnUnselectedGroupSelectsAllItsTracks() {
        assertEquals(
            setOf(a.id, b.id),
            emptySet<TrackId>().toggleTracks(listOf(a, b)),
        )
    }

    @Test
    fun togglingPartiallySelectedGroupCompletesItInsteadOfInverting() {
        // A checkbox promises "checked = the whole group": tapping a half-selected
        // album checks it, it does not flip each track.
        assertEquals(
            setOf(a.id, b.id, c.id, other),
            setOf(a.id, other).toggleTracks(listOf(a, b, c)),
        )
    }

    @Test
    fun togglingFullySelectedGroupRemovesExactlyItsTracks() {
        assertEquals(
            setOf(other),
            setOf(a.id, b.id, other).toggleTracks(listOf(a, b)),
        )
    }
}
