/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.chain.PredictorRuntime
import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    @Test
    fun libraryMixMatrixCannotMutateTheLiveIndex() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(seed, near))

        val space = assertNotNull(harness.engine.libraryMixVectors(listOf(seed.id, near.id)))
        val detached = assertNotNull(space.vector(seed.id))
        assertContentEquals(floatArrayOf(1f, 0f, 0f), detached)
        detached[0] = 0f

        assertContentEquals(floatArrayOf(1f, 0f, 0f), harness.engine.embedding(seed.id))
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
    fun clearAnalysisDeletesMemoryAndPersistedSnapshotWithoutUnloadingModel() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(seed, near))
        assertTrue(harness.store.snapshots.isNotEmpty())

        harness.engine.clearAnalysis()

        assertEquals(EngineState.Ready(indexedCount = 0), harness.engine.state.value)
        assertEquals(null, harness.engine.embedding(seed.id))
        assertTrue(harness.store.snapshots.isEmpty())
        assertEquals(1, harness.backend.loadModelCalls, "clearing analysis must not reload the model")
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
    fun synchronizeLibraryPrunesStalePersistedFingerprintsAndUpdatesState() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(seed, near, far))

        val removed = harness.engine.synchronizeLibrary(listOf(seed, near))

        assertEquals(1, removed)
        assertEquals(null, harness.engine.embedding(far.id))
        assertEquals(EngineState.Ready(indexedCount = 2), harness.engine.state.value)
        assertEquals(setOf(seed.id, near.id), harness.store.snapshots["test-model"]?.keys)
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

    @Test
    fun synchronizeLibraryReencodesChangedMetadataForSemanticSearch() = runTest {
        val textEncoder = FakeTextEncoder()
        val engine = DefaultSimilarityEngine(
            backend = FakeEmbeddingBackend(),
            index = InMemoryVectorIndex(dim = 3),
            store = FakeIndexStore(),
            config = SmartEngineConfig(embeddingDim = 3, modelVersion = "metadata-refresh-test"),
            dispatcher = Dispatchers.Default.limitedParallelism(1, "metadata-refresh-engine"),
            textEncoder = textEncoder,
            textIndex = InMemoryVectorIndex(dim = TextEncoder.TEXT_DIM),
            textStore = FakeIndexStore(),
        )
        val original = TrackDescriptor(TrackId("same-id"), genre = "Dance", artist = "DJ")
        val edited = original.copy(genre = "Rock", artist = "Band")
        engine.initialize()
        engine.ensureMetadataVectors(listOf(original))
        val scoreBefore = engine.semanticSearch("guitars", limit = 1).single().score

        engine.synchronizeLibrary(listOf(edited))
        engine.ensureMetadataVectors(listOf(edited))
        val scoreAfter = engine.semanticSearch("guitars", limit = 1).single().score

        assertTrue(scoreBefore < 0.1f)
        assertTrue(scoreAfter > 0.99f)
    }

    @Test
    fun synchronizeLibraryReembedsChangedAudioSourceWithTheSameId() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        val original = seed.copy(audioUri = "file:///old", durationMs = 1_000)
        val replaced = original.copy(audioUri = "file:///new", durationMs = 2_000)
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(original))
        val callsBefore = harness.backend.embedCalls

        val removed = harness.engine.synchronizeLibrary(listOf(replaced))
        val report = harness.engine.indexLibrary(listOf(replaced))

        assertEquals(1, removed)
        assertEquals(1, report.indexed)
        assertEquals(callsBefore + 1, harness.backend.embedCalls)
    }

    @Test
    fun `57 track library uses the zero padded learned scorer`() = runTest {
        val predictor = CountingPredictor()
        val tracks = smartLibrary(57)
        val backend = FakeEmbeddingBackend(
            tracks.associateTo(mutableMapOf()) { track ->
                track.id to FloatArray(PredictorRuntime.EMBEDDING_DIM).also { vector ->
                    vector[track.id.value.removePrefix("smart-").toInt()] = 1f
                }
            },
        )
        val engine = DefaultSimilarityEngine(
            backend = backend,
            index = InMemoryVectorIndex(PredictorRuntime.EMBEDDING_DIM),
            store = FakeIndexStore(),
            config = SmartEngineConfig(
                embeddingDim = PredictorRuntime.EMBEDDING_DIM,
                modelVersion = "short-pool-test",
            ),
            dispatcher = Dispatchers.Default.limitedParallelism(1, "short-pool-test"),
            predictor = predictor,
        )
        engine.initialize()
        engine.indexLibrary(tracks)

        val queue = engine.smartQueue(tracks.first(), tracks.drop(1), length = 5)

        assertEquals(5, queue.size)
        assertTrue(predictor.scoreCalls > 0, "57 local tracks must reach the trained scorer")
    }

    @Test
    fun `tiny audio corpus keeps the honest metadata fallback`() = runTest {
        val predictor = CountingPredictor()
        val tracks = smartLibrary(23)
        val backend = FakeEmbeddingBackend(
            tracks.associateTo(mutableMapOf()) { track ->
                track.id to FloatArray(PredictorRuntime.EMBEDDING_DIM).also { vector ->
                    vector[track.id.value.removePrefix("smart-").toInt()] = 1f
                }
            },
        )
        val engine = DefaultSimilarityEngine(
            backend = backend,
            index = InMemoryVectorIndex(PredictorRuntime.EMBEDDING_DIM),
            store = FakeIndexStore(),
            config = SmartEngineConfig(
                embeddingDim = PredictorRuntime.EMBEDDING_DIM,
                modelVersion = "tiny-pool-test",
            ),
            dispatcher = Dispatchers.Default.limitedParallelism(1, "tiny-pool-test"),
            predictor = predictor,
        )
        engine.initialize()
        engine.indexLibrary(tracks)

        val queue = engine.smartQueue(tracks.first(), tracks.drop(1), length = 5)

        assertTrue(queue.isEmpty(), "this harness deliberately has no metadata encoder")
        assertEquals(0, predictor.scoreCalls)
    }

    private fun smartLibrary(size: Int): List<TrackDescriptor> = (0 until size).map { row ->
        TrackDescriptor(
            id = TrackId("smart-$row"),
            title = "Track $row",
            artist = "Artist $row",
            genre = if (row % 2 == 0) "Rock" else "Electronic",
        )
    }

    private class CountingPredictor : PredictorRuntime {
        var scoreCalls = 0

        override suspend fun load(): Result<Unit> = Result.success(Unit)

        override fun encodeState(
            historySmall: FloatArray,
            historyMedium: FloatArray,
            historyLarge: FloatArray,
            timeFeatures: FloatArray,
            sessionFeatures: FloatArray,
        ): FloatArray = historySmall.copyOfRange(
            (PredictorRuntime.CONTEXT_K - 1) * PredictorRuntime.TOKEN_DIM,
            (PredictorRuntime.CONTEXT_K - 1) * PredictorRuntime.TOKEN_DIM +
                PredictorRuntime.EMBEDDING_DIM,
        )

        override fun score(
            state: FloatArray,
            candidates: FloatArray,
        ): FloatArray {
            scoreCalls++
            return FloatArray(PredictorRuntime.POOL_SIZE)
        }

        override fun close(): Unit = Unit
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
