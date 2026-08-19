/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test

/**
 * Measures whether a LIGHT, non-neural personalization layer (Rocchio centroids over the
 * objective content embeddings) can recover a listener's preferences — first on a SYNTHETIC
 * listener whose ground-truth taste the simulator knows exactly, then as a sanity read on the
 * real exported log.
 *
 * The world is real (the exported library's audio embeddings), the person is simulated: taste
 * anchors define a true preference; sessions of offers produce completions and skips by that
 * preference plus noise. The layer never sees the anchors — only the events — so "recovery"
 * is honest: rank correlation against the hidden truth on tracks the training never touched.
 *
 * Part B plugs the learned score into the SMART chain through the experimental tuning term
 * (default-off in production) and sweeps its weight: the question is what a personal term buys
 * (true-preference lift of chosen tracks) and what it costs (seed coherence, artist spacing) —
 * the objectivity guardrails.
 */
class PersonalizationSimulation {

    @Test
    fun `rocchio recovery and chain sweep`() {
        val fixture = System.getenv("SMART_PARITY_FIXTURE")?.let(::File)?.takeIf(File::isDirectory)
        val input = System.getenv("SMART_SIM_INPUT")?.let(::File)?.takeIf(File::isDirectory)
        if (fixture == null || input == null) {
            println("SKIP personalization sim: set SMART_PARITY_FIXTURE and SMART_SIM_INPUT")
            return
        }

        val n = File(fixture, "dims.txt").readLines()
            .first { it.startsWith("N=") }.substringAfter('=').trim().toInt()
        val dim = PredictorRuntime.EMBEDDING_DIM
        val audio = floats(File(fixture, "audio.f32"))
        val meta = File(fixture, "meta.tsv").readLines().map { it.split('\t') }
        val durations = File(input, "durations.tsv").readLines().map { it.trim().toLong() }

        // Centered unit space — the same geometry the chain and the app's centroids use.
        val mean = FloatArray(dim)
        for (row in 0 until n) for (d in 0 until dim) mean[d] += audio[row * dim + d] / n
        val unit = Array(n) { row ->
            val v = FloatArray(dim) { d -> audio[row * dim + d] - mean[d] }
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            FloatArray(dim) { d -> v[d] / norm }
        }
        fun cos(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (d in 0 until dim) s += (a[d] * b[d]).toDouble()
            return s
        }

        // ---------------------------------------------------------------- synthetic listener
        // Taste anchors spread by greedy max-min so the loves are genuinely distinct regions;
        // the hate is the row farthest from all loves. The layer under test never sees these.
        val anchors = mutableListOf(0)
        repeat(2) {
            val next = (0 until n).maxBy { row ->
                anchors.minOf { a -> -cos(unit[row], unit[a]) }
            }
            anchors.add(next)
        }
        val hated = (0 until n).maxBy { row -> anchors.sumOf { a -> -cos(unit[row], unit[a]) } }
        fun truePref(row: Int): Double {
            val love = anchors.maxOf { a -> cos(unit[row], unit[a]) }
            val hate = cos(unit[row], unit[hated]).coerceAtLeast(0.0)
            return 2.0 * love - 1.2 * hate
        }
        val prefs = (0 until n).map(::truePref)
        val median = prefs.sorted()[n / 2]
        fun completeProbability(row: Int): Double =
            0.10 + 0.80 / (1.0 + exp(-6.0 * (truePref(row) - median)))

        // 30 days of uniformly random offers: exposure-clean by construction, so recovery
        // measures the layer, not the offer policy.
        data class Event(val row: Int, val day: Int, val completed: Boolean)
        val rng = Random(42)
        val events = buildList {
            for (day in 0 until 30) {
                repeat(24) {
                    val row = rng.nextInt(n)
                    add(Event(row, day, rng.nextDouble() < completeProbability(row)))
                }
            }
        }

        fun rocchio(training: List<Event>, negativeWeight: Double): (Int) -> Double {
            val like = FloatArray(dim)
            val dislike = FloatArray(dim)
            var likes = 0
            var dislikes = 0
            for (event in training) {
                val v = unit[event.row]
                if (event.completed) {
                    for (d in 0 until dim) like[d] += v[d]
                    likes++
                } else {
                    for (d in 0 until dim) dislike[d] += v[d]
                    dislikes++
                }
            }
            fun normalized(sum: FloatArray, count: Int): FloatArray? {
                if (count == 0) return null
                val norm = sqrt(sum.sumOf { (it * it).toDouble() }).toFloat()
                if (norm <= 0f) return null
                return FloatArray(dim) { d -> sum[d] / norm }
            }
            val likeCentroid = normalized(like, likes)
            val dislikeCentroid = normalized(dislike, dislikes)
            return { row ->
                (likeCentroid?.let { cos(unit[row], it) } ?: 0.0) -
                    negativeWeight * (dislikeCentroid?.let { cos(unit[row], it) } ?: 0.0)
            }
        }

        fun spearman(scoreOf: (Int) -> Double, rows: List<Int>): Double {
            fun ranks(values: List<Double>): DoubleArray {
                val order = values.indices.sortedBy { values[it] }
                val out = DoubleArray(values.size)
                order.forEachIndexed { rank, index -> out[index] = rank.toDouble() }
                return out
            }
            val a = ranks(rows.map(scoreOf))
            val b = ranks(rows.map { prefs[it] })
            val ma = a.average(); val mb = b.average()
            var num = 0.0; var da = 0.0; var db = 0.0
            for (i in rows.indices) {
                num += (a[i] - ma) * (b[i] - mb)
                da += (a[i] - ma) * (a[i] - ma)
                db += (b[i] - mb) * (b[i] - mb)
            }
            return num / sqrt(da * db)
        }

        fun auc(scoreOf: (Int) -> Double, test: List<Event>): Double {
            val pos = test.filter { it.completed }.map { scoreOf(it.row) }
            val neg = test.filterNot { it.completed }.map { scoreOf(it.row) }
            if (pos.isEmpty() || neg.isEmpty()) return Double.NaN
            var wins = 0.0
            for (p in pos) for (q in neg) wins += if (p > q) 1.0 else if (p == q) 0.5 else 0.0
            return wins / (pos.size * neg.size)
        }

        println("== Part A: synthetic listener, Rocchio recovery ==")
        println("trainDays\tevents\tspearman(all-unseen)\tAUC(holdout)\tlike-only\tplaycount")
        for (trainDays in listOf(3, 7, 14, 30)) {
            val training = events.filter { it.day < trainDays }
            val holdout = events.filter { it.day >= trainDays }
            val seen = training.mapTo(HashSet()) { it.row }
            val unseen = (0 until n).filter { it !in seen }
            val layer = rocchio(training, negativeWeight = 0.5)
            val likeOnly = rocchio(training, negativeWeight = 0.0)
            val playCounts = training.filter { it.completed }
                .groupingBy { it.row }.eachCount()
            val popularity = { row: Int -> (playCounts[row] ?: 0).toDouble() }
            println(
                "%d\t%d\t%.3f\t%.3f\t%.3f\t%.3f".format(
                    trainDays,
                    training.size,
                    spearman(layer, unseen),
                    auc(layer, holdout),
                    auc(likeOnly, holdout),
                    auc(popularity, holdout),
                ),
            )
        }

        // ------------------------------------------------------------------- Part B: chain
        val tracks = (0 until n).map { row ->
            SmartTrack(
                id = TrackId(row.toString()),
                audio = audio.copyOfRange(row * dim, (row + 1) * dim),
                meta = TrackMeta(
                    title = meta[row][0],
                    artist = meta[row][1],
                    album = meta[row][2],
                    genre = meta[row][3],
                    year = meta[row].getOrNull(4)?.takeIf { it.isNotBlank() }?.toIntOrNull(),
                    durationMs = durations[row].takeIf { it > 0 },
                ),
            )
        }
        val snapshot = requireNotNull(SmartSnapshot.build(tracks))
        val seeds = File(input, "seeds.txt").readLines().filter { it.isNotBlank() }
            .map { TrackId(it.trim()) }
        val rowOf = tracks.mapIndexed { row, t -> t.id to row }.toMap()

        // The layer the chain would actually get: trained on the first half of the log.
        val trained = rocchio(events.filter { it.day < 14 }, negativeWeight = 0.5)
        val affinity = FloatArray(n) { row -> trained(row).toFloat() }

        println()
        println("== Part B: personal term in the SMART chain (default 0 = shipped) ==")
        println("weight\tmeanTruePref\tcosSeed\tcosStep\tartistRep%")
        for (weight in listOf(0f, 0.3f, 0.6f, 1.0f, 1.5f)) {
            var prefSum = 0.0
            var cosSeedSum = 0.0
            var cosStepSum = 0.0
            var picks = 0
            var artistRep = 0
            var pairs = 0
            for (seed in seeds) {
                val chain = SmartChain(
                    snapshot = snapshot,
                    runtime = null,
                    companionGroups = emptyList(),
                    tuning = ChainTuning(
                        personalAffinity = { row -> affinity[row] },
                        personalWeight = weight,
                    ),
                ).build(seedId = seed, length = 24, timeFeatures = FloatArray(5))
                val seedRow = rowOf.getValue(seed)
                var previous = seedRow
                for (row in chain.rows) {
                    prefSum += prefs[row]
                    cosSeedSum += cos(unit[seedRow], unit[row])
                    cosStepSum += cos(unit[previous], unit[row])
                    picks++
                    if (tracks[previous].meta.artist == tracks[row].meta.artist) artistRep++
                    pairs++
                    previous = row
                }
            }
            println(
                "%.1f\t%.3f\t%.3f\t%.3f\t%.1f".format(
                    weight, prefSum / picks, cosSeedSum / picks, cosStepSum / picks,
                    100.0 * artistRep / pairs,
                ),
            )
        }

        // ------------------------------------------- Part C: the real log, sanity read only
        val idsFile = File(input, "ids.txt")
        val historyFile = File(input, "history.log")
        if (idsFile.isFile && historyFile.isFile) {
            val ids = idsFile.readLines().filter { it.isNotBlank() }
            if (ids.size == n) {
                val rowOfReal = ids.mapIndexed { row, id -> id to row }.toMap()
                data class RealEvent(val row: Int, val atMs: Long, val completed: Boolean)
                val real = historyFile.readLines().mapNotNull { line ->
                    val p = line.trim().split('|')
                    if (p.size < 8) return@mapNotNull null
                    val row = rowOfReal[p[1]] ?: return@mapNotNull null
                    val at = p[2].toLongOrNull() ?: return@mapNotNull null
                    val completed = p[5] == "1"
                    val skipped = p[6] == "1"
                    if (!completed && !skipped) return@mapNotNull null
                    RealEvent(row, at, completed)
                }.sortedBy { it.atMs }
                if (real.size >= 200) {
                    val cut = real[real.size * 2 / 3].atMs
                    val train = real.filter { it.atMs < cut }
                    val test = real.filter { it.atMs >= cut }
                    val layer = rocchio(
                        train.map { Event(it.row, 0, it.completed) },
                        negativeWeight = 0.5,
                    )
                    val counts = train.filter { it.completed }
                        .groupingBy { it.row }.eachCount()
                    println()
                    println("== Part C: real log (noisy, secondary) ==")
                    println(
                        "events=%d train=%d test=%d AUC(rocchio)=%.3f AUC(playcount)=%.3f".format(
                            real.size, train.size, test.size,
                            auc(layer, test.map { Event(it.row, 0, it.completed) }),
                            auc(
                                { row -> (counts[row] ?: 0).toDouble() },
                                test.map { Event(it.row, 0, it.completed) },
                            ),
                        ),
                    )

                    // Daypart-conditional Rocchio: one centroid pair per phase of day (the
                    // harness runs in the listener's own timezone, so local hours are honest),
                    // falling back to the global layer where a phase has too little training.
                    fun daypartOf(atMs: Long): Int {
                        val calendar = java.util.Calendar.getInstance()
                        calendar.timeInMillis = atMs
                        return when (calendar.get(java.util.Calendar.HOUR_OF_DAY)) {
                            in 6..11 -> 0
                            in 12..17 -> 1
                            in 18..23 -> 2
                            else -> 3
                        }
                    }
                    val byPart = train.groupBy { daypartOf(it.atMs) }
                    val partLayers = byPart.mapValues { (_, partTrain) ->
                        if (partTrain.size >= 40) {
                            rocchio(
                                partTrain.map { Event(it.row, 0, it.completed) },
                                negativeWeight = 0.5,
                            )
                        } else {
                            layer
                        }
                    }
                    var wins = 0.0
                    var pairsCount = 0
                    for (part in 0..3) {
                        val partTest = test.filter { daypartOf(it.atMs) == part }
                        val partLayer = partLayers[part] ?: layer
                        val pos = partTest.filter { it.completed }.map { partLayer(it.row) }
                        val neg = partTest.filterNot { it.completed }.map { partLayer(it.row) }
                        for (a in pos) for (b in neg) {
                            wins += if (a > b) 1.0 else if (a == b) 0.5 else 0.0
                            pairsCount++
                        }
                    }
                    val daypartAuc = if (pairsCount > 0) wins / pairsCount else Double.NaN
                    println(
                        "AUC(rocchio per-daypart)=%.3f  (phase train sizes: %s)".format(
                            daypartAuc,
                            byPart.entries.sortedBy { it.key }
                                .joinToString { entry -> entry.key.toString() + ":" + entry.value.size },
                        ),
                    )
                }
            }
        }
    }

    private fun floats(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
}
