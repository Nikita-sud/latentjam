/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Full planner parity and opt-in timings; synthetic fixtures and a stand-in predictor exclude ORT time. */
class ChainPlanningDeviceTest {
    @Test
    fun nativePlanningKeepsEveryPickAndScore() {
        assumeTrue(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        val dots = TrackedDots()
        // Just above the native threshold; build the immutable snapshot once for both backends.
        val snapshot = library(4_200, dots)
        for ((caseIndex, mode) in modes(snapshot.size).withIndex()) {
            val runner = Runner(snapshot, mode)
            repeat(2) { seedIndex ->
                val seed = mode.seed(snapshot.size, seedIndex)
                val results = arrayOfNulls<Planned>(2)
                for (native in backendOrder(caseIndex + seedIndex)) {
                    dots.native = native
                    results[if (native) 1 else 0] = runner.plan(seed, 12, trace = true)
                }
                val portable = requireNotNull(results[0])
                val native = requireNotNull(results[1])
                assertEquals("${mode.name}: selected rows", portable.chain.rows, native.chain.rows)
                assertEquals("${mode.name}: retrieved rows", portable.chain.pool, native.chain.pool)
                assertEquals("${mode.name}: walk", portable.chain.walk, native.chain.walk)
                assertEquals("${mode.name}: playback order", portable.ordered, native.ordered)
                assertEquals(portable.traces.size, native.traces.size)
                portable.traces.zip(native.traces).forEach { (a, b) -> assertTraceEquals(a, b) }
            }
        }
        assertTrue("full planning must exercise the native backend", dots.successes > 0)
        assertTrue("native state retrieval must run, not only snapshot reference cosines", dots.stateSuccesses > 0)
    }

    /** -e chainPlanningBenchmark 1; both backends alternate on every first/warmup/measured pair. */
    @Test
    fun benchmarkNativeAndPortablePlanning() {
        assumeTrue(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        assumeTrue(InstrumentationRegistry.getArguments().getString("chainPlanningBenchmark") == "1")
        for ((sizeIndex, n) in listOf(3_000, 10_000).withIndex()) {
            val dots = TrackedDots()
            val snapshot = library(n, dots)
            // Production's 12-track top-up plus a marked 20-track queue and default 40-track reference.
            for ((caseIndex, mode) in modes(n).withIndex()) {
                val length = listOf(12, 20, 40)[caseIndex]
                val runner = Runner(snapshot, mode)
                val first = DoubleArray(2)
                val times = Array(2) { DoubleArray(REPEATS) }
                val checksums = LongArray(2) { 17L }
                val callsBefore = dots.successes
                val statesBefore = dots.stateSuccesses
                repeat(WARMUP + REPEATS) { round ->
                    val seed = mode.seed(n, round % 3)
                    val rows = arrayOfNulls<List<Int>>(2)
                    for (native in backendOrder(round + caseIndex + sizeIndex)) {
                        val backend = if (native) 1 else 0
                        dots.native = native
                        val started = System.nanoTime()
                        val plan = runner.plan(seed, length, trace = false)
                        val ms = (System.nanoTime() - started) / 1e6
                        rows[backend] = plan.ordered
                        if (round == 0) first[backend] = ms
                        if (round >= WARMUP) {
                            times[backend][round - WARMUP] = ms
                            for (row in plan.ordered) checksums[backend] = checksums[backend] * 31 + row
                        }
                    }
                    assertEquals("rows=$n mode=${mode.name} round=$round", rows[0], rows[1])
                }
                assertEquals(checksums[0], checksums[1])
                if (n == 10_000) {
                    assertTrue("10k planning must call native code", dots.successes > callsBefore)
                    if (mode.tuning.continueAfterExhaustion) {
                        assertTrue("10k state retrieval must call native code", dots.stateSuccesses > statesBefore)
                    }
                }
                for (backend in 0..1) {
                    times[backend].sort()
                    println(
                        "LJ_CHAIN rows=$n mode=${mode.name} length=$length " +
                            "backend=${if (backend == 0) "portable" else "native"} " +
                            "first_ms=${first[backend]} median_ms=${times[backend][REPEATS / 2]} " +
                            "checksum=${checksums[backend].toULong().toString(16)} " +
                            "native_calls=${if (backend == 0) 0 else dots.successes - callsBefore} " +
                            "native_state_calls=${if (backend == 0) 0 else dots.stateSuccesses - statesBefore}",
                    )
                }
            }
        }
    }

    private class TrackedDots : BatchDotProducts {
        var native = false
        var successes = 0
        var stateSuccesses = 0
        override fun compute(
            matrix: FloatArray, dim: Int, query: FloatArray, offset: Int,
            rows: IntArray, count: Int, out: FloatArray,
        ): Boolean {
            if (!native) return BatchDotProducts.Portable.compute(matrix, dim, query, offset, rows, count, out)
            return AndroidBatchDotProducts.compute(matrix, dim, query, offset, rows, count, out).also { ran ->
                if (ran) {
                    successes++
                    if (dim == SmartSnapshot.AUDIO_DIM && query !== matrix) stateSuccesses++
                }
            }
        }
    }

    private data class Mode(val name: String, val tuning: ChainTuning, val groups: List<Set<TrackId>>) {
        fun seed(n: Int, index: Int): TrackId =
            if (groups.isEmpty()) TrackId((index * (n / 9) + 7).toString()) else groups[index % 8].first()
    }

    private fun modes(n: Int): List<Mode> {
        val judged = ChainTuning(
            continueAfterExhaustion = true, neighbourhoodBonus = 0f, rerankWeights = Rerank.JUDGED_WEIGHTS,
            soundFloor = Rerank.SOUND_FLOOR, artistRunPenalty = 0.5f,
        )
        val marked = (0 until 8).map { g -> (0 until 20).map { TrackId(((g * 997 + it * 13) % n).toString()) }.toSet() }
        val broad = marked.flatten().toSet() + (0 until n / 3).map { TrackId((it * 3).toString()) }
        return listOf(
            Mode("judged", judged, emptyList()),
            Mode("judged-marked", judged.copy(companionPoints = Rerank.COMPANION_POINTS), marked + listOf(broad)),
            Mode("default", ChainTuning(), emptyList()),
        )
    }

    private class Runner(private val snapshot: SmartSnapshot, private val mode: Mode) {
        private val chain = SmartChain(snapshot, StandInPredictor(), companionGroups = mode.groups, tuning = mode.tuning)
        private val companions = mode.groups.takeIf { it.isNotEmpty() }?.let {
            CompanionMembership.build(snapshot.tracks.map { track -> track.id }, it)
        }
        fun plan(seed: TrackId, length: Int, trace: Boolean): Planned {
            val traces = mutableListOf<PickTrace>()
            val recorder: ((PickTrace) -> Unit)? = if (trace) { pick -> traces.add(pick) } else null
            val result = chain.build(seed, length, FloatArray(5), trace = recorder)
            val ordered = JourneySequencer.order(
                snapshot, result.rows, sameArtistCost = mode.tuning.artistRunPenalty, companions = companions,
                togetherCost = mode.tuning.companionPoints?.together ?: 0f,
            )
            return Planned(result, ordered, traces)
        }
    }

    private data class Planned(val chain: ChainResult, val ordered: List<Int>, val traces: List<PickTrace>)

    private fun assertTraceEquals(a: PickTrace, b: PickTrace) {
        assertEquals(a.position, b.position)
        assertEquals(a.row, b.row)
        assertEquals(a.channel, b.channel)
        assertEquals(a.intentRow, b.intentRow)
        assertEquals(a.intentMoved, b.intentMoved)
        assertEquals(a.ring.toRawBits(), b.ring.toRawBits())
        assertEquals(a.inNeighbourhood, b.inNeighbourhood)
        assertEquals(a.neighbourhoodSize, b.neighbourhoodSize)
        assertEquals(a.poolSize, b.poolSize)
        assertEquals(a.logit.toRawBits(), b.logit.toRawBits())
        assertArrayEquals(a.terms.map(Float::toRawBits).toIntArray(), b.terms.map(Float::toRawBits).toIntArray())
        assertArrayEquals(a.candidates.map(Float::toRawBits).toIntArray(), b.candidates.map(Float::toRawBits).toIntArray())
    }

    private fun backendOrder(round: Int): List<Boolean> =
        if (round % 2 == 0) listOf(false, true) else listOf(true, false)

    /** Same clustered vectors and deterministic generator as the host ChainPlanningBenchmark. */
    private fun library(n: Int, dots: BatchDotProducts): SmartSnapshot {
        var state = 0x5eed_1234L
        fun noise(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return (state ushr 33).toFloat() / (1L shl 31).toFloat() - 0.5f
        }
        fun vector(dim: Int, style: Int, artist: Int, spread: Float): FloatArray {
            val values = FloatArray(dim) { noise() * spread }
            values[style % dim] += 1f
            values[(artist * 7 + 3) % dim] += 0.6f
            val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
            for (i in values.indices) values[i] /= norm
            return values
        }
        return requireNotNull(SmartSnapshot.build((0 until n).map { row ->
            val artist = row / 8
            val style = artist % 40
            SmartTrack(
                TrackId(row.toString()), vector(960, style, artist, 0.08f),
                text = vector(384, style, artist, 0.1f), descriptor = vector(384, style, artist, 0.05f),
                energy = 0.5f + noise() * 0.4f,
                meta = TrackMeta("Title $row", "Artist $artist", "Album ${row / 12}", "Style $style", 1990 + style % 30),
            )
        }, batchDotProducts = dots))
    }

    private class StandInPredictor : PredictorRuntime {
        override suspend fun load(): Result<Unit> = Result.success(Unit)
        override fun encodeState(
            historySmall: FloatArray, historyMedium: FloatArray, historyLarge: FloatArray,
            timeFeatures: FloatArray, sessionFeatures: FloatArray,
        ): FloatArray {
            val newest = (PredictorRuntime.CONTEXT_K - 1) * PredictorRuntime.TOKEN_DIM
            return historySmall.copyOfRange(newest, newest + PredictorRuntime.EMBEDDING_DIM)
        }
        override fun score(state: FloatArray, candidates: FloatArray): FloatArray =
            FloatArray(PredictorRuntime.POOL_SIZE) { candidates[it * PredictorRuntime.SCORER_INPUT_DIM] * 4f }
        override fun close(): Unit = Unit
    }

    private companion object {
        const val WARMUP = 4
        const val REPEATS = 7
    }
}
