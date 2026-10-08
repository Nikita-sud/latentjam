/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * Chooses the candidate universe without confusing an explicitly empty eligible library with an
 * uninitialized one. Falling back on `ifEmpty` would reintroduce every excluded track when the
 * listener excludes their whole library.
 */
internal fun smartCandidatePool(
    eligibleLibrary: List<TrackDescriptor>,
    fallbackPool: List<TrackDescriptor>,
    eligibleLibrarySupplied: Boolean,
): List<TrackDescriptor> = if (eligibleLibrarySupplied) eligibleLibrary else fallbackPool

/**
 * What one SMART top-up may append: [universe] ([smartCandidatePool]) less every queued track, which
 * would play twice, and every track the listener removed from this queue ([recordSmartRemoval]).
 */
internal fun smartTopUpCandidates(
    universe: List<TrackDescriptor>,
    queuedIds: Set<TrackId>,
    removedIds: Set<TrackId>,
): List<TrackDescriptor> = universe.filter { it.id !in queuedIds && it.id !in removedIds }

/**
 * Books the listener's removal of a [trackId] row, whatever the shuffle mode. The track then stays
 * out of SMART's top-ups for the rest of this queue: the engine replans from where the queue now
 * ends, and that plan, or the walk it resumes, would otherwise bring it straight back — the walk
 * releases the picks a top-up offers again, because only the controllers can tell a removal from the
 * app discarding the future to replan it. A queue played in order or shuffled counts too: switched to
 * SMART, it resumes the walk it came from (a For You journey, an earlier SMART plan), which may hold
 * the removed track. A new queue forgets the removals, and so does queueing the track by hand
 * ([releaseSmartProvenance]).
 */
internal fun recordSmartRemoval(removedIds: MutableSet<TrackId>, trackId: TrackId) {
    removedIds += trackId
}

/** Keeps playback history/current intent, but removes ineligible items from the generated tail. */
internal fun retainEligibleSmartTail(
    queue: List<TrackDescriptor>,
    currentIndex: Int,
    eligibleIds: Set<TrackId>,
): List<TrackDescriptor> {
    if (queue.isEmpty()) return emptyList()
    // A prepared/cued queue can briefly report no current index. Its first item is still the
    // listener's explicit seed and must survive an eligibility refresh.
    val keepThrough = currentIndex.coerceIn(0, queue.lastIndex)
    return queue.filterIndexed { index, track ->
        index <= keepThrough || track.id in eligibleIds
    }
}
