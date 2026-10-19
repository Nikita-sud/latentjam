/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListeningNormalization
import io.github.nikitasud.latentjam.history.NormalizedHistory
import io.github.nikitasud.latentjam.history.epochMillis
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One normalised log per history revision, shared by the statistics page's sections so the log is
 * read once.
 *
 * Main-thread only (callers are composition effects). The producer never throws — a failure comes
 * back as a [Result] — and runs under its own supervisor, so a failure cannot cancel the screen's
 * scope. A producer cancelled because the page went inactive, or superseded by a newer revision, is
 * made again by the next awaiter; a finished one stays cached until the revision or retry changes.
 */
internal class StatsSnapshots(
    private val scope: CoroutineScope,
    private val readEvents: suspend () -> List<ListenEvent>,
    private val nowMs: () -> Long = ::epochMillis,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) {
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private var key: Pair<Long, Int>? = null
    private var producer: Deferred<Result<NormalizedHistory>>? = null

    private fun current(revision: Long, retry: Int): Deferred<Result<NormalizedHistory>> {
        val existing = producer
        if (existing != null && key == (revision to retry) && !existing.isCancelled) return existing
        existing?.cancel()
        key = revision to retry
        return scope.async(supervisor + dispatcher) {
            try {
                Result.success(
                    ListeningNormalization.normalize(
                        events = readEvents(),
                        nowMs = nowMs(),
                        cancellationCheck = { ensureActive() },
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        }.also { producer = it }
    }

    /** The snapshot for [revision]; a new [retry] value reads the log again. Throws its failure. */
    suspend fun await(revision: Long, retry: Int): NormalizedHistory {
        val result = try {
            current(revision, retry).await()
        } catch (cancelled: CancellationException) {
            // Our own cancellation ends here; a cancelled shared producer is simply made again.
            currentCoroutineContext().ensureActive()
            current(revision, retry).await()
        }
        return result.getOrThrow()
    }

    /** Stops a read in progress; a finished snapshot stays for when the page is shown again. */
    fun cancelInFlight() {
        producer?.takeIf { !it.isCompleted }?.cancel()
    }
}
