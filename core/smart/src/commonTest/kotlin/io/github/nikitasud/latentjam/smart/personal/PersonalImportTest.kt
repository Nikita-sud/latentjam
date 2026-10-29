/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.personal

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The LB-import fixes (PLAN_PERSONAL, 2026-10-29): imported evidence obeys v6 / v6.1 like the device's own, with one jar
 * per pair, one A-day clock per anchor and one reference. Song 0 is A, song 1 is B.
 */
internal class PersonalImportTest {

    private val day = 86_400_000L
    private val hour = 3_600_000L
    /** 2023-11-15 08:13 UTC. */
    private val t0 = 1_700_000_000_000L + 10 * hour

    private val library = (0 until 60).map {
        PersonalTrack(TrackId("t$it"), "song $it", listOf("artist ${it / 10}"), durationMs = 200_000L)
    }

    private fun imported(id: Int, at: Long) = PersonalListen(TrackId("t$id"), at, null, true, false, PersonalStart.IMPORTED)
    private fun own(id: Int, at: Long) = PersonalListen(TrackId("t$id"), at, 200_000L, true, false, PersonalStart.PICK, surface = PersonalSurface.SEARCH)

    /** [n] imported runs A → B on [n] days from [start]. */
    private fun importedHabit(n: Int, start: Long, a: Int = 0, b: Int = 1) =
        (0 until n).flatMap { listOf(imported(a, start + it * day), imported(b, start + it * day + 200_000L)) }

    private fun model(listens: List<PersonalListen>, nowMs: Long? = null, localDay: (Long) -> Long = PersonalModel::utcDay) =
        PersonalModel.build(listens.sortedBy { it.startedAtMs }, library, localDay, nowMs)

    private fun PersonalModel.jarOf(a: Int = 0, b: Int = 1) = jar(songOf(TrackId("t$a")), songOf(TrackId("t$b")))

    @Test
    fun `D2 the listener's own A-days elsewhere age an imported habit like a device one`() {
        val habit = importedHabit(12, t0)
        val end = t0 + 365 * day
        val before = model(habit, nowMs = end).jarOf()
        // 40 own days in the year after, each A then a pick of another song: 40 / 20 = 2 halvings.
        val elsewhere = (0 until 40).flatMap { d -> listOf(own(0, t0 + (20 + d * 8) * day), own(2 + d % 5, t0 + (20 + d * 8) * day + 200_000L)) }
        val after = model(habit + elsewhere, nowMs = end).jarOf()
        println("D2: imported habit $before -> $after after 40 own A-days elsewhere")
        assertEquals(before * 0.25f, after, 0.02f)
    }

    @Test
    fun `D3 an export keeps aging and a 3-year-old one weighs 2 to the minus 1095 over 730 of a fresh one`() {
        val now = t0 + 2000 * day
        val fresh = model(importedHabit(12, now - 20 * day), nowMs = now).jarOf()
        val old = model(importedHabit(12, now - 20 * day - 1095 * day), nowMs = now).jarOf()
        println("D3: fresh import b $fresh, 3 years older $old (ratio ${old / fresh})")
        assertTrue(fresh >= 4.5f, "a fresh import still teaches: $fresh")
        assertEquals(0.5.pow(1095.0 / 730.0).toFloat(), old / fresh, 0.01f)
        // Without a now (unit tests), the reference is the newest event of either source: here the last import, 9 days younger.
        assertEquals(fresh / 0.5.pow(9.0 / 730.0).toFloat(), model(importedHabit(12, now - 20 * day)).jarOf(), 0.02f)
    }

    @Test
    fun `R1 tier days are local days`() {
        // Three own sessions at 22:30, 23:30 UTC and 00:30 UTC the next day: two UTC days, one day at UTC+2.
        val base = t0 + 14 * hour + 17 * 60_000L
        val listens = (0 until 3).flatMap { k -> listOf(own(0, base + k * hour), own(1, base + k * hour + 200_000L)) }
        val utc = model(listens)
        val zoned = model(listens, localDay = { (it + 2 * hour).let { l -> if (l >= 0) l / day else -((-l + day - 1) / day) } })
        val a = utc.songOf(TrackId("t0"))
        val b = utc.songOf(TrackId("t1"))
        assertEquals(2, utc.days(a, b))
        assertEquals(1, zoned.days(a, b))
        assertEquals(2, utc.tier(a, b))
        assertEquals(1, zoned.tier(a, b))
    }

    @Test
    fun `R2 imported artist-pair evidence is under the per-anchor cap`() {
        // Artist 0's songs (0..9) to twenty songs of artists 1..5, two imported runs each: 40 runs × 0.4 = 16 > 12.
        val listens = (0 until 20).flatMap { j ->
            val a = j % 10
            val b = 10 + j * 2
            (0 until 2).flatMap { k -> listOf(imported(a, t0 + (j * 2 + k) * day), imported(b, t0 + (j * 2 + k) * day + 200_000L)) }
        }
        val m = model(listens, nowMs = t0 + 41 * day)
        var total = 0f
        for (y in 1..5) total += m.artistStrength(TrackId("t0"), TrackId("t${y * 10}")) * PersonalModel.FULL_STRENGTH
        println("R2: imported artist strength summed over artist 0 -> * = $total")
        assertTrue(total <= PersonalModel.IMPORT_ANCHOR_CAP + 0.05f, "uncapped: $total")
    }

    @Test
    fun `R3 imported repeats give track affinity`() {
        val listens = (0 until 3).flatMap { k -> listOf(imported(5, t0 + k * day), imported(5, t0 + k * day + 200_000L)) }
        val m = model(listens)
        val affinity = m.affinity(m.songOf(TrackId("t5")))
        println("R3: imported A->A affinity $affinity")
        assertEquals(3 * 0.4f / PersonalModel.FULL_AFFINITY, affinity, 0.02f)
    }
}
