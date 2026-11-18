/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import androidx.media3.common.Player

/** A seek can buffer without changing the listener's intent to keep playing. */
internal fun showPauseButton(
    playWhenReady: Boolean,
    playbackState: Int,
    pausePending: Boolean,
): Boolean = playWhenReady && !pausePending &&
    (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

/**
 * Whether a change of the transport's intent is the player stopping by itself at the end of the row it played
 * (`pauseAtEndOfMediaItems`, set while the sleep timer waits for the end of the track): see
 * [NowPlaying.pausedAtItemEnd]. Any other pause, and any play, is not.
 */
internal fun pausedAtEndOfItem(playWhenReady: Boolean, reason: Int): Boolean =
    !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
