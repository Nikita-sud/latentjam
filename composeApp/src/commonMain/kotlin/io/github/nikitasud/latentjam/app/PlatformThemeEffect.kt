/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable

/** Keeps platform-owned chrome in sync with the palette selected inside Compose. */
@Composable
internal expect fun PlatformThemeEffect(darkTheme: Boolean)
