/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class SmartChainContinuationTest {
    @Test
    fun `all eligible close tracks are consumed before leaving the initial neighborhood`() {
        val snapshot = library(near = 9, far = 12)
        val near = (1 until snapshot.size).filter {
            Reanchor.isCloseContinuation(snapshot.centeredCosine(0, it), snapshot.descriptorCosine(0, it))
        }.toSet()
        assertEquals(9, near.size)
        val queue = build(snapshot, near.size + 3)
        assertEquals(near, queue.rows.take(near.size).toSet())
        assertEquals(near.size + 3, queue.rows.size)
        assertEquals(queue.rows.size, queue.rows.toSet().size)
        assertTrue(queue.rows.all { it != 0 && it in queue.pool })
    }

    @Test
    fun `pool refills expose suitable tracks beyond the first hundred candidates`() {
        val snapshot = library(near = 125, far = 25)
        val queue = build(snapshot, 120)
        assertEquals(120, queue.rows.size)
        assertEquals(120, queue.rows.toSet().size)
        assertTrue(queue.rows.all { it in 1..125 })
        assertTrue(queue.pool.size > PredictorRuntime.POOL_SIZE)
    }

    @Test
    fun `marked groups retain their existing quota behavior`() {
        val snapshot = library(near = 9, far = 12)
        val groups = listOf(setOf(TrackId("0"), TrackId("15"), TrackId("16")))
        val current = SmartChain(snapshot, null, companionGroups = groups).build(TrackId("0"), 12, FloatArray(5))
        val experimental = SmartChain(
            snapshot, null, companionGroups = groups, tuning = ChainTuning(continueAfterExhaustion = true),
        ).build(TrackId("0"), 12, FloatArray(5))
        assertEquals(current, experimental)
    }

    @Test
    fun `artist semantics without acoustic support do not hold the queue in a neighborhood`() {
        assertFalse(Reanchor.isCloseContinuation(0.1f, 1f))
        assertFalse(Reanchor.isCloseContinuation(0.39f, null))
        assertTrue(Reanchor.isCloseContinuation(0.5f, null))
        assertTrue(Reanchor.isCloseContinuation(0.3f, 0.7f))
        // The descriptor's share decides borderline cases; the sound gate holds at any share.
        assertFalse(Reanchor.isCloseContinuation(0.3f, 0.7f, descriptorWeight = 0.2f))
        assertTrue(Reanchor.isCloseContinuation(0.25f, 0.6f, descriptorWeight = 0.8f))
        assertFalse(Reanchor.isCloseContinuation(0.1f, 1f, descriptorWeight = 0.9f))
    }

    @Test
    fun `a resumed plan keeps to the walk neighbourhood the queue tail would leave`() {
        val snapshot = satellites()
        for (row in NEIGHBOURS) assertTrue(close(snapshot, 0, row), "row $row must neighbour the seed")
        for (row in SATELLITE_ROWS) assertFalse(close(snapshot, 0, row), "row $row must be far from the seed")

        val first = continuing(snapshot).build(TrackId("0"), 3, FloatArray(5))
        assertTrue(first.rows.all { it in NEIGHBOURS })
        val walk = assertNotNull(first.walk)
        assertEquals(TrackId("0"), walk.intent)
        assertEquals(first.rows.map { snapshot.tracks[it].id }, walk.picks)

        // The app's next request: seeded with the queue's last track, everything queued excluded.
        val queued = first.rows.toSet() + 0
        val eligible = BooleanArray(snapshot.size) { it !in queued }
        val tail = snapshot.tracks[first.rows.last()].id
        val fresh = continuing(snapshot, eligible).build(tail, 3, FloatArray(5))
        assertTrue(fresh.rows.any { it in SATELLITE_ROWS }, "the fixture must reproduce the departure")

        val resumed = continuing(snapshot, eligible).build(tail, 3, FloatArray(5), resume = walk)
        assertEquals(3, resumed.rows.size)
        assertTrue(resumed.rows.all { it in NEIGHBOURS && it !in queued }, "left early: ${resumed.rows}")
        assertEquals(TrackId("0"), resumed.walk?.intent)
    }

    @Test
    fun `plans chained through their walks spend the whole neighbourhood before leaving it`() {
        val snapshot = satellites()
        val queued = mutableSetOf(0)
        val played = mutableListOf<Int>()
        var seed = TrackId("0")
        var walk: ChainWalk? = null
        repeat(4) {
            val eligible = BooleanArray(snapshot.size) { it !in queued }
            val plan = continuing(snapshot, eligible).build(seed, 3, FloatArray(5), resume = walk)
            played += plan.rows
            queued += plan.rows
            seed = snapshot.tracks[plan.rows.last()].id
            walk = plan.walk
        }
        assertEquals(NEIGHBOURS, played.take(NEIGHBOURS.size).toSet())
        assertEquals(played.map { snapshot.tracks[it].id }, walk?.picks)
    }

    @Test
    fun `a walk keeps the latest picks of the longest queue the app plans`() {
        val snapshot = library(near = 125, far = 25)
        val queue = build(snapshot, 50)
        val walk = assertNotNull(queue.walk)
        assertEquals(queue.rows.takeLast(ChainWalk.WINDOW).map { snapshot.tracks[it].id }, walk.picks)
        assertTrue(walk.picksUnderIntent <= walk.picks.size)
    }

    @Test
    fun `a soft neighbourhood lets a strongly preferred outsider in and keeps the rest first`() {
        val snapshot = satellites()
        val outsider = SATELLITE_ROWS.first()
        fun plan(bonus: Float) = SmartChain(
            snapshot, null,
            tuning = ChainTuning(
                continueAfterExhaustion = true,
                neighbourhoodBonus = bonus,
                // Stands in for a scorer that strongly prefers one track outside the neighbourhood.
                personalAffinity = { row -> if (row == outsider) 1f else 0f },
                personalWeight = 6f,
            ),
        ).build(TrackId("0"), 3, FloatArray(5)).rows
        assertFalse(outsider in plan(Float.POSITIVE_INFINITY), "the hard neighbourhood admits no outsider")
        assertTrue(outsider in plan(0.5f), "a small bonus lets the preferred outsider in")
        assertTrue(plan(10f).all { it in NEIGHBOURS }, "a large bonus keeps the neighbourhood first")
    }

    @Test
    fun `without the continuation mode a walk changes nothing`() {
        val snapshot = satellites()
        val walk = ChainWalk(TrackId("0"), listOf(TrackId("1")), picksUnderIntent = 1)
        val plain = SmartChain(snapshot, null).build(TrackId("1"), 5, FloatArray(5))
        assertEquals(plain, SmartChain(snapshot, null).build(TrackId("1"), 5, FloatArray(5), resume = walk))
        assertNull(plain.walk)
    }

    private fun build(snapshot: SmartSnapshot, length: Int): ChainResult = SmartChain(
        snapshot, null, tuning = ChainTuning(continueAfterExhaustion = true),
    ).build(TrackId("0"), length, FloatArray(5))

    private fun continuing(
        snapshot: SmartSnapshot,
        eligible: BooleanArray = BooleanArray(snapshot.size) { true },
    ) = SmartChain(snapshot, null, eligible, tuning = ChainTuning(continueAfterExhaustion = true))

    private fun close(snapshot: SmartSnapshot, a: Int, b: Int) =
        Reanchor.isCloseContinuation(snapshot.centeredCosine(a, b), snapshot.descriptorCosine(a, b))

    /** See [satelliteVectors]: the seed, its neighbours, one satellite per neighbour, fillers. */
    private fun satellites(): SmartSnapshot = requireNotNull(SmartSnapshot.build(
        satelliteVectors().mapIndexed { row, audio ->
            SmartTrack(TrackId(row.toString()), audio, meta = TrackMeta("Title $row", "Artist $row", null, null, null))
        },
    ))

    private fun library(near: Int, far: Int): SmartSnapshot = requireNotNull(SmartSnapshot.build(
        (0..near + far).map { row ->
            SmartTrack(
                TrackId(row.toString()),
                audio = FloatArray(PredictorRuntime.EMBEDDING_DIM).also {
                    it[0] = if (row <= near) 1f else -1f
                    it[row + 1] = 0.04f
                },
                meta = TrackMeta("Title $row", "Artist $row", null, null, null),
            )
        },
    ))
}

/** Rows of [satelliteVectors]; row 0 is the seed. */
internal val NEIGHBOURS: Set<Int> = (1..8).toSet()
internal val SATELLITE_ROWS: Set<Int> = (9..16).toSet()

/**
 * A seed (row 0) with eight close neighbours, each with a satellite that sounds like that neighbour
 * but not like the seed, and ten fillers that keep the library mean near zero. From any neighbour its
 * own satellite is the nearest track, so a plan seeded with a neighbour leaves the seed's
 * neighbourhood at its first pick.
 */
internal fun satelliteVectors(): List<FloatArray> {
    fun unit(vararg parts: Pair<Int, Float>) = FloatArray(PredictorRuntime.EMBEDDING_DIM).also { v ->
        for ((axis, value) in parts) v[axis] += value
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (i in v.indices) v[i] /= norm
    }
    return buildList {
        add(unit(0 to 1f))
        for (i in NEIGHBOURS) add(unit(0 to 1f, (10 + i) to 0.8f))
        for (i in NEIGHBOURS) add(unit((10 + i) to 1f, 0 to 0.3f))
        for (j in 1..10) add(unit(0 to -1f, (200 + j) to 0.05f))
    }
}
