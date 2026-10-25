/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Piece 1 check of docs/map-refresh-checks.md, through the production [LibraryLayoutRefresh]. Opt-in:
 * `MAP_CHECK=1 MAP_CHECK_LOG=<file> [MAP_CHECK_SEEDS=37,41,43] [MAP_CHECK_FMA=<draw dir>,...]
 * ./gradlew :core:smart:testAndroidHostTest --tests "*MapRefreshCheck*" --rerun`. Public data only.
 */
class MapRefreshCheck {
    private val scale = MapScaleReplay()
    private val logFile = System.getenv("MAP_CHECK_LOG")?.let(::File)

    private class Lib(val name: String, val ids: List<TrackId>, val rows: FloatArray, val dim: Int, val source: LibraryVectorSource,
                      val genre: IntArray, val style: IntArray, val heldOut: BooleanArray?) { val n get() = ids.size }

    private class Case(val name: String, val after: List<Int>, val before: List<Int>, val beforeRows: Map<Int, FloatArray> = emptyMap())

    @Test
    fun `piece 1 refresh check`() = runBlocking {
        if (System.getenv("MAP_CHECK") != "1") return@runBlocking
        val libs = mutableListOf<() -> Lib>()
        System.getenv("MAP_CHECK_SEEDS")?.split(",")?.filter(String::isNotBlank)?.forEach { s -> libs += { synthetic(s.toInt()) } }
        System.getenv("MAP_CHECK_FMA")?.split(",")?.filter(String::isNotBlank)?.forEach { d -> libs += { fma(File(d)) } }
        for (make in libs) {
            val lib = make()
            log("P1 lib=${lib.name} n=${lib.n} source=${lib.source}")
            for (c in cases(lib)) runCase(lib, c)
            chain(lib)
        }
    }

    private fun synthetic(seed: Int): Lib {
        val f = scale.hierarchicalFixture(10_000, seed)
        return Lib("synthetic-s$seed", f.ids, f.rows, f.dim, LibraryVectorSource.AUDIO,
            IntArray(f.ids.size) { if (f.labels[it] < 48) f.labels[it] else -1 - it },
            IntArray(f.ids.size) { if (f.labels[it] < 48) f.labels[it] else -1 }, null)
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
        return Lib("fma-${dir.parentFile.name}", ids, space.takeRows(), space.dim, space.source,
            IntArray(ids.size) { i -> cells.getValue(ids[i])[1].let { if (it.isEmpty()) -1 - i else g.getOrPut(it) { g.size } } },
            IntArray(ids.size) { i -> cells.getValue(ids[i])[2].let { if (it.isEmpty()) -1 else st.getOrPut(it) { st.size } } },
            BooleanArray(ids.size) { i -> cells.getValue(ids[i]).getOrElse(3) { "" } in setOf("validation", "test") })
    }

    private fun cases(lib: Lib): List<Case> {
        val n = lib.n
        val all = (0 until n).toList()
        val out = mutableListOf<Case>()
        fun pick(p: Double, salt: Int, from: List<Int> = all) = from.shuffled(Random(salt * 7919 + n + 3000)).take(ceil(n * p / 100).toInt())
        for ((i, p) in listOf(0.5, 1.0, 2.0).withIndex()) {
            val added = pick(p, 210 + i).toSet()
            out += Case("add$p", all, all.filter { it !in added })
        }
        for ((i, p) in listOf(0.5, 1.0, 2.0).withIndex()) {
            val random = Random(230 + i)
            out += Case("change$p", all, all, pick(p, 220 + i).associateWith { perturbed(lib, it, random.nextInt(n)) })
        }
        run {
            val random = Random(240 + n)
            val byStyle = all.filter { lib.style[it] >= 0 }.groupBy { lib.style[it] }.filterValues { it.size >= 20 }
            val styles = byStyle.keys.sorted()
            val added = LinkedHashSet<Int>()
            while (added.size < n / 100) added += byStyle.getValue(styles[random.nextInt(styles.size)]).filter { it !in added }.shuffled(random).take(10 + random.nextInt(11))
            out += Case("album1", all, all.filter { it !in added })
        }
        lib.heldOut?.let { held ->
            val added = pick(1.0, 250, all.filter { held[it] }).toSet()
            out += Case("heldout1", all, all.filter { it !in added })
        }
        return out
    }

    private fun perturbed(lib: Lib, row: Int, other: Int): FloatArray {
        val out = FloatArray(lib.dim) { 0.5f * lib.rows[row * lib.dim + it] + 0.5f * lib.rows[other * lib.dim + it] }
        val norm = sqrt(out.sumOf { it.toDouble() * it }).toFloat()
        for (d in 0 until lib.dim) out[d] /= norm
        return out
    }

    private fun matrix(lib: Lib, indices: List<Int>, overrides: Map<Int, FloatArray> = emptyMap()) = FloatArray(indices.size * lib.dim).also { out ->
        indices.forEachIndexed { slot, row -> overrides[row]?.copyInto(out, slot * lib.dim) ?: lib.rows.copyInto(out, slot * lib.dim, row * lib.dim, (row + 1) * lib.dim) }
    }

    private fun rowsOf(lib: Lib, ids: List<TrackId>, m: FloatArray) = LibraryVectorRows(ids, lib.dim, lib.source, m.contentHashCode().toLong(),
        readChunk = { start, count, out -> m.copyInto(out, 0, start * lib.dim, (start + count) * lib.dim) }, verify = { true })

    private fun stateOf(s: MapRefreshSnapshot) = MapRefreshStateCodec.read(ArrayMapRefreshBytes(ArrayMapRefreshSink().also { MapRefreshStateCodec.write(s, it) }.toByteArray()))!!

    private fun positions(points: List<LayoutPoint>) = points.associate { it.trackId to floatArrayOf(it.x, it.y) }

    private suspend fun runCase(lib: Lib, c: Case) {
        val label = "lib=${lib.name} ${c.name}"
        val beforeIds = c.before.map(lib.ids::get)
        val built = LibraryLayoutRefresh.build(rowsOf(lib, beforeIds, matrix(lib, c.before, c.beforeRows)), emptyMap(), "check")!!
        val previous = positions(built.points)
        val ids = c.after.map(lib.ids::get)
        val m = matrix(lib, c.after)
        val n = ids.size
        val start = System.nanoTime()
        val refreshed = LibraryLayoutRefresh.refresh(rowsOf(lib, ids, m), previous, stateOf(built.snapshot!!), "check")
        val refreshMs = (System.nanoTime() - start) / 1e6
        if (refreshed == null) { log("P1 $label decision=declined"); return }
        val todayStart = System.nanoTime()
        val today = LibraryLayout.compute(rowsOf(lib, ids, m), previous)!!
        val todayMs = (System.nanoTime() - todayStart) / 1e6
        val beforeSet = c.before.toSet()
        val flagged = (0 until n).filter { val r = c.after[it]; r !in beforeSet || r in c.beforeRows }
        val unchanged = (0 until n).filter { it !in flagged.toSet() }.map(ids::get)
        val reference = LibraryLayout.reduceForEmbedding(m.copyOf(), n, lib.dim)
        val k = reference.size / n
        val maps = linkedMapOf("today" to flatten(today), "refresh" to flatten(refreshed.points), "cold" to flatten(LibraryLayout.compute(rowsOf(lib, ids, m))!!))
        for (offset in listOf(1, 3, 5, 7, 9)) maps["band+$offset"] = BarnesHutTsne.embed(reference, n, k, 0x1A7E27 + offset)
        val met = metrics(reference, k, IntArray(n) { lib.genre[c.after[it]] }, flagged, maps)
        val sT = stability(previous, today, unchanged); val sR = stability(previous, refreshed.points, unchanged)
        val snap = refreshed.snapshot!!
        val exact = BarnesHutTsne.nearestNeighbours(snap.reduced, n, snap.components, snap.neighbours) { true }!!.index.contentEquals(snap.neighbourIds)
        val t = met.getValue("today"); val r = met.getValue("refresh")
        val bandOverlap = listOf(1, 3, 5, 7, 9).map { met.getValue("band+$it").getValue("overlapCold") }
        val bandCF = listOf(1, 3, 5, 7, 9).map { met.getValue("band+$it").getValue("CF") }
        for ((name, row) in met) log("P1 $label metrics $name ${row.entries.joinToString(" ") { "${it.key}=${f4(it.value)}" }}")
        val rows = linkedMapOf(
            "trust" to (r.getValue("trust") >= t.getValue("trust") - 0.005),
            "recall" to (r.getValue("recall") >= t.getValue("recall") * 0.97),
            "class" to (r.getValue("class") >= t.getValue("class") - 0.010),
            "RF" to (r.getValue("RF") >= t.getValue("RF") * 0.85),
            "lost" to ((1 - sR.kept) <= 0.5 * (1 - sT.kept)),
            "p90move" to (sR.p90 <= 0.5 * sT.p90 && sR.p90 <= 0.02),
            "knnExact" to exact,
            "overlapCaseBand" to (r.getValue("overlapCold") >= bandOverlap.min() - 0.02),
            "cfCaseBand" to (r.getValue("CF") >= bandCF.min() - 0.02),
        )
        val share = flagged.size.toDouble() / n
        log("P1 $label decision=refreshed stale=${refreshed.stale} changed=${refreshed.changedSinceFull} share=${f4(share)} refreshMs=${f1(refreshMs)} todayMs=${f1(todayMs)} " +
            "kept=${f4(sR.kept)}/${f4(sT.kept)} p90=${f4(sR.p90)}/${f4(sT.p90)} overlap=${f4(r.getValue("overlapCold"))} bandOverlapMin=${f4(bandOverlap.min())} bandOverlapMean=${f4(bandOverlap.average())} " +
            "cf=${f4(r.getValue("CF"))} bandCfMin=${f4(bandCF.min())} bandCfMean=${f4(bandCF.average())}")
        log("P1 $label verdict ${rows.entries.joinToString(" ") { "${it.key}=${if (it.value) "PASS" else "FAIL"}" }}")
    }

    private suspend fun chain(lib: Lib) {
        val n = lib.n
        val random = Random(270 + n)
        val order = (0 until n).shuffled(random)
        val held = order.take((n * 0.12).toInt()).toMutableList()
        val current = order.drop((n * 0.12).toInt()).sorted().toMutableList()
        val step = n / 200
        var built = LibraryLayoutRefresh.build(rowsOf(lib, current.map(lib.ids::get), matrix(lib, current)), emptyMap(), "check")!!
        var positions = positions(built.points)
        var state = stateOf(built.snapshot!!)
        val placed = HashSet<Int>()
        var all = true
        var rebuilds = 0
        for (s in 1..20) {
            val add = held.take(step); held.removeAll(add.toSet()); current += add; current.sort(); placed += add
            val ids = current.map(lib.ids::get)
            val m = matrix(lib, current)
            val r = LibraryLayoutRefresh.refresh(rowsOf(lib, ids, m), positions, state, "check")
            if (r == null) { log("P1 chain lib=${lib.name} step=$s declined"); all = false; break }
            val snap = r.snapshot!!
            val exact = BarnesHutTsne.nearestNeighbours(snap.reduced, ids.size, snap.components, snap.neighbours) { true }!!.index.contentEquals(snap.neighbourIds)
            all = all && exact
            var verdict = "n/a"
            if (r.stale || s == 20) {
                val reference = LibraryLayout.reduceForEmbedding(m.copyOf(), ids.size, lib.dim)
                val cold = LibraryLayout.compute(rowsOf(lib, ids, m))!!
                val placedRows = current.indices.filter { current[it] in placed }
                val met = metrics(reference, reference.size / ids.size, IntArray(ids.size) { lib.genre[current[it]] }, placedRows,
                    linkedMapOf("waiting" to flatten(r.points), "cold" to flatten(cold)))
                val w = met.getValue("waiting"); val c = met.getValue("cold")
                val pass = w.getValue("trust") >= c.getValue("trust") - 0.005 && w.getValue("recall") >= c.getValue("recall") * 0.97 &&
                    (placedRows.isEmpty() || w.getValue("RF") >= c.getValue("RF") * 0.85)
                all = all && pass
                verdict = if (pass) "PASS" else "FAIL"
                log("P1 chain lib=${lib.name} step=$s waiting ${w.entries.joinToString(" ") { "${it.key}=${f4(it.value)}" }} cold ${c.entries.joinToString(" ") { "${it.key}=${f4(it.value)}" }}")
            }
            log("P1 chain lib=${lib.name} step=$s n=${ids.size} changed=${r.changedSinceFull} stale=${r.stale} knnExact=$exact check=$verdict")
            positions = positions(r.points)
            state = stateOf(snap)
            if (r.stale) {
                built = LibraryLayoutRefresh.build(rowsOf(lib, ids, m), positions, "check")!!
                positions = positions(built.points)
                state = stateOf(built.snapshot!!)
                placed.clear(); rebuilds++
            }
        }
        log("P1 chain lib=${lib.name} rebuilds=$rebuilds verdict=${if (all) "PASS" else "FAIL"}")
    }

    private fun metrics(reference: FloatArray, k: Int, labels: IntArray, flagged: List<Int>, maps: LinkedHashMap<String, FloatArray>): Map<String, Map<String, Double>> {
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
                "RF" to share(flagged) { i, j -> j in input[i] },
                "CF" to share(flagged) { i, j -> labels[i] == labels[j] },
                "overlapCold" to (cold?.let { overlap(it, nb) } ?: Double.NaN),
            )
        }
    }

    private class Stability(val kept: Double, val p90: Double)

    private fun stability(before: Map<TrackId, FloatArray>, after: List<LayoutPoint>, unchanged: List<TrackId>): Stability {
        val afterById = after.associateBy { it.trackId }
        val a = FloatArray(unchanged.size * 2); val b = FloatArray(unchanged.size * 2); val moved = DoubleArray(unchanged.size)
        unchanged.forEachIndexed { s, id ->
            val p = before.getValue(id); val q = afterById.getValue(id)
            a[s * 2] = p[0]; a[s * 2 + 1] = p[1]; b[s * 2] = q.x; b[s * 2 + 1] = q.y
            val dx = (q.x - p[0]).toDouble(); val dy = (q.y - p[1]).toDouble(); moved[s] = sqrt(dx * dx + dy * dy)
        }
        moved.sort()
        return Stability(overlap(neighbours(a, unchanged.size, 2, 15), neighbours(b, unchanged.size, 2, 15)), moved[moved.size * 9 / 10])
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
