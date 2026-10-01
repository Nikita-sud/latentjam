/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * [queue] with each track [updates] holds a fresher copy of swapped in, by id, order kept. Null
 * when nothing differs, so a controller can skip republishing.
 */
internal fun refreshedTracks(queue: List<TrackDescriptor>, updates: Map<TrackId, TrackDescriptor>): List<TrackDescriptor>? {
    var changed = false
    val refreshed = queue.map { track ->
        val fresh = updates[track.id]
        if (fresh != null && fresh != track) {
            changed = true
            fresh
        } else {
            track
        }
    }
    return refreshed.takeIf { changed }
}
