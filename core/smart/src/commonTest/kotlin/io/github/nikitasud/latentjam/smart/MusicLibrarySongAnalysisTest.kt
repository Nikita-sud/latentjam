/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * A song of the iOS Music library has an `ipod-library://` asset URL instead of a file. What the engine does with it
 * follows from the host's answer alone: "unavailable" is tried again at every pass and keeps the song missing (0.9.5
 * answered that for every such song, because it looked for a file), a vector indexes it, and "invalid audio" (a
 * protected song, one with no audio track) is remembered after one pass.
 */
internal class MusicLibrarySongAnalysisTest {

    private val song = TrackDescriptor(
        id = TrackId("ios-media:4242"),
        title = "Song",
        artist = "Artist",
        durationMs = 200_000,
        audioUri = "ipod-library://item/item.m4a?id=4242",
        folderPath = "Music",
    )

    private fun engine(backend: EmbeddingBackend, store: IndexStore) = DefaultSimilarityEngine(
        backend = backend,
        index = InMemoryVectorIndex(dim = 3),
        store = store,
        config = SmartEngineConfig(embeddingDim = 3, modelVersion = "test-model"),
        dispatcher = Dispatchers.Default.limitedParallelism(1, "music-library-smart-engine"),
    )

    @Test
    fun aSongTheHostCallsUnavailableIsTriedAtEveryPassAndStaysMissing() = runTest {
        val backend = FakeEmbeddingBackend().apply {
            failures[song.id] = EngineError.AudioUnavailable("Audio file is not locally readable")
        }
        val engine = engine(backend, FakeIndexStore())
        engine.initialize()

        repeat(3) { pass ->
            assertIs<EngineError.AudioUnavailable>(engine.indexLibrary(listOf(song)).errors[song.id])
            assertEquals(1, engine.missingFromIndexOf(listOf(song)), "after pass ${pass + 1}")
            assertEquals(pass + 1, backend.embedCalls)
        }
    }

    @Test
    fun aSongTheHostDecodesIsIndexedAndNoLongerMissing() = runTest {
        val backend = FakeEmbeddingBackend(mutableMapOf(song.id to floatArrayOf(0f, 1f, 0f)))
        val engine = engine(backend, FakeIndexStore())
        engine.initialize()

        assertEquals(1, engine.indexLibrary(listOf(song)).indexed)

        assertEquals(0, engine.missingFromIndexOf(listOf(song)))
        assertNotNull(engine.embedding(song.id))
    }

    @Test
    fun aProtectedSongIsRememberedAfterOnePassAndWakesNoModelAgain() = runTest {
        val store = FakeIndexStore()
        val backend = FakeEmbeddingBackend().apply {
            failures[song.id] = EngineError.InvalidAudio("Audio is protected and cannot be read")
        }
        val first = engine(backend, store)
        first.initialize()

        assertIs<EngineError.InvalidAudio>(first.indexLibrary(listOf(song)).errors[song.id])

        assertEquals(0, first.missingFromIndexOf(listOf(song)))
        assertIs<EngineError.InvalidAudio>(first.indexLibrary(listOf(song)).errors[song.id])
        assertEquals(1, backend.embedCalls)
        assertEquals(1, backend.loadModelCalls)

        // The next launch reads the remembered failure back under the same asset URL.
        val restartedBackend = FakeEmbeddingBackend()
        val restarted = engine(restartedBackend, store)
        restarted.initialize()
        restarted.synchronizeLibrary(listOf(song))
        assertEquals(0, restarted.missingFromIndexOf(listOf(song)))
        restarted.indexLibrary(listOf(song))
        assertEquals(0, restartedBackend.loadModelCalls)
        assertEquals(0, restartedBackend.embedCalls)
    }

    @Test
    fun aSongWithNoAssetUrlIsNotMissingAfterOnePassAndLoadsNoModel() = runTest {
        // Only in the cloud, or protected: Music hands out no URL at all.
        val cloud = song.copy(id = TrackId("ios-media:77"), audioUri = null)
        val backend = FakeEmbeddingBackend()
        val engine = engine(backend, FakeIndexStore())
        engine.initialize()

        assertIs<EngineError.InvalidAudio>(engine.indexLibrary(listOf(cloud)).errors[cloud.id])

        assertEquals(0, engine.missingFromIndexOf(listOf(cloud)))
        assertEquals(0, backend.loadModelCalls)
        assertEquals(0, backend.embedCalls)
    }
}
