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
        repeat(600) { i ->
            val start = starts[random.nextInt(starts.size)]
            val skipped = start != PersonalStart.IMPORTED && random.nextInt(6) == 0
            val heard = if (skipped) 1_000L + random.nextLong(25_000L) else 150_000L
            listens += PersonalListen(
                TrackId("g${random.nextInt(40)}"), at, if (start == PersonalStart.IMPORTED) null else heard,
                completed = !skipped, skipped = skipped, start = start,
                surface = PersonalSurface.entries.getOrNull(random.nextInt(PersonalSurface.entries.size + 1)),
                smartPlaced = random.nextBoolean(), personalChanged = random.nextInt(10) == 0,
                albumParent = random.nextBoolean(), shuffleOff = random.nextBoolean(),
            )
            at += heard + if (i % 7 == 6) 3 * 3_600_000L else random.nextLong(60_000L)
        }
        return PersonalModel.build(listens.sortedBy { it.startedAtMs }, library)
    }

    @Test
    fun `the store and its terms match the pinned values`() {
        val m = fixture()
        val now = t0 + 40L * 86_400_000L
        val got = ArrayList<Float>()
        for (k in 0 until 30) {
            val a = m.songOf(TrackId("g${(k * 7) % 40}"))
            val b = m.songOf(TrackId("g${(k * 11 + 3) % 40}"))
            got += m.habit(a, b)
            got += m.artistHabit(TrackId("g${(k * 7) % 40}"), TrackId("g${(k * 11 + 3) % 40}"))
            got += m.rejection(a, b)
            got += m.confidence(a, b)
            got += m.recency(b, now)
            got += m.trackPrior(b, hearted = k % 3 == 0)
        }
        var weights = 0f
        for (a in 0 until 40) for (b in 0 until 40) weights += m.weight(a, b)
        got += weights
        if (GOLDEN.isEmpty()) {
            println("GOLDEN = floatArrayOf(" + got.joinToString(", ") { "${it}f" } + ")")
            return
        }
        assertTrue(got.size == GOLDEN.size, "size ${got.size}")
        for (i in got.indices) assertTrue(abs(got[i] - GOLDEN[i]) <= 1e-5f + 1e-5f * abs(GOLDEN[i]), "value $i: ${got[i]} vs ${GOLDEN[i]}")
    }

    private companion object {
        val GOLDEN = floatArrayOf(
            0.0f, 0.008554087f, 0.0f, 0.0f, 0.097f, 0.15f,
            0.0f, 0.0031031817f, 0.0f, 0.0f, 0.036f, -0.14399362f,
            0.0f, 0.0f, 0.0f, 0.0f, 0.097f, 0.052975625f,
            0.0f, 0.0028448675f, 0.0f, 0.0f, 0.036f, -0.08939442f,
            0.0f, 0.008554087f, 0.0f, 0.0f, 0.036f, 7.195622E-4f,
            0.0f, 0.020333758f, 0.0f, 0.0f, 0.097f, 0.15f,
            0.0f, 0.0f, 0.0f, 0.0f, 0.097f, -0.046513394f,
            0.0f, 0.0028448675f, 0.0f, 0.0f, 0.097f, 0.056469798f,
            0.0f, 0.008554087f, 0.10975875f, 0.0f, 0.097f, -0.14642347f,
            0.0f, 0.0031031817f, 0.11077341f, 0.0f, 0.097f, -0.19554543f,
            0.0f, 0.0f, 0.0f, 0.0f, 0.036f, -0.29381144f,
            0.0f, 0.0011178192f, 0.0f, 0.0f, 0.097f, -0.09290258f,
            0.0f, 0.008554087f, 0.0f, 0.0f, 0.097f, 0.15f,
            0.0f, 0.0031031817f, 0.0f, 0.0f, 0.097f, -0.14763567f,
            0.0f, 2.7377214E-4f, 0.0f, 0.0f, 0.097f, -0.047248915f,
            0.0f, 0.0011178192f, 0.0f, 0.0f, 0.097f, -0.335514f,
            0.0f, 0.008554087f, 0.0f, 0.0f, 0.097f, 0.046160035f,
            0.0f, 0.011412325f, 0.0f, 0.0f, 0.097f, 0.0535181f,
            0.0f, 2.7377214E-4f, 0.0f, 0.0f, 0.097f, 0.15f,
            0.0f, 0.0011178192f, 0.0f, 0.0f, 0.097f, -0.23246771f,
            0.0f, 0.008554087f, 0.0f, 0.0f, 0.097f, -0.0035233498f,
            0.0f, 0.0031031817f, 0.0f, 0.0f, 0.036f, 0.10141633f,
            0.0f, 0.0f, 0.0f, 0.0f, 0.097f, 0.054661527f,
            0.0f, 0.0011178192f, 0.0f, 0.0f, 0.097f, -0.23989674f,
            0.0f, 0.008554087f, 0.0f, 0.0f, 0.097f, 0.15f,
            0.0f, 0.0031031817f, 0.0f, 0.0f, 0.097f, -0.05057901f,
            0.0f, 0.0026724401f, 0.0f, 0.0f, 0.097f, 0.002912365f,
            0.0f, 0.015875839f, 0.0f, 0.0f, 0.097f, -0.08442667f,
            0.0f, 0.008554087f, 0.0f, 0.0f, 0.036f, -0.09219211f,
            0.0f, 0.011412325f, 0.0f, 0.0f, 0.097f, 0.14534007f,
            51.09935f,
        )
    }
}
