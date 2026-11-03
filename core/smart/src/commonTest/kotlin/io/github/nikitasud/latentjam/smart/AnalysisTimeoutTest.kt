/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #27 through the engine: a library batch with one track whose decoding never returns
 * completes, the track is a remembered timed-out failure that later passes skip, and Retry analyses
 * it again.
 */
internal class AnalysisTimeoutTest {

    private val stuck = CompletableDeferred<Unit>()

    @AfterTest
    fun release() {
        stuck.complete(Unit)
    }

    /** Decodes ahead like the Android backend; [hanging] tracks never come back from decoding. */
    private inner class DecodingBackend(
        var hanging: Set<TrackId>,
        budget: Duration = 300.milliseconds,
    ) : EmbeddingBackend {
        val decodes = mutableListOf<TrackId>()
        private val decoding = DeadlineDecoding(
            object : DecodeWorkers {
                override val dispatcher: CoroutineDispatcher = Dispatchers.Default
                override fun abandon() {}
            },
            budget = budget,
        )

        override suspend fun loadModel(): Result<Unit> = Result.success(Unit)

        private suspend fun decode(track: TrackDescriptor): FloatArray {
            decodes += track.id
            if (track.id in hanging) withContext(NonCancellable) { stuck.await() }
            return floatArrayOf(1f, track.id.value.length.toFloat(), 0f)
        }

        override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> =
            decoding.withinBudget { Result.success(decoding.decode { decode(descriptor) }) }

        override suspend fun embedEach(
            tracks: List<TrackDescriptor>,
            onResult: suspend (TrackDescriptor, Result<FloatArray>) -> Unit,
        ) = decoding.each(
            tracks = tracks,
            ahead = 2,
            prefetch = { decode(it) },
            finish = { _, vector -> Result.success(vector) },
            onResult = onResult,
        )

        override fun close() {}
    }

    private val tracks = listOf("one", "stuck", "three", "four5").map {
        TrackDescriptor(id = TrackId(it), audioUri = "file:///music/$it.mp3", sourceRevision = "size=1|mtime=2")
    }
    private val stuckTrack = tracks[1].id

    private fun engine(backend: EmbeddingBackend, store: IndexStore) = DefaultSimilarityEngine(
        backend = backend,
        index = InMemoryVectorIndex(dim = 3),
        store = store,
        config = SmartEngineConfig(embeddingDim = 3, modelVersion = "test-model"),
        dispatcher = Dispatchers.Default.limitedParallelism(1, "timeout-test-engine"),
    )

    @Test
    fun `a track that never decodes fails as timed out and the rest are analysed`() = runTest {
        val store = FakeIndexStore()
        val backend = DecodingBackend(hanging = setOf(stuckTrack))
        val engine = engine(backend, store)
        engine.initialize()

        val report = engine.indexLibrary(tracks)

        assertEquals(3, report.indexed)
        assertEquals(setOf(stuckTrack), report.errors.keys)
        val error = report.errors.getValue(stuckTrack)
        // D1 (plan §0): a timeout is transient now. The engine does not remember it; the app's retry ledger
        // decides when to try again and, after three timeouts, hands it back with rememberAudioFailures.
        assertIs<EngineError.AudioUnavailable>(error)
        assertTrue(error.isAnalysisTimeout())
        assertEquals(1, engine.missingFromIndex(tracks.map { it.id }))
        assertNull(engine.analysing.value)
        assertTrue(store.failureSnapshots["test-model"].isNullOrEmpty())

        // Once the file decodes, the next pass analyses it.
        backend.hanging = emptySet()
        val retried = engine.indexLibrary(tracks)
        assertEquals(1, retried.indexed)
        assertTrue(retried.errors.isEmpty())
        assertEquals(0, engine.missingFromIndex(tracks.map { it.id }))
    }

    @Test
    fun `a track the ledger gives up on is remembered until Retry`() = runTest {
        val store = FakeIndexStore()
        val backend = DecodingBackend(hanging = setOf(stuckTrack))
        val engine = engine(backend, store)
        engine.initialize()
        engine.indexLibrary(tracks)
        val stuck = tracks.single { it.id == stuckTrack }
        engine.rememberAudioFailures(listOf(stuck))
        assertEquals(0, engine.missingFromIndex(tracks.map { it.id }))
        val decodesBefore = backend.decodes.size
        assertIs<EngineError.InvalidAudio>(engine.indexLibrary(tracks).errors[stuckTrack])
        assertEquals(decodesBefore, backend.decodes.size)
        assertEquals(setOf(stuckTrack), store.failureSnapshots["test-model"]?.keys)
        backend.hanging = emptySet()
        assertEquals(1, engine.retryFailedTracks(listOf(stuckTrack)))
        assertEquals(1, engine.indexLibrary(tracks).indexed)
    }

    @Test
    fun `the engine names the track analysis is waiting on`() = runTest {
        val backend = DecodingBackend(hanging = setOf(stuckTrack), budget = 30.seconds)
        val engine = engine(backend, FakeIndexStore())
        engine.initialize()

        val batch = async(Dispatchers.Default) { engine.indexLibrary(tracks) }
        val waiting = withContext(Dispatchers.Default) { engine.analysing.first { it?.trackId == stuckTrack } }
        assertEquals(stuckTrack, waiting?.trackId)
        stuck.complete(Unit)

        assertEquals(4, batch.await().indexed)
        assertNull(engine.analysing.value)
    }

    @Test
    fun `a seed embed that never decodes cannot hold the engine`() = runTest {
        val backend = DecodingBackend(hanging = setOf(stuckTrack))
        val engine = engine(backend, FakeIndexStore())
        engine.initialize()
        engine.indexLibrary(listOf(tracks[0]))

        val result = engine.nextTrack(ListeningContext(seed = tracks[1]))

        assertIs<NextTrackResult.Failure>(result)
        assertIs<EngineError.AudioUnavailable>(result.error)
        // The engine still answers.
        assertEquals(0, engine.missingFromIndex(listOf(tracks[0].id)))
    }
}
