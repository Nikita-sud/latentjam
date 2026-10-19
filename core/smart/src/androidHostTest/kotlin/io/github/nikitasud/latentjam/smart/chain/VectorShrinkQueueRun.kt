/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.InMemoryVectorIndex
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import io.github.nikitasud.latentjam.smart.text.AssetBytes
import io.github.nikitasud.latentjam.smart.text.MusicEntityResolver
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test

/**
 * Research runner, not a regression test: plans one SMART queue per seed of a real library for each vector-store
 * variant, the way DefaultSimilarityEngine plans with the app's settings (judged scoring, continuation, artist run
 * penalty 0.5, no predictor graphs, the journey sequencer), so a compressed store can be compared queue for queue
 * with the float32 one. Rows enter through InMemoryVectorIndex.adopt and leave through storedEntries, as in the app.
 *
 * Inputs come from prep_queue_inputs.py: VSHRINK_DIR=<dir> VSHRINK_ASSETS=<androidApp/src/main/assets/ml>
 * ./gradlew :core:smart:testAndroidHostTest --tests '*VectorShrinkQueueRun*' --rerun
 * Absent the variable it skips.
 */
class VectorShrinkQueueRun {

    @Test
    fun planEveryVariant() {
        val dir = System.getenv("VSHRINK_DIR")?.let(::File)?.takeIf(File::isDirectory) ?: run {
            println("SKIP vector shrink run: VSHRINK_DIR unset")
            return
        }
        val assets = File(requireNotNull(System.getenv("VSHRINK_ASSETS")) { "VSHRINK_ASSETS" })
        val meta = File(dir, "meta.tsv").readLines().filter(String::isNotEmpty).map { it.split('\t') }
        val n = meta.size
        val hasText = File(dir, "hasText.u8").readBytes()
        fun bytes(file: File): AssetBytes? = file.takeIf(File::isFile)?.readBytes()?.let { AssetBytes.of(it) }
        val knowledge = ArtistKnowledge(
            MusicEntityResolver { bytes(File(assets, "music_entities_250k.bin")) },
            loadAdapter = { File(assets, "artist_adapter.bin").takeIf(File::isFile)?.readBytes() },
            loadPack = { bytes(File(assets, "artist_knowledge.bin")) },
        )
        val pairs = (System.getenv("VSHRINK_PAIRS") ?: "fp32:fp32").split(',').map { it.split(':') }
        val length = System.getenv("VSHRINK_LENGTH")?.toInt() ?: 30
        val tuning = ChainTuning(
            continueAfterExhaustion = true,
            neighbourhoodBonus = 0f,
            rerankWeights = Rerank.JUDGED_WEIGHTS,
            soundFloor = Rerank.SOUND_FLOOR,
            artistRunPenalty = 0.5f,
            companionPoints = Rerank.COMPANION_POINTS,
        )
        // VSHRINK_PERMUTE=1: the same float32 library in a shuffled row order, the null baseline (ties break by row).
        val permute = System.getenv("VSHRINK_PERMUTE") == "1"
        // VSHRINK_SNAPSHOT=fp16: the snapshot's centred matrices rounded to IEEE half after the build (hub penalty
        // and typicality keep their float32 values: a slight understatement of an fp16 snapshot's change).
        val snapshotFp16 = System.getenv("VSHRINK_SNAPSHOT") == "fp16"
        // VSHRINK_DROP=0.01: a realistic library change, 1 % of songs removed with the order of the rest kept.
        val drop: Double = System.getenv("VSHRINK_DROP")?.toDouble() ?: 0.0
        val dropped = if (drop > 0.0) (0 until n).shuffled(java.util.Random(77)).take((n * drop).toInt()).toSet() else emptySet()
        val order = (0 until n).filter { it !in dropped }.toMutableList().also { if (permute) it.shuffle(java.util.Random(20261019)) }
        val suffix = (if (permute) ".perm" else "") + (if (snapshotFp16) ".snap16" else "") + (if (drop > 0.0) ".drop" else "")
        for ((audioVariant, textVariant) in pairs) {
            val audio = floats(File(dir, "audio.$audioVariant.f32"), n * PredictorRuntime.EMBEDDING_DIM)
            val text = floats(File(dir, "text.$textVariant.f32"), n * 384)
            val audioIndex = InMemoryVectorIndex(PredictorRuntime.EMBEDDING_DIM)
            val textIndex = InMemoryVectorIndex(384)
            for (row in order) {
                val id = TrackId(meta[row][0])
                audioIndex.adopt(id, audio.copyOfRange(row * PredictorRuntime.EMBEDDING_DIM, (row + 1) * PredictorRuntime.EMBEDDING_DIM))
                if (hasText[row].toInt() != 0) textIndex.adopt(id, text.copyOfRange(row * 384, (row + 1) * 384))
            }
            val storedAudio = audioIndex.storedEntries()
            val storedText = textIndex.storedEntries()
            val rows = order.map { meta[it] }.map { m ->
                val id = TrackId(m[0])
                SmartTrack(
                    id = id,
                    audio = storedAudio.getValue(id),
                    text = storedText[id],
                    meta = TrackMeta(
                        m[1].ifBlank { null }, m[2].ifBlank { null }, m[3].ifBlank { null }, m[4].ifBlank { null },
                        m[5].trim().toIntOrNull(), m[6].trim().toLongOrNull(),
                    ),
                )
            }
            val byId = meta.associateBy { it[0] }
            val snapshot = requireNotNull(SmartSnapshot.build(rows) { row ->
                byId[row.id.value]?.let { knowledge.descriptor(it[2].ifBlank { null }, null, row.text) }
            })
            if (snapshotFp16) {
                for (matrix in listOfNotNull(snapshot.centeredAudio, snapshot.centeredText, snapshot.centeredDescriptor)) {
                    for (i in matrix.indices) matrix[i] = java.lang.Float.float16ToFloat(java.lang.Float.floatToFloat16(matrix[i]))
                }
            }
            val eligible = BooleanArray(snapshot.size) { true }
            val started = System.nanoTime()
            File(dir, "picks.$audioVariant.$textVariant$suffix.tsv").bufferedWriter().use { out ->
                for (row in 0 until snapshot.size) {
                    val seed = snapshot.tracks[row].id
                    val chain = SmartChain(snapshot, null, eligible, typicalityWeight = 0f, tuning = tuning).build(
                        seedId = seed,
                        length = length,
                        timeFeatures = PredictorRuntime.timeFeatures(12, 3),
                    )
                    val ordered = JourneySequencer.order(snapshot, chain.rows, sameArtistCost = 0.5f)
                    out.write(seed.value + "\t" + ordered.joinToString(",") { snapshot.tracks[it].id.value } + "\n")
                }
            }
            println("vector shrink: $audioVariant/$textVariant$suffix ${snapshot.size} seeds in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }

    /**
     * The lossless option's read path: the persisted rows mapped from a file (never on the heap), each divided by a
     * norm computed exactly as InMemoryVectorIndex.normalizeInPlace does, must give the very floats adopt() stores.
     * Also times a full scan of dot products, heap arrays against the mapped buffer.
     */
    @Test
    fun mappedRowsMatchAdoptedRowsBitForBit() {
        val dir = System.getenv("VSHRINK_DIR")?.let(::File)?.takeIf(File::isDirectory) ?: return
        val dim = PredictorRuntime.EMBEDDING_DIM
        val n = File(dir, "meta.tsv").readLines().count(String::isNotEmpty)
        val file = File(dir, "audio.fp32.f32")
        val flat = floats(file, n * dim)
        val index = InMemoryVectorIndex(dim)
        for (row in 0 until n) index.adopt(TrackId(row.toString()), flat.copyOfRange(row * dim, (row + 1) * dim))
        val stored = index.storedEntries()
        val mapped = java.io.RandomAccessFile(file, "r").channel.use {
            it.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.size()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        }
        val norms = FloatArray(n) { row ->
            var sumOfSquares = 0f
            for (d in 0 until dim) { val c = mapped.get(row * dim + d); sumOfSquares += c * c }
            kotlin.math.sqrt(sumOfSquares)
        }
        var mismatches = 0
        for (row in 0 until n) {
            val expected = stored.getValue(TrackId(row.toString()))
            for (d in 0 until dim) {
                val value = if (norms[row] == 0f) mapped.get(row * dim + d) else mapped.get(row * dim + d) / norms[row]
                if (value.toRawBits() != expected[d].toRawBits()) mismatches++
            }
        }
        check(mismatches == 0) { "$mismatches of ${n * dim} floats differ" }
        // The text store goes through the same adopt(); its 384-d rows, read the mapped way, match too.
        val hasText = File(dir, "hasText.u8").readBytes()
        val textFile = File(dir, "text.fp32.f32")
        val textFlat = floats(textFile, n * 384)
        val textIndex = InMemoryVectorIndex(384)
        for (row in 0 until n) if (hasText[row].toInt() != 0) textIndex.adopt(TrackId(row.toString()), textFlat.copyOfRange(row * 384, (row + 1) * 384))
        val textStored = textIndex.storedEntries()
        val textMapped = java.io.RandomAccessFile(textFile, "r").channel.use {
            it.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.size()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        }
        var textMismatches = 0
        for (row in 0 until n) {
            val expected = textStored[TrackId(row.toString())] ?: continue
            var sumOfSquares = 0f
            for (d in 0 until 384) { val c = textMapped.get(row * 384 + d); sumOfSquares += c * c }
            val norm = kotlin.math.sqrt(sumOfSquares)
            for (d in 0 until 384) {
                val value = if (norm == 0f) textMapped.get(row * 384 + d) else textMapped.get(row * 384 + d) / norm
                if (value.toRawBits() != expected[d].toRawBits()) textMismatches++
            }
        }
        check(textMismatches == 0) { "$textMismatches text floats differ" }
        val heapRows = Array(n) { stored.getValue(TrackId(it.toString())) }
        val query = heapRows[0]
        val buffer = FloatArray(dim)
        repeat(3) {
            var t = System.nanoTime(); var sum = 0f
            repeat(20) { for (row in 0 until n) { val v = heapRows[row]; var acc = 0f; for (d in 0 until dim) acc += query[d] * v[d]; sum += acc } }
            val heapNs = (System.nanoTime() - t) / 20
            t = System.nanoTime(); var sum2 = 0f
            repeat(20) {
                for (row in 0 until n) {
                    mapped.position(row * dim); mapped.get(buffer); val inv = norms[row]
                    var acc = 0f; for (d in 0 until dim) acc += query[d] * (buffer[d] / inv); sum2 += acc
                }
            }
            val mappedNs = (System.nanoTime() - t) / 20
            check(sum == sum2) { "scan sums differ: $sum vs $sum2" }
            println("vector shrink mmap: $n rows bit-identical; full scan heap ${heapNs / 1000} us, mapped+normalise ${mappedNs / 1000} us")
        }
    }

    /**
     * A snapshot without its centred matrices: each centred element recomputed from the raw row, the corpus mean and a
     * per-row scale, with the build's own float operations, equals the matrix value bit for bit (audio and text).
     */
    @Test
    fun matrixFreeCentringIsBitIdentical() {
        val dir = System.getenv("VSHRINK_DIR")?.let(::File)?.takeIf(File::isDirectory) ?: return
        val meta = File(dir, "meta.tsv").readLines().filter(String::isNotEmpty).map { it.split('\t') }
        val n = meta.size
        val dim = PredictorRuntime.EMBEDDING_DIM
        val hasText = File(dir, "hasText.u8").readBytes()
        val audio = floats(File(dir, "audio.fp32.f32"), n * dim)
        val text = floats(File(dir, "text.fp32.f32"), n * 384)
        val audioIndex = InMemoryVectorIndex(dim)
        val textIndex = InMemoryVectorIndex(384)
        for (row in 0 until n) {
            audioIndex.adopt(TrackId(meta[row][0]), audio.copyOfRange(row * dim, (row + 1) * dim))
            if (hasText[row].toInt() != 0) textIndex.adopt(TrackId(meta[row][0]), text.copyOfRange(row * 384, (row + 1) * 384))
        }
        val a = audioIndex.storedEntries(); val t = textIndex.storedEntries()
        val rows = meta.map { m -> SmartTrack(TrackId(m[0]), a.getValue(TrackId(m[0])), t[TrackId(m[0])], meta = TrackMeta(null, null, null, null, null)) }
        val snapshot = requireNotNull(SmartSnapshot.build(rows))
        var mismatches = 0
        fun check(matrix: FloatArray, width: Int, raw: (Int, FloatArray) -> Boolean) {
            val row = FloatArray(width); val mean = FloatArray(width); var present = 0
            for (i in 0 until snapshot.size) if (raw(i, row)) { present++; for (d in 0 until width) mean[d] += row[d] }
            for (d in 0 until width) mean[d] /= present
            for (i in 0 until snapshot.size) {
                if (!raw(i, row)) continue
                var sumSq = 0.0
                for (d in 0 until width) { val v = row[d] - mean[d]; sumSq += v.toDouble() * v }
                val scale = 1f / kotlin.math.sqrt(sumSq).toFloat().coerceAtLeast(1e-12f)
                for (d in 0 until width) if (((row[d] - mean[d]) * scale).toRawBits() != matrix[i * width + d].toRawBits()) mismatches++
            }
        }
        check(snapshot.centeredAudio, dim) { i, out -> snapshot.rawAudio.copyInto(i, out); true }
        check(requireNotNull(snapshot.centeredText), 384) { i, out ->
            val v = rows[i].text
            if (v == null) false else {
                var ss = 0.0; for (x in v) ss += x.toDouble() * x
                val s = 1f / kotlin.math.sqrt(ss).toFloat().coerceAtLeast(1e-12f)
                for (d in 0 until 384) out[d] = v[d] * s
                true
            }
        }
        check(mismatches == 0) { "$mismatches centred floats differ" }
        // Descriptors are not index rows (pack PQ decode or the adapter MLP on the row's text): a matrix-free snapshot
        // would keep one centred row per distinct descriptor. Equal raw descriptors must give equal centred rows.
        val knowledge = ArtistKnowledge(
            MusicEntityResolver { System.getenv("VSHRINK_ASSETS")?.let { File(it, "music_entities_250k.bin") }?.takeIf(File::isFile)?.readBytes()?.let { b -> AssetBytes.of(b) } },
            loadAdapter = { System.getenv("VSHRINK_ASSETS")?.let { File(it, "artist_adapter.bin") }?.takeIf(File::isFile)?.readBytes() },
            loadPack = { System.getenv("VSHRINK_ASSETS")?.let { File(it, "artist_knowledge.bin") }?.takeIf(File::isFile)?.readBytes()?.let { b -> AssetBytes.of(b) } },
        )
        val described = rows.map { it.copy(meta = TrackMeta(null, meta.first { m -> m[0] == it.id.value }[2].ifBlank { null }, null, null, null)) }
        val descriptors = described.map { knowledge.descriptor(it.meta.artist, null, it.text) }
        val withDescriptors = requireNotNull(SmartSnapshot.build(described) { row -> descriptors[described.indexOf(row)] })
        val centredDescriptor = requireNotNull(withDescriptors.centeredDescriptor)
        val width = withDescriptors.descriptorDim
        val groups = HashMap<List<Int>, MutableList<Int>>()
        for (i in descriptors.indices) descriptors[i]?.let { d -> groups.getOrPut(d.map(Float::toRawBits)) { ArrayList() }.add(i) }
        var descriptorMismatches = 0
        for (members in groups.values) for (i in members.drop(1)) for (d in 0 until width) {
            if (centredDescriptor[i * width + d].toRawBits() != centredDescriptor[members[0] * width + d].toRawBits()) descriptorMismatches++
        }
        check(descriptorMismatches == 0) { "$descriptorMismatches centred descriptor floats differ between equal descriptors" }
        println("vector shrink descriptors: width $width, ${descriptors.count { it != null }} rows with one, ${groups.size} distinct; equal descriptors give bit-identical centred rows")
        println("vector shrink centring: audio ${snapshot.size}x$dim and text rows recomputed without matrices, bit-identical")
    }

    private fun floats(file: File, count: Int): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        check(buffer.remaining() == count) { "${file.name}: ${buffer.remaining()} floats, expected $count" }
        return FloatArray(count).also { buffer.get(it) }
    }
}
