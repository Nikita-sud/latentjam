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

internal class QueueIdentitySnapshotTest {

    @Test
    fun uniqueQueuesUseStableTrackKeys() {
        val first = track("first")
        val second = track("second")
        val snapshot = queueIdentitySnapshot(listOf(first, second))

        assertFalse(snapshot.hasDuplicateTrackIds)
        assertEquals("first", queueLazyItemKey(snapshot, index = 0, track = first))
        assertEquals("second", queueLazyItemKey(snapshot, index = 1, track = second))
        // Moving a row keeps its key attached to the track rather than its old position.
        assertEquals("first", queueLazyItemKey(snapshot, index = 1, track = first))
    }

    @Test
    fun duplicateQueuesUseUniquePositionalKeys() {
        val first = track("same")
        val second = track("same")
        val snapshot = queueIdentitySnapshot(listOf(first, second))

        assertTrue(snapshot.hasDuplicateTrackIds)
        assertEquals(0, queueLazyItemKey(snapshot, index = 0, track = first))
        assertEquals(1, queueLazyItemKey(snapshot, index = 1, track = second))
    }

    @Test
    fun duplicateDetectionStopsAtTheFirstRepeatedId() {
        var reads = 0
        val queue = object : AbstractList<TrackDescriptor>() {
            override val size: Int = 10_000

            override fun get(index: Int): TrackDescriptor {
                reads += 1
                return track(if (index < 2) "same" else "track-$index")
            }
        }

        assertTrue(queueIdentitySnapshot(queue).hasDuplicateTrackIds)
        assertEquals(2, reads)
    }

    private fun track(id: String): TrackDescriptor = TrackDescriptor(id = TrackId(id))
}
