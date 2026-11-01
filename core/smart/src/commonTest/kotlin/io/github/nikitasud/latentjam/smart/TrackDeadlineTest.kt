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
import kotlinx.coroutines.sync.withLock
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
        // D1: a timeout is transient now, retried by the app's ledger (0.9.3 remembered it as invalid audio).
        assertIs<EngineError.AudioUnavailable>(timedOut)
        assertTrue(timedOut.isAnalysisTimeout())
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
        assertIs<EngineError.AudioUnavailable>((result.exceptionOrNull() as SmartEngineException).error)
        assertNull(result.getOrNull())
    }

    @Test
    fun aTimeoutSpentWaitingForTheInferenceLockIsNotTheFilesFault() = runTest {
        val decoding = DeadlineDecoding(CountingWorkers(), budget = 200.milliseconds)
        // A running total, as the backend keeps it: 50 ms already on it from earlier tracks.
        var total = 50.milliseconds
        val excused = withContext(Dispatchers.Default) {
            decoding.withinBudget(waited = { total }) {
                total += 30.milliseconds
                decoding.decode { hang() }
                Result.success(floatArrayOf(1f))
            }
        }
        val error = (excused.exceptionOrNull() as SmartEngineException).error
        assertIs<EngineError.AudioUnavailable>(error)
        assertTrue(!error.isAnalysisTimeout(), "a tenth of the budget waited: no D2 attempt")
        val counted = withContext(Dispatchers.Default) {
            decoding.withinBudget(waited = { total }) {
                total += 10.milliseconds
                decoding.decode { hang() }
                Result.success(floatArrayOf(1f))
            }
        }
        assertTrue((counted.exceptionOrNull() as SmartEngineException).error.isAnalysisTimeout())
    }

    @Test
    fun anEarlierTracksLockWaitNeverExcusesTheNextTracksHang() = runTest {
        val decoding = DeadlineDecoding(CountingWorkers(), budget = 300.milliseconds)
        var waitedTotal = kotlin.time.Duration.ZERO
        val results = mutableListOf<Pair<TrackId, Result<FloatArray>>>()
        withContext(Dispatchers.Default) {
            decoding.each(
                tracks = tracks.take(2),
                ahead = 1,
                prefetch = { track -> if (track.id.value == "b") hang(); vector(track) },
                // "a" waited 100 ms for the lock (a third of the budget) and finished; "b" hangs in its decode.
                finish = { track, decoded -> if (track.id.value == "a") waitedTotal += 100.milliseconds; Result.success(decoded) },
                waited = { waitedTotal },
                onResult = { track, result -> results += track.id to result },
            )
        }
        assertTrue(results[0].second.isSuccess)
        assertTrue((results[1].second.exceptionOrNull() as SmartEngineException).error.isAnalysisTimeout())
    }

    private val decodedLock = kotlinx.coroutines.sync.Mutex()

    private val chunkB = listOf("e", "f", "g").map { TrackDescriptor(TrackId(it), audioUri = "test://$it") }

    @Test
    fun `decoding carried into the next chunk is reused once and the results are unchanged`() = runTest {
        val decoding = DeadlineDecoding(CountingWorkers())
        val decoded = mutableListOf<String>()
        val results = mutableListOf<Pair<String, Float>>()
        suspend fun chunk(items: List<TrackDescriptor>, upcoming: List<TrackDescriptor>) = decoding.each(
            tracks = items,
            ahead = 2,
            prefetch = { track -> decodedLock.withLock { decoded += track.id.value }; vector(track) },
            finish = { _, vector -> Result.success(vector) },
            onResult = { track, result -> results += track.id.value to result.getOrThrow()[0] },
            upcoming = upcoming,
        )
        withContext(Dispatchers.Default) {
            chunk(tracks, chunkB)
            chunk(chunkB, emptyList())
        }
        assertEquals((tracks + chunkB).map { it.id.value to vector(it)[0] }, results)
        // Every track decoded exactly once, the next chunk's head while the first one finished.
        assertEquals((tracks + chunkB).map { it.id.value }.sorted(), decodedLock.withLock { decoded.sorted() })
    }

    @Test
    fun `carried decoding the next chunk does not ask for is dropped and decoded afresh when asked`() = runTest {
        val decoding = DeadlineDecoding(CountingWorkers())
        val decoded = mutableListOf<String>()
        val results = mutableListOf<String>()
        suspend fun chunk(items: List<TrackDescriptor>, upcoming: List<TrackDescriptor>) = decoding.each(
            tracks = items,
            ahead = 2,
            prefetch = { track -> decodedLock.withLock { decoded += track.id.value }; vector(track) },
            finish = { _, vector -> Result.success(vector) },
            onResult = { track, _ -> results += track.id.value },
            upcoming = upcoming,
        )
        withContext(Dispatchers.Default) {
            chunk(tracks, chunkB)
            // A press indexed "e" meanwhile: the next call brings only f and g.
            chunk(chunkB.drop(1), emptyList())
        }
        assertEquals(listOf("a", "b", "c", "d", "f", "g"), results)
        assertEquals(1, decodedLock.withLock { decoded.count { it == "f" } })
    }

    @Test
    fun `carried decoding expires when no call comes`() = runTest {
        val decoding = DeadlineDecoding(CountingWorkers())
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        withContext(Dispatchers.Default) {
            decoding.each(
                tracks = tracks.take(1),
                ahead = 1,
                prefetch = { track ->
                    if (track.id.value == "e") {
                        started.complete(Unit)
                        try {
                            kotlinx.coroutines.awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }
                    vector(track)
                },
                finish = { _, vector -> Result.success(vector) },
                onResult = { _, _ -> },
                upcoming = chunkB,
            )
            started.await()
            decoding.dropCarried()
            kotlinx.coroutines.delay(50)
        }
        assertTrue(cancelled)
    }

    @Test
    fun `carried decoding is not reused after the workers were replaced between chunks`() = runTest {
        val workers = CountingWorkers()
        val decoding = DeadlineDecoding(workers, budget = 2_000.milliseconds)
        val decoded = mutableListOf<String>()
        val results = mutableListOf<Pair<String, Boolean>>()
        suspend fun chunk(items: List<TrackDescriptor>, upcoming: List<TrackDescriptor>) = decoding.each(
            tracks = items,
            ahead = 2,
            prefetch = { track -> decodedLock.withLock { decoded += track.id.value }; vector(track) },
            finish = { _, vector -> Result.success(vector) },
            upcoming = upcoming,
            onResult = { track, result -> results += track.id.value to result.isSuccess },
        )
        withContext(Dispatchers.Default) {
            chunk(tracks, chunkB)
            // A press's decode timed out in the gap: the pool was replaced under the carried work.
            decoding.withinBudget { decoding.decode { hang() }; Result.success(floatArrayOf(0f)) }
            chunk(chunkB, emptyList())
        }
        assertTrue(results.all { it.second }, "$results")
        assertEquals(listOf("e", "f", "g"), results.drop(4).map { it.first })
    }
}
