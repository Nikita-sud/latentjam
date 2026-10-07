/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shortcuts of a full-library pool give exactly what sorting and pairwise cosines gave. */
internal class PoolSelectionTest {
    @Test
    fun `selecting the best rows gives the stable sort's first rows`() {
        val random = Random(7)
        // Few distinct values so ties are everywhere, including the values compareTo orders specially.
        val values = floatArrayOf(0f, -0f, 1f, -1f, 0.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        repeat(300) { case ->
            val n = random.nextInt(0, 400)
            val scores = FloatArray(n) {
                if (case % 2 == 0) values[random.nextInt(values.size)] else random.nextFloat() * 2f - 1f
            }
            val rows = (0 until n).filter { random.nextInt(4) != 0 }.toIntArray()
            val count = if (rows.isEmpty()) 0 else random.nextInt(0, rows.size + 1)
            for (keep in listOf(0, 1, 3, 100, count, count + 5)) {
                val expected = rows.take(count).sortedByDescending { scores[it] }.take(keep)
                assertEquals(expected, topRows(scores, rows, count, keep).toList(), "case $case keep $keep")
            }
        }
    }

    @Test
    fun `batched dot products are the same floats as one row at a time`() {
        val random = Random(11)
        for (dim in listOf(1, 3, 384, 960)) {
            val rows = 37
            val matrix = FloatArray(rows * dim) { random.nextFloat() * 2f - 1f }
            val query = FloatArray(dim + 5) { random.nextFloat() * 2f - 1f }
            for (count in listOf(0, 1, 4, 7, 33)) {
                val picked = (0 until rows).shuffled(random).take(count).toIntArray()
                val out = FloatArray(rows)
                batchDots(matrix, dim, query, 5, picked, count, out)
                for (row in picked) {
                    var dot = 0f
                    for (d in 0 until dim) dot += matrix[row * dim + d] * query[5 + d]
                    assertEquals(dot.toRawBits(), out[row].toRawBits(), "dim $dim count $count row $row")
                }
            }
        }
    }

    @Test
    fun `cosines with the whole library are the pairwise ones`() {
        val random = Random(5)
        val snapshot = requireNotNull(
            SmartSnapshot.build(
                List(41) { row ->
                    SmartTrack(
                        id = TrackId(row.toString()),
                        audio = FloatArray(SmartSnapshot.AUDIO_DIM) { random.nextFloat() - 0.5f },
                        descriptor = if (row % 3 == 0) {
                            null
                        } else {
                            FloatArray(DESCRIPTOR_DIM) { random.nextFloat() - 0.5f }
                        },
                        meta = TrackMeta("Title $row", "Artist $row", null, null, null),
                    )
                },
            ),
        )
        for (reference in listOf(0, 1, 2, 40)) {
            val audio = snapshot.centeredCosines(reference)
            assertContentEquals(
                List(snapshot.size) { snapshot.centeredCosine(reference, it).toRawBits() },
                audio.map { it.toRawBits() },
            )
            val descriptor = snapshot.descriptorCosines(reference)
            if (reference % 3 == 0) {
                assertNull(descriptor)
                continue
            }
            for (row in 0 until snapshot.size) {
                val expected = snapshot.descriptorCosine(reference, row)
                if (expected == null) {
                    assertEquals(false, snapshot.hasDescriptor?.get(row))
                } else {
                    assertEquals(expected.toRawBits(), requireNotNull(descriptor)[row].toRawBits(), "row $row")
                }
            }
        }
    }

    private companion object {
        const val DESCRIPTOR_DIM = 384
    }
}
