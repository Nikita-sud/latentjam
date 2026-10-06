/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JourneySequencerTest {
    @Test
    fun `bridges an alternating route without changing its songs or destination`() {
        val snapshot = circle(8)
        val original = listOf(0, 4, 2, 6, 1, 5, 3, 7)
        val ordered = JourneySequencer.order(snapshot, original)

        assertEquals(original.toSet(), ordered.toSet())
        assertEquals(original.size, ordered.size)
        assertEquals(original.first(), ordered.first())
        assertEquals(original.last(), ordered.last())
        assertTrue(distance(snapshot, ordered) < distance(snapshot, original) / 2)
        assertTrue(ordered.zipWithNext().all { (a, b) -> snapshot.centeredCosine(a, b) > 0f })
        assertEquals(listOf(0, 4, 2, 6, 1, 5, 3, 7), original)
    }

    @Test
    fun `long plans keep every window and its boundary tracks intact`() {
        val snapshot = circle(29)
        val original = (0 until 29).map { it * 11 % 29 }
        val ordered = JourneySequencer.order(snapshot, original)

        original.chunked(12).zip(ordered.chunked(12)).forEach { (before, after) ->
            assertEquals(before.toSet(), after.toSet())
            assertEquals(before.size, after.size)
            assertEquals(before.first(), after.first())
            assertEquals(before.last(), after.last())
            assertTrue(distance(snapshot, after) <= distance(snapshot, before) + 1e-5f)
        }
        assertEquals(ordered, JourneySequencer.order(snapshot, original))
    }

    @Test
    fun `short or indistinguishable plans retain the original ranking`() {
        val snapshot = circle(8)
        for (n in 0..3) {
            val rows = (0 until n).toList()
            assertEquals(rows, JourneySequencer.order(snapshot, rows))
        }
        val tied = requireNotNull(SmartSnapshot.build((0 until 8).map { row ->
            track(row, FloatArray(960).also { it[row] = 1f })
        }))
        val rows = listOf(7, 2, 5, 3, 0, 6, 4, 1)
        assertEquals(rows, JourneySequencer.order(tied, rows))
    }

    @Test
    fun `partial semantic coverage still produces a deterministic complete route`() {
        val snapshot = circle(8, partialDescriptors = true)
        val rows = listOf(0, 4, 2, 6, 1, 5, 3, 7)
        val ordered = JourneySequencer.order(snapshot, rows)
        assertEquals(rows.sorted(), ordered.sorted())
        assertEquals(rows.first(), ordered.first())
        assertEquals(rows.last(), ordered.last())
        assertEquals(ordered, JourneySequencer.order(snapshot, rows))
    }

    @Test
    fun `a continued plan may move its first pick toward the track before it`() {
        val snapshot = circle(24)
        val rows = listOf(12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 20, 14, 15, 16, 17, 18, 19)
        val plain = JourneySequencer.order(snapshot, rows)
        assertEquals(12, plain.first())
        val continued = JourneySequencer.order(snapshot, rows, from = 0)

        assertEquals(rows.toSet(), continued.toSet())
        assertTrue(snapshot.centeredCosine(0, continued.first()) > snapshot.centeredCosine(0, 12))
        assertTrue(distance(snapshot, listOf(0) + continued.take(12)) < distance(snapshot, listOf(0) + plain.take(12)))
        // Only the first window changes: its destination and every later window stay as they were.
        assertEquals(plain[11], continued[11])
        assertEquals(plain.drop(12), continued.drop(12))
    }

    private fun distance(snapshot: SmartSnapshot, rows: List<Int>): Float =
        rows.zipWithNext().sumOf { (a, b) ->
            val d = 1f - snapshot.centeredCosine(a, b)
            (d * d).toDouble()
        }.toFloat()

    private fun circle(n: Int, partialDescriptors: Boolean = false): SmartSnapshot =
        requireNotNull(SmartSnapshot.build((0 until n).map { row ->
            val angle = 2 * PI * row / n
            track(row, FloatArray(960).also {
                it[0] = cos(angle).toFloat()
                it[1] = sin(angle).toFloat()
            }).copy(descriptor = if (partialDescriptors && row % 2 == 0) {
                floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat())
            } else null)
        }))

    private fun track(row: Int, vector: FloatArray) = SmartTrack(
        id = TrackId(row.toString()),
        audio = vector,
        meta = TrackMeta("Song $row", "Artist $row", null, null, null),
    )
}
