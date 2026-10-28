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
 * PLAN_PERSONAL §7, synthetic listeners on an FMA library (LJ_STAND_PERSONAL_SIM=1 through SmartStandRunner): each
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
    private val byId = library.associateBy { it.id }
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

    private fun chooser(run: Run, full: Boolean) = EngineNextTrackChooser(
        engine = engine, history = run.history, library = { library }, learnFromHabits = { full },
        personalModel = PersonalModelCache(run.history, engine::artistKeysOf).let { cache -> { lib: List<TrackDescriptor> -> cache.model(lib) } },
        clock = { run.now },
    )

    /** One press: [hops] rows planned and topped up as the controllers do. */
    private suspend fun press(chooser: EngineNextTrackChooser, seed: TrackDescriptor, hops: Int = 12): List<Pair<TrackDescriptor, Int?>> {
        // Every press starts afresh: a seed planned in an earlier queue would otherwise resume that queue's walk.
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
                             parent: String? = null, surface: PickSurface? = null) {
        val heard = (track.durationMs ?: 180_000L).coerceAtMost(600_000L)
        val event = ListenEvent(track.id, run.now, heard, track.durationMs, completed = true, skipped = false, shuffleMode = shuffle,
            listenedMs = heard, origin = ListenOrigin(start, plan, parent, surface))
        run.history.record(PersonalSignals.annotate(event))
        run.now += heard + 2_000L
    }

    /** FULL vs CORE on the same history: changed hops, edit distance, every decision's core gap, and collapse. */
    private suspend fun probes(run: Run, name: String): Map<String, Any> {
        val gaps = ArrayList<Float>()
        val farGaps = ArrayList<Float>()
        val record: (Float, Boolean, Boolean) -> Unit = { gap, far, changed -> if (changed) { if (far) farGaps += gap else gaps += gap } }
        PersonalDiagnostics.sink = record
        var changedHops = 0
        var hops = 0
        var edits = 0
        val probeSeeds = seeds.take(20)
        for (seed in probeSeeds) {
            val full = press(chooser(run, true), seed).map { it.first.id }
            PersonalDiagnostics.sink = null
            val core = press(chooser(run, false), seed).map { it.first.id }
            PersonalDiagnostics.sink = record
            hops += maxOf(full.size, core.size)
            changedHops += full.indices.count { it >= core.size || full[it] != core[it] } + (core.size - full.size).coerceAtLeast(0)
            edits += editDistance(full, core)
        }
        PersonalDiagnostics.sink = null
        // Collapse: 100 FULL presses rotating over the seeds.
        val counts = HashMap<TrackId, Int>()
        var picks = 0
        for (p in 0 until 100) {
            val got = press(chooser(run, true), seeds[p % seeds.size])
            for ((t, _) in got) counts.merge(t.id, 1, Int::plus)
            picks += got.size
        }
        return linkedMapOf(
            "listener" to name,
            "changedHopShare" to changedHops.toDouble() / hops.coerceAtLeast(1),
            "meanEditDistance" to edits.toDouble() / probeSeeds.size,
            "personalChanges" to gaps.size,
            "maxGapOverDelta" to (gaps.maxOrNull() ?: 0f),
            "farChanges" to farGaps.size,
            "maxFarGapOverDelta" to (farGaps.maxOrNull() ?: 0f),
            "topShare100" to (counts.maxOfOrNull { it.value } ?: 0).toDouble() / picks.coerceAtLeast(1),
        )
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

    suspend fun runAll(out: File, random: Random = Random(2026)): List<String> {
        val results = ArrayList<Map<String, Any>>()
        // LJ_SIM_PARTS: a comma list of listeners to run (all by default).
        val parts = System.getenv("LJ_SIM_PARTS")?.split(',')?.toSet()
        fun wanted(name: String) = parts == null || name in parts
        // No history: FULL and CORE identical.
        if (wanted("no-history")) run {
            val r = Run(history(), start)
            var identical = 0
            for (seed in seeds.take(20)) {
                if (press(chooser(r, true), seed).map { it.first.id } == press(chooser(r, false), seed).map { it.first.id }) identical++
            }
            var coreRepeat = 0
            for (seed in seeds.take(20)) {
                if (press(chooser(r, false), seed).map { it.first.id } == press(chooser(r, false), seed).map { it.first.id }) coreRepeat++
            }
            results += linkedMapOf("listener" to "no-history", "identical" to identical, "of" to seeds.take(20).size, "coreVsCoreIdentical" to coreRepeat)
        }

        // Cluster-jumper: three presses a day for 90 days, and after each, with p = 0.2, a random song anywhere.
        if (wanted("cluster-jumper")) run {
            val r = Run(history(), start)
            var far = 0
            PersonalDiagnostics.sink = { _, isFar, changed -> if (isFar && changed) far++ }
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
            PersonalDiagnostics.sink = null
            results += probes(r, "cluster-jumper") + ("farInsertions90d" to far)
        }

        // Sleeper: a press a night and four hours of autoplay (sixty songs), thirty nights.
        if (wanted("sleeper")) run {
            val r = Run(history(), start)
            val c = chooser(r, true)
            val streakPairs = ArrayList<Pair<TrackId, TrackId>>()
            for (d in 0 until 30) {
                r.now = start + d * day
                val seed = library[random.nextInt(library.size)]
                play(r, seed, ListenStart.USER_PICK)
                val queue = press(c, seed, hops = 60)
                for ((t, plan) in queue) play(r, t, ListenStart.AUTO_ADVANCE, plan)
                for (i in 21 until queue.size) streakPairs += queue[i - 1].first.id to queue[i].first.id
            }
            val model = PersonalModelCache(r.history, engine::artistKeysOf).model(library)
            val maxLate = streakPairs.maxOfOrNull { (a, b) -> model.weight(model.songOf(a), model.songOf(b)) } ?: 0f
            results += probes(r, "sleeper") + ("maxStreakPairWeightPast20" to maxLate)
        }

        // Album listener: a whole album in order every day, shuffle off, thirty days.
        if (wanted("album-listener")) run {
            val r = Run(history(), start)
            val albums = library.filter { !it.album.isNullOrBlank() }.groupBy { it.album!! }.filterValues { it.size >= 4 }.values.toList()
            val pairs = ArrayList<Pair<TrackId, TrackId>>()
            for (d in 0 until 30) {
                r.now = start + d * day
                val album = albums[random.nextInt(albums.size)].sortedBy { it.trackNumber }
                album.forEachIndexed { i, t ->
                    play(r, t, if (i == 0) ListenStart.USER_PICK else ListenStart.AUTO_ADVANCE, shuffle = "OFF", parent = "album:${t.album}")
                    if (i > 0) pairs += album[i - 1].id to t.id
                }
            }
            val model = PersonalModelCache(r.history, engine::artistKeysOf).model(library)
            val maxHabit = pairs.maxOfOrNull { (a, b) -> model.habit(model.songOf(a), model.songOf(b)) } ?: 0f
            results += probes(r, "album-listener") + ("maxAlbumPairHabit" to maxHabit) + ("albums" to albums.size)
        }

        // Shuffle user: twenty random songs a day with shuffle on, thirty days.
        if (wanted("shuffle-user")) run {
            val r = Run(history(), start)
            for (d in 0 until 30) {
                r.now = start + d * day
                repeat(20) { i -> play(r, library[random.nextInt(library.size)], if (i == 0) ListenStart.USER_PICK else ListenStart.AUTO_ADVANCE, shuffle = "ON") }
            }
            results += probes(r, "shuffle-user")
        }

        // A→B lover: B is the core's runner-up at hop 1 from A; picked right after A in twenty sessions.
        if (wanted("a-to-b-lover")) run {
            var wins = 0
            var tried = 0
            val lovers = ArrayList<String>()
            for (a in seeds.take(5)) {
                val r = Run(history(), start)
                val coreFirst = press(chooser(r, false), a, hops = 1).firstOrNull()?.first ?: continue
                // The core's next choice with its first left out: the runner-up.
                val c = chooser(r, false)
                val b = c.choose(a, emptyList(), library.filter { it.id != a.id && it.id != coreFirst.id })?.track ?: continue
                for (d in 0 until 20) {
                    r.now = start + d * day
                    play(r, a, ListenStart.USER_PICK)
                    play(r, b, ListenStart.USER_PICK, surface = PickSurface.SEARCH)
                }
                var gapB = Float.NaN
                PersonalDiagnostics.hop = { _, gaps -> if (gapB.isNaN()) gapB = gaps[b.id] ?: Float.POSITIVE_INFINITY }
                r.now = start + 21 * day
                press(chooser(r, true), a, hops = 1)
                PersonalDiagnostics.hop = null
                var won = 0
                for (k in 0 until 10) {
                    r.now = start + 21 * day + k * 3_600_000L
                    if (press(chooser(r, true), a, hops = 1).firstOrNull()?.first?.id == b.id) won++
                }
                wins += won
                tried += 10
                lovers += "${a.id.value}->${b.id.value}:$won/10 gap ${"%.2f".format(gapB)} delta"
            }
            results += linkedMapOf("listener" to "a-to-b-lover", "hop1Wins" to wins, "of" to tried, "pairs" to lovers)
        }

        val lines = results.map { json(it) }
        File(out, "personal_sim.json").writeText(lines.joinToString("\n") + "\n")
        return lines
    }

    private fun json(m: Map<String, Any>): String = m.entries.joinToString(",", "{", "}") { (k, v) ->
        "\"$k\":" + when (v) {
            is String -> "\"$v\""
            is List<*> -> v.joinToString(",", "[", "]") { "\"$it\"" }
            is Float -> if (v.isFinite()) "%.4f".format(v) else "null"
            is Double -> if (v.isFinite()) "%.4f".format(v) else "null"
            else -> v.toString()
        }
    }
}
