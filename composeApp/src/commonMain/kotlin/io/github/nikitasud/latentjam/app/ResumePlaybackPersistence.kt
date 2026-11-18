/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map

/**
 * Startup must not erase a saved session; emptying an established queue must erase it. While [hold] keeps a
 * queue that was restored without some of its rows, what is written is the stored queue, not the shortened one.
 */
internal fun Flow<ResumePlayback?>.resumePlaybackWrites(
    hold: MutableStateFlow<HeldResumeQueue?>? = null,
): Flow<ResumePlayback?> =
    dropWhile { it == null }.map { live -> hold?.storedFor(live) ?: live }.distinctUntilChanged()

/**
 * A saved session restored from a library scan that could not see every song (a card that is out or still being
 * checked, a grant that is missing): the player got the rows that could be found, and the stored queue keeps the
 * others, so they are back with the storage. Without this the shortened queue was written over the stored one at
 * once and the missing rows were gone for good.
 *
 * It lasts while the player's queue is still the restored rows, with or without rows added after them (a SMART
 * top-up). Any other queue is the listener's new one and is stored as it is.
 */
internal class HeldResumeQueue(
    /** The session as it was stored when it was restored. */
    val saved: ResumePlayback,
    /** For each restored row, its place in [saved]'s queue. */
    val restoredIndices: List<Int>,
) {
    /** The ids of the rows the player was given, in order. */
    val restoredIds: List<String> = restoredIndices.map { saved.queueTrackIds[it] }

    /** The stored rows the player was not given. */
    private val missingIds: Set<String> = saved.queueTrackIds.toHashSet().apply { removeAll(restoredIds.toSet()) }

    /** The last live queue merged and its result: the live list is the same instance until the queue changes. */
    @Volatile private var merged: Pair<List<String>, List<String>?>? = null

    /**
     * Whether [liveIds] is still the restored queue, possibly with rows added after it. A row the player was not
     * given is not such an addition: with it the queue is the stored one coming back, not the restored one grown.
     */
    fun holds(liveIds: List<String>): Boolean =
        liveIds.size >= restoredIds.size && liveIds.subList(0, restoredIds.size) == restoredIds &&
            (restoredIds.size until liveIds.size).none { liveIds[it] in missingIds }

    /**
     * What to store while the player shows [live]: the stored queue with the rows added since after it, and the
     * live track, position, mode and source. Null when [live] is another queue.
     */
    fun storedFor(live: ResumePlayback): ResumePlayback? {
        val liveIds = live.queueTrackIds
        val queue = merged?.takeIf { it.first === liveIds }?.second ?: run {
            val result = when {
                !holds(liveIds) -> null
                liveIds.size == restoredIds.size -> saved.queueTrackIds
                else -> saved.queueTrackIds + liveIds.subList(restoredIds.size, liveIds.size)
            }
            merged = liveIds to result
            result
        } ?: return null
        val index = live.queueIndex
        return saved.copy(
            trackId = live.trackId,
            shuffleMode = live.shuffleMode,
            positionMs = live.positionMs,
            sourceKind = live.sourceKind,
            sourceName = live.sourceName,
            sourceReference = live.sourceReference,
            queueTrackIds = queue,
            queueIndex = when {
                index < 0 -> index
                index < restoredIndices.size -> restoredIndices[index]
                else -> saved.queueTrackIds.size + (index - restoredIndices.size)
            },
            smartContinuationIds = saved.smartContinuationIds.plusNew(live.smartContinuationIds),
            smartPlanPositions = if (saved.smartPlanPositions.keys.containsAll(live.smartPlanPositions.keys)) {
                saved.smartPlanPositions
            } else {
                live.smartPlanPositions + saved.smartPlanPositions
            },
        )
    }

    private fun Set<String>.plusNew(other: Set<String>): Set<String> = if (containsAll(other)) this else this + other
}

/**
 * The hold for a session about to be restored with the rows at [restoredIndices] of its stored queue: null when
 * the scan can vouch for itself ([authoritative]: what it does not show is gone), or when it shows every row of
 * the queue and of its source ([known]).
 */
internal fun heldResumeQueue(
    saved: ResumePlayback,
    restoredIndices: List<Int>,
    known: (String) -> Boolean,
    authoritative: Boolean,
): HeldResumeQueue? {
    if (authoritative || restoredIndices.isEmpty()) return null
    val shortened = restoredIndices.size < saved.queueTrackIds.size || saved.sourceQueueTrackIds.any { !known(it) }
    return if (shortened) HeldResumeQueue(saved, restoredIndices) else null
}

/** The hold of this process, if a restore made one: set by the restore, read by the writer of the saved session. */
internal val resumeQueueHold = MutableStateFlow<HeldResumeQueue?>(null)

/** What to write for [live]; a queue the hold no longer covers, or none at all, ends the hold. */
private fun MutableStateFlow<HeldResumeQueue?>.storedFor(live: ResumePlayback?): ResumePlayback? {
    val held = value ?: return live
    val stored = live?.let(held::storedFor)
    if (stored == null) compareAndSet(held, null)
    return stored ?: live
}

/** Keep disk writes bounded during playback, but resume at the actual pause/seek position. */
internal fun resumePositionMs(positionMs: Long, isPlaying: Boolean): Long =
    positionMs.coerceAtLeast(0).let { if (isPlaying) it - it % 10_000 else it }
