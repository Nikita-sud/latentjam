/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class BatchDotFallbackTest {
    @Test
    fun `an unavailable platform kernel preserves portable scores and skipped rows`() {
        var attempts = 0
        val unavailable = BatchDotProducts { _, _, _, _, _, _, _ -> attempts++; false }
        val track = SmartTrack(
            TrackId("seed"), FloatArray(SmartSnapshot.AUDIO_DIM) { if (it == 0) 1f else 0f },
            meta = TrackMeta("Seed", "Artist", null, null, null),
        )
        val snapshot = requireNotNull(SmartSnapshot.build(listOf(track), unavailable))
        val n = 4_101
        val dim = 17
        val matrix = FloatArray(n * dim) { (it % 37 - 18) / 37f }
        val query = FloatArray(dim + 3) { (it % 11 - 5) / 11f }
        val rows = IntArray(n) { n - it - 1 }
        val expected = FloatArray(n) { -1234.5f }
        val actual = expected.copyOf()
        batchDots(matrix, dim, query, 3, rows, 4_096, expected)
        snapshot.batchDots(matrix, dim, query, 3, rows, 4_096, actual)
        assertEquals(1, attempts)
        assertContentEquals(expected.map(Float::toRawBits), actual.map(Float::toRawBits))
        // Small batches bypass the platform call but keep the same output contract.
        batchDots(matrix, dim, query, 3, rows, 9, expected)
        snapshot.batchDots(matrix, dim, query, 3, rows, 9, actual)
        assertEquals(1, attempts)
        assertContentEquals(expected.map(Float::toRawBits), actual.map(Float::toRawBits))
    }
}
