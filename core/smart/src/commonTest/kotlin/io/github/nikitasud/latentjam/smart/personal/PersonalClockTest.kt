/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.personal

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The v6.1 gates (PLAN_PERSONAL v6.1): the weighted A-day clock. A habit A → B fades on the days the listener plays A
 * and goes elsewhere themselves, and only slowly with the calendar. Song 0 is A, song 1 is B.
 */
internal class PersonalClockTest {

    private val day = 86_400_000L
    private val hour = 3_600_000L
    /** 2023-11-15 08:13 UTC: a morning, so a day's sessions stay on one calendar day. */
    private val t0 = 1_700_000_000_000L + 10 * hour

    private val library = (0 until 40).map {
        PersonalTrack(TrackId("t$it"), "song $it", listOf("artist $it"), album = if (it in 20..29) "Album" else null,
            trackNumber = if (it in 20..29) it - 19 else null, durationMs = 200_000L)
    }

    private fun listen(id: Int, at: Long, start: PersonalStart, smart: Boolean = false, shuffleOff: Boolean = false,
                       albumParent: Boolean = false, surface: PersonalSurface? = PersonalSurface.SEARCH) =
        PersonalListen(TrackId("t$id"), at, 200_000L, true, false, start, surface = surface, smartSourced = smart,
            shuffleOff = shuffleOff, albumParent = albumParent)

    /** A → [b] picked by hand at [at]. */
    private fun pick(a: Int, b: Int, at: Long) = listOf(listen(a, at, PersonalStart.PICK), listen(b, at + 200_000L, PersonalStart.PICK))

    /** Eight own sessions 0 → 1 on eight days (b = 6), the last on day 7. */
    private val saturated = (0 until 8).flatMap { pick(0, 1, t0 + it * day) }

    /** An unrelated listen at [at]: the device's newest event, so two histories share their reference. */
    private fun reference(at: Long) = listOf(listen(39, at, PersonalStart.PICK))

    private fun jar(listens: List<PersonalListen>, a: Int = 0, b: Int = 1, localDay: (Long) -> Long = PersonalModel::utcDay): Float {
        val m = PersonalModel.build(listens.sortedBy { it.startedAtMs }, library, localDay)
        return m.jar(m.songOf(TrackId("t$a")), m.songOf(TrackId("t$b")))
    }

    @Test
    fun `B and C half and half both stay at least 5`() {
        val listens = (0 until 60).flatMap { d -> pick(0, if (d % 2 == 0) 1 else 2, t0 + d * day) }
        val b = jar(listens, b = 1)
        val c = jar(listens, b = 2)
        println("v6.1: B/C 50/50 b(B) $b b(C) $c")
        assertTrue(b >= 5f && c >= 5f, "B $b C $c")
    }

    @Test
    fun `dormant 180 days keeps at least 4`() {
        val b = jar(saturated + reference(t0 + 7 * day + 180 * day))
        println("v6.1: dormant 180 d b $b")
        assertTrue(b >= 4f, "b $b")
        assertEquals(6f * 0.5f.pow(180f / 730f), b, 0.02f)
    }

    @Test
    fun `no SMART hop shuffle or For You follower lowers b`() {
        val end = t0 + 60 * day
        val followers = (0 until 30).flatMap { d ->
            val at = t0 + (10 + d) * day
            listOf(
                listen(0, at, PersonalStart.PICK),
                listen(5 + d % 3, at + 200_000L, PersonalStart.AUTO, smart = true),
                listen(0, at + 2 * hour, PersonalStart.PICK),
                listen(9, at + 2 * hour + 200_000L, PersonalStart.AUTO, shuffleOff = false),
                listen(0, at + 4 * hour, PersonalStart.PICK),
                listen(11, at + 4 * hour + 200_000L, PersonalStart.PICK, surface = PersonalSurface.FOR_YOU),
            )
        }
        assertEquals(jar(saturated + reference(end)), jar(saturated + followers + reference(end)))
    }

    @Test
    fun `genre-only days never change b`() {
        val end = t0 + 60 * day
        val genre = (0 until 30).flatMap { d -> pick(10 + d % 5, 15 + d % 5, t0 + (10 + d) * day) }
        assertEquals(jar(saturated + reference(end)), jar(saturated + genre + reference(end)))
    }

    @Test
    fun `stop-only A-days change only the calendar term`() {
        val end = t0 + 60 * day
        val stops = (0 until 30).map { d -> listen(0, t0 + (10 + d) * day, PersonalStart.PICK) }
        val b = jar(saturated + stops + reference(end))
        assertEquals(jar(saturated + reference(end)), b)
        assertEquals(6f * 0.5f.pow((end - (t0 + 7 * day)).toFloat() / (730f * day)), b, 0.02f)
    }

    @Test
    fun `20 album-order A-days keep at least 4_9`() {
        // A is the album's third track (t22), its habit B is t1; on 20 days A plays on into the album's fourth track.
        val habit = (0 until 8).flatMap { pick(22, 1, t0 + it * day) }
        val album = (0 until 20).flatMap { d ->
            val at = t0 + (10 + d) * day
            listOf(
                listen(22, at, PersonalStart.PICK, shuffleOff = true, albumParent = true),
                listen(23, at + 200_000L, PersonalStart.AUTO, shuffleOff = true, albumParent = true),
            )
        }
        val b = jar(habit + album, a = 22)
        println("v6.1: 20 album-order A-days b $b")
        assertTrue(b >= 4.9f, "b $b")
    }

    @Test
    fun `one A-day elsewhere lowers b by at most 3_5 percent`() {
        val last = t0 + 7 * day
        // The rule, exactly: a day later with one A-day elsewhere, b keeps 2^(−(1/20 + 1/730)).
        val factor = DayClockProbe.factor(PersonalModel::utcDay, last + day, listOf(Triple(last + day, 2, 1.0)), 1, last)
        println("v6.1: one A-day elsewhere keeps $factor (lowered by ${1 - factor})")
        assertTrue(1 - factor <= 0.035, "lowered by ${1 - factor}")
        // In the store (half precision, a step of 1/256 near 6): within that rounding of the same.
        val before = jar(saturated)
        val after = jar(saturated + pick(0, 2, last + day))
        assertEquals(before * factor.toFloat(), after, 4f / 1024f)
    }

    @Test
    fun `a day of B itself is no day elsewhere for A to B and the day's strongest other follower counts once`() {
        val ref = t0 + 30 * day
        val at = t0 + 5 * day
        val calendar = 0.5.pow((ref - at).toDouble() / (730.0 * day))
        val d = t0 + 10 * day
        fun f(follows: List<Triple<Long, Int, Double>>, b: Int) = DayClockProbe.factor(PersonalModel::utcDay, ref, follows, b, at)
        assertEquals(calendar, f(listOf(Triple(d, 1, 1.0)), b = 1), 1e-12, "B's own day")
        assertEquals(calendar * 0.5.pow(1.0 / 20), f(listOf(Triple(d, 1, 1.0)), b = 2), 1e-12, "elsewhere for C")
        // B and C on one day: elsewhere for B (C, 1); a second pick of C and a continuation the same day count once.
        val day = listOf(Triple(d, 1, 1.0), Triple(d + hour, 2, 1.0), Triple(d + 2 * hour, 2, 1.0), Triple(d + 3 * hour, 3, 0.25))
        assertEquals(calendar * 0.5.pow(1.0 / 20), f(day, b = 1), 1e-12)
        assertEquals(calendar * 0.5.pow(0.25 / 20), f(listOf(Triple(d, 3, 0.25)), b = 1), 1e-12, "a continuation")
    }

    /** A zone at UTC+1 that moves to UTC+2 at [switch] (a DST change), in local calendar days. */
    private val switch = t0 + 12 * day
    private fun dstDay(ms: Long): Long {
        val offset = if (ms < switch) hour else 2 * hour
        val local = ms + offset
        return if (local >= 0) local / day else -((-local + day - 1) / day)
    }

    @Test
    fun `local days with a DST change and the golden`() {
        // Two A-days elsewhere at 22:30 UTC: under UTC+2 they fall on the next local day, each on its own day.
        val late = t0 + 14 * hour + 17 * 60_000L   // 22:30 UTC
        val elsewhere = pick(0, 2, late + 10 * day) + pick(0, 3, late + 13 * day) + pick(0, 4, late + 13 * day + 2 * hour)
        val listens = saturated + elsewhere + reference(t0 + 40 * day)
        val utc = jar(listens)
        val dst = jar(listens, localDay = ::dstDay)
        println("v6.1: DST case b under UTC $utc, under the zone $dst")
        assertTrue(dst > utc, "under the zone the two late picks of day 13 share one local day")
        val got = doubleArrayOf(utc.toDouble(), dst.toDouble(),
            DayClockProbe.factor(::dstDay, t0 + 40 * day, listOf(Triple(late + 10 * day, 2, 1.0), Triple(late + 13 * day, 3, 1.0)), 1, t0 + 7 * day))
        if (GOLDEN.isEmpty()) {
            println("GOLDEN_CLOCK = doubleArrayOf(" + got.joinToString(", ") + ")")
            return
        }
        for (i in got.indices) assertTrue(abs(got[i] - GOLDEN[i]) <= 1e-9 + 1e-6 * abs(GOLDEN[i]), "value $i: ${got[i]} vs ${GOLDEN[i]}")
    }

    private fun Float.pow(x: Float): Float = kotlin.math.exp(x * kotlin.math.ln(this))
    private fun Double.pow(x: Double): Double = kotlin.math.exp(x * kotlin.math.ln(this))

    private companion object {
        val GOLDEN: DoubleArray = doubleArrayOf(5.2421875, 5.42578125, 0.9042505782372094)
    }
}

/** The clock's raw factor for a golden at 1e-9 (no half precision): anchor 0's followers (time, song, class). */
internal object DayClockProbe {
    fun factor(localDay: (Long) -> Long, refMs: Long, follows: List<Triple<Long, Int, Double>>, b: Int, atMs: Long): Double {
        val clock = DayClock(localDay, refMs)
        for ((at, song, weight) in follows) clock.follow(0, at, song, weight)
        clock.finish()
        return clock.factor(0, b, atMs)
    }
}
