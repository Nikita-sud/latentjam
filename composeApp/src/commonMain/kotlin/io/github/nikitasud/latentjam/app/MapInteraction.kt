/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed

internal fun mapUsesSidePanel(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 640f && widthDp > heightDp * 1.25f

internal fun mapSidePanelWidth(widthDp: Float, fontScale: Float): Float =
    (340f * fontScale.coerceIn(1f, 1.3f)).coerceAtMost(widthDp * 0.45f)

internal fun mapPortraitPlotHeight(widthDp: Float): Float =
    ((widthDp - 16f) * 0.94f).coerceIn(260f, 500f)

internal enum class MapGestureAction { PASS, TRANSFORM, CONSUME }

/** A gesture has one owner until every finger lifts. Never steal an already-started page scroll. */
internal class MapGestureSession {
    private var mapOwnsGesture = false
    private var pageOwnsGesture = false

    fun update(pressedPointers: Int, consumedMovement: Boolean = false): MapGestureAction {
        if (!mapOwnsGesture && consumedMovement) pageOwnsGesture = true
        if (pageOwnsGesture) return MapGestureAction.PASS
        if (pressedPointers >= 2) mapOwnsGesture = true
        return when {
            !mapOwnsGesture -> MapGestureAction.PASS
            pressedPointers >= 2 -> MapGestureAction.TRANSFORM
            else -> MapGestureAction.CONSUME
        }
    }
}

/** Single-finger drags scroll the page; two fingers own the map, including the final finger-up. */
internal suspend fun PointerInputScope.detectMapTransformGestures(
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val session = MapGestureSession()
    do {
        // Claim a second finger before the parent scroll/tap detectors see it. Waiting for Main
        // would let a vertical pinch start scrolling the page before the map can claim it.
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val action = session.update(event.changes.count { it.pressed })
        when (action) {
            MapGestureAction.TRANSFORM -> {
                // Both fingers must have a previous position: adding/removing a finger must not
                // jump to a new centroid or produce a one-finger pan on the first pinch frame.
                if (event.changes.count { it.pressed && it.previousPressed } >= 2) {
                    onTransform(
                        event.calculateCentroid(useCurrent = false),
                        event.calculatePan(),
                        event.calculateZoom(),
                    )
                }
                event.changes.forEach { it.consume() }
            }
            MapGestureAction.CONSUME -> event.changes.forEach { it.consume() }
            MapGestureAction.PASS -> {
                val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                session.update(
                    finalEvent.changes.count { it.pressed },
                    consumedMovement = finalEvent.changes.any {
                        it.isConsumed && it.positionChangedIgnoreConsumed()
                    },
                )
            }
        }
    } while (event.changes.any { it.pressed })
}
