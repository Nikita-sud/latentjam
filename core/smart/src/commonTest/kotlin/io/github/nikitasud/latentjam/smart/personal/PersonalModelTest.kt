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

/** One test per evidence and aggregation rule, each with PLAN_PERSONAL §7's frozen criterion. */
internal class PersonalModelTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    private fun library(n: Int = 60, durationMs: Long = 200_000L) = (0 until n).map {
        PersonalTrack(TrackId("t$it"), key = "song$it", artists = listOf("artist$it"), durationMs = durationMs)
    }

    /** A listen builder: [at] minutes after [t0] plus [days]. */
    private class Log(private val t0: Long) {
        val listens = ArrayList<PersonalListen>()
        var clock = t0
        fun play(
            track: Int, start: PersonalStart, completed: Boolean = true, skippedAfterMs: Long? = null, smart: Boolean = false,
            changed: Boolean = false, surface: PersonalSurface? = null, gapMs: Long = 0L,
        ): Log {
            clock += gapMs
            val heard = skippedAfterMs ?: 200_000L
            listens += PersonalListen(
                TrackId("t$track"), clock, listenedMs = heard, completed = completed && skippedAfterMs == null,
                skipped = skippedAfterMs != null, start = start, surface = surface, smartPlaced = smart, personalChanged = changed,
            )
            clock += heard
            return this
        }
        fun newSession(): Log = also { clock += 3_600_000L }
        fun day(): Log = also { clock += 86_400_000L }
    }

    private fun model(log: Log, lib: List<PersonalTrack> = library()) = PersonalModel.build(log.listens, lib)
    private fun PersonalModel.w(a: Int, b: Int) = weight(songOf(TrackId("t$a")), songOf(TrackId("t$b")))
    private fun PersonalModel.h(a: Int, b: Int) = habit(songOf(TrackId("t$a")), songOf(TrackId("t$b")))
    private fun PersonalModel.neg(a: Int, b: Int) = rejection(songOf(TrackId("t$a")), songOf(TrackId("t$b")))

    @Test
    fun `E3 an accepted continuation is positive and below a pick`() {
        val m = model(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO))
        val w = m.w(0, 1)
        assertTrue(w > 0f && w < PersonalModel.PICK_UNKNOWN, "E3 $w")
        assertEquals(PersonalModel.ACCEPT, w, 2e-3f)
    }

    @Test
    fun `E4 a skip then a pick is a rejection and a choice and a skip run is one rejection`() {
        val m = model(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, skippedAfterMs = 5_000L)
            .play(2, PersonalStart.SKIP_NEXT, skippedAfterMs = 4_000L).play(3, PersonalStart.SKIP_NEXT, skippedAfterMs = 20_000L)
            .play(4, PersonalStart.PICK))
        assertTrue(m.neg(0, 1) > 0f, "A -> X1 rejected")
        assertEquals(0f, m.neg(0, 2), "the run is one rejection")
        assertEquals(0f, m.neg(0, 3))
        assertEquals(0f, m.w(0, 1))
        assertEquals(PersonalModel.PICK_UNKNOWN, m.w(0, 4), 2e-3f)
        // A 1.5 s skip is accidental: no rejection, and the next song still follows A.
        val accidental = model(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, skippedAfterMs = 1_500L).play(2, PersonalStart.AUTO))
        assertEquals(0f, accidental.neg(0, 1))
        assertEquals(PersonalModel.ACCEPT, accidental.w(0, 2), 2e-3f)
        // An early skip weighs more than a late one.
        val late = model(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, skippedAfterMs = 20_000L).play(4, PersonalStart.PICK))
        assertTrue(m.neg(0, 1) > late.neg(0, 1))
    }

    @Test
    fun `E6 SMART's own placement halves an acceptance and a personal change zeroes it`() {
        // One own pick of the pair keeps the link in the store; the second session is the one compared.
        fun withOwn(smart: Boolean, changed: Boolean = false) = model(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.PICK).newSession()
            .play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, smart = smart, changed = changed)).w(0, 1) - PersonalModel.PICK_UNKNOWN
        val plain = withOwn(smart = false)
        assertEquals(PersonalModel.ACCEPT, plain, 2e-3f)
        assertEquals(0.5f * plain, withOwn(smart = true), 2e-3f)
        assertEquals(0f, withOwn(smart = true, changed = true), 2e-3f)
        // SMART's placements alone teach nothing and are not kept.
        assertEquals(0f, model(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, smart = true)).w(0, 1))
    }

    @Test
    fun `E1 E2 pick grades by surface`() {
        fun grade(surface: PersonalSurface?, start: PersonalStart = PersonalStart.PICK) =
            model(Log(t0).play(0, PersonalStart.PICK).play(1, start, surface = surface)).w(0, 1)
        assertEquals(1.0f, grade(PersonalSurface.SEARCH), 2e-3f)
        assertEquals(1.0f, grade(PersonalSurface.COLLECTION), 2e-3f)
        assertEquals(0.7f, grade(PersonalSurface.QUEUE), 2e-3f)
        // A For You pick is SMART's suggestion (E6, capped like SMART's own): beside an own pick it adds 0.35.
        val forYou = model(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.PICK).newSession()
            .play(0, PersonalStart.PICK).play(1, PersonalStart.PICK, surface = PersonalSurface.FOR_YOU)).w(0, 1)
        assertEquals(PersonalModel.PICK_UNKNOWN + 0.35f, forYou, 2e-3f)
        assertEquals(0f, grade(PersonalSurface.FOR_YOU), "alone it teaches nothing")
        assertEquals(0.85f, grade(null), 2e-3f)
        assertEquals(0.7f, grade(null, PersonalStart.QUEUED), 2e-3f)
    }

    @Test
    fun `E5 a gap over 15 minutes is no transition`() {
        val m = model(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.PICK, gapMs = 16 * 60_000L))
        assertEquals(0f, m.w(0, 1))
        val near = model(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.PICK, gapMs = 14 * 60_000L))
        assertTrue(near.w(0, 1) > 0f)
    }

    @Test
    fun `A1 one event is weak and twenty make a habit`() {
        val one = model(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.PICK))
        assertTrue(one.h(0, 1) < 0.2f, "one pick ${one.h(0, 1)}")
        assertTrue(one.h(0, 1) <= PersonalModel.PICK_UNKNOWN / (PersonalModel.PICK_UNKNOWN + PersonalModel.ALPHA) + 1e-3f)
        val log = Log(t0)
        repeat(20) { log.newSession().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK) }
        // Background listening, so song 1 is not the listener's whole diet.
        repeat(200) { i -> log.newSession().play(10 + i % 40, PersonalStart.PICK).play(10 + (i * 7 + 3) % 40, PersonalStart.PICK) }
        val many = model(log)
        assertTrue(many.h(0, 1) > 0.6f, "twenty picks ${many.h(0, 1)}")
    }

    @Test
    fun `A2 a song played after everything gains no lift from an anchor at its base rate`() {
        // Forty anchors, each followed by B half the time: B's base rate is one half, as is P(B | A).
        val log = Log(t0)
        for (a in 10 until 50) repeat(5) {
            log.newSession().play(a, PersonalStart.PICK).play(1, PersonalStart.PICK)
            log.newSession().play(a, PersonalStart.PICK).play(50 + a, PersonalStart.PICK)
        }
        val m = model(log, library(120))
        assertEquals(0f, m.h(10, 1), "popular B, P at its base rate")
        assertTrue(m.h(10, 60) > 0f, "the anchor's own follower lifts")
    }

    @Test
    fun `A4 decay halves at 120 days from the source's newest listen and an old import is its own reference`() {
        val log = Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.PICK)
        log.clock += 120 * day - 400_000L
        log.play(20, PersonalStart.PICK)
        val m = model(log)
        assertTrue(abs(m.w(0, 1) - PersonalModel.PICK_UNKNOWN * 0.5f) < 1e-3f, "${m.w(0, 1)}")
        // An import two years before the device's listens, newest of its own source: undecayed.
        val lib = library()
        val listens = listOf(
            PersonalListen(TrackId("t0"), t0, null, true, false, PersonalStart.IMPORTED),
            PersonalListen(TrackId("t1"), t0 + 200_000L, null, true, false, PersonalStart.IMPORTED),
            PersonalListen(TrackId("t5"), t0 + 730 * day, 200_000L, true, false, PersonalStart.PICK),
        )
        val imported = PersonalModel.build(listens, lib)
        assertEquals(PersonalModel.IMPORT, imported.weight(imported.songOf(TrackId("t0")), imported.songOf(TrackId("t1"))), 2e-3f)
    }

    @Test
    fun `L1 album order and replayed sequences are discounted in imports`() {
        val lib = (0 until 10).map { PersonalTrack(TrackId("t$it"), "song$it", listOf("a"), album = "rel", trackNumber = it + 1, durationMs = 200_000L) } +
            (10 until 20).map { PersonalTrack(TrackId("t$it"), "song$it", listOf("b$it"), durationMs = 200_000L) }
        fun imp(id: Int, at: Long) = PersonalListen(TrackId("t$id"), at, null, true, false, PersonalStart.IMPORTED)
        val album = PersonalModel.build(listOf(imp(0, t0), imp(1, t0 + 200_000L)), lib)
        val loose = PersonalModel.build(listOf(imp(10, t0), imp(11, t0 + 200_000L)), lib)
        val wAlbum = album.weight(album.songOf(TrackId("t0")), album.songOf(TrackId("t1")))
        val wLoose = loose.weight(loose.songOf(TrackId("t10")), loose.songOf(TrackId("t11")))
        assertTrue(wAlbum <= 0.1f * wLoose + 1e-3f, "album $wAlbum vs $wLoose")
        // The same three songs in order in two sessions: a playlist or radio replay.
        val replay = listOf(imp(10, t0), imp(11, t0 + 200_000L), imp(12, t0 + 400_000L),
            imp(10, t0 + day), imp(11, t0 + day + 200_000L), imp(12, t0 + day + 400_000L))
        val r = PersonalModel.build(replay, lib)
        val once = PersonalModel.build(replay.take(3), lib)
        val wr = r.weight(r.songOf(TrackId("t10")), r.songOf(TrackId("t11")))
        val wo = once.weight(once.songOf(TrackId("t10")), once.songOf(TrackId("t11")))
        assertTrue(wr <= 2 * 0.2f * wo + 1e-5f, "replayed $wr vs once $wo (two sessions at 0.2 each)")
    }

    @Test
    fun `L1 imports carry no negatives`() {
        val lib = library()
        val listens = listOf(
            PersonalListen(TrackId("t0"), t0, null, true, false, PersonalStart.IMPORTED),
            PersonalListen(TrackId("t1"), t0 + 200_000L, null, true, false, PersonalStart.IMPORTED),
        )
        val m = PersonalModel.build(listens, lib)
        assertEquals(0f, m.rejection(m.songOf(TrackId("t0")), m.songOf(TrackId("t1"))))
        assertEquals(0f, m.trackPrior(m.songOf(TrackId("t1")), hearted = false))
    }

    @Test
    fun `import mass is capped at 300 picks`() {
        val lib = library(200)
        val listens = ArrayList<PersonalListen>()
        var at = t0
        repeat(5_000) { i ->
            listens += PersonalListen(TrackId("t${(i * 37) % 200}"), at, null, true, false, PersonalStart.IMPORTED)
            at += 200_000L
        }
        val m = PersonalModel.build(listens, lib)
        var total = 0f
        for (a in 0 until 200) for (b in 0 until 200) total += m.weight(a, b)
        assertTrue(total <= 300 * PersonalModel.PICK_UNKNOWN + 1f, "import mass $total")
    }

    @Test
    fun `E7 against O2 a hearted song skipped early ten times nets between -0_5 and 0`() {
        val log = Log(t0)
        repeat(10) { log.newSession().play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, skippedAfterMs = 5_000L) }
        val m = model(log)
        val prior = m.trackPrior(m.songOf(TrackId("t1")), hearted = true)
        assertTrue(prior >= -0.5f && prior <= 0f, "net $prior")
        assertEquals(PersonalModel.HEART, m.trackPrior(m.songOf(TrackId("t9")), hearted = true), 2e-3f)
    }

    @Test
    fun `a repeat and going back are no transition but affinity`() {
        val m = model(Log(t0).play(0, PersonalStart.PICK).play(0, PersonalStart.REPEAT).play(1, PersonalStart.PICK)
            .play(0, PersonalStart.SKIP_PREVIOUS))
        assertEquals(0f, m.w(0, 0))
        assertEquals(0f, m.w(1, 0), "going back is no transition")
        assertTrue(m.trackPrior(m.songOf(TrackId("t0")), hearted = false) > 0f)
    }

    @Test
    fun `duration short songs count by length and long ones not at all`() {
        val short = PersonalModel.build(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO).listens, library(60, durationMs = 30_000L))
        assertEquals(PersonalModel.ACCEPT * 0.5f, short.w(0, 1), 2e-3f)
        val long = PersonalModel.build(Log(t0).play(0, PersonalStart.PICK).play(1, PersonalStart.PICK).listens, library(60, durationMs = 25 * 60_000L))
        assertEquals(0f, long.w(0, 1))
    }

    @Test
    fun `attention a long passive streak fades to nothing`() {
        val log = Log(t0).play(0, PersonalStart.PICK)
        for (i in 1..60) log.play(i, PersonalStart.AUTO)
        val m = model(log)
        assertEquals(PersonalModel.ACCEPT, m.w(1, 2), 1e-3f)
        assertTrue(m.w(30, 31) < 0.01f, "streak position 30: ${m.w(30, 31)}")
    }

    @Test
    fun `album order on the device is discounted`() {
        val lib = (0 until 10).map { PersonalTrack(TrackId("t$it"), "song$it", listOf("a"), album = "rel", trackNumber = it + 1, durationMs = 200_000L) }
        val listens = listOf(
            PersonalListen(TrackId("t0"), t0, 200_000L, true, false, PersonalStart.PICK, albumParent = true, shuffleOff = true),
            PersonalListen(TrackId("t1"), t0 + 200_000L, 200_000L, true, false, PersonalStart.AUTO, albumParent = true, shuffleOff = true),
        )
        val m = PersonalModel.build(listens, lib)
        assertEquals(PersonalModel.ACCEPT * PersonalModel.ALBUM_FACTOR, m.weight(m.songOf(TrackId("t0")), m.songOf(TrackId("t1"))), 2e-3f)
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
            PersonalListen(TrackId("b-slowed"), t0 + 200_000L, 200_000L, true, false, PersonalStart.PICK),
        )
        val m = PersonalModel.build(listens, lib)
        assertEquals(m.songOf(TrackId("b")), m.songOf(TrackId("b-slowed")))
        assertTrue(m.weight(m.songOf(TrackId("a")), m.songOf(TrackId("b"))) > 0f)
    }

    @Test
    fun `D1 and D2 thresholds count sessions and SMART's own evidence never makes a far habit`() {
        val log = Log(t0)
        repeat(8) { log.newSession().play(0, PersonalStart.PICK).play(1, PersonalStart.PICK) }
        val m = model(log)
        val a = m.songOf(TrackId("t0"))
        val b = m.songOf(TrackId("t1"))
        assertEquals(8, m.sessions(a, b))
        assertTrue(m.strong(a, b))
        assertTrue(b in m.farHabits(a, log.clock).toList())
        val smart = Log(t0)
        repeat(30) { smart.newSession().play(0, PersonalStart.PICK).play(1, PersonalStart.AUTO, smart = true) }
        val s = model(smart)
        assertTrue(s.farHabits(s.songOf(TrackId("t0")), smart.clock).isEmpty())
        assertTrue(!s.strong(s.songOf(TrackId("t0")), s.songOf(TrackId("t1"))), "SMART's own acceptances are not countable")
        // A far habit must be recent: 61 days later it is gone.
        assertTrue(m.farHabits(a, log.clock + 61 * day).isEmpty())
    }

    @Test
    fun `recency buckets`() {
        val m = model(Log(t0).play(0, PersonalStart.PICK))
        val s = m.songOf(TrackId("t0"))
        assertEquals(1f, m.recency(s, t0 + 10 * 60_000L))
        assertEquals(0.6f, m.recency(s, t0 + 2 * 3_600_000L))
        assertEquals(0.26f, m.recency(s, t0 + 3 * day))
        assertEquals(0f, m.recency(s, t0 + 100 * day))
        // A skip is no listen.
        val skipped = model(Log(t0).play(0, PersonalStart.PICK, skippedAfterMs = 5_000L))
        assertEquals(0f, skipped.recency(skipped.songOf(TrackId("t0")), t0 + 60_000L))
    }

    @Test
    fun `no history is empty`() {
        assertTrue(PersonalModel.build(emptyList(), library()).isEmpty)
        assertTrue(PersonalModel.EMPTY.isEmpty)
    }
}
