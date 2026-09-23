/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
internal class PlaybackPendingWorkTest {
    @Test
    fun delayedNextCannotSkipAReplacementTrack() = runTest {
        val pending = PendingPlaybackAdvance()
        val planning = CompletableDeferred<Unit>()
        var currentTrack = "original"
        val next = launch {
            pending.afterPlanning(plan = { planning.await() }) { currentTrack = "unexpected-next" }
        }
        runCurrent()
        pending.invalidate() // The listener selects another song while SMART is working.
        currentTrack = "replacement"
        planning.complete(Unit)
        next.join()
        assertEquals("replacement", currentTrack)

        pending.afterPlanning(plan = {}) { currentTrack = "expected-next" }
        assertEquals("expected-next", currentTrack)
    }

    @Test
    fun cancelledNextNeverCommitsEvenIfPlannerSwallowsCancellation() = runTest {
        val pending = PendingPlaybackAdvance()
        var advances = 0
        val job = launch {
            pending.afterPlanning(plan = {
                try { CompletableDeferred<Unit>().await() } catch (_: CancellationException) { }
            }) { advances++ }
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(0, advances)
    }

    @Test
    fun newerNextSupersedesAnOlderCommandWaitingForTheSamePlanner() = runTest {
        val pending = PendingPlaybackAdvance()
        val planning = CompletableDeferred<Unit>()
        val advances = mutableListOf<String>()
        val old = launch {
            pending.afterPlanning(plan = { planning.await() }) { advances += "old" }
        }
        runCurrent()
        pending.invalidate()
        val latest = launch {
            pending.afterPlanning(plan = { planning.await() }) { advances += "latest" }
        }
        runCurrent()
        planning.complete(Unit)
        old.join()
        latest.join()
        assertEquals(listOf("latest"), advances)
    }

    @Test
    fun failedRecommendationAbstainsButCancellationPropagates() = runTest {
        assertNull(awaitPlaybackRecommendation { error("Unavailable local model") })
        assertFailsWith<CancellationException> {
            awaitPlaybackRecommendation { throw CancellationException("Obsolete request") }
        }
    }

    @Test
    fun progressTicksReuseMetadataAndQueueRemovalPrunesContinuationLabels() {
        val first = TrackDescriptor(id = TrackId("first"))
        val second = TrackDescriptor(id = TrackId("second"))
        val queue = listOf(first, second)
        val ids = mutableSetOf(second.id)
        val cache = PlaybackContinuationSnapshot()
        val initial = cache.get(queue, ids)
        repeat(20) { assertSame(initial, cache.get(queue, ids)) }

        assertEquals(emptySet(), cache.get(listOf(first), ids))
        assertEquals(emptySet(), ids)
        // Previously published state remains immutable after a removal.
        assertEquals(setOf(second.id), initial)
    }
}
