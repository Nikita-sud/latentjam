/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BarnesHutTsneTest {
    @Test
    fun `neighbour search matches brute force including ties`() {
        val random = Random(3)
        val n = 90
        val dim = 4
        // Rounded coordinates create exact distance ties, broken by the lower row index.
        val rows = FloatArray(n * dim) { (random.nextInt(5) - 2).toFloat() }
        val k = 12
        val actual = assertNotNull(BarnesHutTsne.nearestNeighbours(rows, n, dim, k) { true })
        for (i in 0 until n) {
            val expected = (0 until n).filter { it != i }
                .sortedWith(compareBy<Int> { squared(rows, i, it, dim) }.thenBy { it })
                .take(k)
            assertEquals(expected, actual.index.copyOfRange(i * k, (i + 1) * k).toList(), "row $i")
            for (s in 0 until k) assertEquals(squared(rows, i, expected[s], dim), actual.distance[i * k + s])
        }
    }

    @Test
    fun `joint probabilities are symmetric and sum to one`() {
        val (rows, n, dim) = clustered(groups = 4, perGroup = 30, dim = 6, seed = 5)
        val k = 20
        val neighbours = assertNotNull(BarnesHutTsne.nearestNeighbours(rows, n, dim, k) { true })
        val p = assertNotNull(BarnesHutTsne.jointProbabilities(neighbours, n, k) { true })
        val pairs = HashMap<Long, Float>()
        var total = 0.0
        for (i in 0 until n) {
            var previous = -1
            for (edge in p.start[i] until p.start[i + 1]) {
                val j = p.column[edge]
                assertTrue(j > previous && j != i, "row $i is sorted without duplicates or self")
                previous = j
                pairs[i.toLong() * n + j] = p.value[edge]
                total += p.value[edge]
            }
        }
        for ((key, value) in pairs) {
            val i = (key / n).toInt()
            val j = (key % n).toInt()
            assertEquals(value, pairs[j.toLong() * n + i], "p[$i,$j] is mirrored")
        }
        assertTrue(abs(total - 1.0) < 1e-4, "total probability was $total")
    }

    @Test
    fun `embedding keeps planted groups apart and is deterministic`() {
        val (rows, n, dim) = clustered(groups = 6, perGroup = 25, dim = 16, seed = 9)
        val first = BarnesHutTsne.embed(rows, n, dim, 0x1A7E27)
        val second = BarnesHutTsne.embed(rows, n, dim, 0x1A7E27)
        assertContentEquals(first, second)
        assertTrue(first.all(Float::isFinite))
        // Ten nearest map neighbours of each point should come from its own planted group.
        var same = 0
        for (i in 0 until n) {
            val nearest = (0 until n).filter { it != i }.sortedBy { squared(first, i, it, 2) }.take(10)
            same += nearest.count { it / 25 == i / 25 }
        }
        val precision = same.toDouble() / (n * 10)
        assertTrue(precision > 0.95, "group precision was $precision")
    }

    @Test
    fun `cancellation is polled in every phase`() {
        val (rows, n, dim) = clustered(groups = 3, perGroup = 30, dim = 5, seed = 1)
        // Neighbour rows, then bandwidth rows, then optimizer iterations.
        for (stopAfter in listOf(1, n + 1, 2 * n + 5)) {
            var polls = 0
            val result = BarnesHutTsne.embed(rows, n, dim, 7, isActive = { ++polls <= stopAfter })
            assertEquals(stopAfter + 1, polls, "stops at the first false poll after $stopAfter")
            assertEquals(n * 2, result.size)
        }
        var polls = 0
        BarnesHutTsne.embed(rows, n, dim, 7, isActive = { polls++; true })
        assertEquals(2 * n + Tsne.ITERATIONS, polls)
        assertNull(BarnesHutTsne.nearestNeighbours(rows, n, dim, 5) { false })
    }

    @Test
    fun `coincident points stay finite`() {
        val n = 80
        val rows = FloatArray(n * 3) { if (it % 3 == 0) 1f else 0f }
        for (i in 40 until n) rows[i * 3 + 1] = 1f
        val result = BarnesHutTsne.embed(rows, n, 3, 11)
        assertTrue(result.all(Float::isFinite))
    }

    @Test
    fun `working memory is linear`() {
        val small = BarnesHutTsne.workingBytes(1000)
        val large = BarnesHutTsne.workingBytes(10_000)
        assertTrue(large < 11 * small)
        assertTrue(large < 40L * 1024 * 1024)
    }

    private fun squared(rows: FloatArray, a: Int, b: Int, dim: Int): Float {
        var sum = 0f
        for (d in 0 until dim) {
            val delta = rows[a * dim + d] - rows[b * dim + d]
            sum += delta * delta
        }
        return sum
    }

    private fun clustered(groups: Int, perGroup: Int, dim: Int, seed: Int): Triple<FloatArray, Int, Int> {
        val random = Random(seed)
        val n = groups * perGroup
        val rows = FloatArray(n * dim)
        for (i in 0 until n) {
            var norm = 0f
            for (d in 0 until dim) {
                val value = (if (d % groups == i / perGroup) 3f else 0f) + random.nextFloat() - 0.5f
                rows[i * dim + d] = value
                norm += value * value
            }
            norm = sqrt(norm)
            for (d in 0 until dim) rows[i * dim + d] /= norm
        }
        return Triple(rows, n, dim)
    }
}
