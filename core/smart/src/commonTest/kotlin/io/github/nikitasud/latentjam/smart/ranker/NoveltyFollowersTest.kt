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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PLAN_NOVELTY: soft novelty with hardLast 0, and the seed's full followers out of both the exclusion and the penalty
 * (the credit-run decoder, which applies the soft-novelty penalty, as every shipped model).
 */
class NoveltyFollowersTest {
    private fun model(novelty: String) = RankerModel.parse(
        """{"name": "nv", "features": ["same_family", "same_artist"], "mean": [0, 0], "sd": [1, 1], "weights": [1.0, 0.5],
           "runPenalty": 1.0, "anchor": "mix", "epsilon": 0.0,
           "artistReentry": {"lambda": 5.0, "k": 10, "gamma": 0.7, "credit": 0.5, "creditRun": true}$novelty}""",
    )!!

    private val library = (0 until 40).map {
        TrackDescriptor(TrackId("t$it"), title = "Song $it", artist = "Artist ${it % 9}", genre = if (it < 30) "Rock" else "Jazz", durationMs = 200_000, fileName = "t$it.mp3")
    }
    private val seed = library[0]
    // Heard in the last hour: the 10 best rock neighbours.
    private val heard = (1..10).map { library[it].id }.toSet()
    private val ages = heard.associateWith { 600_000L }

    private suspend fun plan(m: RankerModel, followers: Set<TrackId> = emptySet(), personal: Boolean = false) =
        SmartRanker(m, null, null, fullModel = m).plan(
            PlanRequest(seed, library, library, 20, exclude = heard, excludeAgeMs = ages, personal = personal, fullFollowers = followers),
        ).ids

    @Test
    fun withoutTheFlagFullFollowersChangeNothing() = runTest {
        for (m in listOf(model(""), model(""", "softNovelty": {"lambda": 1.0, "hardLast": 0}"""))) {
            assertEquals(plan(m), plan(m, setOf(library[9].id)))
        }
    }

    @Test
    fun hardLastZeroLetsRecentTracksBackWithAPenalty() = runTest {
        val hard = plan(model(""))
        assertTrue(hard.none { it in heard }, "today: the 24 h set is out")
        val soft = plan(model(""", "softNovelty": {"lambda": 1.0, "hardLast": 0}"""))
        assertTrue(soft.any { it in heard }, "soft: recent tracks may return")
        assertFalse(soft.take(5).all { it in heard }, "but penalised: they do not take the head of the queue")
    }

    @Test
    fun theFastComponentHoldsJustHeardTracksBackHarder() = runTest {
        val slow = plan(model(""", "softNovelty": {"lambda": 1.0, "hardLast": 0}"""))
        val fast = plan(model(""", "softNovelty": {"lambda": 1.0, "hardLast": 0, "fastLambda": 4.0, "fastTauH": 3}"""))
        assertTrue(fast.take(10).count { it in heard } <= slow.take(10).count { it in heard }, "fewer just-heard tracks near the top")
        assertTrue(fast.take(10).count { it in heard } < 3, "10 minutes ago: held back ($fast)")
    }

    @Test
    fun aFullFollowerOfTheSeedIsNeitherExcludedNorPenalisedInCoreAndFull() = runTest {
        val b = library[9].id   // the seed's artist: near the top whenever it is in the pool unpenalised
        for (personal in listOf(false, true)) {
            for (n in listOf("""{"lambda": 1.0, "hardLast": 10, "fullFollowers": true}""", """{"lambda": 50.0, "hardLast": 0, "fullFollowers": true}""")) {
                val m = model(""", "softNovelty": $n""")
                val without = plan(m, personal = personal)
                val with = plan(m, setOf(b), personal = personal)
                // Exempt = no exclusion and no penalty: present, and earlier than without (absent counts as last).
                assertTrue(b in with, "exempt: in the queue ($with)")
                val before = without.indexOf(b).takeIf { it >= 0 } ?: Int.MAX_VALUE
                assertTrue(with.indexOf(b) < before, "earlier than without the exemption ($with vs $without)")
            }
        }
    }
}
