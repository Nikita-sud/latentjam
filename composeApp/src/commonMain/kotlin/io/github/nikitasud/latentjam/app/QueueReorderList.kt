/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal data class QueueDrag(val from: Int, val top: Float, val height: Int, val pointerY: Float)
internal data class QueueDragRow(val index: Int, val top: Int, val height: Int)

internal fun queueDropIndex(centerY: Float, rows: List<QueueDragRow>): Int? =
    rows.minByOrNull { abs(centerY - (it.top + it.height / 2f)) }?.index

/** Pixels per second, independent of refresh rate. Holding still at an edge keeps scrolling. */
internal fun queueEdgeScrollSpeed(y: Float, start: Int, end: Int, edge: Float, maximum: Float): Float {
    if (end <= start || edge <= 0f || !y.isFinite()) return 0f
    val band = minOf(edge, (end - start) / 2f)
    return when {
        y < start + band -> -maximum * ((start + band - y) / band).coerceIn(0f, 1f)
        y > end - band -> maximum * ((y - end + band) / band).coerceIn(0f, 1f)
        else -> 0f
    }
}

/** The gesture and floating row outlive lazy-row disposal; the queue changes only on drop. */
@Composable
internal fun QueueReorderList(
    identity: Any,
    canReorder: Boolean,
    listState: LazyListState,
    onMove: (Int, Int) -> Unit,
    draggedItem: @Composable (Int, Modifier) -> Unit,
    content: @Composable (Int?) -> Unit,
) {
    var drag by remember(identity, canReorder) { mutableStateOf<QueueDrag?>(null) }
    val latestMove by rememberUpdatedState(onMove)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val maximum = with(density) { 720.dp.toPx() }
    LaunchedEffect(drag?.from, identity, canReorder) {
        if (drag == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag != null) {
            val now = withFrameNanos { it }
            val elapsed = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previous = now
            val active = drag ?: break
            val layout = listState.layoutInfo
            val speed = queueEdgeScrollSpeed(active.pointerY, layout.viewportStartOffset, layout.viewportEndOffset, edge, maximum)
            if (speed != 0f) listState.scrollBy(speed * elapsed)
        }
    }
    Box(Modifier.fillMaxWidth().pointerInput(identity, canReorder) {
        if (!canReorder) return@pointerInput
        detectDragGesturesAfterLongPress(
            onDragStart = { position ->
                val row = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                    position.y >= it.offset && position.y < it.offset + it.size
                }
                if (row != null) {
                    drag = QueueDrag(row.index, row.offset.toFloat(), row.size, position.y)
                    haptics.play(PlayerHaptic.HOLD)
                }
            },
            onDrag = { change, delta ->
                drag?.let { active ->
                    change.consume()
                    drag = active.copy(top = active.top + delta.y, pointerY = active.pointerY + delta.y)
                }
            },
            onDragEnd = {
                val active = drag
                drag = null
                if (active != null) {
                    val target = queueDropIndex(active.top + active.height / 2f,
                        listState.layoutInfo.visibleItemsInfo.map { QueueDragRow(it.index, it.offset, it.size) })
                    if (target != null && target != active.from) {
                        latestMove(active.from, target)
                        haptics.play(PlayerHaptic.SUCCESS)
                    }
                }
            },
            onDragCancel = { drag = null },
        )
    }) {
        content(drag?.from)
        drag?.let { active ->
            draggedItem(active.from, Modifier.fillMaxWidth().graphicsLayer { translationY = active.top })
        }
    }
}
