/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.MediaStoreArtwork
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Cover URIs carrying their album's version.
 *
 * A tag edit changes a file's revision but not its cover's URI, and the image loader, the accent
 * colour and Media3's notification all cache by URI string. Without a version every one of them
 * keeps the old cover (spec §6.5).
 *
 * The version is the sum of the album's track revisions' hashes: the same for every track of the
 * album, so grouping, which compares the album's URI, is unaffected. It does not depend on track
 * order, and it changes when any track changes. A song's own cover carries its album's version
 * too: it falls back to the album's cover, which MediaProvider makes from another file of the
 * album. MediaProvider opens covers by the URI's path alone, so the query is invisible to it.
 */
internal fun withAlbumArtVersions(tracks: List<TrackDescriptor>): List<TrackDescriptor> {
    val versions = HashMap<String, Long>()
    for (track in tracks) {
        val album = track.albumCover() ?: continue
        versions[album] = (versions[album] ?: 0L) + (track.sourceRevision?.hashCode() ?: 0)
    }
    return tracks.map { track ->
        val album = track.albumCover() ?: return@map track
        val version = versions.getValue(album).toULong().toString(16)
        track.copy(
            artworkUri = track.artworkUri?.let { MediaStoreArtwork.versioned(it, version) },
            albumArtworkUri = track.albumArtworkUri?.let { MediaStoreArtwork.versioned(it, version) },
        )
    }
}

/** The album's cover, by which its tracks share one version. */
private fun TrackDescriptor.albumCover(): String? = albumArtworkUri ?: artworkUri
