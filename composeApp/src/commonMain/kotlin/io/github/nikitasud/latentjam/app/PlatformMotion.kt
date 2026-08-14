/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** One platform observer for the app root; descendants read the supplied policy without I/O. */
@Composable
internal expect fun rememberPlatformReduceMotion(): Boolean

internal val LocalReduceMotion = staticCompositionLocalOf { false }

/** True when the operating system asks apps to avoid non-essential motion. */
@Composable
internal fun rememberReduceMotion(): Boolean = LocalReduceMotion.current
