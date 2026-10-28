/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.PersonalInputs
import io.github.nikitasud.latentjam.smart.personal.PersonalListen
import io.github.nikitasud.latentjam.smart.personal.PersonalModel
import io.github.nikitasud.latentjam.smart.personal.PersonalStart
import io.github.nikitasud.latentjam.smart.personal.PersonalTrack
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The personal term inside the real chain (PLAN_PERSONAL §7, interaction): P0's margin, D1's relief, the loop guard, the
 * D2 cluster-jumper, no collapse, and the degenerate δ cases. Synthetic library: row 0 the seed, rows 1–60 sound like it
 * at graded closeness, rows 61–119 a far cluster.
 */
internal class PersonalChainTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

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

    private fun inputs(listens: List<PersonalListen>, now: Long, habits: Boolean = true, hearts: Set<TrackId> = emptySet(),
                       playlists: List<Set<TrackId>> = emptyList()) =
        PersonalInputs(PersonalModel.build(listens, library), now, habits, hearts, playlists)

    private fun chain(personal: PersonalTerm?, theme: ThemePool? = null) =
        SmartChain(snapshot, null, tuning = ChainTuning(continueAfterExhaustion = true, personal = personal, theme = theme))

    private fun build(personal: PersonalTerm?, length: Int = 12, seed: String = "0", theme: ThemePool? = null) =
        chain(personal, theme).build(TrackId(seed), length, FloatArray(5))

    /** Hop 1's core scores by row (the chain's trace before any personal term) and its δ. */
    private fun hopOne(seed: String = "0", theme: ThemePool? = null): Pair<Map<Int, Float>, Float> {
        var candidates = FloatArray(0)
        chain(null, theme).build(TrackId(seed), 1, FloatArray(5), trace = { candidates = it.candidates })
        val width = PickTrace.CANDIDATE_WIDTH
        val scores = (0 until candidates.size / width).associate { candidates[it * width].toInt() to candidates[it * width + 1] }
        val values = scores.values.toFloatArray()
        return scores to PersonalTerm.delta(values, BooleanArray(values.size) { true })
    }

    private fun picks(seed: Int, b: Int, sessions: Int, endAt: Long): List<PersonalListen> = buildList {
        for (s in 0 until sessions) {
            val at = endAt - (sessions - 1 - s) * day
            add(PersonalListen(TrackId("$seed"), at, 200_000L, true, false, PersonalStart.PICK))
            add(PersonalListen(TrackId("$b"), at + 200_000L, 200_000L, true, false, PersonalStart.PICK))
        }
    }

    private fun id(row: Int) = snapshot.tracks[row].id.value.toInt()

    @Test
    fun `no history leaves the core exactly as it was`() {
        val plain = build(null)
        val empty = build(PersonalTerm(snapshot, PersonalInputs(PersonalModel.EMPTY, t0, habits = true)))
        assertEquals(plain.rows, empty.rows)
        assertTrue(empty.personalChanged.isEmpty())
        val core = build(PersonalTerm(snapshot, PersonalInputs(PersonalModel.EMPTY, t0, habits = false)))
        assertEquals(plain.rows, core.rows)
    }

    @Test
    fun `P0 a candidate below best minus delta never wins whatever its evidence`() {
        for (theme in listOf(null, ThemePool(floatArrayOf(0f, 0f, 1f, 1f, 1f, 1f, 0f, 0f), slots = 10, bonus = 0f))) {
            val (scores, delta) = hopOne(theme = theme)
            assertTrue(delta > 0f, "delta $delta")
            val best = scores.maxOf { it.value }
            val outside = scores.filter { it.value < best - delta }.keys
            assertTrue(outside.isNotEmpty())
            for (row in outside.shuffled(Random(1)).take(5)) {
                val b = id(row)
                val evidence = picks(0, b, sessions = 20, endAt = t0)
                val term = PersonalTerm(snapshot, inputs(evidence, t0 + day, hearts = setOf(TrackId("$b")), playlists = listOf(setOf(TrackId("0"), TrackId("$b")))))
                val hop1 = build(term, length = 1, theme = theme).rows.single()
                assertTrue(hop1 != row, "row $row (core ${scores[row]} < ${best - delta}) won hop 1")
            }
        }
    }

    @Test
    fun `a close call with real evidence wins and is reported as changed`() {
        val (scores, delta) = hopOne()
        val ranked = scores.entries.sortedByDescending { it.value }
        val second = ranked[1].key
        assertTrue(ranked[0].value - ranked[1].value < delta, "the second is a close call")
        val b = id(second)
        // Picked after the seed in ten sessions, last a month ago (no recency to fight).
        val term = PersonalTerm(snapshot, inputs(picks(0, b, 10, t0), t0 + 30 * day))
        val result = build(term, length = 1)
        assertEquals(second, result.rows.single())
        assertEquals(setOf(second), result.personalChanged)
    }

    @Test
    fun `one event never moves a song past a gap of more than 0_18 delta`() {
        val (scores, delta) = hopOne()
        val best = scores.maxOf { it.value }
        val candidates = scores.filter { best - it.value > 0.2f * delta && best - it.value < delta }.keys
        assertTrue(candidates.isNotEmpty())
        for (row in candidates) {
            val term = PersonalTerm(snapshot, inputs(picks(0, id(row), 1, t0), t0 + 30 * day))
            assertTrue(build(term, length = 1).rows.single() != row, "one pick moved row $row")
        }
    }

    @Test
    fun `D1 a strong habit beats yesterday's recency and a weak one does not`() {
        val (scores, delta) = hopOne()
        val ranked = scores.entries.sortedByDescending { it.value }
        val now = t0 + 20 * 3_600_000L
        // The closest call whose strong habit wins with relief.
        val strongRow = ranked.drop(1).map { it.key }.firstOrNull { row ->
            build(PersonalTerm(snapshot, inputs(picks(0, id(row), 8, t0), now)), length = 1).rows.single() == row
        }
        requireNotNull(strongRow) { "no close call wins with a strong habit (delta $delta)" }
        val noRelief = build(PersonalTerm(snapshot, inputs(picks(0, id(strongRow), 8, t0), now), relief = false), length = 1).rows.single()
        assertTrue(noRelief != strongRow, "without D1 the same evidence loses to yesterday's recency")
        val weak = build(PersonalTerm(snapshot, inputs(picks(0, id(strongRow), 1, t0), now)), length = 1).rows.single()
        assertTrue(weak != strongRow, "a one-off link keeps the normal recency")
    }

    /** One press from [seed] on [day]: the first [accept] picks heard to the end, as SMART served them. */
    private class Listener(val t0: Long) {
        val listens = ArrayList<PersonalListen>()
        fun press(at: Long, seed: String, result: ChainResult, snapshot: SmartSnapshot, accept: Int = 3): Long {
            var clock = at
            listens += PersonalListen(TrackId(seed), clock, 200_000L, true, false, PersonalStart.PICK)
            clock += 200_000L
            for (row in result.rows.take(accept)) {
                listens += PersonalListen(snapshot.tracks[row].id, clock, 200_000L, true, false, PersonalStart.AUTO,
                    smartPlaced = true, personalChanged = row in result.personalChanged)
                clock += 200_000L
            }
            return clock
        }
    }

    @Test
    fun `loop guard 60 days of always-accept plateau and no runaway lead`() {
        val full = Listener(t0)
        val core = Listener(t0)
        val leadsFull = HashMap<Int, Int>()
        val leadsCore = HashMap<Int, Int>()
        val changedRows = HashSet<Int>()
        val weightAt = HashMap<Int, Map<Int, Float>>()
        for (d in 0 until 60) {
            val at = t0 + d * day
            val fm = PersonalModel.build(full.listens, library)
            val cm = PersonalModel.build(core.listens, library)
            val f = build(PersonalTerm(snapshot, PersonalInputs(fm, at, habits = true)))
            val c = build(PersonalTerm(snapshot, PersonalInputs(cm, at, habits = false)))
            leadsFull[f.rows.first()] = (leadsFull[f.rows.first()] ?: 0) + 1
            leadsCore[c.rows.first()] = (leadsCore[c.rows.first()] ?: 0) + 1
            changedRows += f.personalChanged
            full.press(at, "0", f, snapshot)
            core.press(at, "0", c, snapshot)
            if (d == 9 || d == 59) {
                val m = PersonalModel.build(full.listens, library)
                weightAt[d] = (1..119).associateWith { m.countableWeight(m.songOf(TrackId("0")), m.songOf(TrackId("$it"))) }
                println("day ${d + 1}: raw W(A -> *) top ${(1..119).maxOf { m.weight(m.songOf(TrackId("0")), m.songOf(TrackId("$it"))) }}")
            }
        }
        for (row in changedRows) {
            val b = id(row)
            val w10 = weightAt.getValue(9)[b] ?: 0f
            val w60 = weightAt.getValue(59)[b] ?: 0f
            assertTrue(w60 <= 1.1f * w10 + 1e-3f, "row $row grew $w10 -> $w60 after personal moved it")
        }
        val maxFull = leadsFull.maxOf { it.value } / 60f
        val maxCore = leadsCore.maxOf { it.value } / 60f
        println("loop guard: FULL max lead ${maxFull} CORE ${maxCore} changed rows ${changedRows.size}")
        assertTrue(maxFull <= maxOf(0.30f, maxCore), "FULL lead $maxFull vs CORE $maxCore")
    }

    @Test
    fun `D2 a cluster-jumper over 90 days never gets a far habit`() {
        val random = Random(7)
        val listens = ArrayList<PersonalListen>()
        var insertions = 0
        for (d in 0 until 90) {
            val model = PersonalModel.build(listens, library)
            repeat(3) { p ->
                val at = t0 + d * day + p * 3 * 3_600_000L
                val seed = (1 + random.nextInt(20)).toString()
                val result = build(PersonalTerm(snapshot, PersonalInputs(model, at, habits = true)), seed = seed)
                if (result.walk?.farUsed == true) insertions++
                var clock = at
                listens += PersonalListen(TrackId(seed), clock, 200_000L, true, false, PersonalStart.PICK)
                clock += 200_000L
                for (row in result.rows.take(3)) {
                    listens += PersonalListen(snapshot.tracks[row].id, clock, 200_000L, true, false, PersonalStart.AUTO, smartPlaced = true)
                    clock += 200_000L
                }
                if (random.nextDouble() < 0.2) {
                    listens += PersonalListen(far.random(random), clock, 200_000L, true, false, PersonalStart.PICK)
                }
            }
        }
        assertEquals(0, insertions)
    }

    @Test
    fun `D2 a repeated far habit can enter but never in the first three slots`() {
        // Every near song led to far song 61 in eight sessions: whatever the walk passes through, the habit is there.
        val now = t0 + 10 * day
        // Sessions three days apart, each anchor's 40 minutes after the previous one's, so none run into each other.
        val listens = (1..60).flatMap { a -> (0 until 8).flatMap { k -> picks(a, 61, 1, now - day - k * 3 * day - a * 40 * 60_000L) } }
            .sortedBy { it.startedAtMs }
        var inserted = 0
        for (seed in 1..20) {
            val result = build(PersonalTerm(snapshot, inputs(listens, now)), length = 12, seed = "$seed")
            val farSlots = result.rows.withIndex().filter { snapshot.tracks[it.value].id in far }.map { it.index + 1 }
            assertTrue(farSlots.size <= 1, "seed $seed: far habits at $farSlots")
            assertTrue(farSlots.all { it > PersonalTerm.FAR_EARLIEST_SLOT }, "seed $seed: far habit at $farSlots")
            if (farSlots.isNotEmpty()) inserted++
        }
        // The mechanism itself: the far song joins hop pools (it is scored), and loses on the far margin.
        var scoredHops = 0
        var gaps = ArrayList<Float>()
        chain(PersonalTerm(snapshot, inputs(listens, now))).build(TrackId("5"), 12, FloatArray(5), trace = { t ->
            val width = PickTrace.CANDIDATE_WIDTH
            val scores = (0 until t.candidates.size / width).associate { t.candidates[it * width].toInt() to t.candidates[it * width + 1] }
            val farRow = snapshot.rowOf(TrackId("61"))
            scores[farRow]?.let { f ->
                scoredHops++
                val values = scores.values.toFloatArray()
                gaps += (scores.values.max() - f) / PersonalTerm.delta(values, BooleanArray(values.size) { true })
            }
        })
        println("D2 planted: a far habit entered $inserted of 20 presses; from seed 5 it was in $scoredHops hop pools, gaps in delta $gaps")
        assertTrue(scoredHops > 0, "the far habit never joined a pool")
        // From the pressed seed itself it never can: hop 1 is the seed's only hop with it as the anchor.
        val fromSeed = build(PersonalTerm(snapshot, inputs(picks(0, 61, 8, now - day), now)), length = 12)
        assertTrue(fromSeed.rows.none { snapshot.tracks[it].id in far })
    }

    @Test
    fun `no collapse over 100 presses no song above 5 percent`() {
        val random = Random(3)
        val listens = ArrayList<PersonalListen>()
        var clock = t0
        repeat(400) {
            val a = 1 + random.nextInt(60)
            val b = 1 + random.nextInt(60)
            listens += PersonalListen(TrackId("$a"), clock, 200_000L, true, false, PersonalStart.PICK)
            listens += PersonalListen(TrackId("$b"), clock + 200_000L, 200_000L, true, false, PersonalStart.PICK)
            clock += 6 * 3_600_000L
        }
        val model = PersonalModel.build(listens, library)
        val counts = HashMap<Int, Int>()
        var total = 0
        repeat(100) { p ->
            val seed = (1 + p % 30).toString()
            val result = build(PersonalTerm(snapshot, PersonalInputs(model, clock + p * 3_600_000L, habits = true)), seed = seed)
            for (row in result.rows) counts[row] = (counts[row] ?: 0) + 1
            total += result.rows.size
        }
        val top = counts.maxOf { it.value }.toFloat() / total
        val coreCounts = HashMap<Int, Int>()
        repeat(100) { p ->
            val result = build(PersonalTerm(snapshot, PersonalInputs(model, clock + p * 3_600_000L, habits = false)), seed = (1 + p % 30).toString())
            for (row in result.rows) coreCounts[row] = (coreCounts[row] ?: 0) + 1
        }
        val topRow = counts.maxBy { it.value }.key
        assertTrue(top <= maxOf(0.05f, coreCounts.maxOf { it.value }.toFloat() / total), "FULL collapses beyond the core")
        println("no collapse: FULL top share ${top} (row $topRow) CORE top ${coreCounts.maxOf { it.value }.toFloat() / total} (row ${coreCounts.maxBy { it.value }.key}, its FULL ${counts[coreCounts.maxBy { it.value }.key]}) over $total picks")
    }

    @Test
    fun `degenerate delta`() {
        assertEquals(0f, PersonalTerm.delta(FloatArray(10) { 1.5f }, BooleanArray(10) { true }), "equal scores")
        assertEquals(0f, PersonalTerm.delta(floatArrayOf(1f), booleanArrayOf(true)), "one candidate")
        // Fewer than 50: the IQR of all of them.
        val few = FloatArray(9) { it.toFloat() }
        assertEquals(0.5f * 4f, PersonalTerm.delta(few, BooleanArray(9) { true }), 1e-6f)
        // More than 50: only the best 50 count.
        val many = FloatArray(200) { it.toFloat() }
        assertEquals(0.5f * (186.75f - 162.25f), PersonalTerm.delta(many, BooleanArray(200) { true }), 1e-4f)
        // Unscored rows are left out.
        assertEquals(0.5f * 4f, PersonalTerm.delta(FloatArray(12) { if (it < 9) it.toFloat() else 1000f }, BooleanArray(12) { it < 9 }), 1e-6f)
    }

    @Test
    fun `cold start first hop equals the core`() {
        // History of other songs only: no evidence about this seed and its candidates' recency.
        val listens = picks(100, 101, 3, t0)
        val withHistory = build(PersonalTerm(snapshot, inputs(listens, t0 + 200 * day)), length = 1)
        assertEquals(build(null, length = 1).rows, withHistory.rows)
    }
}
