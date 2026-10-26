/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** A quiet, stationary echo of the player. Browsing never starts a frame ticker. */
@Composable
internal fun Modifier.browseCloud(accent: TrackAccent?): Modifier {
    val read: (() -> TrackAccent)? = accent?.let { fixed -> { fixed } }
    return browseCloud(read)
}

/**
 * [accent] is read while drawing, so the accent's cross-fade between tracks redraws the cloud
 * without recomposing the screen that wears it.
 */
@Composable
internal fun Modifier.browseCloud(accent: (() -> TrackAccent)?): Modifier {
    val strength = LocalAppearance.current.cloud.alphaScale
    if (accent == null || strength == 0f) return this
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    // The wash runs on under the side safe areas, which the page's content keeps out of.
    val bleed = rememberSideBleed()
    return drawWithCache {
        val vivid = accent().vivid
        val color = if (dark) {
            liftForDarkCloud(vivid, (0.30f * strength).coerceAtMost(1f), colors.surface, colors.onSurface)
        } else {
            tintForLightSurface(vivid)
        }
        val companionColor = if (dark) {
            val companionAlpha = (0.20f * strength).coerceAtMost(1f)
            liftForDarkCloud(lerp(color, Color.White, 0.16f), companionAlpha, colors.surface, colors.onSurface)
        } else {
            rotateHue(color, 12f)
        }
        val window = bleed.window(size)
        // Keep the song recognisable across the light header. Radial clouds alone fade nearly to
        // the floor between their centres; this wash dissolves into it. A glow, not a band: at
        // full strength and twice the depth it read as a block of colour painted over the list.
        val headerWash = if (dark) null else Brush.verticalGradient(
            0f to color.copy(alpha = (0.45f * strength).coerceAtMost(1f)),
            0.4f to color.copy(alpha = (0.45f * strength).coerceAtMost(1f)),
            1f to color.copy(alpha = 0f),
            endY = 300.dp.toPx(),
        )
        // Two cached gradients carry the current song's hue through the shared header.
        // No blur layer, bitmap, or animation clock is needed while browsing.
        val primary = Brush.radialGradient(
            colors = listOf(color.copy(alpha = ((if (dark) 0.30f else 0.50f) * strength).coerceAtMost(1f)), Color.Transparent),
            center = Offset(window.left + 100.dp.toPx(), 50.dp.toPx()),
            radius = 220.dp.toPx(),
        )
        val companion = Brush.radialGradient(
            colors = listOf(
                companionColor.copy(alpha = ((if (dark) 0.20f else 0.28f) * strength).coerceAtMost(1f)),
                Color.Transparent,
            ),
            center = Offset(window.right - 60.dp.toPx(), 0f),
            radius = 190.dp.toPx(),
        )
        onDrawBehind {
            headerWash?.let { drawRect(it, topLeft = window.topLeft, size = window.size) }
            drawRect(primary, topLeft = window.topLeft, size = window.size)
            drawRect(companion, topLeft = window.topLeft, size = window.size)
        }
    }
}

/** The floating player has a quiet floor; list rows disappear before the home indicator. */
@Composable
internal fun Modifier.playbackDockFade(): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    return drawWithCache {
        val fadeEnd = (24.dp.toPx() / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val brush = Brush.verticalGradient(
            0f to surface.copy(alpha = 0f),
            fadeEnd to surface,
            1f to surface,
        )
        onDrawBehind { drawRect(brush) }
    }
}

/** Tonal ink remains readable across artwork hues and both appearance modes. */
@Composable
internal fun browseAccentInk(accent: TrackAccent): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        lerp(accent.vivid, Color.White, 0.7f)
    } else {
        lerp(accent.vivid, Color.Black, 0.7f)
    }
