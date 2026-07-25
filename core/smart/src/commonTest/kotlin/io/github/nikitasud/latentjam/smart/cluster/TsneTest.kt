/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TsneTest {

    // Three well-separated blobs in 8-d must come out as three separated blobs in 2-d. This is the
    // only behavioural claim the map makes: things that belong together land together.
    @Test
    fun `embed separates three planted clusters`() {
        val perCluster = 30
        val dim = 8
        val n = perCluster * 3
        val rows = FloatArray(n * dim)
        var state = 99L
        fun noise(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toFloat() / (1L shl 31).toFloat()) - 0.5f
        }
        for (c in 0 until 3) {
            for (i in 0 until perCluster) {
                val row = c * perCluster + i
                for (d in 0 until dim) {
                    rows[row * dim + d] = (if (d == c) 6f else 0f) + noise() * 0.4f
                }
            }
        }

        val out = Tsne.embed(rows, n, dim, seed = 5)
        assertEquals(n * 2, out.size)

        val within = meanDistance(out) { a, b -> a / perCluster == b / perCluster }
        val between = meanDistance(out) { a, b -> a / perCluster != b / perCluster }
        assertTrue(
            between > within * 2f,
            "clusters did not separate: within=$within between=$between",
        )
    }

    @Test
    fun `embed is deterministic for a fixed seed`() {
        val n = 40
        val dim = 6
        val rows = FloatArray(n * dim) { ((it * 17) % 13).toFloat() - 6f }
        val a = Tsne.embed(rows, n, dim, seed = 2)
        val b = Tsne.embed(rows, n, dim, seed = 2)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
    }

    // A warm start must be honoured, not ignored: Task 4's anti-churn machinery depends on it.
    @Test
    fun `embed started from a layout stays nearer it than a cold run`() {
        val n = 45
        val dim = 6
        val rows = FloatArray(n * dim) { ((it * 29) % 19).toFloat() - 9f }
        val reference = Tsne.embed(rows, n, dim, seed = 1)
        val warm = Tsne.embed(rows, n, dim, seed = 8, initial = reference)
        val cold = Tsne.embed(rows, n, dim, seed = 8)
        assertTrue(
            drift(warm, reference) < drift(cold, reference),
            "warm start drifted further than a cold run",
        )
    }

    private fun meanDistance(out: FloatArray, pair: (Int, Int) -> Boolean): Float {
        var sum = 0f
        var count = 0
        val n = out.size / 2
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (!pair(i, j)) continue
                val dx = out[i * 2] - out[j * 2]
                val dy = out[i * 2 + 1] - out[j * 2 + 1]
                sum += sqrt(dx * dx + dy * dy)
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun drift(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val delta = a[i] - b[i]
            sum += delta * delta
        }
        return sqrt(sum)
    }
}
