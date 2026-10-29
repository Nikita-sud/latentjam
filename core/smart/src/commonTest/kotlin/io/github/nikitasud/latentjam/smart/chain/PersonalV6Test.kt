/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.SmartHistoryEvent
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.Jar
import io.github.nikitasud.latentjam.smart.personal.PersonalDiagnostics
import io.github.nikitasud.latentjam.smart.personal.PersonalInputs
import io.github.nikitasud.latentjam.smart.personal.PersonalListen
import io.github.nikitasud.latentjam.smart.personal.PersonalModel
import io.github.nikitasud.latentjam.smart.personal.PersonalStart
import io.github.nikitasud.latentjam.smart.personal.PersonalTrack
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The v6 gates (PLAN_PERSONAL v6): reach tiers, the cluster guard, keep-together precedence and the jar. Synthetic
 * library: row 0 the seed, rows 1–60 "Pop" that sound like it at graded closeness, rows 61–119 a far "Metal" cluster.
 */
internal class PersonalV6Test {

    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val t0 = 1_700_000_000_000L
    /** 00:13 UTC: a day's worth of hourly sessions from here stays on one calendar day. */
    private val dawn = t0 + 2 * hour

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
        for (j in 61..119) add(SmartTrack(TrackId("$j"), unit(1 to 1f, (100 + j) to 0.5f), meta = TrackMeta("Far $j", "Artist $j", null, "Metal", 2000)))
    }))
    private val far = (61..119).map { TrackId("$it") }.toSet()
    private val library = snapshot.tracks.map { PersonalTrack(it.id, "song ${it.id.value}", listOf("artist ${it.id.value}"), durationMs = 200_000L) }
    private val guardTheme = ThemePool(floatArrayOf(0.7455f, 0.6732f, 0.6191f, 0.3468f, 0.9851f, 0.1879f, 0.4924f, -2.7054f), slots = 0, bonus = 0f)

    private fun events(listens: List<PersonalListen>, seed: String, now: Long): List<SmartHistoryEvent> =
        listens.map { SmartHistoryEvent(it.trackId, it.startedAtMs, if (it.skipped) 0f else 1f, it.completed, it.skipped) } +
            SmartHistoryEvent(TrackId(seed), now, 1f, true, false)

    private fun term(listens: List<PersonalListen>) = PersonalTerm(snapshot, PersonalInputs(PersonalModel.build(listens, library)))

    private fun chain(personal: PersonalTerm?, theme: ThemePool? = guardTheme, groups: List<Set<TrackId>> = emptyList()) =
        SmartChain(snapshot, null, BooleanArray(snapshot.size) { true }, companionGroups = groups,
            tuning = ChainTuning(continueAfterExhaustion = true, personal = personal?.takeIf { it.active }, theme = theme,
                companionPoints = if (groups.isEmpty()) null else Rerank.COMPANION_POINTS))

    private fun build(personal: PersonalTerm?, listens: List<PersonalListen>, seed: String = "0", length: Int = 12, now: Long,
                      theme: ThemePool? = guardTheme, groups: List<Set<TrackId>> = emptyList()): ChainResult =
        chain(personal, theme, groups).build(TrackId(seed), length, FloatArray(5), historyEvents = events(listens, seed, now))

    private fun pick(a: Int, b: Int, at: Long) = listOf(
        PersonalListen(TrackId("$a"), at, 200_000L, true, false, PersonalStart.PICK),
        PersonalListen(TrackId("$b"), at + 200_000L, 200_000L, true, false, PersonalStart.PICK),
    )

    /** A hard skip (5 s) of [b] offered right after [a]. */
    private fun skip(a: Int, b: Int, at: Long) = listOf(
        PersonalListen(TrackId("$a"), at, 200_000L, true, false, PersonalStart.PICK),
        PersonalListen(TrackId("$b"), at + 200_000L, 5_000L, false, true, PersonalStart.AUTO, smartSourced = true),
    )

    /** Background listening, so lift has a base rate to beat: random pairs among rows 1–60, three sessions a day. */
    private fun background(days: Int, end: Long, random: Random = Random(5)): List<PersonalListen> = buildList {
        for (d in 0 until days) for (s in 0 until 3) {
            val at = end - (days - d) * day + s * 4 * hour + 4_380_000L
            add(PersonalListen(TrackId("${1 + random.nextInt(60)}"), at, 200_000L, true, false, PersonalStart.PICK))
            add(PersonalListen(TrackId("${1 + random.nextInt(60)}"), at + 200_000L, 200_000L, true, false, PersonalStart.PICK))
        }
    }

    /** Hop 1's δ, the mask-core gaps (in δ) and the additions. */
    private fun hopOne(listens: List<PersonalListen>, now: Long, theme: ThemePool? = guardTheme): Triple<Float, Map<TrackId, Float>, Map<TrackId, Float>> {
        var seen: Triple<Float, Map<TrackId, Float>, Map<TrackId, Float>>? = null
        PersonalDiagnostics.hop = { d, gaps, adds -> if (seen == null) seen = Triple(d, gaps, adds) }
        build(term(listens), listens, length = 1, now = now, theme = theme)
        PersonalDiagnostics.hop = null
        return requireNotNull(seen) { "the personal stage did not run" }
    }

    private fun model(listens: List<PersonalListen>) = PersonalModel.build(listens.sortedBy { it.startedAtMs }, library)

    private fun PersonalModel.jarOf(a: Int, b: Int) = jar(songOf(TrackId("$a")), songOf(TrackId("$b")))

    /** [n] pick sessions of 0 → 1 an hour apart from [start] (one day each with [every] = day). */
    private fun saturate(n: Int, start: Long, every: Long = hour): List<PersonalListen> = (0 until n).flatMap { pick(0, 1, start + it * every) }

    // ---- The jar ----

    @Test
    fun `jar a saturated habit walks 6 4 3 2 0 and floors at -1_8 and one skip changes nothing`() {
        val start = dawn
        val base = saturate(8, start)
        assertEquals(6f, model(base).jarOf(0, 1), 1e-3f)
        val expected = mapOf(1 to 6f, 2 to 4f, 3 to 3f, 4 to 2f, 6 to 0f, 8 to -1.8f)
        for ((skips, b) in expected) {
            val listens = base + (0 until skips).flatMap { skip(0, 1, start + (10 + it) * hour) }
            val m = model(listens)
            // An hour apart: decay is 2^(−1 h / 120 d), well inside the tolerance.
            assertEquals(b, m.jarOf(0, 1), 0.02f, "after $skips skips")
            // Tiers count positives only: skips never demote.
            assertEquals(1, m.tier(m.songOf(TrackId("0")), m.songOf(TrackId("1"))), "one day: tier 1 after $skips skips")
        }
    }

    @Test
    fun `jar 4 skips then 4 picks is back to at least 0_85`() {
        val listens = saturate(8, dawn) + (0 until 4).flatMap { skip(0, 1, dawn + (10 + it) * hour) } +
            (0 until 4).flatMap { pick(0, 1, dawn + (19 + it) * hour) }
        val b = model(listens).jarOf(0, 1)
        println("v6: 4 skips then 4 picks b/6 = ${b / 6f}")
        assertTrue(b / 6f >= 0.85f, "b/6 ${b / 6f}")
    }

    @Test
    fun `jar a pick the personal term made adds nothing and its skip counts in full`() {
        val own = saturate(2, t0)
        val changed = listOf(
            PersonalListen(TrackId("0"), t0 + 5 * hour, 200_000L, true, false, PersonalStart.PICK),
            PersonalListen(TrackId("1"), t0 + 5 * hour + 200_000L, 200_000L, true, false, PersonalStart.AUTO, smartSourced = true, personalChanged = true),
        )
        // Only the reference moves (5 hours of decay) and half precision rounds.
        assertEquals(model(own).jarOf(0, 1), model(own + changed).jarOf(0, 1), 0.005f, "an accepted personalChanged pick adds 0")
        val skipped = (0 until 2).flatMap { k ->
            listOf(
                PersonalListen(TrackId("0"), t0 + (6 + k) * hour, 200_000L, true, false, PersonalStart.PICK),
                PersonalListen(TrackId("1"), t0 + (6 + k) * hour + 200_000L, 5_000L, false, true, PersonalStart.AUTO, smartSourced = true, personalChanged = true),
            )
        }
        assertEquals(-0.3f, model(own + skipped).jarOf(0, 1), 0.01f, "1.7 − 1 − 1")
    }

    @Test
    fun `jar SMART positives are capped causally at a quarter of the own so far`() {
        // SMART first: nothing own yet, so nothing counts; then two own picks (0.85 each, no pick surface); then SMART's
        // 0.125 acceptances add at most 0.25 · 1.7 between them.
        val smart = { at: Long ->
            listOf(
                PersonalListen(TrackId("0"), at, 200_000L, true, false, PersonalStart.PICK),
                PersonalListen(TrackId("1"), at + 200_000L, 200_000L, true, false, PersonalStart.AUTO, smartSourced = true),
            )
        }
        val listens = smart(t0) + saturate(2, t0 + 2 * hour) + (0 until 6).flatMap { smart(t0 + (5 + it) * hour) }
        assertEquals(1.7f + 0.425f, model(listens).jarOf(0, 1), 0.01f)
    }

    @Test
    fun `jar the fold over decayed values is the natural fold at 1e-9`() {
        val random = Random(20261029)
        val half = PersonalModel.HALF_LIFE_MS.toDouble()
        repeat(200) {
            val ref = t0 + 400 * day
            val times = (0 until 1 + random.nextInt(25)).map { ref - random.nextLong(300 * day) }.sorted()
            val kinds = times.map { random.nextInt(3) }
            val grades = kinds.map { k -> if (k == Jar.REJECT) listOf(1.0, 0.5)[random.nextInt(2)] else listOf(1.0, 0.85, 0.7, 0.35, 0.25)[random.nextInt(5)] }
            val steps = times.indices.map { Jar.Step(times[it], grades[it] * decay120(times[it], ref), kinds[it]) }
            // The natural form: b ← clamp(b · 2^(−Δt / 120 d) ± g), then decayed to the reference.
            var b = 0.0
            var own = 0.0
            var smart = 0.0
            var rejections = 0
            var held = 0.0
            var last = times.first()
            for (i in times.indices) {
                val d = 2.0.pow(-(times[i] - last) / half)
                b *= d; own *= d; smart *= d; held *= d
                last = times[i]
                when (kinds[i]) {
                    Jar.OWN -> { own += grades[i]; b += grades[i] }
                    Jar.SMART -> { val add = minOf(grades[i], maxOf(0.0, 0.25 * own - smart)); smart += add; b += add }
                    else -> {
                        rejections++
                        if (rejections < 2) { held += grades[i]; continue }
                        b -= held + grades[i]; held = 0.0
                    }
                }
                b = b.coerceIn(-1.8, 6.0)
            }
            b *= 2.0.pow(-(ref - last) / half)
            assertEquals(b, Jar.fold(steps) { decay120(it, ref) }, 1e-9)
        }
    }

    @Test
    fun `jar golden pinned on the JVM`() {
        val ref = t0 + 400 * day
        val random = Random(7)
        val got = DoubleArray(20) {
            val steps = (0 until 12).map {
                val at = ref - random.nextLong(200 * day)
                val kind = random.nextInt(3)
                Jar.Step(at, (if (kind == Jar.REJECT) 1.0 else 0.85) * decay120(at, ref), kind)
            }
            Jar.fold(steps) { decay120(it, ref) }
        }
        if (GOLDEN_JAR.isEmpty()) {
            println("GOLDEN_JAR = doubleArrayOf(" + got.joinToString(", ") + ")")
            return
        }
        for (i in got.indices) assertEquals(GOLDEN_JAR[i], got[i], 1e-9, "value $i")
    }

    // ---- The term ----

    /** Song 1 after 0 with lift: background pairs among 2–60 over 30 days, then [planted] (sorted). */
    private fun withBackground(planted: List<PersonalListen>, end: Long): List<PersonalListen> =
        (background(30, end - 40 * day) + planted).sortedBy { it.startedAtMs }

    private fun addition(listens: List<PersonalListen>, guard: Boolean = true, recencyLoss: Float = 1f): Float =
        term(listens).addition(0, 1, 1f, recencyLoss, readmitted = false, inMask = true) { guard }

    @Test
    fun `N 100 then 6 hard skips leaves t at most 0 and no relief`() {
        val end = t0 + 400 * day
        val strong = (0 until 100).flatMap { pick(0, 1, end - 200 * day + it * day) }
        val before = withBackground(strong, end)
        val m = model(before)
        assertTrue(m.strong(m.songOf(TrackId("0")), m.songOf(TrackId("1"))), "D1-strong before the skips")
        assertTrue(addition(before) > 1f, "tier 2 with relief: ${addition(before)}")
        val after = withBackground(strong + (0 until 6).flatMap { skip(0, 1, end - 50 * day + it * day) }, end)
        val t = addition(after, recencyLoss = 0f)
        val withRecency = addition(after, recencyLoss = 1f)
        println("v6: N=100 then 6 hard skips t = $t, with R = 1 the same: $withRecency, jar ${model(after).jarOf(0, 1)}")
        assertTrue(t <= 0f, "t $t")
        assertEquals(t, withRecency, 1e-6f, "relief 0")
    }

    @Test
    fun `D1 an imported habit then 6 hard device skips leaves t at most 0 and no relief`() {
        val end = t0 + 400 * day
        // A saturated imported habit: fifteen imported runs A → B on fifteen days.
        val imported = (0 until 15).flatMap { k ->
            listOf(
                PersonalListen(TrackId("0"), end - 200 * day + k * day, null, true, false, PersonalStart.IMPORTED),
                PersonalListen(TrackId("1"), end - 200 * day + k * day + 200_000L, null, true, false, PersonalStart.IMPORTED),
            )
        }
        val before = withBackground(imported, end)
        assertTrue(addition(before) > 1f, "tier 2 before the skips: ${addition(before)}")
        val after = withBackground(imported + (0 until 6).flatMap { skip(0, 1, end - 50 * day + it * day) }, end)
        val t = addition(after, recencyLoss = 0f)
        println("v6: imported habit then 6 hard skips t = $t, jar ${model(after).jarOf(0, 1)}")
        assertTrue(t <= 0f, "t $t")
        assertEquals(t, addition(after, recencyLoss = 1f), 1e-6f, "relief 0")
    }

    @Test
    fun `one skip leaves the habit term unchanged and E7 alone moves t`() {
        val end = t0 + 400 * day
        // A later unrelated listen is the device's newest event in both, so both decay to the same reference.
        val habit = (0 until 4).flatMap { pick(0, 1, end - 30 * day + it * day) } +
            PersonalListen(TrackId("59"), end - 5 * day, 200_000L, true, false, PersonalStart.PICK)
        val before = withBackground(habit, end)
        val after = withBackground(habit + skip(0, 1, end - 10 * day), end)
        val mb = model(before)
        val ma = model(after)
        assertEquals(mb.jarOf(0, 1), ma.jarOf(0, 1), 1e-6f, "b unchanged")
        val e7 = ma.skipPenalty(ma.songOf(TrackId("1")))
        assertEquals(addition(before, guard = false) - e7, addition(after, guard = false), 1e-5f, "only E7 (${e7})")
        assertEquals(addition(before) - e7, addition(after), 1e-5f, "tier 2: only E7")
    }

    @Test
    fun `no t jump at decay and the tier-2 step is reported`() {
        val end = t0 + 400 * day
        val habit = (0 until 6).flatMap { pick(0, 1, end - 300 * day + it * day) }
        var previous = Float.NaN
        var worst = 0f
        // One unrelated listen a day moves the device's newest event, so the habit decays day by day.
        val daily = ArrayList<PersonalListen>()
        for (d in 0 until 300) {
            daily += PersonalListen(TrackId("${2 + d % 50}"), end - 290 * day + d * day, 200_000L, true, false, PersonalStart.PICK)
            val t = term((habit + daily).sortedBy { it.startedAtMs }).addition(0, 1, 1f, 0f, false, inMask = true) { true }
            if (!previous.isNaN()) worst = maxOf(worst, abs(t - previous))
            previous = t
        }
        println("v6: largest day-to-day change of t under decay ${worst} delta_weak (t at 300 days $previous)")
        assertTrue(worst <= 0.02f, "a jump of $worst")
        // The step at the third own session (second day): δ_weak · b / 6 → δ_habit · b / 6, by design; its size is reported.
        val two = (0 until 2).flatMap { pick(0, 1, end - 10 * day + it * hour) }
        val three = two + pick(0, 1, end - 9 * day)
        val t2 = term(two).addition(0, 1, 1f, 0f, false, inMask = true) { true }
        val t3 = term(three).addition(0, 1, 1f, 0f, false, inMask = true) { true }
        println("v6: tier step t = $t2 at 2 sessions (one day) -> $t3 at 3 sessions on 2 days (delta_weak units)")
        assertTrue(t3 >= t2)
    }

    // ---- The chain ----

    @Test
    fun `C no history is the shipped chain bit for bit with the guard theme`() {
        for (seed in listOf("0", "5", "30", "70")) {
            val now = t0 + 400 * day
            val shipped = chain(null).build(TrackId(seed), 12, FloatArray(5))
            val full = chain(PersonalTerm(snapshot, PersonalInputs(PersonalModel.EMPTY))).build(TrackId(seed), 12, FloatArray(5))
            assertEquals(shipped, full)
            assertEquals(build(null, emptyList(), seed, now = now), build(term(emptyList()), emptyList(), seed, now = now))
        }
    }

    @Test
    fun `cluster guard a cross-cluster pair fails and never reaches beyond M`() {
        val now = t0 + 400 * day
        // Tier-2 habits from the seed to every far song: eight sessions on eight days.
        val listens = (61..119).flatMap { b -> (0 until 8).flatMap { k -> pick(0, b, now - 30 * day - k * day - (b - 60) * 600_000L) } }
            .sortedBy { it.startedAtMs }
        for (theme in listOf(null, guardTheme)) {
            val c = chain(term(listens), theme)
            assertEquals(0, c.clusterGuardPasses(0, IntArray(59) { 61 + it }), "a far song passed the guard (theme ${theme != null})")
            val (_, gaps, adds) = hopOne(listens, now, theme)
            for ((id, gap) in gaps) if (gap > 1f + 1e-4f) assertEquals(0f, adds.getValue(id), "row $id outside M was lifted")
            for (seed in listOf("0", "3", "20")) {
                val gapsChanged = ArrayList<Float>()
                PersonalDiagnostics.sink = { gap, changed -> if (changed) gapsChanged += gap }
                val full = build(term(listens), listens, seed, now = now, theme = theme)
                PersonalDiagnostics.sink = null
                assertTrue(gapsChanged.all { it <= 1f + 1e-4f }, "a pick beyond delta: ${gapsChanged.maxOrNull()}")
                val core = build(null, listens, seed, now = now, theme = theme)
                assertTrue(full.rows.none { snapshot.tracks[it].id in far } || core.rows.any { snapshot.tracks[it].id in far })
            }
        }
    }

    @Test
    fun `cluster guard rules`() {
        assertTrue(PersonalTerm.clusterGuard(setOf("rock", "pop"), setOf("rock"), 0f), "primary in B")
        assertFalse(PersonalTerm.clusterGuard(setOf("pop", "rock"), setOf("rock", "metal", "punk"), 2f), "primary out, Jaccard 1/4")
        assertFalse(PersonalTerm.clusterGuard(setOf("pop", "rock"), setOf("rock", "folk"), 0.5f), "primary out, Jaccard 1/3")
        assertTrue(PersonalTerm.clusterGuard(setOf("pop", "rock"), setOf("rock", "pop", "folk"), 0f), "primary in")
        assertTrue(PersonalTerm.clusterGuard(setOf("pop", "rock"), setOf("folk", "rock"), 0f).not() &&
            PersonalTerm.clusterGuard(setOf("folk", "rock"), setOf("rock", "folk", "jazz"), 0f), "Jaccard 2/3 passes")
        assertFalse(PersonalTerm.clusterGuard(setOf("rock"), setOf("rock"), -0.01f), "theme z below 0")
        assertFalse(PersonalTerm.clusterGuard(emptySet(), setOf("rock"), 0.99f), "no families: z ≥ 1")
        assertTrue(PersonalTerm.clusterGuard(emptySet(), emptySet(), 1f), "no families: z ≥ 1")
        assertFalse(PersonalTerm.clusterGuard(emptySet(), setOf("rock"), null), "no families, no theme")
        assertTrue(PersonalTerm.clusterGuard(setOf("rock"), setOf("rock"), null), "no theme: families alone")
    }

    /** Hop 1's near candidates with mask-core gaps in [range] (in δ), closest first, from a history of background only. */
    private fun candidates(range: ClosedFloatingPointRange<Float>, now: Long): List<TrackId> {
        val back = background(30, now - 40 * day)
        val (_, gaps, _) = hopOne(back, now)
        return gaps.entries.filter { it.value in range && it.key !in far }.sortedWith(compareBy({ it.value }, { it.key.value })).map { it.key }
    }

    @Test
    fun `YSR to UNION pick 1 at 4 own sessions over 2 days and not at 2 on one morning`() {
        val now = t0 + 400 * day
        val bs = candidates(1.1f..1.7f, now)
        assertTrue(bs.isNotEmpty())
        val back = background(30, now - 40 * day)
        var checked = 0
        for (b in bs.take(5)) {
            val row = b.value.toInt()
            val morning = (0 until 2).map { pick(0, row, now - 10 * day + it * hour) }.flatten()
            val twoDays = morning + (0 until 2).map { pick(0, row, now - 9 * day + it * hour) }.flatten()
            val first = { planted: List<PersonalListen> ->
                val listens = (back + planted).sortedBy { it.startedAtMs }
                snapshot.tracks[build(term(listens), listens, length = 1, now = now).rows.single()].id
            }
            assertTrue(first(morning) != b, "$b won with 2 sessions on one morning")
            assertEquals(b, first(twoDays), "$b at 4 sessions over 2 days")
            checked++
        }
        println("v6: YSR-style pick 1 checked on $checked candidates 1.1–1.7 delta behind")
    }

    @Test
    fun `competing tier-2 habits the stronger leads and when both are full the higher core wins`() {
        val now = t0 + 400 * day
        val back = background(30, now - 200 * day)
        val bs = candidates(0.05f..0.9f, now)
        assertTrue(bs.size >= 2)
        // The closer one (higher core) gets the weaker habit: three own sessions; the further one six.
        val close = bs.first()
        val farther = bs.last()
        fun sessions(b: TrackId, n: Int, from: Long) = (0 until n).flatMap { pick(0, b.value.toInt(), from + it * day) }
        fun first(planted: List<PersonalListen>): TrackId {
            val listens = (back + planted).sortedBy { it.startedAtMs }
            return snapshot.tracks[build(term(listens), listens, length = 1, now = now).rows.single()].id
        }
        val weakClose = sessions(close, 3, now - 120 * day) + sessions(farther, 6, now - 110 * day)
        val m = model(back + weakClose)
        println("v6: competing habits jar close ${m.jarOf(0, close.value.toInt())} vs farther ${m.jarOf(0, farther.value.toInt())}")
        assertEquals(farther, first(weakClose), "the stronger habit leads")
        // Both saturated (eight sessions each): equally full, so the core decides.
        val bothFull = sessions(close, 8, now - 120 * day) + sessions(farther, 8, now - 110 * day)
        assertEquals(close, first(bothFull), "both full: the higher core wins")
    }

    @Test
    fun `keep-together is never outvoted`() {
        val now = t0 + 400 * day
        val back = background(30, now - 40 * day)
        // A marked group puts a keep-together row (K > 0) first at hop 1; then each candidate B up to δ_habit behind gets
        // a saturated tier-2 habit from the seed (eight own sessions on eight days) that would otherwise win.
        val bs = candidates(0.05f..2.9f, now)
        var checked = 0
        var members = 0
        var wouldWin = 0
        for (member in bs) {
            if (members == 3) break
            val marked = listOf(setOf(TrackId("0"), member))
            val shipped = build(null, back, length = 1, now = now, groups = marked).rows.single()
            if (snapshot.tracks[shipped].id != member) continue
            members++
            for (b in bs) {
                if (b == member) continue
                val listens = (back + (0 until 8).flatMap { pick(0, b.value.toInt(), now - 30 * day + it * day) }).sortedBy { it.startedAtMs }
                val habitsOnly = build(term(listens), listens, length = 1, now = now).rows.single()
                val full = build(term(listens), listens, length = 1, now = now, groups = marked).rows.single()
                if (habitsOnly == snapshot.rowOf(b)) wouldWin++
                assertEquals(shipped, full, "the habit to $b outvoted keep-together's $member (without the group it picks ${snapshot.tracks[habitsOnly].id})")
                checked++
            }
        }
        println("v6: keep-together hops checked $checked over $members marked members; the habit wins without the group in $wouldWin")
        assertTrue(wouldWin > 0)
        assertTrue(checked > 0)
    }

    @Test
    fun `after 4 rejections B's pick-1 share is at most max of 30 percent and CORE`() {
        val now = t0 + 400 * day
        val back = background(30, now - 200 * day)
        val bs = candidates(0.05f..2.9f, now)
        val shares = ArrayList<Float>()
        for (b in bs.take(12)) {
            val row = b.value.toInt()
            // A saturated tier-2 habit, eight own sessions on eight days, long ago enough for recency to be gone.
            val listens = (back + (0 until 8).flatMap { pick(0, row, now - 150 * day + it * day) }).toMutableList()
            var rejections = 0
            var full = 0
            var core = 0
            var presses = 0
            for (d in 0 until 60) {
                val at = now - 100 * day + d * day
                val sorted = listens.sortedBy { it.startedAtMs }
                val result = build(term(sorted), sorted, length = 1, now = at)
                val first = result.rows.single()
                val served = snapshot.tracks[first].id == b
                if (rejections >= 4) {
                    presses++
                    if (served) full++
                    if (snapshot.tracks[build(null, sorted, length = 1, now = at).rows.single()].id == b) core++
                }
                // The listener presses the seed and skips B whenever it comes first; otherwise hears the first pick.
                listens += PersonalListen(TrackId("0"), at, 200_000L, true, false, PersonalStart.PICK)
                if (served) {
                    listens += PersonalListen(b, at + 200_000L, 5_000L, false, true, PersonalStart.AUTO, smartSourced = true,
                        personalChanged = first in result.personalChanged)
                    rejections++
                } else {
                    listens += PersonalListen(snapshot.tracks[first].id, at + 200_000L, 200_000L, true, false, PersonalStart.AUTO,
                        smartSourced = true, personalChanged = first in result.personalChanged)
                }
                if (rejections < 4 && d == 59) presses = 0
            }
            if (presses == 0) continue
            val share = full.toFloat() / presses
            shares += share
            assertTrue(share <= maxOf(0.3f, core.toFloat() / presses), "$b: pick 1 in $full / $presses presses after 4 rejections, CORE $core")
        }
        println("v6: after 4 rejections B's pick-1 shares $shares over ${shares.size} B reaching 4 rejections")
    }

    /** A fixed 120-day calendar decay: the fold is the same whatever clock supplies its factors. */
    private fun decay120(at: Long, ref: Long): Double = 0.5.pow((ref - at).coerceAtLeast(0L).toDouble() / (120.0 * day))

    private companion object {
        val GOLDEN_JAR: DoubleArray = doubleArrayOf(
            1.0876130559674673, -0.03228220789354208, 0.09694320192894446, 1.0057575107131649, -1.6216513476802974,
            1.1502126396256962, 0.5799878869729357, -1.3315343571597489, 0.034150783348119274, -0.5185723854547576,
            2.5083118084414973, -1.4921476176724675, -1.758290869777107, 0.020174099170831128, -0.7375609556425172,
            0.5169443298633153, 3.005422894614239, 2.305988978094338, 1.253477937876755, 1.6859701418913646,
        )
    }
}
