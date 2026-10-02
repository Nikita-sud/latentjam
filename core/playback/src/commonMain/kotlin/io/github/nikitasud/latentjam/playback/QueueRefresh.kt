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

/**
 * The queue items, by index in [queueIds], that [updates] refreshes, each with whether its cover
 * changed: whether [previousArtworkUris], the covers held before the refresh, lacks the track or
 * names another cover. Only those items get their artwork republished, so a title edit keeps a
 * generated cover, and one song's new cover leaves the rest of its album as it was.
 */
internal fun refreshedItems(
    queueIds: List<String>,
    previousArtworkUris: Map<TrackId, String?>,
    updates: Map<TrackId, TrackDescriptor>,
): List<Pair<Int, Boolean>> = queueIds.mapIndexedNotNull { index, id ->
    val fresh = updates[TrackId(id)] ?: return@mapIndexedNotNull null
    val unchangedCover = fresh.id in previousArtworkUris && previousArtworkUris[fresh.id] == fresh.artworkUri
    index to !unchangedCover
}
