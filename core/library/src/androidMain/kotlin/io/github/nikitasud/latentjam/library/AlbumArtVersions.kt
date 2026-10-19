/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.smart.MediaStoreArtwork
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import java.security.MessageDigest

/**
 * Cover URIs carrying their album's version.
 *
 * A tag edit changes a file's revision but not its cover's URI, and the image loader, the accent
 * colour and Media3's notification all cache by URI string. Without a version every one of them
 * keeps the old cover (spec §6.5).
 *
 * The version hashes the album's sorted track identities and full revisions: the same for every
 * track of the album, so grouping, which compares the album's URI, is unaffected. Track order
 * does not matter, but exchanging two tracks' revisions does. A song's own cover carries its
 * album's version too: it falls back to the album's cover, which MediaProvider makes from another file of the
 * album. MediaProvider opens covers by the URI's path alone, so the query is invisible to it.
 */
internal fun withAlbumArtVersions(tracks: List<TrackDescriptor>): List<TrackDescriptor> {
    val versions = tracks.filter { it.albumCover() != null }.groupBy { it.albumCover()!! }
        .mapValues { (_, albumTracks) ->
            val digest = MessageDigest.getInstance("SHA-256")
            for (track in albumTracks.sortedWith(compareBy({ it.id.value }, { it.sourceRevision }))) {
                digest.addField(track.id.value)
                digest.addField(track.sourceRevision)
            }
            digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }
    // One versioned album locator per album, not one equal copy per song.
    val versionedAlbums = HashMap<String, String>()
    return tracks.map { track ->
        val album = track.albumCover() ?: return@map track
        val version = versions.getValue(album)
        track.copy(
            artworkUri = track.artworkUri?.let { MediaStoreArtwork.versioned(it, version) },
            albumArtworkUri = track.albumArtworkUri?.let {
                versionedAlbums.getOrPut(it) { MediaStoreArtwork.versioned(it, version) }
            },
        )
    }
}

/** Length prefixes distinguish null, empty, and arbitrary delimiter-containing revisions. */
private fun MessageDigest.addField(value: String?) {
    val bytes = value?.toByteArray(Charsets.UTF_8)
    val length = bytes?.size ?: -1
    for (shift in 24 downTo 0 step 8) update((length ushr shift).toByte())
    if (bytes != null) update(bytes)
}

/** The album's cover, by which its tracks share one version. */
private fun TrackDescriptor.albumCover(): String? = albumArtworkUri ?: artworkUri
