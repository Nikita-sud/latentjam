/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException

/** One region with a stable identity: [id] and [colour] persist across rediscoveries; [world] is what it shows. */
public class IdentityRegion(
    public val id: Int,
    public val colour: Int,
    public val world: LibraryWorld,
    /** Unit mean of the members' rows. */
    public val centroid: FloatArray,
    /** The members' 5th-percentile cosine to [centroid]: a joining track must clear it. */
    public val floor: Float,
)

/**
 * Regions that keep their identity while the library changes (docs/map-cost-research-2026-10-25.md,
 * implementation plan, piece 4). Between rebuilds a new or changed track [join]s the nearest region; at a
 * full rebuild [rediscover] matches the freshly discovered worlds to the old regions by membership, keeps
 * matched regions' ids, colours and names, and moves a settled track only when its new region is clearly
 * better (by `delta` in cosine to the regions' centroids).
 *
 * Rows are streamed from [LibraryVectorRows] one pass at a time, so only the regions' centroids and one
 * row are held beside the library; [isActive] is polled once per [LibraryVectorRows.CHUNK_ROWS] rows.
 */
public object RegionIdentity {
    /** How much closer (cosine to the centroids) a settled track's new region must be before it moves; set by the piece-4 pilot. */
    public const val DELTA: Float = 0.2f

    /** Fresh identities for a first discovery: ids and colours in world order. */
    public suspend fun initial(
        worlds: List<LibraryWorld>,
        rows: LibraryVectorRows,
        isActive: () -> Boolean = { true },
    ): List<IdentityRegion> {
        val stats = summarize(worlds.map { it.tracks }, rows, isActive)
        return worlds.indices.mapNotNull { w -> stats[w]?.let { (c, f) -> IdentityRegion(w, w, worlds[w], c, f) } }
    }

    /**
     * Adds [changed] tracks (new, or with a new vector) to the region with the nearest centroid when they
     * clear its floor; a changed track that clears none leaves its region. Tracks not in [present] are dropped.
     * Centroids and floors are kept (they move only at a rebuild). Reads only the changed tracks' rows.
     */
    public suspend fun join(
        regions: List<IdentityRegion>,
        changed: List<TrackDescriptor>,
        present: Set<TrackId>,
        rows: LibraryVectorRows,
        isActive: () -> Boolean = { true },
    ): List<IdentityRegion> {
        val changedIds = changed.mapTo(HashSet()) { it.id }
        val rowOf = rows.trackIds.withIndex().associate { it.value to it.index }
        val added = HashMap<Int, MutableList<TrackDescriptor>>()
        val row = FloatArray(rows.dim)
        for ((i, track) in changed.withIndex()) {
            if (i % LibraryVectorRows.CHUNK_ROWS == 0) poll(isActive)
            rows.read(rowOf[track.id] ?: continue, row)
            var best: IdentityRegion? = null
            var bestCos = -2f
            for (r in regions) { val c = cosine(row, r.centroid); if (c > bestCos) { bestCos = c; best = r } }
            if (best != null && bestCos >= best.floor) added.getOrPut(best.id) { mutableListOf() } += track
        }
        return regions.mapNotNull { r ->
            val kept = r.world.tracks.filter { it.id in present && it.id !in changedIds }
            val tracks = kept + added[r.id].orEmpty()
            if (tracks.isEmpty()) null else IdentityRegion(r.id, r.colour, r.world.copy(tracks = tracks), r.centroid, r.floor)
        }
    }

    /**
     * The regions after a full rediscovery ([worlds], today's discovery of the current library).
     *
     * 1. Each world is matched to the old region it shares most settled members with, greedily and one to
     *    one, largest overlap first. A matched world keeps that region's id, colour and name.
     * 2. A settled track (present and unchanged since the last rebuild) stays in its old region unless the
     *    world it falls in now is matched to another region (or to none) and its cosine to that world's
     *    centroid beats its cosine to its old region's centroid by more than [delta].
     * 3. An unmatched world with at least [minSize] members (after step 2) becomes a new region.
     */
    public suspend fun rediscover(
        previous: List<IdentityRegion>,
        worlds: List<LibraryWorld>,
        settled: Set<TrackId>,
        rows: LibraryVectorRows,
        delta: Float,
        minSize: Int = TrackClustering.MIN_CLUSTER_SIZE,
        isActive: () -> Boolean = { true },
        /** The first id a new region may take: above every id ever given, so a vanished region's id is never reused. */
        nextId: Int = nextId(previous),
    ): List<IdentityRegion> {
        val oldOf = HashMap<TrackId, Int>()
        for (r in previous) for (t in r.world.tracks) oldOf[t.id] = r.id
        val pairs = ArrayList<Triple<Int, Int, Int>>()
        worlds.forEachIndexed { w, world ->
            val counts = HashMap<Int, Int>()
            for (t in world.tracks) if (t.id in settled) oldOf[t.id]?.let { counts[it] = (counts[it] ?: 0) + 1 }
            for ((r, c) in counts) pairs += Triple(c, w, r)
        }
        pairs.sortWith(compareByDescending<Triple<Int, Int, Int>> { it.first }.thenBy { it.second }.thenBy { it.third })
        val matchOf = HashMap<Int, Int>()
        val used = HashSet<Int>()
        for ((_, w, r) in pairs) if (w !in matchOf && r !in used) { matchOf[w] = r; used += r }
        val byId = previous.associateBy { it.id }

        // The tracks whose old region is not their world's match: only they need a cosine comparison.
        val worldOf = HashMap<TrackId, Int>()
        worlds.forEachIndexed { w, world -> for (t in world.tracks) worldOf[t.id] = w }
        val contested = HashSet<TrackId>()
        for ((id, w) in worldOf) {
            val old = oldOf[id] ?: continue
            if (id in settled && old != matchOf[w] && old in byId) contested += id
        }
        val worldCentroids = centroids(worlds.map { it.tracks }, rows, isActive)
        val stays = HashSet<TrackId>()
        if (contested.isNotEmpty()) {
            val row = FloatArray(rows.dim)
            stream(rows, isActive) { id, r ->
                if (id !in contested) return@stream
                r.copyInto(row)
                val newCos = worldCentroids[worldOf.getValue(id)]?.let { cosine(row, it) } ?: -2f
                if (newCos - cosine(row, byId.getValue(oldOf.getValue(id)).centroid) <= delta) stays += id
            }
        }

        val members = LinkedHashMap<Int, MutableList<TrackDescriptor>>()
        val leftovers = HashMap<Int, MutableList<TrackDescriptor>>()
        val placed = HashSet<TrackId>()
        worlds.forEachIndexed { w, world ->
            val target = matchOf[w]
            for (t in world.tracks) {
                when {
                    t.id in stays -> members.getOrPut(oldOf.getValue(t.id)) { mutableListOf() } += t
                    target != null -> members.getOrPut(target) { mutableListOf() } += t
                    else -> leftovers.getOrPut(w) { mutableListOf() } += t
                }
                placed += t.id
            }
        }
        // Settled tracks the rediscovery left out of every world keep their region.
        for (r in previous) for (t in r.world.tracks) if (t.id in settled && t.id !in placed) members.getOrPut(r.id) { mutableListOf() } += t

        // An unsettled track (new, changed, or without a region before) is placed as a join would place it:
        // in the kept region whose settled core it is nearest, when it clears that region's floor. Only
        // otherwise does it follow its rediscovered world. A coarse world (the k-means fallback) then cannot
        // pour such tracks into the one fine region it happened to match.
        val unsettled = worldOf.keys.filterTo(HashSet()) { it !in settled || oldOf[it] == null }
        if (unsettled.isNotEmpty()) {
            val coreIds = members.keys.filter { it in byId }
            val cores = centroids(coreIds.map { id -> members.getValue(id).filter { it.id !in unsettled } }, rows, isActive)
            val moveTo = HashMap<TrackId, Int>()
            val row = FloatArray(rows.dim)
            stream(rows, isActive) { id, r ->
                if (id !in unsettled) return@stream
                r.copyInto(row)
                var best = -1
                var bestCos = -2f
                for (k in coreIds.indices) { val c = cores[k] ?: continue; val cos = cosine(row, c); if (cos > bestCos) { bestCos = cos; best = k } }
                if (best >= 0 && bestCos >= byId.getValue(coreIds[best]).floor) moveTo[id] = coreIds[best]
            }
            if (moveTo.isNotEmpty()) {
                val moved = ArrayList<Pair<Int, TrackDescriptor>>()
                for (list in members.values) list.removeAll { t -> moveTo[t.id]?.also { moved += it to t } != null }
                for (list in leftovers.values) list.removeAll { t -> moveTo[t.id]?.also { moved += it to t } != null }
                for ((id, t) in moved) members.getOrPut(id) { mutableListOf() } += t
                members.entries.removeAll { it.value.isEmpty() }
                leftovers.entries.removeAll { it.value.isEmpty() }
            }
        }

        var newId = maxOf(nextId, nextId(previous))
        val shownOf = HashMap<Int, LibraryWorld>()
        for ((w, id) in matchOf) shownOf[id] = byId.getValue(id).world
        val finalIds = ArrayList<Int>()
        val finalWorlds = ArrayList<LibraryWorld>()
        val colours = ArrayList<Int>()
        for ((id, tracks) in members) {
            val old = byId[id] ?: continue
            finalIds += id; colours += old.colour
            finalWorlds += (shownOf[id] ?: old.world).copy(tracks = tracks)
        }
        worlds.forEachIndexed { w, world ->
            val rest = leftovers[w] ?: return@forEachIndexed
            // A new region's colour index is its id, as in [initial].
            if (rest.size >= minSize) { finalIds += newId; colours += newId; newId++; finalWorlds += world.copy(tracks = rest) }
        }
        val stats = summarize(finalWorlds.map { it.tracks }, rows, isActive)
        return finalWorlds.indices.mapNotNull { i ->
            stats[i]?.let { (c, f) -> IdentityRegion(finalIds[i], colours[i], finalWorlds[i], c, f) }
                ?: null.also { println("Region identity: region ${finalIds[i]} has no rows and is dropped (${finalWorlds[i].tracks.size} tracks)") }
        }.sortedBy { it.id }
    }

    /** One more than the highest id in [regions]. */
    public fun nextId(regions: List<IdentityRegion>): Int = (regions.maxOfOrNull { it.id } ?: -1) + 1

    /** One pass: every row's hash ([MapRefreshRows.hash]), in [LibraryVectorRows.trackIds] order. */
    public suspend fun hashes(rows: LibraryVectorRows, isActive: () -> Boolean = { true }): LongArray {
        val out = LongArray(rows.size)
        var i = 0
        stream(rows, isActive) { _, row -> out[i++] = MapRefreshRows.hash(row, rows.dim) }
        return out
    }

    /** Per group: its unit centroid and its members' 5th-percentile cosine to it (two passes). */
    private suspend fun summarize(
        groups: List<List<TrackDescriptor>>,
        rows: LibraryVectorRows,
        isActive: () -> Boolean,
    ): Array<Pair<FloatArray, Float>?> {
        val centroids = centroids(groups, rows, isActive)
        val groupOf = groupIndex(groups)
        val cosines = Array(groups.size) { FloatArray(groups[it].size) }
        val filled = IntArray(groups.size)
        stream(rows, isActive) { id, row ->
            val g = groupOf[id] ?: return@stream
            val c = centroids[g] ?: return@stream
            cosines[g][filled[g]++] = cosine(row, c)
        }
        return Array(groups.size) { g ->
            val c = centroids[g] ?: return@Array null
            if (filled[g] == 0) return@Array null
            val sorted = cosines[g].copyOf(filled[g]).also { it.sort() }
            c to sorted[(sorted.size * 0.05).toInt()]
        }
    }

    private suspend fun centroids(
        groups: List<List<TrackDescriptor>>,
        rows: LibraryVectorRows,
        isActive: () -> Boolean,
    ): Array<FloatArray?> {
        val groupOf = groupIndex(groups)
        val sums = arrayOfNulls<FloatArray>(groups.size)
        stream(rows, isActive) { id, row ->
            val g = groupOf[id] ?: return@stream
            val acc = sums[g] ?: FloatArray(rows.dim).also { sums[g] = it }
            for (d in row.indices) acc[d] += row[d]
        }
        for (acc in sums) {
            if (acc == null) continue
            var norm = 0f
            for (v in acc) norm += v * v
            norm = sqrt(norm)
            if (norm > 0f) for (d in acc.indices) acc[d] /= norm
        }
        return sums
    }

    /** A track in two groups (it should not happen) counts in the first. */
    private fun groupIndex(groups: List<List<TrackDescriptor>>): HashMap<TrackId, Int> {
        val out = HashMap<TrackId, Int>()
        groups.forEachIndexed { g, tracks -> for (t in tracks) if (t.id !in out) out[t.id] = g }
        return out
    }

    private suspend fun stream(rows: LibraryVectorRows, isActive: () -> Boolean, visit: (TrackId, FloatArray) -> Unit) {
        val row = FloatArray(rows.dim)
        for (i in 0 until rows.size) {
            if (i % LibraryVectorRows.CHUNK_ROWS == 0) poll(isActive)
            rows.read(i, row)
            visit(rows.trackIds[i], row)
        }
    }

    private fun poll(isActive: () -> Boolean) {
        if (!isActive()) throw CancellationException("Region identity cancelled")
    }

    internal fun cosine(row: FloatArray, unitCentroid: FloatArray): Float {
        var dot = 0f
        var nn = 0f
        for (d in row.indices) { dot += row[d] * unitCentroid[d]; nn += row[d] * row[d] }
        return if (nn > 0f) dot / sqrt(nn) else -2f
    }
}
