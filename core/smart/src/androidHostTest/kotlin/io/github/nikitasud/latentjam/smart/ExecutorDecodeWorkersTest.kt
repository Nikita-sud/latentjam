/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Issue #27 with real threads: the only decode thread blocks in a call that ignores interruption (as
 * MediaExtractor or MediaCodec can). The track times out, and the tracks behind it still decode on a
 * fresh pool instead of waiting forever behind the stuck thread.
 */
internal class ExecutorDecodeWorkersTest {

    private val gate = CountDownLatch(1)

    /** Blocks the calling thread until [gate] opens, swallowing interrupts like native code. */
    private fun blockUninterruptibly() {
        while (true) {
            try {
                gate.await()
                return
            } catch (_: InterruptedException) {
                // A native call does not notice Thread.interrupt either.
            }
        }
    }

    @AfterTest
    fun release() {
        gate.countDown()
    }

    @Test
    fun `a decode thread stuck in native code costs one track and the pool is replaced`() {
        runBlocking { stuckThreadCostsOneTrack() }
    }

    private suspend fun stuckThreadCostsOneTrack() {
        val workers = ExecutorDecodeWorkers(threads = 1) { task -> Thread(task, "test-decode").apply { isDaemon = true } }
        val decoding = DeadlineDecoding(workers, budget = 400.milliseconds)
        val tracks = listOf("a", "b", "c", "d", "e").map { TrackDescriptor(TrackId(it), audioUri = "test://$it") }
        val results = mutableListOf<Pair<String, Result<FloatArray>>>()

        decoding.each(
            tracks = tracks,
            ahead = 1,
            prefetch = { track ->
                if (track.id.value == "b") blockUninterruptibly()
                floatArrayOf(track.id.value.first().code.toFloat())
            },
            finish = { _, decoded -> Result.success(decoded) },
            onResult = { track, result -> results += track.id.value to result },
        )
        decoding.close()

        assertEquals(listOf("a", "b", "c", "d", "e"), results.map { it.first })
        assertEquals(listOf(true, false, true, true, true), results.map { it.second.isSuccess })
        assertIs<EngineError.InvalidAudio>((results[1].second.exceptionOrNull() as SmartEngineException).error)
    }

    @Test
    fun aPassCancelledWhileItsDecodeIsStuckLeavesTheNextPassFreshWorkers() {
        runBlocking { cancelledPassLeavesFreshWorkers() }
    }

    private suspend fun cancelledPassLeavesFreshWorkers() = coroutineScope {
        val workers = ExecutorDecodeWorkers(threads = 1) { task -> Thread(task, "test-decode").apply { isDaemon = true } }
        val decoding = DeadlineDecoding(workers, budget = 400.milliseconds)
        fun tracks(vararg ids: String) = ids.map { TrackDescriptor(TrackId(it), audioUri = "test://$it") }
        val prefetch: suspend (TrackDescriptor) -> FloatArray = { track ->
            if (track.id.value == "a") blockUninterruptibly()
            floatArrayOf(track.id.value.first().code.toFloat())
        }

        // The only decode thread is stuck on a when the pass is cancelled (a library change, Retry).
        val cancelled = launch {
            decoding.each(tracks("a"), ahead = 1, prefetch, finish = { _, decoded -> Result.success(decoded) }) { _, _ -> }
        }
        delay(50)
        cancelled.cancelAndJoin()

        val results = mutableListOf<Pair<String, Result<FloatArray>>>()
        decoding.each(
            tracks = tracks("b", "c"),
            ahead = 1,
            prefetch = prefetch,
            finish = { _, decoded -> Result.success(decoded) },
            onResult = { track, result -> results += track.id.value to result },
        )
        decoding.close()

        assertEquals(listOf("b" to true, "c" to true), results.map { it.first to it.second.isSuccess })
    }
}
