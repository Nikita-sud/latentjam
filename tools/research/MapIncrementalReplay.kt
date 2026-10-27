/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Research replay for docs/map-cost-research-2026-10-25.md: what a library change costs and moves
 * when the warm-started Barnes-Hut is shortened and started at its raw scale, and when new or
 * changed tracks are first placed by their nearest unchanged neighbours. Synthetic fixtures only.
 *
 * Copy into core/smart/src/androidHostTest/kotlin/io/github/nikitasud/latentjam/smart/cluster/ to run.
 * Run: `MAP_INC_REPLAY=1 [MAP_INC_N=10000] [MAP_INC_SEEDS=7,11]
 *   [MAP_INC_SCENARIOS=same,add1,add5,add10,style,change5] ./gradlew
 *   :core:smart:testAndroidHostTest --tests "*MapIncrementalReplay*" --rerun`
 * Lines start with `INC`; the JUnit XML under core/smart/build/test-results keeps them.
 *
 * Scenarios. `same`: nothing changes (noise floor). `addP`: P % of tracks, spread evenly, are new.
 * `style`: one whole style (~5 % of the library) is new, a cluster the old map has never seen.
 * `changeP`: P % of tracks keep their id but their vector moves (half its old self, half another
 * random track), as when analysis replaces a provisional vector.
 */
class MapIncrementalReplay {
    private val scale = MapScaleReplay()

    @Test
    fun `incremental refresh against today's warm rerun`() {
        if (System.getenv("MAP_INC_REPLAY") != "1") return
        val n = System.getenv("MAP_INC_N")?.toInt() ?: 10_000
        val seeds = System.getenv("MAP_INC_SEEDS")?.split(",")?.map(String::toInt) ?: listOf(7, 11)
        val scenarios = System.getenv("MAP_INC_SCENARIOS")?.split(",")
            ?: listOf("same", "add1", "add5", "add10", "style", "change5")
        for (seed in seeds) {
            val fixture = scale.hierarchicalFixture(n, seed = seed)
            for (scenario in scenarios) run("n=$n seed=$seed $scenario", fixture, scenario, seed)
        }
    }

    private fun run(label: String, fixture: MapScaleReplay.Fixture, scenario: String, seed: Int) {
        val ids = fixture.ids
        val rows = fixture.rows
        val dim = fixture.dim
        val n = ids.size
        val random = Random(seed * 31 + scenario.hashCode())
        // flagged: rows the refresh is told are new or changed. Old rows outside it are "unchanged".
        val flagged = BooleanArray(n)
        val present = BooleanArray(n) { true }
        var beforeRows = rows
        when {
            scenario == "same" -> Unit
            scenario.startsWith("add") -> {
                val step = 100 / scenario.removePrefix("add").toInt()
                for (i in 0 until n) if (i % step == 0) { flagged[i] = true; present[i] = false }
            }
            scenario == "style" -> {
                val counts = fixture.labels.filter { it < 48 }.groupingBy { it }.eachCount()
                val style = counts.minBy { abs(it.value - n / 20) }.key
                for (i in 0 until n) if (fixture.labels[i] == style) { flagged[i] = true; present[i] = false }
            }
            scenario.startsWith("change") -> {
                val share = scenario.removePrefix("change").toInt() / 100.0
                beforeRows = rows.copyOf()
                for (i in 0 until n) {
                    if (random.nextDouble() >= share) continue
                    flagged[i] = true
                    val other = random.nextInt(n)
                    var norm = 0.0
                    for (d in 0 until dim) {
                        val v = 0.5f * rows[i * dim + d] + 0.5f * rows[other * dim + d]
                        beforeRows[i * dim + d] = v
                        norm += v.toDouble() * v
                    }
                    val inv = (1.0 / sqrt(norm)).toFloat()
                    for (d in 0 until dim) beforeRows[i * dim + d] *= inv
                }
            }
            else -> error("unknown scenario $scenario")
        }
        val before = (0 until n).filter { present[it] }
        val beforeIds = before.map(ids::get)
        val beforeMatrix = FloatArray(before.size * dim)
        before.forEachIndexed { into, from -> beforeRows.copyInto(beforeMatrix, into * dim, from * dim, (from + 1) * dim) }
        val unchangedIds = (0 until n).filter { present[it] && !flagged[it] }.map(ids::get)
        fun space(subset: List<TrackId>, matrix: FloatArray) =
            LibraryVectorSpace(subset, matrix.copyOf(), dim, LibraryVectorSource.AUDIO)

        var rawSpread = 0f
        val recording = MapEmbeddingBackend { r, rn, rd, initial, isActive ->
            ApproximateMapEmbedding.embed(r, rn, rd, initial, isActive).also { if (it is MapEmbeddingResult.Complete) rawSpread = spread(it.coordinates) }
        }
        val previousPoints = LibraryLayout.computeWith(space(beforeIds, beforeMatrix), emptyMap(), { true }, Long.MAX_VALUE, recording)
        val previous = previousPoints.associate { it.trackId to floatArrayOf(it.x, it.y) }
        println("INC $label flagged=${flagged.count { it }} rawSpread=${f(rawSpread.toDouble())}")
        val reference = LibraryLayout.reduceForEmbedding(rows.copyOf(), n, dim)
        val k = reference.size / n

        val backends = linkedMapOf<String, MapEmbeddingBackend>(
            "today" to ApproximateMapEmbedding,
            "today-s" to rescaledToday { rawSpread },
            "warm-150s" to short(150) { rawSpread },
            "place" to placed(flagged, 0) { rawSpread },
            "place+50s" to placed(flagged, 50) { rawSpread },
            "place+150s" to placed(flagged, 150) { rawSpread },
            "place+300s" to placed(flagged, 300) { rawSpread },
        )
        val maps = linkedMapOf<String, FloatArray>()
        var start = System.nanoTime()
        maps["cold"] = flatten(LibraryLayout.computeWith(space(ids, rows), emptyMap(), { true }, Long.MAX_VALUE, ApproximateMapEmbedding))
        println("INC $label cold ms=${ms(start)}")
        start = System.nanoTime()
        maps["cold-seed2"] = BarnesHutTsne.embed(reference, n, k, SEED + 1)
        println("INC $label cold-seed2 ms=${ms(start)} (embed only, unnormalized)")
        for ((name, backend) in backends) {
            start = System.nanoTime()
            val after = LibraryLayout.computeWith(space(ids, rows), previous, { true }, Long.MAX_VALUE, backend)
            val took = ms(start)
            maps[name] = flatten(after)
            println("INC $label $name ms=$took ${stability(previousPoints, after, unchangedIds)}")
        }

        val inputNeighbours = neighbours(reference, n, k, K)
        val mapNeighbours = maps.mapValues { (_, map) -> neighbours(map, n, 2, K) }
        val todayNeighbours = mapNeighbours.getValue("today")
        val coldNeighbours = mapNeighbours.getValue("cold")
        val penalty = DoubleArray(maps.size)
        val rank = IntArray(n)
        val packed = LongArray(n)
        for (i in 0 until n) {
            sortedByDistance(reference, n, k, i, packed)
            for (position in 1 until n) rank[packed[position].toInt()] = position
            mapNeighbours.values.forEachIndexed { m, neighbours ->
                for (j in neighbours[i]) penalty[m] += maxOf(0, rank[j] - K).toDouble()
            }
        }
        val classes = fixture.labels
        val flaggedRows = (0 until n).filter { flagged[it] }
        mapNeighbours.entries.forEachIndexed { m, (name, neighbours) ->
            val trust = 1.0 - 2.0 / (n * K * (2.0 * n - 3.0 * K - 1.0)) * penalty[m]
            fun share(rowsOf: List<Int>, hit: (Int, Int) -> Boolean) =
                if (rowsOf.isEmpty()) Double.NaN else rowsOf.sumOf { i -> neighbours[i].count { hit(i, it) }.toDouble() / K } / rowsOf.size
            val recallFlagged = share(flaggedRows) { i, j -> j in inputNeighbours[i] }
            val classFlagged = share(flaggedRows) { i, j -> classes[j] == classes[i] }
            val classShare = share((0 until n).toList()) { i, j -> classes[j] == classes[i] }
            println(
                "INC $label $name trust@15=${f(trust)} recall@15=${f(overlap(inputNeighbours, neighbours))} " +
                    "recallFlagged@15=${f(recallFlagged)} class@15=${f(classShare)} classFlagged@15=${f(classFlagged)} " +
                    "overlapToday=${f(overlap(todayNeighbours, neighbours))} overlapCold=${f(overlap(coldNeighbours, neighbours))}",
            )
        }
    }

    /** Today's full schedule, but from the stored positions rescaled to the previous raw spread. */
    private fun rescaledToday(spread: () -> Float) = MapEmbeddingBackend { rows, n, dim, initial, isActive ->
        ApproximateMapEmbedding.embed(rows, n, dim, rescaled(initial!!, spread()), isActive)
    }

    /** Warm refresh: no early exaggeration, late momentum from the first step, raw-scale start. */
    private fun short(iterations: Int, spread: () -> Float) = MapEmbeddingBackend { rows, n, dim, initial, isActive ->
        MapEmbeddingResult.Complete(refresh(rows, n, dim, rescaled(initial!!, spread()), isActive, iterations))
    }

    /**
     * Flagged rows go to the mean of their [PLACE_NEIGHBOURS] nearest unflagged rows in the PCA-50
     * input, at those rows' stored positions; then [iterations] refresh steps over every row.
     */
    private fun placed(flagged: BooleanArray, iterations: Int, spread: () -> Float) = MapEmbeddingBackend { rows, n, dim, initial, isActive ->
        val y = rescaled(initial!!, spread())
        val start = y.copyOf()
        val packed = LongArray(n)
        for (i in 0 until n) {
            if (!flagged[i]) continue
            sortedByDistance(rows, n, dim, i, packed)
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
            y[i * 2] = x / taken
            y[i * 2 + 1] = z / taken
        }
        MapEmbeddingResult.Complete(if (iterations == 0) y else refresh(rows, n, dim, y, isActive, iterations))
    }

    private fun refresh(rows: FloatArray, n: Int, dim: Int, y: FloatArray, isActive: () -> Boolean, iterations: Int) =
        BarnesHutTsne.embed(
            rows, n, dim, SEED, y, isActive,
            iterations = iterations, exaggerationIterations = 0, scheduleOffset = Tsne.EXAGGERATION_ITERATIONS,
        )

    /** Root-mean-square distance from the centroid. */
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

    /** The stored 0..1 positions scaled back to the raw t-SNE extent of the previous run. */
    private fun rescaled(y: FloatArray, target: Float): FloatArray {
        val factor = target / spread(y)
        return FloatArray(y.size) { y[it] * factor }
    }

    /** Over the unchanged tracks: share of 2-D 15-NN kept, and how far they moved in the 0..1 box. */
    private fun stability(before: List<LayoutPoint>, after: List<LayoutPoint>, unchanged: List<TrackId>): String {
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
        val kept = overlap(neighbours(a, unchanged.size, 2, K), neighbours(b, unchanged.size, 2, K))
        return "kept15=${f(kept)} medianMove=${f(moved[moved.size / 2])} p90Move=${f(moved[moved.size * 9 / 10])}"
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

    private fun ms(start: Long) = "%.0f".format((System.nanoTime() - start) / 1e6)
    private fun f(value: Double): String = String.format(java.util.Locale.ROOT, "%.4f", value)

    private companion object {
        const val K = 15
        const val SEED = 0x1A7E27
        const val PLACE_NEIGHBOURS = 5
    }
}
