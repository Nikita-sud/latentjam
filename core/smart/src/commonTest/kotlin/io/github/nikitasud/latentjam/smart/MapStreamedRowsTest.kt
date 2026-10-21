/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.cluster.LibraryLayout
import io.github.nikitasud.latentjam.smart.cluster.MapComputationUnavailable
import io.github.nikitasud.latentjam.smart.cluster.MapMemoryBudget
import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorRows
import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorSource
import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Map's streamed layout reads fused rows from the engine on demand. It is only the same Map
 * if every row, and therefore the whole layout, matches the held-matrix path bit for bit.
 */
internal class MapStreamedRowsTest {

    // More than one chunk, so reads cross a chunk boundary; one track has no audio and one has no
    // metadata, so both single-modality hybrid rows are exercised.
    private val count = LibraryVectorRows.CHUNK_ROWS + 44
    private val tracks = List(count) { i ->
        TrackDescriptor(
            TrackId("t$i"),
            title = "Track $i",
            artist = "Artist ${i % 17}",
            genre = listOf("Rock", "Jazz", "Folk", "Pop", "Metal")[i % 5],
            audioUri = "test://$i",
        )
    }
    private val ids = tracks.map(TrackDescriptor::id)

    @Test
    fun streamedRowsAndLayoutEqualTheHeldMatrix() = runTest {
        val engine = hybridEngine()
        val space = assertNotNull(engine.libraryMixVectors(ids))
        assertEquals(LibraryVectorSource.AUDIO_AND_METADATA, space.source)
        val rows = assertNotNull(engine.libraryMixRows(ids))
        assertEquals(space.trackIds, rows.trackIds)
        assertEquals(space.dim, rows.dim)
        assertEquals(space.fingerprint, rows.fingerprint)
        val held = space.peekRows().copyOf()
        val row = FloatArray(rows.dim)
        for (i in 0 until rows.size) {
            rows.read(i, row)
            assertTrue(
                row.contentEquals(held.copyOfRange(i * rows.dim, (i + 1) * rows.dim)),
                "Row $i (${rows.trackIds[i].value}) differs from the held matrix",
            )
        }
        assertTrue(rows.isCurrent())

        // Two maps from the same input: one through the held matrix, one streamed.
        val expected = LibraryLayout.compute(space)
        val streamed = assertNotNull(LibraryLayout.compute(assertNotNull(engine.libraryMixRows(ids))))
        assertEquals(expected, streamed)

        // And again as a warm start, which is how a library change reaches the page.
        val previous = expected.drop(10).associate { it.trackId to floatArrayOf(it.x, it.y) }
        val warmHeld = LibraryLayout.compute(assertNotNull(engine.libraryMixVectors(ids)), previous)
        val warmStreamed = LibraryLayout.compute(assertNotNull(engine.libraryMixRows(ids)), previous)
        assertEquals(warmHeld, warmStreamed)
    }

    @Test
    fun aPopulationChangeDuringTheReadDiscardsTheLayout() = runTest {
        val engine = hybridEngine()
        val rows = assertNotNull(engine.libraryMixRows(ids))
        val row = FloatArray(rows.dim)
        rows.read(0, row)
        assertTrue(rows.isCurrent())
        // A track disappears between chunks: its row would read as zeros.
        engine.synchronizeLibrary(tracks.drop(1))
        assertFalse(rows.isCurrent())
        assertFalse(rows.keepsPopulation())
        assertNull(LibraryLayout.compute(rows), "Rows read across an index change must not be laid out")
    }

    @Test
    fun vectorsAnalysedDuringTheReadStillGiveALayout() = runTest {
        // The last stretch of an analysis: the space is already hybrid and audio keeps arriving
        // for tracks that have metadata, faster than one read of the rows. Every chunk read here
        // is followed by one more analysed track, so no read ever sees a still index.
        val pending = 8
        val backend = FakeEmbeddingBackend()
        val engine = hybridEngine(backend, withoutAudio = pending)
        val planned = assertNotNull(engine.libraryMixRows(ids))
        assertEquals(LibraryVectorSource.AUDIO_AND_METADATA, planned.source)
        var analysed = 0
        val scratch = FloatArray(planned.dim)
        val rows = LibraryVectorRows(
            trackIds = planned.trackIds,
            dim = planned.dim,
            source = planned.source,
            fingerprint = planned.fingerprint,
            readChunk = { start, count, out ->
                for (slot in 0 until count) {
                    planned.read(start + slot, scratch)
                    scratch.copyInto(out, slot * planned.dim)
                }
                if (analysed < pending) {
                    val id = tracks[analysed++].id
                    backend.vectors[id] = FloatArray(AUDIO_DIM) { d -> if (d == analysed % AUDIO_DIM) 1f else 0.1f }
                    engine.retryFailedTracks(listOf(id))
                    engine.indexLibrary(tracks)
                }
            },
            verify = planned::isCurrent,
            verifyPopulation = planned::keepsPopulation,
        )

        val layout = assertNotNull(LibraryLayout.compute(rows), "Analysis progress must not discard the map")
        assertEquals(ids.toSet(), layout.map { it.trackId }.toSet())
        assertTrue(layout.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue(analysed > 1, "The index must have moved during the read")
        assertFalse(rows.isCurrent(), "The layout is filed under a fingerprint the next visit no longer matches")
        assertTrue(rows.keepsPopulation())

        // Once the index is still, the next visit's rows are current and lay out exactly as held.
        val settled = assertNotNull(engine.libraryMixRows(ids))
        assertTrue(settled.fingerprint != planned.fingerprint)
        val redrawn = assertNotNull(LibraryLayout.compute(settled))
        assertTrue(settled.isCurrent())
        assertEquals(LibraryLayout.compute(assertNotNull(engine.libraryMixVectors(ids))), redrawn)
    }

    @Test
    fun aBudgetMeasuredBeforeTheRowsExistAlreadyCoversTheirChunk() = runTest {
        // The page measures free memory once, before the rows allocate their chunk buffer, and
        // hands that figure on. It must suffice exactly: the requirement counts the chunk itself.
        val engine = hybridEngine()
        val rows = assertNotNull(engine.libraryMixRows(ids))
        val required = MapMemoryBudget.projectionStreamedBytes(rows.size, rows.dim, LibraryLayout.experimentalBackend)
        assertNotNull(LibraryLayout.compute(rows, workBudgetBytes = required))
        val refused = assertFailsWith<MapComputationUnavailable> {
            LibraryLayout.compute(assertNotNull(engine.libraryMixRows(ids)), workBudgetBytes = required - 1)
        }
        assertTrue(refused.memoryLimited)
    }

    @Test
    fun theEngineLockIsFreeBetweenChunks() = runTest {
        val engine = hybridEngine()
        val rows = assertNotNull(engine.libraryMixRows(ids))
        val row = FloatArray(rows.dim)
        for (i in 0 until rows.size) {
            rows.read(i, row)
            // Another engine call completes mid-read, which a lock held for the pass would block.
            if (i == LibraryVectorRows.CHUNK_ROWS - 1) assertNotNull(engine.libraryMixCoverage(ids))
        }
        assertTrue(rows.isCurrent())
    }

    @Test
    fun theDefaultRowsServeABuiltSpace() = runTest {
        val engine = hybridEngine()
        val space = assertNotNull(engine.libraryMixVectors(ids))
        val rows = LibraryVectorRows.of(space)
        val row = FloatArray(rows.dim)
        rows.read(rows.size - 1, row)
        assertTrue(row.contentEquals(space.peekRows().copyOfRange((rows.size - 1) * rows.dim, rows.size * rows.dim)))
        assertTrue(rows.isCurrent())
    }

    private suspend fun TestScope.hybridEngine(
        backend: FakeEmbeddingBackend = FakeEmbeddingBackend(),
        withoutAudio: Int = 1,
    ): DefaultSimilarityEngine {
        var state = 0x5EEDL
        fun next(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toInt() % 2000) / 1000f - 1f
        }
        tracks.drop(withoutAudio).forEach { backend.vectors[it.id] = FloatArray(AUDIO_DIM) { next() } }
        val engine = DefaultSimilarityEngine(
            backend = backend,
            index = InMemoryVectorIndex(AUDIO_DIM),
            store = FakeIndexStore(),
            config = SmartEngineConfig(embeddingDim = AUDIO_DIM, modelVersion = "map-stream-test"),
            dispatcher = StandardTestDispatcher(testScheduler),
            textEncoder = object : TextEncoder {
                override suspend fun load(): Result<Unit> = Result.success(Unit)
                override fun encode(metadata: String): FloatArray {
                    val seed = metadata.hashCode()
                    return FloatArray(TextEncoder.TEXT_DIM) { d -> ((seed * 31 + d * 7919) % 1000) / 1000f }
                }
                override fun close() = Unit
            },
            textIndex = InMemoryVectorIndex(TextEncoder.TEXT_DIM),
            textStore = FakeIndexStore(),
        )
        engine.initialize()
        engine.ensureMetadataVectors(tracks.dropLast(1))
        engine.indexLibrary(tracks)
        return engine
    }

    private companion object {
        const val AUDIO_DIM = 24
    }
}
