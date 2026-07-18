/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * iOS artwork sampling is not wired yet; tracks fall back to their latent
 * colour (once indexed) or the theme accent. A UIImage + CoreImage average
 * pass lands with the iOS library source.
 */
@Composable
actual fun rememberArtworkColor(uri: String?): Color? = null
