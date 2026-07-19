/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grouping rules, pinned.
 *
 * Clustering fails quietly: a broken run still returns plausible-looking groups, and the only way
 * to notice from the UI is that the section stops making sense months later. These are the
 * properties that would be silently lost.
 */
class TrackClusteringTest {

    private val dim = 16

    /**
     * A unit vector near [angle] on a circle embedded in [dim] dimensions, jittered by [spread].
     *
     * Unit-length like a real encoder's output, which matters: the production path normalises
     * before it centers, so a fixture of ragged-length vectors would be testing a different
     * pipeline than the one that ships.
     */
    private fun point(angle: Double, spread: Double, random: Random, axis: Int = 0): FloatArray {
        val raw = DoubleArray(dim) { d ->
            val base = when (d) {
                axis * 2 -> cos(angle)
                axis * 2 + 1 -> sin(angle)
                else -> 0.0
            }
            base + random.nextDouble(-spread, spread)
        }
        val length = sqrt(raw.sumOf { it * it })
        return FloatArray(dim) { (raw[it] / length).toFloat() }
    }

    /** Two well-separated clouds: [count] points each, on opposite sides of the circle. */
    private fun twoClouds(count: Int, spread: Double = 0.05): Pair<List<TrackId>, Map<TrackId, FloatArray>> {
        val random = Random(11)
        val ids = mutableListOf<TrackId>()
        val vectors = mutableMapOf<TrackId, FloatArray>()
        repeat(count) { i ->
            val left = TrackId("L$i")
            val right = TrackId("R$i")
            ids += left
            ids += right
            vectors[left] = point(0.4, spread, random)
            vectors[right] = point(3.6, spread, random)
        }
        return ids to vectors
    }

    @Test
    fun `the same library clusters the same way every time`() {
        val (ids, vectors) = twoClouds(count = 20)
        val first = TrackClustering.cluster(ids, vectors, dim)
        repeat(5) {
            val again = TrackClustering.cluster(ids, vectors, dim)
            assertEquals(
                first.map { it.members },
                again.map { it.members },
                "clustering must not depend on anything but its inputs",
            )
        }
    }

    @Test
    fun `two obvious clouds come back as two clusters`() {
        val (ids, vectors) = twoClouds(count = 20)
        val clusters = TrackClustering.cluster(ids, vectors, dim, k = 2)
        assertEquals(2, clusters.size)
        // Each cluster must be pure: the clouds are far enough apart that any mixing is a bug.
        for (cluster in clusters) {
            val prefixes = cluster.members.map { it.value.first() }.toSet()
            assertEquals(1, prefixes.size, "a cluster mixed both clouds: ${cluster.members}")
        }
        assertEquals(40, clusters.sumOf { it.size })
    }

    @Test
    fun `a handful of tracks that sit together is noise rather than a cluster`() {
        val random = Random(3)
        val ids = mutableListOf<TrackId>()
        val vectors = mutableMapOf<TrackId, FloatArray>()
        // Two regions worth showing, and a third too thin to mean anything.
        listOf(Triple("big", 0.0, 20), Triple("mid", 2.1, 12), Triple("stray", 4.2, 3))
            .forEach { (prefix, angle, count) ->
                repeat(count) { i ->
                    val id = TrackId("$prefix$i")
                    ids += id
                    vectors[id] = point(angle, 0.05, random)
                }
            }

        val kept = TrackClustering.cluster(ids, vectors, dim, k = 4, minSize = 4)
        val everything = TrackClustering.cluster(ids, vectors, dim, k = 4, minSize = 1)
        assertTrue(
            everything.any { it.size < 4 },
            "the fixture produced nothing small enough to exercise the rule",
        )
        // Three tracks that happen to sit together say nothing about a library, so they are dropped
        // rather than dressed up as a region of it — and nothing else about the grouping changes.
        assertEquals(everything.filter { it.size >= 4 }, kept)
        assertTrue(
            kept.none { cluster -> cluster.members.any { it.value.startsWith("stray") } },
            "a three-track cluster was offered as a region of the library",
        )
    }

    @Test
    fun `the most central member comes first`() {
        val random = Random(7)
        val ids = mutableListOf<TrackId>()
        val vectors = mutableMapOf<TrackId, FloatArray>()
        repeat(24) { i ->
            val id = TrackId("t$i")
            ids += id
            // Deliberately widening: later tracks sit further out, so the ordering has something
            // to get wrong.
            vectors[id] = point(1.0, 0.02 + i * 0.01, random)
        }
        val cluster = TrackClustering.cluster(ids, vectors, dim, k = 1, minSize = 4).single()

        // Distance to the cluster's own centre must increase along the member list.
        val centre = FloatArray(dim)
        val rows = cluster.members.map { vectors.getValue(it).copyOf() }
        val flat = FloatArray(rows.size * dim)
        rows.forEachIndexed { i, row -> row.copyInto(flat, i * dim) }
        TrackClustering.center(flat, rows.size, dim)
        for (i in rows.indices) for (d in 0 until dim) centre[d] += flat[i * dim + d]

        val similarities = rows.indices.map { i ->
            var dot = 0f
            for (d in 0 until dim) dot += flat[i * dim + d] * centre[d]
            dot
        }
        assertEquals(
            similarities.sortedDescending(),
            similarities,
            "members are not ordered center-outward, so index 0 is not the medoid",
        )
    }

    @Test
    fun `a track with no vector is left out rather than parked at the origin`() {
        val (ids, vectors) = twoClouds(count = 20)
        val unindexed = (1..10).map { TrackId("missing$it") }
        val zeroed = (1..10).map { TrackId("zero$it") }
        val wrongSize = TrackId("short")
        val withGaps = ids + unindexed + zeroed + wrongSize
        val withZeros = vectors +
            zeroed.associateWith { FloatArray(dim) } +
            (wrongSize to FloatArray(dim - 1) { 1f })

        val clusters = TrackClustering.cluster(withGaps, withZeros, dim, k = 2)
        val clustered = clusters.flatMap { it.members }.toSet()

        // Twenty rows that are equally similar to everything would otherwise form a cluster of
        // their own — and it would look like a real one.
        assertTrue(unindexed.none { it in clustered }, "an unindexed track was clustered")
        assertTrue(zeroed.none { it in clustered }, "a zero vector was clustered")
        assertTrue(wrongSize !in clustered, "a wrong-dimension vector was clustered")
        assertEquals(40, clustered.size)
    }

    @Test
    fun `a library too small to have regions has none`() {
        val random = Random(5)
        val ids = (1..3).map { TrackId("t$it") }
        val vectors = ids.associateWith { point(0.5, 0.05, random) }
        assertTrue(TrackClustering.cluster(ids, vectors, dim).isEmpty())
        assertTrue(TrackClustering.cluster(emptyList(), vectors, dim).isEmpty())
    }

    @Test
    fun `clusters are disjoint and complete`() {
        val (ids, vectors) = twoClouds(count = 30, spread = 0.35)
        // Nothing dropped, so every input must be accounted for exactly once.
        val clusters = TrackClustering.cluster(ids, vectors, dim, k = 4, minSize = 1)
        val all = clusters.flatMap { it.members }
        assertEquals(all.size, all.toSet().size, "a track appeared in two clusters")
        assertEquals(60, all.size, "a track was assigned nowhere")
        // Largest first, so the strongest region leads a row whose later slots are barely looked at.
        assertEquals(clusters.map { it.size }.sortedDescending(), clusters.map { it.size })
    }
}
