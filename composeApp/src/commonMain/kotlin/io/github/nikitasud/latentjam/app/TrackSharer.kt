/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** Opens the platform share sheet for the locally-addressable tracks in [List]. */
@Composable
internal expect fun rememberTrackSharer(): (List<TrackDescriptor>) -> Unit
