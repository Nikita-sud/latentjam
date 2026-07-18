/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
actual fun latentJamColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) NeutralDarkColors else NeutralLightColors
