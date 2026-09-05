/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/** Null preserves the queue; an empty set proves no remaining known track may stay in it. */
internal fun queueRetentionIds(
    snapshot: AutomaticIndexingRequest,
    hiddenTracks: List<TrackDescriptor>,
): Set<TrackId>? {
    if (!snapshot.librarySnapshotAuthoritative) return null
    return snapshot.tracks.mapTo(HashSet()) { it.id }.also { known ->
        // Hiding a track changes browsing/recommendations, not an existing listening session.
        hiddenTracks.mapTo(known) { it.id }
    }
}
