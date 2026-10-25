/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Held-out gate for approach 1 of docs/map-cost-research-2026-10-25.md ("refresh, don't rebuild").
 * Parameters and thresholds are the ones frozen in that report before this run: k = 5 placement,
 * 150 iterations without exaggeration from the raw-scale start, refresh only when flagged ≤ T
 * (3 %, 2 %, 1 % ladder), at most 10 % of flagged rows novel (nearest unflagged row farther than 2×
 * the median 5th-neighbour distance), placed share since the last full build ≤ B (10 %, 5 %, 3 %).
 * Rows are flagged by a 64-bit hash of the fused row, as the plug-in prescribes.
 *
 * Copy into core/smart/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/ to run.
 * Run: `MAP_GATE_REPLAY=1 [MAP_GATE_SEEDS=13,17,19] [MAP_GATE_FMA=<dir with fma-N/>] [MAP_GATE_FMA_SIZES=1000,5000,7989]
 *   [MAP_GATE_PARTS=scenarios,chain,probe] ./gradlew :core:smart:testAndroidHostTest --tests "*MapGateReplay*" --rerun`
 * FMA dirs come from tools/research/map_gate_fma_dump.py. Synthetic and public data only.
 */
class MapGateReplay {
    private val scale = MapScaleReplay()

    private class Lib(
        val name: String,
        val ids: List<TrackId>,
        val rows: FloatArray,
        val dim: Int,
        /** class@15 / genre@15 label; unknown genres get a unique negative label. */
        val genre: IntArray,
        /** style / sub-genre used to build album and novel-style scenarios; -1 = none. */
        val style: IntArray,
    ) {
        val n get() = ids.size
    }

    private class Scenario(
        val name: String,
        val after: List<Int>,
        val before: List<Int>,
        val beforeRows: Map<Int, FloatArray> = emptyMap(),
    )

    @Test
    fun `refresh gate`() {
        if (System.getenv("MAP_GATE_REPLAY") != "1") return
        val parts = System.getenv("MAP_GATE_PARTS")?.split(",")?.toSet() ?: setOf("scenarios", "chain", "probe")
        val libs = mutableListOf<() -> Lib>()
        System.getenv("MAP_GATE_SEEDS")?.split(",")?.filter(String::isNotBlank)?.map(String::toInt).orEmpty().forEach { seed ->
            libs += { synthetic(10_000, seed) }
        }
        System.getenv("MAP_GATE_FMA")?.let { dir ->
            val sizes = System.getenv("MAP_GATE_FMA_SIZES")?.split(",")?.map(String::toInt) ?: listOf(1000, 5000, 7989)
            sizes.forEach { n -> libs += { fma(File(dir, "fma-$n")) } }
        }
        for (make in libs) {
            val lib = make()
            log("GATE lib=${lib.name} n=${lib.n} dim=${lib.dim}")
            if (lib.n <= LibraryLayout.EXACT_MAX_TRACKS) {
                log("GATE lib=${lib.name} NOT-APPLICABLE exact backend (Tsne.embed has no refresh schedule; refresh applies above ${LibraryLayout.EXACT_MAX_TRACKS})")
                continue
            }
            val only = System.getenv("MAP_GATE_SCENARIOS")?.split(",")?.toSet()
            if ("scenarios" in parts) for (scenario in scenarios(lib)) if (only == null || scenario.name in only) runScenario(lib, scenario)
            val budgets = System.getenv("MAP_GATE_CHAIN_BUDGETS")?.split(",")?.map(String::toDouble) ?: listOf(0.10, 0.05, 0.03)
            if ("chain" in parts) for (budget in budgets) chain(lib, budget)
            if ("probe" in parts && lib.name.startsWith("synthetic")) probe(lib)
        }
    }

    // ---------------------------------------------------------------- libraries

    private fun synthetic(n: Int, seed: Int): Lib {
        val fixture = scale.hierarchicalFixture(n, seed)
        val style = IntArray(n) { if (fixture.labels[it] < 48) fixture.labels[it] else -1 }
        val genre = IntArray(n) { if (fixture.labels[it] < 48) fixture.labels[it] else -1 - it }
        return Lib("synthetic-s$seed", fixture.ids, fixture.rows, fixture.dim, genre, style)
    }

    private fun fma(dir: File): Lib {
        val (audioDim, audio) = scale.readNpy(File(dir, "smart_index_vecs.npy"))
        val (textDim, text) = scale.readNpy(File(dir, "smart_text_vecs.npy"))
        val audioIds = File(dir, "smart_index_ids.txt").readLines().filter(String::isNotBlank).map(::TrackId)
        val textIds = File(dir, "smart_text_ids.txt").readLines().filter(String::isNotBlank).map(::TrackId)
        val space = requireNotNull(LibraryVectorFusion.build(
            audioIds, audioIds.zip(audio).toMap(), textIds.zip(text).toMap(), audioDim, textDim,
        ))
        val labels = File(dir, "labels.tsv").readLines().filter(String::isNotBlank).associate { line ->
            val cells = line.split("\t")
            TrackId(cells[0]) to (cells.getOrElse(1) { "" } to cells.getOrElse(2) { "" })
        }
        val genreIndex = HashMap<String, Int>()
        val styleIndex = HashMap<String, Int>()
        val ids = space.trackIds
        val genre = IntArray(ids.size) { i ->
            val g = labels.getValue(ids[i]).first
            if (g.isEmpty()) -1 - i else genreIndex.getOrPut(g) { genreIndex.size }
        }
        val style = IntArray(ids.size) { i ->
            val s = labels.getValue(ids[i]).second
            if (s.isEmpty()) -1 else styleIndex.getOrPut(s) { styleIndex.size }
        }
        log("GATE fma ${dir.name} source=${space.source} genres=${genreIndex.size} styles=${styleIndex.size}")
        return Lib("fma-${ids.size}", ids, space.takeRows(), space.dim, genre, style)
    }

    // ---------------------------------------------------------------- scenarios

    private fun scenarios(lib: Lib): List<Scenario> {
        val n = lib.n
        val all = (0 until n).toList()
        val out = mutableListOf(Scenario("same", all, all))
        fun uniform(name: String, percent: Double, salt: Int): List<Int> {
            val random = Random(salt * 7919 + n)
            return all.shuffled(random).take(ceil(n * percent / 100).toInt())
        }
        for ((i, p) in listOf(1.0, 2.0, 3.0).withIndex()) {
            val added = uniform("add", p, 10 + i).toSet()
            out += Scenario("add${p.toInt()}", all, all.filter { it !in added })
        }
        for ((i, p) in listOf(1.0, 2.0, 3.0).withIndex()) {
            val changed = uniform("change", p, 20 + i)
            val random = Random(30 + i)
            out += Scenario("change${p.toInt()}", all, all, changed.associateWith { row -> perturbed(lib, row, random.nextInt(n)) })
        }
        // Album additions: groups of 10-20 tracks drawn from one style each, ~1 % in all.
        run {
            val random = Random(40 + n)
            val byStyle = all.filter { lib.style[it] >= 0 }.groupBy { lib.style[it] }.filterValues { it.size >= 20 }
            val styles = byStyle.keys.sorted()
            val added = LinkedHashSet<Int>()
            while (added.size < n / 100) {
                val members = byStyle.getValue(styles[random.nextInt(styles.size)]).filter { it !in added }.shuffled(random)
                added += members.take(10 + random.nextInt(11))
            }
            out += Scenario("album1", all, all.filter { it !in added })
        }
        // Novel styles below the size threshold: one style the old map never saw, subsampled.
        val counts = all.filter { lib.style[it] >= 0 }.groupingBy { lib.style[it] }.eachCount()
        val novel = counts.filter { it.value >= ceil(0.025 * n) }.minByOrNull { kotlin.math.abs(it.value - 0.03 * n) }?.key
        if (novel != null) {
            val members = all.filter { lib.style[it] == novel }.shuffled(Random(50 + n))
            for (p in listOf(0.5, 1.0, 2.0)) {
                val take = members.take(ceil(n * p / 100).toInt()).toSet()
                val after = all.filter { lib.style[it] != novel || it in take }
                out += Scenario("novel$p", after, after.filter { it !in take })
            }
            log("GATE lib=${lib.name} novel style=$novel members=${members.size}")
        }
        // Above-threshold changes, routed by size alone (router check only).
        run {
            val added = uniform("add", 5.0, 60).toSet()
            out += Scenario("add5", all, all.filter { it !in added })
            val style = counts.minByOrNull { kotlin.math.abs(it.value - n / 20) }!!.key
            out += Scenario("style", all, all.filter { lib.style[it] != style })
        }
        return out
    }

    private fun perturbed(lib: Lib, row: Int, other: Int): FloatArray {
        val dim = lib.dim
        val out = FloatArray(dim) { 0.5f * lib.rows[row * dim + it] + 0.5f * lib.rows[other * dim + it] }
        val norm = sqrt(out.sumOf { it.toDouble() * it }).toFloat()
        for (d in 0 until dim) out[d] /= norm
        return out
    }

    private fun matrix(lib: Lib, indices: List<Int>, overrides: Map<Int, FloatArray> = emptyMap()): FloatArray {
        val dim = lib.dim
        val out = FloatArray(indices.size * dim)
        indices.forEachIndexed { slot, row ->
            val override = overrides[row]
            if (override != null) override.copyInto(out, slot * dim) else lib.rows.copyInto(out, slot * dim, row * dim, (row + 1) * dim)
        }
        return out
    }

    private fun rowHash(rows: FloatArray, slot: Int, dim: Int): Long {
        var h = -0x340d631b7bdddcdbL
        for (d in 0 until dim) {
            h = h xor rows[slot * dim + d].toRawBits().toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    // ---------------------------------------------------------------- the refresh under test

    private class Stored(val points: List<LayoutPoint>, val rawSpread: Float, val hashes: Map<TrackId, Long>)

    private data class Decision(val refresh: Boolean, val reason: String, val flagged: Int, val novel: Int, val routerMs: Double)

    private class Outcome(val points: List<LayoutPoint>, val rawSpread: Float, val decision: Decision, val ms: Double)

    /**
     * One library change as the plug-in describes it: flag by hash, route, then either today's
     * full build (warm, as production) or the refresh. [placedSinceFull] is the chain's running count.
     */
    private fun update(
        ids: List<TrackId>, rows: FloatArray, dim: Int, stored: Stored, sizeLimit: Double,
        placedShareLimit: Double = 1.0, placedSinceFull: Int = 0, isActive: () -> Boolean = { true },
    ): Outcome {
        val n = ids.size
        val flagged = BooleanArray(n) { i ->
            val old = stored.hashes[ids[i]]
            old == null || old != rowHash(rows, i, dim)
        }
        val flaggedCount = flagged.count { it }
        var decision: Decision? = null
        var raw = 0f
        val previous = stored.points.associate { it.trackId to floatArrayOf(it.x, it.y) }
        val backend = MapEmbeddingBackend { reduced, rn, rd, initial, active ->
            val started = System.nanoTime()
            val route = route(reduced, rn, rd, flagged, flaggedCount, sizeLimit, placedShareLimit, placedSinceFull)
            decision = route.copy(routerMs = (System.nanoTime() - started) / 1e6)
            val result = if (!route.refresh) {
                ApproximateMapEmbedding.embed(reduced, rn, rd, initial, active)
            } else {
                val y = rescaled(initial!!, stored.rawSpread)
                place(reduced, rn, rd, flagged, y, active)
                MapEmbeddingResult.Complete(BarnesHutTsne.embed(
                    reduced, rn, rd, SEED, y, active,
                    iterations = REFRESH_ITERATIONS, exaggerationIterations = 0, scheduleOffset = Tsne.EXAGGERATION_ITERATIONS,
                ))
            }
            if (result is MapEmbeddingResult.Complete) raw = spread(result.coordinates)
            result
        }
        val start = System.nanoTime()
        val points = LibraryLayout.computeWith(
            LibraryVectorSpace(ids, rows.copyOf(), dim, LibraryVectorSource.AUDIO), previous, isActive, Long.MAX_VALUE, backend,
        )
        val ms = (System.nanoTime() - start) / 1e6 - (decision?.routerMs ?: 0.0)
        return Outcome(points, raw, decision!!, ms)
    }

    private fun route(
        reduced: FloatArray, n: Int, dim: Int, flagged: BooleanArray, flaggedCount: Int,
        sizeLimit: Double, placedShareLimit: Double, placedSinceFull: Int,
    ): Decision {
        if (flaggedCount > sizeLimit * n) return Decision(false, "size", flaggedCount, 0, 0.0)
        if (placedSinceFull + flaggedCount > placedShareLimit * n) return Decision(false, "budget", flaggedCount, 0, 0.0)
        if (flaggedCount == 0 || NO_NOVELTY) return Decision(true, "refresh", flaggedCount, 0, 0.0)
        // Median 5th-neighbour distance over the library, and each flagged row's nearest unflagged row.
        val fifth = DoubleArray(n)
        val packed = LongArray(n)
        var novel = 0
        for (i in 0 until n) {
            sortedByDistance(reduced, n, dim, i, packed)
            fifth[i] = sqrt(Float.fromBits((packed[5] ushr 32).toInt()).toDouble())
        }
        val median = fifth.sorted()[n / 2]
        for (i in 0 until n) {
            if (!flagged[i]) continue
            sortedByDistance(reduced, n, dim, i, packed)
            for (position in 1 until n) {
                val j = (packed[position] and 0xFFFFFFFFL).toInt()
                if (flagged[j]) continue
                if (sqrt(Float.fromBits((packed[position] ushr 32).toInt()).toDouble()) > NOVELTY_FACTOR * median) novel++
                break
            }
        }
        val refresh = novel <= NOVEL_SHARE * flaggedCount
        return Decision(refresh, if (refresh) "refresh" else "novelty", flaggedCount, novel, 0.0)
    }

    /** Flagged rows to the mean stored position of their [PLACE_NEIGHBOURS] nearest unflagged rows. */
    private fun place(reduced: FloatArray, n: Int, dim: Int, flagged: BooleanArray, y: FloatArray, isActive: () -> Boolean) {
        val start = y.copyOf()
        val packed = LongArray(n)
        for (i in 0 until n) {
            if (!flagged[i]) continue
            if (!isActive()) return
            sortedByDistance(reduced, n, dim, i, packed)
            var x = 0f
            var z = 0f
            var taken = 0
            for (position in 1 until n) {
                val j = (packed[position] and 0xFFFFFFFFL).toInt()
                if (flagged[j]) continue
                x += start[j * 2]
                z += start[j * 2 + 1]
                if (++taken == PLACE_NEIGHBOURS) break
            }
            if (taken > 0) {
                y[i * 2] = x / taken
                y[i * 2 + 1] = z / taken
            }
        }
    }

    private fun cold(ids: List<TrackId>, rows: FloatArray, dim: Int): Stored {
        var raw = 0f
        val recording = MapEmbeddingBackend { r, rn, rd, initial, active ->
            ApproximateMapEmbedding.embed(r, rn, rd, initial, active).also { if (it is MapEmbeddingResult.Complete) raw = spread(it.coordinates) }
        }
        val points = LibraryLayout.computeWith(LibraryVectorSpace(ids, rows.copyOf(), dim, LibraryVectorSource.AUDIO), emptyMap(), { true }, Long.MAX_VALUE, recording)
        return Stored(points, raw, hashes(ids, rows, dim))
    }

    private fun hashes(ids: List<TrackId>, rows: FloatArray, dim: Int): Map<TrackId, Long> =
        ids.indices.associate { ids[it] to rowHash(rows, it, dim) }

    // ---------------------------------------------------------------- one scenario

    private fun runScenario(lib: Lib, scenario: Scenario) {
        val dim = lib.dim
        val label = "lib=${lib.name} ${scenario.name}"
        val beforeIds = scenario.before.map(lib.ids::get)
        val before = cold(beforeIds, matrix(lib, scenario.before, scenario.beforeRows), dim)
        val ids = scenario.after.map(lib.ids::get)
        val rows = matrix(lib, scenario.after)
        val n = ids.size
        val beforeSet = scenario.before.toSet()
        val flaggedRows = (0 until n).filter { val r = scenario.after[it]; r !in beforeSet || r in scenario.beforeRows }
        val flaggedSet = flaggedRows.toSet()
        // T = 3 %, the top of the ladder. A change of at most 2 % (1 %) gets the same result under
        // T = 2 % (1 %); a larger one is a full build there, which is today's path by construction.
        val refresh = update(ids, rows, dim, before, 0.03)
        val d = refresh.decision
        log("GATE $label share=${f4(flaggedRows.size.toDouble() / n)} decision=${if (d.refresh) "refresh" else "full"} reason=${d.reason} flagged=${d.flagged} novel=${d.novel} routerMs=${f1(d.routerMs)}")
        if (!d.refresh || scenario.name == "add5" || scenario.name == "style") return
        val today = update(ids, rows, dim, before, -1.0)
        check(!today.decision.refresh)
        val coldAfter = cold(ids, rows, dim)
        val reference = LibraryLayout.reduceForEmbedding(rows.copyOf(), n, dim)
        val k = reference.size / n
        val coldSeed2 = BarnesHutTsne.embed(reference, n, k, SEED + 1)
        val unchanged = (0 until n).filter { it !in flaggedSet }.map(ids::get)
        val m = metrics(
            reference, k, IntArray(n) { lib.genre[scenario.after[it]] }, flaggedRows,
            linkedMapOf("today" to flatten(today.points), "refresh" to flatten(refresh.points), "cold" to flatten(coldAfter.points), "cold2" to coldSeed2),
        )
        val sToday = stability(before.points, today.points, unchanged)
        val sRefresh = stability(before.points, refresh.points, unchanged)
        log("GATE $label time todayMs=${f1(today.ms)} refreshMs=${f1(refresh.ms)} ratio=${f4(refresh.ms / today.ms)}")
        log("GATE $label stability today kept=${f4(sToday.kept)} p90=${f4(sToday.p90)} median=${f4(sToday.median)} refresh kept=${f4(sRefresh.kept)} p90=${f4(sRefresh.p90)} median=${f4(sRefresh.median)}")
        for ((name, row) in m) log("GATE $label metrics $name ${row.entries.joinToString(" ") { "${it.key}=${f4(it.value)}" }}")
        val t = m.getValue("today")
        val r = m.getValue("refresh")
        val noise = overlapOf(m, "cold2")
        val rowsOut = linkedMapOf(
            "trust" to (r.getValue("trust") >= t.getValue("trust") - 0.005),
            "recall" to (r.getValue("recall") >= t.getValue("recall") * 0.97),
            "class" to (r.getValue("class") >= t.getValue("class") - 0.010),
            "overlapCold" to (r.getValue("overlapCold") >= noise - 0.10),
            "kept15" to (sRefresh.kept >= sToday.kept + 0.10),
            "p90move" to (sRefresh.p90 <= 0.5 * sToday.p90 && sRefresh.p90 <= 0.02),
            "timeHost" to (refresh.ms <= 0.4 * today.ms),
        )
        if (flaggedRows.isNotEmpty()) {
            rowsOut["RF"] = r.getValue("RF") >= t.getValue("RF") * 0.85
            rowsOut["CF"] = r.getValue("CF") >= t.getValue("CF") - 0.05
        }
        log("GATE $label verdict ${rowsOut.entries.joinToString(" ") { "${it.key}=${if (it.value) "PASS" else "FAIL"}" }} overlapNoise=${f4(noise)}")
    }

    private fun overlapOf(m: Map<String, Map<String, Double>>, name: String) = m.getValue(name).getValue("overlapCold")

    // ---------------------------------------------------------------- chain

    /** 88 % cold, ten +1 % additions, one −2 % removal; full builds when the router says so. */
    private fun chain(lib: Lib, budget: Double) {
        val dim = lib.dim
        val n = lib.n
        val random = Random(70 + n)
        val order = (0 until n).shuffled(random)
        val held = order.take((n * 0.12).toInt()).toMutableList()
        val current = order.drop((n * 0.12).toInt()).sorted().toMutableList()
        val step = n / 100
        var stored = cold(current.map(lib.ids::get), matrix(lib, current), dim)
        var placedSinceFull = 0
        val placedEver = HashSet<Int>()
        var fullBuilds = 0
        var totalMs = 0.0
        for (s in 1..11) {
            if (s <= 10) {
                val add = held.take(step); held.removeAll(add.toSet()); current += add; current.sort(); placedEver += add
            } else {
                val remove = current.shuffled(random).take(current.size / 50).toSet()
                current.removeAll(remove); placedEver.removeAll(remove)
            }
            val ids = current.map(lib.ids::get)
            val rows = matrix(lib, current)
            val outcome = update(ids, rows, dim, stored, 0.03, budget, placedSinceFull)
            totalMs += outcome.ms
            if (outcome.decision.refresh) placedSinceFull += outcome.decision.flagged else { placedSinceFull = 0; fullBuilds++ }
            stored = Stored(outcome.points, outcome.rawSpread, hashes(ids, rows, dim))
            log("GATE chain lib=${lib.name} B=$budget step=$s n=${ids.size} decision=${if (outcome.decision.refresh) "refresh" else "full"} reason=${outcome.decision.reason} flagged=${outcome.decision.flagged} ms=${f1(outcome.ms)}")
        }
        val ids = current.map(lib.ids::get)
        val rows = matrix(lib, current)
        val m = ids.size
        val coldFinal = cold(ids, rows, dim)
        val reference = LibraryLayout.reduceForEmbedding(rows.copyOf(), m, dim)
        val k = reference.size / m
        val placedRows = current.indices.filter { current[it] in placedEver }
        val metrics = metrics(
            reference, k, IntArray(m) { lib.genre[current[it]] }, placedRows,
            linkedMapOf("chain" to flatten(stored.points), "cold" to flatten(coldFinal.points)),
        )
        val c = metrics.getValue("chain")
        val cold = metrics.getValue("cold")
        val pass = c.getValue("trust") >= cold.getValue("trust") - 0.005 &&
            c.getValue("recall") >= cold.getValue("recall") * 0.97 && c.getValue("RF") >= cold.getValue("RF") * 0.85
        for ((name, row) in metrics) log("GATE chain lib=${lib.name} B=$budget metrics $name ${row.entries.joinToString(" ") { "${it.key}=${f4(it.value)}" }}")
        log("GATE chain lib=${lib.name} B=$budget fullBuilds=$fullBuilds totalMs=${f1(totalMs)} verdict=${if (pass) "PASS" else "FAIL"}")
    }

    // ---------------------------------------------------------------- heap and cancellation

    private fun probe(lib: Lib) {
        val dim = lib.dim
        val all = (0 until lib.n).toList()
        val added = all.shuffled(Random(80)).take(lib.n / 100).toSet()
        val before = all.filter { it !in added }
        val stored = cold(before.map(lib.ids::get), matrix(lib, before), dim)
        val ids = all.map(lib.ids::get)
        val rows = matrix(lib, all)
        for (mode in listOf("today", "refresh", "today", "refresh")) {
            val limit = if (mode == "refresh") 0.03 else -1.0
            val peak = peakHeap { update(ids, rows, dim, stored, limit) }
            log("GATE probe lib=${lib.name} heap mode=$mode peakAboveBaselineMb=${f1(peak / 1048576.0)}")
        }
        for (cancelAt in listOf(100L, 1000L, 2500L, 4000L)) {
            for (mode in listOf("today", "refresh")) {
                val limit = if (mode == "refresh") 0.03 else -1.0
                val start = System.nanoTime()
                val deadline = start + cancelAt * 1_000_000
                var flippedAt = 0L
                val active = { val now = System.nanoTime(); if (now >= deadline) { if (flippedAt == 0L) flippedAt = now; false } else true }
                try { update(ids, rows, dim, stored, limit, isActive = active) } catch (_: Throwable) {}
                val end = System.nanoTime()
                val stop = if (flippedAt == 0L) -1.0 else (end - flippedAt) / 1e6
                log("GATE probe lib=${lib.name} cancel mode=$mode at=${cancelAt}ms stoppedWithinMs=${f1(stop)}")
            }
        }
    }

    private fun peakHeap(block: () -> Unit): Long {
        val memory = ManagementFactory.getMemoryMXBean()
        repeat(3) { System.gc() }
        val baseline = memory.heapMemoryUsage.used
        val peak = java.util.concurrent.atomic.AtomicLong(baseline)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread { while (running.get()) { peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf); Thread.sleep(1) } }
        sampler.isDaemon = true
        sampler.start()
        block()
        running.set(false)
        sampler.join()
        return peak.get() - baseline
    }

    // ---------------------------------------------------------------- metrics

    private fun metrics(
        reference: FloatArray, k: Int, labels: IntArray, flaggedRows: List<Int>, maps: LinkedHashMap<String, FloatArray>,
    ): Map<String, Map<String, Double>> {
        val n = labels.size
        val inputNeighbours = neighbours(reference, n, k, K)
        val mapNeighbours = maps.mapValues { (_, map) -> neighbours(map, n, 2, K) }
        val coldNeighbours = mapNeighbours["cold"]
        val penalty = DoubleArray(maps.size)
        val rank = IntArray(n)
        val packed = LongArray(n)
        for (i in 0 until n) {
            sortedByDistance(reference, n, k, i, packed)
            for (position in 1 until n) rank[packed[position].toInt()] = position
            mapNeighbours.values.forEachIndexed { m, neighbours -> for (j in neighbours[i]) penalty[m] += maxOf(0, rank[j] - K).toDouble() }
        }
        return mapNeighbours.entries.withIndex().associate { (m, entry) ->
            val (name, neighbours) = entry
            fun share(rowsOf: List<Int>, hit: (Int, Int) -> Boolean) =
                if (rowsOf.isEmpty()) Double.NaN else rowsOf.sumOf { i -> neighbours[i].count { hit(i, it) }.toDouble() / K } / rowsOf.size
            name to linkedMapOf(
                "trust" to 1.0 - 2.0 / (n * K * (2.0 * n - 3.0 * K - 1.0)) * penalty[m],
                "recall" to overlap(inputNeighbours, neighbours),
                "class" to share((0 until n).toList()) { i, j -> labels[i] == labels[j] },
                "RF" to share(flaggedRows) { i, j -> j in inputNeighbours[i] },
                "CF" to share(flaggedRows) { i, j -> labels[i] == labels[j] },
                "overlapCold" to (coldNeighbours?.let { overlap(it, neighbours) } ?: Double.NaN),
            )
        }
    }

    private class Stability(val kept: Double, val median: Double, val p90: Double)

    private fun stability(before: List<LayoutPoint>, after: List<LayoutPoint>, unchanged: List<TrackId>): Stability {
        val beforeById = before.associateBy { it.trackId }
        val afterById = after.associateBy { it.trackId }
        val a = FloatArray(unchanged.size * 2)
        val b = FloatArray(unchanged.size * 2)
        val moved = DoubleArray(unchanged.size)
        unchanged.forEachIndexed { slot, id ->
            val p = beforeById.getValue(id)
            val q = afterById.getValue(id)
            a[slot * 2] = p.x; a[slot * 2 + 1] = p.y
            b[slot * 2] = q.x; b[slot * 2 + 1] = q.y
            val dx = (q.x - p.x).toDouble()
            val dy = (q.y - p.y).toDouble()
            moved[slot] = sqrt(dx * dx + dy * dy)
        }
        moved.sort()
        return Stability(overlap(neighbours(a, unchanged.size, 2, K), neighbours(b, unchanged.size, 2, K)), moved[moved.size / 2], moved[moved.size * 9 / 10])
    }

    private fun spread(y: FloatArray): Float {
        val n = y.size / 2
        var cx = 0.0
        var cy = 0.0
        for (i in 0 until n) { cx += y[i * 2]; cy += y[i * 2 + 1] }
        cx /= n; cy /= n
        var sum = 0.0
        for (i in 0 until n) { val dx = y[i * 2] - cx; val dy = y[i * 2 + 1] - cy; sum += dx * dx + dy * dy }
        return sqrt(sum / n).toFloat()
    }

    private fun rescaled(y: FloatArray, target: Float): FloatArray {
        val factor = target / spread(y)
        return FloatArray(y.size) { y[it] * factor }
    }

    private fun flatten(points: List<LayoutPoint>): FloatArray =
        FloatArray(points.size * 2).also { out -> points.forEachIndexed { i, p -> out[i * 2] = p.x; out[i * 2 + 1] = p.y } }

    private fun sortedByDistance(points: FloatArray, n: Int, dim: Int, i: Int, out: LongArray) {
        for (j in 0 until n) {
            var sum = 0f
            if (j != i) for (d in 0 until dim) {
                val delta = points[i * dim + d] - points[j * dim + d]
                sum += delta * delta
            }
            val key = if (j == i) -1L else sum.toRawBits().toLong()
            out[j] = (key shl 32) or j.toLong()
        }
        out.sort()
    }

    private fun neighbours(points: FloatArray, n: Int, dim: Int, k: Int): Array<IntArray> {
        val packed = LongArray(n)
        return Array(n) { i ->
            sortedByDistance(points, n, dim, i, packed)
            IntArray(k) { (packed[it + 1] and 0xFFFFFFFFL).toInt() }
        }
    }

    private fun overlap(a: Array<IntArray>, b: Array<IntArray>): Double =
        a.indices.sumOf { i -> b[i].count { it in a[i] }.toDouble() / K } / a.size

    private val logFile = System.getenv("MAP_GATE_LOG")?.let(::File)

    private fun log(line: String) {
        println(line)
        logFile?.appendText(line + "\n")
    }

    private fun f1(value: Double) = String.format(java.util.Locale.ROOT, "%.1f", value)
    private fun f4(value: Double) = String.format(java.util.Locale.ROOT, "%.4f", value)

    private companion object {
        const val K = 15
        const val SEED = 0x1A7E27
        const val PLACE_NEIGHBOURS = 5
        const val REFRESH_ITERATIONS = 150
        const val NOVELTY_FACTOR = 2.0
        const val NOVEL_SHARE = 0.10
        /** Diagnostic only (not the frozen design): skip the novelty check. */
        val NO_NOVELTY = System.getenv("MAP_GATE_NO_NOVELTY") == "1"
    }
}
