/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Tag-only updates retain the local configuration, so Media3 need not prepare again. A relocated
 * file retains the app's media ID but must replace the obsolete URI. Generated artwork survives
 * until a real cover replaces it, including when an unreadable album-art URI is unchanged.
 */
internal fun MediaItem.withRefreshedTrack(fresh: TrackDescriptor, coverChanged: Boolean): MediaItem {
    require(mediaId == fresh.id.value)
    val metadata = mediaMetadata.buildUpon()
        .setTitle(fresh.title)
        .setArtist(fresh.artist)
        .setAlbumTitle(fresh.album)
    if (coverChanged) {
        metadata.setArtworkUri(fresh.artworkUri?.let(Uri::parse))
        if (fresh.artworkUri != null) metadata.setArtworkData(null, null)
    }
    val builder = buildUpon().setMediaMetadata(metadata.build())
    fresh.audioUri?.takeIf { it.isNotBlank() && it != localConfiguration?.uri?.toString() }
        ?.let { builder.setUri(it) }
    return builder.build()
}

/**
 * A different URI forces Media3 to replace its source (remove/add), which resets the current
 * item's position even when its media ID is unchanged. Keep the playhead for this one case.
 * [previousAudioUri] comes from the app's queue: a controller may omit local configurations in
 * the session's timeline. Tag-only refreshes and replacements elsewhere must never seek.
 */
internal fun Player.refreshTrackAt(
    index: Int,
    fresh: TrackDescriptor,
    coverChanged: Boolean,
    previousAudioUri: String?,
) {
    val original = getMediaItemAt(index)
    val previousUri = original.localConfiguration?.uri?.toString() ?: previousAudioUri
    val relocated = !fresh.audioUri.isNullOrBlank() && previousUri != null && fresh.audioUri != previousUri
    val resumePosition = if (relocated && index == currentMediaItemIndex) currentPosition.coerceAtLeast(0L) else null
    replaceMediaItem(index, original.withRefreshedTrack(fresh, coverChanged))
    // Replacing the source keeps playWhenReady; restoring it here would overwrite a pause request.
    if (resumePosition != null) seekTo(index, resumePosition)
}
