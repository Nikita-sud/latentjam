/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.MapRefreshEngine.Config
import io.github.nikitasud.latentjam.smart.cluster.MapRefreshEngine.Router
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Round 2 of the Map refresh gate (docs/map-cost-research-2026-10-25.md, "Round 2"): approach 1 + 3 through
 * [MapRefreshEngine] with the round-2 routers. `MAP_R2_MODE=pilot` (routing + quick quality on pilot data) or
 * `gate` (every frozen row). Synthetic and public data only.
 *
 * Copy (with MapRefreshEngine.kt) into core/smart/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/.
 * Gate runs (UNION primary and the Soundtrack exclusion are hard-wired in gate mode), one invocation each:
 *   MAP_R2_REPLAY=1 MAP_R2_MODE=gate MAP_R2_SEEDS=23,29,31 MAP_R2_LOG=<log> ./gradlew :core:smart:testAndroidHostTest --tests "*MapRound2Replay*" --rerun
 *   MAP_R2_REPLAY=1 MAP_R2_MODE=gate MAP_R2_FMA=<dump>/fma-gate-s101 MAP_R2_FMA_SIZES=5000 MAP_R2_LOG=<log> ./gradlew ... (same task)
 *   ... MAP_R2_FMA=<dump>/fma-gate-s202 MAP_R2_FMA_SIZES=5000 ...;  ... MAP_R2_FMA=<dump>/fma-gate-s303 MAP_R2_FMA_SIZES=6500 ...
 * Pilot: MAP_R2_MODE=pilot with MAP_R2_ROUTERS / MAP_R2_PRIMARY / MAP_R2_SCENARIOS.
 */
class MapRound2Replay {
    private val scale = MapScaleReplay()
    private val mode = System.getenv("MAP_R2_MODE") ?: "pilot"
    private val logFile = System.getenv("MAP_R2_LOG")?.let(::File)

    private class Lib(
        val name: String, val ids: List<TrackId>, val rows: FloatArray, val dim: Int,
        val genre: IntArray, val style: IntArray, val heldOut: BooleanArray, val family: Map<TrackId, String?>?,
        val styleName: Map<Int, String> = emptyMap(),
    ) { val n get() = ids.size }

    private class Scenario(val name: String, val after: List<Int>, val before: List<Int>, val beforeRows: Map<Int, FloatArray> = emptyMap())

    @Test
    fun `round 2`() {
        if (System.getenv("MAP_R2_REPLAY") != "1") return
        val parts = System.getenv("MAP_R2_PARTS")?.split(",")?.toSet() ?: setOf("scenarios", "chain", "probe")
        // Gate mode is frozen: UNION is the primary, the single routers are reported variants.
        val routers = if (mode == "gate") listOf(Router.UNION, Router.GEOMETRY, Router.GEOMETRY_FAMILY, Router.CHECK)
            else (System.getenv("MAP_R2_ROUTERS") ?: "GEOMETRY,GEOMETRY_FAMILY,CHECK").split(",").map(Router::valueOf)
        val primary = if (mode == "gate") Router.UNION else System.getenv("MAP_R2_PRIMARY")?.let(Router::valueOf)
        val only = System.getenv("MAP_R2_SCENARIOS")?.split(",")?.toSet()
        val libs = mutableListOf<() -> Lib>()
        System.getenv("MAP_R2_SEEDS")?.split(",")?.filter(String::isNotBlank)?.forEach { s -> libs += { synthetic(10_000, s.toInt()) } }
        System.getenv("MAP_R2_FMA")?.let { dir ->
            (System.getenv("MAP_R2_FMA_SIZES") ?: "5000").split(",").forEach { n -> libs += { fma(File(dir, "fma-$n")) } }
        }
        for (make in libs) {
            val lib = make()
            log("R2 lib=${lib.name} n=${lib.n} dim=${lib.dim} family=${lib.family != null} mode=$mode")
            val variants = routers.filter { it != Router.GEOMETRY_FAMILY || lib.family != null }
            if ("scenarios" in parts) for (s in scenarios(lib)) if (only == null || s.name in only) runScenario(lib, s, variants)
            if ("chain" in parts && primary != null && (primary != Router.GEOMETRY_FAMILY || lib.family != null)) {
                for (budget in listOf(0.10, 0.05, 0.03)) chain(lib, primary, budget)
            }
            if ("probe" in parts && primary != null && lib.name.startsWith("synthetic")) probe(lib, primary)
        }
    }

    private fun config(lib: Lib, router: Router, placedShareLimit: Double = 0.10) = Config(router, placedShareLimit = placedShareLimit, family = lib.family)

    // ------------------------------------------------------------ data

    private fun synthetic(n: Int, seed: Int): Lib {
        val f = scale.hierarchicalFixture(n, seed)
        return Lib("synthetic-s$seed", f.ids, f.rows, f.dim,
            IntArray(n) { if (f.labels[it] < 48) f.labels[it] else -1 - it },
            IntArray(n) { if (f.labels[it] < 48) f.labels[it] else -1 }, BooleanArray(n) { true }, null,
            (0 until 48).associateWith { "style-$it" })
    }

    private fun fma(dir: File): Lib {
        val (audioDim, audio) = scale.readNpy(File(dir, "smart_index_vecs.npy"))
        val (textDim, text) = scale.readNpy(File(dir, "smart_text_vecs.npy"))
        val audioIds = File(dir, "smart_index_ids.txt").readLines().filter(String::isNotBlank).map(::TrackId)
        val textIds = File(dir, "smart_text_ids.txt").readLines().filter(String::isNotBlank).map(::TrackId)
        val space = requireNotNull(LibraryVectorFusion.build(audioIds, audioIds.zip(audio).toMap(), textIds.zip(text).toMap(), audioDim, textDim))
        val cells = File(dir, "labels.tsv").readLines().filter(String::isNotBlank).associate { l -> l.split("\t").let { TrackId(it[0]) to it } }
        val ids = space.trackIds
        val g = HashMap<String, Int>(); val st = HashMap<String, Int>()
        return Lib(
            "fma-${dir.name.removePrefix("fma-")}-${dir.parentFile.name}", ids, space.takeRows(), space.dim,
            IntArray(ids.size) { i -> cells.getValue(ids[i])[1].let { if (it.isEmpty()) -1 - i else g.getOrPut(it) { g.size } } },
            IntArray(ids.size) { i -> cells.getValue(ids[i])[2].let { if (it.isEmpty()) -1 else st.getOrPut(it) { st.size } } },
            BooleanArray(ids.size) { i -> cells.getValue(ids[i]).getOrElse(3) { "" } in setOf("validation", "test") },
            ids.associateWith { id -> cells.getValue(id).getOrElse(4) { "" }.ifEmpty { null } },
            st.entries.associate { it.value to it.key },
        )
    }

    private fun scenarios(lib: Lib): List<Scenario> {
        val n = lib.n
        val all = (0 until n).toList()
        val out = mutableListOf(Scenario("same", all, all))
        fun uniform(p: Double, salt: Int) = all.shuffled(Random(salt * 7919 + n + 1000)).take(ceil(n * p / 100).toInt())
        for ((i, p) in listOf(1.0, 2.0, 3.0).withIndex()) {
            val added = uniform(p, 110 + i).toSet()
            out += Scenario("add${p.toInt()}", all, all.filter { it !in added })
        }
        for ((i, p) in listOf(1.0, 2.0, 3.0).withIndex()) {
            val changed = uniform(p, 120 + i)
            val random = Random(130 + i)
            out += Scenario("change${p.toInt()}", all, all, changed.associateWith { perturbed(lib, it, random.nextInt(n)) })
        }
        run {
            val random = Random(140 + n)
            val byStyle = all.filter { lib.style[it] >= 0 }.groupBy { lib.style[it] }.filterValues { it.size >= 20 }
            val styles = byStyle.keys.sorted()
            val added = LinkedHashSet<Int>()
            while (added.size < n / 100) {
                val members = byStyle.getValue(styles[random.nextInt(styles.size)]).filter { it !in added }.shuffled(random)
                added += members.take(10 + random.nextInt(11))
            }
            out += Scenario("album1", all, all.filter { it !in added })
        }
        // New styles (absent before the change), newcomers drawn only from held-out rows (FMA validation/test, never
        // seen by the tagger). Gate: the 3 styles with the most held-out members, the pilot's "Soundtrack" excluded;
        // synthetic: the 3 styles whose share is closest to 3 %. Pilot: round-1 rule (one style).
        val excluded = if (mode == "gate") setOf("Soundtrack") else System.getenv("MAP_R2_EXCLUDE_STYLES")?.split("|")?.toSet().orEmpty()
        val heldCounts = all.filter { lib.style[it] >= 0 && lib.heldOut[it] }.groupingBy { lib.style[it] }.eachCount()
            .filterKeys { lib.styleName[it] !in excluded }
        val share = all.filter { lib.style[it] >= 0 }.groupingBy { lib.style[it] }.eachCount()
        val styles = when {
            mode != "gate" -> listOfNotNull(heldCounts.filter { it.value >= ceil(0.02 * n) }.keys.minByOrNull { st -> kotlin.math.abs(share.getValue(st) - 0.03 * n) }
                ?: heldCounts.maxByOrNull { it.value }?.key)
            lib.family == null -> heldCounts.keys.sortedBy { st -> kotlin.math.abs(share.getValue(st) - 0.03 * n) }.take(3)
            else -> heldCounts.entries.sortedByDescending { it.value }.take(3).map { it.key }
        }
        for (novel in styles) {
            val members = all.filter { lib.style[it] == novel && lib.heldOut[it] }.shuffled(Random(150 + n + novel))
            val name = lib.styleName[novel] ?: "style-$novel"
            log("R2 lib=${lib.name} novel style=$novel name=$name heldOutMembers=${members.size} allMembers=${share[novel]} predictedFamilies=" +
                members.groupingBy { lib.family?.get(lib.ids[it]) ?: "other" }.eachCount().entries.joinToString(",") { "${it.key}:${it.value}" })
            for (p in listOf(0.5, 1.0, 2.0)) {
                val take = members.take(minOf(members.size, ceil(n * p / 100).toInt())).toSet()
                val after = all.filter { lib.style[it] != novel || it in take }
                val tag = if (mode == "gate") "novel-${name.replace(' ', '_')}-$p" else "novel$p"
                out += Scenario(tag, after, after.filter { it !in take })
            }
        }
        // Control for the family signal: +1 % uniform drawn only from held-out rows (must stay on the refresh).
        if (lib.family != null) {
            val pool = all.filter { lib.heldOut[it] }.shuffled(Random(160 + n))
            val added = pool.take(ceil(n / 100.0).toInt()).toSet()
            out += Scenario("heldout1", all, all.filter { it !in added })
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
            val o = overrides[row]
            if (o != null) o.copyInto(out, slot * dim) else lib.rows.copyInto(out, slot * dim, row * dim, (row + 1) * dim)
        }
        return out
    }

    // ------------------------------------------------------------ one scenario

    private fun runScenario(lib: Lib, s: Scenario, variants: List<Router>) {
        val dim = lib.dim
        val label = "lib=${lib.name} ${s.name}"
        val before = MapRefreshEngine.fullBuild(s.before.map(lib.ids::get), matrix(lib, s.before, s.beforeRows), dim, null).state
        val ids = s.after.map(lib.ids::get)
        val rows = matrix(lib, s.after)
        val n = ids.size
        val beforeSet = s.before.toSet()
        val flaggedRows = (0 until n).filter { val r = s.after[it]; r !in beforeSet || r in s.beforeRows }
        val flaggedSet = flaggedRows.toSet()
        val unchanged = (0 until n).filter { it !in flaggedSet }.map(ids::get)
        val results = variants.associateWith { MapRefreshEngine.update(ids, rows, dim, before, config(lib, it)) }
        for ((v, r) in results) log("R2 $label router=$v share=${f4(flaggedRows.size.toDouble() / n)} decision=${if (r.refreshed) "refresh" else "full"} reason=${r.reason} flagged=${r.flagged} novel=${r.novel} ms=${f1(r.ms)} phases=${r.phases.entries.joinToString(",") { "${it.key}:${f1(it.value)}" }}")
        val refreshed = results.filterValues { it.refreshed }
        if (refreshed.isEmpty() || s.name == "same" && mode == "pilot") return
        val today = MapRefreshEngine.fullBuild(ids, rows, dim, before)
        val reference = LibraryLayout.reduceForEmbedding(rows.copyOf(), n, dim)
        val k = reference.size / n
        val maps = linkedMapOf("today" to flatten(today.points))
        for ((v, r) in refreshed) maps["refresh-$v"] = flatten(r.points)
        var noise = Double.NaN
        if (mode == "gate") {
            val cold = MapRefreshEngine.fullBuild(ids, rows, dim, null)
            maps["cold"] = flatten(cold.points)
            // Tsne.initialPositions ORs the seed with 1, so odd offsets give distinct starts.
            for (offset in listOf(1, 3, 5)) maps["cold+$offset"] = BarnesHutTsne.embed(reference, n, k, MapRefreshEngine.SEED + offset)
            maps["approach1"] = approach1(ids, rows, dim, before)
        }
        val m = metrics(reference, k, IntArray(n) { lib.genre[s.after[it]] }, flaggedRows, maps)
        for ((name, row) in m) log("R2 $label metrics $name ${row.entries.joinToString(" ") { "${it.key}=${f4(it.value)}" }}")
        val sToday = stability(before.positions, today.points, unchanged)
        log("R2 $label today ms=${f1(today.ms)} kept=${f4(sToday.kept)} p90=${f4(sToday.p90)} median=${f4(sToday.median)}")
        if (mode == "gate") noise = listOf(1, 3, 5).map { m.getValue("cold+$it").getValue("overlapCold") }.average()
        for ((v, r) in refreshed) {
            val st = stability(before.positions, r.points, unchanged)
            val t = m.getValue("today")
            val q = m.getValue("refresh-$v")
            val rowsOut = linkedMapOf<String, Boolean>()
            rowsOut["trust"] = q.getValue("trust") >= t.getValue("trust") - 0.005
            rowsOut["recall"] = q.getValue("recall") >= t.getValue("recall") * 0.97
            rowsOut["class"] = q.getValue("class") >= t.getValue("class") - 0.010
            if (flaggedRows.isNotEmpty()) {
                rowsOut["RF"] = q.getValue("RF") >= t.getValue("RF") * 0.85
                rowsOut["CF"] = q.getValue("CF") >= t.getValue("CF") - 0.05
            }
            rowsOut["lost"] = (1 - st.kept) <= 0.5 * (1 - sToday.kept)
            rowsOut["p90move"] = st.p90 <= 0.5 * sToday.p90 && st.p90 <= 0.02
            rowsOut["timeHost"] = r.ms <= 0.25 * today.ms
            if (mode == "gate") {
                rowsOut["overlapCold"] = q.getValue("overlapCold") >= noise - 0.10
                rowsOut["staleBasis"] = q.getValue("recall") >= 0.97 * m.getValue("approach1").getValue("recall")
                val exact = BarnesHutTsne.nearestNeighbours(r.reducedRows!!, n, k, r.neighbours!!.index.size / n) { true }!!
                rowsOut["knnExact"] = exact.index.contentEquals(r.neighbours.index) && exact.distance.contentEquals(r.neighbours.distance)
            }
            log("R2 $label router=$v stability kept=${f4(st.kept)} p90=${f4(st.p90)} median=${f4(st.median)} ratio=${f4(r.ms / today.ms)} noise=${f4(noise)}")
            log("R2 $label router=$v verdict ${rowsOut.entries.joinToString(" ") { "${it.key}=${if (it.value) "PASS" else "FAIL"}" }}")
        }
    }

    /** Round 1's refresh (fresh PCA, exact 60-NN, same placement and iterations), the stale-basis reference. */
    private fun approach1(ids: List<TrackId>, rows: FloatArray, dim: Int, state: MapRefreshEngine.State): FloatArray {
        val n = ids.size
        val reduced = LibraryLayout.reduceForEmbedding(rows.copyOf(), n, dim)
        val k = reduced.size / n
        val kk = minOf(MapRefreshEngine.K, n - 1)
        val nb = BarnesHutTsne.nearestNeighbours(reduced, n, k, kk) { true }!!
        val flagged = BooleanArray(n) { i -> state.hashes[ids[i]]?.let { it != MapRefreshEngine.rowHash(rows, i, dim) } ?: true }
        val y = FloatArray(n * 2)
        var cx = 0f; var cy = 0f; var c = 0
        for (i in 0 until n) state.positions[ids[i]]?.let { cx += it[0]; cy += it[1]; c++ }
        cx /= c; cy /= c
        for (i in 0 until n) { val p = state.positions[ids[i]]; if (p != null && !flagged[i]) { y[i * 2] = p[0]; y[i * 2 + 1] = p[1] } else { y[i * 2] = cx; y[i * 2 + 1] = cy } }
        val factor = state.rawSpread / MapRefreshEngine.spread(y)
        for (i in y.indices) y[i] *= factor
        val start = y.copyOf()
        for (i in 0 until n) if (flagged[i]) {
            var x = 0f; var z = 0f; var t = 0
            for (s in 0 until kk) { val j = nb.index[i * kk + s]; if (flagged[j]) continue; x += start[j * 2]; z += start[j * 2 + 1]; if (++t == 5) break }
            if (t > 0) { y[i * 2] = x / t; y[i * 2 + 1] = z / t }
        }
        val p = BarnesHutTsne.jointProbabilities(nb, n, kk) { true }!!
        val e = MapRefreshEngine.optimize(p, n, y, MapRefreshEngine.REFRESH_ITERATIONS) { true }
        return flatten(LibraryLayout.normalize(ids, LibraryLayout.alignToPrevious(ids, e, state.positions, n), n))
    }

    // ------------------------------------------------------------ chain, probe

    private fun chain(lib: Lib, router: Router, budget: Double) {
        val dim = lib.dim
        val n = lib.n
        val random = Random(170 + n)
        val order = (0 until n).shuffled(random)
        val held = order.take((n * 0.12).toInt()).toMutableList()
        val current = order.drop((n * 0.12).toInt()).sorted().toMutableList()
        val step = n / 100
        var state = MapRefreshEngine.fullBuild(current.map(lib.ids::get), matrix(lib, current), dim, null).state
        val placedEver = HashSet<Int>()
        var full = 0
        for (s in 1..11) {
            if (s <= 10) { val add = held.take(step); held.removeAll(add.toSet()); current += add; current.sort(); placedEver += add }
            else { val remove = current.shuffled(random).take(current.size / 50).toSet(); current.removeAll(remove); placedEver.removeAll(remove) }
            val ids = current.map(lib.ids::get)
            val r = MapRefreshEngine.update(ids, matrix(lib, current), dim, state, config(lib, router, budget))
            if (!r.refreshed) full++
            state = r.state
            val exact = if (r.refreshed) BarnesHutTsne.nearestNeighbours(r.reducedRows!!, ids.size, r.reducedRows.size / ids.size, r.neighbours!!.index.size / ids.size) { true }!!.let {
                it.index.contentEquals(r.neighbours.index) && it.distance.contentEquals(r.neighbours.distance)
            } else null
            log("R2 chain lib=${lib.name} router=$router B=$budget step=$s n=${ids.size} decision=${if (r.refreshed) "refresh" else "full"} reason=${r.reason} ms=${f1(r.ms)} knnExact=${exact ?: "n/a"}")
        }
        val ids = current.map(lib.ids::get)
        val rows = matrix(lib, current)
        val m = ids.size
        val cold = MapRefreshEngine.fullBuild(ids, rows, dim, null)
        val reference = LibraryLayout.reduceForEmbedding(rows.copyOf(), m, dim)
        val placed = current.indices.filter { current[it] in placedEver }
        val chainMap = FloatArray(m * 2).also { out -> ids.forEachIndexed { i, id -> val p = state.positions.getValue(id); out[i * 2] = p[0]; out[i * 2 + 1] = p[1] } }
        val met = metrics(reference, reference.size / m, IntArray(m) { lib.genre[current[it]] }, placed, linkedMapOf("chain" to chainMap, "cold" to flatten(cold.points)))
        val c = met.getValue("chain"); val k = met.getValue("cold")
        val pass = c.getValue("trust") >= k.getValue("trust") - 0.005 && c.getValue("recall") >= k.getValue("recall") * 0.97 && c.getValue("RF") >= k.getValue("RF") * 0.85
        for ((name, row) in met) log("R2 chain lib=${lib.name} router=$router B=$budget metrics $name ${row.entries.joinToString(" ") { "${it.key}=${f4(it.value)}" }}")
        log("R2 chain lib=${lib.name} router=$router B=$budget fullBuilds=$full verdict=${if (pass) "PASS" else "FAIL"}")
    }

    private fun probe(lib: Lib, router: Router) {
        val dim = lib.dim
        val all = (0 until lib.n).toList()
        val added = all.shuffled(Random(180)).take(lib.n / 100).toSet()
        val before = all.filter { it !in added }
        val state = MapRefreshEngine.fullBuild(before.map(lib.ids::get), matrix(lib, before), dim, null).state
        val ids = all.map(lib.ids::get)
        val rows = matrix(lib, all)
        for (mode in listOf("today", "refresh", "today", "refresh")) {
            val disposable = rows.copyOf()  // allocated before the baseline; today (or a rejected refresh) consumes it in place
            var outcome = "today"
            val peak = peakHeap {
                if (mode == "today") MapRefreshEngine.fullBuild(ids, disposable, dim, state, withState = false)
                else MapRefreshEngine.update(ids, disposable, dim, state, Config(router, family = lib.family, buildState = false)).also { outcome = if (it.refreshed) "refresh" else "full:${it.reason}" }
            }
            val held = if (mode == "today") 0L else state.productionBytes()
            log("R2 probe lib=${lib.name} heap mode=$mode outcome=$outcome peakAboveBaselineMb=${f1(peak / 1048576.0)} plusStoredStateMb=${f1(held / 1048576.0)} totalMb=${f1((peak + held) / 1048576.0)}")
        }
        val reference = MapRefreshEngine.update(ids, rows, dim, state, config(lib, router))
        log("R2 probe lib=${lib.name} phases ${reference.phases.entries.joinToString(" ") { "${it.key}=${f1(it.value)}" }} total=${f1(reference.ms)}")
        // Deadlines spread over the refresh's phases; latency is measured from the deadline itself.
        var at = 0.0
        val deadlines = mutableListOf<Long>()
        for ((_, ms) in reference.phases) { at += ms; deadlines += maxOf(1L, (at - ms / 2).toLong()) }
        for (deadline in deadlines.distinct()) {
            val start = System.nanoTime()
            val due = start + deadline * 1_000_000
            var outcome = "completed"
            try { MapRefreshEngine.update(ids, rows, dim, state, config(lib, router)) { System.nanoTime() < due } } catch (_: MapRefreshEngine.Cancelled) { outcome = "cancelled" } catch (e: Throwable) { outcome = "stopped:${e::class.simpleName}" }
            val end = System.nanoTime()
            log("R2 probe lib=${lib.name} cancel at=${deadline}ms outcome=$outcome latencyFromDeadlineMs=${f1((end - due) / 1e6)}")
        }
    }

    private fun peakHeap(block: () -> Unit): Long {
        val memory = ManagementFactory.getMemoryMXBean()
        repeat(3) { System.gc() }
        val baseline = memory.heapMemoryUsage.used
        val peak = java.util.concurrent.atomic.AtomicLong(baseline)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread { while (running.get()) { peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf); Thread.sleep(1) } }
        sampler.isDaemon = true; sampler.start(); block(); running.set(false); sampler.join()
        return peak.get() - baseline
    }

    // ------------------------------------------------------------ metrics

    private fun metrics(reference: FloatArray, k: Int, labels: IntArray, flaggedRows: List<Int>, maps: LinkedHashMap<String, FloatArray>): Map<String, Map<String, Double>> {
        val n = labels.size
        val input = neighbours(reference, n, k, 15)
        val mapNb = maps.mapValues { (_, map) -> neighbours(map, n, 2, 15) }
        val cold = mapNb["cold"]
        val penalty = DoubleArray(maps.size)
        val rank = IntArray(n)
        val packed = LongArray(n)
        for (i in 0 until n) {
            sorted(reference, n, k, i, packed)
            for (pos in 1 until n) rank[packed[pos].toInt()] = pos
            mapNb.values.forEachIndexed { m, nb -> for (j in nb[i]) penalty[m] += maxOf(0, rank[j] - 15).toDouble() }
        }
        return mapNb.entries.withIndex().associate { (m, e) ->
            val nb = e.value
            fun share(rowsOf: List<Int>, hit: (Int, Int) -> Boolean) = if (rowsOf.isEmpty()) Double.NaN else rowsOf.sumOf { i -> nb[i].count { hit(i, it) } / 15.0 } / rowsOf.size
            e.key to linkedMapOf(
                "trust" to 1.0 - 2.0 / (n * 15 * (2.0 * n - 45 - 1.0)) * penalty[m],
                "recall" to overlap(input, nb),
                "class" to share((0 until n).toList()) { i, j -> labels[i] == labels[j] },
                "RF" to share(flaggedRows) { i, j -> j in input[i] },
                "CF" to share(flaggedRows) { i, j -> labels[i] == labels[j] },
                "overlapCold" to (cold?.let { overlap(it, nb) } ?: Double.NaN),
            )
        }
    }

    private class Stability(val kept: Double, val median: Double, val p90: Double)

    private fun stability(before: Map<TrackId, FloatArray>, after: List<LayoutPoint>, unchanged: List<TrackId>): Stability {
        val afterById = after.associateBy { it.trackId }
        val a = FloatArray(unchanged.size * 2); val b = FloatArray(unchanged.size * 2); val moved = DoubleArray(unchanged.size)
        unchanged.forEachIndexed { s, id ->
            val p = before.getValue(id); val q = afterById.getValue(id)
            a[s * 2] = p[0]; a[s * 2 + 1] = p[1]; b[s * 2] = q.x; b[s * 2 + 1] = q.y
            val dx = (q.x - p[0]).toDouble(); val dy = (q.y - p[1]).toDouble(); moved[s] = sqrt(dx * dx + dy * dy)
        }
        moved.sort()
        return Stability(overlap(neighbours(a, unchanged.size, 2, 15), neighbours(b, unchanged.size, 2, 15)), moved[moved.size / 2], moved[moved.size * 9 / 10])
    }

    private fun flatten(points: List<LayoutPoint>) = FloatArray(points.size * 2).also { o -> points.forEachIndexed { i, p -> o[i * 2] = p.x; o[i * 2 + 1] = p.y } }

    private fun sorted(points: FloatArray, n: Int, dim: Int, i: Int, out: LongArray) {
        for (j in 0 until n) {
            var sum = 0f
            if (j != i) for (d in 0 until dim) { val delta = points[i * dim + d] - points[j * dim + d]; sum += delta * delta }
            out[j] = ((if (j == i) -1L else sum.toRawBits().toLong()) shl 32) or j.toLong()
        }
        out.sort()
    }

    private fun neighbours(points: FloatArray, n: Int, dim: Int, k: Int): Array<IntArray> {
        val packed = LongArray(n)
        return Array(n) { i -> sorted(points, n, dim, i, packed); IntArray(k) { (packed[it + 1] and 0xFFFFFFFFL).toInt() } }
    }

    private fun overlap(a: Array<IntArray>, b: Array<IntArray>) = a.indices.sumOf { i -> b[i].count { it in a[i] } / 15.0 } / a.size

    private fun log(line: String) { println(line); logFile?.appendText(line + "\n") }
    private fun f1(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)
    private fun f4(v: Double) = String.format(java.util.Locale.ROOT, "%.4f", v)
}
