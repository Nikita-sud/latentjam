/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Shares the content's scroll state, without making the label strip a second scrolling list. */
@Composable
internal fun Modifier.browseHeaderSwipe(pagerState: PagerState, enabled: Boolean): Modifier {
    val flingThreshold = with(LocalDensity.current) { 400.dp.toPx() }
    val snapLayout = remember(pagerState, flingThreshold) {
        object : SnapLayoutInfoProvider {
            // Settle directly from the release position. A free decay could skip several tabs.
            override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f

            override fun calculateSnapOffset(velocity: Float): Float {
                val target = browseHeaderSnapPage(
                    currentPage = pagerState.currentPage,
                    offsetFraction = pagerState.currentPageOffsetFraction,
                    pageCount = pagerState.pageCount,
                    velocity = velocity,
                    flingThreshold = flingThreshold,
                )
                return (target - pagerState.currentPage - pagerState.currentPageOffsetFraction) *
                    (pagerState.layoutInfo.pageSize + pagerState.layoutInfo.pageSpacing)
            }
        }
    }
    return scrollable(
        state = pagerState,
        orientation = Orientation.Horizontal,
        enabled = enabled,
        reverseDirection = LocalLayoutDirection.current == LayoutDirection.Ltr,
        // PagerDefaults depends on pointer history collected inside HorizontalPager. Header
        // gestures never reach it, so use the actual page offset and release velocity instead.
        flingBehavior = rememberSnapFlingBehavior(snapLayout),
    )
}

/** Velocity and offset use logical page order; scrollable handles the physical RTL direction. */
internal fun browseHeaderSnapPage(
    currentPage: Int,
    offsetFraction: Float,
    pageCount: Int,
    velocity: Float,
    flingThreshold: Float,
): Int {
    val step = when {
        velocity >= flingThreshold && offsetFraction >= 0f -> 1
        velocity <= -flingThreshold && offsetFraction <= 0f -> -1
        else -> 0 // The pager's currentPage is already the nearest page after a slow drag.
    }
    return (currentPage + step).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
}
