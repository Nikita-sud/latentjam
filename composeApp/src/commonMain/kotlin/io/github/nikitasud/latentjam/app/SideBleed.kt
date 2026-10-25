/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.constrainWidth

/**
 * How far the side safe areas reach in from the window's left and right edges, in pixels.
 *
 * The app keeps its content out of them once, at the root (see App), so everything inside is laid
 * out that much narrower than the window. Backdrops need not be: a page's wash or the player's
 * cloud stopping at a foldable iPhone's status column, or at a landscape camera, leaves a pale
 * strip that reads as the app cut short. A backdrop runs on under them with this, the way the
 * floors already run under the status and home-indicator bars.
 */
@Immutable
internal data class SideBleed(val left: Int, val right: Int) {
    val none: Boolean get() = left == 0 && right == 0

    /** The window's full width around a [size] laid out inside the side safe areas. */
    fun window(size: Size): Rect = Rect(Offset(-left.toFloat(), 0f), Size(size.width + left + right, size.height))
}

@Composable
internal fun rememberSideBleed(): SideBleed {
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return SideBleed(insets.getLeft(density, direction), insets.getRight(density, direction))
}

/** Lays this element out [bleed] wider on each side, under the side safe areas. */
internal fun Modifier.underSides(bleed: SideBleed): Modifier = if (bleed.none) this else layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = bleed.left + bleed.right
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(constraints.constrainWidth(placeable.width - extra), placeable.height) {
        placeable.place(-bleed.left, 0)
    }
}

/** Inside an element laid out [underSides], keeps its content back out of the side safe areas. */
@Composable
internal fun Modifier.clearOfSides(bleed: SideBleed): Modifier {
    if (bleed.none) return this
    val density = LocalDensity.current
    return with(density) { absolutePadding(left = bleed.left.toDp(), right = bleed.right.toDp()) }
}
