/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The native call takes the engine's inference lock per window (docs/analysis-094-plan.md 1b), never per track. */
class IosWindowGateTest {

    @BeforeTest
    fun setUp(): Unit = IosInferenceRegistry.resetForTests()

    @AfterTest
    fun tearDown(): Unit = IosInferenceRegistry.resetForTests()

    private val track = TrackDescriptor(id = TrackId("gated"), audioUri = "file:///song.m4a")

    /** Two windows, each under the gate; between them it waits for [between], holding nothing. */
    private class TwoWindowProvider(private val lock: Mutex) : IosInferenceProvider {
        val firstDone = CompletableDeferred<Unit>()
        val between = CompletableDeferred<Unit>()
        val heldInWindows = ArrayList<Boolean>()
        var windowsRun = 0

        override fun loadAudio(): String? = null

        override fun embedAudio(uri: String, durationMs: Long, outputDim: Int, gate: IosInferenceGate?): IosAudioEmbeddingResult {
            checkNotNull(gate)
            repeat(2) { window ->
                if (!gate.enter()) {
                    return IosAudioEmbeddingResult(IosAudioEmbeddingStatus.UNAVAILABLE, technicalDetail = "Analysis stopped")
                }
                heldInWindows += lock.isLocked
                windowsRun++
                gate.exit()
                if (window == 0) {
                    firstDone.complete(Unit)
                    runBlocking { between.await() }
                }
            }
            return IosAudioEmbeddingResult(IosAudioEmbeddingStatus.SUCCESS, embedding = floatArrayOf(1f, 0f, 0f))
        }

        override fun loadSemantic(): String? = null
        override fun classifySemantics(embeddings: FloatArray, batchSize: Int, inputDim: Int, outputDim: Int): FloatArray? = null
        override fun loadText(): String? = null
        override fun encodeText(inputIds: LongArray): FloatArray? = null
        override fun close() {}
    }

    private fun gated(lock: Mutex, provider: IosInferenceProvider): IosEmbeddingBackend {
        IosInferenceRegistry.install(provider)
        return IosEmbeddingBackend(SmartEngineConfig(embeddingDim = 3)).also {
            assertTrue(it.takeInferenceLock(lock))
        }
    }

    @Test
    fun theLockIsFreeBetweenTwoWindowsOfOneTrack(): Unit = runBlocking {
        val lock = Mutex()
        val provider = TwoWindowProvider(lock)
        val backend = gated(lock, provider)
        assertTrue(backend.loadModel().isSuccess)
        val embed = async(Dispatchers.IO) { backend.embed(track) }
        withTimeout(10_000) { provider.firstDone.await() }
        // A press would take it here, between the windows.
        assertTrue(lock.tryLock(), "the lock is free while the track waits between windows")
        lock.unlock()
        provider.between.complete(Unit)
        assertTrue(withTimeout(10_000) { embed.await() }.isSuccess)
        assertEquals(listOf(true, true), provider.heldInWindows)
    }

    @Test
    fun aModelClosedBetweenWindowsIsNeverRunAndIsNotTheFilesFault(): Unit = runBlocking {
        val lock = Mutex()
        val provider = TwoWindowProvider(lock)
        val backend = gated(lock, provider)
        assertTrue(backend.loadModel().isSuccess)
        val embed = async(Dispatchers.IO) { backend.embed(track) }
        withTimeout(10_000) { provider.firstDone.await() }
        lock.withLock { backend.close() }
        provider.between.complete(Unit)
        val failure = withTimeout(10_000) { embed.await() }.exceptionOrNull()
        assertIs<EngineError.ModelUnavailable>(assertIs<SmartEngineException>(failure).error)
        assertEquals(1, provider.windowsRun)
        assertFalse(lock.isLocked)
    }
}
