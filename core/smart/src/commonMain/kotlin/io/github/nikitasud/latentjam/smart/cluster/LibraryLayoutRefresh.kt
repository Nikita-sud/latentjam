/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt

/** What [SampledTsne.reduce] and [LibraryLayout.computeStreamed] collect for a [MapRefreshSnapshot]. */
internal class ReductionCapture {
    var mean: FloatArray? = null
    var basis: FloatArray? = null
    var hashes: LongArray? = null
    var reduced: FloatArray? = null
    var neighbours: BarnesHutTsne.Neighbours? = null
    var rawSpread: Float = 0f
}

internal object MapRefreshRows {
    /** FNV-1a 64 over a raw fused row's float bits: equal rows, equal hash. */
    fun hash(row: FloatArray, dim: Int): Long {
        var h = -0x340d631b7bdddcdbL
        for (d in 0 until dim) {
            h = h xor (row[d].toRawBits().toLong() and 0xFFFFFFFFL)
            h *= 0x100000001b3L
        }
        return h
    }

    /** RMS distance from the centroid of row-major `n x 2` coordinates. */
    fun spread(y: FloatArray): Float {
        val n = y.size / 2
        var cx = 0.0
        var cy = 0.0
        for (i in 0 until n) { cx += y[i * 2]; cy += y[i * 2 + 1] }
        cx /= n; cy /= n
        var sum = 0.0
        for (i in 0 until n) { val dx = y[i * 2] - cx; val dy = y[i * 2 + 1] - cy; sum += dx * dx + dy * dy }
        return sqrt(sum / n).toFloat()
    }
}

/** A Map layout plus the refresh state to store with it (null when this layout cannot be refreshed). */
public class MapLayoutResult(
    public val points: List<LayoutPoint>,
    public val snapshot: MapRefreshSnapshot?,
    /** True when [points] came from a refresh rather than a full build. */
    public val refreshed: Boolean,
    /** Tracks added, changed or removed since the last full build, this change included. */
    public val changedSinceFull: Int,
    /** True when the map should get a deferred full rebuild (budget passed or a stale hint fired). */
    public val stale: Boolean,
)

/**
 * The Map refresh (docs/map-cost-research-2026-10-25.md, implementation plan, piece 1): a small
 * library change since the last full build is laid out by projecting the changed rows with the
 * stored basis, patching the stored 60-NN lists, placing each changed track at its nearest unchanged
 * neighbours and running [REFRESH_ITERATIONS] iterations without exaggeration from the stored
 * map at its raw scale. Anything it cannot handle returns null and the caller runs today's build.
 */
public object LibraryLayoutRefresh {
    /** Above this share of the library changed since the last full build, a refresh is not tried. */
    public const val MAX_SHARE: Double = 0.25
    /** Above this share the map is refreshed but also marked stale for a deferred full rebuild. */
    public const val STALE_SHARE: Double = 0.02
    public const val REFRESH_ITERATIONS: Int = 150
    public const val PLACE_NEIGHBOURS: Int = 5
    /** Geometry hint: share of changed rows that form a new, separate group. */
    public const val NOVEL_SHARE: Double = 0.10
    private const val SEED: Int = 0x1A7E27

    /** Whether a library of [n] tracks lays out on the backend whose lists a refresh can reuse. */
    public fun refreshable(n: Int): Boolean =
        n > LibraryLayout.EXACT_MAX_TRACKS && !LibraryLayout.experimentalBackend && n <= LibraryLayout.MAX_TRACKS

    /**
     * Today's full build ([LibraryLayout.compute] over [rows], identical points), also returning the
     * refresh state when the library is [refreshable].
     */
    public suspend fun build(
        rows: LibraryVectorRows,
        previous: Map<TrackId, FloatArray>,
        inputVersion: String,
        isActive: () -> Boolean = { true },
        workBudgetBytes: Long = Long.MAX_VALUE,
    ): MapLayoutResult? {
        val ids = rows.trackIds
        val n = ids.size
        if (!refreshable(n)) {
            val points = LibraryLayout.compute(rows, previous, isActive, workBudgetBytes) ?: return null
            return MapLayoutResult(points, null, refreshed = false, changedSinceFull = 0, stale = false)
        }
        if (MapMemoryBudget.projectionStreamedBytes(n, rows.dim, LibraryLayout.experimentalBackend) > workBudgetBytes) {
            throw MapComputationUnavailable("Insufficient memory for map projection", memoryLimited = true)
        }
        val capture = ReductionCapture()
        val points = LibraryLayout.computeStreamed(
            ids, rows::read, rows.dim, previous, isActive, keepsPopulation = rows::keepsPopulation, capture = capture,
        ) ?: return null
        val neighbours = capture.neighbours
        val reduced = capture.reduced
        val snapshot = if (neighbours != null && reduced != null && capture.rawSpread > 0f && capture.rawSpread.isFinite()) {
            MapRefreshSnapshot(
                inputVersion, rows.source, rows.fingerprint, ids, rows.dim, reduced.size / n, neighbours.index.size / n,
                capture.rawSpread, capture.mean!!, capture.basis!!, reduced, neighbours.index, capture.hashes!!,
            )
        } else null
        return MapLayoutResult(points, snapshot, refreshed = false, changedSinceFull = 0, stale = false)
    }

    /**
     * Refreshes [state]'s map for [rows], or returns null when a full build is required: another
     * input version or modality, a library outside [refreshable], more than [MAX_SHARE] changed
     * since the last full build, or a population change while the rows were read.
     *
     * @param previous the stored layout's normalized positions (the warm start)
     * @param extraHint an outside stale hint (e.g. mostly unseen artists); it marks the map stale
     * @throws kotlinx.coroutines.CancellationException when [isActive] becomes false
     */
    public suspend fun refresh(
        rows: LibraryVectorRows,
        previous: Map<TrackId, FloatArray>,
        state: MapRefreshState,
        inputVersion: String,
        isActive: () -> Boolean = { true },
        extraHint: Boolean = false,
        /** Artist of a track, for the unseen-artists hint (more than half of the changed tracks by new artists). */
        artistOf: ((TrackId) -> String?)? = null,
    ): MapLayoutResult? {
        val ids = rows.trackIds
        val n = ids.size
        val dim = rows.dim
        if (!refreshable(n) || !refreshable(state.size)) return null
        if (state.inputVersion != inputVersion || state.source != rows.source || state.dim != dim) return null
        val k = state.components
        val kk = minOf(BarnesHutTsne.NEIGHBORS, n - 1)
        if (state.neighbours != minOf(BarnesHutTsne.NEIGHBORS, state.size - 1)) return null
        val poll = { if (!isActive()) throw kotlinx.coroutines.CancellationException("Map refresh cancelled") }

        // 1. Flags by raw-row hash, and the changed rows projected with the stored mean and basis.
        val storedRow = HashMap<TrackId, Int>(state.size * 2)
        state.ids.forEachIndexed { i, id -> storedRow[id] = i }
        val reduced = FloatArray(n * k)
        val hashes = LongArray(n)
        val flagged = BooleanArray(n)
        val stateOf = IntArray(n) { -1 }
        val row = FloatArray(dim)
        var flaggedCount = 0
        for (i in 0 until n) {
            if (i % 64 == 0) poll()
            rows.read(i, row)
            val h = MapRefreshRows.hash(row, dim)
            hashes[i] = h
            val s = storedRow[ids[i]]
            if (s != null && state.hash(s) == h) {
                stateOf[i] = s
                state.reducedRow(s, reduced, i * k)
            } else {
                flagged[i] = true
                flaggedCount++
                SampledTsne.centerAndUnitize(row, state.mean, dim)
                Pca.project(row, dim, state.basis, k, reduced, i * k)
                SampledTsne.unitize(reduced, i * k, k)
            }
        }
        if (!rows.keepsPopulation()) return null
        val present = HashSet<TrackId>(n * 2).also { it.addAll(ids) }
        val removed = state.ids.count { it !in present }
        val changed = state.changedSinceFull + flaggedCount + removed
        if (changed > MAX_SHARE * n) return null

        // 2. Exact 60-NN over the current reduced rows, patched from the stored lists.
        val currentOf = IntArray(state.size) { -1 }
        for (i in 0 until n) if (stateOf[i] >= 0) currentOf[stateOf[i]] = i
        val neighbours = patchNeighbours(reduced, n, k, kk, flagged, stateOf, currentOf, state, poll)

        // 3. Hints (latency only) and the stale mark.
        val novel = groupNovel(neighbours, n, kk, flagged, poll)
        val geometryHint = flaggedCount > 0 && novel > NOVEL_SHARE * flaggedCount
        val artistHint = artistOf != null && flaggedCount > 0 && run {
            val known = HashSet<String>()
            for (i in 0 until n) if (!flagged[i]) artistOf(ids[i])?.lowercase()?.let(known::add)
            val changedArtists = (0 until n).filter { flagged[it] }.mapNotNull { artistOf(ids[it])?.lowercase() }
            changedArtists.isNotEmpty() && changedArtists.count { it !in known } * 2 > changedArtists.size
        }
        val stale = state.stale || changed > STALE_SHARE * n || geometryHint || artistHint || extraHint

        // 4. Placement at the raw scale, then the refresh iterations.
        val y = FloatArray(n * 2)
        var cx = 0f
        var cy = 0f
        var c = 0
        for (i in 0 until n) previous[ids[i]]?.let { if (it.size >= 2 && it[0].isFinite() && it[1].isFinite()) { cx += it[0]; cy += it[1]; c++ } }
        if (c < 3) return null
        cx /= c
        cy /= c
        for (i in 0 until n) {
            val p = previous[ids[i]]
            if (p != null && !flagged[i] && p.size >= 2 && p[0].isFinite() && p[1].isFinite()) { y[i * 2] = p[0]; y[i * 2 + 1] = p[1] } else { y[i * 2] = cx; y[i * 2 + 1] = cy }
        }
        val factor = state.rawSpread / MapRefreshRows.spread(y)
        if (!factor.isFinite() || factor <= 0f) return null
        for (i in y.indices) y[i] *= factor
        val start = y.copyOf()
        for (i in 0 until n) {
            if (!flagged[i]) continue
            if (i % 64 == 0) poll()
            var x = 0f
            var z = 0f
            var taken = 0
            for (slot in 0 until kk) {
                val j = neighbours.index[i * kk + slot]
                if (flagged[j]) continue
                x += start[j * 2]
                z += start[j * 2 + 1]
                if (++taken == PLACE_NEIGHBOURS) break
            }
            if (taken > 0) { y[i * 2] = x / taken; y[i * 2 + 1] = z / taken }
        }
        val p = BarnesHutTsne.jointProbabilities(neighbours, n, kk, isActive)
            ?: throw kotlinx.coroutines.CancellationException("Map refresh cancelled")
        val embedded = Tsne.initialPositions(n, SEED, y)
        BarnesHutTsne.optimize(p, n, embedded, isActive, REFRESH_ITERATIONS, 0, Tsne.EXAGGERATION_ITERATIONS)
        poll()
        if (!embedded.all(Float::isFinite)) return null
        val rawSpread = MapRefreshRows.spread(embedded)
        val points = LibraryLayout.normalize(ids, LibraryLayout.alignToPrevious(ids, embedded, previous, n), n)
        val snapshot = MapRefreshSnapshot(
            inputVersion, rows.source, rows.fingerprint, ids, dim, k, kk, rawSpread, state.mean, state.basis, reduced,
            neighbours.index, hashes, changedSinceFull = changed, stale = stale,
        )
        return MapLayoutResult(points, snapshot, refreshed = true, changedSinceFull = changed, stale = stale)
    }

    /**
     * The exact 60-NN of [reduced] (as [BarnesHutTsne.nearestNeighbours] would return it), built
     * from the stored lists: an unchanged row whose stored list holds only unchanged, present
     * tracks keeps it and is offered every changed row; any other row is searched in full.
     * Distances are recomputed from the rows with nearestNeighbours' arithmetic.
     */
    internal fun patchNeighbours(
        reduced: FloatArray,
        n: Int,
        k: Int,
        kk: Int,
        flagged: BooleanArray,
        stateOf: IntArray,
        currentOf: IntArray,
        state: MapRefreshState,
        poll: () -> Unit,
    ): BarnesHutTsne.Neighbours {
        val index = IntArray(n * kk)
        val distance = FloatArray(n * kk)
        val flaggedRows = (0 until n).filter { flagged[it] }.toIntArray()
        fun dist(a: Int, b: Int): Float {
            val lo = minOf(a, b) * k
            val hi = maxOf(a, b) * k
            var sum = 0f
            for (d in 0 until k) { val delta = reduced[lo + d] - reduced[hi + d]; sum += delta * delta }
            return sum
        }
        val full = LongArray(n)
        val merge = LongArray(kk + flaggedRows.size)
        for (i in 0 until n) {
            if (i % 64 == 0) poll()
            val s = stateOf[i]
            var clean = s >= 0 && state.neighbours == kk
            if (clean) {
                for (slot in 0 until kk) {
                    val j = currentOf[state.neighbour(s, slot)]
                    if (j < 0 || flagged[j]) { clean = false; break }
                }
            }
            if (clean) {
                var m = 0
                for (slot in 0 until kk) { val j = currentOf[state.neighbour(s, slot)]; merge[m++] = pack(dist(i, j), j) }
                for (f in flaggedRows) merge[m++] = pack(dist(i, f), f)
                merge.sort(0, m)
                for (slot in 0 until kk) { index[i * kk + slot] = (merge[slot] and 0xFFFFFFFFL).toInt(); distance[i * kk + slot] = Float.fromBits((merge[slot] ushr 32).toInt()) }
            } else {
                poll()
                var m = 0
                for (j in 0 until n) if (j != i) full[m++] = pack(dist(i, j), j)
                full.sort(0, m)
                for (slot in 0 until kk) { index[i * kk + slot] = (full[slot] and 0xFFFFFFFFL).toInt(); distance[i * kk + slot] = Float.fromBits((full[slot] ushr 32).toInt()) }
            }
        }
        return BarnesHutTsne.Neighbours(index, distance)
    }

    /** Non-negative float bits sort as the floats; the low word breaks ties by the lower row. */
    private fun pack(distance: Float, index: Int): Long = (distance.toRawBits().toLong() shl 32) or index.toLong()

    /**
     * Group novelty (round 2's G): a changed row is novel when at least 3 of its 5 nearest rows are
     * changed and its nearest unchanged row is farther than twice the library's median
     * 5th-neighbour distance (or not among its 60 at all).
     */
    private fun groupNovel(nb: BarnesHutTsne.Neighbours, n: Int, kk: Int, flagged: BooleanArray, poll: () -> Unit): Int {
        if (kk < 5) return 0
        val fifth = FloatArray(n) { nb.distance[it * kk + 4] }
        fifth.sort()
        val median = sqrt(fifth[n / 2].toDouble())
        var novel = 0
        for (i in 0 until n) {
            if (!flagged[i]) continue
            if (i % 64 == 0) poll()
            var group = 0
            for (slot in 0 until 5) if (flagged[nb.index[i * kk + slot]]) group++
            if (group < 3) continue
            var nearest = Double.POSITIVE_INFINITY
            for (slot in 0 until kk) if (!flagged[nb.index[i * kk + slot]]) { nearest = sqrt(nb.distance[i * kk + slot].toDouble()); break }
            if (nearest > 2.0 * median) novel++
        }
        return novel
    }
}
