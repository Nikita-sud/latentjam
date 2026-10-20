/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The compact lookups read as the associateBy maps they replace. */
internal class TrackLookupTest {
    private val tracks = listOf("c", "a", "b", "a", "d").mapIndexed { index, id -> TrackDescriptor(TrackId(id), title = "t$index") }

    @Test
    fun byIdMatchesAssociateBy() {
        val expected = tracks.associateBy { it.id }
        val actual = tracks.associateById()
        assertEquals(expected, actual)
        assertEquals(actual, expected)
        assertEquals(expected.keys.toList(), actual.keys.toList())
        assertEquals(expected.values.toList(), actual.values.toList())
        assertEquals("t3", actual[TrackId("a")]?.title)
        assertNull(actual[TrackId("z")])
        assertTrue(TrackId("d") in actual)
        assertFalse(TrackId("z") in actual)
        assertEquals(expected.hashCode(), actual.hashCode())
    }

    @Test
    fun byIdValueMatchesAssociateBy() {
        val expected = tracks.associateBy { it.id.value }
        val actual = tracks.associateByIdValue()
        assertEquals(expected, actual)
        assertEquals(expected.keys.toList(), actual.keys.toList())
        assertEquals("t3", actual["a"]?.title)
        assertNull(actual["z"])
        assertEquals(expected + ("e" to tracks[0]), actual + ("e" to tracks[0]))
    }
}
