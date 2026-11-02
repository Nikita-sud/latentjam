/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * LIFE-03 at library scale (docs/analysis-094-segments.md): 50,000 tracks of 960 dims, analysed with a checkpoint per
 * 1,024 tracks, then a cold start on the same files. The segmented store restores every row; the single file (the
 * control) stops at its 64 MiB envelope and the rest is analysed again.
 */
internal class SegmentedSnapshotLibraryTest {
    private val directory: File = Files.createTempDirectory("lj-50k").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private val library = (0 until TRACKS).map { TrackDescriptor(id = TrackId("${1_000_000_000L + it}"), audioUri = "content://media/external/audio/media/$it", durationMs = 200_000) }

    /** Vectors made on request, so the test never holds a second copy of the library's floats. */
    private class Generated : HashMap<TrackId, FloatArray>() {
        override fun get(key: TrackId): FloatArray {
            var state = key.value.toLong() * 6364136223846793005L + 1442695040888963407L
            return FloatArray(960) {
                state = state * 6364136223846793005L + 1442695040888963407L
                ((state ushr 40).toFloat() / (1L shl 24).toFloat()) - 0.5f
            }
        }
    }

    private fun launch(store: IndexStore, backend: FakeEmbeddingBackend) = DefaultSimilarityEngine(
        backend = backend,
        index = InMemoryVectorIndex(960, HalfRowStore(960)),
        store = store,
        config = SmartEngineConfig(embeddingDim = 960, modelVersion = "v"),
        dispatcher = Dispatchers.Default.limitedParallelism(1, "segments-50k"),
    )

    private fun embedsOnSecondLaunch(store: () -> IndexStore): Pair<Int, Int> = runBlocking {
        val first = FakeEmbeddingBackend(Generated())
        launch(store(), first).apply {
            initialize()
            for (chunk in library.chunked(1_024)) {
                stageLibraryIndex(chunk)
                persistPendingAnalysis()
            }
        }
        val second = FakeEmbeddingBackend(Generated())
        launch(store(), second).apply {
            initialize()
            synchronizeLibrary(library)
            for (chunk in library.chunked(1_024)) {
                stageLibraryIndex(chunk)
                persistPendingAnalysis()
            }
        }
        first.embedCalls to second.embedCalls
    }

    @Test
    fun aFiftyThousandSongLibraryIsNotAnalysedAgainAfterARestart() {
        val dir = File(directory, "segmented").apply { mkdirs() }
        val (first, second) = embedsOnSecondLaunch {
            VersionedIndexStore("smart_index") { name ->
                SegmentedIndexStore(name, { FileIndexStore(dir, it, half = true) }, { dir.list()?.toList().orEmpty() })
            }
        }
        println("LJ_SEGMENTS segmented first=$first second=$second files=${dir.list()!!.sorted()}")
        assertEquals(TRACKS, first)
        assertEquals(0, second)
    }

    @Test
    fun theSingleFileControlAnalysesTheRowsAboveItsEnvelopeAgain() {
        val dir = File(directory, "single").apply { mkdirs() }
        val (first, second) = embedsOnSecondLaunch {
            VersionedIndexStore("smart_index") { name -> FileIndexStore(dir, name, half = true) }
        }
        println("LJ_SEGMENTS single first=$first second=$second")
        assertEquals(TRACKS, first)
        assertTrue(second > 10_000, "the control must reproduce LIFE-03 (re-embedded $second)")
    }

    private companion object {
        const val TRACKS = 50_000
    }
}
