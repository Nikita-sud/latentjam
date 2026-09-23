/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Main-thread-owned fence for a Next command that must wait for SMART planning. */
internal class PendingPlaybackAdvance {
    private var revision = 0L

    /** A newer transport action or native item transition supersedes the pending command. */
    fun invalidate() { revision++ }

    suspend fun afterPlanning(plan: suspend () -> Unit, advance: () -> Unit) {
        val requestedRevision = revision
        plan()
        currentCoroutineContext().ensureActive()
        if (requestedRevision == revision) advance()
    }
}

/** Failed inference is an abstention; cancelled inference must never append a fallback row. */
internal suspend fun awaitPlaybackRecommendation(
    recommend: suspend () -> TrackDescriptor?,
): TrackDescriptor? {
    currentCoroutineContext().ensureActive()
    val chosen = try {
        recommend()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    currentCoroutineContext().ensureActive()
    return chosen
}

/** Immutable metadata reused by progress ticks; queue work happens only after structural edits. */
internal class PlaybackContinuationSnapshot {
    private var lastQueue: List<TrackDescriptor>? = null
    private var lastIds: Set<TrackId> = emptySet()

    fun get(queue: List<TrackDescriptor>, continuationIds: MutableSet<TrackId>): Set<TrackId> {
        if (queue === lastQueue && continuationIds == lastIds) return lastIds
        if (continuationIds.isNotEmpty()) {
            continuationIds.retainAll(queue.mapTo(HashSet()) { it.id })
        }
        lastQueue = queue
        lastIds = continuationIds.toSet()
        return lastIds
    }
}
