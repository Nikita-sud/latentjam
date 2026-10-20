/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikitasud.latentjam.smart.MappedRowStore
import io.github.nikitasud.latentjam.smart.RowStoreFactory
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * On ART, as the app runs it: anchors in mapped scratch stores and each block scored through the native kernel. The
 * penalty must be the old one-row-at-a-time scalar loop's, bit for bit.
 */
class HubPenaltyDeviceTest {

    @Test
    fun nativeBlockedHubPenaltyMatchesTheScalarLoop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val n = 3_000
        val dim = SmartSnapshot.AUDIO_DIM
        val random = Random(20261020)
        val matrix = FloatArray(n * dim) { random.nextFloat() - 0.5f }
        val scratch = RowStoreFactory { d, name -> MappedRowStore.forIndex(context, d, name) }
        val actual = SmartSnapshot.computeHubPenalty(
            MatrixCentredRows(matrix, dim),
            scratch = scratch,
            kernel = AndroidBatchDotProducts,
        )
        assertArrayEquals(
            scalarHub(matrix, n, dim).map(Float::toRawBits).toIntArray(),
            actual.map(Float::toRawBits).toIntArray(),
        )
    }

    /** computeHubPenalty as it was before the blocks and the kernel. */
    private fun scalarHub(centered: FloatArray, n: Int, dim: Int): FloatArray {
        val limit = SmartSnapshot.HUB_ANCHOR_SAMPLE
        val perm = IntArray(n) { it }
        val rng = Random(0x1A7E47L)
        for (i in n - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = perm[i]
            perm[i] = perm[j]
            perm[j] = tmp
        }
        val anchors = perm.copyOf(limit)
        val k = SmartSnapshot.HUB_TOPK
        val anchorRows = FloatArray(limit * dim)
        for (c in 0 until limit) centered.copyInto(anchorRows, c * dim, anchors[c] * dim, (anchors[c] + 1) * dim)
        val rowI = FloatArray(dim)
        val top = FloatArray(k)
        val penalty = FloatArray(n)
        for (i in 0 until n) {
            top.fill(Float.NEGATIVE_INFINITY)
            centered.copyInto(rowI, 0, i * dim, (i + 1) * dim)
            for (c in 0 until limit) {
                if (anchors[c] == i) continue
                var dot = 0f
                val baseJ = c * dim
                for (d in 0 until dim) dot += rowI[d] * anchorRows[baseJ + d]
                var minIdx = 0
                for (t in 1 until k) if (top[t] < top[minIdx]) minIdx = t
                if (dot > top[minIdx]) top[minIdx] = dot
            }
            var sum = 0f
            for (t in 0 until k) sum += top[t]
            penalty[i] = sum / k
        }
        var mean = 0f
        for (v in penalty) mean += v
        mean /= n
        for (i in 0 until n) penalty[i] -= mean
        return penalty
    }
}
