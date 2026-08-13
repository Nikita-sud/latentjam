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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class DefaultSimilarityEngineTest {

    private val seed = TrackDescriptor(id = TrackId("seed"), audioUri = "test://seed")
    private val near = TrackDescriptor(id = TrackId("near"), audioUri = "test://near")
    private val far = TrackDescriptor(id = TrackId("far"), audioUri = "test://far")

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

    private fun engine(
        backend: EmbeddingBackend,
        store: IndexStore,
        textEncoder: TextEncoder? = null,
        textIndex: VectorIndex? = null,
        textStore: IndexStore? = null,
        modelVersion: String = "test-model",
    ) = DefaultSimilarityEngine(
        backend = backend,
        index = InMemoryVectorIndex(dim = 3),
        store = store,
        config = SmartEngineConfig(embeddingDim = 3, modelVersion = modelVersion),
        dispatcher = Dispatchers.Default.limitedParallelism(1, "restart-smart-engine"),
        textEncoder = textEncoder,
        textIndex = textIndex,
        textStore = textStore,
    )

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
    fun initializeIsIdempotentAndNeverTouchesTheAudioBackend() = runTest {
        val harness = Harness()
        assertTrue(harness.engine.initialize().isSuccess)
        assertTrue(harness.engine.initialize().isSuccess)
        assertEquals(0, harness.backend.loadModelCalls, "launch must not pay for the audio model")
    }

    @Test
    fun brokenAudioModelFailsPerOperationNotStartup() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.backend.loadModelResult =
            Result.failure(SmartEngineException(EngineError.ModelUnavailable))

        // A corrupt audio model must not brick the engine: restored vectors and
        // the metadata fallback still serve queries without it.
        assertTrue(harness.engine.initialize().isSuccess)
        assertEquals(EngineState.Ready(indexedCount = 0), harness.engine.state.value)

        // The failure surfaces, typed, on the operation that actually needed the model…
        val report = harness.engine.indexLibrary(listOf(seed, near))
        assertEquals(0, report.indexed)
        assertEquals(2, report.failed)
        assertTrue(report.errors.values.all { it == EngineError.ModelUnavailable })
        assertEquals(EngineState.Ready(indexedCount = 0), harness.engine.state.value)

        // …and is never latched: the next operation retries the load.
        harness.backend.loadModelResult = Result.success(Unit)
        val retried = harness.engine.indexLibrary(listOf(seed, near))
        assertEquals(2, retried.indexed)
        assertEquals(2, harness.backend.loadModelCalls)
    }

    @Test
    fun fullyIndexedLibraryNeverLoadsTheAudioModel() = runTest {
        val harness = Harness()
        harness.store.snapshots["test-model"] = mapOf(
            seed.id to floatArrayOf(1f, 0f, 0f),
            near.id to floatArrayOf(0.9f, 0.1f, 0f),
            far.id to floatArrayOf(0f, 1f, 0f),
        )
        harness.engine.initialize()

        val features = assertNotNull(
            harness.engine.libraryMixFeatures(listOf(seed.id, near.id, far.id)),
        )
        assertEquals(3, features.vectorSpace.size)
        assertTrue(features.semantics.isEmpty())
        val report = harness.engine.indexLibrary(listOf(seed, near, far))
        assertEquals(3, report.skipped)
        val result = harness.engine.nextTrack(ListeningContext(seed = seed))
        assertEquals(near.id, assertIs<NextTrackResult.Match>(result).trackId)
        assertEquals(
            0,
            harness.backend.loadModelCalls,
            "restored vectors and optional background mix semantics must keep the model lazy",
        )
        assertEquals(0, harness.backend.loadSemanticModelCalls)
    }

    @Test
    fun explicitSemanticMixOptInMayLoadTheModelForUsefulClassification() = runTest {
        val harness = Harness()
        harness.store.snapshots["test-model"] = mapOf(
            seed.id to floatArrayOf(1f, 0f, 0f),
        )
        harness.engine.initialize()

        assertNotNull(
            harness.engine.libraryMixFeatures(
                ids = listOf(seed.id),
                loadMissingSemantics = true,
            ),
        )

        assertEquals(0, harness.backend.loadModelCalls, "semantic routing must not load audio")
        assertEquals(1, harness.backend.loadSemanticModelCalls)
    }

    @Test
    fun audioModelLoadsOnceAcrossEmbeddingBatches() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()

        harness.engine.indexLibrary(listOf(seed, near))
        harness.engine.indexLibrary(listOf(seed, near, far))

        assertEquals(1, harness.backend.loadModelCalls)
        assertEquals(EngineState.Ready(indexedCount = 3), harness.engine.state.value)
    }

    @Test
    fun `unchanged audio decode failure survives restart without loading model again`() = runTest {
        val store = FakeIndexStore()
        val broken = TrackDescriptor(
            id = TrackId("broken-file"),
            audioUri = "content://media/42",
            durationMs = 12_345,
            sourceRevision = "android-mediastore-v1:100:200:3",
        )
        val firstBackend = FakeEmbeddingBackend().apply {
            failures[broken.id] = EngineError.InvalidAudio("decoder rejected test input")
        }
        val first = engine(firstBackend, store)
        first.initialize()

        val firstReport = first.indexLibrary(listOf(broken))

        assertEquals(1, firstReport.failed)
        assertIs<EngineError.InvalidAudio>(firstReport.errors[broken.id])
        assertEquals(1, firstBackend.loadModelCalls)
        assertEquals(1, firstBackend.embedCalls)
        assertEquals(setOf(broken.id), store.failureSnapshots["test-model"]?.keys)

        val restartedBackend = FakeEmbeddingBackend(
            mutableMapOf(broken.id to floatArrayOf(1f, 0f, 0f)),
        )
        val restarted = engine(restartedBackend, store)
        restarted.initialize()
        restarted.synchronizeLibrary(listOf(broken))

        assertEquals(0, restarted.missingFromIndex(listOf(broken.id)))
        val rememberedReport = restarted.indexLibrary(listOf(broken))
        assertEquals(1, rememberedReport.failed)
        assertIs<EngineError.InvalidAudio>(rememberedReport.errors[broken.id])
        assertEquals(0, restartedBackend.loadModelCalls)
        assertEquals(0, restartedBackend.embedCalls)
    }

    @Test
    fun `tracks without audio URI never load the model and remain remembered`() = runTest {
        val store = FakeIndexStore()
        val protected = TrackDescriptor(
            id = TrackId("protected-library-row"),
            audioUri = null,
            durationMs = 12_345,
            sourceRevision = "protected-v1",
        )
        val firstBackend = FakeEmbeddingBackend()
        val first = engine(firstBackend, store)
        first.initialize()

        val firstReport = first.indexLibrary(listOf(protected))

        assertIs<EngineError.InvalidAudio>(firstReport.errors[protected.id])
        assertEquals(0, firstBackend.loadModelCalls)
        assertEquals(0, firstBackend.embedCalls)
        assertEquals(setOf(protected.id), store.failureSnapshots["test-model"]?.keys)

        val restartedBackend = FakeEmbeddingBackend()
        val restarted = engine(restartedBackend, store)
        restarted.initialize()
        restarted.synchronizeLibrary(listOf(protected))
        assertEquals(0, restarted.missingFromIndex(listOf(protected.id)))
        assertIs<EngineError.InvalidAudio>(
            restarted.indexLibrary(listOf(protected)).errors[protected.id],
        )
        assertEquals(0, restartedBackend.loadModelCalls)
        assertEquals(0, restartedBackend.embedCalls)
    }

    @Test
    fun `unindexed seed without audio URI never loads model for direct or smart query`() = runTest {
        val store = FakeIndexStore().apply {
            snapshots["test-model"] = mapOf(
                near.id to floatArrayOf(0.9f, 0.1f, 0f),
                far.id to floatArrayOf(0f, 1f, 0f),
            )
        }
        val backend = FakeEmbeddingBackend()
        val engine = engine(backend, store)
        engine.initialize()
        val protectedSeed = TrackDescriptor(
            id = TrackId("protected-seed"),
            title = "Protected seed",
            genre = "Rock",
            audioUri = null,
        )

        assertIs<EngineError.InvalidAudio>(
            assertIs<NextTrackResult.Failure>(
                engine.nextTrack(ListeningContext(seed = protectedSeed)),
            ).error,
        )
        engine.smartQueue(protectedSeed, listOf(near, far), length = 1)
        assertEquals(0, backend.loadModelCalls)
        assertEquals(0, backend.embedCalls)
    }

    @Test
    fun `ambiguous empty scan preserves remembered audio failure until authoritative scan`() = runTest {
        val store = FakeIndexStore()
        val broken = TrackDescriptor(
            id = TrackId("permission-hidden-failure"),
            audioUri = "content://media/73",
            sourceRevision = "android-mediastore-v1:10:20:3",
        )
        val firstBackend = FakeEmbeddingBackend().apply {
            failures[broken.id] = EngineError.InvalidAudio("unsupported stream")
        }
        val first = engine(firstBackend, store)
        first.initialize()
        first.indexLibrary(listOf(broken))

        val restarted = engine(FakeEmbeddingBackend(), store)
        restarted.initialize()
        restarted.synchronizeLibrary(emptyList(), pruneMissing = false)

        assertEquals(setOf(broken.id), store.failureSnapshots["test-model"]?.keys)
        assertEquals(
            1,
            restarted.missingFromIndex(listOf(broken.id)),
            "legacy untyped markers are intentionally retried after classification migration",
        )

        restarted.synchronizeLibrary(emptyList(), pruneMissing = true)

        assertTrue(store.failureSnapshots["test-model"].orEmpty().isEmpty())
        assertEquals(1, restarted.missingFromIndex(listOf(broken.id)))
    }

    @Test
    fun `changed failed track is retried and replaces its failure marker`() = runTest {
        val store = FakeIndexStore()
        val original = TrackDescriptor(
            id = TrackId("replaced-file"),
            audioUri = "file:///music/replaced.mp3",
            durationMs = 1_000,
            sourceRevision = "size=10|mtime=20",
        )
        val firstBackend = FakeEmbeddingBackend().apply {
            failures[original.id] = EngineError.InvalidAudio("truncated")
        }
        val first = engine(firstBackend, store)
        first.initialize()
        first.indexLibrary(listOf(original))

        val changed = original.copy(sourceRevision = "size=12|mtime=21")
        val restartedBackend = FakeEmbeddingBackend(
            mutableMapOf(changed.id to floatArrayOf(0f, 1f, 0f)),
        )
        val restarted = engine(restartedBackend, store)
        restarted.initialize()
        restarted.synchronizeLibrary(listOf(changed))

        assertEquals(1, restarted.missingFromIndex(listOf(changed.id)))
        val report = restarted.indexLibrary(listOf(changed))
        assertEquals(1, report.indexed)
        assertEquals(0, report.failed)
        assertEquals(1, restartedBackend.loadModelCalls)
        assertEquals(1, restartedBackend.embedCalls)
        assertTrue(store.failureSnapshots["test-model"].orEmpty().isEmpty())
        assertContentEquals(floatArrayOf(0f, 1f, 0f), restarted.embedding(changed.id))
    }

    @Test
    fun `backend failures stay retryable across restart`() = runTest {
        val store = FakeIndexStore()
        val track = TrackDescriptor(
            TrackId("transient-backend-failure"),
            audioUri = "test://transient-backend-failure",
        )
        val first = engine(FakeEmbeddingBackend(), store)
        first.initialize()
        assertIs<EngineError.BackendFailure>(first.indexLibrary(listOf(track)).errors[track.id])
        assertTrue(store.failureSnapshots["test-model"].orEmpty().isEmpty())

        val restartedBackend = FakeEmbeddingBackend(
            mutableMapOf(track.id to floatArrayOf(1f, 0f, 0f)),
        )
        val restarted = engine(restartedBackend, store)
        restarted.initialize()
        restarted.synchronizeLibrary(listOf(track))

        assertEquals(1, restarted.indexLibrary(listOf(track)).indexed)
        assertEquals(1, restartedBackend.loadModelCalls)
        assertEquals(1, restartedBackend.embedCalls)
    }

    @Test
    fun `temporary audio failure stays retryable across restart`() = runTest {
        val store = FakeIndexStore()
        val track = TrackDescriptor(
            TrackId("temporarily-unreadable"),
            audioUri = "test://temporarily-unreadable",
        )
        val firstBackend = FakeEmbeddingBackend().apply {
            failures[track.id] = EngineError.AudioUnavailable("permission temporarily unavailable")
        }
        val first = engine(firstBackend, store)
        first.initialize()

        assertIs<EngineError.AudioUnavailable>(first.indexLibrary(listOf(track)).errors[track.id])
        assertTrue(store.failureSnapshots["test-model"].orEmpty().isEmpty())

        val restartedBackend = FakeEmbeddingBackend(
            mutableMapOf(track.id to floatArrayOf(1f, 0f, 0f)),
        )
        val restarted = engine(restartedBackend, store)
        restarted.initialize()
        restarted.synchronizeLibrary(listOf(track))

        assertEquals(1, restarted.missingFromIndex(listOf(track.id)))
        assertEquals(1, restarted.indexLibrary(listOf(track)).indexed)
        assertEquals(1, restartedBackend.loadModelCalls)
        assertEquals(1, restartedBackend.embedCalls)
    }

    @Test
    fun `explicit retry clears unchanged invalid-audio marker durably`() = runTest {
        val store = FakeIndexStore()
        val track = TrackDescriptor(
            id = TrackId("retry-invalid-audio"),
            audioUri = "file:///music/retry.mp3",
            sourceRevision = "size=10|mtime=20",
        )
        val failedBackend = FakeEmbeddingBackend().apply {
            failures[track.id] = EngineError.InvalidAudio("no decodable audio")
        }
        val first = engine(failedBackend, store)
        first.initialize()
        first.indexLibrary(listOf(track))
        assertEquals(setOf(track.id), store.failureSnapshots["test-model"]?.keys)

        val retryBackend = FakeEmbeddingBackend(
            mutableMapOf(track.id to floatArrayOf(0f, 1f, 0f)),
        )
        val retrying = engine(retryBackend, store)
        retrying.initialize()
        retrying.synchronizeLibrary(listOf(track))

        assertEquals(1, retrying.retryFailedTracks(listOf(track.id, track.id)))
        assertTrue(store.failureSnapshots["test-model"].orEmpty().isEmpty())
        assertEquals(1, retrying.missingFromIndex(listOf(track.id)))
        assertEquals(1, retrying.indexLibrary(listOf(track)).indexed)
        assertEquals(1, retryBackend.loadModelCalls)
        assertEquals(1, retryBackend.embedCalls)
    }

    @Test
    fun `legacy untyped failure marker is retried`() = runTest {
        val store = FakeIndexStore().apply {
            snapshots["test-model"] = emptyMap()
            failureSnapshots["test-model"] = mapOf(
                seed.id to "audio-v1|legacy-marker-from-untyped-failure-cache",
            )
        }
        val backend = FakeEmbeddingBackend(
            mutableMapOf(seed.id to floatArrayOf(1f, 0f, 0f)),
        )
        val restarted = engine(backend, store)
        restarted.initialize()

        restarted.synchronizeLibrary(listOf(seed))

        assertEquals(1, restarted.missingFromIndex(listOf(seed.id)))
        assertTrue(store.failureSnapshots["test-model"].orEmpty().isEmpty())
        assertEquals(1, restarted.indexLibrary(listOf(seed)).indexed)
    }

    // --------------------------------------------------------------- indexLibrary

    @Test
    fun indexLibraryEmbedsAndReports() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        val unknown = TrackDescriptor(id = TrackId("unknown"), audioUri = "test://unknown")
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

        val unknownSeed = TrackDescriptor(
            id = TrackId("not-registered"),
            audioUri = "test://not-registered",
        )
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
        // re-initialize restores the three indexed tracks from the store, and the
        // restored vectors mean the audio model is not reloaded either.
        assertEquals(EngineState.Ready(indexedCount = 3), harness.engine.state.value)
        assertEquals(1, harness.backend.loadModelCalls)
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
    fun ambiguousPartialScanPreservesMissingVectorsButInvalidatesChangedVisibleRows() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(seed, near, far))
        val changedSeed = seed.copy(audioUri = "file:///changed-seed.mp3")

        val removed = harness.engine.synchronizeLibrary(
            library = listOf(changedSeed),
            pruneMissing = false,
        )

        assertEquals(1, removed, "the supplied changed row is still reconciled")
        assertEquals(null, harness.engine.embedding(seed.id))
        assertNotNull(harness.engine.embedding(near.id))
        assertNotNull(harness.engine.embedding(far.id))
        assertEquals(setOf(near.id, far.id), harness.store.snapshots["test-model"]?.keys)
    }

    @Test
    fun ambiguousEmptyScanPreservesDurableVectors() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(seed, near, far))

        val removed = harness.engine.synchronizeLibrary(emptyList(), pruneMissing = false)

        assertEquals(0, removed)
        assertEquals(EngineState.Ready(indexedCount = 3), harness.engine.state.value)
        assertEquals(setOf(seed.id, near.id, far.id), harness.store.snapshots["test-model"]?.keys)
    }

    @Test
    fun authoritativeEmptyScanClearsDurableVectors() = runTest {
        val harness = Harness()
        harness.registerTriangle()
        harness.engine.initialize()
        harness.engine.indexLibrary(listOf(seed, near, far))

        val removed = harness.engine.synchronizeLibrary(emptyList(), pruneMissing = true)

        assertEquals(3, removed)
        assertEquals(EngineState.Ready(indexedCount = 0), harness.engine.state.value)
        assertEquals(emptySet(), harness.store.snapshots["test-model"]?.keys)
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
    fun `restart invalidates audio vector when same source gets a new content revision`() = runTest {
        val store = FakeIndexStore()
        val id = TrackId("opaque,|\u0000id")
        val original = TrackDescriptor(
            id = id,
            audioUri = "file:///same/path",
            durationMs = 1_000,
            sourceRevision = "size=10|mtime=20",
        )
        val changed = original.copy(sourceRevision = "size=10|mtime=21")
        val firstBackend = FakeEmbeddingBackend(mutableMapOf(id to floatArrayOf(1f, 0f, 0f)))
        val first = engine(firstBackend, store)
        first.initialize()
        first.indexLibrary(listOf(original))

        val secondBackend = FakeEmbeddingBackend(mutableMapOf(id to floatArrayOf(0f, 1f, 0f)))
        val restarted = engine(secondBackend, store)
        restarted.initialize()
        assertNotNull(restarted.embedding(id), "the persisted vector restores before reconciliation")

        assertEquals(1, restarted.synchronizeLibrary(listOf(changed)))
        assertEquals(null, restarted.embedding(id))
        val report = restarted.indexLibrary(listOf(changed))
        assertEquals(1, report.indexed)
        assertEquals(1, secondBackend.embedCalls)
        assertContentEquals(floatArrayOf(0f, 1f, 0f), restarted.embedding(id))
    }

    @Test
    fun `restart reuses audio vector when persisted identity is unchanged`() = runTest {
        val store = FakeIndexStore()
        val track = TrackDescriptor(
            id = TrackId("same"),
            audioUri = "content://media/1",
            durationMs = 1_000,
            sourceRevision = "android-mediastore-v1:10:20:30",
        )
        val firstBackend = FakeEmbeddingBackend(
            mutableMapOf(track.id to floatArrayOf(1f, 0f, 0f)),
        )
        val first = engine(firstBackend, store)
        first.initialize()
        first.indexLibrary(listOf(track))

        val secondBackend = FakeEmbeddingBackend()
        val restarted = engine(secondBackend, store)
        restarted.initialize()

        assertEquals(0, restarted.synchronizeLibrary(listOf(track)))
        val report = restarted.indexLibrary(listOf(track))
        assertEquals(1, report.skipped)
        assertEquals(0, secondBackend.embedCalls)
        assertContentEquals(floatArrayOf(1f, 0f, 0f), restarted.embedding(track.id))
    }

    @Test
    fun `legacy restart snapshot is reindexed on first full synchronization`() = runTest {
        val store = FakeIndexStore()
        store.snapshots["test-model"] = mapOf(seed.id to floatArrayOf(1f, 0f, 0f))
        val backend = FakeEmbeddingBackend(
            mutableMapOf(seed.id to floatArrayOf(0f, 1f, 0f)),
        )
        val restarted = engine(backend, store)
        restarted.initialize()

        assertEquals(1, restarted.synchronizeLibrary(listOf(seed)))
        assertEquals(null, restarted.embedding(seed.id))
        assertEquals(1, restarted.indexLibrary(listOf(seed)).indexed)
    }

    @Test
    fun `restart invalidates metadata vector after tag edit and reuses unchanged identity`() = runTest {
        val audioStore = FakeIndexStore()
        val textStore = FakeIndexStore()
        val original = TrackDescriptor(TrackId("meta|id"), genre = "Dance", artist = "DJ")
        val edited = original.copy(genre = "Rock", artist = "Band")

        val first = engine(
            backend = FakeEmbeddingBackend(),
            store = audioStore,
            textEncoder = FakeTextEncoder(),
            textIndex = InMemoryVectorIndex(TextEncoder.TEXT_DIM),
            textStore = textStore,
        )
        first.initialize()
        assertEquals(1, first.ensureMetadataVectors(listOf(original)))

        val unchanged = engine(
            backend = FakeEmbeddingBackend(),
            store = audioStore,
            textEncoder = FakeTextEncoder(),
            textIndex = InMemoryVectorIndex(TextEncoder.TEXT_DIM),
            textStore = textStore,
        )
        unchanged.initialize()
        unchanged.synchronizeLibrary(listOf(original))
        assertEquals(0, unchanged.ensureMetadataVectors(listOf(original)))

        val changedAfterRestart = engine(
            backend = FakeEmbeddingBackend(),
            store = audioStore,
            textEncoder = FakeTextEncoder(),
            textIndex = InMemoryVectorIndex(TextEncoder.TEXT_DIM),
            textStore = textStore,
        )
        changedAfterRestart.initialize()
        changedAfterRestart.synchronizeLibrary(listOf(edited))
        assertEquals(1, changedAfterRestart.ensureMetadataVectors(listOf(edited)))
        assertTrue(changedAfterRestart.semanticSearch("guitars", 1).single().score > 0.99f)
    }

    @Test
    fun `index save failure is observable to the indexing caller`() = runTest {
        val failingStore = object : IndexStore {
            override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? = null
            override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>) {
                error("disk full")
            }
            override suspend fun clear(): Unit = Unit
        }
        val backend = FakeEmbeddingBackend(
            mutableMapOf(seed.id to floatArrayOf(1f, 0f, 0f)),
        )
        val engine = engine(backend, failingStore)
        engine.initialize()

        kotlin.test.assertFailsWith<IllegalStateException> {
            engine.indexLibrary(listOf(seed))
        }
    }

    @Test
    fun `failed audio snapshot save is retried even when every track is already indexed`() = runTest {
        val store = FakeIndexStore().apply { saveFailuresRemaining = 1 }
        val backend = FakeEmbeddingBackend(
            mutableMapOf(seed.id to floatArrayOf(1f, 0f, 0f)),
        )
        val engine = engine(backend, store)
        engine.initialize()

        assertFailsWith<IllegalStateException> {
            engine.indexLibrary(listOf(seed))
        }
        assertEquals(1, store.saveCalls)
        assertTrue(store.snapshots.isEmpty())

        val retry = engine.indexLibrary(listOf(seed))

        assertEquals(0, retry.indexed)
        assertEquals(1, retry.skipped)
        assertEquals(2, store.saveCalls)
        assertEquals(setOf(seed.id), store.snapshots["test-model"]?.keys)
    }

    @Test
    fun `failed metadata snapshot save is retried without reencoding the vector`() = runTest {
        val textStore = FakeIndexStore().apply { saveFailuresRemaining = 1 }
        val engine = engine(
            backend = FakeEmbeddingBackend(),
            store = FakeIndexStore(),
            textEncoder = FakeTextEncoder(),
            textIndex = InMemoryVectorIndex(TextEncoder.TEXT_DIM),
            textStore = textStore,
        )
        val track = TrackDescriptor(TrackId("metadata-retry"), genre = "Rock")
        engine.initialize()

        assertFailsWith<IllegalStateException> {
            engine.ensureMetadataVectors(listOf(track))
        }
        assertEquals(1, textStore.saveCalls)
        assertTrue(textStore.snapshots.isEmpty())

        assertEquals(0, engine.ensureMetadataVectors(listOf(track)))
        assertEquals(2, textStore.saveCalls)
        assertEquals(setOf(track.id), textStore.snapshots[TEXT_INDEX_VERSION]?.keys)
    }

    @Test
    fun `smart queue retries a dirty metadata snapshot before returning`() = runTest {
        val textStore = FakeIndexStore().apply { saveFailuresRemaining = 1 }
        val engine = engine(
            backend = FakeEmbeddingBackend(),
            store = FakeIndexStore(),
            textEncoder = FakeTextEncoder(),
            textIndex = InMemoryVectorIndex(TextEncoder.TEXT_DIM),
            textStore = textStore,
        )
        val track = TrackDescriptor(TrackId("metadata-smart-retry"), genre = "Rock")
        engine.initialize()
        assertFailsWith<IllegalStateException> {
            engine.ensureMetadataVectors(listOf(track))
        }

        engine.smartQueue(track, listOf(track), length = 1, history = emptyList())

        assertEquals(2, textStore.saveCalls)
        assertEquals(setOf(track.id), textStore.snapshots[TEXT_INDEX_VERSION]?.keys)
    }

    @Test
    fun `partial durable clear is repaired while memory stays live and retry clears everything`() =
        runTest {
            val audioStore = FakeIndexStore()
            val textStore = FakeIndexStore()
            val backend = FakeEmbeddingBackend(
                mutableMapOf(seed.id to floatArrayOf(1f, 0f, 0f)),
            )
            val engine = engine(
                backend = backend,
                store = audioStore,
                textEncoder = FakeTextEncoder(),
                textIndex = InMemoryVectorIndex(TextEncoder.TEXT_DIM),
                textStore = textStore,
            )
            engine.initialize()
            engine.indexLibrary(listOf(seed.copy(genre = "Rock")))
            audioStore.clearFailuresRemaining = 1
            audioStore.failClearAfterDeletion = true

            assertFailsWith<IllegalStateException> { engine.clearAnalysis() }

            assertEquals(1, audioStore.clearCalls)
            assertEquals(1, textStore.clearCalls, "the second store must still be attempted")
            assertEquals(EngineState.Ready(indexedCount = 1), engine.state.value)
            assertNotNull(engine.embedding(seed.id))
            assertEquals(setOf(seed.id), engine.metadataVectors().keys)
            assertEquals(setOf(seed.id), audioStore.snapshots["test-model"]?.keys)
            assertEquals(setOf(seed.id), textStore.snapshots[TEXT_INDEX_VERSION]?.keys)

            engine.clearAnalysis()

            assertEquals(2, audioStore.clearCalls)
            assertEquals(2, textStore.clearCalls)
            assertEquals(EngineState.Ready(indexedCount = 0), engine.state.value)
            assertEquals(null, engine.embedding(seed.id))
            assertTrue(engine.metadataVectors().isEmpty())
            assertTrue(audioStore.snapshots.isEmpty())
            assertTrue(textStore.snapshots.isEmpty())
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

    @Test
    fun `under twenty four tracks metadata fallback still honors marked playlist quota`() = runTest {
        val tracks = smartLibrary(23)
        val remote = tracks.last().copy(genre = "Dance")
        val library = tracks.dropLast(1) + remote
        val backend = FakeEmbeddingBackend(
            library.associateTo(mutableMapOf()) { track ->
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
                modelVersion = "metadata-companion-test",
            ),
            dispatcher = Dispatchers.Default.limitedParallelism(1, "metadata-companion-test"),
            textEncoder = FakeTextEncoder(),
            textIndex = InMemoryVectorIndex(TextEncoder.TEXT_DIM),
            textStore = FakeIndexStore(),
        )
        engine.initialize()
        engine.indexLibrary(library)
        val seed = library.first()

        val queue = engine.smartQueue(
            seed = seed,
            library = library.drop(1),
            length = 3,
            companionGroups = listOf(setOf(seed.id, remote.id)),
        )

        assertEquals(remote.id, queue[2])
    }

    private fun smartLibrary(size: Int): List<TrackDescriptor> = (0 until size).map { row ->
        TrackDescriptor(
            id = TrackId("smart-$row"),
            title = "Track $row",
            artist = "Artist $row",
            genre = if (row % 2 == 0) "Rock" else "Electronic",
            audioUri = "test://smart-$row",
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

    @Test
    fun missingFromIndexCountsOnlyUnembeddedTracks() = runTest {
        val harness = Harness()
        harness.engine.initialize()
        val indexed = TrackDescriptor(id = TrackId("indexed"))
        val absent = TrackDescriptor(id = TrackId("absent"))
        harness.backend.vectors[indexed.id] = floatArrayOf(1f, 0f, 0f)
        harness.engine.indexLibrary(listOf(indexed))

        assertEquals(1, harness.engine.missingFromIndex(listOf(indexed.id, absent.id)))
        assertEquals(0, harness.engine.missingFromIndex(listOf(indexed.id)))
        assertEquals(0, harness.engine.missingFromIndex(emptyList()))
    }
}
