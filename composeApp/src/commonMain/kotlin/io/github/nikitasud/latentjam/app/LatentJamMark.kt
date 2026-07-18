/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The LatentJam mark as an icon-sized vector: three nodes in latent space,
 * joined by curved edges, tracing an L.
 *
 * Drawn in Kotlin rather than loaded as an asset so it tints with the rest of
 * the icon set and stays crisp at any size. Geometry matches the brand mark
 * (branding/logo.svg, 100×100 viewport).
 *
 * Used as the SMART-shuffle glyph: the app's signature mode wears the app's
 * own symbol, which distinguishes it from plain shuffle at a glance.
 */
val LatentJamMark: ImageVector by lazy {
    ImageVector.Builder(
        name = "LatentJamMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 100f,
        viewportHeight = 100f,
    ).apply {
        // Ghost "L" behind the graph.
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 6f,
            strokeAlpha = 0.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(30f, 30f)
            verticalLineTo(75f)
            horizontalLineTo(45f)
        }
        // Edges.
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 7f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(30f, 30f)
            curveTo(50f, 30f, 50f, 50f, 70f, 50f)
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 7f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(70f, 50f)
            curveTo(70f, 70f, 60f, 75f, 45f, 75f)
        }
        // Nodes.
        node(centerX = 30f, centerY = 30f)
        node(centerX = 70f, centerY = 50f)
        node(centerX = 45f, centerY = 75f)
    }.build()
}

private fun ImageVector.Builder.node(centerX: Float, centerY: Float, radius: Float = 9f) {
    path(fill = SolidColor(Color.Black)) {
        moveTo(centerX - radius, centerY)
        arcToRelative(radius, radius, 0f, true, false, radius * 2, 0f)
        arcToRelative(radius, radius, 0f, true, false, -radius * 2, 0f)
        close()
    }
}
