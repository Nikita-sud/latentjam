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
import kotlin.test.assertNotEquals

/** PLAN_FINAL §8: argmax with a salted pick among the near-best rows, the press in the key. */
class RankerDecoderTest {
    private fun model(epsilon: Double) = RankerModel.parse(
        """{"features": ["same_family"], "mean": [0], "sd": [1], "weights": [1], "runPenalty": 0, "anchor": "seed",
           "epsilon": $epsilon}""",
    )!!

    private val seed = TrackDescriptor(TrackId("seed"), title = "s", artist = "S", genre = "Rock", fileName = "s.mp3")
    // Ten equally good Rock tracks and two worse Pop ones.
    private val library = (0 until 10).map { TrackDescriptor(TrackId("r$it"), title = "r$it", artist = "A$it", genre = "Rock") } +
        (0 until 2).map { TrackDescriptor(TrackId("p$it"), title = "p$it", artist = "P$it", genre = "Pop") }

    @Test
    fun withoutEpsilonOnlyExactTiesAreDrawnAndTheWorseNeverWins() = runTest {
        val ranker = SmartRanker(model(0.0), null, null)
        val plan = ranker.plan(seed, library, library, emptySet(), length = 10, press = 0).map { it.value }
        assertEquals((0 until 10).map { "r$it" }.toSet(), plan.toSet())
    }

    @Test
    fun theSamePressRepeatsAndAnotherPressVaries() = runTest {
        val ranker = SmartRanker(model(0.01), null, null)
        val first = ranker.plan(seed, library, library, emptySet(), length = 5, press = 0)
        assertEquals(first, ranker.plan(seed, library, library, emptySet(), length = 5, press = 0))
        val others = (1L..5L).map { ranker.plan(seed, library, library, emptySet(), length = 5, press = it) }
        assertNotEquals(listOf(first), others.distinct())
        // The pick is always among the near-best: never a Pop track while Rock ones remain.
        assertEquals(true, (others + listOf(first)).all { plan -> plan.all { it.value.startsWith("r") } })
    }

    @Test
    fun libraryOrderDoesNotChangeTheQueue() = runTest {
        val ranker = SmartRanker(model(0.01), null, null)
        val a = ranker.plan(seed, library, library, emptySet(), length = 8, press = 2)
        val shuffled = library.reversed()
        assertEquals(a, SmartRanker(model(0.01), null, null).plan(seed, shuffled, shuffled, emptySet(), length = 8, press = 2))
    }
}
