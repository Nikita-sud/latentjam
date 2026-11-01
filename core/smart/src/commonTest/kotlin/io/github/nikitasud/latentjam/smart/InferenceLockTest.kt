/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A backend that takes the engine's inference lock per unit itself (docs/analysis-094-plan.md 1b): a SMART press then
 * waits for one unit of analysis, never a whole chunk, and the stored rows are the same.
 */
internal class InferenceLockTest {

    private val seed = TrackDescriptor(id = TrackId("seed"), audioUri = "test://seed")
    private val near = TrackDescriptor(id = TrackId("near"), audioUri = "test://near")
    private val far = TrackDescriptor(id = TrackId("far"), audioUri = "test://far")
    private val chunk = (1..4).map { TrackDescriptor(id = TrackId("t$it"), audioUri = "test://t$it") }

    /** Locks per track when [locks]; between its first and second track it waits on [between], outside the lock. */
    private class GatedBackend(private val locks: Boolean) : EmbeddingBackend {
        val vectors = HashMap<TrackId, FloatArray>()
        val firstDone = CompletableDeferred<Unit>()
        val between = CompletableDeferred<Unit>()
        private var lock: Mutex? = null
        private var gated = false

        override fun takeInferenceLock(lock: Mutex): Boolean {
            if (locks) this.lock = lock
            return locks
        }

        override suspend fun loadModel(): Result<Unit> = Result.success(Unit)

        override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> {
            val unit = { Result.success(vectors.getValue(descriptor.id).copyOf()) }
            return lock?.withLock { unit() } ?: unit()
        }

        override suspend fun embedEach(
            tracks: List<TrackDescriptor>,
            onResult: suspend (TrackDescriptor, Result<FloatArray>) -> Unit,
        ) {
            for (track in tracks) {
                onResult(track, embed(track))
                if (!gated) {
                    gated = true
                    firstDone.complete(Unit)
                    between.await()
                }
            }
        }

        override fun close() {}
    }

    private fun engine(backend: EmbeddingBackend, store: FakeIndexStore) = DefaultSimilarityEngine(
        backend = backend,
        index = InMemoryVectorIndex(dim = 3),
        store = store,
        config = SmartEngineConfig(embeddingDim = 3, modelVersion = "test-model"),
        dispatcher = Dispatchers.Default.limitedParallelism(1, "inference-lock-engine"),
        inferenceDispatcher = Dispatchers.Default.limitedParallelism(1, "inference-lock-inference"),
    )

    private fun GatedBackend.register() {
        vectors[seed.id] = floatArrayOf(1f, 0f, 0f)
        vectors[near.id] = floatArrayOf(0.9f, 0.1f, 0f)
        vectors[far.id] = floatArrayOf(0f, 1f, 0f)
        chunk.forEachIndexed { i, t -> vectors[t.id] = floatArrayOf(0f, 0.5f, 0.1f * (i + 1)) }
    }

    /** Runs a press while analysis sits between two units of a chunk; true when the press finished meanwhile. */
    private fun pressDuringAChunk(locks: Boolean): Pair<Boolean, FakeIndexStore> = runBlocking {
        withContext(Dispatchers.Default) {
            val backend = GatedBackend(locks).apply { register() }
            val store = FakeIndexStore().apply {
                snapshots["test-model"] = mapOf(near.id to floatArrayOf(0.9f, 0.1f, 0f), far.id to floatArrayOf(0f, 1f, 0f))
            }
            val engine = engine(backend, store)
            engine.initialize()
            coroutineScope {
                val analysis = async { engine.indexLibrary(chunk) }
                backend.firstDone.await()
                val press = async { engine.nextTrack(ListeningContext(seed = seed)) }
                // With the hand-over the press must finish while the chunk waits (a generous bound, never hit when
                // it works); without it, it must still be waiting a moment later.
                val finished = if (locks) {
                    runCatching { withTimeout(10_000) { press.await() } }.isSuccess
                } else {
                    delay(300)
                    !press.isActive
                }
                backend.between.complete(Unit)
                assertIs<NextTrackResult.Match>(press.await())
                assertEquals(4, analysis.await().indexed)
                finished to store
            }
        }
    }

    @Test
    fun aPressWaitsForOneUnitNotTheWholeChunk() {
        val (finished, _) = pressDuringAChunk(locks = true)
        assertTrue(finished, "the press ran while the chunk was between units")
    }

    @Test
    fun withoutTheLockHandOverTheEngineStillHoldsItForTheChunk() {
        val (finished, _) = pressDuringAChunk(locks = false)
        assertFalse(finished, "0.9.3's hold: the press waits for the chunk")
    }

    @Test
    fun theStoredRowsAreTheSameEitherWay() {
        val (_, locked) = pressDuringAChunk(locks = true)
        val (_, held) = pressDuringAChunk(locks = false)
        val a = locked.snapshots.getValue("test-model")
        val b = held.snapshots.getValue("test-model")
        assertEquals(b.keys.toList(), a.keys.toList())
        for ((id, v) in b) assertTrue(v.contentEquals(a.getValue(id)), "row ${id.value}")
    }
}
