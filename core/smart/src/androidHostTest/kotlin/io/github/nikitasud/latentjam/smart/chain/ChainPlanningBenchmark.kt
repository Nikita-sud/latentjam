/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Opt-in diagnostic: SMART_CHAIN_BENCHMARK=1, then run this host test with --rerun. Times one plan of the shipped
 * judged chain, with and without marked playlists (and of the default chain, for reference), the journey order
 * included, on synthetic libraries of 3,000 and 10,000 tracks, with a stand-in predictor so the per-hop state ranking
 * runs as in the app. ORT's own time is not part of it.
 *
 * Output: one `chain-bench` line per library size, mode and length with the median milliseconds per plan and a
 * checksum of every planned row, so a change that must not alter queues can be checked on the same libraries.
 */
class ChainPlanningBenchmark {
    @Test
    fun `measure judged and default planning on large synthetic libraries`() {
        if (System.getenv("SMART_CHAIN_BENCHMARK") != "1") return
        val judged = ChainTuning(
            continueAfterExhaustion = true,
            neighbourhoodBonus = 0f,
            rerankWeights = Rerank.JUDGED_WEIGHTS,
            soundFloor = Rerank.SOUND_FLOOR,
            artistRunPenalty = 0.5f,
        )
        for (n in listOf(3_000, 10_000)) {
            val snapshot = library(n)
            // Marked playlists as a listener might have them: eight of 20 tracks and a broad one holding them.
            val marked = (0 until 8).map { g ->
                (0 until 20).map { TrackId(((g * 997 + it * 13) % n).toString()) }.toSet()
            }
            val broad = marked.flatten().toSet() + (0 until n / 3).map { TrackId((it * 3).toString()) }
            val groups = marked + listOf(broad)
            val modes = listOf(
                Triple("judged", judged, emptyList<Set<TrackId>>()),
                Triple("judged-marked", judged.copy(companionPoints = Rerank.COMPANION_POINTS), groups),
                Triple("default", ChainTuning(), emptyList()),
            )
            for ((mode, tuning, companionGroups) in modes) {
                val chain = SmartChain(snapshot, StandInPredictor(), companionGroups = companionGroups, tuning = tuning)
                val companions = if (companionGroups.isEmpty()) null else
                    CompanionMembership.build(snapshot.tracks.map { it.id }, companionGroups)
                for (length in listOf(12, 20, 40)) {
                    // Seeds inside the marked playlists when there are any, so their points take part.
                    val seeds = (0 until SEEDS).map { i ->
                        if (companionGroups.isEmpty()) TrackId((i * (n / SEEDS) + 7).toString())
                        else marked[i % 8].first()
                    }
                    repeat(2) { chain.build(seeds[it], length, FloatArray(5)) }
                    var checksum = 17L
                    val times = seeds.map { seed ->
                        val start = System.nanoTime()
                        val planned = chain.build(seed, length, FloatArray(5)).rows
                        // The order step as the engine runs it.
                        val rows = JourneySequencer.order(
                            snapshot, planned, sameArtistCost = tuning.artistRunPenalty, companions = companions,
                            togetherCost = tuning.companionPoints?.together ?: 0f,
                        )
                        val elapsed = (System.nanoTime() - start) / 1e6
                        for (row in rows) checksum = checksum * 31 + row
                        elapsed
                    }.sorted()
                    println(
                        "chain-bench n=$n mode=$mode length=$length medianMs=${"%.1f".format(times[times.size / 2])} " +
                            "checksum=${checksum.toULong().toString(16)}",
                    )
                }
            }
        }
    }

    /** Clustered unit vectors: tracks of one artist share a centre, artists of one style share a broader one. */
    private fun library(n: Int): SmartSnapshot {
        var state = 0x5eed_1234L
        fun noise(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toFloat() / (1L shl 31).toFloat()) - 0.5f
        }
        fun vector(dim: Int, style: Int, artist: Int, spread: Float): FloatArray {
            val v = FloatArray(dim) { noise() * spread }
            v[style % dim] += 1f
            v[(artist * 7 + 3) % dim] += 0.6f
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            for (i in v.indices) v[i] /= norm
            return v
        }
        val tracks = (0 until n).map { row ->
            val artist = row / 8
            val style = artist % 40
            SmartTrack(
                id = TrackId(row.toString()),
                audio = vector(SmartSnapshot.AUDIO_DIM, style, artist, 0.08f),
                text = vector(SmartSnapshot.TEXT_DIM, style, artist, 0.1f),
                descriptor = vector(DESCRIPTOR_DIM, style, artist, 0.05f),
                energy = 0.5f + noise() * 0.4f,
                meta = TrackMeta(
                    "Title $row", "Artist $artist", "Album ${row / 12}", "Style $style", 1990 + style % 30,
                ),
            )
        }
        return requireNotNull(SmartSnapshot.build(tracks))
    }

    /** The newest history token as the state, the candidates' first audio value as the logit: cheap, deterministic. */
    private class StandInPredictor : PredictorRuntime {
        override suspend fun load(): Result<Unit> = Result.success(Unit)

        override fun encodeState(
            historySmall: FloatArray,
            historyMedium: FloatArray,
            historyLarge: FloatArray,
            timeFeatures: FloatArray,
            sessionFeatures: FloatArray,
        ): FloatArray {
            val newest = (PredictorRuntime.CONTEXT_K - 1) * PredictorRuntime.TOKEN_DIM
            return historySmall.copyOfRange(newest, newest + PredictorRuntime.EMBEDDING_DIM)
        }

        override fun score(state: FloatArray, candidates: FloatArray): FloatArray =
            FloatArray(PredictorRuntime.POOL_SIZE) { candidates[it * PredictorRuntime.SCORER_INPUT_DIM] * 4f }

        override fun close(): Unit = Unit
    }

    private companion object {
        const val SEEDS = 9
        const val DESCRIPTOR_DIM = 384
    }
}
