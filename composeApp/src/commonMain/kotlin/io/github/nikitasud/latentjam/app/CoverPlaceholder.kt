/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The cover of a track, album or playlist that has none: one quiet tile in the theme's colours
 * under LatentJam's waveform mark, the same for every item. A colour per item would look like it
 * meant something, and it would not.
 *
 * Drawn on a canvas rather than as a vector icon: an icon renders through its own offscreen
 * bitmap, which cost every scrolled row an extra texture (#18).
 */
@Composable
internal fun CoverPlaceholder(modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val mark = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MARK_ALPHA)
    Canvas(modifier) { drawCoverPlaceholder(surface, mark) }
}

/**
 * Five rounded bars, tallest in the middle, centred on the tile; proportions of its short side.
 * The widget draws the same mark into its bitmap ([WAVEFORM_HEIGHTS], [WAVEFORM_SPACING],
 * [WAVEFORM_STROKE]).
 */
private fun DrawScope.drawCoverPlaceholder(surface: Color, mark: Color) {
    drawRect(surface)
    val side = minOf(size.width, size.height)
    WAVEFORM_HEIGHTS.forEachIndexed { index, height ->
        val x = center.x + (index - 2) * side * WAVEFORM_SPACING
        val half = side * height / 2f
        drawLine(mark, Offset(x, center.y - half), Offset(x, center.y + half), side * WAVEFORM_STROKE, StrokeCap.Round)
    }
}

internal val WAVEFORM_HEIGHTS = floatArrayOf(0.20f, 0.40f, 0.62f, 0.40f, 0.20f)
internal const val WAVEFORM_SPACING = 0.13f
internal const val WAVEFORM_STROKE = 0.055f

/** Quiet enough to read as "no cover" rather than as a picture of five bars. */
private const val MARK_ALPHA = 0.45f
