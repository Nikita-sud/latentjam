/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.*

class AverageLinkageClusteringTest {
    @Test
    fun `nearest neighbor chain agrees with direct global average linkage`() {
        // The reference recomputes every cross-member distance, independently of the chain
        // and its weighted distance update. Unequal group sizes distinguish UPGMA from WPGMA.
        for (seed in 0..19) {
            val random = Random(seed)
            val n = 19
            val dim = 7
            val rows = FloatArray(n * dim) { random.nextFloat() * 2 - 1 }
            for (cut in listOf(0.0, 0.2, 0.5, 0.75, 1.0, 1.4, 2.0)) {
                val actual = AverageLinkageClustering.assign(rows, n, dim, cut)
                val expected = reference(rows, n, dim, cut)
                for (i in 0 until n) for (j in 0 until n) {
                    assertEquals(expected[i] == expected[j], actual[i] == actual[j], "seed=$seed cut=$cut pair=$i,$j")
                }
            }
        }
    }

    @Test
    fun `a distant early merge does not hide a later tight island`() {
        val rows = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 0f,0.01f,1f)
        val labels = AverageLinkageClustering.assign(rows, 4, 3, 0.1)
        assertEquals(labels[2], labels[3])
        assertNotEquals(labels[0], labels[1])
        assertNotEquals(labels[0], labels[2])
    }

    @Test
    fun `exact ties are deterministic and do not cycle`() {
        val rows = FloatArray(16) { if (it / 4 == it % 4) 1f else 0f }
        assertContentEquals(intArrayOf(0,1,2,3), AverageLinkageClustering.assign(rows, 4, 4, 0.9))
        assertContentEquals(intArrayOf(0,0,0,0), AverageLinkageClustering.assign(rows, 4, 4, 1.0))
        assertContentEquals(IntArray(20), AverageLinkageClustering.assign(FloatArray(40) { 1f }, 20, 2))
    }

    @Test
    fun `small islands remain outside regions and collected regions have no kmeans margins`() {
        val ids = (0..5).map { TrackId(it.toString()) }
        val rows = floatArrayOf(1f,0f, 1f,0f, 1f,0f, 1f,0f, 0f,1f, 0f,1f)
        val space = LibraryVectorSpace(ids, rows, 2, LibraryVectorSource.AUDIO)
        val clusters = AverageLinkageClustering.cluster(space)
        assertEquals(1, clusters.size)
        assertEquals(ids.take(4).toSet(), clusters.single().members.toSet())
        assertTrue(clusters.single().memberships.isEmpty())
        assertFailsWith<IllegalStateException> { space.peekRows() }
    }

    @Test
    fun `identical directions do not invent regions from a zero centered matrix`() {
        val ids = (0..7).map { TrackId(it.toString()) }
        assertTrue(AverageLinkageClustering.cluster(
            LibraryVectorSpace(ids, FloatArray(16) { 1f }, 2, LibraryVectorSource.AUDIO),
        ).isEmpty())
    }

    @Test
    fun `discovery can cancel and rejects an unbounded distance allocation`() {
        assertFailsWith<CancellationException> {
            AverageLinkageClustering.assign(FloatArray(20) { 1f }, 10, 2, isActive = { false })
        }
        val n = AverageLinkageClustering.MAX_TRACKS + 1
        assertFailsWith<IllegalArgumentException> {
            AverageLinkageClustering.assign(FloatArray(n) { 1f }, n, 1)
        }
    }


    @Test
    fun `tiny centered directions are ignored instead of aborting discovery`() {
        // These are finite unit vectors accepted by the production fusion builder. After
        // centering only their sub-epsilon second component remains; it has no stable direction.
        val ids = List(8) { TrackId("tiny-$it") }
        val vectors = ids.mapIndexed { index, id ->
            id to floatArrayOf(1f, if (index < 4) 1e-14f else -1e-14f)
        }.toMap()
        val space = assertNotNull(LibraryVectorFusion.build(ids, vectors, emptyMap(), 2, 2))
        assertTrue(AverageLinkageClustering.cluster(space).isEmpty())
    }

    private fun reference(rows: FloatArray, n: Int, dim: Int, cut: Double): IntArray {
        val norms = DoubleArray(n) { i -> sqrt((0 until dim).sumOf { d -> rows[i * dim + d].toDouble().let { it * it } }) }
        val distances = Array(n) { i -> DoubleArray(n) { j ->
            (1.0 - (0 until dim).sumOf { d -> rows[i * dim + d].toDouble() * rows[j * dim + d] } / (norms[i] * norms[j])).coerceIn(0.0, 2.0)
        } }
        val groups = (0 until n).map { mutableListOf(it) }.toMutableList()
        while (groups.size > 1) {
            var best = Double.POSITIVE_INFINITY
            var a = -1
            var b = -1
            for (i in groups.indices) for (j in 0 until i) {
                val average = groups[i].sumOf { x -> groups[j].sumOf { y -> distances[x][y] } } / (groups[i].size * groups[j].size)
                if (average < best) { best = average; a = i; b = j }
            }
            if (best > cut) break
            groups[b].addAll(groups.removeAt(a))
        }
        return IntArray(n).also { result -> groups.forEachIndexed { label, group -> group.forEach { result[it] = label } } }
    }
}
