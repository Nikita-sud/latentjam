/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Opt-in quality and stability comparison of the "sample and place" prototype against full
 * Barnes-Hut (docs/map-sample-place-2026-10-16.md). Prints aggregates only.
 *
 * `MAP_SAMPLE_REPLAY=1 [MAP_SAMPLE_SIZES=5000,10000] [MAP_SAMPLE_VARIANTS=r0,r30] [MAP_DIAG_DIR=..]`
 * Variants: `r<refine>[@rate][k<neighbours>][f<full BH iterations>][i<sample iterations>][s<fraction>][P = PCA on the sample only]`,
 * e.g. `r30@0.5k15` or `r0f200`.
 * `MAP_SAMPLE_TUNE=1` runs only the tuning fixture (seed 3), which is not a gate input.
 */
class MapSampleReplay {
    private val scale = MapScaleReplay()

    @Test
    fun `sample and place against barnes hut`() {
        if (System.getenv("MAP_SAMPLE_REPLAY") != "1") return
        val variants = (System.getenv("MAP_SAMPLE_VARIANTS") ?: "r0,r30").split(",").map(::Variant)
        val stability = System.getenv("MAP_SAMPLE_STABILITY") != "0"
        if (System.getenv("MAP_SAMPLE_TUNE") == "1") {
            val n = System.getenv("MAP_SAMPLE_TUNE_N")?.toInt() ?: 6000
            val fixture = scale.hierarchicalFixture(n, seed = 3)
            run("tune n=$n", fixture.ids, fixture.rows, fixture.dim, SampledTsne.SAMPLE_SIZE, variants, fixture.labels, null, stability)
            return
        }
        val sizes = System.getenv("MAP_SAMPLE_SIZES")?.split(",")?.map(String::toInt) ?: listOf(5000, 10000)
        for (n in sizes) {
            val fixture = scale.hierarchicalFixture(n, seed = 7)
            run("synthetic n=$n", fixture.ids, fixture.rows, fixture.dim, SampledTsne.SAMPLE_SIZE, variants, fixture.labels, null, stability)
        }
        val dump = scale.realLibrary() ?: return
        val space = dump.space()
        val ids = space.trackIds
        val rows = space.takeRows()
        val sample = (ids.size * 0.3).toInt()
        run("library n=${ids.size} sample=$sample", ids, rows, space.dim, sample, variants, null, dump.playlistsFor(ids), stability)
    }

    private class Variant(spec: String) {
        val name = spec
        val iterations = Regex("r(\\d+)").find(spec)!!.groupValues[1].toInt()
        val rate = Regex("@([0-9.]+)").find(spec)?.groupValues?.get(1)?.toFloat() ?: 1f
        val neighbours = Regex("k(\\d+)").find(spec)?.groupValues?.get(1)?.toInt() ?: SampledTsne.PLACE_NEIGHBORS
        val full = Regex("f(\\d+)").find(spec)?.groupValues?.get(1)?.toInt() ?: 0
        val sampleIterations = Regex("i(\\d+)").find(spec)?.groupValues?.get(1)?.toInt() ?: Tsne.ITERATIONS
        val samplePca = "P" in spec
        val sampleFraction = Regex("s([0-9.]+)").find(spec)?.groupValues?.get(1)?.toDouble()
    }

    private fun run(
        label: String,
        ids: List<TrackId>,
        rows: FloatArray,
        dim: Int,
        sampleSize: Int,
        variants: List<Variant>,
        classes: IntArray?,
        playlists: List<Set<Int>>?,
        stability: Boolean,
    ) {
        val n = ids.size
        val reference = LibraryLayout.reduceForEmbedding(rows.copyOf(), n, dim)
        val k = reference.size / n
        val maps = linkedMapOf<String, FloatArray>()
        var start = System.nanoTime()
        maps["bh"] = BarnesHutTsne.embed(reference, n, k, SEED)
        println("SAMPLE $label bh ms=${ms(start)}")
        maps["bh-seed2"] = BarnesHutTsne.embed(reference, n, k, SEED + 1)
        // Isolates the streamed PCA: basis fitted on the sample, then full Barnes-Hut on every row.
        val sampledPca = runBlocking { SampledTsne.reduce(source(rows, dim), n, dim, SampledTsne.sampleRows(ids, sampleSize), LibraryLayout.COMPONENTS, SEED) { true }!! }
        maps["pca-sample+bh"] = BarnesHutTsne.embed(sampledPca, n, k, SEED)
        val streamed = runBlocking { SampledTsne.reduce(source(rows, dim), n, dim, null, LibraryLayout.COMPONENTS, SEED) { true }!! }
        println("SAMPLE $label streamed-pca bitIdentical=${streamed.contentEquals(reference)}")
        for (variant in variants) {
            start = System.nanoTime()
            val points = runBlocking { LibraryLayout.computeSampled(
                ids, source(rows, dim), dim, sampleSize = variant.sampleFraction?.let { (n * it).toInt() } ?: sampleSize,
                refineIterations = variant.iterations, refineRate = variant.rate, neighbours = variant.neighbours,
                fullIterations = variant.full, sampleIterations = variant.sampleIterations, samplePca = variant.samplePca,
            ) }
            println("SAMPLE $label ${variant.name} ms=${ms(start)}")
            maps[variant.name] = flatten(points)
        }
        val mapNeighbours = maps.mapValues { (_, map) -> neighbours(map, n, 2, K) }
        val bhNeighbours = mapNeighbours.getValue("bh")
        val inputNeighbours = neighbours(reference, n, k, K)
        // Trustworthiness without an n x n rank table: one input ranking per row, reused by every map.
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
        val membership = playlists?.let {
            Array(n) { HashSet<Int>() }.also { m -> it.forEachIndexed { index, members -> members.forEach { m[it].add(index) } } }
        }
        mapNeighbours.entries.forEachIndexed { m, (name, neighbours) ->
            val parts = mutableListOf(
                "trust@15=${f(1.0 - 2.0 / (n * K * (2.0 * n - 3.0 * K - 1.0)) * penalty[m])}",
                "recall@15=${f(overlap(inputNeighbours, neighbours))}",
                "overlapWithBh=${f(overlap(bhNeighbours, neighbours))}",
            )
            if (classes != null) parts += "class@15=${f(shared(neighbours, { true }) { a, b -> classes[a] == classes[b] })}"
            if (membership != null) {
                parts += "playlist@15=${f(shared(neighbours, { membership[it].isNotEmpty() }) { a, b ->
                    membership[a].any { it in membership[b] }
                })}"
            }
            println("SAMPLE $label $name ${parts.joinToString(" ")}")
        }
        if (stability) stability(label, ids, rows, dim, sampleSize, variants)
    }

    /**
     * Lays out 95% of the library cold, then the whole library warm-started from it, as after an
     * import. Reports, over the old tracks, how many of their 2-D 15 nearest old neighbours are kept
     * and how far they moved in the normalized 0..1 box.
     */
    private fun stability(label: String, ids: List<TrackId>, rows: FloatArray, dim: Int, sampleSize: Int, variants: List<Variant>) {
        val n = ids.size
        val old = (0 until n).filter { it % 20 != 0 }
        val oldIds = old.map(ids::get)
        val oldRows = FloatArray(old.size * dim)
        old.forEachIndexed { into, from -> rows.copyInto(oldRows, into * dim, from * dim, (from + 1) * dim) }
        fun report(name: String, before: List<LayoutPoint>, after: List<LayoutPoint>) {
            val afterById = after.associateBy { it.trackId }
            val a = flatten(before)
            val b = FloatArray(old.size * 2)
            val moved = DoubleArray(old.size)
            oldIds.forEachIndexed { slot, id ->
                val point = afterById.getValue(id)
                b[slot * 2] = point.x
                b[slot * 2 + 1] = point.y
                val dx = (point.x - a[slot * 2]).toDouble()
                val dy = (point.y - a[slot * 2 + 1]).toDouble()
                moved[slot] = sqrt(dx * dx + dy * dy)
            }
            moved.sort()
            val kept = overlap(neighbours(a, old.size, 2, K), neighbours(b, old.size, 2, K))
            println("SAMPLE stability $label $name kept15=${f(kept)} medianMove=${f(moved[moved.size / 2])} p90Move=${f(moved[moved.size * 9 / 10])}")
        }
        fun space(subset: List<TrackId>, matrix: FloatArray) =
            LibraryVectorSpace(subset, matrix.copyOf(), dim, LibraryVectorSource.AUDIO)
        val bhBefore = LibraryLayout.computeWith(space(oldIds, oldRows), emptyMap(), { true }, Long.MAX_VALUE, ApproximateMapEmbedding)
        val bhAfter = LibraryLayout.computeWith(
            space(ids, rows), bhBefore.associate { it.trackId to floatArrayOf(it.x, it.y) }, { true }, Long.MAX_VALUE, ApproximateMapEmbedding,
        )
        report("bh-warm", bhBefore, bhAfter)
        for (variant in variants) {
            fun sampled(subset: List<TrackId>, matrix: FloatArray, previous: Map<TrackId, FloatArray>) = runBlocking { LibraryLayout.computeSampled(
                subset, source(matrix, dim), dim, previous,
                sampleSize = variant.sampleFraction?.let { (subset.size * it).toInt() } ?: sampleSize,
                refineIterations = variant.iterations, refineRate = variant.rate, neighbours = variant.neighbours,
                fullIterations = variant.full, sampleIterations = variant.sampleIterations, samplePca = variant.samplePca,
            ) }
            val before = sampled(oldIds, oldRows, emptyMap())
            val after = sampled(ids, rows, before.associate { it.trackId to floatArrayOf(it.x, it.y) })
            report(variant.name, before, after)
        }
    }

    private fun source(rows: FloatArray, dim: Int) = LayoutRowSource { row, out -> rows.copyInto(out, 0, row * dim, (row + 1) * dim) }

    private fun flatten(points: List<LayoutPoint>): FloatArray =
        FloatArray(points.size * 2).also { out -> points.forEachIndexed { i, p -> out[i * 2] = p.x; out[i * 2 + 1] = p.y } }

    /** Row indices sorted by squared distance from row [i] (row i first), ties by index. */
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

    private fun shared(neighbours: Array<IntArray>, only: (Int) -> Boolean, same: (Int, Int) -> Boolean): Double {
        val anchors = neighbours.indices.filter(only)
        return anchors.sumOf { i -> neighbours[i].count { same(i, it) }.toDouble() / K } / anchors.size
    }

    private fun ms(start: Long) = "%.0f".format((System.nanoTime() - start) / 1e6)
    private fun f(value: Double): String = "%.4f".format(value)

    private companion object {
        const val K = 15
        const val SEED = 0x1A7E27
    }
}
