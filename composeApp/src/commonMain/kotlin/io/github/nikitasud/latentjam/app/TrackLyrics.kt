/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Reads the track's lyrics, fully offline: those embedded in its own file, and a `.lrc` file
 * beside it (on Android 10+ only inside folders granted in Settings), chosen by
 * [io.github.nikitasud.latentjam.library.tags.SidecarLyrics.choose]. A sidecar problem of any kind
 * falls back to the embedded lyrics and is never reported.
 *
 * Returns null when neither source has lyrics. Search opts into read failures
 * so a temporarily unreadable file cannot be cached as having no lyrics. Main-safe: implementations do their IO off the caller's thread.
 */
@Composable
internal expect fun rememberLyricsReader(reportReadFailures: Boolean = false): suspend (TrackDescriptor) -> Lyrics?
