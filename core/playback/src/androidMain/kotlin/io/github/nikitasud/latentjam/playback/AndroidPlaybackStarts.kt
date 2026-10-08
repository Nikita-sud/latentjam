/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the playback instance at the playhead began, kept between the system-created
 * [PlaybackService] and the app-owned controller like [AndroidShuffleModeRegistry].
 *
 * The service's own ExoPlayer feeds it, because the player reports each change once, with its real
 * reason. The controller's MediaController is a poor witness: it reports its masked guess of a
 * command and then the session's confirmation, and re-reports the playing item with a stale reason
 * whenever its copy of the item and the session's differ — a queue restored at launch came back as
 * an automatic advance, a confirmed skip as a second jump. Only the app knows why it issued a
 * command, so the controller still [announce]s its own. Main-thread only: the service's player and
 * the controller share the main looper.
 */
internal object AndroidPlaybackStarts {
    private val ledger = PlaybackStartLedger()

    /** The last jump between rows, which the player reports just before the transition it causes. */
    private var pendingSeek: SeekBetweenRows? = null

    private val mutableCurrent = MutableStateFlow<PlaybackStart?>(null)

    /** See [NowPlaying.playbackStart]. */
    val current: StateFlow<PlaybackStart?> = mutableCurrent.asStateFlow()

    private val mutableExternalQueues = MutableStateFlow(0L)

    /**
     * How many queues a controller outside the app has installed, for the app's own controller.
     * What it remembers about the queue it built — the tracks the listener removed from SMART — does
     * not hold for a queue it did not build. [MediaBrowseRegistry.onExternalQueue] belongs to the app
     * module, so the controller in this module listens here instead of taking that callback over.
     */
    val externalQueues: StateFlow<Long> = mutableExternalQueues.asStateFlow()

    /** The command about to run will make [target] current because of [cause]. */
    fun announce(cause: StartCause, target: TrackId) {
        ledger.announce(cause, target)
    }

    /**
     * From the service's session, before it installs [mediaItems]: a controller outside the app set
     * this queue, so the row it starts on — see [externalQueueStart] — is the listener's pick. The
     * [startIndex] is Media3's, where [C.INDEX_UNSET] leaves the row to the player; [shuffled] is the
     * player's own shuffle.
     */
    fun announceExternalQueue(mediaItems: List<MediaItem>, startIndex: Int, shuffled: Boolean) {
        // Even when no start can be named, the queue now belongs to whoever sent it.
        MediaBrowseRegistry.onExternalQueue?.invoke()
        mutableExternalQueues.value++
        val start = externalQueueStart(
            mediaItems,
            startIndex.takeUnless { it == C.INDEX_UNSET },
            shuffled,
        ) ?: return
        ledger.announce(StartCause.USER_PICK, TrackId(start.mediaId))
    }

    /** From the service player's listener: a skip between rows or an announced in-place replay. */
    fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
            pendingSeek = SeekBetweenRows(oldPosition.mediaItemIndex, newPosition.mediaItemIndex)
        } else if (newPosition.positionMs == 0L) {
            val trackId = newPosition.mediaItem?.mediaId?.let(::TrackId) ?: return
            ledger.restartIfAnnounced(trackId)?.let { mutableCurrent.value = it }
        }
    }

    /** From the service player's listener: [player] made [mediaItem] current because of [reason]. */
    fun onMediaItemTransition(player: Player, mediaItem: MediaItem?, reason: Int) {
        val seek = pendingSeek
        pendingSeek = null
        val trackId = mediaItem?.mediaId?.let(::TrackId) ?: return
        when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> ledger.begin(trackId, StartCause.AUTO_ADVANCE)
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> ledger.begin(trackId, StartCause.REPEAT)
            // Next or Previous from a headset, the notification or a car arrive only as a seek.
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ->
                ledger.begin(trackId, seek?.let { seekCause(player, it) })
            // A queue installed, restored, or edited around the playhead.
            else -> ledger.playlistChanged(trackId)
        }
        mutableCurrent.value = ledger.current
    }

    /** What a seek between rows amounted to in the transport's own traversal. */
    private fun seekCause(player: Player, seek: SeekBetweenRows): StartCause? {
        val timeline = player.currentTimeline
        if (seek.fromRow !in 0 until timeline.windowCount) return null
        // Next and Previous treat repeat-one as off; read the jump the way they took it.
        val repeat = player.repeatMode.takeUnless { it == Player.REPEAT_MODE_ONE } ?: Player.REPEAT_MODE_OFF
        val shuffle = player.shuffleModeEnabled
        return seekStartCause(
            toIndex = seek.toRow,
            nextIndex = timeline.getNextWindowIndex(seek.fromRow, repeat, shuffle)
                .takeIf { it != C.INDEX_UNSET },
            previousIndex = timeline.getPreviousWindowIndex(seek.fromRow, repeat, shuffle)
                .takeIf { it != C.INDEX_UNSET },
        )
    }

    private data class SeekBetweenRows(val fromRow: Int, val toRow: Int)
}
