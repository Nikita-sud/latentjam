/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartRankerTest {
    private fun model(runPenalty: Double = 0.0, anchor: String = "seed", sameArtist: Double = 1.0, sameFamily: Double = 0.5) =
        RankerModel.parse(
            """{"name": "t", "features": ["same_artist", "same_family"], "mean": [0, 0], "sd": [1, 1],
               "weights": [$sameArtist, $sameFamily], "runPenalty": $runPenalty, "anchor": "$anchor"}""",
        )!!

    private fun track(id: String, artist: String, genre: String? = null) =
        TrackDescriptor(TrackId(id), title = id, artist = artist, genre = genre)

    private val seed = track("seed", "A", "Rock")
    private val library = listOf(
        track("a1", "A", "Rock"), track("a2", "A", "Rock"), track("a3", "A", "Rock"),
        track("b1", "B", "Rock"), track("c1", "C", "Pop"), track("d1", "D", "Pop"),
    )

    @Test
    fun `the best scored candidates come first and excluded ones never`() {
        val ranker = SmartRanker(model(), null, null)
        val plan = ranker.plan(seed, library, library, exclude = setOf(TrackId("a2")), length = 4).map { it.value }
        assertEquals(listOf("a1", "a3", "b1", "c1"), plan)
    }

    @Test
    fun `a run of one artist pays runLength spreads of the hop scores`() {
        val ranker = SmartRanker(model(runPenalty = 1.0), null, null)
        // Hop 1: run A (the seed), length 1, spread > 0: a1 (1.5) - spread vs b1 (0.5) ...
        val plan = ranker.plan(seed, library, library, exclude = emptySet(), length = 6).map { it.value }
        assertEquals(listOf("a1", "b1", "a2", "a3", "c1", "d1"), plan)
    }

    @Test
    fun `with the previous pick as anchor the queue follows its last track`() {
        val ranker = SmartRanker(model(anchor = "previous", sameArtist = 0.0, sameFamily = 1.0), null, null)
        val pop = listOf(track("c1", "C", "Pop"), track("r1", "R", "Rock"), track("d1", "D", "Pop"))
        // Seed Rock -> r1 (Rock); from r1 nothing else is Rock -> first in order, c1 (Pop); from c1, d1 (Pop).
        assertEquals(listOf("r1", "c1", "d1"), ranker.plan(seed, pop, pop, emptySet(), 3).map { it.value })
    }

    @Test
    fun `an unknown feature or anchor refuses the asset`() {
        assertNull(RankerModel.parse("""{"features": ["mystery"], "mean": [0], "sd": [1], "weights": [1]}"""))
        assertNull(RankerModel.parse("""{"features": ["cos"], "mean": [0], "sd": [1], "weights": [1], "anchor": "x"}"""))
        assertNull(RankerModel.parse("""{"features": ["cos"], "mean": [0], "sd": [0], "weights": [1]}"""))
    }
}
