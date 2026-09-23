/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fades scrolling content into its actual backdrop, including the artwork cloud.
 *
 * Only the small top strip uses an offscreen layer. The rest draws directly; the clipped replay
 * reuses the same content/render nodes without another composition, layout or artwork request.
 * Read [enabled] while drawing so scroll offsets never recompose the viewport for this effect.
 */
internal fun Modifier.fadingListTop(
    height: Dp = 10.dp,
    enabled: () -> Boolean,
): Modifier = drawWithCache {
    val fadeHeight = height.toPx().coerceIn(0f, size.height)
    val stripBounds = Rect(0f, 0f, size.width, fadeHeight)
    val layerPaint = Paint()
    val mask = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color.Black),
        startY = 0f,
        endY = fadeHeight.coerceAtLeast(1f),
    )
    onDrawWithContent {
        if (!enabled() || fadeHeight <= 0f) {
            drawContent()
        } else {
            clipRect(top = fadeHeight) { this@onDrawWithContent.drawContent() }
            // Clip BEFORE saving the layer: the render target must be strip-sized, even on a
            // backend that treats saveLayer bounds as a hint. Never buffer the entire list.
            clipRect(bottom = fadeHeight) {
                val canvas = drawContext.canvas
                canvas.saveLayer(stripBounds, layerPaint)
                try {
                    this@onDrawWithContent.drawContent()
                    drawRect(
                        brush = mask,
                        size = Size(size.width, fadeHeight),
                        blendMode = BlendMode.DstIn,
                    )
                } finally {
                    canvas.restore()
                }
            }
        }
    }
}

/** Shared page-list treatment. No mask at rest, no extra animated state, and no extra scroll owner. */
@Composable
internal fun FadingLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.fadingListTop { state.canScrollBackward },
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}
