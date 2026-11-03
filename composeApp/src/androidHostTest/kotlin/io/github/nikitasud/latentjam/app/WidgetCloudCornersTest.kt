/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** The cloud bitmap's own corners: round after the fitXY stretch, without the root's outline clip. */
class WidgetCloudCornersTest {
    private fun stretched(bitmapWidth: Int, bitmapHeight: Int, widthDp: Int, heightDp: Int): Pair<Float, Float> {
        val (rx, ry) = widgetCloudCornerRadii(bitmapWidth, bitmapHeight, widthDp, heightDp)
        return rx * widthDp / bitmapWidth to ry * heightDp / bitmapHeight
    }

    @Test
    fun cornersBecomeTheCardsRadiusOnTheWidget() {
        for ((w, h) in listOf(280 to 72, 360 to 96, 412 to 110)) {
            val (x, y) = stretched(320, 72, w, h)
            assertEquals(WIDGET_CORNER_DP, x, 0.01f, "pulse ${w}x$h")
            assertEquals(WIDGET_CORNER_DP, y, 0.01f, "pulse ${w}x$h")
        }
        val (x, y) = stretched(320, 170, 300, 170)
        assertEquals(WIDGET_CORNER_DP, x, 0.01f)
        assertEquals(WIDGET_CORNER_DP, y, 0.01f)
    }

    @Test
    fun aTinyWidgetNeverGetsMoreThanHalfItsSide() {
        val (rx, ry) = widgetCloudCornerRadii(320, 72, 30, 20)
        assertEquals(160f, rx)
        assertEquals(36f, ry)
    }
}
