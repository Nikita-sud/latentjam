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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays the reference SMART implementation's recorded model outputs through this port and asserts
 * the queues come out identical.
 *
 * The fixture holds the library matrices plus, per seed, the state vectors and candidate logits the
 * reference's own ONNX sessions produced at each hop. Feeding those back in isolates everything
 * this module actually owns — pool construction, geometric terms, metadata multipliers, the
 * skip/exclude rules and the selection loop — from the two graphs, which are the same files on both
 * sides. A drift of one constant or one filter shows up as a different chain.
 *
 * The fixture is ~8 MB of float matrices and is not committed; regenerate it with
 * `tools/export_parity_fixture.py` and point `SMART_PARITY_FIXTURE` at the output. Absent, this
 * test reports as skipped rather than failing, so it never blocks a normal build.
 */
class SmartChainParityTest {

    @Test
    fun `chains match the reference implementation on every recorded seed`() {
        val fixture = fixtureDir() ?: run {
            println("SKIP parity: set SMART_PARITY_FIXTURE to a directory from export_parity_fixture.py")
            return
        }

        val n = File(fixture, "dims.txt").readLines()
            .first { it.startsWith("N=") }.substringAfter('=').trim().toInt()
        val audio = floats(File(fixture, "audio.f32"))
        val text = floats(File(fixture, "text.f32"))
        val descriptor = floats(File(fixture, "desc.f32"))
        val hasText = bytes(File(fixture, "hasT.u8"))
        val hasDescriptor = bytes(File(fixture, "hasD.u8"))
        val energy = floats(File(fixture, "energy.f32"))
        val timeFeatures = floats(File(fixture, "time.f32"))
        val sessionFeatures = floats(File(fixture, "sess.f32"))

        val meta = File(fixture, "meta.tsv").readLines().map { line ->
            val f = line.split('\t')
            TrackMeta(
                title = f[0], artist = f[1], album = f[2], genre = f[3],
                year = f.getOrNull(4)?.takeIf { it.isNotBlank() }?.toInt(),
            )
        }
        assertEquals(n, meta.size, "meta.tsv rows must match dims.txt")

        val tracks = (0 until n).map { row ->
            SmartTrack(
                id = TrackId(row.toString()),
                audio = audio.copyOfRange(
                    row * PredictorRuntime.EMBEDDING_DIM,
                    (row + 1) * PredictorRuntime.EMBEDDING_DIM,
                ),
                text = if (hasText[row]) text.copyOfRange(row * 384, (row + 1) * 384) else null,
                descriptor = if (hasDescriptor[row]) {
                    descriptor.copyOfRange(row * 768, (row + 1) * 768)
                } else {
                    null
                },
                energy = energy[row],
                meta = meta[row],
            )
        }
        val snapshot = requireNotNull(SmartSnapshot.build(tracks)) { "snapshot must build" }
        assertEquals(n, snapshot.size, "no row may be dropped, or indices stop lining up")

        val seeds = File(fixture, "seeds.tsv").readLines().filter { it.isNotBlank() }
        assertTrue(seeds.isNotEmpty(), "fixture has no seeds")

        val failures = mutableListOf<String>()
        for (line in seeds) {
            val (tag, seedRow, _, _, _, chainLength) = line.split('\t').let {
                Row(it[0], it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt(), it[5].toInt())
            }
            val states = floats(File(fixture, "$tag.states.f32"))
            val logits = floats(File(fixture, "$tag.logits.f32"))
            val expectedPool = ints(File(fixture, "$tag.pool.i32"))
            val expectedChain = ints(File(fixture, "$tag.chain.i32"))

            val result = SmartChain(snapshot, ReplayRuntime(states, logits))
                .build(TrackId(seedRow.toString()), chainLength, timeFeatures, sessionFeatures)

            if (result.pool != expectedPool.toList()) {
                val firstDiff = expectedPool.indices.firstOrNull { result.pool.getOrNull(it) != expectedPool[it] }
                failures += "$tag: pool size=${result.pool.size} (reference ${expectedPool.size})" +
                    if (firstDiff == null) "" else ", diverges at $firstDiff " +
                        "(port=${result.pool.getOrNull(firstDiff)}, reference=${expectedPool[firstDiff]})"
                continue
            }
            if (result.rows != expectedChain.toList()) {
                failures += "$tag: chain\n      port=${result.rows}\n reference=${expectedChain.toList()}"
            }
        }
        assertTrue(failures.isEmpty(), "SMART port diverges from reference:\n" + failures.joinToString("\n"))
        println("parity: ${seeds.size} seeds, chains identical to the reference implementation")
    }

    private data class Row(
        val tag: String, val seed: Int, val states: Int,
        val logits: Int, val pool: Int, val chain: Int,
    )

    /**
     * Stands in for the ONNX sessions, handing back what the reference's sessions returned at the
     * same point in the walk.
     */
    private class ReplayRuntime(
        private val states: FloatArray,
        private val logits: FloatArray,
    ) : PredictorRuntime {
        private var encodeCalls = 0
        private var scoreCalls = 0
        private var coldCentroid: FloatArray? = null

        override suspend fun load() = Result.success(Unit)

        override fun encodeState(
            historySmall: FloatArray,
            historyMedium: FloatArray,
            historyLarge: FloatArray,
            timeFeatures: FloatArray,
            sessionFeatures: FloatArray,
        ): FloatArray {
            val dim = PredictorRuntime.EMBEDDING_DIM
            // Cold/no-history contract, mirrored by export_parity_fixture.py: the two slow-taste
            // inputs are the seed's audio and stay FIXED across hops — the chain re-encodes only
            // history_small (the reference re-encodes `features.copy(historySmall = ...)`; the port
            // passes the same fixed context.medium/large). A port that wrongly advanced them to the
            // newest token would break this. History-aware inputs have a separate test.
            check(historyMedium.contentEquals(historyLarge)) {
                "cold contract: history_medium and history_large are the same fixed centroid"
            }
            val fixed = coldCentroid
            if (fixed == null) {
                val newest = historySmall.copyOfRange(
                    (PredictorRuntime.CONTEXT_K - 1) * PredictorRuntime.TOKEN_DIM,
                    (PredictorRuntime.CONTEXT_K - 1) * PredictorRuntime.TOKEN_DIM + dim,
                )
                check(historyMedium.contentEquals(newest)) {
                    "cold contract: the slow-taste inputs start at the seed token"
                }
                coldCentroid = historyMedium.copyOf()
            } else {
                check(historyMedium.contentEquals(fixed)) {
                    "cold contract: the slow-taste inputs stay fixed at the seed across hops"
                }
            }
            val at = encodeCalls++
            return states.copyOfRange(at * dim, (at + 1) * dim)
        }

        override fun score(
            state: FloatArray,
            candidates: FloatArray,
        ): FloatArray {
            val at = scoreCalls++
            val size = PredictorRuntime.POOL_SIZE
            return logits.copyOfRange(at * size, (at + 1) * size)
        }

        override fun close() = Unit
    }

    private fun fixtureDir(): File? =
        System.getenv("SMART_PARITY_FIXTURE")?.let(::File)?.takeIf { File(it, "dims.txt").isFile }

    private fun floats(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also(buffer::get)
    }

    private fun ints(file: File): IntArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return IntArray(buffer.remaining()).also(buffer::get)
    }

    private fun bytes(file: File): BooleanArray =
        file.readBytes().let { raw -> BooleanArray(raw.size) { raw[it].toInt() != 0 } }
}
