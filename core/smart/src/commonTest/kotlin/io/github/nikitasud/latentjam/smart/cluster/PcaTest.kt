/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcaTest {

    // Data that lives on a plane inside a 5-d space must survive reduction to 2 components with
    // its pairwise distances essentially intact: that is the only property t-SNE needs from PCA.
    @Test
    fun `reduce preserves distances for data on a low-rank plane`() {
        val n = 40
        val dim = 5
        val rows = FloatArray(n * dim)
        for (i in 0 until n) {
            val a = (i % 8).toFloat() - 3.5f
            val b = (i / 8).toFloat() - 2f
            // Only two directions carry variance; the other three are fixed combinations.
            rows[i * dim + 0] = a
            rows[i * dim + 1] = b
            rows[i * dim + 2] = a + b
            rows[i * dim + 3] = a - b
            rows[i * dim + 4] = 0f
        }
        center(rows, n, dim)

        val reduced = Pca.reduce(rows, n, dim, components = 2, seed = 7)
        assertEquals(n * 2, reduced.size)

        var worst = 0f
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val full = distance(rows, i, j, dim)
                val low = distance(reduced, i, j, 2)
                worst = maxOf(worst, abs(full - low))
            }
        }
        assertTrue(worst < 1e-2f, "worst pairwise distance error was $worst")
    }

    // The whole page rests on the map not moving between runs.
    @Test
    fun `reduce is deterministic for a fixed seed`() {
        val n = 30
        val dim = 12
        val rows = FloatArray(n * dim) { ((it * 37) % 23).toFloat() - 11f }
        center(rows, n, dim)
        val a = Pca.reduce(rows, n, dim, components = 4, seed = 3)
        val b = Pca.reduce(rows, n, dim, components = 4, seed = 3)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }

    // The abort hook exists so a cancelled Map visit stops paying for iterations nobody will see
    // (see LibraryLayout.MAX_TRACKS's doc). `calls` counts every isActive() invocation; returning
    // false on the 2nd call must stop the loop before a 3rd check ever happens, proving the 4
    // configured ITERATIONS do not all run regardless.
    @Test
    fun `reduce stops iterating once isActive turns false`() {
        val n = 20
        val dim = 8
        val rows = FloatArray(n * dim) { ((it * 5) % 11).toFloat() - 5f }
        center(rows, n, dim)
        var calls = 0
        Pca.reduce(rows, n, dim, components = 3, seed = 1, isActive = { calls++; calls <= 1 })
        assertEquals(2, calls, "expected the loop to stop right after isActive first returned false")
    }

    private fun center(rows: FloatArray, n: Int, dim: Int) {
        for (d in 0 until dim) {
            var mean = 0f
            for (i in 0 until n) mean += rows[i * dim + d]
            mean /= n
            for (i in 0 until n) rows[i * dim + d] -= mean
        }
    }

    private fun distance(rows: FloatArray, i: Int, j: Int, dim: Int): Float {
        var sum = 0f
        for (d in 0 until dim) {
            val delta = rows[i * dim + d] - rows[j * dim + d]
            sum += delta * delta
        }
        return kotlin.math.sqrt(sum)
    }
}
