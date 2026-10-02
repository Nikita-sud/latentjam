/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Album-art URIs carrying their album's version.
 *
 * A tag edit changes a file's revision but not its album's `…/albumart/<id>` URI, and the image
 * loader, the accent colour and Media3's notification all cache by URI string. Without a version
 * every one of them keeps the old cover (spec §6.5).
 *
 * The version is the sum of the album's track revisions' hashes: the same for every track of the
 * album, so grouping, which compares these URIs, is unaffected. It does not depend on track order,
 * and it changes when any track changes. MediaProvider opens album art by the URI's path alone, so
 * the query is invisible to it.
 */
internal fun withAlbumArtVersions(tracks: List<TrackDescriptor>): List<TrackDescriptor> {
    val versions = HashMap<String, Long>()
    for (track in tracks) {
        val uri = track.artworkUri ?: continue
        versions[uri] = (versions[uri] ?: 0L) + (track.sourceRevision?.hashCode() ?: 0)
    }
    return tracks.map { track ->
        val uri = track.artworkUri ?: return@map track
        track.copy(artworkUri = "$uri?v=${versions.getValue(uri).toULong().toString(16)}")
    }
}
