/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import io.github.nikitasud.latentjam.smart.text.TextEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class SmartQueueAvailabilityTest {
    @Test
    fun `fully indexed library keeps refilling through its last remaining track`() = runTest {
        val library = tracks(3_000)
        val harness = harness(library, includeText = false)
        val byId = library.associateBy { it.id }
        val used = mutableSetOf(library.first().id)
        var seed = library.first()
        var calls = 0
        while (used.size < library.size) {
            val candidates = library.filter { it.id !in used }
            val queue = harness.engine.smartQueue(seed, candidates, 12)
            assertTrue(queue.isNotEmpty(), "Premature abstention with ${candidates.size} candidates")
            assertTrue(queue.all { it !in used })
            assertEquals(queue.size, queue.toSet().size)
            used.addAll(queue)
            seed = byId.getValue(queue.last())
            calls++
        }
        assertEquals(library.size, used.size)
        assertEquals(250, calls)
    }

    @Test
    fun `short indexed tail with unknown artists still uses its audio vectors`() = runTest {
        val library = tracks(64).map { it.copy(artist = null) }
        val harness = harness(library)
        val candidates = library.takeLast(22)
        val queue = harness.engine.smartQueue(library.first(), candidates, 12)
        assertEquals(12, queue.size)
        assertTrue(queue.all { id -> candidates.any { it.id == id } })
    }

    @Test
    fun `equal normalized titles by different artists do not exhaust the queue`() = runTest {
        val library = tracks(64).mapIndexed { i, track -> track.copy(title = "Intro (Version $i)") }
        val harness = harness(library)
        assertEquals(12, harness.engine.smartQueue(library.first(), library.drop(1), 12).size)
    }

    @Test
    fun `failed metadata checkpoint leaves audio recommendations available and remains retryable`() = runTest {
        val library = tracks(64)
        val harness = harness(library)
        val expected = harness.engine.smartQueue(library.first(), library.drop(1), 12)
        val pending = TrackDescriptor(TrackId("pending-metadata"), genre = "Folk")
        harness.textStore.saveFailuresRemaining = 2
        assertFailsWith<IllegalStateException> { harness.engine.ensureMetadataVectors(listOf(pending)) }
        val savesBeforeQuery = harness.textStore.saveCalls

        assertEquals(expected, harness.engine.smartQueue(library.first(), library.drop(1), 12))
        assertEquals(savesBeforeQuery + 1, harness.textStore.saveCalls)
        assertEquals(EngineState.Ready(64), harness.engine.state.value)
        harness.engine.persistPendingAnalysis()
        assertEquals(savesBeforeQuery + 2, harness.textStore.saveCalls)
        assertTrue(pending.id in harness.textStore.snapshots.getValue(TEXT_INDEX_VERSION))
    }

    @Test
    fun `failed audio checkpoint after embedding the seed still returns a queue`() = runTest {
        val library = tracks(64)
        val audioStore = FakeIndexStore().apply {
            snapshots[MODEL] = library.drop(1).mapIndexed { i, track -> track.id to vector(i + 1) }.toMap()
            saveFailuresRemaining = 1
        }
        val engine = DefaultSimilarityEngine(
            backend = FakeEmbeddingBackend(mutableMapOf(library.first().id to vector(0))),
            index = InMemoryVectorIndex(960),
            store = audioStore,
            config = SmartEngineConfig(embeddingDim = 960, modelVersion = MODEL),
            dispatcher = Dispatchers.Default,
        )
        engine.initialize()
        assertEquals(12, engine.smartQueue(library.first(), library.drop(1), 12).size)
        assertEquals(EngineState.Ready(64), engine.state.value)
        assertEquals(1, audioStore.saveCalls)
        engine.persistPendingAnalysis()
        assertEquals(2, audioStore.saveCalls)
        assertEquals(64, audioStore.snapshots.getValue(MODEL).size)
    }

    @Test
    fun `query checkpoint cancellation propagates and does not discard pending metadata`() = runTest {
        val library = tracks(24)
        val backing = FakeIndexStore()
        var cancelSave = true
        val textStore = object : IndexStore by backing {
            override suspend fun saveSnapshot(modelVersion: String, snapshot: StoredIndexSnapshot) {
                if (cancelSave) throw CancellationException("cancelled checkpoint")
                backing.saveSnapshot(modelVersion, snapshot)
            }
        }
        val audioStore = FakeIndexStore().apply {
            snapshots[MODEL] = library.mapIndexed { i, track -> track.id to vector(i) }.toMap()
        }
        val engine = DefaultSimilarityEngine(
            backend = FakeEmbeddingBackend(), index = InMemoryVectorIndex(960), store = audioStore,
            config = SmartEngineConfig(embeddingDim = 960, modelVersion = MODEL), dispatcher = Dispatchers.Default,
            textEncoder = TestTextEncoder(), textIndex = InMemoryVectorIndex(384), textStore = textStore,
        )
        engine.initialize()
        assertFailsWith<CancellationException> { engine.smartQueue(library.first(), library.drop(1), 12) }
        cancelSave = false
        engine.persistPendingAnalysis()
        assertTrue(library.first().id in backing.snapshots.getValue(TEXT_INDEX_VERSION))
    }

    private data class Harness(val engine: DefaultSimilarityEngine, val textStore: FakeIndexStore)

    private suspend fun harness(library: List<TrackDescriptor>, includeText: Boolean = true): Harness {
        val audioStore = FakeIndexStore().apply {
            snapshots[MODEL] = library.mapIndexed { i, track -> track.id to vector(i) }.toMap()
        }
        val textStore = FakeIndexStore()
        val engine = DefaultSimilarityEngine(
            backend = FakeEmbeddingBackend(), index = InMemoryVectorIndex(960), store = audioStore,
            config = SmartEngineConfig(embeddingDim = 960, modelVersion = MODEL), dispatcher = Dispatchers.Default,
            textEncoder = if (includeText) TestTextEncoder() else null,
            textIndex = if (includeText) InMemoryVectorIndex(384) else null,
            textStore = if (includeText) textStore else null,
        )
        assertTrue(engine.initialize().isSuccess)
        if (includeText) engine.ensureMetadataVectors(library)
        assertEquals(EngineState.Ready(library.size), engine.state.value)
        return Harness(engine, textStore)
    }

    private class TestTextEncoder : TextEncoder {
        override suspend fun load() = Result.success(Unit)
        override fun encode(metadata: String) = FloatArray(384).also { it[0] = 1f }
        override fun close() = Unit
    }

    private fun tracks(count: Int) = (0 until count).map { i ->
        TrackDescriptor(
            TrackId("availability-$i"), title = "Track $i", artist = "Artist $i",
            genre = "Rock", audioUri = "test://availability-$i",
        )
    }

    private fun vector(i: Int) = FloatArray(960).also {
        it[i % 960] = 1f
        it[(i * 31 + 17) % 960] += 0.3f
    }

    private companion object {
        const val MODEL = "availability-test"
    }
}
