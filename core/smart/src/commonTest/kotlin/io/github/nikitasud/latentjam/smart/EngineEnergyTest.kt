/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** PLAN_AUDIO_ON §8.8: e = semantic head [6] − [5] on the stored vector, cached per track, persisted, invalidated. */
class EngineEnergyTest {
    /** A backend whose semantic head reports energy.high = v[0], energy.low = v[1]. */
    private class HeadBackend(val fake: FakeEmbeddingBackend = FakeEmbeddingBackend()) : EmbeddingBackend by fake {
        var classified = 0
        override suspend fun classify(embeddings: List<FloatArray>): Result<List<FloatArray>> {
            classified += embeddings.size
            return Result.success(embeddings.map { v -> FloatArray(27).also { it[6] = v[0]; it[5] = v[1] } })
        }
    }

    private val seed = TrackDescriptor(id = TrackId("seed"), audioUri = "test://seed")
    private val near = TrackDescriptor(id = TrackId("near"), audioUri = "test://near")
    private val far = TrackDescriptor(id = TrackId("far"), audioUri = "test://far")

    private fun engine(backend: EmbeddingBackend, store: IndexStore, energyStore: IndexStore) = DefaultSimilarityEngine(
        inferenceDispatcher = Dispatchers.Default.limitedParallelism(1, "energy-inference"),
        backend = backend,
        index = InMemoryVectorIndex(dim = 3),
        store = store,
        config = SmartEngineConfig(embeddingDim = 3, modelVersion = "test-model"),
        dispatcher = Dispatchers.Default.limitedParallelism(1, "energy-engine"),
        energyStore = energyStore,
    )

    @Test
    fun energyIsComputedOncePersistedAndDroppedWithItsVector() = runTest {
        val backend = HeadBackend()
        backend.fake.vectors[seed.id] = floatArrayOf(0.9f, 0.1f, 0f)
        backend.fake.vectors[near.id] = floatArrayOf(0.5f, 0.5f, 0.1f)
        backend.fake.vectors[far.id] = floatArrayOf(0.1f, 0.8f, 0.2f)
        val store = FakeIndexStore()
        val energyStore = FakeIndexStore()
        val first = engine(backend, store, energyStore)
        first.initialize().getOrThrow()
        val report = first.indexLibrary(listOf(seed, near, far))
        assertEquals(3, report.indexed, "indexed: $report")
        assertNull(first.energy(seed.id), "no e before the pass")
        assertEquals(3, first.ensureEnergies())
        // On the vector as the index stores it (unit length), as the harness reads the stored A0.
        fun e(v: FloatArray): Float { val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); return v[0] / n - v[1] / n }
        assertEquals(e(floatArrayOf(0.9f, 0.1f, 0f)), first.energy(seed.id)!!.toFloat(), 1e-6f)
        assertEquals(e(floatArrayOf(0.1f, 0.8f, 0.2f)), first.energy(far.id)!!.toFloat(), 1e-6f)
        assertEquals(3 to 3, first.energyCoverage())
        assertEquals(0, first.ensureEnergies(), "a second pass has nothing to do")

        // A restart restores e from its store without running the head again.
        val again = HeadBackend().also { it.fake.vectors.putAll(backend.fake.vectors) }
        val second = engine(again, store, energyStore)
        second.initialize().getOrThrow()
        assertEquals(0f, second.energy(near.id)!!.toFloat(), 1e-6f)
        assertEquals(0, second.ensureEnergies())
        assertEquals(0, again.classified)

        // A changed file (new revision) re-embeds the track and drops its old e until the next pass.
        val changed = listOf(seed, near.copy(sourceRevision = "r2"), far)
        second.synchronizeLibrary(changed, pruneMissing = true)
        second.indexLibrary(changed)
        assertNull(second.energy(near.id))
        assertEquals(1, second.ensureEnergies())
        assertEquals(3 to 3, second.energyCoverage())
    }

    /** A backend whose inference waits on [gate] (indexing in the middle of a chunk). */
    private class SlowBackend(val fake: FakeEmbeddingBackend = FakeEmbeddingBackend()) : EmbeddingBackend by fake {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var slow = false
        override suspend fun embed(descriptor: TrackDescriptor): Result<FloatArray> {
            if (slow) { entered.complete(Unit); gate.await() }
            return fake.embed(descriptor)
        }

        override suspend fun embedEach(
            tracks: List<TrackDescriptor>,
            onResult: suspend (TrackDescriptor, Result<FloatArray>) -> Unit,
        ) {
            for (track in tracks) onResult(track, embed(track))
        }
    }

    @Test
    fun audioReadsAreNotBlockedWhileIndexingRunsInference() = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
        val backend = SlowBackend()
        backend.fake.vectors[seed.id] = floatArrayOf(1f, 0f, 0f)
        backend.fake.vectors[near.id] = floatArrayOf(0.9f, 0.1f, 0f)
        backend.fake.vectors[far.id] = floatArrayOf(0f, 1f, 0f)
        val engine = engine(backend, FakeIndexStore(), FakeIndexStore())
        engine.initialize().getOrThrow()
        engine.indexLibrary(listOf(seed, near))
        backend.slow = true
        val indexing = launch(Dispatchers.Default) { engine.indexLibrary(listOf(seed, near, far)) }
        kotlinx.coroutines.withTimeout(10_000) { backend.entered.await() }
        // Inference is stuck on far; the ranker's reads must still answer at once.
        val read = kotlinx.coroutines.withTimeoutOrNull(2_000) {
            engine.embedding(near.id) to engine.audioNeighbours(seed.id, 2)
        }
        kotlin.test.assertNotNull(read, "audio reads waited behind indexing inference")
        kotlin.test.assertNotNull(read.first)
        assertEquals(listOf(near.id), read.second.map { it.trackId })
        backend.gate.complete(Unit)
        indexing.join()
        assertEquals(3, engine.audioNeighbours(seed.id, 5).size + 1)
    }
}
