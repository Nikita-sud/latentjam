/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt

/**
 * Research engine for round 2 of docs/map-cost-research-2026-10-25.md: approach 1 (refresh at raw scale) on top of
 * approach 3 (frozen PCA basis and centering mean, stored reduced rows, patched 60-NN lists), with the round-2
 * routers. Test code only; nothing in the app calls it. The same file is copied into the device test.
 */
internal object MapRefreshEngine {
    const val K = BarnesHutTsne.NEIGHBORS
    const val COMPONENTS = LibraryLayout.COMPONENTS
    const val SEED = 0x1A7E27
    const val REFRESH_ITERATIONS = 150
    const val PLACE_NEIGHBOURS = 5

    /**
     * Everything a build leaves behind for the next refresh. Lists hold squared distances, nearest first. Production
     * would hold these as primitive arrays: [productionBytes] is that size, which the heap rows add to the refresh.
     */
    class State(
        val ids: List<TrackId>,
        val positions: Map<TrackId, FloatArray>,
        val rawSpread: Float,
        val hashes: Map<TrackId, Long>,
        val dim: Int,
        val mean: FloatArray,
        val basis: FloatArray,
        val k: Int,
        val reduced: Map<TrackId, FloatArray>,
        val lists: Map<TrackId, Pair<Array<TrackId>, FloatArray>>,
        val placedSinceFull: Int,
    ) {
        /** reduced rows + 60-NN lists (int + float) + mean + basis + row hashes, as primitive arrays. */
        fun productionBytes(): Long = 4L * ids.size * k + 8L * ids.size * minOf(K, ids.size - 1) + 4L * dim + 4L * dim * k + 8L * ids.size
    }

    /**
     * UNION (round-2 primary): GEOMETRY, then the family conflict when predicted families exist, then refresh and
     * CHECK; any one of them sends the change to a full build.
     */
    enum class Router { GEOMETRY, GEOMETRY_FAMILY, CHECK, UNION, NONE }

    class Config(
        val router: Router,
        val sizeLimit: Double = 0.03,
        val placedShareLimit: Double = 0.10,
        /** Predicted family per track (never an evaluation label); null when unavailable. */
        val family: Map<TrackId, String?>? = null,
        val groupMin: Int = 3,
        val isolation: Double = 2.0,
        val novelShare: Double = 0.10,
        val familyConflictMargin: Double = 0.15,
        val checkRatio: Double = 0.85,
        /**
         * False for measured runs: skip building the research [State] (production keeps primitive arrays already
         * counted), and a router rejection's full build then consumes the rows in place (the caller must not reuse them).
         */
        val buildState: Boolean = true,
    )

    class Result(
        val points: List<LayoutPoint>,
        val state: State,
        val refreshed: Boolean,
        val reason: String,
        val flagged: Int,
        val novel: Int,
        val ms: Double,
        val phases: Map<String, Double>,
        /** Patched lists and stale-basis rows, for the harness's bit-exact check. */
        val reducedRows: FloatArray?,
        val neighbours: BarnesHutTsne.Neighbours?,
    )

    class Cancelled : RuntimeException()

    fun rowHash(rows: FloatArray, slot: Int, dim: Int): Long {
        var h = -0x340d631b7bdddcdbL
        for (d in 0 until dim) {
            h = h xor rows[slot * dim + d].toRawBits().toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    /**
     * Today's path (warm Barnes-Hut through LibraryLayout.computeWith), timed; then, untimed, the state a production
     * build would keep as a by-product (mean, basis, reduced rows, 60-NN lists).
     */
    fun fullBuild(
        ids: List<TrackId>, rows: FloatArray, dim: Int, previous: State?, isActive: () -> Boolean = { true },
        /** False for measured "today" runs: no state by-product, and [rows] is consumed in place (no copy). */
        withState: Boolean = true,
    ): Result {
        require(withState || previous != null) { "a measured build needs the previous state" }
        val n = ids.size
        var raw = 0f
        val owned = if (withState) rows.copyOf() else rows
        val recording = MapEmbeddingBackend { r, rn, rd, initial, active ->
            ApproximateMapEmbedding.embed(r, rn, rd, initial, active).also { if (it is MapEmbeddingResult.Complete) raw = spread(it.coordinates) }
        }
        val start = System.nanoTime()
        val points = LibraryLayout.computeWith(
            LibraryVectorSpace(ids, owned, dim, LibraryVectorSource.AUDIO), previous?.positions ?: emptyMap(), isActive, Long.MAX_VALUE, recording,
        )
        val ms = (System.nanoTime() - start) / 1e6
        val state = if (withState) stateOf(ids, rows, dim, points, raw, isActive) else previous!!
        return Result(points, state, false, "full", 0, 0, ms, emptyMap(), null, null)
    }

    /**
     * The refresh state a build leaves. Computed separately here (a second PCA and 60-NN), so it is an upper bound on
     * what production would pay by keeping the layout's own basis and lists. Polls [isActive].
     */
    fun stateOf(ids: List<TrackId>, rows: FloatArray, dim: Int, points: List<LayoutPoint>, raw: Float, isActive: () -> Boolean = { true }): State {
        val n = ids.size
        val mean = FloatArray(dim)
        for (d in 0 until dim) {
            var m = 0f
            for (i in 0 until n) m += rows[i * dim + d]
            mean[d] = m / n
        }
        val prepared = rows.copyOf()
        LibraryLayout.prepareForPca(prepared, n, dim)
        val basis = Pca.basis(prepared, n, dim, COMPONENTS, SEED, isActive) ?: throw Cancelled()
        val k = basis.size / dim
        if (!isActive()) throw Cancelled()
        val reduced = LibraryLayout.reduceForEmbedding(rows.copyOf(), n, dim, isActive)
        if (!isActive()) throw Cancelled()
        val neighbours = BarnesHutTsne.nearestNeighbours(reduced, n, k, minOf(K, n - 1), isActive) ?: throw Cancelled()
        val kk = minOf(K, n - 1)
        return State(
            ids, points.associate { it.trackId to floatArrayOf(it.x, it.y) }, raw,
            ids.indices.associate { ids[it] to rowHash(rows, it, dim) }, dim, mean, basis, k,
            ids.indices.associate { ids[it] to reduced.copyOfRange(it * k, (it + 1) * k) },
            ids.indices.associate { i ->
                ids[i] to (Array(kk) { s -> ids[neighbours.index[i * kk + s]] } to neighbours.distance.copyOfRange(i * kk, (i + 1) * kk))
            },
            0,
        )
    }

    /**
     * Device-stage state build (added after the round-3 freeze, used only by MapRound3DeviceTest): the same mean,
     * basis, reduced rows and 60-NN as [stateOf], but from ONE matrix that it consumes (centred and unitized in
     * place), with reduced rows projected row by row. [stateOf]'s two extra copies of the n x dim matrix do not fit a
     * 256 MB heap next to the library matrix. Numerically equivalent up to float summation order.
     */
    fun stateOfLean(ids: List<TrackId>, rows: FloatArray, dim: Int, points: List<LayoutPoint>, raw: Float, isActive: () -> Boolean = { true }): State {
        val n = ids.size
        val hashes = ids.indices.associate { ids[it] to rowHash(rows, it, dim) }
        val mean = FloatArray(dim)
        for (d in 0 until dim) { var m = 0f; for (i in 0 until n) m += rows[i * dim + d]; mean[d] = m / n }
        LibraryLayout.prepareForPca(rows, n, dim)
        val basis = Pca.basis(rows, n, dim, COMPONENTS, SEED, isActive) ?: throw Cancelled()
        val k = basis.size / dim
        val reduced = FloatArray(n * k)
        val row = FloatArray(dim)
        for (i in 0 until n) {
            if (i % 256 == 0 && !isActive()) throw Cancelled()
            rows.copyInto(row, 0, i * dim, (i + 1) * dim)
            Pca.project(row, dim, basis, k, reduced, i * k)
            var norm = 0f
            for (c in 0 until k) norm += reduced[i * k + c] * reduced[i * k + c]
            norm = sqrt(norm)
            if (norm >= 1e-12f) for (c in 0 until k) reduced[i * k + c] /= norm
        }
        val kk = minOf(K, n - 1)
        val neighbours = BarnesHutTsne.nearestNeighbours(reduced, n, k, kk, isActive) ?: throw Cancelled()
        return State(
            ids, points.associate { it.trackId to floatArrayOf(it.x, it.y) }, raw, hashes, dim, mean, basis, k,
            ids.indices.associate { ids[it] to reduced.copyOfRange(it * k, (it + 1) * k) },
            ids.indices.associate { i -> ids[i] to (Array(kk) { s -> ids[neighbours.index[i * kk + s]] } to neighbours.distance.copyOfRange(i * kk, (i + 1) * kk)) },
            0,
        )
    }

    /** One library change: flag, patch, route, then refresh or (router says no) today's full build. */
    fun update(ids: List<TrackId>, rows: FloatArray, dim: Int, state: State, config: Config, isActive: () -> Boolean = { true }): Result {
        val poll = { if (!isActive()) throw Cancelled() }
        val phases = LinkedHashMap<String, Double>()
        var mark = System.nanoTime()
        fun phase(name: String) { val now = System.nanoTime(); phases[name] = (now - mark) / 1e6; mark = now }
        val start = mark
        val n = ids.size
        val k = state.k
        // 1. Flags.
        val flagged = BooleanArray(n) { i ->
            if (i % 256 == 0) poll()
            state.hashes[ids[i]]?.let { it != rowHash(rows, i, dim) } ?: true
        }
        val flaggedCount = flagged.count { it }
        fun full(reason: String, novel: Int = 0): Result {
            // Time spent deciding, plus the full build itself; the untimed state by-product is excluded.
            val deciding = (System.nanoTime() - start) / 1e6
            val built = fullBuild(ids, rows, dim, state, isActive, withState = config.buildState)
            return Result(built.points, built.state, false, reason, flaggedCount, novel, deciding + built.ms, phases, null, null)
        }
        if (flaggedCount > config.sizeLimit * n) return full("size")
        if (state.placedSinceFull + flaggedCount > config.placedShareLimit * n) return full("budget")
        phase("flags")
        // 2. Reduced rows: stored for unchanged tracks, frozen mean + basis for flagged ones.
        val reduced = FloatArray(n * k)
        val centred = FloatArray(dim)
        for (i in 0 until n) {
            if (!flagged[i]) { state.reduced.getValue(ids[i]).copyInto(reduced, i * k); continue }
            poll()
            var norm = 0f
            for (d in 0 until dim) { val v = rows[i * dim + d] - state.mean[d]; centred[d] = v; norm += v * v }
            norm = sqrt(norm)
            if (norm >= 1e-12f) for (d in 0 until dim) centred[d] /= norm
            Pca.project(centred, dim, state.basis, k, reduced, i * k)
            var rn = 0f
            for (c in 0 until k) rn += reduced[i * k + c] * reduced[i * k + c]
            rn = sqrt(rn)
            if (rn >= 1e-12f) for (c in 0 until k) reduced[i * k + c] /= rn
        }
        phase("project")
        // 3. Patched 60-NN lists.
        val kk = minOf(K, n - 1)
        val neighbours = patchNeighbours(ids, reduced, n, k, kk, flagged, state, poll)
        phase("neighbours")
        // 4. Router (reuses the lists; polls per row).
        var novel = 0
        when (config.router) {
            Router.GEOMETRY, Router.GEOMETRY_FAMILY, Router.UNION -> {
                novel = groupNovel(neighbours, n, kk, flagged, config, poll)
                if (novel > config.novelShare * flaggedCount) { phase("router"); return full("novelty", novel) }
                if (config.router != Router.GEOMETRY && config.family != null &&
                    familyConflict(ids, neighbours, n, kk, flagged, config, poll)
                ) { phase("router"); return full("family", novel) }
            }
            else -> Unit
        }
        phase("router")
        // 5. Placement from the lists, raw-scale start.
        val y = FloatArray(n * 2)
        run {
            var cx = 0f; var cy = 0f; var c = 0
            for (i in 0 until n) state.positions[ids[i]]?.let { cx += it[0]; cy += it[1]; c++ }
            cx /= c; cy /= c
            for (i in 0 until n) {
                val p = state.positions[ids[i]]
                if (p != null && !flagged[i]) { y[i * 2] = p[0]; y[i * 2 + 1] = p[1] } else { y[i * 2] = cx; y[i * 2 + 1] = cy }
            }
            val factor = state.rawSpread / spread(y)
            for (i in y.indices) y[i] *= factor
            val start = y.copyOf()
            for (i in 0 until n) {
                if (!flagged[i]) continue
                poll()
                var x = 0f; var z = 0f; var taken = 0
                for (s in 0 until kk) {
                    val j = neighbours.index[i * kk + s]
                    if (flagged[j]) continue
                    x += start[j * 2]; z += start[j * 2 + 1]
                    if (++taken == PLACE_NEIGHBOURS) break
                }
                if (taken > 0) { y[i * 2] = x / taken; y[i * 2 + 1] = z / taken }
            }
        }
        phase("place")
        // 6. Affinities over the patched lists, then the refresh iterations.
        val p = BarnesHutTsne.jointProbabilities(neighbours, n, kk) { isActive() } ?: throw Cancelled()
        phase("affinities")
        val embedded = optimize(p, n, y, REFRESH_ITERATIONS, isActive)
        phase("optimizer")
        val raw = spread(embedded)
        val aligned = LibraryLayout.alignToPrevious(ids, embedded, state.positions, n)
        val points = LibraryLayout.normalize(ids, aligned, n)
        phase("finish")
        // 7. Refresh-then-check router: rebuild when the flagged rows' map neighbourhoods are clearly worse.
        val checks = config.router == Router.CHECK || config.router == Router.UNION
        if (checks && flaggedCount > 0 && !checkPasses(points, neighbours, n, kk, flagged, config, poll)) {
            phase("check")
            return full("check")
        }
        if (checks) phase("check")
        val ms = (System.nanoTime() - start) / 1e6
        // The state handed to the next refresh; building it (and persisting it) is outside [ms].
        if (!config.buildState) return Result(points, state, true, "refresh", flaggedCount, novel, ms, phases, reduced, neighbours)
        val newState = State(
            ids, points.associate { it.trackId to floatArrayOf(it.x, it.y) }, raw,
            ids.indices.associate { ids[it] to rowHash(rows, it, dim) }, dim, state.mean, state.basis, k,
            ids.indices.associate { ids[it] to reduced.copyOfRange(it * k, (it + 1) * k) },
            ids.indices.associate { i ->
                ids[i] to (Array(kk) { s -> ids[neighbours.index[i * kk + s]] } to neighbours.distance.copyOfRange(i * kk, (i + 1) * kk))
            },
            state.placedSinceFull + flaggedCount,
        )
        return Result(points, newState, true, "refresh", flaggedCount, novel, ms, phases, reduced, neighbours)
    }

    /**
     * Exact 60-NN over [reduced], built from the stored lists: unchanged rows whose stored list still holds only
     * unchanged, present tracks keep it and are offered every flagged row; other rows are searched in full. Ties as
     * BarnesHutTsne.nearestNeighbours (distance, then lower row index).
     */
    private fun patchNeighbours(
        ids: List<TrackId>, reduced: FloatArray, n: Int, k: Int, kk: Int, flagged: BooleanArray, state: State, poll: () -> Unit,
    ): BarnesHutTsne.Neighbours {
        val row = HashMap<TrackId, Int>(n * 2).also { m -> ids.forEachIndexed { i, id -> m[id] = i } }
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
            // Clean: unchanged, with a stored list of only unchanged, present tracks. Merge it with the flagged rows.
            val stored = if (flagged[i]) null else state.lists[ids[i]]
            var clean = stored != null && stored.first.size == kk
            if (clean) {
                for (s in 0 until kk) { val j = row[stored!!.first[s]]; if (j == null || flagged[j]) { clean = false; break } }
            }
            if (clean) {
                var m = 0
                for (s in 0 until kk) merge[m++] = pack(stored!!.second[s], row.getValue(stored.first[s]))
                for (f in flaggedRows) merge[m++] = pack(dist(i, f), f)
                java.util.Arrays.sort(merge, 0, m)
                for (s in 0 until kk) { index[i * kk + s] = (merge[s] and 0xFFFFFFFFL).toInt(); distance[i * kk + s] = Float.fromBits((merge[s] ushr 32).toInt()) }
            } else {
                poll()
                var m = 0
                for (j in 0 until n) if (j != i) full[m++] = pack(dist(i, j), j)
                java.util.Arrays.sort(full, 0, m)
                for (s in 0 until kk) { index[i * kk + s] = (full[s] and 0xFFFFFFFFL).toInt(); distance[i * kk + s] = Float.fromBits((full[s] ushr 32).toInt()) }
            }
        }
        return BarnesHutTsne.Neighbours(index, distance)
    }

    private fun pack(distance: Float, index: Int): Long = (distance.toRawBits().toLong() shl 32) or index.toLong()

    /**
     * Group novelty: a flagged row is novel if at least [Config.groupMin] of its 5 nearest rows are flagged and its
     * nearest unflagged row is farther than [Config.isolation] × the library's median 5th-neighbour distance (or not
     * within its 60-NN at all). Isolated outliers are therefore not novel; a new, separate cluster is.
     */
    fun groupNovel(nb: BarnesHutTsne.Neighbours, n: Int, kk: Int, flagged: BooleanArray, config: Config, poll: () -> Unit): Int {
        val fifth = FloatArray(n) { nb.distance[it * kk + minOf(4, kk - 1)] }
        fifth.sort()
        val median = sqrt(fifth[n / 2].toDouble())
        var novel = 0
        for (i in 0 until n) {
            if (!flagged[i]) continue
            poll()
            var group = 0
            for (s in 0 until minOf(5, kk)) if (flagged[nb.index[i * kk + s]]) group++
            if (group < config.groupMin) continue
            var nearest = Double.POSITIVE_INFINITY
            for (s in 0 until kk) if (!flagged[nb.index[i * kk + s]]) { nearest = sqrt(nb.distance[i * kk + s].toDouble()); break }
            if (nearest > config.isolation * median) novel++
        }
        return novel
    }

    /**
     * Family conflict (predicted families only): the share of flagged rows whose predicted family differs from the
     * majority family of their 5 nearest unflagged rows, against the same share for unflagged rows (their 5 nearest
     * other unflagged rows). Routes to a full build when it exceeds the library's own rate by [Config.familyConflictMargin].
     */
    fun familyConflict(
        ids: List<TrackId>, nb: BarnesHutTsne.Neighbours, n: Int, kk: Int, flagged: BooleanArray, config: Config, poll: () -> Unit,
    ): Boolean {
        val family = config.family!!
        fun conflict(i: Int): Boolean? {
            val own = family[ids[i]] ?: return null
            val counts = HashMap<String?, Int>()
            var taken = 0
            for (s in 0 until kk) {
                val j = nb.index[i * kk + s]
                if (flagged[j]) continue
                counts.merge(family[ids[j]], 1, Int::plus)
                if (++taken == PLACE_NEIGHBOURS) break
            }
            if (taken == 0) return true
            // A conflict only when the row's own family is not among the tied most common ones; "other" (null) rows
            // are skipped as rows, but count as a neighbour family.
            val top = counts.values.max()
            return counts[own] != top
        }
        var fc = 0; var fn = 0; var bc = 0; var bn = 0
        for (i in 0 until n) {
            if (i % 64 == 0) poll()
            val c = conflict(i) ?: continue
            if (flagged[i]) { fn++; if (c) fc++ } else { bn++; if (c) bc++ }
        }
        if (fn == 0 || bn == 0) return false
        return fc.toDouble() / fn > bc.toDouble() / bn + config.familyConflictMargin
    }

    /**
     * Refresh-then-check: mean share of each flagged row's 15 map neighbours that are among its 15 nearest input rows
     * (from the 60-NN lists), against the same for up to 500 unflagged rows; passes at ≥ [Config.checkRatio] × that.
     */
    private fun checkPasses(
        points: List<LayoutPoint>, nb: BarnesHutTsne.Neighbours, n: Int, kk: Int, flagged: BooleanArray, config: Config, poll: () -> Unit,
    ): Boolean {
        val xs = FloatArray(n) { points[it].x }
        val ys = FloatArray(n) { points[it].y }
        val scratch = LongArray(n)
        fun recall(i: Int): Double {
            var m = 0
            for (j in 0 until n) if (j != i) { val dx = xs[i] - xs[j]; val dy = ys[i] - ys[j]; scratch[m++] = pack(dx * dx + dy * dy, j) }
            java.util.Arrays.sort(scratch, 0, m)
            val input = HashSet<Int>(); for (s in 0 until minOf(15, kk)) input.add(nb.index[i * kk + s])
            var hit = 0
            for (s in 0 until 15) if ((scratch[s] and 0xFFFFFFFFL).toInt() in input) hit++
            return hit / 15.0
        }
        var f = 0.0; var fc = 0
        for (i in 0 until n) if (flagged[i]) { poll(); f += recall(i); fc++ }
        val step = maxOf(1, (n - fc) / 500)
        var b = 0.0; var bc = 0
        var seen = 0
        for (i in 0 until n) if (!flagged[i]) { if (seen++ % step == 0) { poll(); b += recall(i); bc++ } }
        return f / fc >= config.checkRatio * (b / bc)
    }

    /** BarnesHutTsne.embed's optimizer loop over precomputed affinities, no exaggeration, late momentum. */
    fun optimize(p: BarnesHutTsne.SparseAffinities, n: Int, initial: FloatArray, iterations: Int, isActive: () -> Boolean): FloatArray {
        val y = Tsne.initialPositions(n, SEED, initial)
        val gains = FloatArray(n * 2) { 1f }
        val velocity = FloatArray(n * 2)
        val grad = FloatArray(n * 2)
        val repulsion = FloatArray(n * 2)
        val tree = BarnesHutTsne.QuadTree(n)
        for (iteration in 0 until iterations) {
            if (!isActive()) throw Cancelled()
            tree.build(y)
            var normalizer = 0.0
            for (i in 0 until n) normalizer += tree.repel(i, y, repulsion)
            val sum = if (normalizer <= 0.0) 1e-12f else normalizer.toFloat()
            for (i in 0 until n) {
                val ix = y[i * 2]
                val iy = y[i * 2 + 1]
                var ax = 0f
                var ay = 0f
                for (edge in p.start[i] until p.start[i + 1]) {
                    val j = p.column[edge]
                    val dx = ix - y[j * 2]
                    val dy = iy - y[j * 2 + 1]
                    val force = p.value[edge] / (1f + dx * dx + dy * dy)
                    ax += force * dx
                    ay += force * dy
                }
                grad[i * 2] = 4f * (ax - repulsion[i * 2] / sum)
                grad[i * 2 + 1] = 4f * (ay - repulsion[i * 2 + 1] / sum)
            }
            Tsne.update(y, n, grad, gains, velocity, iteration + Tsne.EXAGGERATION_ITERATIONS)
        }
        return y
    }

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
