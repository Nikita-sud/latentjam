/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Resolves the app's color scheme for the current platform.
 *
 * Android 12+ returns Material You dynamic color (wallpaper-derived); older
 * Android and iOS fall back to the brand palette seeded from the LatentJam
 * mark (purple → cyan). Dark theme always follows the system setting.
 */
@Composable
expect fun latentJamColorScheme(darkTheme: Boolean): ColorScheme

/** Brand seeds from branding/logo.svg. */
private val BrandPurple = Color(0xFF8E24AA)
private val BrandCyan = Color(0xFF00ACC1)

internal val BrandLightColors: ColorScheme = lightColorScheme(
    primary = BrandPurple,
    tertiary = BrandCyan,
)

internal val BrandDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFE2B6F0),
    tertiary = Color(0xFF7BD5E4),
)
