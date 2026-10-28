/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.personal

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One test per evidence and aggregation rule, each with PLAN_PERSONAL v2 §5's frozen criterion. */
internal class PersonalModelTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L
    private val tol = 0.01f

    private fun library(n: Int = 120, durationMs: Long = 200_000L) = (0 until n).map {
        PersonalTrack(TrackId("t$it"), key = "song$it", artists = listOf("artist$it"), durationMs = durationMs)
    }

    /** A listening log; [session] starts a new one an hour later. */
    private class Log(t0: Long) {
        val listens = ArrayList<PersonalListen>()
        var clock = t0
        fun play(
            track: Int, start: PersonalStart, skippedAfterMs: Long? = null, smart: Boolean = false, forYou: Boolean = false,
            surface: PersonalSurface? = null, gapMs: Long = 0L, completed: Boolean = true,
        ): Log {
            clock += gapMs
            val heard = skippedAfterMs ?: 200_000L
            listens += PersonalListen(TrackId("t$track"), clock, heard, completed && skippedAfterMs == null, skippedAfterMs != null,
                start, surface, smartSourced = smart || forYou, forYou = forYou)
            clock += heard
            return this
        }
        fun session(): Log = also { clock += 3_600_000L }
    }

    private fun model(log: Log, lib: List<PersonalTrack> = library()) = PersonalModel.build(log.listens, lib)
    private fun PersonalModel.s(id: Int) = songOf(TrackId("t$id"))
    private fun PersonalModel.pos(a: Int, b: Int) = positive(s(a), s(b))
    private fun PersonalModel.str(a: Int, b: Int) = strength(s(a), s(b))

    /** The same A → B occurrence in two sessions (a link needs two to be kept), as [occurrence] writes B. */
    private fun twice(occurrence: Log.() -> Unit): PersonalModel {
        val log = Log(t0)
        repeat(2) { log.session().play(0, PersonalStart.PICK).occurrence() }
        return model(log)
    }

    @Test
    fun `grades per occurrence follow the frozen table`() {
        assertEquals(2 * 0.85f, twice { play(1, PersonalStart.PICK) }.pos(0, 1), tol)
        assertEquals(2 * 1.0f, twice { play(1, PersonalStart.PICK, surface = PersonalSurface.SEARCH) }.pos(0, 1), tol)
        assertEquals(2 * 0.7f, twice { play(1, PersonalStart.PICK, surface = PersonalSurface.QUEUE) }.pos(0, 1), tol)
        assertEquals(2 * 0.7f, twice { play(1, PersonalStart.QUEUED) }.pos(0, 1), tol)
        assertEquals(2 * 0.25f, twice { play(1, PersonalStart.AUTO) }.pos(0, 1), tol)
        // An unfinished continuation is no acceptance.
        assertEquals(0f, twice { play(1, PersonalStart.AUTO, completed = false) }.pos(0, 1))
    }

    @Test
    fun `SMART-sourced positives count at most a quarter of the listener's own`() {
        // Alone they teach nothing (and the link is not even kept).
        assertEquals(0f, twice { play(1, PersonalStart.AUTO, smart = true) }.pos(0, 1))
        assertEquals(0f, twice { play(1, PersonalStart.PICK, forYou = true) }.pos(0, 1))
        val log = Log(t0)
        repeat(2) { log.session().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK) }
        repeat(10) { log.session().play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, smart = true) }
        val m = model(log)
        assertEquals(1.7f + 0.25f * 1.7f, m.pos(0, 1), tol)
        assertEquals(2, m.sessions(m.s(0), m.s(1)), "SMART's sessions are not the listener's own")
    }

    @Test
    fun `E6 and album order discount an acceptance and For You picks are 0_35`() {
        // E6 inside the cap: own 1.7 plus SMART's 2 x 0.125 = 0.25, under the cap 0.425.
        val log = Log(t0)
        repeat(2) { log.session().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK) }
        repeat(2) { log.session().play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, smart = true) }
        assertEquals(1.7f + 0.25f, model(log).pos(0, 1), tol)
        val forYou = Log(t0)
        repeat(2) { forYou.session().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK) }
        repeat(2) { forYou.session().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK, forYou = true) }
        assertEquals(1.7f + 0.25f * 1.7f, model(forYou).pos(0, 1), tol)
        val lib = (0 until 10).map { PersonalTrack(TrackId("t$it"), "song$it", listOf("a$it"), album = "rel", trackNumber = it + 1, durationMs = 200_000L) }
        val album = ArrayList<PersonalListen>()
        repeat(2) { k ->
            val at = t0 + k * day
            album += PersonalListen(TrackId("t0"), at, 200_000L, true, false, PersonalStart.PICK, albumParent = true, shuffleOff = true)
            album += PersonalListen(TrackId("t1"), at + 200_000L, 200_000L, true, false, PersonalStart.AUTO, albumParent = true, shuffleOff = true)
        }
        val m = PersonalModel.build(album, lib)
        assertEquals(2 * 0.025f, m.positive(m.songOf(TrackId("t0")), m.songOf(TrackId("t1"))), 0.002f)
    }

    @Test
    fun `E3 attention damping and duration`() {
        val log = Log(t0).play(0, PersonalStart.PICK)
        for (i in 1..40) log.play(i, PersonalStart.AUTO)
        log.session().play(0, PersonalStart.PICK)
        for (i in 1..40) log.play(i, PersonalStart.AUTO)
        val m = model(log)
        assertEquals(2 * 0.25f, m.pos(1, 2), tol)
        assertEquals(2 * 0.25f * 0.7f, m.pos(5, 6), tol)
        assertTrue(m.pos(30, 31) < 0.01f)
        val short = PersonalModel.build(Log(t0).also { l -> repeat(2) { l.session().play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO) } }.listens,
            library(durationMs = 30_000L))
        assertEquals(2 * 0.125f, short.positive(short.songOf(TrackId("t0")), short.songOf(TrackId("t1"))), tol)
        val long = PersonalModel.build(Log(t0).also { l -> repeat(2) { l.session().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK) } }.listens,
            library(durationMs = 25 * 60_000L))
        assertEquals(0f, long.positive(long.songOf(TrackId("t0")), long.songOf(TrackId("t1"))))
    }

    @Test
    fun `E4 a skip run is one rejection and a sub-2-second skip is ignored and SMART placement does not matter`() {
        val log = Log(t0)
        repeat(2) {
            log.session().play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, skippedAfterMs = 5_000L, smart = true)
                .play(2, PersonalStart.SKIP_NEXT, skippedAfterMs = 4_000L).play(3, PersonalStart.PICK)
        }
        val m = model(log)
        assertEquals(1f, m.rejection(m.s(0), m.s(1)), tol)
        assertEquals(0f, m.rejection(m.s(0), m.s(2)), "the run is one rejection")
        assertEquals(2 * 0.85f, m.pos(0, 3), tol)
        val late = Log(t0)
        repeat(2) { k -> late.session().play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, skippedAfterMs = if (k == 0) 5_000L else 20_000L).play(3, PersonalStart.PICK) }
        val lm = model(late)
        assertEquals(0.75f, lm.rejection(lm.s(0), lm.s(1)), tol)
        val accidental = Log(t0)
        repeat(2) { accidental.session().play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, skippedAfterMs = 1_500L).play(2, PersonalStart.AUTO) }
        val am = model(accidental)
        assertEquals(0f, am.rejection(am.s(0), am.s(1)))
        assertEquals(2 * 0.25f, am.pos(0, 2), tol)
        // One rejecting session is chance.
        val once = model(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, skippedAfterMs = 5_000L).play(3, PersonalStart.PICK))
        assertEquals(0f, once.rejection(once.s(0), once.s(1)))
    }

    @Test
    fun `windows a transition needs 15 minutes end to start and a session 30`() {
        val apart = Log(t0)
        repeat(2) { apart.session().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK, gapMs = 20 * 60_000L) }
        assertEquals(0f, model(apart).pos(0, 1), "20 minutes is no transition")
        val near = Log(t0)
        repeat(2) { near.session().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK, gapMs = 14 * 60_000L) }
        assertEquals(2 * 0.85f, model(near).pos(0, 1), tol)
        // Two pairs 20 minutes apart are one session, which is chance; 40 minutes apart they are two.
        fun pairsApart(gapMinutes: Long): PersonalModel {
            val log = Log(t0)
            repeat(2) { log.play(0, PersonalStart.PICK, gapMs = gapMinutes * 60_000L).play(1, PersonalStart.PICK) }
            return model(log)
        }
        assertEquals(0f, pairsApart(20).str(0, 1))
        assertEquals(2, pairsApart(40).let { it.sessions(it.s(0), it.s(1)) })
    }

    @Test
    fun `strength eight pick sessions in a week make s 1 and seven do not and one is chance`() {
        fun picks(n: Int): PersonalModel {
            val log = Log(t0)
            repeat(n) { k ->
                log.play(10 + k, PersonalStart.PICK).play(0, PersonalStart.PICK).play(1, PersonalStart.PICK)
                log.clock += 18 * 3_600_000L
            }
            return model(log)
        }
        assertEquals(1f, picks(8).str(0, 1))
        assertTrue(picks(7).str(0, 1) < 1f)
        assertEquals(0f, picks(1).str(0, 1), "one session is chance")
        assertTrue(picks(2).str(0, 1) > 0f)
    }

    @Test
    fun `lift filter blocks a song played after everything`() {
        val log = Log(t0)
        for (a in 10 until 50) repeat(3) {
            log.session().play(a, PersonalStart.PICK).play(1, PersonalStart.PICK)
            log.session().play(a, PersonalStart.PICK).play(50 + a, PersonalStart.PICK)
        }
        val m = model(log)
        assertFalse(m.strong(m.s(10), m.s(1)), "popular B: lift ${m.lift(m.s(10), m.s(1))}")
        // The anchor's own follower, seen nowhere else, passes with four sessions.
        val own = Log(t0)
        repeat(4) { own.session().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK) }
        repeat(30) { k -> own.session().play(20 + k % 40, PersonalStart.PICK).play(60 + (k * 7) % 40, PersonalStart.PICK) }
        val om = model(own)
        assertTrue(om.strong(om.s(0), om.s(1)), "own follower: lift ${om.lift(om.s(0), om.s(1))}")
    }

    @Test
    fun `import only five sessions in two weeks reach s 0_307`() {
        val listens = ArrayList<PersonalListen>()
        for (k in 0 until 5) {
            val at = t0 + k * 3 * day
            listens += PersonalListen(TrackId("t${10 + k}"), at, null, true, false, PersonalStart.IMPORTED)
            listens += PersonalListen(TrackId("t0"), at + 200_000L, null, true, false, PersonalStart.IMPORTED)
            listens += PersonalListen(TrackId("t1"), at + 400_000L, null, true, false, PersonalStart.IMPORTED)
        }
        val m = PersonalModel.build(listens, library())
        val a = m.songOf(TrackId("t0"))
        val b = m.songOf(TrackId("t1"))
        assertTrue(m.positive(a, b) >= 1.84f, "S_dec ${m.positive(a, b)}")
        assertTrue(m.strength(a, b) >= 0.307f, "s ${m.strength(a, b)}")
        assertEquals(5, m.sessions(a, b))
    }

    @Test
    fun `a replayed import sequence is one session`() {
        val listens = ArrayList<PersonalListen>()
        for (k in 0 until 4) {
            val at = t0 + k * day
            for ((j, t) in listOf(0, 1, 2).withIndex()) listens += PersonalListen(TrackId("t$t"), at + j * 200_000L, null, true, false, PersonalStart.IMPORTED)
        }
        val m = PersonalModel.build(listens, library())
        assertEquals(0f, m.strength(m.songOf(TrackId("t0")), m.songOf(TrackId("t1"))), "one session however often replayed")
        // The same pair in four sessions, each followed by another song, is four sessions.
        val varied = ArrayList<PersonalListen>()
        for (k in 0 until 4) {
            val at = t0 + k * day
            for ((j, t) in listOf(0, 1, 2 + k).withIndex()) varied += PersonalListen(TrackId("t$t"), at + j * 200_000L, null, true, false, PersonalStart.IMPORTED)
        }
        val v = PersonalModel.build(varied, library())
        assertEquals(4, v.sessions(v.songOf(TrackId("t0")), v.songOf(TrackId("t1"))))
    }

    @Test
    fun `the imported strength from one anchor is capped at 12`() {
        val listens = ArrayList<PersonalListen>()
        var at = t0
        // Song 0 followed by 60 different songs, each in 3 sessions: 180 x 0.4 = 72 before the cap.
        for (k in 0 until 180) {
            listens += PersonalListen(TrackId("t0"), at, null, true, false, PersonalStart.IMPORTED)
            listens += PersonalListen(TrackId("t${1 + k % 60}"), at + 200_000L, null, true, false, PersonalStart.IMPORTED)
            at += 3_600_000L
        }
        val m = PersonalModel.build(listens, library(400))
        val a = m.songOf(TrackId("t0"))
        var total = 0f
        for (b in 0 until 400) total += m.positive(a, b)
        assertTrue(total <= 12.1f, "imported from one anchor: $total")
        // The cap scales each link (3 sessions x 0.4 x 12 / 72 = 0.2) rather than dropping any.
        val kept = (0 until 400).map { m.positive(a, it) }.filter { it > 0f }
        assertTrue(kept.isNotEmpty() && kept.all { kotlin.math.abs(it - 0.2f) < tol }, "kept links $kept")
    }

    @Test
    fun `decay halves at 120 days from each source's newest event`() {
        val log = Log(t0)
        repeat(2) { log.session().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK) }
        log.clock += 120 * day
        log.play(30, PersonalStart.PICK)
        assertEquals(0.85f, model(log).pos(0, 1), tol)
        val listens = ArrayList<PersonalListen>()
        for (k in 0 until 2) {
            listens += PersonalListen(TrackId("t0"), t0 + k * 3_600_000L, null, true, false, PersonalStart.IMPORTED)
            listens += PersonalListen(TrackId("t1"), t0 + k * 3_600_000L + 200_000L, null, true, false, PersonalStart.IMPORTED)
        }
        listens += PersonalListen(TrackId("t5"), t0 + 730 * day, 200_000L, true, false, PersonalStart.PICK)
        val m = PersonalModel.build(listens, library())
        assertEquals(0.8f, m.positive(m.songOf(TrackId("t0")), m.songOf(TrackId("t1"))), tol)
    }

    @Test
    fun `E7 is 0_1 per early skip and at most 0_5`() {
        val log = Log(t0)
        repeat(3) { log.session().play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, skippedAfterMs = 5_000L) }
        val m = model(log)
        assertEquals(0.3f, m.skipPenalty(m.s(1)), tol)
        val many = Log(t0)
        repeat(12) { many.session().play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, skippedAfterMs = 5_000L) }
        val mm = model(many)
        assertEquals(0.5f, mm.skipPenalty(mm.s(1)), tol)
    }

    @Test
    fun `artist pairs need two own sessions`() {
        val lib = listOf(
            PersonalTrack(TrackId("a1"), "a|1", listOf("A")), PersonalTrack(TrackId("a2"), "a|2", listOf("A")),
            PersonalTrack(TrackId("b1"), "b|1", listOf("B")), PersonalTrack(TrackId("b2"), "b|2", listOf("B", "C")),
        )
        fun pick(id: String, at: Long) = PersonalListen(TrackId(id), at, 200_000L, true, false, PersonalStart.PICK)
        val listens = listOf(pick("a1", t0), pick("b1", t0 + 200_000L), pick("a2", t0 + day), pick("b2", t0 + day + 200_000L))
        val m = PersonalModel.build(listens, lib)
        assertTrue(m.artistStrength(TrackId("a1"), TrackId("b2")) > 0f, "A -> B in two sessions")
        assertEquals(0f, m.artistStrength(TrackId("a1"), TrackId("a2")), "the same artist is no artist pair")
        assertEquals(0f, m.strength(m.songOf(TrackId("a1")), m.songOf(TrackId("b1"))), "each song pair once: chance")
    }

    @Test
    fun `versions share their evidence`() {
        val lib = listOf(
            PersonalTrack(TrackId("a"), "x|song a", listOf("x")),
            PersonalTrack(TrackId("b"), "y|song b", listOf("y")),
            PersonalTrack(TrackId("b-slowed"), "y|song b", listOf("y")),
        )
        val listens = listOf(
            PersonalListen(TrackId("a"), t0, 200_000L, true, false, PersonalStart.PICK),
            PersonalListen(TrackId("b"), t0 + 200_000L, 200_000L, true, false, PersonalStart.PICK),
            PersonalListen(TrackId("a"), t0 + day, 200_000L, true, false, PersonalStart.PICK),
            PersonalListen(TrackId("b-slowed"), t0 + day + 200_000L, 200_000L, true, false, PersonalStart.PICK),
        )
        val m = PersonalModel.build(listens, lib)
        assertEquals(m.songOf(TrackId("b")), m.songOf(TrackId("b-slowed")))
        assertEquals(2, m.sessions(m.songOf(TrackId("a")), m.songOf(TrackId("b"))))
    }

    @Test
    fun `repeats and going back are no transition`() {
        val log = Log(t0)
        repeat(2) { log.session().play(0, PersonalStart.PICK).play(0, PersonalStart.REPEAT).play(1, PersonalStart.PICK).play(0, PersonalStart.SKIP_PREVIOUS) }
        val m = model(log)
        assertEquals(0f, m.pos(1, 0), "going back is no transition")
        assertEquals(2 * 0.85f, m.pos(0, 1), tol)
    }

    @Test
    fun `track affinity repeats and returns over two sessions or more`() {
        val once = model(Log(t0).play(0, PersonalStart.PICK).play(0, PersonalStart.REPEAT))
        assertEquals(0f, once.affinity(once.s(0)), "one session is chance")
        val log = Log(t0)
        log.session().play(0, PersonalStart.PICK).play(0, PersonalStart.REPEAT)
        log.session().play(5, PersonalStart.PICK).play(0, PersonalStart.PICK).play(0, PersonalStart.PICK)
        log.session().play(5, PersonalStart.PICK).play(0, PersonalStart.SKIP_PREVIOUS)
        val m = model(log)
        assertEquals(1f, m.affinity(m.s(0)), tol)
        val two = Log(t0)
        repeat(2) { two.session().play(0, PersonalStart.PICK).play(0, PersonalStart.REPEAT) }
        val tm = model(two)
        assertEquals(2f / 3f, tm.affinity(tm.s(0)), tol)
    }

    @Test
    fun `the own strength leaves SMART's evidence out`() {
        val log = Log(t0)
        repeat(2) { log.session().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK) }
        repeat(10) { log.session().play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, smart = true) }
        val m = model(log)
        assertEquals(1.7f / 6f, m.ownStrength(m.s(0), m.s(1)), tol)
        assertEquals((1.7f + 0.425f) / 6f, m.str(0, 1), tol)
    }

    @Test
    fun `no history is empty`() {
        assertTrue(PersonalModel.build(emptyList(), library()).isEmpty)
        assertTrue(PersonalModel.EMPTY.isEmpty)
    }
}
