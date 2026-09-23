/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.math.abs

/** Measured viewport geometry captured when the listener starts dragging a page. */
internal data class PageReorderItem(val page: StartPage, val offset: Int, val size: Int) {
    val center: Float get() = offset.toFloat() + size / 2f
}

/**
 * Snap to the closest visible page slot. Text can make adjacent rows different heights, so pixel
 * travel must not be divided by the dragged row's own height. Identity and the starting order also
 * protect a delayed drop from overwriting a newer order; current visibility preferences survive.
 */
internal fun PageLayout.reorderPageAfterDrag(
    page: StartPage,
    startingOrder: List<StartPage>,
    visibleRows: List<PageReorderItem>,
    distancePx: Float,
): PageLayout {
    val current = normalized()
    if (current.order != startingOrder || !distancePx.isFinite() || distancePx == 0f) return this
    val rows = visibleRows.filter { it.size > 0 && it.page in current.order }
    val origin = rows.firstOrNull { it.page == page } ?: return this
    val center = origin.center + distancePx
    if (!center.isFinite()) return this
    val target = rows.minWithOrNull(
        compareBy<PageReorderItem> { abs(it.center - center) }
            .thenBy { if (distancePx < 0f) it.center else -it.center },
    ) ?: return this
    return current.movePage(page, current.order.indexOf(target.page) - current.order.indexOf(page))
}
