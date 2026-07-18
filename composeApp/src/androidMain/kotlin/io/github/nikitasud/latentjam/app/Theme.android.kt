/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Material You's wallpaper palette is deliberately NOT used: it tints the
 * whole app with a hue borrowed from the home screen, which both fights the
 * cover-derived accents and contradicts the neutral look this app wants.
 */
@Composable
actual fun latentJamColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) NeutralDarkColors else NeutralLightColors
