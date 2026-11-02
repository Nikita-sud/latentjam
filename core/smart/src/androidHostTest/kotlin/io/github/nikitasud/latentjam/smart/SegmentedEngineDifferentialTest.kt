/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.oracle093.FileIndexStore093
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The engine on the segmented store against the engine on the frozen 0.9.3 single file (docs/analysis-094-segments.md,
 * G-e at engine level): the same scripts of analysis, tag and file changes, removals, failures, clears and restarts,
 * and after every restart the same index, row for row and in the same order, with the same identities.
 */
internal class SegmentedEngineDifferentialTest {
    private val directory: File = Files.createTempDirectory("lj-segments-engine").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private class Side(val store: () -> IndexStore) {
        var index = InMemoryVectorIndex(DIM)
        var backend = FakeEmbeddingBackend()
        var engine = start()

        fun start(): DefaultSimilarityEngine {
            index = InMemoryVectorIndex(DIM)
            backend = FakeEmbeddingBackend(backend.vectors).also { it.failures.putAll(backend.failures) }
            return DefaultSimilarityEngine(
                backend = backend,
                index = index,
                store = store(),
                config = SmartEngineConfig(embeddingDim = DIM, modelVersion = "v"),
                dispatcher = Dispatchers.Default.limitedParallelism(1, "segments-diff"),
            ).also { engine = it }
        }

        /** The index as the engine restored it: ids in order, each vector as halves. */
        fun view(): List<Pair<String, List<Short>>> =
            index.ids().map { id -> id.value to index.vector(id)!!.map(HalfFloat::toHalf) }
    }

    @Test
    fun theEngineRestoresWhatItRestoresFromThe093File() = runBlocking {
        repeat(SCRIPTS) { script ->
            val random = Random(script)
            val dir = File(directory, "s$script").apply { mkdirs() }
            val oracleDir = File(directory, "o$script").apply { mkdirs() }
            val segmented = Side { SegmentedIndexStore("smart_index.v.bin", { FileIndexStore(dir, it, half = true) }, { dir.list()!!.toList() }, 1 + random.nextInt(4)) }
            val oracle = Side { FileIndexStore093(oracleDir, "smart_index.v.bin", half = true) }
            val sides = listOf(segmented, oracle)
            val revisions = HashMap<Int, Int>()
            fun track(i: Int) = TrackDescriptor(id = TrackId("t$i"), audioUri = "test://t$i", durationMs = 200_000, sourceRevision = "r${revisions[i] ?: 0}")
            fun vector(i: Int) = FloatArray(DIM) { d -> (((i + 1) * 37 + d * 11 + (revisions[i] ?: 0) * 5) % 101) / 101f - 0.4f }
            val library = LinkedHashSet<Int>()
            for (side in sides) side.engine.initialize()
            repeat(STEPS) { step ->
                val label = "script $script step $step"
                when (random.nextInt(10)) {
                    0, 1, 2 -> {
                        val added = (0 until 1 + random.nextInt(5)).map { random.nextInt(30) }.filter { it !in library }.distinct()
                        library += added
                        for (i in added) {
                            val fails = random.nextInt(6) == 0
                            for (side in sides) {
                                side.backend.vectors[TrackId("t$i")] = vector(i)
                                if (fails) side.backend.failures[TrackId("t$i")] = EngineError.InvalidAudio()
                            }
                        }
                        val chunk = library.map(::track)
                        for (side in sides) {
                            side.engine.stageLibraryIndex(chunk)
                            side.engine.persistPendingAnalysis()
                        }
                    }
                    3 -> library.randomOrNull(random)?.let { i ->
                        // The file changed: a new revision, so a new identity and a new vector.
                        revisions[i] = (revisions[i] ?: 0) + 1
                        for (side in sides) {
                            side.backend.vectors[TrackId("t$i")] = vector(i)
                            side.backend.failures.remove(TrackId("t$i"))
                        }
                    }
                    4 -> library.randomOrNull(random)?.let { library.remove(it) }
                    5 -> {
                        val listed = library.map(::track)
                        for (side in sides) {
                            side.engine.synchronizeLibrary(listed)
                            side.engine.stageLibraryIndex(listed)
                            side.engine.persistPendingAnalysis()
                        }
                    }
                    6, 7 -> {
                        for (side in sides) {
                            side.start().initialize()
                            side.engine.synchronizeLibrary(library.map(::track))
                        }
                        assertEquals(oracle.view(), segmented.view(), "$label: restored index")
                    }
                    8 -> if (random.nextInt(6) == 0) for (side in sides) side.engine.clearAnalysis()
                    9 -> {
                        val listed = library.map(::track)
                        for (side in sides) {
                            side.engine.stageLibraryIndex(listed)
                            side.engine.persistPendingAnalysis()
                        }
                    }
                }
            }
            for (side in sides) side.start().initialize()
            assertEquals(oracle.view(), segmented.view(), "script $script final restore")
        }
    }

    private companion object {
        const val DIM = 8
        const val SCRIPTS = 60
        const val STEPS = 50
    }
}
