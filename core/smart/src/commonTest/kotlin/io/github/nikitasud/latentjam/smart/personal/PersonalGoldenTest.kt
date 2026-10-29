/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.personal

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The store and the personal term on a fixed history, pinned to values the JVM computed: the same test runs on iOS, so a
 * platform difference in hashing, sorting or float maths fails here (PLAN_PERSONAL §7, golden).
 */
internal class PersonalGoldenTest {

    private val t0 = 1_700_000_000_000L

    private fun fixture(): PersonalModel {
        val library = (0 until 40).map {
            PersonalTrack(TrackId("g$it"), "artist${it % 9}|song$it", listOf("artist${it % 9}", "guest${it % 4}"),
                album = "album${it / 8}", trackNumber = it % 8 + 1, durationMs = 150_000L + 5_000L * it)
        }
        val random = Random(20261028)
        val starts = PersonalStart.entries
        val listens = ArrayList<PersonalListen>()
        var at = t0
        repeat(1500) { i ->
            val start = starts[random.nextInt(starts.size)]
            val skipped = start != PersonalStart.IMPORTED && random.nextInt(6) == 0
            val heard = if (skipped) 1_000L + random.nextLong(25_000L) else 150_000L
            listens += PersonalListen(
                TrackId("g${random.nextInt(12)}"), at, if (start == PersonalStart.IMPORTED) null else heard,
                completed = !skipped, skipped = skipped, start = start,
                surface = PersonalSurface.entries.getOrNull(random.nextInt(PersonalSurface.entries.size + 1)),
                smartSourced = random.nextBoolean(), forYou = random.nextInt(10) == 0,
                albumParent = random.nextBoolean(), shuffleOff = random.nextBoolean(),
            )
            at += heard + if (i % 7 == 6) 3 * 3_600_000L else random.nextLong(60_000L)
        }
        return PersonalModel.build(listens.sortedBy { it.startedAtMs }, library)
    }

    @Test
    fun `the store and its terms match the pinned values`() {
        val m = fixture()
        val got = ArrayList<Float>()
        for (k in 0 until 30) {
            val a = m.songOf(TrackId("g${(k * 7) % 40}"))
            val b = m.songOf(TrackId("g${(k * 11 + 3) % 40}"))
            got += m.strength(a, b)
            got += m.artistStrength(TrackId("g${(k * 7) % 40}"), TrackId("g${(k * 11 + 3) % 40}"))
            got += m.rejection(a, b)
            got += m.lift(a, b)
            got += m.skipPenalty(b)
            got += m.sessions(a, b).toFloat()
            got += m.ownStrength(a, b)
            got += m.affinity(b)
        }
        // The whole store: every link's strength and rejection, summed.
        var positives = 0f
        var negatives = 0f
        for (a in 0 until 40) for (b in 0 until 40) {
            positives += m.positive(a, b)
            negatives += m.negative(a, b)
        }
        got += positives
        got += negatives
        got += m.habitPairs().toFloat()
        if (GOLDEN.isEmpty()) {
            println("GOLDEN = floatArrayOf(" + got.joinToString(", ") { "${it}f" } + ")")
            return
        }
        assertTrue(got.size == GOLDEN.size, "size ${got.size}")
        for (i in got.indices) assertTrue(abs(got[i] - GOLDEN[i]) <= 1e-5f + 1e-5f * abs(GOLDEN[i]), "value $i: ${got[i]} vs ${GOLDEN[i]}")
    }

    @Test
    fun `the v6 jar days and tiers match the pinned values`() {
        val m = fixture()
        val got = ArrayList<Float>()
        for (k in 0 until 30) {
            val a = m.songOf(TrackId("g${(k * 7) % 40}"))
            val b = m.songOf(TrackId("g${(k * 11 + 3) % 40}"))
            got += m.jar(a, b)
            got += m.days(a, b).toFloat()
            got += m.tier(a, b).toFloat()
            got += m.rejectionSessions(a, b).toFloat()
            got += m.jarWeight(a, b)
        }
        var jars = 0f
        for (a in 0 until 40) for (b in 0 until 40) jars += m.jar(a, b)
        got += jars
        if (GOLDEN_V6.isEmpty()) {
            println("GOLDEN_V6 = floatArrayOf(" + got.joinToString(", ") { "${it}f" } + ")")
            return
        }
        assertTrue(got.size == GOLDEN_V6.size, "size ${got.size}")
        for (i in got.indices) assertTrue(abs(got[i] - GOLDEN_V6[i]) <= 1e-5f + 1e-5f * abs(GOLDEN_V6[i]), "value $i: ${got[i]} vs ${GOLDEN_V6[i]}")
    }

    private companion object {
        val GOLDEN_V6: FloatArray = floatArrayOf(
            0.99121094f, 2.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 1.0097656f, 2.0f, 2.0f, 2.0f, 0.16829427f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            1.0f, 2.3144531f, 5.0f, 2.0f, 0.0f, 1.0f, 92.48865f,
        )

        val GOLDEN: FloatArray = floatArrayOf(
            0.16520183f, 1.0f, 0.0f, 0.3387979f, 0.5f, 2.0f, 0.13216145f, 1.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.31429037f, 1.0f, 0.43798828f, 0.66492146f, 0.5f, 3.0f, 0.27604166f, 1.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.5f, 0.0f, 0.0f, 1.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.43554688f, 1.0f, 0.0f, 0.51509595f, 0.5f, 5.0f, 0.34830728f, 1.0f,
            141.85059f, 56.208984f, 93.0f,
        )
    }
}
