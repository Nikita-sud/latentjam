/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile

/** Startup must not erase a saved session; emptying an established queue must erase it. */
internal fun Flow<ResumePlayback?>.resumePlaybackWrites(): Flow<ResumePlayback?> =
    dropWhile { it == null }.distinctUntilChanged()

/** Keep disk writes bounded during playback, but resume at the actual pause/seek position. */
internal fun resumePositionMs(positionMs: Long, isPlaying: Boolean): Long =
    positionMs.coerceAtLeast(0).let { if (isPlaying) it - it % 10_000 else it }
