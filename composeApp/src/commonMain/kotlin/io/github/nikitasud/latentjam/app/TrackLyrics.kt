/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * Reads lyrics embedded in the track's own file (ID3 `USLT`), fully offline.
 *
 * Returns null for files without embedded lyrics, non-ID3 containers, and platforms without a
 * reader yet. Main-safe: implementations do their IO off the caller's thread.
 */
@Composable
internal expect fun rememberLyricsReader(): suspend (TrackDescriptor) -> String?
