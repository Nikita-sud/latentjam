/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Piece 4 of docs/map-refresh-checks.md: region identity through [RegionIdentity] against a fresh
 * rediscovery. `MAP_REGIONS=1 MAP_REGIONS_MODE=pilot|check MAP_REGIONS_LOG=<file> [MAP_REGIONS_SEEDS=..]
 * [MAP_REGIONS_FMA=<draw dir>,..] [MAP_REGIONS_DELTAS=0,0.02,..]`. Public data only.
 */
class MapRegionIdentityReplay {
    private val scale = MapScaleReplay()
    private val mode = System.getenv("MAP_REGIONS_MODE") ?: "pilot"
    private val logFile = System.getenv("MAP_REGIONS_LOG")?.let(::File)

    private class Lib(val name: String, val ids: List<TrackId>, val rows: FloatArray, val dim: Int, val source: LibraryVectorSource,
                      val genre: IntArray, val style: IntArray, val heldOut: BooleanArray?, val descriptors: Map<TrackId, TrackDescriptor>,
                      val hybridAudioDim: Int = 0, val hybridMetadataWeight: Float = Float.NaN, val styleName: (Int) -> String = { "s$it" }) { val n get() = ids.size }

    private class Case(val name: String, val after: List<Int>, val before: List<Int>, val beforeRows: Map<Int, FloatArray> = emptyMap(), val novel: Boolean = false)

    @Test
    fun `region identity`() = kotlinx.coroutines.runBlocking {
        if (System.getenv("MAP_REGIONS") != "1") return@runBlocking
        val deltas = (System.getenv("MAP_REGIONS_DELTAS") ?: "0.05").split(",").map(String::toFloat)
        val libs = mutableListOf<() -> Lib>()
        System.getenv("MAP_REGIONS_SEEDS")?.split(",")?.filter(String::isNotBlank)?.forEach { s -> libs += { synthetic(s.toInt()) } }
        System.getenv("MAP_REGIONS_FMA")?.split(",")?.filter(String::isNotBlank)?.forEach { d -> libs += { fma(File(d)) } }
        for (make in libs) {
            val lib = make()
            log("R4 lib=${lib.name} n=${lib.n} mode=$mode")
            for (c in cases(lib)) for (switch in listOf(false, true)) runCase(lib, c, deltas, switch)
            for (d in deltas) for (switch in listOf(false, true)) chain(lib, d, switch)
        }
    }

    private fun synthetic(seed: Int): Lib {
        val f = scale.hierarchicalFixture(10_000, seed)
        return Lib("synthetic-s$seed", f.ids, f.rows, f.dim, LibraryVectorSource.AUDIO,
            IntArray(f.ids.size) { if (f.labels[it] < 48) f.labels[it] else -1 - it },
            IntArray(f.ids.size) { if (f.labels[it] < 48) f.labels[it] else -1 }, null, f.ids.associateWith { TrackDescriptor(it) })
    }

    private fun fma(dir: File): Lib {
        val (audioDim, audio) = scale.readNpy(File(dir, "smart_index_vecs.npy"))
        val (textDim, text) = scale.readNpy(File(dir, "smart_text_vecs.npy"))
        val audioIds = File(dir, "smart_index_ids.txt").readLines().filter(String::isNotBlank).map(::TrackId)
        val textIds = File(dir, "smart_text_ids.txt").readLines().filter(String::isNotBlank).map(::TrackId)
        val space = requireNotNull(LibraryVectorFusion.build(audioIds, audioIds.zip(audio).toMap(), textIds.zip(text).toMap(), audioDim, textDim))
        val cells = File(dir, "labels.tsv").readLines().filter(String::isNotBlank).associate { l -> l.split("\t").let { TrackId(it[0]) to it } }
        val desc = File(dir, "descriptors.tsv").takeIf(File::isFile)?.readLines()?.filter(String::isNotBlank)?.associate { l ->
            val c = l.split("\t")
            TrackId(c[0]) to TrackDescriptor(TrackId(c[0]), title = c.getOrNull(1)?.ifEmpty { null }, artist = c.getOrNull(2)?.ifEmpty { null },
                album = c.getOrNull(3)?.ifEmpty { null }, genre = c.getOrNull(4)?.ifEmpty { null }, year = c.getOrNull(5)?.toIntOrNull())
        }.orEmpty()
        val ids = space.trackIds
        val hybridDim = space.hybridAudioDim; val hybridWeight = space.hybridMetadataWeight
        val g = HashMap<String, Int>(); val st = HashMap<String, Int>()
        return Lib("fma-${dir.parentFile.name}", ids, space.takeRows(), space.dim, space.source,
            IntArray(ids.size) { i -> cells.getValue(ids[i])[1].let { if (it.isEmpty()) -1 - i else g.getOrPut(it) { g.size } } },
            IntArray(ids.size) { i -> cells.getValue(ids[i])[2].let { if (it.isEmpty()) -1 else st.getOrPut(it) { st.size } } },
            BooleanArray(ids.size) { i -> cells.getValue(ids[i]).getOrElse(3) { "" } in setOf("validation", "test") },
            ids.associateWith { desc[it] ?: TrackDescriptor(it) }, hybridDim, hybridWeight, styleName = { id -> st.entries.first { it.value == id }.key })
    }

    private fun cases(lib: Lib): List<Case> {
        val n = lib.n
        val all = (0 until n).toList()
        fun pick(p: Double, salt: Int, from: List<Int> = all) = from.shuffled(Random(salt * 7919 + n + 5000)).take(ceil(n * p / 100).toInt())
        val out = mutableListOf<Case>()
        for ((i, p) in listOf(1.0, 2.0, 5.0).withIndex()) { val a = pick(p, 310 + i).toSet(); out += Case("add${p.toInt()}", all, all.filter { it !in a }) }
        run { val random = Random(330); out += Case("change2", all, all, pick(2.0, 320).associateWith { perturbed(lib, it, random.nextInt(n)) }) }
        run {
            val random = Random(340 + n)
            val byStyle = all.filter { lib.style[it] >= 0 }.groupBy { lib.style[it] }.filterValues { it.size >= 20 }
            val styles = byStyle.keys.sorted()
            val added = LinkedHashSet<Int>()
            while (added.size < n / 100) added += byStyle.getValue(styles[random.nextInt(styles.size)]).filter { it !in added }.shuffled(random).take(10 + random.nextInt(11))
            out += Case("album1", all, all.filter { it !in added })
        }
        // A new style: one style absent before (its 2 % most-held-out members added; FMA: held-out split only).
        val share = all.filter { lib.style[it] >= 0 }.groupingBy { lib.style[it] }.eachCount()
        val heldCount = all.filter { lib.style[it] >= 0 && (lib.heldOut?.get(it) ?: true) }.groupingBy { lib.style[it] }.eachCount()
        // The pilot's chosen style is excluded from the check (review round 1, issue 7): MAP_REGIONS_EXCLUDE_STYLE.
        val excluded = (System.getenv("MAP_REGIONS_EXCLUDE_STYLE") ?: "").split(",").filter(String::isNotBlank).toSet()
        val style = heldCount.keys.filter { lib.styleName(it) !in excluded }.minByOrNull { kotlin.math.abs(share.getValue(it) - 0.03 * n) }
        if (style != null) {
            log("R4 lib=${lib.name} novel style=${lib.styleName(style)} share=${share.getValue(style)}")
            val members = all.filter { lib.style[it] == style && (lib.heldOut?.get(it) ?: true) }.shuffled(Random(350 + n))
            val take = members.take(minOf(members.size, ceil(0.02 * n).toInt())).toSet()
            val after = all.filter { lib.style[it] != style || it in take }
            out += Case("novel", after, after.filter { it !in take }, novel = true)
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

    /** Today's discovery; [kmeans] forces the budget fallback (TrackClustering), the path a 256 MB device takes at 10,000. */
    private fun discover(lib: Lib, ids: List<TrackId>, m: FloatArray, kmeans: Boolean): List<LibraryWorld> =
        LibraryWorlds.discover(ids.map { lib.descriptors.getValue(it) }, LibraryVectorSpace(ids, m.copyOf(), lib.dim, lib.source).also {
            it.hybridAudioDim = lib.hybridAudioDim; it.hybridMetadataWeight = lib.hybridMetadataWeight
        }, emptyMap(), isActive = { true }, workBudgetBytes = if (kmeans) 1L else Long.MAX_VALUE)

    private fun rowsMap(ids: List<TrackId>, m: FloatArray, dim: Int) = LibraryVectorRows(ids, dim, LibraryVectorSource.AUDIO, m.contentHashCode().toLong(),
        { start, count, out -> m.copyInto(out, 0, start * dim, (start + count) * dim) }, { true })

    private suspend fun runCase(lib: Lib, c: Case, deltas: List<Float>, switch: Boolean) {
        val label = "lib=${lib.name} ${c.name} switch=$switch"
        val beforeIds = c.before.map(lib.ids::get)
        val beforeRows = matrix(lib, c.before, c.beforeRows)
        val ids = c.after.map(lib.ids::get)
        val m = matrix(lib, c.after)
        val beforeSet = c.before.toSet()
        val flagged = (0 until ids.size).filter { val r = c.after[it]; r !in beforeSet || r in c.beforeRows }.map(ids::get).toSet()
        val settled = ids.filter { it !in flagged }.toSet()
        val rowBefore = rowsMap(beforeIds, beforeRows, lib.dim)
        val rowAfter = rowsMap(ids, m, lib.dim)
        val initial = RegionIdentity.initial(discover(lib, beforeIds, beforeRows, kmeans = false), rowBefore)
        val joined = RegionIdentity.join(initial, flagged.map { lib.descriptors.getValue(it) }, ids.toSet(), rowAfter)
        val fresh = discover(lib, ids, m, kmeans = switch)
        val genreOf = ids.withIndex().associate { it.value to lib.genre[c.after[it.index]] }
        val freshRegions = RegionIdentity.initial(fresh, rowAfter)
        val pFresh = purity(freshRegions, genreOf); val covFresh = coverage(freshRegions, ids.size); val ownFresh = own(freshRegions, flagged, c.novel)
        val beforeOf = membership(initial)
        val today = todayMembership(initial, fresh, settled)
        val freshOf = membership(freshRegions)
        // Placed tracks: new or changed, or settled without a region before. Their agreement with their
        // region's majority label, against the same tracks in the fresh map of the same path.
        val placed = ids.filter { it in flagged || beforeOf[it] == null }.toSet()
        val agreeFresh = agreement(freshOf, genreOf, placed)
        // For contrast only on the switch: the average-linkage fresh map of the after library.
        val alFresh = if (switch) RegionIdentity.initial(discover(lib, ids, m, kmeans = false), rowAfter) else null
        log("R4 $label fresh regions=${fresh.size} purity=${f4(pFresh)} coverage=${f4(covFresh)} own=${f4(ownFresh)} placed=${placed.size} placedAgreeFresh=${f4(agreeFresh)} " +
            "todayMoved=${f4(churn(beforeOf, today, settled))} todayPerRegion ${distribution(beforeOf, today, settled)} joinedCoverage=${f4(coverage(joined, ids.size))}" +
            (alFresh?.let { " averageLinkageFresh regions=${it.size} purity=${f4(purity(it, genreOf))} placedAgree=${f4(agreement(membership(it), genreOf, placed))}" } ?: ""))
        for (delta in deltas) {
            val after = RegionIdentity.rediscover(joined, fresh, settled, rowAfter, delta)
            val afterOf = membership(after)
            val churn = churn(beforeOf, afterOf, settled)
            val newRegions = after.filter { r -> initial.none { it.id == r.id } }.map { it.world.tracks.size }
            val p = purity(after, genreOf); val cov = coverage(after, ids.size); val ow = own(after, flagged, c.novel)
            val agree = agreement(afterOf, genreOf, placed)
            val ownVerdict = when { !c.novel -> "PASS"; ownFresh <= 0 -> "n/a"; else -> pf(ow >= ownFresh - 0.10) }
            log("R4 $label delta=$delta churn=${f4(churn)} gained=${f4(gained(beforeOf, afterOf, settled))} perRegion ${distribution(beforeOf, afterOf, settled)} purity=${f4(p)} coverage=${f4(cov)} own=${f4(ow)} regions=${after.size} freshRegions=${fresh.size} newRegions=${newRegions.size} newMin=${newRegions.minOrNull() ?: 0} " +
                "verdict purity=${pf(p >= pFresh - 0.01)} coverage=${pf(cov >= covFresh - 0.02)} churn=${pf(churn <= 0.02)} own=$ownVerdict placedAgree=${f4(agree)} placed=${pf(agree >= agreeFresh - 0.05)} minSize=${pf(newRegions.all { it >= TrackClustering.MIN_CLUSTER_SIZE })}")
        }
    }

    /** The 2 % chain of piece 1 with regions: join every step, rediscover at each stale step (δ given). */
    private suspend fun chain(lib: Lib, delta: Float, switch: Boolean) {
        val n = lib.n
        val random = Random(370 + n)
        val order = (0 until n).shuffled(random)
        val held = order.take((n * 0.12).toInt()).toMutableList()
        val current = order.drop((n * 0.12).toInt()).sorted().toMutableList()
        val step = n / 200
        var ids = current.map(lib.ids::get); var m = matrix(lib, current)
        var regions = RegionIdentity.initial(discover(lib, ids, m, kmeans = false), rowsMap(ids, m, lib.dim))
        val first = membership(regions)
        val settledAtStart = ids.toSet()
        val pending = HashSet<TrackId>()
        var rebuilds = 0
        for (s in 1..20) {
            val add = held.take(step); held.removeAll(add.toSet()); current += add; current.sort()
            ids = current.map(lib.ids::get); m = matrix(lib, current)
            val rows = rowsMap(ids, m, lib.dim)
            val addedIds = add.map(lib.ids::get)
            regions = RegionIdentity.join(regions, addedIds.map { lib.descriptors.getValue(it) }, ids.toSet(), rows)
            pending += addedIds
            if (pending.size > 0.02 * ids.size) {
                regions = RegionIdentity.rediscover(regions, discover(lib, ids, m, kmeans = switch), ids.filter { it !in pending }.toSet(), rows, delta)
                pending.clear(); rebuilds++
            }
        }
        val rows = rowsMap(ids, m, lib.dim)
        val fresh = RegionIdentity.initial(discover(lib, ids, m, kmeans = switch), rows)
        val genreOf = ids.withIndex().associate { it.value to lib.genre[current[it.index]] }
        val p = purity(regions, genreOf); val pf0 = purity(fresh, genreOf)
        val cov = coverage(regions, ids.size); val covF = coverage(fresh, ids.size)
        val churn = churn(first, membership(regions), settledAtStart.filter { it in ids.toSet() }.toSet())
        log("R4 chain lib=${lib.name} switch=$switch delta=$delta rebuilds=$rebuilds churnFromStart=${f4(churn)} purity=${f4(p)} fresh=${f4(pf0)} coverage=${f4(cov)} fresh=${f4(covF)} regions=${regions.size} freshRegions=${fresh.size} " +
            "verdict purity=${pf(p >= pf0 - 0.01)} coverage=${pf(cov >= covF - 0.02)} churn=${pf(churn <= 0.02)}")
    }

    private fun membership(regions: List<IdentityRegion>): Map<TrackId, Int> = HashMap<TrackId, Int>().also { m -> for (r in regions) for (t in r.world.tracks) m[t.id] = r.id }

    /**
     * Today's behaviour for contrast: the fresh worlds alone, each given the old id it shares most settled
     * members with (greedy, one to one). No stickiness and no keeping of tracks the fresh worlds leave out.
     */
    private fun todayMembership(old: List<IdentityRegion>, fresh: List<LibraryWorld>, settled: Set<TrackId>): Map<TrackId, Int> {
        val oldOf = membership(old)
        val pairs = fresh.flatMapIndexed { w, world ->
            world.tracks.filter { it.id in settled }.mapNotNull { oldOf[it.id] }.groupingBy { it }.eachCount().map { (r, c) -> Triple(c, w, r) }
        }.sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.first }.thenBy { it.second }.thenBy { it.third })
        val matchOf = HashMap<Int, Int>(); val used = HashSet<Int>()
        for ((_, w, r) in pairs) if (w !in matchOf && r !in used) { matchOf[w] = r; used += r }
        var next = (old.maxOfOrNull { it.id } ?: -1) + 1
        val out = HashMap<TrackId, Int>()
        fresh.forEachIndexed { w, world -> val id = matchOf[w] ?: next++; for (t in world.tracks) out[t.id] = id }
        return out
    }

    /** Among [tracks] that have a region in [of]: the share whose label is their region's majority label (over all its members). */
    private fun agreement(of: Map<TrackId, Int>, label: Map<TrackId, Int>, tracks: Set<TrackId>): Double {
        val majority = of.entries.groupBy({ it.value }, { label.getValue(it.key) }).mapValues { (_, l) -> l.groupingBy { it }.eachCount().maxBy { it.value }.key }
        val assigned = tracks.filter { of[it] != null }
        return if (assigned.isEmpty()) Double.NaN else assigned.count { label.getValue(it) == majority.getValue(of.getValue(it)) }.toDouble() / assigned.size
    }

    /** Moved: settled tracks that had a region and now have another one or none (over settled tracks). */
    private fun churn(a: Map<TrackId, Int>, b: Map<TrackId, Int>, tracks: Set<TrackId>) =
        if (tracks.isEmpty()) 0.0 else tracks.count { a[it] != null && a[it] != b[it] }.toDouble() / tracks.size

    /** Gained: settled tracks without a region before that have one now (coverage, not movement). */
    private fun gained(a: Map<TrackId, Int>, b: Map<TrackId, Int>, tracks: Set<TrackId>) =
        if (tracks.isEmpty()) 0.0 else tracks.count { a[it] == null && b[it] != null }.toDouble() / tracks.size

    /** Per old region, the share of its settled members that moved: p50 / p90 / max. */
    private fun distribution(a: Map<TrackId, Int>, b: Map<TrackId, Int>, tracks: Set<TrackId>): String {
        val shares = tracks.filter { a[it] != null }.groupBy { a.getValue(it) }.values.map { m -> m.count { a[it] != b[it] }.toDouble() / m.size }.sorted()
        if (shares.isEmpty()) return "p50=0 p90=0 max=0"
        fun q(f: Double) = shares[minOf(shares.size - 1, (f * shares.size).toInt())]
        return "p50=${f4(q(0.5))} p90=${f4(q(0.9))} max=${f4(shares.last())}"
    }

    private fun purity(regions: List<IdentityRegion>, label: Map<TrackId, Int>): Double {
        val total = regions.sumOf { it.world.tracks.size }
        return regions.sumOf { r -> r.world.tracks.groupingBy { label.getValue(it.id) }.eachCount().values.max().toDouble() } / total
    }

    private fun coverage(regions: List<IdentityRegion>, n: Int) = regions.sumOf { it.world.tracks.size }.toDouble() / n

    private fun own(regions: List<IdentityRegion>, flagged: Set<TrackId>, novel: Boolean): Double {
        if (!novel || flagged.isEmpty()) return Double.NaN
        return regions.maxOf { r -> val f = r.world.tracks.count { it.id in flagged }; if (f * 2 > r.world.tracks.size) f.toDouble() / flagged.size else 0.0 }
    }

    private fun pf(b: Boolean) = if (b) "PASS" else "FAIL"
    private fun log(line: String) { println(line); logFile?.appendText(line + "\n") }
    private fun f4(v: Double) = String.format(java.util.Locale.ROOT, "%.4f", v)
}
