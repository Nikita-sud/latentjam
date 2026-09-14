/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.cluster.LibraryVectorSource
import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MapCoverageCacheTest {
    private val first = TrackDescriptor(TrackId("first"), title = "First", genre = "Rock", audioUri = "test://first")
    private val second = TrackDescriptor(TrackId("second"), title = "Second", genre = "Rock", audioUri = "test://second")

    @Test
    fun warmVisitsDoNotReadOrCopyEmbeddingRowsAndChangedPopulationStillInvalidates() = runTest {
        val audio = CountingIndex(InMemoryVectorIndex(3))
        val engine = makeEngine(audio, StandardTestDispatcher(testScheduler))
        engine.initialize()
        engine.indexLibrary(listOf(first, second))
        val ids = mutableListOf(first.id, second.id)
        val cold = assertNotNull(engine.libraryMixCoverage(ids))
        val reads = audio.reads
        assertTrue(reads > 0)
        repeat(5) {
            assertEquals(cold.fingerprint, engine.libraryMixCoverage(ids.toList())?.fingerprint)
        }
        assertEquals(reads, audio.reads, "Warm visits must not copy/hash every audio embedding again")

        ids.removeAt(0)
        val smaller = assertNotNull(engine.libraryMixCoverage(ids))
        assertEquals(listOf(second.id), smaller.trackIds)
        assertTrue(audio.reads > reads, "The cache key must own its ID snapshot")
        assertNotEquals(cold.fingerprint, smaller.fingerprint)
    }

    @Test
    fun buildingAndConsumingTheMatrixAlsoPrimesSmallCoverageCache() = runTest {
        val audio = CountingIndex(InMemoryVectorIndex(3))
        val engine = makeEngine(audio, StandardTestDispatcher(testScheduler))
        engine.initialize()
        engine.indexLibrary(listOf(first, second))
        val ids = listOf(first.id, second.id)
        val space = assertNotNull(engine.libraryMixVectors(ids))
        space.takeRows()
        val reads = audio.reads
        assertEquals(space.fingerprint, engine.libraryMixCoverage(ids)?.fingerprint)
        assertEquals(reads, audio.reads)

        val features = assertNotNull(engine.libraryMixFeatures(listOf(first.id)))
        features.vectorSpace.takeRows()
        val featureReads = audio.reads
        assertEquals(features.vectorSpace.fingerprint, engine.libraryMixCoverage(listOf(first.id))?.fingerprint)
        assertEquals(featureReads, audio.reads)
    }

    @Test
    fun changedAudioInvalidatesCachedFingerprintEvenWithTheSameIds() = runTest {
        val audio = CountingIndex(InMemoryVectorIndex(3))
        val backend = backend()
        val engine = makeEngine(audio, StandardTestDispatcher(testScheduler), backend)
        engine.initialize()
        engine.indexLibrary(listOf(first))
        val ids = listOf(first.id)
        val old = assertNotNull(engine.libraryMixCoverage(ids))
        val changed = first.copy(audioUri = "test://replacement")
        engine.synchronizeLibrary(listOf(changed))
        assertNull(engine.libraryMixCoverage(ids), "Invalidated media cannot keep its old map vector")
        backend.vectors[first.id] = floatArrayOf(0f, 0f, 1f)
        engine.indexLibrary(listOf(changed))
        val updated = assertNotNull(engine.libraryMixCoverage(ids))
        assertNotEquals(old.fingerprint, updated.fingerprint)
        engine.synchronizeLibrary(emptyList())
        assertNull(engine.libraryMixCoverage(ids), "Deleted tracks must leave cached coverage")
    }

    @Test
    fun metadataPromotionAndRetaggingInvalidateWarmCoverage() = runTest {
        val audio = CountingIndex(InMemoryVectorIndex(3))
        val text = CountingIndex(InMemoryVectorIndex(TextEncoder.TEXT_DIM))
        val engine = DefaultSimilarityEngine(
            backend = backend(),
            index = audio,
            store = FakeIndexStore(),
            config = SmartEngineConfig(embeddingDim = 3, modelVersion = "map-cache-test"),
            dispatcher = StandardTestDispatcher(testScheduler),
            textEncoder = object : TextEncoder {
                override suspend fun load(): Result<Unit> = Result.success(Unit)
                override fun encode(metadata: String): FloatArray = FloatArray(TextEncoder.TEXT_DIM).also {
                    it[if ("Jazz" in metadata) 1 else 0] = 1f
                }
                override fun close() = Unit
            },
            textIndex = text,
            textStore = FakeIndexStore(),
        )
        engine.initialize()
        val ids = listOf(first.id)
        assertNull(engine.libraryMixCoverage(ids))
        engine.ensureMetadataVectors(listOf(first))
        assertEquals(LibraryVectorSource.METADATA, engine.libraryMixCoverage(ids)?.source)
        engine.indexLibrary(listOf(first))
        val fused = assertNotNull(engine.libraryMixCoverage(ids))
        assertEquals(LibraryVectorSource.AUDIO_AND_METADATA, fused.source)
        val changed = first.copy(genre = "Jazz")
        engine.synchronizeLibrary(listOf(changed))
        engine.ensureMetadataVectors(listOf(changed))
        val retagged = assertNotNull(engine.libraryMixCoverage(ids))
        assertEquals(fused.source, retagged.source)
        assertNotEquals(fused.fingerprint, retagged.fingerprint)
        val reads = audio.reads to text.reads
        engine.libraryMixCoverage(ids)
        assertEquals(reads, audio.reads to text.reads)
    }

    @Test
    fun clearingAnalysisAndReleasingTheEngineDropCachedCoverage() = runTest {
        val engine = makeEngine(CountingIndex(InMemoryVectorIndex(3)), StandardTestDispatcher(testScheduler))
        engine.initialize()
        engine.indexLibrary(listOf(first))
        val ids = listOf(first.id)
        assertNotNull(engine.libraryMixCoverage(ids))
        engine.clearAnalysis()
        assertNull(engine.libraryMixCoverage(ids))
        engine.release()
        assertNull(engine.libraryMixCoverage(ids))
        engine.initialize()
        assertNull(engine.libraryMixCoverage(ids))
    }

    private fun backend() = FakeEmbeddingBackend(mutableMapOf(
        first.id to floatArrayOf(1f, 0f, 0f),
        second.id to floatArrayOf(0f, 1f, 0f),
    ))

    private fun makeEngine(
        audio: VectorIndex,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        backend: FakeEmbeddingBackend = backend(),
    ) = DefaultSimilarityEngine(
        backend = backend,
        index = audio,
        store = FakeIndexStore(),
        config = SmartEngineConfig(embeddingDim = 3, modelVersion = "map-cache-test"),
        dispatcher = dispatcher,
    )

    private class CountingIndex(private val delegate: VectorIndex) : VectorIndex by delegate {
        var reads = 0
        override fun vector(id: TrackId): FloatArray? {
            reads++
            return delegate.vector(id)
        }
    }
}
