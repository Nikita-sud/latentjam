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
    private companion object {
        const val STABLE_KEY_OF_TRACK_42 = 7300103868188868364L
    }

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

    @Test
    fun `sampled hierarchy recovers separated regions and keeps strays outside`() {
        val random = Random(4)
        val groups = 6
        val perGroup = 20
        val strays = 8
        val n = groups * perGroup + strays
        // Each stray owns one extra axis, so it is far from every group and every other stray.
        val dim = 24 + strays
        val rows = FloatArray(n * dim)
        for (i in 0 until n) {
            val stray = i - groups * perGroup
            for (d in 0 until dim) {
                val planted = if (stray >= 0) d == 24 + stray else d < 24 && d % groups == i / perGroup
                // A long stray axis keeps the shared centering offset from pulling strays together.
                val length = if (stray >= 0) 12f else 4f
                rows[i * dim + d] = (if (planted) length else 0f) + random.nextFloat() - 0.5f
            }
        }
        val ids = List(n) { TrackId("sampled-$it") }
        fun space() = LibraryVectorSpace(ids, rows.copyOf(), dim, LibraryVectorSource.AUDIO)
        val exact = AverageLinkageClustering.cluster(space()).map { it.members.toSet() }.toSet()
        val sampled = AverageLinkageClustering.cluster(space(), exactLimit = 50)
        assertEquals(exact, sampled.map { it.members.toSet() }.toSet())
        // Every region is pure, and no stray joins one.
        for (cluster in sampled) {
            val origins = cluster.members.map { it.value.removePrefix("sampled-").toInt() / perGroup }.toSet()
            assertEquals(1, origins.size)
            assertTrue(origins.single() < groups)
        }
        // Deterministic, including member order.
        assertEquals(sampled, AverageLinkageClustering.cluster(space(), exactLimit = 50))
    }

    @Test
    fun `sampled hierarchy cancels after work has started`() {
        val n = 80
        val ids = List(n) { TrackId("cancel-sampled-$it") }
        val rows = FloatArray(n * 6) { if (it % 6 == (it / 6) % 6) 1f else 0.05f * (it % 5) }
        var polls = 0
        assertFailsWith<CancellationException> {
            AverageLinkageClustering.cluster(
                LibraryVectorSpace(ids, rows, 6, LibraryVectorSource.AUDIO),
                // Centering and the usable-row filter poll 3n + 1 times; stop inside the sampled path.
                isActive = { ++polls < 4 * n + 30 },
                exactLimit = 20,
            )
        }
        assertEquals(4 * n + 30, polls)
    }

    @Test
    fun `library population limit and stable sample keys are pinned`() {
        assertEquals(3_000, AverageLinkageClustering.MAX_TRACKS)
        assertEquals(10_000, AverageLinkageClustering.MAX_LIBRARY_TRACKS)
        val n = AverageLinkageClustering.MAX_LIBRARY_TRACKS + 1
        assertFailsWith<IllegalArgumentException> {
            AverageLinkageClustering.cluster(
                LibraryVectorSpace(List(n) { TrackId("over-$it") }, FloatArray(n), 1, LibraryVectorSource.AUDIO),
            )
        }
        // The sample must not depend on the platform's String.hashCode or iteration order.
        assertEquals(STABLE_KEY_OF_TRACK_42, AverageLinkageClustering.stableKey("track-42"))
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
