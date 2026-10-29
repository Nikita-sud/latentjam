/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultListeningHistory
import io.github.nikitasud.latentjam.history.HistoryStore
import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.PickSurface
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.personal.PersonalDiagnostics
import java.io.File
import kotlin.random.Random

/**
 * PLAN_PERSONAL v2 §5, synthetic listeners on an FMA library (LJ_STAND_PERSONAL_SIM=1 through SmartStandRunner). Each
 * listener lives through its days with the app's chooser ("Learn from my habits" on), then FULL and CORE plan the same
 * probe presses on the history it made. Writes `<out>/personal_sim.json` and returns its lines.
 */
internal class PersonalListenerSim(
    private val engine: SimilarityEngine,
    library0: List<TrackDescriptor>,
    private val seeds: List<TrackDescriptor>,
) {
    /** FMA tags carry no track numbers: an album's tracks are numbered in library order, so album order is defined. */
    private val library: List<TrackDescriptor> = run {
        val next = HashMap<String, Int>()
        library0.map { t ->
            val album = t.album?.takeIf(String::isNotBlank) ?: return@map t
            t.copy(trackNumber = next.merge(album, 1, Int::plus))
        }
    }
    private val day = 86_400_000L
    private val start = 1_760_000_000_000L

    private class Run(val history: DefaultListeningHistory, var now: Long)

    private fun history(): DefaultListeningHistory {
        val lines = ArrayList<String>()
        return DefaultListeningHistory(object : HistoryStore {
            override suspend fun append(line: String) { lines += line }
            override suspend fun readAll(): List<String> = lines
            override suspend fun clear() = lines.clear()
        })
    }

    private fun chooser(run: Run, full: Boolean, artistTerm: Boolean = true) = EngineNextTrackChooser(
        engine = engine, history = run.history, library = { library }, learnFromHabits = { full },
        personalModel = PersonalModelCache(run.history, engine::artistKeysOf, clock = { run.now }).let { cache -> { lib: List<TrackDescriptor> -> cache.model(lib) } },
        personalArtistTerm = artistTerm,
        clock = { run.now },
    )

    /** One press, afresh ([SimilarityEngine.forgetWalks]): [hops] rows planned and topped up as the controllers do. */
    private suspend fun press(chooser: EngineNextTrackChooser, seed: TrackDescriptor, hops: Int = 12): List<Pair<TrackDescriptor, Int?>> {
        engine.forgetWalks()
        val queue = mutableListOf(seed)
        val out = ArrayList<Pair<TrackDescriptor, Int?>>()
        repeat(hops) {
            val queued = queue.mapTo(HashSet()) { it.id }
            val choice = chooser.choose(queue.last(), queue.dropLast(1).takeLast(10).map { it.id }, library.filter { it.id !in queued })
                ?: return out
            queue += choice.track
            out += choice.track to choice.planPosition
        }
        return out
    }

    private suspend fun play(run: Run, track: TrackDescriptor, start: ListenStart, plan: Int? = null, shuffle: String = "SMART",
                             parent: String? = null, surface: PickSurface? = null, skippedAfterMs: Long? = null) {
        val heard = skippedAfterMs ?: (track.durationMs ?: 180_000L).coerceAtMost(600_000L)
        val event = ListenEvent(track.id, run.now, heard, track.durationMs, completed = skippedAfterMs == null, skipped = skippedAfterMs != null,
            shuffleMode = shuffle, listenedMs = heard, origin = ListenOrigin(start, plan, parent, surface))
        run.history.record(PersonalSignals.annotate(event))
        run.now += heard + 2_000L
    }

    /** What the probes count over a listener's population of FULL hops. */
    private class Tally {
        var hops = 0
        var changed = 0
        var nonZero = 0
        var stageHops = 0
        var maxChangedGap = 0f
        var changedBeyondM = 0
        var guardPassed = 0L
        var guardTotal = 0L
        var edits = 0
        var probes = 0
        val counts = HashMap<TrackId, Int>()
        val coreCounts = HashMap<TrackId, Int>()
        var picks = 0
    }

    /** FULL vs CORE on the same history: the counterfactual personalChanged rate, changes within δ, edit distance, collapse. */
    private suspend fun probes(run: Run, tally: Tally) {
        for (seed in seeds.take(20)) {
            PersonalDiagnostics.sink = { gap, changed ->
                tally.stageHops++
                if (changed) {
                    tally.changed++
                    if (gap > tally.maxChangedGap) tally.maxChangedGap = gap
                    if (gap > 1f + 1e-4f) tally.changedBeyondM++
                }
            }
            PersonalDiagnostics.hop = { _, _, adds -> if (adds.values.any { it != 0f }) tally.nonZero++ }
            PersonalDiagnostics.guard = { passed, total -> tally.guardPassed += passed; tally.guardTotal += total }
            val full = press(chooser(run, true), seed).map { it.first.id }
            PersonalDiagnostics.sink = null
            PersonalDiagnostics.hop = null
            PersonalDiagnostics.guard = null
            val core = press(chooser(run, false), seed).map { it.first.id }
            tally.hops += full.size
            tally.edits += editDistance(full, core)
            tally.probes++
        }
        for (p in 0 until 100) {
            val seed = library[Random(p).nextInt(library.size)]
            for ((t, _) in press(chooser(run, true), seed)) { tally.counts.merge(t.id, 1, Int::plus); tally.picks++ }
            for ((t, _) in press(chooser(run, false), seed)) tally.coreCounts.merge(t.id, 1, Int::plus)
        }
    }

    private fun editDistance(a: List<TrackId>, b: List<TrackId>): Int {
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.size][b.size]
    }

    private fun summary(name: String, t: Tally): Map<String, Any> {
        val worst = t.counts.entries.maxOfOrNull { (id, n) -> n.toDouble() / t.picks - (t.coreCounts[id] ?: 0).toDouble() / t.picks } ?: 0.0
        val collapse = t.counts.entries.count { (id, n) -> n.toDouble() / t.picks > maxOf(0.05, (t.coreCounts[id] ?: 0).toDouble() / t.picks + 0.02) }
        return linkedMapOf(
            "listener" to name,
            "hops" to t.hops,
            "changed" to t.changed,
            "changedRate" to t.changed.toDouble() / t.hops.coerceAtLeast(1),
            "nonZeroShare" to t.nonZero.toDouble() / t.stageHops.coerceAtLeast(1),
            "maxChangedGapOverDelta" to t.maxChangedGap,
            "changedBeyondM" to t.changedBeyondM,
            "guardPassRate" to t.guardPassed.toDouble() / t.guardTotal.coerceAtLeast(1),
            "meanEditDistance" to t.edits.toDouble() / t.probes.coerceAtLeast(1),
            "collapseViolations" to collapse,
            "worstShareOverCore" to worst,
        )
    }

    suspend fun runAll(out: File, random: Random = Random(2026)): List<String> {
        val results = ArrayList<Map<String, Any>>()
        val parts = System.getenv("LJ_SIM_PARTS")?.split(',')?.toSet()
        fun wanted(name: String) = parts == null || name in parts

        // C: no history and no priors, FULL and CORE identical.
        if (wanted("no-history")) {
            val r = Run(history(), start)
            var identical = 0
            for (seed in seeds.take(20)) {
                if (press(chooser(r, true), seed).map { it.first.id } == press(chooser(r, false), seed).map { it.first.id }) identical++
            }
            results += linkedMapOf("listener" to "no-history", "identical" to identical, "of" to seeds.take(20).size)
        }

        // Cluster-jumper: three presses a day for 90 days; after each, with p = 0.2, a random song anywhere.
        if (wanted("cluster-jumper")) {
            val r = Run(history(), start)
            val c = chooser(r, true)
            for (d in 0 until 90) {
                r.now = start + d * day
                repeat(3) {
                    val seed = library[random.nextInt(library.size)]
                    play(r, seed, ListenStart.USER_PICK, surface = PickSurface.COLLECTION)
                    for ((t, plan) in press(c, seed).take(5)) play(r, t, ListenStart.AUTO_ADVANCE, plan)
                    if (random.nextDouble() < 0.2) play(r, library[random.nextInt(library.size)], ListenStart.USER_PICK, surface = PickSurface.SEARCH)
                    r.now += 3 * 3_600_000L
                }
            }
            results += summary("cluster-jumper", Tally().also { probes(r, it) })
        }

        // Sleeper: a press a night and four hours of autoplay (sixty songs), thirty nights.
        if (wanted("sleeper")) {
            val r = Run(history(), start)
            val c = chooser(r, true)
            for (d in 0 until 30) {
                r.now = start + d * day
                val seed = library[random.nextInt(library.size)]
                play(r, seed, ListenStart.USER_PICK)
                for ((t, plan) in press(c, seed, hops = 60)) play(r, t, ListenStart.AUTO_ADVANCE, plan)
            }
            results += summary("sleeper", Tally().also { probes(r, it) })
        }

        // Album listener: a whole album in order every day, shuffle off, thirty days.
        if (wanted("album-listener")) {
            val r = Run(history(), start)
            val albums = library.filter { !it.album.isNullOrBlank() }.groupBy { it.album!! }.filterValues { it.size >= 4 }.values.toList()
            for (d in 0 until 30) {
                r.now = start + d * day
                val album = albums[random.nextInt(albums.size)].sortedBy { it.trackNumber }
                album.forEachIndexed { i, t ->
                    play(r, t, if (i == 0) ListenStart.USER_PICK else ListenStart.AUTO_ADVANCE, shuffle = "OFF", parent = "album:${t.album}")
                }
            }
            results += summary("album-listener", Tally().also { probes(r, it) })
        }

        // Shuffle user: twenty random songs a day with shuffle on, an early skip now and then, thirty days.
        if (wanted("shuffle-user")) {
            val r = Run(history(), start)
            for (d in 0 until 30) {
                r.now = start + d * day
                repeat(20) { i ->
                    play(r, library[random.nextInt(library.size)], if (i == 0) ListenStart.USER_PICK else ListenStart.AUTO_ADVANCE,
                        shuffle = "ON", skippedAfterMs = if (random.nextInt(5) == 0) 6_000L else null)
                }
            }
            results += summary("shuffle-user", Tally().also { probes(r, it) })
        }

        // A→B lover (reported apart): B picked right after A daily for twenty days; ten presses from A after.
        // (a) artist term off; (b) everything on. B ∈ M ⇒ hop 1 in ≥ 90 %; B ∉ M ⇒ 0 %.
        if (wanted("a-to-b-lover")) {
            val pairs = ArrayList<String>()
            for ((k, a) in seeds.take(6).withIndex()) {
                // B from A's mask at hop 1, with a neutral history so the personal stage runs: three inside M, three outside.
                val probe = Run(history(), start)
                play(probe, library[(k * 37 + 11) % library.size], ListenStart.USER_PICK)
                var gaps: Map<TrackId, Float> = emptyMap()
                PersonalDiagnostics.hop = { _, g, _ -> if (gaps.isEmpty()) gaps = g }
                probe.now = start + 2 * day
                press(chooser(probe, true), a, hops = 1)
                PersonalDiagnostics.hop = null
                val inside = k < 3
                val b = gaps.entries.filter { (id, g) -> id != a.id && if (inside) g in 0.05f..0.9f else g in 1.2f..3f }
                    .minByOrNull { it.value }?.key?.let { id -> library.first { it.id == id } }
                if (b == null) {
                    pairs += "${a.id.value}: no B ${if (inside) "inside" else "outside"} M"
                    continue
                }
                for (artistTerm in listOf(false, true)) {
                    val r = Run(history(), start)
                    for (d in 0 until 20) {
                        r.now = start + d * day
                        play(r, a, ListenStart.USER_PICK)
                        play(r, b, ListenStart.USER_PICK, surface = PickSurface.SEARCH)
                    }
                    var won = 0
                    var gapB = Float.NaN
                    for (p in 0 until 10) {
                        r.now = start + 21 * day + p * 3_600_000L
                        PersonalDiagnostics.hop = { _, g, _ -> if (gapB.isNaN()) gapB = g[b.id] ?: Float.POSITIVE_INFINITY }
                        if (press(chooser(r, true, artistTerm), a, hops = 1).firstOrNull()?.first?.id == b.id) won++
                        PersonalDiagnostics.hop = null
                    }
                    pairs += "${if (artistTerm) "b" else "a"} ${a.id.value}->${b.id.value} ${if (inside) "in" else "out"} gap ${"%.2f".format(java.util.Locale.ROOT, gapB)}: $won/10"
                }
            }
            results += linkedMapOf("listener" to "a-to-b-lover", "pairs" to pairs)
        }

        val lines = results.map { json(it) }
        File(out, "personal_sim.json").writeText(lines.joinToString("\n") + "\n")
        return lines
    }

    private fun json(m: Map<String, Any>): String = m.entries.joinToString(",", "{", "}") { (k, v) ->
        "\"$k\":" + when (v) {
            is String -> "\"$v\""
            is List<*> -> v.joinToString(",", "[", "]") { "\"$it\"" }
            is Float -> if (v.isFinite()) "%.4f".format(java.util.Locale.ROOT, v) else "null"
            is Double -> if (v.isFinite()) "%.4f".format(java.util.Locale.ROOT, v) else "null"
            else -> v.toString()
        }
    }
}
