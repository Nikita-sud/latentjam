/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Opt-in quality comparison for the large-library Map paths against the exact ones they replace
 * above their limits. `MAP_SCALE_REPLAY=1` runs synthetic fixtures; `MAP_DIAG_DIR=<dir>` adds a
 * private device dump (see [MapDiagnosticsReplay]). Prints aggregates only.
 *
 * Run: `MAP_SCALE_REPLAY=1 ./gradlew :core:smart:testAndroidHostTest --tests "*MapScaleReplay*" --rerun-tasks`
 */
class MapScaleReplay {
    @Test
    fun `barnes hut layout against exact`() {
        if (System.getenv("MAP_SCALE_REPLAY") != "1") return
        val sizes = System.getenv("MAP_SCALE_SIZES")?.split(",")?.map(String::toInt) ?: listOf(1000, 3000)
        for (n in sizes) {
            val fixture = hierarchicalFixture(n, seed = 7)
            compareLayouts("synthetic n=$n", fixture.rows, n, fixture.dim, fixture.labels, null)
        }
        val dump = realLibrary() ?: return
        val space = dump.space()
        val rows = space.takeRows()
        compareLayouts("library n=${space.size}", rows, space.size, space.dim, null, dump.playlistsFor(space.trackIds))
    }

    @Test
    fun `sampled average linkage against exact`() {
        if (System.getenv("MAP_SCALE_REPLAY") != "1") return
        val sizes = System.getenv("MAP_SCALE_SIZES")?.split(",")?.map(String::toInt) ?: listOf(1000, 3000)
        for (n in sizes) {
            val fixture = hierarchicalFixture(n, seed = 11)
            fun space() = LibraryVectorSpace(fixture.ids, fixture.rows.copyOf(), fixture.dim, LibraryVectorSource.AUDIO)
            compareRegions("synthetic n=$n", fixture.ids, ::space, fixture.labels)
        }
        val dump = realLibrary() ?: return
        val ids = dump.space().trackIds
        compareRegions("library n=${ids.size}", ids, dump::space, null)
    }

    @Test
    fun `host timing at ten thousand`() {
        if (System.getenv("MAP_SCALE_TIMING") != "1") return
        val n = 10_000
        val fixture = hierarchicalFixture(n, seed = 7)
        var start = System.nanoTime()
        val reduced = LibraryLayout.reduceForEmbedding(fixture.rows.copyOf(), n, fixture.dim)
        println("SCALE host pca n=$n ms=${(System.nanoTime() - start) / 1e6}")
        start = System.nanoTime()
        BarnesHutTsne.embed(reduced, n, reduced.size / n, 0x1A7E27)
        println("SCALE host bh n=$n ms=${(System.nanoTime() - start) / 1e6}")
        start = System.nanoTime()
        val clusters = AverageLinkageClustering.cluster(
            LibraryVectorSpace(fixture.ids, fixture.rows, fixture.dim, LibraryVectorSource.AUDIO),
        )
        println("SCALE host regions n=$n ms=${(System.nanoTime() - start) / 1e6} regions=${clusters.size}")
    }

    private fun compareLayouts(
        label: String,
        rows: FloatArray,
        n: Int,
        dim: Int,
        classes: IntArray?,
        playlists: List<Set<Int>>?,
    ) {
        val reduced = LibraryLayout.reduceForEmbedding(rows, n, dim)
        val reducedDim = reduced.size / n
        val inputNeighbours = neighbours(reduced, n, reducedDim, K)
        val inputRanks = ranks(reduced, n, reducedDim)
        val results = linkedMapOf<String, FloatArray>()
        var start = System.nanoTime()
        results["exact"] = Tsne.embed(reduced, n, reducedDim, SEED)
        val exactMs = (System.nanoTime() - start) / 1e6
        results["exact-seed2"] = Tsne.embed(reduced, n, reducedDim, SEED + 1)
        start = System.nanoTime()
        results["bh"] = BarnesHutTsne.embed(reduced, n, reducedDim, SEED)
        val bhMs = (System.nanoTime() - start) / 1e6
        results["bh-seed2"] = BarnesHutTsne.embed(reduced, n, reducedDim, SEED + 1)
        val exactNeighbours = neighbours(results.getValue("exact"), n, 2, K)
        println("SCALE layout $label exactMs=${f(exactMs)} bhMs=${f(bhMs)}")
        for ((name, embedding) in results) {
            val mapNeighbours = neighbours(embedding, n, 2, K)
            val parts = mutableListOf(
                "trust@15=${f(trustworthiness(inputRanks, mapNeighbours, n))}",
                "recall@15=${f(overlap(inputNeighbours, mapNeighbours, n))}",
                "overlapWithExact=${f(overlap(exactNeighbours, mapNeighbours, n))}",
            )
            if (classes != null) parts += "class@15=${f(sharedLabel(mapNeighbours, n) { a, b -> classes[a] == classes[b] })}"
            if (playlists != null) {
                val membership = Array(n) { HashSet<Int>() }
                playlists.forEachIndexed { index, members -> members.forEach { membership[it].add(index) } }
                parts += "playlist@15=${f(sharedLabel(mapNeighbours, n, only = { membership[it].isNotEmpty() }) { a, b ->
                    membership[a].any { it in membership[b] }
                })}"
            }
            println("SCALE layout $label $name ${parts.joinToString(" ")}")
        }
    }

    private fun compareRegions(
        label: String,
        ids: List<TrackId>,
        space: () -> LibraryVectorSpace,
        classes: IntArray?,
    ) {
        val index = ids.withIndex().associate { it.value to it.index }
        fun labels(clusters: List<TrackCluster>): IntArray {
            val out = IntArray(ids.size) { -1 - it }
            clusters.forEachIndexed { cluster, region -> region.members.forEach { out[index.getValue(it)] = cluster } }
            return out
        }
        var start = System.nanoTime()
        val exact = AverageLinkageClustering.cluster(space())
        val exactMs = (System.nanoTime() - start) / 1e6
        val exactLabels = labels(exact)
        println("SCALE regions $label exact regions=${exact.size} assigned=${exact.sumOf { it.members.size }} ms=${f(exactMs)}" +
            (classes?.let { " purity=${f(purity(exactLabels, it))}" } ?: ""))
        val kmeans = TrackClustering.cluster(space(), k = LibraryWorlds.recommendedK(ids.size))
        println("SCALE regions $label kmeans-fallback regions=${kmeans.size} ari=${f(adjustedRand(exactLabels, labels(kmeans)))}")
        // Natural instability: the exact hierarchy on a 90% subset, compared on the common tracks.
        for (drop in 0 until 3) {
            val keep = ids.indices.filter { (it * 7 + drop * 3) % 10 != 0 }
            val full = space()
            val rows = full.peekRows()
            val subsetRows = FloatArray(keep.size * full.dim)
            keep.forEachIndexed { into, from -> rows.copyInto(subsetRows, into * full.dim, from * full.dim, (from + 1) * full.dim) }
            val subset = LibraryVectorSpace(keep.map(ids::get), subsetRows, full.dim, full.source).also {
                it.hybridAudioDim = full.hybridAudioDim
                it.hybridMetadataWeight = full.hybridMetadataWeight
            }
            val subsetLabels = labels(AverageLinkageClustering.cluster(subset))
            println("SCALE regions $label exact-90%-subset#$drop ari=${f(adjustedRand(
                keep.map { exactLabels[it] }.toIntArray(), keep.map { subsetLabels[it] }.toIntArray(),
            ))}")
        }
        for (fraction in listOf(0.5, 0.3)) {
            val limit = (ids.size * fraction).toInt()
            start = System.nanoTime()
            val sampled = AverageLinkageClustering.cluster(space(), exactLimit = limit)
            val ms = (System.nanoTime() - start) / 1e6
            val sampledLabels = labels(sampled)
            println(
                "SCALE regions $label sample=$fraction regions=${sampled.size} " +
                    "assigned=${sampled.sumOf { it.members.size }} ms=${f(ms)} " +
                    "ari=${f(adjustedRand(exactLabels, sampledLabels))}" +
                    (classes?.let { " purity=${f(purity(sampledLabels, it))}" } ?: ""),
            )
        }
    }

    // --- Metrics -------------------------------------------------------------------------------

    private fun neighbours(points: FloatArray, n: Int, dim: Int, k: Int): Array<IntArray> = Array(n) { i ->
        val distances = DoubleArray(n) { j ->
            if (j == i) Double.POSITIVE_INFINITY else {
                var sum = 0.0
                for (d in 0 until dim) {
                    val delta = (points[i * dim + d] - points[j * dim + d]).toDouble()
                    sum += delta * delta
                }
                sum
            }
        }
        (0 until n).sortedWith(compareBy<Int> { distances[it] }.thenBy { it }).take(k).toIntArray()
    }

    /** ranks[i][j] = 1-based rank of j among i's input-space neighbours. */
    private fun ranks(points: FloatArray, n: Int, dim: Int): Array<IntArray> = Array(n) { i ->
        val distances = DoubleArray(n) { j ->
            var sum = 0.0
            for (d in 0 until dim) {
                val delta = (points[i * dim + d] - points[j * dim + d]).toDouble()
                sum += delta * delta
            }
            sum
        }
        val order = (0 until n).filter { it != i }.sortedWith(compareBy<Int> { distances[it] }.thenBy { it })
        IntArray(n).also { rank -> order.forEachIndexed { position, j -> rank[j] = position + 1 } }
    }

    private fun trustworthiness(inputRanks: Array<IntArray>, mapNeighbours: Array<IntArray>, n: Int): Double {
        var penalty = 0.0
        for (i in 0 until n) for (j in mapNeighbours[i]) penalty += maxOf(0, inputRanks[i][j] - K)
        return 1.0 - 2.0 / (n * K * (2.0 * n - 3.0 * K - 1.0)) * penalty
    }

    private fun overlap(a: Array<IntArray>, b: Array<IntArray>, n: Int): Double =
        (0 until n).sumOf { i -> b[i].count { it in a[i] }.toDouble() / K } / n

    private fun sharedLabel(
        mapNeighbours: Array<IntArray>,
        n: Int,
        only: (Int) -> Boolean = { true },
        same: (Int, Int) -> Boolean,
    ): Double {
        val anchors = (0 until n).filter(only)
        return anchors.sumOf { i -> mapNeighbours[i].count { same(i, it) }.toDouble() / K } / anchors.size
    }

    /** Assigned tracks whose region's majority class is their own; unassigned excluded. */
    private fun purity(labels: IntArray, classes: IntArray): Double {
        val byRegion = labels.indices.filter { labels[it] >= 0 }.groupBy { labels[it] }
        val majority = byRegion.values.sumOf { members -> members.groupingBy { classes[it] }.eachCount().values.max() }
        return majority.toDouble() / byRegion.values.sumOf { it.size }
    }

    /** Adjusted Rand index; each unassigned track is its own singleton (negative labels). */
    private fun adjustedRand(a: IntArray, b: IntArray): Double {
        fun choose2(x: Long) = x * (x - 1) / 2.0
        val joint = HashMap<Long, Long>()
        val left = HashMap<Int, Long>()
        val right = HashMap<Int, Long>()
        for (i in a.indices) {
            joint.merge((a[i].toLong() shl 32) or (b[i].toLong() and 0xFFFFFFFFL), 1L, Long::plus)
            left.merge(a[i], 1L, Long::plus)
            right.merge(b[i], 1L, Long::plus)
        }
        val index = joint.values.sumOf(::choose2)
        val sumA = left.values.sumOf(::choose2)
        val sumB = right.values.sumOf(::choose2)
        val expected = sumA * sumB / choose2(a.size.toLong())
        val maximum = (sumA + sumB) / 2
        return if (maximum == expected) 1.0 else (index - expected) / (maximum - expected)
    }

    // --- Inputs --------------------------------------------------------------------------------

    internal class Fixture(val ids: List<TrackId>, val rows: FloatArray, val dim: Int, val labels: IntArray)

    /**
     * 8 broad families of 6 styles each, styles of Zipf-like sizes, 15% scattered tracks, unit
     * rows. Unlike the 12-class benchmark fixture, it has nested structure and real outliers.
     */
    internal fun hierarchicalFixture(n: Int, seed: Int, dim: Int = 1344): Fixture {
        val random = Random(seed)
        fun gaussian(): Float {
            var u = random.nextDouble()
            while (u <= 1e-12) u = random.nextDouble()
            return (sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * random.nextDouble())).toFloat()
        }
        fun unit(vector: FloatArray): FloatArray {
            val norm = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
            for (i in vector.indices) vector[i] /= norm
            return vector
        }
        val families = List(8) { unit(FloatArray(dim) { gaussian() }) }
        val styles = List(48) { style ->
            val family = families[style / 6]
            unit(FloatArray(dim) { family[it] + 0.9f * gaussian() / sqrt(dim.toFloat()) * 2f })
        }
        val weights = DoubleArray(48) { 1.0 / (1 + it % 12) }
        val total = weights.sum()
        val rows = FloatArray(n * dim)
        val labels = IntArray(n)
        for (i in 0 until n) {
            val scattered = random.nextDouble() < 0.15
            var pick = random.nextDouble() * total
            var style = 0
            while (pick > weights[style]) { pick -= weights[style]; style++ }
            labels[i] = if (scattered) 48 + i else style
            val spread = if (scattered) 0f else 0.55f + 0.3f * (style % 4) / 3f
            val base = if (scattered) FloatArray(dim) else styles[style]
            val row = FloatArray(dim) { base[it] + (if (scattered) 1f else spread) * gaussian() / sqrt(dim.toFloat()) * 2f }
            unit(row).copyInto(rows, i * dim)
        }
        return Fixture(List(n) { TrackId("scale-$it") }, rows, dim, labels)
    }

    private class RealLibrary(
        val ids: List<TrackId>,
        val audio: Map<TrackId, FloatArray>,
        val text: Map<TrackId, FloatArray>,
        val audioDim: Int,
        val textDim: Int,
        val playlists: List<Set<TrackId>>,
    ) {
        fun space(): LibraryVectorSpace = requireNotNull(LibraryVectorFusion.build(ids, audio, text, audioDim, textDim))
        fun playlistsFor(order: List<TrackId>): List<Set<Int>> {
            val index = order.withIndex().associate { it.value to it.index }
            return playlists.map { members -> members.mapNotNullTo(HashSet()) { index[it] } }.filter { it.size >= 4 }
        }
    }

    private fun realLibrary(): RealLibrary? {
        val dir = System.getenv("MAP_DIAG_DIR") ?: return null
        val (audioDim, audio) = readNpy(File(dir, "smart_index_vecs.npy"))
        val (textDim, text) = readNpy(File(dir, "smart_text_vecs.npy"))
        val audioIds = File(dir, "smart_index_ids.txt").readLines().filter(String::isNotBlank)
        val textIds = File(dir, "smart_text_ids.txt").readLines().filter(String::isNotBlank)
        val audioMap = audioIds.withIndex().associate { TrackId(it.value) to audio[it.index] }
        val textMap = textIds.withIndex().associate { TrackId(it.value) to text[it.index] }
        val playlists = File(dir, "playlists.tsv").takeIf(File::isFile)?.readLines().orEmpty().mapNotNull { line ->
            val cells = line.split("\t")
            if (cells.size < 2) null else cells[1].split(",").filter(String::isNotBlank).mapTo(HashSet(), ::TrackId)
        }
        return RealLibrary((audioIds + textIds).distinct().map(::TrackId), audioMap, textMap, audioDim, textDim, playlists)
    }

    private fun readNpy(file: File): Pair<Int, List<FloatArray>> {
        val bytes = file.readBytes()
        val major = bytes[6].toInt()
        val headerStart = if (major == 1) 10 else 12
        val headerLength = if (major == 1) {
            (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        } else {
            ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
        val header = String(bytes, headerStart, headerLength, Charsets.US_ASCII)
        val shape = Regex("\\(([^)]*)\\)").find(header)!!.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
        val dim = shape.last()
        val flat = FloatArray(shape.fold(1) { a, b -> a * b })
        ByteBuffer.wrap(bytes, headerStart + headerLength, flat.size * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(flat)
        return dim to List(shape.first()) { flat.copyOfRange(it * dim, (it + 1) * dim) }
    }

    private fun f(value: Double): String = "%.4f".format(value)

    private companion object {
        const val K = 15
        const val SEED = 0x1A7E27
    }
}
