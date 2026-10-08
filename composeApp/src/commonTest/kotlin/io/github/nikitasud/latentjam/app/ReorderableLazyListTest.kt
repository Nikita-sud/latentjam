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
}
