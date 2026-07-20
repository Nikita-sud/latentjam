/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DefaultSimilarityEngineTest {

    private val seed = TrackDescriptor(id = TrackId("seed"))
    private val near = TrackDescriptor(id = TrackId("near"))
    private val far = TrackDescriptor(id = TrackId("far"))

    private class Harness {
        val backend = FakeEmbeddingBackend()
        val index = InMemoryVectorIndex(dim = 3)
        val store = FakeIndexStore()
        val engine = DefaultSimilarityEngine(
            backend = backend,
            index = index,
            store = store,
            config = SmartEngineConfig(embeddingDim = 3, modelVersion = "test-model"),
            dispatcher = Dispatchers.Default.limitedParallelism(1, "test-smart-engine"),
        )
    }

    private fun Harness.registerTriangle() {
        backend.vectors[seed.id] = floatArrayOf(1f, 0f, 0f)
        backend.vectors[near.id] = floatArrayOf(0.9f, 0.1f, 0f)
        backend.vectors[far.id] = floatArrayOf(0f, 1f, 0f)
    }

    // ---------------------------------------------------------------- initialize

    @Test
    fun initializeTransitionsToReady() = runTest {
        val harness = Harness()
        assertEquals(EngineState.Uninitialized, harness.engine.state.value)
        assertTrue(harness.engine.initialize().isSuccess)
        assertEquals(EngineState.Ready(indexedCount = 0), harness.engine.state.value)
    }

    @Test
    fun initializeIsIdempotent() = runTest {
        val harness = Harness()
        assertTrue(harness.engine.initialize().isSuccess)
        assertTrue(harness.engine.initialize().isSuccess)
        assertEquals(1, harness.backend.loadModelCalls, "Ready engine must not reload the model")
    }

    @Test
    fun initializeFailureIsTypedAndRetryable() = runTest {
        val harness = Harness()
        harness.backend.loadModelResult =
            Result.failure(SmartEngineException(EngineError.ModelUnavailable))

        val failure = harness.engine.initialize()
        val exception = assertIs<SmartEngineException>(failure.exceptionOrNull())
        assertEquals(EngineError.ModelUnavailable, exception.error)
        assertEquals(EngineState.Failed(EngineError.ModelUnavailable), harness.engine.state.value)

        harness.backend.loadModelResult = Result.success(Unit)
        assertTrue(harness.engine.initialize().isSuccess)
        assertEquals(2, harness.backend.loadModelCalls)
        assertEquals(EngineState.Ready(indexedCount = 0), harness.engine.state.value)
    }

    // --------------------------------------------------------------- indexLibrary

    @Test
    fun indexLibraryEmbedsAndReports() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        val unknown = TrackDescriptor(id = TrackId("unknown"))
        harness.engine.initialize()

        val report = harness.engine.indexLibrary(listOf(seed, near, far, unknown))

        assertEquals(3, report.indexed)
        assertEquals(1, report.failed)
        assertIs<EngineError.BackendFailure>(report.errors[unknown.id])
        assertEquals(EngineState.Ready(indexedCount = 3), harness.engine.state.value)
    }

    @Test
    fun indexLibraryWithoutInitializeFailsEveryTrack() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        val report = harness.engine.indexLibrary(listOf(seed, near))
        assertEquals(0, report.indexed)
        assertEquals(2, report.failed)
        assertTrue(report.errors.values.all { it == EngineError.ModelUnavailable })
    }

    @Test
    fun indexLibraryRejectsWrongDimensionVectors() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.backend.vectors[far.id] = floatArrayOf(1f, 0f) // wrong dim
        harness.engine.initialize()

        val report = harness.engine.indexLibrary(listOf(seed, near, far))

        assertEquals(2, report.indexed)
        assertEquals(1, report.failed)
        assertIs<EngineError.BackendFailure>(report.errors[far.id])
    }

    // ----------------------------------------------------------------- nextTrack

    @Test
    fun nextTrackReturnsNearestNeighbor() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(seed, near, far))

        val result = harness.engine.nextTrack(ListeningContext(seed = seed))

        val match = assertIs<NextTrackResult.Match>(result)
        assertEquals(near.id, match.trackId)
        assertTrue(match.similarity > 0.9f)
    }

    @Test
    fun nextTrackUsesStoredSeedVectorWithoutReEmbedding() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(seed, near, far))
        val embedCallsAfterIndexing = harness.backend.embedCalls

        harness.engine.nextTrack(ListeningContext(seed = seed))

        assertEquals(embedCallsAfterIndexing, harness.backend.embedCalls)
    }

    @Test
    fun nextTrackEmbedsUnindexedSeedOnTheFly() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(near, far)) // seed NOT indexed
        val embedCallsAfterIndexing = harness.backend.embedCalls

        val result = harness.engine.nextTrack(ListeningContext(seed = seed))

        assertEquals(near.id, assertIs<NextTrackResult.Match>(result).trackId)
        assertEquals(embedCallsAfterIndexing + 1, harness.backend.embedCalls)
    }

    @Test
    fun nextTrackHonorsExclusions() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(seed, near, far))

        val skippingNear = harness.engine.nextTrack(
            ListeningContext(seed = seed, recentTrackIds = listOf(near.id)),
        )
        assertEquals(far.id, assertIs<NextTrackResult.Match>(skippingNear).trackId)

        val skippingEverything = harness.engine.nextTrack(
            ListeningContext(
                seed = seed,
                recentTrackIds = listOf(near.id),
                excludedTrackIds = setOf(far.id),
            ),
        )
        assertEquals(NextTrackResult.NoCandidates, skippingEverything)
    }

    @Test
    fun nextTrackFailsTypedWhenNotReadyOrNotIndexed() = runTest {
        val harness = Harness()
        harness.registerTriangle()

        val beforeInitialize = harness.engine.nextTrack(ListeningContext(seed = seed))
        assertEquals(NextTrackResult.Failure(EngineError.ModelUnavailable), beforeInitialize)

        harness.engine.initialize()
        val beforeIndexing = harness.engine.nextTrack(ListeningContext(seed = seed))
        assertEquals(NextTrackResult.Failure(EngineError.NotIndexed), beforeIndexing)
    }

    @Test
    fun nextTrackPropagatesBackendFailureForUnknownSeed() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(near, far))

        val unknownSeed = TrackDescriptor(id = TrackId("not-registered"))
        val result = harness.engine.nextTrack(ListeningContext(seed = unknownSeed))

        assertIs<EngineError.BackendFailure>(assertIs<NextTrackResult.Failure>(result).error)
    }

    // ------------------------------------------------------------------- release

    @Test
    fun releaseResetsEngineAndAllowsReInitialize() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(seed, near, far))

        harness.engine.release()

        assertEquals(EngineState.Uninitialized, harness.engine.state.value)
        assertTrue(harness.backend.closed)
        assertEquals(
            NextTrackResult.Failure(EngineError.ModelUnavailable),
            harness.engine.nextTrack(ListeningContext(seed = seed)),
        )

        assertTrue(harness.engine.initialize().isSuccess)
        // release() clears the in-memory index but NOT the persisted snapshot:
        // re-initialize restores the three indexed tracks from the store.
        assertEquals(EngineState.Ready(indexedCount = 3), harness.engine.state.value)
        assertEquals(2, harness.backend.loadModelCalls)
    }

    // --------------------------------------------------------------- persistence

    @Test
    fun initializeRestoresPersistedSnapshot() = runTest {
        val harness = Harness()
        harness.store.snapshots["test-model"] = mapOf(
            near.id to floatArrayOf(0.9f, 0.1f, 0f),
            far.id to floatArrayOf(0f, 1f, 0f),
        )
        harness.registerTriangle()

        harness.engine.initialize()

        assertEquals(EngineState.Ready(indexedCount = 2), harness.engine.state.value)
        // Query works purely from restored vectors: seed embeds on the fly,
        // neighbors come from the snapshot.
        val result = harness.engine.nextTrack(ListeningContext(seed = seed))
        assertEquals(near.id, assertIs<NextTrackResult.Match>(result).trackId)
    }

    @Test
    fun indexLibrarySkipsAlreadyIndexedTracks() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(seed, near))
        val embedCallsAfterFirst = harness.backend.embedCalls

        val report = harness.engine.indexLibrary(listOf(seed, near, far))

        assertEquals(1, report.indexed, "only the new track embeds")
        assertEquals(2, report.skipped)
        assertEquals(0, report.failed)
        assertEquals(embedCallsAfterFirst + 1, harness.backend.embedCalls)
    }

    @Test
    fun indexLibraryPersistsSnapshotAfterBatch() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()

        harness.engine.indexLibrary(listOf(seed, near, far))

        assertEquals(1, harness.store.saveCalls)
        assertEquals(3, harness.store.snapshots["test-model"]?.size)
    }

    @Test
    fun semanticSearchEncodesTheQueryAgainstTheStoredTextIndex() = runTest {
        val textEncoder = FakeTextEncoder()
        val engine = DefaultSimilarityEngine(
            backend = FakeEmbeddingBackend(),
            index = InMemoryVectorIndex(dim = 3),
            store = FakeIndexStore(),
            config = SmartEngineConfig(embeddingDim = 3, modelVersion = "semantic-test"),
            dispatcher = Dispatchers.Default.limitedParallelism(1, "semantic-test-engine"),
            textEncoder = textEncoder,
            textIndex = InMemoryVectorIndex(dim = TextEncoder.TEXT_DIM),
            textStore = FakeIndexStore(),
        )
        val rock = TrackDescriptor(TrackId("rock"), genre = "Rock", artist = "Band")
        val dance = TrackDescriptor(TrackId("dance"), genre = "Dance", artist = "DJ")
        engine.initialize()
        engine.ensureMetadataVectors(listOf(dance, rock))

        val results = engine.semanticSearch("guitars", limit = 2)

        assertEquals(rock.id, results.first().trackId)
        assertTrue(results.first().score > results.last().score)
    }

    private class FakeTextEncoder : TextEncoder {
        override suspend fun load(): Result<Unit> = Result.success(Unit)

        override fun encode(metadata: String): FloatArray = FloatArray(TextEncoder.TEXT_DIM).also { vector ->
            when {
                metadata.contains("Rock", ignoreCase = true) ||
                    metadata.equals("guitars", ignoreCase = true) -> vector[0] = 1f
                metadata.contains("Dance", ignoreCase = true) -> vector[1] = 1f
                else -> vector[2] = 1f
            }
        }

        override fun close(): Unit = Unit
    }
}
