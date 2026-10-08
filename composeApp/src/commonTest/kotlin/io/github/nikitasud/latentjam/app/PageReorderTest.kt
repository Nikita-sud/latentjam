/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageReorderTest {
    private val layout = PageLayout()

    @Test fun pageMovesToADestinationThatBecameVisibleAfterScrolling() {
        val moved = layout.reorderPageAfterDrop(layout.order, 0, layout.order.lastIndex)
        assertEquals(layout.order.first(), moved.order.last())
        assertEquals(layout.order.drop(1), moved.order.dropLast(1))
        assertEquals(layout, moved.reorderPageAfterDrop(moved.order, moved.order.lastIndex, 0))
    }

    @Test fun changedOrderCancelsAnOldDropWithoutOverwritingIt() {
        val newer = layout.movePage(StartPage.FOLDERS, Int.MIN_VALUE)
        assertEquals(newer, newer.reorderPageAfterDrop(layout.order, 1, 5))
    }

    @Test fun visibilityChangesDuringTheDragSurviveTheMove() {
        val newer = layout.withPageEnabled(StartPage.MAP, true)
        val moved = newer.reorderPageAfterDrop(layout.order, layout.order.indexOf(StartPage.MAP), 5)
        assertEquals(newer.hiddenPages, moved.hiddenPages)
        assertTrue(StartPage.MAP in moved.visiblePages)
        assertEquals(5, moved.order.indexOf(StartPage.MAP))
    }

    @Test fun invalidOrUnchangedDestinationsDoNotMoveAPage() {
        assertEquals(layout, layout.reorderPageAfterDrop(layout.order, -1, 2))
        assertEquals(layout, layout.reorderPageAfterDrop(layout.order, 2, layout.order.size))
        assertEquals(layout, layout.reorderPageAfterDrop(layout.order, 2, 2))
    }
}
