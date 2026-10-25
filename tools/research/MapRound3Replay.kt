/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.MapRefreshEngine.Config
import io.github.nikitasud.latentjam.smart.cluster.MapRefreshEngine.Router
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Round 3 of the Map refresh research (docs/map-cost-research-2026-10-25.md, "Round 3"): EVERY change is refreshed
 * (approach 1 + 3 through [MapRefreshEngine] with no router), regions keep their identity (new tracks join the
 * nearest region; a deferred rediscovery is matched to the old regions by membership), and a full rebuild is
 * deferred until the map is marked stale (budget or a "maybe a new style" hint). Synthetic and public data only.
 *
 * Copy (with MapRefreshEngine.kt) into core/smart/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/.
 * Run: `MAP_R3_REPLAY=1 MAP_R3_MODE=gate|pilot [MAP_R3_SEEDS=..] [MAP_R3_FMA=<dir> MAP_R3_FMA_SIZES=..] [MAP_R3_PARTS=scenarios,chain]
 *   [MAP_R3_SCENARIOS=..] MAP_R3_LOG=<file> ./gradlew :core:smart:testAndroidHostTest --tests "*MapRound3Replay*" --rerun`
 */
class MapRound3Replay {
    private val scale = MapScaleReplay()
    private val mode = System.getenv("MAP_R3_MODE") ?: "pilot"
    private val logFile = System.getenv("MAP_R3_LOG")?.let(::File)

    private class Lib(
        val name: String, val ids: List<TrackId>, val rows: FloatArray, val dim: Int, val genre: IntArray, val style: IntArray,
        val heldOut: BooleanArray, val family: Map<TrackId, String?>?, val styleName: Map<Int, String>,
        val descriptors: Map<TrackId, TrackDescriptor>, val artist: Map<TrackId, String?>?,
    ) { val n get() = ids.size }

    private class Scenario(val name: String, val after: List<Int>, val before: List<Int>, val beforeRows: Map<Int, FloatArray> = emptyMap(), val novel: Boolean = false)

    /** Regions as identities: track -> region id, plus each region's unit centroid and acceptance floor. */
    private class Regions(val of: Map<TrackId, Int>, val names: Map<Int, String>, val centroid: Map<Int, FloatArray>, val floor: Map<Int, Float>)

    @Test
    fun `round 3`() {
        if (System.getenv("MAP_R3_REPLAY") != "1") return
        val parts = System.getenv("MAP_R3_PARTS")?.split(",")?.toSet() ?: setOf("scenarios", "chain")
        val only = System.getenv("MAP_R3_SCENARIOS")?.split(",")?.toSet()
        val libs = mutableListOf<() -> Lib>()
        System.getenv("MAP_R3_SEEDS")?.split(",")?.filter(String::isNotBlank)?.forEach { s -> libs += { synthetic(10_000, s.toInt()) } }
        System.getenv("MAP_R3_FMA")?.let { dir -> (System.getenv("MAP_R3_FMA_SIZES") ?: "5000").split(",").forEach { n -> libs += { fma(File(dir, "fma-$n")) } } }
        for (make in libs) {
            val lib = make()
            log("R3 lib=${lib.name} n=${lib.n} dim=${lib.dim} family=${lib.family != null} artists=${lib.artist != null} mode=$mode")
            if ("scenarios" in parts) for (s in scenarios(lib)) if (only == null || s.name in only) runScenario(lib, s)
            if ("chain" in parts) for (budget in listOf(0.05, 0.03)) chain(lib, budget)
        }
    }

    // ------------------------------------------------------------ data (as round 2, plus descriptors and artists)

    private fun synthetic(n: Int, seed: Int): Lib {
        val f = scale.hierarchicalFixture(n, seed)
        return Lib("synthetic-s$seed", f.ids, f.rows, f.dim,
            IntArray(n) { if (f.labels[it] < 48) f.labels[it] else -1 - it },
            IntArray(n) { if (f.labels[it] < 48) f.labels[it] else -1 }, BooleanArray(n) { true }, null,
            (0 until 48).associateWith { "style-$it" }, f.ids.associateWith { TrackDescriptor(it) }, null)
    }

    private fun fma(dir: File): Lib {
        val (audioDim, audio) = scale.readNpy(File(dir, "smart_index_vecs.npy"))
        val (textDim, text) = scale.readNpy(File(dir, "smart_text_vecs.npy"))
        val audioIds = File(dir, "smart_index_ids.txt").readLines().filter(String::isNotBlank).map(::TrackId)
        val textIds = File(dir, "smart_text_ids.txt").readLines().filter(String::isNotBlank).map(::TrackId)
        val space = requireNotNull(LibraryVectorFusion.build(audioIds, audioIds.zip(audio).toMap(), textIds.zip(text).toMap(), audioDim, textDim))
        val cells = File(dir, "labels.tsv").readLines().filter(String::isNotBlank).associate { l -> l.split("\t").let { TrackId(it[0]) to it } }
        val desc = File(dir, "descriptors.tsv").readLines().filter(String::isNotBlank).associate { l ->
            val c = l.split("\t")
            TrackId(c[0]) to TrackDescriptor(TrackId(c[0]), title = c.getOrNull(1)?.ifEmpty { null }, artist = c.getOrNull(2)?.ifEmpty { null },
                album = c.getOrNull(3)?.ifEmpty { null }, genre = c.getOrNull(4)?.ifEmpty { null }, year = c.getOrNull(5)?.toIntOrNull())
        }
        val ids = space.trackIds
        val g = HashMap<String, Int>(); val st = HashMap<String, Int>()
        return Lib(
            "fma-${dir.name.removePrefix("fma-")}-${dir.parentFile.name}", ids, space.takeRows(), space.dim,
            IntArray(ids.size) { i -> cells.getValue(ids[i])[1].let { if (it.isEmpty()) -1 - i else g.getOrPut(it) { g.size } } },
            IntArray(ids.size) { i -> cells.getValue(ids[i])[2].let { if (it.isEmpty()) -1 else st.getOrPut(it) { st.size } } },
            BooleanArray(ids.size) { i -> cells.getValue(ids[i]).getOrElse(3) { "" } in setOf("validation", "test") },
            ids.associateWith { id -> cells.getValue(id).getOrElse(4) { "" }.ifEmpty { null } },
            st.entries.associate { it.value to it.key }, ids.associateWith { desc[it] ?: TrackDescriptor(it) },
            ids.associateWith { desc[it]?.artist },
        )
    }

    private fun scenarios(lib: Lib): List<Scenario> {
        val n = lib.n
        val all = (0 until n).toList()
        val out = mutableListOf(Scenario("same", all, all))
        fun uniform(p: Double, salt: Int) = all.shuffled(Random(salt * 7919 + n + 1000)).take(ceil(n * p / 100).toInt())
        for ((i, p) in listOf(1.0, 2.0, 3.0, 5.0).withIndex()) {
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
            while (added.size < n / 100) added += byStyle.getValue(styles[random.nextInt(styles.size)]).filter { it !in added }.shuffled(random).take(10 + random.nextInt(11))
            out += Scenario("album1", all, all.filter { it !in added })
        }
        // Gate: the pilot's style is excluded. Pilot: the round-2 pilot rule (one style), which picks "Soundtrack" on FMA.
        val excluded = if (mode == "gate") setOf("Soundtrack") else emptySet()
        val heldCounts = all.filter { lib.style[it] >= 0 && lib.heldOut[it] }.groupingBy { lib.style[it] }.eachCount().filterKeys { lib.styleName[it] !in excluded }
        val share = all.filter { lib.style[it] >= 0 }.groupingBy { lib.style[it] }.eachCount()
        val styles = when {
            mode != "gate" -> listOfNotNull(heldCounts.filter { it.value >= ceil(0.02 * n) }.keys.minByOrNull { st -> kotlin.math.abs(share.getValue(st) - 0.03 * n) }
                ?: heldCounts.maxByOrNull { it.value }?.key)
            lib.family == null -> heldCounts.keys.sortedBy { st -> kotlin.math.abs(share.getValue(st) - 0.03 * n) }.take(3)
            else -> heldCounts.entries.sortedByDescending { it.value }.take(3).map { it.key }
        }
        for (novel in styles) {
            val members = all.filter { lib.style[it] == novel && lib.heldOut[it] }.shuffled(Random(150 + n + novel))
            val name = (lib.styleName[novel] ?: "style-$novel").replace(' ', '_')
            log("R3 lib=${lib.name} novel style=$name heldOutMembers=${members.size} allMembers=${share[novel]}")
            for (p in listOf(0.5, 1.0, 2.0)) {
                val take = members.take(minOf(members.size, ceil(n * p / 100).toInt())).toSet()
                val after = all.filter { lib.style[it] != novel || it in take }
                out += Scenario("novel-$name-$p", after, after.filter { it !in take }, novel = true)
            }
        }
        if (lib.family != null) {
            val added = all.filter { lib.heldOut[it] }.shuffled(Random(160 + n)).take(ceil(n / 100.0).toInt()).toSet()
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

    private fun matrix(lib: Lib, indices: List<Int>, overrides: Map<Int, FloatArray> = emptyMap()) = FloatArray(indices.size * lib.dim).also { out ->
        indices.forEachIndexed { slot, row -> overrides[row]?.copyInto(out, slot * lib.dim) ?: lib.rows.copyInto(out, slot * lib.dim, row * lib.dim, (row + 1) * lib.dim) }
    }

    // ------------------------------------------------------------ regions with identity

    private fun discover(lib: Lib, ids: List<TrackId>, rows: FloatArray): List<LibraryWorld> =
        LibraryWorlds.discover(ids.map { lib.descriptors.getValue(it) }, LibraryVectorSpace(ids, rows.copyOf(), lib.dim, LibraryVectorSource.AUDIO))

    private fun regionsOf(lib: Lib, ids: List<TrackId>, rows: FloatArray, worlds: List<LibraryWorld>, idOf: (Int) -> Int = { it }): Regions {
        val row = ids.withIndex().associate { it.value to it.index }
        val of = HashMap<TrackId, Int>()
        val names = HashMap<Int, String>(); val centroid = HashMap<Int, FloatArray>(); val floor = HashMap<Int, Float>()
        worlds.forEachIndexed { w, world ->
            val id = idOf(w)
            names[id] = world.name
            val c = FloatArray(lib.dim)
            for (t in world.tracks) { of[t.id] = id; val r = row.getValue(t.id); for (d in 0 until lib.dim) c[d] += rows[r * lib.dim + d] }
            unit(c); centroid[id] = c
            val cos = world.tracks.map { cosine(rows, row.getValue(it.id), lib.dim, c) }.sorted()
            floor[id] = cos[(cos.size * 0.05).toInt()]
        }
        return Regions(of, names, centroid, floor)
    }

    /** Frozen: a new or changed track joins the region with the nearest centroid if it clears that region's 5th-percentile member cosine. */
    private fun join(regions: Regions, ids: List<TrackId>, rows: FloatArray, dim: Int, flagged: Set<TrackId>): Regions {
        val of = HashMap<TrackId, Int>()
        val present = ids.toSet()
        for ((t, r) in regions.of) if (t in present && t !in flagged) of[t] = r
        ids.forEachIndexed { i, t ->
            if (t !in flagged) return@forEachIndexed
            var best = -1; var bestCos = -2f
            for ((r, c) in regions.centroid) { val cos = cosine(rows, i, dim, c); if (cos > bestCos) { bestCos = cos; best = r } }
            if (best >= 0 && bestCos >= regions.floor.getValue(best)) of[t] = best
        }
        return Regions(of, regions.names, regions.centroid, regions.floor)
    }

    /** Frozen: rediscovered worlds take old region ids by greedy largest member overlap (one-to-one); the rest get fresh ids. */
    private fun matched(lib: Lib, old: Regions, ids: List<TrackId>, rows: FloatArray, worlds: List<LibraryWorld>): Regions {
        val members = old.of.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }
        val pairs = mutableListOf<Triple<Int, Int, Int>>()
        worlds.forEachIndexed { w, world -> val set = world.tracks.map { it.id }; for ((r, m) in members) { val o = set.count { it in m }; if (o > 0) pairs += Triple(o, w, r) } }
        pairs.sortByDescending { it.first }
        val take = HashMap<Int, Int>(); val used = HashSet<Int>()
        for ((_, w, r) in pairs) if (w !in take && r !in used) { take[w] = r; used += r }
        var next = (old.names.keys.maxOrNull() ?: -1) + 1
        val idOf = worlds.indices.associateWith { take[it] ?: next++ }
        val fresh = regionsOf(lib, ids, rows, worlds) { idOf.getValue(it) }
        // A matched region keeps its old name; an unmatched one gets the discovered name.
        return Regions(fresh.of, fresh.names.mapValues { (id, name) -> old.names[id] ?: name }, fresh.centroid, fresh.floor)
    }

    /**
     * Frozen: sticky rediscovery. After matching, an unchanged track keeps its old region if that region survived
     * the match and the track still clears the region's (new) 5th-percentile cosine floor; otherwise it takes the
     * rediscovered assignment. Centroids and floors are those of the matched rediscovery.
     */
    private fun sticky(old: Regions, rediscovered: Regions, ids: List<TrackId>, rows: FloatArray, dim: Int, unchanged: Set<TrackId>): Regions {
        val of = HashMap(rediscovered.of)
        ids.forEachIndexed { i, t ->
            if (t !in unchanged) return@forEachIndexed
            val r = old.of[t] ?: return@forEachIndexed
            val c = rediscovered.centroid[r] ?: return@forEachIndexed
            if (cosine(rows, i, dim, c) >= rediscovered.floor.getValue(r)) of[t] = r
        }
        return Regions(of, rediscovered.names, rediscovered.centroid, rediscovered.floor)
    }

    /**
     * Frozen: additive regions. Settled tracks (present and unchanged since the last full build) keep their region.
     * Pending tracks (added or changed since) take the rediscovered world they fall in: that world's old id when its
     * settled members are mostly (>= 50 %) from one old region (greedy, one-to-one, largest overlap first), else a new
     * region (a new style). Pending tracks in no world keep their joined region.
     */
    private fun additive(old: Regions, worlds: List<LibraryWorld>, settled: Set<TrackId>, pending: Set<TrackId>): Regions {
        val majority = HashMap<Int, Int>()
        val pairs = mutableListOf<Triple<Int, Int, Int>>()
        worlds.forEachIndexed { w, world ->
            val s = world.tracks.filter { it.id in settled }.mapNotNull { old.of[it.id] }
            s.groupingBy { it }.eachCount().forEach { (r, c) -> if (c * 2 >= s.size) { pairs += Triple(c, w, r); majority[w] = r } }
        }
        pairs.sortByDescending { it.first }
        val take = HashMap<Int, Int>(); val used = HashSet<Int>()
        for ((_, w, r) in pairs) if (w !in take && r !in used) { take[w] = r; used += r }
        var next = (old.names.keys.maxOrNull() ?: -1) + 1
        val of = HashMap<TrackId, Int>()
        for ((t, r) in old.of) if (t in settled || t in pending) of[t] = r
        val names = HashMap(old.names)
        lastNewRegionSizes.clear()
        worlds.forEachIndexed { w, world ->
            val pend = world.tracks.filter { it.id in pending }
            if (pend.isEmpty()) return@forEachIndexed
            // Matched old id; else a split world's settled majority; else a new region only from MIN_CLUSTER_SIZE
            // pending tracks (otherwise they keep their joined region).
            val id = take[w] ?: majority[w] ?: if (pend.size >= TrackClustering.MIN_CLUSTER_SIZE) {
                (next++).also { names[it] = world.name; lastNewRegionSizes += pend.size }
            } else null
            if (id != null) for (t in pend) of[t.id] = id
        }
        return withCentroids(of, names)
    }

    /** Sizes of the regions the last [additive] call created (logged; a frozen row bounds them). */
    private val lastNewRegionSizes = mutableListOf<Int>()

    /** Centroids and floors recomputed from the final membership (needed so a region made at a rebuild can be joined). */
    private lateinit var centroidRows: Pair<List<TrackId>, FloatArray>
    private var centroidDim = 0

    private fun withCentroids(of: Map<TrackId, Int>, names: Map<Int, String>): Regions {
        val (ids, rows) = centroidRows
        val dim = centroidDim
        val row = ids.withIndex().associate { it.value to it.index }
        val members = of.entries.filter { it.key in row }.groupBy({ it.value }, { it.key })
        val centroid = HashMap<Int, FloatArray>(); val floor = HashMap<Int, Float>()
        for ((id, m) in members) {
            val c = FloatArray(dim)
            for (t in m) { val i = row.getValue(t); for (d in 0 until dim) c[d] += rows[i * dim + d] }
            unit(c); centroid[id] = c
            val cos = m.map { cosine(rows, row.getValue(it), dim, c) }.sorted()
            floor[id] = cos[(cos.size * 0.05).toInt()]
        }
        return Regions(of.filterKeys { it in row }, names.filterKeys { it in centroid }, centroid, floor)
    }

    /** Mean share of each region's members that carry the region's most common evaluation label (genre / style). */
    private fun purity(r: Regions, label: (TrackId) -> Int): Double {
        val groups = r.of.entries.groupBy({ it.value }, { it.key })
        return groups.values.sumOf { m -> m.groupingBy(label).eachCount().values.max().toDouble() } / groups.values.sumOf { it.size }
    }

    private fun churn(a: Map<TrackId, Int>, b: Map<TrackId, Int>, tracks: Collection<TrackId>) = tracks.count { a[it] != b[it] }.toDouble() / tracks.size

    private fun nameChurn(a: Map<TrackId, String>, b: Map<TrackId, String>, tracks: Collection<TrackId>) = tracks.count { a[it] != b[it] }.toDouble() / tracks.size

    private fun cosine(rows: FloatArray, i: Int, dim: Int, c: FloatArray): Float {
        var dot = 0f; var nn = 0f
        for (d in 0 until dim) { dot += rows[i * dim + d] * c[d]; nn += rows[i * dim + d] * rows[i * dim + d] }
        return dot / sqrt(nn)
    }

    private fun unit(v: FloatArray) { val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat(); if (n > 0) for (i in v.indices) v[i] /= n }

    // ------------------------------------------------------------ hint (latency only)

    private fun hint(lib: Lib, ids: List<TrackId>, r: MapRefreshEngine.Result, flaggedIds: Set<TrackId>): List<String> {
        if (flaggedIds.isEmpty() || r.neighbours == null) return emptyList()
        val n = ids.size
        val kk = r.neighbours.index.size / n
        val flagged = BooleanArray(n) { ids[it] in flaggedIds }
        val reasons = mutableListOf<String>()
        val config = Config(Router.UNION, family = lib.family)
        if (MapRefreshEngine.groupNovel(r.neighbours, n, kk, flagged, config) {} > 0.10 * flaggedIds.size) reasons += "geometry"
        if (lib.family != null && MapRefreshEngine.familyConflict(ids, r.neighbours, n, kk, flagged, config) {}) reasons += "family"
        if (lib.artist != null) {
            val known = ids.filter { it !in flaggedIds }.mapNotNull { lib.artist[it]?.lowercase() }.toSet()
            val withArtist = flaggedIds.mapNotNull { lib.artist[it]?.lowercase() }
            if (withArtist.isNotEmpty() && withArtist.count { it !in known } > 0.5 * withArtist.size) reasons += "unseen-artists"
        }
        return reasons
    }

    // ------------------------------------------------------------ one scenario

    private fun runScenario(lib: Lib, s: Scenario) {
        val dim = lib.dim
        val label = "lib=${lib.name} ${s.name}"
        val beforeIds = s.before.map(lib.ids::get)
        val beforeRows = matrix(lib, s.before, s.beforeRows)
        val before = MapRefreshEngine.fullBuild(beforeIds, beforeRows, dim, null).state
        centroidRows = beforeIds to beforeRows; centroidDim = dim
        val regionsBefore = regionsOf(lib, beforeIds, beforeRows, discover(lib, beforeIds, beforeRows))
        val ids = s.after.map(lib.ids::get)
        val rows = matrix(lib, s.after)
        val n = ids.size
        val beforeSet = s.before.toSet()
        val flaggedRows = (0 until n).filter { val r = s.after[it]; r !in beforeSet || r in s.beforeRows }
        val flaggedIds = flaggedRows.map(ids::get).toSet()
        val unchanged = ids.filter { it !in flaggedIds }
        // New design: always refresh (no router; size and budget limits lifted inside the engine).
        val refresh = MapRefreshEngine.update(ids, rows, dim, before, Config(Router.NONE, sizeLimit = 1.0, placedShareLimit = 1.0, family = lib.family))
        check(refresh.refreshed)
        val reasons = hint(lib, ids, refresh, flaggedIds)
        val share = flaggedRows.size.toDouble() / n
        val stale = share > 0.05 || reasons.isNotEmpty()
        log("R3 $label share=${f4(share)} refreshMs=${f1(refresh.ms)} hint=${reasons.joinToString("+").ifEmpty { "none" }} staleAt5=${share > 0.05 || reasons.isNotEmpty()} staleAt3=${share > 0.03 || reasons.isNotEmpty()} novel=${s.novel}")
        val today = MapRefreshEngine.fullBuild(ids, rows, dim, before)
        // The deferred full rebuild: today's path, warm from the refreshed (waiting) map.
        val rebuild = MapRefreshEngine.fullBuild(ids, rows, dim, refresh.state)
        val reference = LibraryLayout.reduceForEmbedding(rows.copyOf(), n, dim)
        val k = reference.size / n
        val maps = linkedMapOf("today" to flatten(today.points), "refresh" to flatten(refresh.points), "rebuild" to flatten(rebuild.points))
        if (mode == "gate") {
            maps["cold"] = flatten(MapRefreshEngine.fullBuild(ids, rows, dim, null).points)
            for (offset in listOf(1, 3, 5)) maps["cold+$offset"] = BarnesHutTsne.embed(reference, n, k, MapRefreshEngine.SEED + offset)
            maps["approach1"] = approach1(ids, rows, dim, before)
        }
        val m = metrics(reference, k, IntArray(n) { lib.genre[s.after[it]] }, flaggedRows, maps)
        for ((name, row) in m) log("R3 $label metrics $name ${row.entries.joinToString(" ") { "${it.key}=${f4(it.value)}" }}")
        val sToday = stability(before.positions, today.points, unchanged)
        val sRefresh = stability(before.positions, refresh.points, unchanged)
        log("R3 $label time todayMs=${f1(today.ms)} refreshMs=${f1(refresh.ms)} ratio=${f4(refresh.ms / today.ms)} rebuildMs=${f1(rebuild.ms)}")
        log("R3 $label stability today kept=${f4(sToday.kept)} p90=${f4(sToday.p90)} refresh kept=${f4(sRefresh.kept)} p90=${f4(sRefresh.p90)}")
        // Regions.
        val worldsAfter = discover(lib, ids, rows)
        val todayRegions = regionsOf(lib, ids, rows, worldsAfter)
        val joined = join(regionsBefore, ids, rows, dim, flaggedIds)
        val rediscovered = matched(lib, joined, ids, rows, worldsAfter)
        fun names(r: Regions) = r.of.mapValues { r.names.getValue(it.value) }
        val churnTodayName = nameChurn(names(regionsBefore), names(todayRegions), unchanged)
        val churnTodayMatched = churn(regionsBefore.of, matched(lib, regionsBefore, ids, rows, worldsAfter).of, unchanged)
        val churnJoin = churn(regionsBefore.of, joined.of, unchanged)
        val churnRebuild = churn(joined.of, rediscovered.of, unchanged)
        val stuck = sticky(joined, rediscovered, ids, rows, dim, unchanged.toSet())
        val churnSticky = churn(joined.of, stuck.of, unchanged)
        val genreOf = ids.withIndex().associate { it.value to lib.genre[s.after[it.index]] }
        val purityDiscovered = purity(rediscovered) { genreOf.getValue(it) }
        val purityStuck = purity(stuck) { genreOf.getValue(it) }
        centroidRows = ids to rows; centroidDim = dim
        val add = additive(joined, worldsAfter, unchanged.toSet(), flaggedIds)
        val churnAdditive = churn(joined.of.filterKeys { it in unchanged }, add.of.filterKeys { it in unchanged }, unchanged)
        val purityAdditive = purity(add) { genreOf.getValue(it) }
        val coverageAdditive = add.of.size.toDouble() / n
        val coverageDiscovered = rediscovered.of.size.toDouble() / n
        // A new style "has its own region" when one region holds at least half of its tracks and they are its majority.
        fun ownRegion(r: Regions): Double {
            if (!s.novel || flaggedIds.isEmpty()) return Double.NaN
            val byRegion = r.of.entries.groupBy({ it.value }, { it.key })
            return byRegion.values.maxOf { m -> val f = m.count { it in flaggedIds }; if (f * 2 > m.size) f.toDouble() / flaggedIds.size else 0.0 }
        }
        val duplicateNames = add.names.values.groupingBy { it }.eachCount().count { it.value > 1 }
        val newRegions = lastNewRegionSizes.toList()
        val stuckAgree = if (flaggedIds.isEmpty()) Double.NaN else flaggedIds.count { joined.of[it] == stuck.of[it] }.toDouble() / flaggedIds.size
        val agree = if (flaggedIds.isEmpty()) Double.NaN else flaggedIds.count { joined.of[it] == rediscovered.of[it] }.toDouble() / flaggedIds.size
        val regionsLine = "regionsBefore=${regionsBefore.names.size} regionsAfter=${worldsAfter.size} churnTodayByName=${f4(churnTodayName)} churnTodayMatched=${f4(churnTodayMatched)} " +
            "churnJoin=${f4(churnJoin)} churnRebuildMatched=${f4(churnRebuild)} churnRebuildSticky=${f4(churnSticky)} purityDiscovered=${f4(purityDiscovered)} puritySticky=${f4(purityStuck)} " +
            "stickyRegions=${stuck.of.values.toSet().size} churnAdditive=${f4(churnAdditive)} purityAdditive=${f4(purityAdditive)} coverageAdditive=${f4(coverageAdditive)} coverageDiscovered=${f4(coverageDiscovered)} " +
            "ownRegionDiscovered=${f4(ownRegion(rediscovered))} ownRegionAdditive=${f4(ownRegion(add))} additiveRegions=${add.names.size} duplicateNames=$duplicateNames newRegions=${newRegions.size} newRegionMinSize=${newRegions.minOrNull() ?: 0} newTrackJoinAgreesWithSticky=${f4(stuckAgree)} newTrackJoinAgreesWithRebuild=${f4(agree)} newTracksJoined=${flaggedIds.count { it in joined.of }}/${flaggedIds.size}"
        log("R3 $label regions $regionsLine")
        // Frozen rows.
        val t = m.getValue("today"); val q = m.getValue("refresh"); val b = m.getValue("rebuild")
        val out = linkedMapOf<String, Boolean>()
        if (!s.novel) {
            out["trust"] = q.getValue("trust") >= t.getValue("trust") - 0.005
            out["recall"] = q.getValue("recall") >= t.getValue("recall") * 0.97
            out["class"] = q.getValue("class") >= t.getValue("class") - 0.010
            if (flaggedRows.isNotEmpty()) { out["RF"] = q.getValue("RF") >= t.getValue("RF") * 0.85; out["CF"] = q.getValue("CF") >= t.getValue("CF") - 0.05 }
            out["lost"] = (1 - sRefresh.kept) <= 0.5 * (1 - sToday.kept)
            out["p90move"] = sRefresh.p90 <= 0.5 * sToday.p90 && sRefresh.p90 <= 0.02
            if (mode == "gate") {
                val noise = listOf(1, 3, 5).map { m.getValue("cold+$it").getValue("overlapCold") }.average()
                out["overlapCold"] = q.getValue("overlapCold") >= noise - 0.10
                out["staleBasis"] = q.getValue("recall") >= 0.97 * m.getValue("approach1").getValue("recall")
                val exact = BarnesHutTsne.nearestNeighbours(refresh.reducedRows!!, n, k, refresh.neighbours!!.index.size / n) { true }!!
                out["knnExact"] = exact.index.contentEquals(refresh.neighbours.index) && exact.distance.contentEquals(refresh.neighbours.distance)
            }
            out["timeHost"] = refresh.ms <= 0.25 * today.ms
        } else {
            // Waiting: the rest of the map is unharmed; the new style is reported, bounded at RF >= 0.5x; the rebuild recovers it.
            out["waitTrust"] = q.getValue("trust") >= t.getValue("trust") - 0.005
            out["waitRecall"] = q.getValue("recall") >= t.getValue("recall") * 0.95
            out["waitClass"] = q.getValue("class") >= t.getValue("class") - 0.010
            out["waitLost"] = (1 - sRefresh.kept) <= 0.5 * (1 - sToday.kept)
            out["waitRF"] = q.getValue("RF") >= 0.5 * t.getValue("RF")
            out["recoverRF"] = b.getValue("RF") >= 0.95 * t.getValue("RF")
            out["recoverCF"] = b.getValue("CF") >= t.getValue("CF") - 0.02
            out["recoverTrust"] = b.getValue("trust") >= t.getValue("trust") - 0.005
            out["recoverRecall"] = b.getValue("recall") >= t.getValue("recall") * 0.97
        }
        log("R3 $label verdict ${out.entries.joinToString(" ") { "${it.key}=${if (it.value) "PASS" else "FAIL"}" }} waitRFratio=${f4(q.getValue("RF") / t.getValue("RF"))} waitCFdelta=${f4(q.getValue("CF") - t.getValue("CF"))} flagged=${flaggedRows.size}")
    }

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

    // ------------------------------------------------------------ chain: refresh every step, rebuild when stale

    private fun chain(lib: Lib, budget: Double) {
        val dim = lib.dim
        val n = lib.n
        val random = Random(170 + n)
        val order = (0 until n).shuffled(random)
        val held = order.take((n * 0.12).toInt()).toMutableList()
        val current = order.drop((n * 0.12).toInt()).sorted().toMutableList()
        val step = n / 100
        var state = MapRefreshEngine.fullBuild(current.map(lib.ids::get), matrix(lib, current), dim, null).state
        // Region identity through the chain: join every step, additive at each rebuild.
        run { val i0 = current.map(lib.ids::get); val r0 = matrix(lib, current); centroidRows = i0 to r0; centroidDim = dim }
        var regions = run { val i0 = current.map(lib.ids::get); val r0 = matrix(lib, current); regionsOf(lib, i0, r0, discover(lib, i0, r0)) }
        val pendingRegions = HashSet<TrackId>()
        val chainNewRegions = mutableListOf<Int>()
        var sinceFull = 0
        val placedSinceFull = HashSet<Int>()
        var rebuilds = 0
        var allPass = true
        for (s in 1..11) {
            if (s <= 10) { val add = held.take(step); held.removeAll(add.toSet()); current += add; current.sort(); placedSinceFull += add }
            else { val remove = current.shuffled(random).take(current.size / 50).toSet(); current.removeAll(remove); placedSinceFull.removeAll(remove) }
            val ids = current.map(lib.ids::get)
            val rows = matrix(lib, current)
            val flaggedIds = ids.filter { state.hashes[it] == null }.toSet()
            val r = MapRefreshEngine.update(ids, rows, dim, state, Config(Router.NONE, sizeLimit = 1.0, placedShareLimit = 1.0, family = lib.family))
            sinceFull += flaggedIds.size
            val reasons = hint(lib, ids, r, flaggedIds)
            val stale = sinceFull > budget * ids.size || reasons.isNotEmpty()
            val exact = BarnesHutTsne.nearestNeighbours(r.reducedRows!!, ids.size, r.reducedRows.size / ids.size, r.neighbours!!.index.size / ids.size) { true }!!.let {
                it.index.contentEquals(r.neighbours.index) && it.distance.contentEquals(r.neighbours.distance)
            }
            allPass = allPass && exact
            centroidRows = ids to rows; centroidDim = dim
            regions = join(regions, ids, rows, dim, flaggedIds)
            pendingRegions += flaggedIds
            pendingRegions.retainAll(ids.toSet())
            var verdict = "n/a"
            if (stale || s == 11) {
                // The waiting map at its worst point (just before the deferred rebuild), against a cold build of this library.
                val m = ids.size
                val cold = MapRefreshEngine.fullBuild(ids, rows, dim, null)
                val reference = LibraryLayout.reduceForEmbedding(rows.copyOf(), m, dim)
                val placed = current.indices.filter { current[it] in placedSinceFull }
                val met = metrics(reference, reference.size / m, IntArray(m) { lib.genre[current[it]] }, placed, linkedMapOf("waiting" to flatten(r.points), "cold" to flatten(cold.points)))
                val w = met.getValue("waiting"); val c = met.getValue("cold")
                val pass = w.getValue("trust") >= c.getValue("trust") - 0.005 && w.getValue("recall") >= c.getValue("recall") * 0.97 &&
                    (placed.isEmpty() || w.getValue("RF") >= c.getValue("RF") * 0.85) && exact
                allPass = allPass && pass
                verdict = if (pass) "PASS" else "FAIL"
                log("R3 chain lib=${lib.name} B=$budget step=$s waiting ${w.entries.joinToString(" ") { "${it.key}=${f4(it.value)}" }} cold ${c.entries.joinToString(" ") { "${it.key}=${f4(it.value)}" }}")
            }
            log("R3 chain lib=${lib.name} B=$budget step=$s n=${ids.size} sinceFull=$sinceFull hint=${reasons.joinToString("+").ifEmpty { "none" }} stale=$stale knnExact=$exact refreshMs=${f1(r.ms)} check=$verdict")
            state = r.state
            if (stale) {
                state = MapRefreshEngine.fullBuild(ids, rows, dim, state).state
                regions = additive(regions, discover(lib, ids, rows), ids.filter { it !in pendingRegions }.toSet(), pendingRegions.toSet())
                chainNewRegions += lastNewRegionSizes
                pendingRegions.clear()
                sinceFull = 0; placedSinceFull.clear(); rebuilds++
            }
        }
        // Long-run regions: the chain's additive regions against a fresh discovery of the final library.
        val ids = current.map(lib.ids::get); val rows = matrix(lib, current)
        centroidRows = ids to rows; centroidDim = dim
        val fresh = regionsOf(lib, ids, rows, discover(lib, ids, rows))
        val genreOf = ids.withIndex().associate { it.value to lib.genre[current[it.index]] }
        val pa = purity(regions) { genreOf.getValue(it) }; val pf = purity(fresh) { genreOf.getValue(it) }
        val ca = regions.of.size.toDouble() / ids.size; val cf = fresh.of.size.toDouble() / ids.size
        val regionPass = pa >= pf - 0.01 && ca >= cf - 0.02 && chainNewRegions.all { it >= TrackClustering.MIN_CLUSTER_SIZE }
        val chainDuplicates = regions.names.values.groupingBy { it }.eachCount().count { it.value > 1 }
        log("R3 chain lib=${lib.name} B=$budget regions purityAdditive=${f4(pa)} purityFresh=${f4(pf)} coverageAdditive=${f4(ca)} coverageFresh=${f4(cf)} regionsAdditive=${regions.names.size} regionsFresh=${fresh.names.size} newRegions=${chainNewRegions.size} newRegionSizes=${chainNewRegions.joinToString("/")} duplicateNames=$chainDuplicates regionVerdict=${if (regionPass) "PASS" else "FAIL"}")
        log("R3 chain lib=${lib.name} B=$budget rebuilds=$rebuilds verdict=${if (allPass && regionPass) "PASS" else "FAIL"}")
    }

    // ------------------------------------------------------------ metrics (as round 2)

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
