/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

internal class StatsSnapshotsTest {

    private val events = listOf(
        ListenEvent(TrackId("a"), startedAtMs = 1_000, playedMs = 60_000, trackDurationMs = 60_000, completed = true, skipped = false),
    )

    @Test
    fun bothSectionsShareOneReadOfTheLog() = runTest {
        var reads = 0
        val snapshots = StatsSnapshots(backgroundScope, { reads++; events }, { 10_000 }, StandardTestDispatcher(testScheduler))
        val first = async { snapshots.await(revision = 1, retry = 0) }
        val second = async { snapshots.await(revision = 1, retry = 0) }
        assertEquals(first.await().events, second.await().events)
        assertEquals(1, reads)
        snapshots.await(revision = 2, retry = 0)
        assertEquals(2, reads)
    }

    @Test
    fun aFailureDoesNotCancelTheScopeAndRetryReadsAgain() = runTest {
        var fail = true
        val snapshots = StatsSnapshots(
            backgroundScope,
            { if (fail) error("disk") else events },
            { 10_000 },
            StandardTestDispatcher(testScheduler),
        )
        assertFailsWith<IllegalStateException> { snapshots.await(revision = 1, retry = 0) }
        assertTrue(backgroundScope.isActive)
        fail = false
        // The same request keeps its answer; a retry reads the log again.
        assertFailsWith<IllegalStateException> { snapshots.await(revision = 1, retry = 0) }
        assertEquals(1, snapshots.await(revision = 1, retry = 1).size)
    }

    @Test
    fun oneAwaiterGoingAwayDoesNotStrandTheOther() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val snapshots = StatsSnapshots(
            backgroundScope,
            { reads++; gate.await(); events },
            { 10_000 },
            StandardTestDispatcher(testScheduler),
        )
        val leaving = async { snapshots.await(revision = 1, retry = 0) }
        val staying = async { snapshots.await(revision = 1, retry = 0) }
        advanceUntilIdle()
        leaving.cancel()
        // The page went inactive mid-read: the shared read stops, and the next awaiter makes it again.
        snapshots.cancelInFlight()
        advanceUntilIdle()
        gate.complete(Unit)
        assertEquals(1, staying.await().size)
        assertTrue(reads >= 1)
    }
}
