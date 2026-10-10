/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IosInferenceLifecycleTest {

    @BeforeTest
    fun setUp(): Unit = IosInferenceRegistry.resetForTests()

    @AfterTest
    fun tearDown(): Unit = IosInferenceRegistry.resetForTests()

    @Test
    fun `last lease closes sessions while retaining app provider for reuse`() {
        val provider = SpyProvider()
        IosInferenceRegistry.install(provider)

        val first = checkNotNull(IosInferenceRegistry.acquire())
        val second = checkNotNull(IosInferenceRegistry.acquire())
        first.release()
        first.release()
        assertEquals(0, provider.closeCalls, "lease release must be idempotent")

        second.release()
        assertEquals(1, provider.closeCalls)
        assertSame(provider, IosInferenceRegistry.current())

        checkNotNull(IosInferenceRegistry.acquire()).release()
        assertEquals(2, provider.closeCalls, "the retained provider must support another lifecycle")
    }

    @Test
    fun `two subsystems share provider until both close`() = runBlocking {
        val provider = SpyProvider()
        IosInferenceRegistry.install(provider)
        val backend = IosEmbeddingBackend(SmartEngineConfig(embeddingDim = 3))
        // A second lease holder; in the app it is the text encoder, which also needs the bundle's
        // vocabulary and so cannot load in a unit test.
        val other = IosEmbeddingBackend(SmartEngineConfig(embeddingDim = 3))

        assertTrue(backend.loadModel().isSuccess)
        assertTrue(other.loadSemanticModel().isSuccess)
        assertEquals(1, provider.audioLoads)
        assertEquals(1, provider.semanticLoads)

        backend.close()
        backend.close()
        assertEquals(0, provider.closeCalls)
        other.close()
        assertEquals(1, provider.closeCalls)

        assertTrue(backend.loadModel().isSuccess)
        backend.close()
        assertEquals(2, provider.closeCalls, "release then initialize must reuse the Swift provider")
    }

    @Test
    fun `replacing provider invalidates stale leases without closing replacement`() {
        val old = SpyProvider()
        val replacement = SpyProvider()
        IosInferenceRegistry.install(old)
        val stale = checkNotNull(IosInferenceRegistry.acquire())

        IosInferenceRegistry.install(replacement)
        assertEquals(1, old.closeCalls)
        assertSame(replacement, IosInferenceRegistry.current())

        val current = checkNotNull(IosInferenceRegistry.acquire())
        stale.release()
        assertEquals(0, replacement.closeCalls)
        current.release()
        assertEquals(1, replacement.closeCalls)
    }

    @Test
    fun `backend preserves native deterministic and transient audio classifications`(): Unit = runBlocking {
        val provider = SpyProvider()
        IosInferenceRegistry.install(provider)
        val backend = IosEmbeddingBackend(SmartEngineConfig(embeddingDim = 3))
        assertTrue(backend.loadModel().isSuccess)
        val track = TrackDescriptor(id = TrackId("typed-ios-audio"), audioUri = "file:///song.m4a")

        provider.audioResult = IosAudioEmbeddingResult(
            status = IosAudioEmbeddingStatus.INVALID_AUDIO,
            technicalDetail = "no decodable windows",
        )
        assertIs<EngineError.InvalidAudio>(
            assertIs<SmartEngineException>(backend.embed(track).exceptionOrNull()).error,
        )

        provider.audioResult = IosAudioEmbeddingResult(
            status = IosAudioEmbeddingStatus.UNAVAILABLE,
            technicalDetail = "file access temporarily failed",
        )
        assertIs<EngineError.AudioUnavailable>(
            assertIs<SmartEngineException>(backend.embed(track).exceptionOrNull()).error,
        )

        provider.audioResult = IosAudioEmbeddingResult(
            status = IosAudioEmbeddingStatus.BACKEND_FAILURE,
            technicalDetail = "ORT run failed",
        )
        assertIs<EngineError.BackendFailure>(
            assertIs<SmartEngineException>(backend.embed(track).exceptionOrNull()).error,
        )
    }

    @Test
    fun `wrong-dimension native success remains global backend failure`(): Unit = runBlocking {
        val provider = SpyProvider()
        IosInferenceRegistry.install(provider)
        val backend = IosEmbeddingBackend(SmartEngineConfig(embeddingDim = 3))
        assertTrue(backend.loadModel().isSuccess)
        provider.audioResult = IosAudioEmbeddingResult(
            status = IosAudioEmbeddingStatus.SUCCESS,
            embedding = floatArrayOf(1f, 0f),
        )

        val failure = backend.embed(
            TrackDescriptor(id = TrackId("wrong-dim"), audioUri = "file:///song.m4a"),
        ).exceptionOrNull()
        assertIs<EngineError.BackendFailure>(assertIs<SmartEngineException>(failure).error)
    }

    private class SpyProvider : IosInferenceProvider {
        var audioLoads: Int = 0
        var semanticLoads: Int = 0
        var closeCalls: Int = 0
        var audioResult: IosAudioEmbeddingResult = IosAudioEmbeddingResult(
            status = IosAudioEmbeddingStatus.INVALID_AUDIO,
            technicalDetail = "test provider has no audio",
        )

        override fun loadAudio(): String? {
            audioLoads++
            return null
        }

        override fun embedAudio(
            uri: String,
            durationMs: Long,
            outputDim: Int,
        ): IosAudioEmbeddingResult = audioResult

        override fun loadSemantic(): String? {
            semanticLoads++
            return null
        }

        override fun classifySemantics(
            embeddings: FloatArray,
            batchSize: Int,
            inputDim: Int,
            outputDim: Int,
        ): FloatArray? = null

        override fun loadText(): String? = null

        override fun encodeText(inputIds: LongArray): FloatArray? = null

        override fun close(): Unit {
            closeCalls++
        }
    }
}
