/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransGraphTest {
    private val day = 86_400_000L
    private val seedMs = 100 * day

    private fun cfg(variant: String) = RankerModel.TransGraph(variant, 3, 30L * 60_000, 120.0, 2.0, 1.5, 1.5)

    /** Session k, days ago: A (full), then [next] (played [playedS] of 200 s, skipped or not, started as [start]). */
    private fun session(daysAgo: Int, next: String, playedS: Long, skipped: Boolean, start: String): List<PlayEvent> {
        val t = seedMs - daysAgo * day
        return listOf(
            PlayEvent(TrackId("A"), t, 200_000, 200_000, false, "USER_PICK"),
            PlayEvent(TrackId(next), t + 201_000, playedS * 1000, 200_000, skipped, start),
        )
    }

    @Test
    fun habitIsPositiveAndEarlySkipIsNegative() {
        val plays = (1..4).flatMap { session(it * 3, "B", 200, false, "USER_PICK") } +
            (1..4).flatMap { session(it * 3 + 1, "C", 6, true, "AUTO_ADVANCE") }
        val g = TransGraph(plays, seedMs, cfg("A"))
        assertTrue(g.value(TrackId("A"), TrackId("B")) > 0.5, "habit lifts")
        assertTrue(g.value(TrackId("A"), TrackId("C")) < -0.3, "early skips after A push C down")
        assertEquals(0.0, g.value(TrackId("A"), TrackId("Z")))
    }

    @Test
    fun lateSkipOfTheFadeOutCountsAsAListen() {
        val plays = (1..3).flatMap { session(it * 2, "B", 190, true, "USER_PICK") }
        assertTrue(TransGraph(plays, seedMs, cfg("A")).value(TrackId("A"), TrackId("B")) > 0.4)
    }

    @Test
    fun autoplayCountsLessThanOwnPicksAndVariantCIgnoresTheStart() {
        val auto = (1..3).flatMap { session(it * 2, "B", 200, false, "AUTO_ADVANCE") }
        val pick = (1..3).flatMap { session(it * 2, "B", 200, false, "USER_PICK") }
        val a = TransGraph(auto, seedMs, cfg("A")).value(TrackId("A"), TrackId("B"))
        val p = TransGraph(pick, seedMs, cfg("A")).value(TrackId("A"), TrackId("B"))
        assertTrue(a < p, "autoplay $a < own pick $p")
        assertEquals(p, TransGraph(auto, seedMs, cfg("C")).value(TrackId("A"), TrackId("B")), 1e-9)
    }

    @Test
    fun sessionKnnHasNoNegativesAndSessionsBreakOnGaps() {
        val plays = (1..3).flatMap { session(it * 3, "C", 6, true, "AUTO_ADVANCE") } + (1..3).flatMap { session(it * 3 + 1, "B", 200, false, "USER_PICK") }
        val d = TransGraph(plays, seedMs, cfg("D"))
        assertEquals(0.0, d.value(TrackId("A"), TrackId("C")), "a skipped track is not in the session, and D has no negatives")
        assertTrue(d.value(TrackId("A"), TrackId("B")) > 0.0)
        val gap = listOf(
            PlayEvent(TrackId("A"), seedMs - day, 200_000, 200_000, false, "USER_PICK"),
            PlayEvent(TrackId("B"), seedMs - day + 200_000 + 31 * 60_000, 200_000, 200_000, false, "USER_PICK"),
        )
        assertEquals(0.0, TransGraph(gap, seedMs, cfg("A")).value(TrackId("A"), TrackId("B")))
    }

    @Test
    fun importsAreNeutralNeverNegativeAndWeighLess() {
        fun imp(daysAgo: Int, next: String) = session(daysAgo, next, 200, false, "USER_PICK").map {
            PlayEvent(it.trackId, it.startedAtMs, it.durationMs, it.durationMs, false, null, imported = true)
        }
        val imported = (1..3).flatMap { imp(it * 2, "B") }
        val inApp = (1..3).flatMap { session(it * 2, "B", 200, false, "USER_PICK") }
        val vi = TransGraph(imported, seedMs, cfg("A")).value(TrackId("A"), TrackId("B"))
        val va = TransGraph(inApp, seedMs, cfg("A")).value(TrackId("A"), TrackId("B"))
        assertTrue(vi > 0.0 && vi < va, "imported $vi lifts, less than in-app $va")
    }

    @Test
    fun aSingleRejectionCountsHalf() {
        val one = TransGraph(session(2, "C", 6, true, "AUTO_ADVANCE"), seedMs, cfg("A")).value(TrackId("A"), TrackId("C"))
        val two = TransGraph(session(2, "C", 6, true, "AUTO_ADVANCE") + session(4, "C", 6, true, "AUTO_ADVANCE"), seedMs, cfg("A"))
            .value(TrackId("A"), TrackId("C"))
        assertTrue(one < 0.0 && one > two, "one skip $one is milder than two $two")
    }

    @Test
    fun importGapsBreakTheChainAndDuplicatesOfInAppPlaysAreDropped() {
        val t = seedMs - 2 * day
        fun imp(id: String, at: Long) = PlayEvent(TrackId(id), at, 200_000, 200_000, false, null, imported = true)
        // A, then an unmatched row (200 s missing), then B: no A -> B edge.
        val gapped = TransGraph(listOf(imp("A", t), imp("B", t + 400_000)), seedMs, cfg("A"))
        assertEquals(0.0, gapped.value(TrackId("A"), TrackId("B")))
        val contiguous = TransGraph(listOf(imp("A", t), imp("B", t + 201_000)), seedMs, cfg("A"))
        assertTrue(contiguous.value(TrackId("A"), TrackId("B")) > 0.0)
        // The same listens imported on top of in-app plays count once.
        val inApp = session(2, "B", 200, false, "USER_PICK")
        val both = TransGraph(inApp + inApp.map { imp(it.trackId.value, it.startedAtMs + 5_000) }, seedMs, cfg("A"))
        assertEquals(TransGraph(inApp, seedMs, cfg("A")).value(TrackId("A"), TrackId("B")), both.value(TrackId("A"), TrackId("B")), 1e-12)
    }
}
