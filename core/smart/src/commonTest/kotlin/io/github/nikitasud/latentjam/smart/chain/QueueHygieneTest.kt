/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ChainConfig.CHAIN_HYGIENE: fragments, very long tracks and jarring kinds give up spreads, softly. */
internal class QueueHygieneTest {
    @Test
    fun `a fragment that sounds closest no longer leads the queue`() {
        val snapshot = library(firstMs = 30_000L)
        assertEquals("1", first(snapshot, hygiene = 0f), "without hygiene the closest track leads")
        assertNotEquals("1", first(snapshot, hygiene = ChainConfig.CHAIN_HYGIENE))
    }

    @Test
    fun `a very long track gives way too`() {
        val snapshot = library(firstMs = 700_000L)
        assertNotEquals("1", first(snapshot, hygiene = ChainConfig.CHAIN_HYGIENE))
    }

    @Test
    fun `a normal length or an unknown one pays nothing`() {
        for (ms in listOf(200_000L, null)) {
            val snapshot = library(firstMs = ms)
            assertEquals(first(snapshot, hygiene = 0f), first(snapshot, hygiene = ChainConfig.CHAIN_HYGIENE))
        }
    }

    @Test
    fun `hygiene is soft and the fragment still plays later`() {
        val snapshot = library(firstMs = 30_000L)
        val rows = SmartChain(snapshot, null, tuning = ChainTuning(continueAfterExhaustion = true))
            .build(TrackId("0"), 8, FloatArray(5)).rows.map { snapshot.tracks[it].id.value }
        assertTrue("1" in rows, "the fragment is not filtered: $rows")
    }

    @Test
    fun `after a long seed long tracks pay nothing`() {
        val snapshot = library(firstMs = 700_000L, seedMs = 900_000L)
        assertEquals("1", first(snapshot, hygiene = ChainConfig.CHAIN_HYGIENE))
    }

    private fun first(snapshot: SmartSnapshot, hygiene: Float): String = SmartChain(
        snapshot, null, tuning = ChainTuning(continueAfterExhaustion = true, hygiene = hygiene, durationSanity = false),
    ).build(TrackId("0"), 4, FloatArray(5)).rows.first().let { snapshot.tracks[it].id.value }

    /** The seed (0), six close tracks by different artists with 1 the closest, fillers far away. */
    private fun library(firstMs: Long?, seedMs: Long = 200_000L): SmartSnapshot {
        fun unit(vararg parts: Pair<Int, Float>) = FloatArray(PredictorRuntime.EMBEDDING_DIM).also { v ->
            for ((axis, value) in parts) v[axis] += value
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            for (i in v.indices) v[i] /= norm
        }
        val tracks = buildList {
            add(Triple(unit(0 to 1f), "Seed", seedMs))
            for (i in 1..6) add(Triple(unit(0 to 1f, (10 + i) to 0.2f + 0.02f * i), "Artist $i", if (i == 1) firstMs else 200_000L))
            for (k in 1..12) add(Triple(unit(0 to -1f, (60 + k) to 0.5f), "Filler $k", 200_000L))
        }
        return requireNotNull(SmartSnapshot.build(tracks.mapIndexed { row, (audio, artist, ms) ->
            SmartTrack(TrackId(row.toString()), audio, meta = TrackMeta("Title $row", artist, null, null, null, durationMs = ms))
        }))
    }
}
