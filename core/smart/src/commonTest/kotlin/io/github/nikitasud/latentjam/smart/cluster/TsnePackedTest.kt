/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class TsnePackedTest {
    @Test
    fun `aborted affinities retain the packed shape in both preparation phases`() {
        val n = 73
        val dim = 7
        val rows = FloatArray(n * dim) { ((it * 19) % 23).toFloat() / 23f }
        for (abortAfter in listOf(0, n + 1)) {
            var calls = 0
            val packed = Tsne.affinities(rows, n, dim) { calls++ < abortAfter }
            assertContentEquals(FloatArray(n * (n - 1) / 2), packed)
            assertEquals(abortAfter + 1, calls)
        }
    }

    @Test
    fun `packed affinities equal a separate dense conditional calculation`() {
        // Includes the small-library perplexity clamp and repeated/equidistant vectors. A
        // distance row overwritten too early changes its next bandwidth step or another row.
        for (n in listOf(3, 12, 21, 73)) {
            val dim = 7
            val rows = FloatArray(n * dim) { ((it * 19) % 23).toFloat() / 23f }
            val conditional = FloatArray(n * n)
            for (i in 0 until n) {
                val distances = FloatArray(n) { j ->
                    var distance = 0f
                    for (d in 0 until dim) {
                        val delta = rows[i * dim + d] - rows[j * dim + d]
                        distance += delta * delta
                    }
                    distance
                }
                var beta = 1f
                var low = 0f
                var high = Float.MAX_VALUE
                val target = ln(minOf(20f, maxOf(2f, (n - 1) / 3f)))
                for (step in 0 until 60) {
                    var sum = 0f
                    var weightedDistance = 0f
                    for (j in 0 until n) {
                        if (j == i) continue
                        val probability = exp(-distances[j] * beta)
                        conditional[i * n + j] = probability
                        sum += probability
                        weightedDistance += distances[j] * probability
                    }
                    sum = sum.coerceAtLeast(1e-12f)
                    val error = ln(sum) + beta * weightedDistance / sum - target
                    for (j in 0 until n) conditional[i * n + j] /= sum
                    if (error > 0f) {
                        low = beta
                        beta = if (high == Float.MAX_VALUE) beta * 2f else (beta + high) / 2f
                    } else {
                        high = beta
                        beta = (beta + low) / 2f
                    }
                    if (error > -1e-5f && error < 1e-5f) break
                }
            }
            val packed = Tsne.affinities(rows, n, dim) { true }
            assertEquals(n * (n - 1) / 2, packed.size)
            var pair = 0
            for (i in 0 until n) for (j in i + 1 until n) {
                val expected = ((conditional[i * n + j] + conditional[j * n + i]) / (2f * n))
                    .coerceAtLeast(1e-12f)
                assertEquals(expected, packed[pair++], "n=$n pair=($i,$j)")
            }
        }
    }

    @Test
    fun `one force per pair preserves dense gradient including accumulation order`() {
        val n = 73
        val y = FloatArray(n * 2) { ((it * 37) % 31).toFloat() - 15f }
        val denseP = FloatArray(n * n)
        val denseQ = FloatArray(n * n)
        val packedP = FloatArray(n * (n - 1) / 2)
        val packedQ = FloatArray(packedP.size)
        var pair = 0
        var sum = 0f
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = y[i * 2] - y[j * 2]
            val dy = y[i * 2 + 1] - y[j * 2 + 1]
            val q = 1f / (1f + dx * dx + dy * dy)
            val p = ((pair * 17) % 41 + 1).toFloat() / (n * n)
            denseP[i * n + j] = p
            denseP[j * n + i] = p
            denseQ[i * n + j] = q
            denseQ[j * n + i] = q
            packedP[pair] = p
            packedQ[pair++] = q
            sum += 2f * q
        }
        for (scale in listOf(1f, 12f)) {
            val expected = FloatArray(n * 2)
            for (i in 0 until n) for (j in 0 until n) {
                if (i == j) continue
                val q = denseQ[i * n + j]
                val force = (scale * denseP[i * n + j] - q / sum) * q
                expected[i * 2] += 4f * force * (y[i * 2] - y[j * 2])
                expected[i * 2 + 1] += 4f * force * (y[i * 2 + 1] - y[j * 2 + 1])
            }
            val actual = FloatArray(n * 2) { 99f }
            Tsne.gradient(y, n, packedP, packedQ, sum, scale, actual)
            assertContentEquals(expected, actual, "exaggeration=$scale")
        }
    }
}
