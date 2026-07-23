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
