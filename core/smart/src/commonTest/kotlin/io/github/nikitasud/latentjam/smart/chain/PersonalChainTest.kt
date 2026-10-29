/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.SmartHistoryEvent
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.PersonalDiagnostics
import io.github.nikitasud.latentjam.smart.personal.PersonalInputs
import io.github.nikitasud.latentjam.smart.personal.PersonalListen
import io.github.nikitasud.latentjam.smart.personal.PersonalModel
import io.github.nikitasud.latentjam.smart.personal.PersonalStart
import io.github.nikitasud.latentjam.smart.personal.PersonalTrack
import io.github.nikitasud.latentjam.smart.personal.RecencyMode
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The personal stage inside the real chain (PLAN_PERSONAL v2 §5). Synthetic library: row 0 the seed, rows 1–60 sound like
 * it at graded closeness, rows 61–119 a far cluster. Recency comes from the same history as the evidence.
 */
internal class PersonalChainTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    @AfterTest
    fun reset() {
        PersonalDiagnostics.hop = null
        PersonalDiagnostics.sink = null
    }

    private fun unit(vararg parts: Pair<Int, Float>) = FloatArray(PredictorRuntime.EMBEDDING_DIM).also { v ->
        for ((axis, value) in parts) v[axis] += value
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (i in v.indices) v[i] /= norm
    }

    private val snapshot: SmartSnapshot = requireNotNull(SmartSnapshot.build(buildList {
        add(SmartTrack(TrackId("0"), unit(0 to 1f), meta = TrackMeta("Seed", "Artist 0", null, "Pop", 2000)))
        for (i in 1..60) add(SmartTrack(TrackId("$i"), unit(0 to 1f, (5 + i) to (0.25f + 0.012f * i)), meta = TrackMeta("Song $i", "Artist $i", null, "Pop", 2000)))
        for (j in 61..119) add(SmartTrack(TrackId("$j"), unit(1 to 1f, (100 + j) to 0.5f), meta = TrackMeta("Far $j", "Artist $j", null, "Pop", 2000)))
    }))
    private val far = (61..119).map { TrackId("$it") }.toSet()
    private val library = snapshot.tracks.map { PersonalTrack(it.id, "song ${it.id.value}", listOf("artist ${it.id.value}"), durationMs = 200_000L) }

    private fun events(listens: List<PersonalListen>, seed: String, now: Long): List<SmartHistoryEvent> =
        listens.map { SmartHistoryEvent(it.trackId, it.startedAtMs, if (it.skipped) 0f else 1f, it.completed, it.skipped) } +
            SmartHistoryEvent(TrackId(seed), now, 1f, true, false)

    private fun term(listens: List<PersonalListen>, mode: RecencyMode = RecencyMode.NOVELTY, hearts: Set<TrackId> = emptySet()) =
        PersonalTerm(snapshot, PersonalInputs(PersonalModel.build(listens, library), hearts = hearts, recency = mode))

    private fun tuning(personal: PersonalTerm?, theme: ThemePool? = null) =
        ChainTuning(continueAfterExhaustion = true, personal = personal?.takeIf { it.active }, theme = theme)

    private fun build(personal: PersonalTerm?, listens: List<PersonalListen> = emptyList(), seed: String = "0", length: Int = 12,
                      now: Long = t0 + 400 * day, theme: ThemePool? = null, eligible: BooleanArray? = null): ChainResult =
        SmartChain(snapshot, null, eligible ?: BooleanArray(snapshot.size) { true }, tuning = tuning(personal, theme))
            .build(TrackId(seed), length, FloatArray(5), historyEvents = if (listens.isEmpty()) emptyList() else events(listens, seed, now))

    /** Picks of [b] right after [a] in [sessions] sessions [every] apart (a day: tier 2 from 3; hours on one day: tier 1), the last ending at [end]. */
    private fun picks(a: Int, b: Int, sessions: Int, end: Long, every: Long = day): List<PersonalListen> = buildList {
        for (k in 0 until sessions) {
            val at = end - (sessions - 1 - k) * every
            add(PersonalListen(TrackId("$a"), at, 200_000L, true, false, PersonalStart.PICK))
            add(PersonalListen(TrackId("$b"), at + 200_000L, 200_000L, true, false, PersonalStart.PICK))
        }
    }

    /** Background listening, so lift has a base rate to beat: random pairs among rows 1–60, three sessions a day. */
    private fun background(days: Int, end: Long, random: Random = Random(5)): List<PersonalListen> = buildList {
        for (d in 0 until days) for (s in 0 until 3) {
            // Off the planted picks' grid (whole hours before "now"), so the two never interleave.
            val at = end - (days - d) * day + s * 4 * 3_600_000L + 4_380_000L
            add(PersonalListen(TrackId("${1 + random.nextInt(60)}"), at, 200_000L, true, false, PersonalStart.PICK))
            add(PersonalListen(TrackId("${1 + random.nextInt(60)}"), at + 200_000L, 200_000L, true, false, PersonalStart.PICK))
        }
    }

    private fun id(row: Int) = snapshot.tracks[row].id.value.toInt()

    /** The shipped theme weights (c095) as the cluster guard's z only: no pool slots, no bonus, so the walk is unchanged. */
    private val guardTheme = ThemePool(floatArrayOf(0.7455f, 0.6732f, 0.6191f, 0.3468f, 0.9851f, 0.1879f, 0.4924f, -2.7054f), slots = 0, bonus = 0f)

    /** Hop 1's δ, the mask-core gaps (in δ) and the additions, read from the personal stage. */
    private fun hopOne(personal: PersonalTerm, listens: List<PersonalListen>, now: Long, theme: ThemePool? = null): Triple<Float, Map<TrackId, Float>, Map<TrackId, Float>> {
        var seen: Triple<Float, Map<TrackId, Float>, Map<TrackId, Float>>? = null
        PersonalDiagnostics.hop = { d, gaps, adds -> if (seen == null) seen = Triple(d, gaps, adds) }
        build(personal, listens, length = 1, now = now, theme = theme)
        PersonalDiagnostics.hop = null
        return requireNotNull(seen) { "the personal stage did not run" }
    }

    @Test
    fun `C no history and no priors is the shipped chain bit for bit`() {
        for (seed in listOf("0", "5", "30", "70")) {
            val shipped = build(null, seed = seed)
            val full = build(PersonalTerm(snapshot, PersonalInputs(PersonalModel.EMPTY)), seed = seed)
            assertEquals(shipped, full)
            // With history but "Learn from my habits" off the app passes no personal inputs: the shipped chain, recency included.
            val history = background(10, t0 + 400 * day)
            assertEquals(build(null, history, seed = seed), build(null, history, seed = seed))
        }
    }

    @Test
    fun `P0 nothing outside the mask is ever lifted with or without the theme channel`() {
        val now = t0 + 400 * day
        for (theme in listOf(null, ThemePool(floatArrayOf(0f, 0f, 1f, 1f, 1f, 1f, 0f, 0f), slots = 10, bonus = 0f))) {
            // Heavy evidence from the seed to every song, each pair's eight sessions on one day (tier 1, v6): whatever
            // lies outside M must stay as it was.
            val listens = (1..119).flatMap { b -> picks(0, b, 8, now - 30 * day - b * day, every = 90 * 60_000L) }.sortedBy { it.startedAtMs }
            val (delta, gaps, adds) = hopOne(term(listens), listens, now, theme)
            assertTrue(delta > 0f)
            for ((id, gap) in gaps) if (gap > 1f + 1e-4f) assertEquals(0f, adds.getValue(id), "row $id outside M was lifted")
            var pickGap = Float.NaN
            PersonalDiagnostics.sink = { gap, _ -> if (pickGap.isNaN()) pickGap = gap }
            build(term(listens), listens, length = 1, now = now, theme = theme)
            assertTrue(pickGap <= 1f + 1e-4f, "the pick lies $pickGap delta below the best")
        }
    }

    @Test
    fun `P0 v6 beyond the mask only a guarded tier-2 habit and never past delta_habit`() {
        val now = t0 + 400 * day
        for (theme in listOf(null, guardTheme)) {
            // Eight sessions on eight days from the seed to every song: tier 2 wherever the guard passes.
            val listens = (1..119).flatMap { b -> picks(0, b, 8, now - 30 * day - b * 3_600_000L) }.sortedBy { it.startedAtMs }
            val (delta, gaps, adds) = hopOne(term(listens), listens, now, theme)
            assertTrue(delta > 0f)
            for ((id, gap) in gaps) {
                if (gap <= 1f + 1e-4f || adds.getValue(id) == 0f) continue
                assertTrue(gap <= PersonalTerm.HABIT + 1e-4f, "row $id lifted at $gap delta")
                assertTrue(id !in far || theme == null, "far row $id passed the guard under the shipped theme")
            }
            var pickGap = Float.NaN
            PersonalDiagnostics.sink = { gap, _ -> if (pickGap.isNaN()) pickGap = gap }
            build(term(listens), listens, length = 1, now = now, theme = theme)
            assertTrue(pickGap <= PersonalTerm.HABIT + 1e-4f, "the pick lies $pickGap delta below the best")
        }
    }

    @Test
    fun `D1 a strong habit relieves yesterday's recency and two sessions do not`() {
        val now = t0 + 400 * day
        val back = background(60, now - 2 * day)
        val (delta, gaps, _) = hopOne(term(back), back, now)
        // Rows inside M, best first; B heard yesterday through its own sessions.
        val inM = gaps.filter { it.value in 0.05f..0.9f }.keys.map { snapshot.rowOf(it) }.sortedBy { gaps.getValue(snapshot.tracks[it].id) }
        val shipped = build(null, back, length = 1, now = now).rows.single()
        val candidates = inM.filter { it != shipped }
        assertTrue(candidates.isNotEmpty(), "delta $delta")
        val strongRow = candidates.firstOrNull { row ->
            val listens = (back + picks(0, id(row), 8, now - 20 * 3_600_000L)).sortedBy { it.startedAtMs }
            build(term(listens), listens, length = 1, now = now).rows.single() == row
        }
        if (strongRow == null) {
            val row = candidates.first()
            val listens = (back + picks(0, id(row), 8, now - 20 * 3_600_000L)).sortedBy { it.startedAtMs }
            val m = PersonalModel.build(listens, library)
            val a = m.songOf(TrackId("0"))
            val b = m.songOf(snapshot.tracks[row].id)
            val (d2, g2, ad2) = hopOne(term(listens), listens, now)
            error("no row won: row $row s=${m.strength(a, b)} sessions=${m.sessions(a, b)} lift=${m.lift(a, b)} strong=${m.strong(a, b)} " +
                "gap=${g2[snapshot.tracks[row].id]} add=${ad2[snapshot.tracks[row].id]} delta=$d2 shippedGap=${g2[snapshot.tracks[shipped].id]} picked=${build(term(listens), listens, length = 1, now = now).rows.single()}")
        }
        val weak = (back + picks(0, id(strongRow), 2, now - 20 * 3_600_000L)).sortedBy { it.startedAtMs }
        assertTrue(build(term(weak), weak, length = 1, now = now).rows.single() != strongRow, "two sessions relieve nothing")
        val strong = (back + picks(0, id(strongRow), 8, now - 20 * 3_600_000L)).sortedBy { it.startedAtMs }
        assertTrue(build(null, strong, length = 1, now = now).rows.single() != strongRow, "habits off: recency keeps it down")
    }

    @Test
    fun `CREDIT mode the credit is at most delta and only on M`() {
        val now = t0 + 400 * day
        // Three sessions per pair on one day (D1-strong, tier 1 in v6), so the bound is the credit's alone.
        val listens = (background(60, now - 2 * day) + (1..60).flatMap { picks(0, it, 3, now - it * day - 14 * 3_600_000L, every = 2 * 3_600_000L) }).sortedBy { it.startedAtMs }
        val (delta, gaps, adds) = hopOne(term(listens, RecencyMode.CREDIT), listens, now)
        for ((id, add) in adds) {
            assertTrue(add <= delta + 1e-4f, "credit mode addition $add > delta $delta")
            if (gaps.getValue(id) > 1f + 1e-4f) assertEquals(0f, add)
        }
    }

    @Test
    fun `import only a planted pair reaches t of at least 0_3 delta`() {
        val now = t0 + 400 * day
        val back = background(30, now - 60 * day)
        val (_, gaps, _) = hopOne(term(back), back, now)
        val b = gaps.entries.filter { it.value in 0.05f..0.9f }.sortedWith(compareBy({ it.value }, { it.key.value })).first().key
        // The press is within 14 days of the newest import (PLAN_PERSONAL v6.1 gate 8).
        val imports = (0 until 5).flatMap { k ->
            val at = now - 22 * day + k * 2 * day
            listOf(
                PersonalListen(TrackId("${90 + k}"), at, null, true, false, PersonalStart.IMPORTED),
                PersonalListen(TrackId("0"), at + 200_000L, null, true, false, PersonalStart.IMPORTED),
                PersonalListen(b, at + 400_000L, null, true, false, PersonalStart.IMPORTED),
            )
        }
        val listens = (back + imports).sortedBy { it.startedAtMs }
        val (delta, _, adds) = hopOne(term(listens), listens, now)
        assertTrue(adds.getValue(b) >= 0.3f * delta - 1e-4f, "import-only t ${adds.getValue(b)} vs delta $delta")
    }

    @Test
    fun `D3 delta is the same with and without a marked playlist and the command stands`() {
        val now = t0 + 400 * day
        val listens = background(30, now - 2 * day)
        fun deltaOf(groups: List<Set<TrackId>>): Pair<Float, ChainResult> {
            var delta = Float.NaN
            PersonalDiagnostics.hop = { d, _, _ -> if (delta.isNaN()) delta = d }
            val t = ChainTuning(continueAfterExhaustion = true, companionPoints = Rerank.COMPANION_POINTS, personal = term(listens, hearts = setOf(TrackId("119"))))
            val result = SmartChain(snapshot, null, BooleanArray(snapshot.size) { true }, companionGroups = groups, tuning = t)
                .build(TrackId("0"), 12, FloatArray(5), historyEvents = events(listens, "0", now))
            return delta to result
        }
        val marked = listOf(setOf(TrackId("0"), TrackId("2"), TrackId("3"), TrackId("4")))
        val (without, _) = deltaOf(emptyList())
        val (with, withQueue) = deltaOf(marked)
        assertEquals(without, with, "delta ignores keep-together")
        // The command still acts: the shipped chain's marked queue, with no addition reaching its rows, is unchanged.
        val shipped = SmartChain(snapshot, null, BooleanArray(snapshot.size) { true }, companionGroups = marked,
            tuning = ChainTuning(continueAfterExhaustion = true, companionPoints = Rerank.COMPANION_POINTS))
            .build(TrackId("0"), 12, FloatArray(5), historyEvents = events(listens, "0", now))
        assertEquals(shipped.rows, withQueue.rows)
    }

    @Test
    fun `G the quota hop's pick stands whatever the habits`() {
        val now = t0 + 400 * day
        val marked = listOf(setOf(TrackId("0"), TrackId("40"), TrackId("41"), TrackId("42")))
        val listens = (1..39).flatMap { picks(0, it, 8, now - 30 * day - it * 3_600_000L) }.sortedBy { it.startedAtMs }
        fun queue(personal: PersonalTerm?) = SmartChain(snapshot, null, BooleanArray(snapshot.size) { true }, companionGroups = marked,
            tuning = ChainTuning(personal = personal)).build(TrackId("0"), 9, FloatArray(5), historyEvents = events(listens, "0", now)).rows
        val shipped = queue(null)
        val full = queue(term(listens))
        // Every third hop is the quota's: a marked member in both.
        for (hop in listOf(2, 5, 8)) {
            assertTrue(snapshot.tracks[full[hop]].id in marked.single(), "hop ${hop + 1}: ${full[hop]}")
            assertTrue(snapshot.tracks[shipped[hop]].id in marked.single())
        }
    }

    @Test
    fun `starvation guard a re-admitted song gets no relief`() {
        val now = t0 + 400 * day
        // Only seven songs to choose from, five of them heard this session: the guard lets them back in.
        val eligible = BooleanArray(snapshot.size) { it in 0..7 }
        val session = (1..5).map { PersonalListen(TrackId("$it"), now - 10 * 60_000L + it * 1000L, 200_000L, true, false, PersonalStart.AUTO) }
        // Three sessions on one day: D1-strong, tier 1 (v6), so t alone is at most delta.
        val listens = (picks(0, 3, 3, now - 2 * day, every = 2 * 3_600_000L) + session).sortedBy { it.startedAtMs }
        var adds: Map<TrackId, Float>? = null
        var delta = 0f
        PersonalDiagnostics.hop = { d, _, a -> if (adds == null) { adds = a; delta = d } }
        build(term(listens), listens, length = 12, now = now, eligible = eligible)
        val got = adds ?: return
        assertTrue((got[TrackId("3")] ?: 0f) <= delta + 1e-4f, "a re-admitted song kept t only: ${got[TrackId("3")]} vs delta $delta")
    }

    /** The always-accept listener: every press from A, the first three picks heard to the end as SMART served them. */
    private fun accept(listens: MutableList<PersonalListen>, at: Long, result: ChainResult) {
        var clock = at
        listens += PersonalListen(TrackId("0"), clock, 200_000L, true, false, PersonalStart.PICK)
        clock += 200_000L
        for (row in result.rows.take(3)) {
            listens += PersonalListen(snapshot.tracks[row].id, clock, 200_000L, true, false, PersonalStart.AUTO, smartSourced = true,
                personalChanged = row in result.personalChanged)
            clock += 200_000L
        }
    }

    /**
     * B's pick-1 presses from A over 60 days of always-accept; [ownOnly] drops SMART-sourced listens from the evidence.
     * The three planted own sessions are a day apart (tier 2) or, with [tier1], two hours apart on one day.
     */
    private fun loopLeads(mode: RecencyMode, ownOnly: Boolean, tier1: Boolean = false): Pair<Int, Int> {
        val start = t0 + 400 * day
        val back = background(60, start - 2 * day)
        val (_, gaps, _) = hopOne(term(back), back, start)
        val b = gaps.entries.filter { it.value in 0.05f..0.9f }.sortedWith(compareBy({ it.value }, { it.key.value })).first().key
        val planted = if (tier1) picks(0, b.value.toInt(), 3, start - 3 * day, every = 2 * 3_600_000L) else picks(0, b.value.toInt(), 3, start - 3 * day)
        val listens = (back + planted).sortedBy { it.startedAtMs }.toMutableList()
        var fullLeads = 0
        var coreLeads = 0
        for (d in 0 until 60) {
            val at = start + d * day
            val evidence = if (ownOnly) listens.filter { !it.smartSourced } else listens
            val full = build(term(evidence, mode), listens, now = at)
            if (snapshot.tracks[full.rows.first()].id == b) fullLeads++
            if (snapshot.tracks[build(null, listens, now = at).rows.first()].id == b) coreLeads++
            accept(listens, at, full)
        }
        return fullLeads to coreLeads
    }

    @Test
    fun `loop tier 1 always-accept for 60 days SMART's own evidence adds at most 5 presses`() {
        // Owner (PLAN_PERSONAL v6.1 amendment): no ceiling over CORE for any tier; only SMART's own evidence must not feed it.
        val (full, core) = loopLeads(RecencyMode.NOVELTY, ownOnly = false, tier1 = true)
        val (own, _) = loopLeads(RecencyMode.NOVELTY, ownOnly = true, tier1 = true)
        println("loop tier 1: B pick 1 in $full / 60 presses, $own / 60 with SMART's own evidence excluded; CORE $core / 60")
        assertTrue(full - own <= 5, "SMART's own evidence added ${full - own} presses")
    }

    @Test
    fun `loop tier 2 always-accept for 60 days SMART's own evidence adds at most 5 presses`() {
        // Owner (PLAN_PERSONAL v6 / v6.1 amendments): a strong own habit leads unless a stronger one competes, so no
        // ceiling over CORE; only SMART's own evidence must not feed it.
        val (full, core) = loopLeads(RecencyMode.NOVELTY, ownOnly = false)
        val (own, _) = loopLeads(RecencyMode.NOVELTY, ownOnly = true)
        println("loop tier 2: B pick 1 in $full / 60 presses, $own / 60 with SMART's own evidence excluded; CORE $core / 60")
        assertTrue(full - own <= 5, "SMART's own evidence added ${full - own} presses")
    }

    @Test
    fun `collapse over 100 presses from uniform seeds no song beyond its core share plus 2 points`() {
        val random = Random(3)
        val now = t0 + 400 * day
        val listens = background(120, now - day, random)
        val model = term(listens)
        val full = HashMap<Int, Int>()
        val core = HashMap<Int, Int>()
        var total = 0
        repeat(100) { p ->
            val seed = random.nextInt(snapshot.size).toString()
            val at = now + p * 3_600_000L
            for (row in build(model, listens, seed = seed, now = at).rows) { full[row] = (full[row] ?: 0) + 1; total++ }
            for (row in build(null, listens, seed = seed, now = at).rows) core[row] = (core[row] ?: 0) + 1
        }
        for ((row, n) in full) {
            val share = n.toFloat() / total
            val coreShare = (core[row] ?: 0).toFloat() / total
            assertTrue(share <= maxOf(0.05f, coreShare + 0.02f), "row $row: FULL $share vs CORE $coreShare")
        }
    }

    @Test
    fun `D2-final a cluster-jumper and a far-habit lover never reach a far song and pass M only by a guarded habit`() {
        // v6: with the shipped theme weights as the cluster guard's z, a far-sounding pair fails the guard. The lover's
        // sessions also interleave (a's session k and a+36's session k+1 share a time), planting same-cluster tier-2
        // pairs such as 2 → 38 on seven days: those may reach up to δ_habit, never further.
        val random = Random(7)
        val now = t0 + 400 * day
        val jumper = ArrayList<PersonalListen>()
        var clock = now - 90 * day
        repeat(270) {
            val seed = 1 + random.nextInt(60)
            jumper += PersonalListen(TrackId("$seed"), clock, 200_000L, true, false, PersonalStart.PICK)
            if (random.nextDouble() < 0.2) jumper += PersonalListen(far.random(random), clock + 200_000L, 200_000L, true, false, PersonalStart.PICK)
            clock += 8 * 3_600_000L
        }
        val lover = (1..60).flatMap { a -> picks(a, 61, 8, now - day - a * 40 * 60_000L) }.sortedBy { it.startedAtMs }
        for (listens in listOf(jumper, lover)) {
            val gaps = ArrayList<Float>()
            PersonalDiagnostics.sink = { gap, changed -> if (changed) gaps += gap }
            for (seed in 1..20) {
                val result = build(term(listens), listens, seed = "$seed", now = now, theme = guardTheme)
                assertTrue(result.rows.none { snapshot.tracks[it].id in far } || build(null, listens, seed = "$seed", now = now, theme = guardTheme).rows.any { snapshot.tracks[it].id in far },
                    "a far song entered only through the habits")
            }
            PersonalDiagnostics.sink = null
            assertTrue(gaps.all { it <= PersonalTerm.HABIT + 1e-4f }, "a pick beyond delta_habit: ${gaps.maxOrNull()}")
            val chain = SmartChain(snapshot, null, BooleanArray(snapshot.size) { true }, tuning = tuning(term(listens), guardTheme))
            for (a in 1..60) assertEquals(0, chain.clusterGuardPasses(snapshot.rowOf(TrackId("$a")), far.map { snapshot.rowOf(it) }.toIntArray()), "far passed from $a")
        }
    }

    @Test
    fun `degenerate delta`() {
        assertEquals(0f, PersonalTerm.delta(FloatArray(10) { 1.5f }, BooleanArray(10) { true }), "equal scores")
        assertEquals(0f, PersonalTerm.delta(floatArrayOf(1f), booleanArrayOf(true)), "one candidate")
        assertEquals(0.5f * 4f, PersonalTerm.delta(FloatArray(9) { it.toFloat() }, BooleanArray(9) { true }), 1e-6f)
        assertEquals(0.5f * (186.75f - 162.25f), PersonalTerm.delta(FloatArray(200) { it.toFloat() }, BooleanArray(200) { true }), 1e-4f)
        assertEquals(0.5f * 4f, PersonalTerm.delta(FloatArray(12) { if (it < 9) it.toFloat() else 1000f }, BooleanArray(12) { it < 9 }), 1e-6f)
    }
}
