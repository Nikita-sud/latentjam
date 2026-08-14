/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test

/**
 * Offline what-if harness: the REAL chain over a real exported library, swept across candidate
 * tunings, reported as one metrics row per configuration.
 *
 * Not a correctness test — it never asserts. It exists so a constant changes only after the full
 * production code path (pool, quota, nesting, metadata multipliers) has shown the trade on the
 * listener's actual data. Gated on env vars and skipped otherwise:
 * `SMART_PARITY_FIXTURE` for the library snapshot, `SMART_SIM_INPUT` for groups/durations/seeds.
 */
class SmartChainSimulation {

    @Test
    fun `sweep tunings over the exported library`() {
        val fixture = System.getenv("SMART_PARITY_FIXTURE")?.let(::File)?.takeIf(File::isDirectory)
        val input = System.getenv("SMART_SIM_INPUT")?.let(::File)?.takeIf(File::isDirectory)
        if (fixture == null || input == null) {
            println("SKIP sim: set SMART_PARITY_FIXTURE and SMART_SIM_INPUT")
            return
        }

        val n = File(fixture, "dims.txt").readLines()
            .first { it.startsWith("N=") }.substringAfter('=').trim().toInt()
        val audio = floats(File(fixture, "audio.f32"))
        val meta = File(fixture, "meta.tsv").readLines().map { it.split('\t') }
        val durations = File(input, "durations.tsv").readLines().map { it.trim().toLong() }
        val tracks = (0 until n).map { row ->
            SmartTrack(
                id = TrackId(row.toString()),
                audio = audio.copyOfRange(
                    row * PredictorRuntime.EMBEDDING_DIM,
                    (row + 1) * PredictorRuntime.EMBEDDING_DIM,
                ),
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

        val groups = File(input, "groups.tsv").readLines().filter { it.isNotBlank() }.map { line ->
            val f = line.split('\t')
            f[0] to f[1].split(',').map { TrackId(it) }.toSet()
        }
        val groupSets = groups.map { it.second }
        val memberOfAny = groupSets.flatten().toSet()
        val seeds = File(input, "seeds.txt").readLines().filter { it.isNotBlank() }
            .map { TrackId(it.trim()) }

        // Centered unit vectors for the metrics, matching the chain's own geometry.
        val dim = PredictorRuntime.EMBEDDING_DIM
        val mean = FloatArray(dim)
        for (t in tracks) for (d in 0 until dim) mean[d] += t.audio[d] / n
        val centered = tracks.map { t ->
            val v = FloatArray(dim) { d -> t.audio[d] - mean[d] }
            val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            FloatArray(dim) { d -> v[d] / norm }
        }
        fun cos(a: Int, b: Int): Double {
            var s = 0.0
            for (d in 0 until dim) s += (centered[a][d] * centered[b][d]).toDouble()
            return s
        }

        val rowOf = tracks.mapIndexed { row, t -> t.id to row }.toMap()
        data class Config(val bonus: Float, val margin: Float, val duration: Boolean)
        val configs = buildList {
            for (bonus in listOf(0.17f, 0.30f, 0.50f)) {
                for (margin in listOf(Float.POSITIVE_INFINITY, 0.6f, 0.3f)) {
                    add(Config(bonus, margin, true))
                }
            }
            add(Config(0.17f, Float.POSITIVE_INFINITY, false)) // shipped baseline
        }

        println("bonus\tmargin\tdur\tthemeShare\tcosSeed\tcosStep\tshort%\tlong%\tartistRep%")
        for (config in configs) {
            var themeNum = 0.0; var themeDen = 0
            var cosSeedSum = 0.0; var cosStepSum = 0.0; var cosCount = 0
            var short = 0; var long = 0; var picks = 0; var artistRep = 0; var pairs = 0
            for (seed in seeds) {
                val chain = SmartChain(
                    snapshot = snapshot,
                    runtime = null,
                    companionGroups = groupSets,
                    tuning = ChainTuning(
                        companionBonus = config.bonus,
                        quotaMargin = config.margin,
                        durationSanity = config.duration,
                    ),
                ).build(seedId = seed, length = 24, timeFeatures = FloatArray(5))
                val seedRow = rowOf.getValue(seed)
                val seedGroups = groupSets.filter { seed in it }
                var previous = seedRow
                for (row in chain.rows) {
                    val id = tracks[row].id
                    if (seedGroups.isNotEmpty()) {
                        if (seedGroups.any { id in it }) themeNum++
                        themeDen++
                    }
                    cosSeedSum += cos(seedRow, row)
                    cosStepSum += cos(previous, row)
                    cosCount++
                    val d = durations[row]
                    if (d in 1 until 90_000) short++
                    if (d > 8 * 60_000) long++
                    picks++
                    if (tracks[previous].meta.artist == tracks[row].meta.artist) artistRep++
                    pairs++
                    previous = row
                }
            }
            val margin = if (config.margin.isFinite()) "%.1f".format(config.margin) else "inf"
            println(
                "%.2f\t%s\t%s\t%.3f\t%.3f\t%.3f\t%.1f\t%.1f\t%.1f".format(
                    config.bonus, margin, if (config.duration) "on" else "off",
                    themeNum / themeDen, cosSeedSum / cosCount, cosStepSum / cosCount,
                    100.0 * short / picks, 100.0 * long / picks, 100.0 * artistRep / pairs,
                ),
            )
        }
    }

    private fun floats(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
}
