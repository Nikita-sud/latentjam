/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueReorderListTest {
    @Test fun dropUsesCurrentViewportAfterOriginalRowWasDisposed() {
        val rows = listOf(QueueDragRow(148, -10, 60), QueueDragRow(149, 50, 120), QueueDragRow(150, 170, 60))
        assertEquals(150, queueDropIndex(195f, rows))
        assertEquals(149, queueDropIndex(100f, rows))
        assertEquals(148, queueDropIndex(-100f, rows))
        assertNull(queueDropIndex(100f, emptyList()))
    }

    @Test fun bothEdgesScrollEvenWithoutFurtherPointerMovement() {
        assertEquals(-720f, queueEdgeScrollSpeed(0f, 0, 400, 56f, 720f))
        assertEquals(720f, queueEdgeScrollSpeed(400f, 0, 400, 56f, 720f))
        assertEquals(360f, queueEdgeScrollSpeed(372f, 0, 400, 56f, 720f))
        assertEquals(0f, queueEdgeScrollSpeed(200f, 0, 400, 56f, 720f))
        assertEquals(720f, queueEdgeScrollSpeed(800f, 0, 400, 56f, 720f))
    }

    @Test fun smallAndInvalidViewportsCannotScrollInBothDirections() {
        assertEquals(0f, queueEdgeScrollSpeed(20f, 0, 40, 56f, 720f))
        assertEquals(0f, queueEdgeScrollSpeed(Float.NaN, 0, 400, 56f, 720f))
        assertEquals(0f, queueEdgeScrollSpeed(1f, 0, 0, 56f, 720f))
    }
}
