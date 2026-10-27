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

/** "Keep together": marked playlists join the pool (RK) and earn a soft bonus beside the seed and the previous pick, CORE and FULL. */
class KeepTogetherTest {
    private fun model(extra: String = "") = RankerModel.parse(
        """{"name": "kt", "features": ["same_family", "same_artist"], "mean": [0, 0], "sd": [1, 1], "weights": [1.0, 0.5],
           "runPenalty": 1.0, "anchor": "mix", "epsilon": 0.0$extra}""",
    )!!

    private val creditRun = """, "artistReentry": {"lambda": 5.0, "k": 10, "gamma": 0.7, "credit": 0.5, "creditRun": true}"""

    private fun track(i: Int, genre: String) =
        TrackDescriptor(TrackId("t$i"), title = "Song $i", artist = "Artist ${i % 23}", genre = genre, durationMs = 200_000, fileName = "t$i.mp3")

    /** 60 rock tracks around the seed and 30 jazz tracks the seed's family never reaches first. */
    private val library = (0 until 60).map { track(it, "Rock") } + (60 until 90).map { track(it, "Jazz") }
    private val seed = library[0]
    private val jazz = library.drop(60).take(8).map { it.id }.toSet()

    private suspend fun plan(m: RankerModel, groups: List<Set<TrackId>>, lib: List<TrackDescriptor> = library, personal: Boolean = false) =
        SmartRanker(m, null, null, fullModel = m).plan(PlanRequest(seed, lib, lib, 20, personal = personal, companionGroups = groups))

    @Test
    fun noMarksChangesNothing() = runTest {
        for (m in listOf(model(), model(creditRun))) {
            val none = plan(m, emptyList()).ids
            assertEquals(none, plan(m, listOf(setOf(seed.id), setOf(library[5].id))).ids, "one-track groups relate nothing")
            assertEquals(none, SmartRanker(m, null, null).plan(PlanRequest(seed, library, library, 20)).ids, "the default request")
        }
    }

    @Test
    fun aMarkedPlaylistPlaysNearItsSeedInCoreAndFull() = runTest {
        val group = listOf(jazz + seed.id)
        for (m in listOf(model(), model(creditRun))) for (personal in listOf(false, true)) {
            val without = plan(m, emptyList(), personal = personal).ids
            val with = plan(m, group, personal = personal).ids
            assertTrue(without.take(10).none { it in jazz }, "unmarked, the other family stays out of the first 10")
            val marked = with.take(10).count { it in jazz }
            assertTrue(marked >= 2, "marked tracks come forward ($marked of the first 10; personal=$personal)")
            // Soft and bounded: the seed's own family still holds the queue.
            assertTrue(with.take(10).count { it !in jazz } >= 4, "a marked playlist does not take the queue over: $with")
        }
    }

    @Test
    fun theBonusFollowsThePreviousPick() = runTest {
        // Marked pairs in the seed's own family, the seed in none: a marked track picked on its own merit pulls its
        // playlist-mate next, where the plain plan scatters them.
        val m = model(creditRun)
        val lib = library
        val pairs = (1 until 40 step 2).map { setOf(lib[it].id, lib[it + 20].id) }
        val ids = plan(m, pairs).ids
        val after = ids.zipWithNext().count { (a, b) -> pairs.any { a in it && b in it } }
        val plain = plan(m, emptyList()).ids.zipWithNext().count { (a, b) -> pairs.any { a in it && b in it } }
        assertTrue(after > plain, "playlist-mates sit next to each other more often ($after vs $plain): $ids")
    }

    @Test
    fun rkRetrievesMarkedTracksIntoALargeLibrarysPool() = runTest {
        val big = (0 until 700).map { track(it, if (it < 650) "Rock" else "Jazz") }
        val marked = big.drop(650).take(12).map { it.id }.toSet() + big[0].id
        val m = model(""", "serving": {"wholeLibraryUpTo": 100, "quotas": {"family": 80}, "l2": true, "poolTarget": 150}""")
        val r = SmartRanker(m, null, null)
        val without = r.plan(PlanRequest(big[0], big, big, 20))
        val with = r.plan(PlanRequest(big[0], big, big, 20, companionGroups = listOf(marked)))
        assertEquals(null, without.sourceCounts["RK"])
        assertEquals(12, with.sourceCounts["RK"], "every marked track the retrievers missed joins the pool: ${with.sourceCounts}")
        assertTrue(with.ids.take(10).any { it in marked }, "and can play")
    }

    @Test
    fun rkKeepsItsQuotaAndPerArtistCap() = runTest {
        // 60 marked tracks by 3 artists: the cap (8 per artist) and the quota (40) both bound what RK adds.
        val big = (0 until 700).map { i ->
            if (i in 600 until 660) TrackDescriptor(TrackId("t$i"), title = "M $i", artist = "Marked ${i % 3}", genre = "Jazz", durationMs = 200_000, fileName = "t$i.mp3")
            else track(i, "Rock")
        }
        val marked = big.subList(600, 660).map { it.id }.toSet() + big[0].id
        val m = model(""", "serving": {"wholeLibraryUpTo": 100, "quotas": {"family": 80}, "l2": true, "poolTarget": 150}""")
        val got = SmartRanker(m, null, null).plan(PlanRequest(big[0], big, big, 20, companionGroups = listOf(marked))).sourceCounts["RK"]
        assertEquals(24, got, "3 artists × 8")
    }

    @Test
    fun theBoundCountsATopUpsServedRows() = runTest {
        // A top-up whose last 10 served rows end in 5 marked ones: the bonus is gone while they stay in the window (the
        // first 6 picks), so the marks change nothing there (the whole library is the pool, RK adds nothing). Both paths.
        val prefix = listOf(10, 11, 12, 13, 14).map { library[it].id } + (60 until 65).map { library[it].id }
        val group = listOf(jazz + seed.id + prefix.drop(5))
        val cands = library.filter { it.id !in prefix }
        for (m in listOf(model(), model(creditRun))) {
            val r = SmartRanker(m, null, null)
            val with = r.plan(PlanRequest(seed, library, cands, 10, prefix = prefix, companionGroups = group)).ids
            val without = r.plan(PlanRequest(seed, library, cands, 10, prefix = prefix)).ids
            assertEquals(without.take(6), with.take(6), "half of the last 10 marked: no bonus")
        }
    }

    @Test
    fun theSpecParsesAndDefaults() {
        assertEquals(1.0, model().keepTogether.seed)
        assertEquals(1.5, model().keepTogether.prev)
        val k = model(""", "keepTogether": {"seed": 0.5, "quota": 10}""").keepTogether
        assertEquals(0.5, k.seed); assertEquals(1.5, k.prev); assertEquals(10, k.quota); assertEquals(8, k.artistCap)
    }
}
