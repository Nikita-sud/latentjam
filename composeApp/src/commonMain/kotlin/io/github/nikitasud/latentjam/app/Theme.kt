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
 * The app's colour scheme.
 *
 * Deliberately neutral: greys and near-black, with no hue in the chrome.
 * Colour in this app belongs to the music — the cover-derived (or
 * latent-space-derived) accent on the mini-player and now-playing screen —
 * and a tinted UI would compete with it. Material You's wallpaper colours are
 * not used for the same reason: they drag an unrelated hue across everything.
 *
 * [BrandCyan] is the single exception, reserved for SMART shuffle.
 */
@Composable
expect fun latentJamColorScheme(darkTheme: Boolean): ColorScheme

/** Reserved for the SMART affordance — the one deliberate spot of brand colour. */
internal val BrandCyan = Color(0xFF7BD5E4)

internal val NeutralDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFE6E6E6),
    onPrimary = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF2E2E2E),
    onPrimaryContainer = Color(0xFFEDEDED),
    secondary = Color(0xFFC9C9C9),
    onSecondary = Color(0xFF1F1F1F),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFE0E0E0),
    tertiary = BrandCyan,
    onTertiary = Color(0xFF00363E),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0C0C0C),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFAEAEAE),
    surfaceContainerLowest = Color(0xFF070707),
    surfaceContainerLow = Color(0xFF131313),
    surfaceContainer = Color(0xFF171717),
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = Color(0xFF282828),
    outline = Color(0xFF585858),
    outlineVariant = Color(0xFF3A3A3A),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1A1A1A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

internal val NeutralLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1F1F1F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E2E2),
    onPrimaryContainer = Color(0xFF141414),
    secondary = Color(0xFF3D3D3D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF1B1B1B),
    tertiary = Color(0xFF00697A),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF141414),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF141414),
    surfaceVariant = Color(0xFFE4E4E4),
    onSurfaceVariant = Color(0xFF5A5A5A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE4E4E4),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFCFCFCF),
    inverseSurface = Color(0xFF2A2A2A),
    inverseOnSurface = Color(0xFFF2F2F2),
)
