/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SmartRanker.warm computes the hub scores a first press would: the press after it plans exactly the same queue. */
class HubWarmTest {
    private fun model(hub: Boolean) = RankerModel.parse(
        """{"name": "t", "features": ["same_artist", "same_family"], "mean": [0, 0], "sd": [1, 1], "weights": [0.2, 0.1]
           ${if (hub) """, "hubPenalty": {"beta": 4.0, "topk": 3, "sample": 40}""" else ""}}""",
    )!!

    // 300 tracks: the hub's unit vectors are read in 256-row chunks, so a chunk boundary is crossed.
    private val library = (0 until 300).map {
        TrackDescriptor(TrackId("t" + it.toString().padStart(3, '0')), title = "t$it", artist = "a${it % 37}", genre = if (it % 3 == 0) "Rock" else "Pop")
    }

    private val audio = object : AudioSource {
        private val rows = library.associate { t ->
            var x = t.id.value.hashCode().toLong() * 2654435761L
            t.id to FloatArray(12) {
                x = x * 6364136223846793005L + 1442695040888963407L
                ((x ushr 40).toInt() / 16_777_216f) - 0.5f + if (it == 0) 0.3f else 0f
            }
        }
        override suspend fun vector(id: TrackId): FloatArray? = rows[id]?.copyOf()
        override suspend fun neighbours(id: TrackId, k: Int): List<TrackId> = emptyList()
    }

    private fun ranker(hub: Boolean = true) = SmartRanker(model(hub), null, null, audio = audio)

    private suspend fun SmartRanker.press() = plan(PlanRequest(library[5], library, library, length = 20, press = 3L))

    @Test
    fun `a warmed ranker plans the same queue and scores as a cold one`() = runTest {
        val cold = ranker().press()
        val warmed = ranker()
        assertEquals(300, warmed.warm(library))
        val warm = warmed.press()
        assertEquals(cold.ids, warm.ids)
        assertEquals(cold.picks.map { it.score.toBits() }, warm.picks.map { it.score.toBits() })
        assertTrue(cold.fromModel == 20 && !cold.timedOut)
    }

    @Test
    fun `warm is a no-op without a hub penalty`() = runTest {
        assertEquals(0, ranker(hub = false).warm(library))
    }
}
