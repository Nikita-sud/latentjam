/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LatentColorAxesTest {

    /**
     * Six "genres" around a large direction every track shares, the shape real embeddings have:
     * the shared part dwarfs what separates them.
     */
    private fun library(perCluster: Int = 40, dim: Int = 64, seed: Int = 3): List<Pair<Int, FloatArray>> {
        val random = Random(seed)
        val shared = FloatArray(dim) { 1f + random.nextFloat() }
        val centres = List(6) { FloatArray(dim) { random.nextFloat() * 2f - 1f } }
        return centres.flatMapIndexed { cluster, centre ->
            List(perCluster) {
                cluster to FloatArray(dim) { d -> 8f * shared[d] + centre[d] + 0.15f * (random.nextFloat() - 0.5f) }
            }
        }
    }

    private fun circular(a: Float, b: Float): Float = abs(a - b).let { min(it, 1f - it) }

    @Test
    fun `fully even the library covers the whole wheel`() {
        val tracks = library()
        val axes = assertNotNull(LatentColorAxes.fit(tracks.map { it.second }))
        val angles = tracks.map { assertNotNull(axes.place(it.second, evenness = 1f)).angle }.sorted()
        // Evenly spread: every tenth of the wheel holds some tracks.
        for (decile in 0 until 10) {
            assertTrue(angles.any { it >= decile / 10f && it < (decile + 1) / 10f }, "decile $decile is empty")
        }
    }

    @Test
    fun `tracks of one kind share a colour and different kinds do not`() {
        val tracks = library()
        val axes = assertNotNull(LatentColorAxes.fit(tracks.map { it.second }))
        val placed = tracks.map { (cluster, row) -> cluster to assertNotNull(axes.place(row)).angle }
        var within = 0f
        var withinCount = 0
        var between = 0f
        var betweenCount = 0
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val distance = circular(placed[i].second, placed[j].second)
            if (placed[i].first == placed[j].first) {
                within += distance; withinCount++
            } else {
                between += distance; betweenCount++
            }
        }
        assertTrue(within / withinCount * 4f < between / betweenCount, "within ${within / withinCount}, between ${between / betweenCount}")
    }

    @Test
    fun `by default every kind of track gets a hue of its own`() {
        val tracks = library()
        val axes = assertNotNull(LatentColorAxes.fit(tracks.map { it.second }))
        val means = tracks.groupBy { it.first }.values.map { group ->
            group.map { assertNotNull(axes.place(it.second)).angle }.sorted()[group.size / 2]
        }
        for (i in means.indices) for (j in i + 1 until means.size) {
            assertTrue(circular(means[i], means[j]) > 0.04f, "kinds $i and $j share a hue")
        }
    }

    @Test
    fun `a slightly larger library keeps every colour where it was`() {
        val tracks = library(perCluster = 50)
        val small = assertNotNull(LatentColorAxes.fit(tracks.filterIndexed { i, _ -> i % 5 != 0 }.map { it.second }))
        val full = assertNotNull(LatentColorAxes.fit(tracks.map { it.second }))
        val drift = tracks.map { (_, row) ->
            circular(assertNotNull(small.place(row)).angle, assertNotNull(full.place(row)).angle)
        }.average()
        assertTrue(drift < 0.06, "average hue drift $drift of a turn")
    }

    @Test
    fun `too few tracks or a foreign vector give no colour`() {
        assertNull(LatentColorAxes.fit(library(perCluster = 3).map { it.second }))
        val axes = assertNotNull(LatentColorAxes.fit(library().map { it.second }))
        assertNull(axes.place(FloatArray(3)))
        assertNull(axes.place(FloatArray(64) { Float.NaN }))
    }
}
