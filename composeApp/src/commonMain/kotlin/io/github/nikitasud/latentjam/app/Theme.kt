/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/**
 * The app's colour scheme.
 *
 * Neutral chrome leaves colour to the music: cover-derived (or latent-space-derived) player
 * surfaces and browse backdrops share the current song's hue. Material You's wallpaper colours are
 * not used for the same reason: they drag an unrelated hue across everything.
 *
 * [BrandCyan] is the single exception, reserved for SMART shuffle.
 */
@Composable
expect fun latentJamColorScheme(darkTheme: Boolean): ColorScheme

/** Shared readable secondary text; cached once and still scaled by the system text setting. */
internal val LatentJamTypography: Typography = Typography().let { defaults ->
    defaults.copy(
        bodySmall = defaults.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = defaults.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
    )
}

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
    background = Color(0xFF0B0B0B),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF0B0B0B),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceContainerLowest = Color(0xFF070707),
    surfaceContainerLow = Color(0xFF131313),
    surfaceContainer = Color(0xFF151515),
    surfaceContainerHigh = Color(0xFF1E1E1E),
    surfaceContainerHighest = Color(0xFF282828),
    outline = Color(0xFF585858),
    outlineVariant = Color(0xFF2E2E2E),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1A1A1A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * The dark scheme on a true black floor for OLED screens: every container steps one shade darker
 * so sheets and cards still separate from the floor.
 */
internal fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF202020),
    surfaceVariant = Color(0xFF222222),
    outlineVariant = Color(0xFF262626),
)

internal val NeutralLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1F1F1F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF141414),
    secondary = Color(0xFF3D3D3D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF1B1B1B),
    tertiary = Color(0xFF00697A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDF3F6),
    onTertiaryContainer = Color(0xFF00363E),
    // Paper on a desk: the page is a soft cool grey and everything laid on it (cards, sheets,
    // the queue, the mini player) is white, so it reads as raised without a border or shadow.
    // Grey cards on a near-white page read the other way round, as dull dents.
    background = Color(0xFFF1F1F4),
    onBackground = Color(0xFF141414),
    surface = Color(0xFFF1F1F4),
    onSurface = Color(0xFF141414),
    surfaceVariant = Color(0xFFE3E3E8),
    // Pastel channels stay >=.656 through cloud overlaps. Even a further 10% black current-row
    // wash keeps >4.5:1 with this ink; neutral cards and ordinary cloud text have more contrast.
    onSurfaceVariant = Color(0xFF2E2E2E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    // The one container darker than the page: placeholders, tracks and other recessed wells.
    surfaceContainerHighest = Color(0xFFE6E6EB),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFDCDCE2),
    inverseSurface = Color(0xFF2A2A2A),
    inverseOnSurface = Color(0xFFF2F2F2),
    inversePrimary = Color(0xFFE6E6E6),
    // Elevated Material surfaces must keep this neutral palette, rather than inheriting the
    // default purple tint from lightColorScheme's unspecified roles.
    surfaceTint = Color(0xFF1F1F1F),
)
