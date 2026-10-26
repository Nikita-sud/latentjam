/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #27: one track whose decoding never returns must cost that track, not the library. The stuck
 * decode here ignores cancellation, as a platform call blocked in native code does.
 */
internal class TrackDeadlineTest {

    private val stuck = CompletableDeferred<Unit>()

    /** Never returns while [stuck] is open, whatever cancels it. */
    private suspend fun hang() = withContext(NonCancellable) { stuck.await() }

    @AfterTest
    fun release() {
        stuck.complete(Unit)
    }

    private class CountingWorkers : DecodeWorkers {
        var abandoned = 0
        override val dispatcher: CoroutineDispatcher = Dispatchers.Default
        override fun abandon() {
            abandoned++
        }
    }

    private val tracks = listOf("a", "b", "c", "d").map { TrackDescriptor(TrackId(it), audioUri = "test://$it") }

    private fun vector(track: TrackDescriptor) = floatArrayOf(track.id.value.first().code.toFloat())

    @Test
    fun `a track whose prefetch never returns times out and the batch goes on`() = runTest {
        val workers = CountingWorkers()
        val decoding = DeadlineDecoding(workers, budget = 300.milliseconds)
        val results = mutableListOf<Pair<TrackId, Result<FloatArray>>>()

        withContext(Dispatchers.Default) {
            decoding.each(
                tracks = tracks,
                ahead = 2,
                prefetch = { track -> if (track.id.value == "b") hang(); vector(track) },
                finish = { _, decoded -> Result.success(decoded) },
                onResult = { track, result -> results += track.id to result },
            )
        }

        assertEquals(listOf("a", "b", "c", "d"), results.map { it.first.value })
        val timedOut = (results[1].second.exceptionOrNull() as SmartEngineException).error
        assertIs<EngineError.InvalidAudio>(timedOut)
        assertTrue(timedOut.technicalDetail.orEmpty().contains("timed out"))
        for (index in listOf(0, 2, 3)) {
            assertEquals(vector(tracks[index])[0], results[index].second.getOrThrow()[0])
        }
        assertEquals(1, workers.abandoned)
    }

    @Test
    fun `a re-decode that never returns times out on the worker not the caller`() = runTest {
        val workers = CountingWorkers()
        val decoding = DeadlineDecoding(workers, budget = 300.milliseconds)
        val results = mutableListOf<Pair<TrackId, Result<FloatArray>>>()

        withContext(Dispatchers.Default) {
            decoding.each(
                tracks = tracks,
                ahead = 1,
                prefetch = { track -> track },
                finish = { track, _ ->
                    // The inline second decode a backend makes after a transient window failure.
                    decoding.decode { if (track.id.value == "c") hang() }
                    Result.success(vector(track))
                },
                onResult = { track, result -> results += track.id to result },
            )
        }

        assertEquals(listOf(true, true, false, true), results.map { it.second.isSuccess })
        assertEquals(1, workers.abandoned)
    }

    @Test
    fun `a single embed is bounded too`() = runTest {
        val decoding = DeadlineDecoding(CountingWorkers(), budget = 200.milliseconds)
        val result = withContext(Dispatchers.Default) {
            decoding.withinBudget { decoding.decode { hang() }; Result.success(floatArrayOf(1f)) }
        }
        assertIs<EngineError.InvalidAudio>((result.exceptionOrNull() as SmartEngineException).error)
        assertNull(result.getOrNull())
    }
}
