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
