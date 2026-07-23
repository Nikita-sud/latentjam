/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/** Returns the installed app's user-visible version, or null when it is unavailable. */
@Composable
internal expect fun rememberAppVersion(): String?
