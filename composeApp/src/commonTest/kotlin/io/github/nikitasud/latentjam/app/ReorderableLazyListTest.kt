/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReorderableLazyListTest {
    @Test fun dropUsesCurrentViewportAfterOriginalRowWasDisposed() {
        val rows = listOf(ReorderableRow(148, -10, 60), ReorderableRow(149, 50, 120), ReorderableRow(150, 170, 60))
        assertEquals(150, reorderDropIndex(195f, rows))
        assertEquals(149, reorderDropIndex(100f, rows))
        assertEquals(148, reorderDropIndex(-100f, rows))
        assertNull(reorderDropIndex(100f, emptyList()))
    }

    @Test fun playlistHeadersAndFooterAreNeverDropTargets() {
        val rows = listOf(
            ReorderableRow(0, -80, 120), ReorderableRow(1, 40, 40), ReorderableRow(2, 80, 64),
            ReorderableRow(3, 144, 64), ReorderableRow(4, 208, 96), ReorderableRow(5, 304, 60),
        )
        val mapped = visibleReorderRows(rows, leadingItems = 3, itemCount = 2, viewportStartOffset = -24)
        assertEquals(listOf(ReorderableRow(0, 168, 64), ReorderableRow(1, 232, 96)), mapped)
        assertEquals(0, reorderDropIndex(-100f, mapped))
        assertEquals(1, reorderDropIndex(900f, mapped))
        assertEquals(emptyList(), visibleReorderRows(rows.take(3), 3, 2, -24))
    }

    @Test fun pagesUseCurrentRowsAfterTheDraggedPageScrolledOutOfView() {
        val rows = visibleReorderRows(listOf(
            ReorderableRow(5, -40, 60), ReorderableRow(6, 20, 120), ReorderableRow(7, 140, 60),
        ), leadingItems = 1, itemCount = 9, viewportStartOffset = 0)
        assertEquals(6, reorderDropIndex(195f, rows))
        assertEquals(5, reorderDropIndex(75f, rows))
        assertEquals(4, reorderDropIndex(-90f, rows))
    }

    @Test fun bothEdgesScrollEvenWithoutFurtherPointerMovement() {
        assertEquals(-720f, reorderEdgeScrollSpeed(0f, 0, 400, 56f, 720f))
        assertEquals(720f, reorderEdgeScrollSpeed(400f, 0, 400, 56f, 720f))
        assertEquals(360f, reorderEdgeScrollSpeed(372f, 0, 400, 56f, 720f))
        assertEquals(0f, reorderEdgeScrollSpeed(200f, 0, 400, 56f, 720f))
        assertEquals(720f, reorderEdgeScrollSpeed(800f, 0, 400, 56f, 720f))
    }

    @Test fun smallAndInvalidViewportsCannotScrollInBothDirections() {
        assertEquals(0f, reorderEdgeScrollSpeed(20f, 0, 40, 56f, 720f))
        assertEquals(0f, reorderEdgeScrollSpeed(Float.NaN, 0, 400, 56f, 720f))
        assertEquals(0f, reorderEdgeScrollSpeed(1f, 0, 0, 56f, 720f))
    }

    @Test fun previewStaysBetweenPlaylistHeadersAndLastRowInAShortList() {
        val rows = listOf(ReorderableRow(0, 240, 60), ReorderableRow(1, 300, 60))
        val bounds = reorderBounds(rows, 600)!!
        assertEquals(ReorderBounds(240f, 360f), bounds)
        assertEquals(240f, bounds.rowTop(-500f, 60))
        assertEquals(300f, bounds.rowTop(900f, 60))
        assertEquals(265f, bounds.rowTop(265f, 60))
        assertEquals(0f, reorderScrollDelta(-50f, rows, 2, 600))
        assertEquals(0f, reorderScrollDelta(50f, rows, 2, 600))
    }

    @Test fun previewStopsAtViewportEdgesWhileThePointerKeepsScrolling() {
        val rows = listOf(ReorderableRow(20, -30, 160), ReorderableRow(21, 130, 160), ReorderableRow(22, 290, 160))
        val bounds = reorderBounds(rows, 400)!!
        assertEquals(ReorderBounds(0f, 400f), bounds)
        assertEquals(0f, bounds.rowTop(-500f, 160))
        assertEquals(240f, bounds.rowTop(900f, 160))
        assertEquals(-36f, reorderScrollDelta(-36f, rows, 60, 400))
        assertEquals(36f, reorderScrollDelta(36f, rows, 60, 400))
    }

    @Test fun scrollStopsExactlyAtFirstAndLastSlotsWithoutRevealingHeadersOrFooter() {
        val rows = listOf(ReorderableRow(0, -15, 240), ReorderableRow(1, 225, 200))
        assertEquals(-15f, reorderScrollDelta(-50f, rows, 2, 400))
        assertEquals(25f, reorderScrollDelta(50f, rows, 2, 400))
    }

    @Test fun oversizedRowsAndEmptyViewportsHaveSafePreviewBounds() {
        val rows = listOf(ReorderableRow(0, 20, 500))
        val bounds = reorderBounds(rows, 300)!!
        assertEquals(ReorderBounds(20f, 300f), bounds)
        assertEquals(20f, bounds.rowTop(900f, 500))
        assertNull(reorderBounds(rows, 0))
        assertNull(reorderBounds(emptyList(), 300))
        assertEquals(0f, reorderScrollDelta(50f, emptyList(), 0, 300))
    }

}
