/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Reads lyrics embedded in the track's own file (ID3 `USLT`), fully offline.
 *
 * Returns null for files without supported embedded lyrics. Search opts into read failures
 * so a temporarily unreadable file cannot be cached as having no lyrics. Main-safe: implementations do their IO off the caller's thread.
 */
@Composable
internal expect fun rememberLyricsReader(reportReadFailures: Boolean = false): suspend (TrackDescriptor) -> Lyrics?
