/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseHeaderSwipeTest {
    private fun target(page: Int, offset: Float, velocity: Float, count: Int = 5) =
        browseHeaderSnapPage(page, offset, count, velocity, flingThreshold = 400f)

    @Test
    fun slowOrCancelledDragSettlesToNearestPageInEitherDirection() {
        for (offset in listOf(-0.49f, -0.1f, 0f, 0.1f, 0.49f)) {
            for (velocity in listOf(-399f, 0f, 399f)) {
                assertEquals(2, target(2, offset, velocity))
            }
        }
    }

    @Test
    fun shortFlickAdvancesButCrossingMidpointDoesNotSkipAnotherPage() {
        assertEquals(3, target(2, 0.1f, 800f))
        assertEquals(3, target(3, -0.4f, 800f))
        assertEquals(1, target(2, -0.1f, -800f))
        assertEquals(1, target(1, 0.4f, -800f))
    }

    @Test
    fun reversingBeforeReleaseReturnsInTheReleaseDirection() {
        assertEquals(2, target(2, 0.4f, -800f))
        assertEquals(2, target(2, -0.4f, 800f))
        assertEquals(3, target(2, 0f, 800f))
        assertEquals(1, target(2, 0f, -800f))
    }

    @Test
    fun edgesAndSinglePageNeverWrapOrLeaveTheVisiblePageRange() {
        assertEquals(0, target(0, 0f, -800f))
        assertEquals(4, target(4, 0f, 800f))
        assertEquals(0, target(0, 0f, 800f, count = 1))
        assertEquals(0, target(0, 0f, -800f, count = 1))
    }

    @Test
    fun destinationUsesTheEnabledPagesInTheirCustomOrder() {
        val pages = listOf(StartPage.STATISTICS, StartPage.MAP, StartPage.TRACKS)
        assertEquals(StartPage.TRACKS, pages[target(1, 0.1f, 800f, pages.size)])
        assertEquals(StartPage.STATISTICS, pages[target(1, -0.1f, -800f, pages.size)])
    }
}
