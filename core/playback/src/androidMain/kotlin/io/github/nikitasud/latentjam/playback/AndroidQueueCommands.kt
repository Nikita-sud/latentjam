/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.os.Bundle
import androidx.media3.session.MediaController

/**
 * Queue edits must run AFTER earlier player commands, including asynchronous setMediaItems.
 * Session custom commands bypass that queue. Playlist metadata is an ordered player command;
 * a service-side ForwardingPlayer consumes its private marker before metadata equality checks.
 * Payloads contain only small indices or one media item, never an entire playlist.
 */
internal object AndroidQueueCommand {
    const val KEY = "io.github.nikitasud.latentjam.QUEUE_EDIT"
    const val REQUEST = "request"
    const val OPERATION = "operation"
    const val MEDIA_ID = "media_id"
    const val INDEX = "index"
    const val SIZE = "size"
    const val ITEM = "item"
    const val FROM = "from"
    const val TO = "to"
    const val START = "start"
    const val RESTORE = "restore"
    const val PLAY_NEXT = "play_next"
    const val APPEND = "append"
    const val MOVE = "move"
}

internal fun MediaController.enqueueQueueEdit(operation: String, args: Bundle = Bundle()) {
    val command = Bundle(args).apply {
        putLong(AndroidQueueCommand.REQUEST, System.nanoTime())
        putString(AndroidQueueCommand.OPERATION, operation)
    }
    val extras = Bundle(playlistMetadata.extras ?: Bundle.EMPTY).apply {
        putBundle(AndroidQueueCommand.KEY, command)
    }
    playlistMetadata = playlistMetadata.buildUpon().setExtras(extras).build()
}
