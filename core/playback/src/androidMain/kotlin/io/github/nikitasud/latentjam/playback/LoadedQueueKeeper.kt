/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * The answer to a set request that should play the queue already loaded, as it stands; see
 * [LoadedQueueKeeper]. It has no audio, so it must never reach ExoPlayer.
 */
internal val KeepLoadedQueue: MediaItem =
    MediaItem.Builder().setMediaId("latentjam:keep-loaded-queue").build()

/**
 * The session's player, which takes a set of [KeepLoadedQueue] as a request to leave its queue
 * alone.
 *
 * Media3 installs whatever the session callback resolves a set request to, and only then prepares
 * the player if it must, restarts a track that ended and plays when the controller asked it to.
 * Installed again, the loaded queue would restart the playing track from an empty buffer and draw a
 * new shuffle order, so a request for it resolves to the stand-in, which stops here whichever
 * setter Media3 uses. Media3's prepare and play then act on the queue as it was.
 */
internal open class LoadedQueueKeeper(player: Player) : ForwardingPlayer(player) {

    override fun setMediaItem(mediaItem: MediaItem) {
        if (mediaItem !== KeepLoadedQueue) super.setMediaItem(mediaItem)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (mediaItem !== KeepLoadedQueue) super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (mediaItem !== KeepLoadedQueue) super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        if (!mediaItems.keepsLoadedQueue()) super.setMediaItems(mediaItems)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        if (!mediaItems.keepsLoadedQueue()) super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        if (!mediaItems.keepsLoadedQueue()) {
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }
    }

    private fun List<MediaItem>.keepsLoadedQueue(): Boolean = singleOrNull() === KeepLoadedQueue
}
