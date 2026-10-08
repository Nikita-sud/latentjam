/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal data class ListDrag(val from: Int, val top: Float, val height: Int, val pointerY: Float)
internal data class ReorderableRow(val index: Int, val top: Int, val height: Int)

internal fun reorderDropIndex(centerY: Float, rows: List<ReorderableRow>): Int? =
    rows.minByOrNull { abs(centerY - (it.top + it.height / 2f)) }?.index

/** Ignore headers/footers and convert lazy-layout coordinates to the container's coordinates. */
internal fun visibleReorderRows(
    rows: List<ReorderableRow>, leadingItems: Int, itemCount: Int, viewportStartOffset: Int,
): List<ReorderableRow> = rows.mapNotNull { row ->
    val index = row.index - leadingItems
    if (index !in 0 until itemCount || row.height <= 0) null
    else row.copy(index = index, top = row.top - viewportStartOffset)
}

/** Pixels per second, independent of refresh rate. Holding still at an edge keeps scrolling. */
internal fun reorderEdgeScrollSpeed(y: Float, start: Int, end: Int, edge: Float, maximum: Float): Float {
    if (end <= start || edge <= 0f || !y.isFinite()) return 0f
    val band = minOf(edge, (end - start) / 2f)
    return when {
        y < start + band -> -maximum * ((start + band - y) / band).coerceIn(0f, 1f)
        y > end - band -> maximum * ((y - end + band) / band).coerceIn(0f, 1f)
        else -> 0f
    }
}

/** The gesture and floating row outlive lazy-row disposal; the collection changes only on drop. */
@Composable
internal fun ReorderableLazyList(
    identity: Any,
    canReorder: Boolean,
    listState: LazyListState,
    itemCount: Int,
    leadingItems: Int = 0,
    onMove: (Int, Int) -> Unit,
    draggedItem: @Composable (Int, Modifier) -> Unit,
    content: @Composable (Int?) -> Unit,
) {
    var drag by remember(identity, canReorder, itemCount, leadingItems) { mutableStateOf<ListDrag?>(null) }
    val latestMove by rememberUpdatedState(onMove)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val maximum = with(density) { 720.dp.toPx() }
    fun visibleRows(): List<ReorderableRow> {
        val layout = listState.layoutInfo
        return visibleReorderRows(
            layout.visibleItemsInfo.map { ReorderableRow(it.index, it.offset, it.size) },
            leadingItems, itemCount, layout.viewportStartOffset,
        )
    }
    LaunchedEffect(drag?.from, identity, canReorder, itemCount, leadingItems) {
        if (drag == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag != null) {
            val now = withFrameNanos { it }
            val elapsed = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previous = now
            val active = drag ?: break
            val layout = listState.layoutInfo
            // Bottom padding reserves space for the floating player/navigation controls.
            val bottom = layout.viewportEndOffset - layout.viewportStartOffset - layout.afterContentPadding
            val speed = reorderEdgeScrollSpeed(active.pointerY, 0, bottom, edge, maximum)
            if (speed != 0f) listState.scrollBy(speed * elapsed)
        }
    }
    Box(Modifier.fillMaxWidth().pointerInput(identity, canReorder, itemCount, leadingItems) {
        if (!canReorder) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val row = visibleRows().firstOrNull {
                held.position.y >= it.top && held.position.y < it.top + it.height
            } ?: return@awaitEachGesture
            drag = ListDrag(row.index, row.top.toFloat(), row.height, held.position.y)
            haptics.play(PlayerHaptic.HOLD)
            try {
                while (true) {
                    // After the hold, claim movement before LazyColumn or the sheet consumes it.
                    // Until then normal list scrolling, swipes and taps retain their own gestures.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == held.id } ?: break
                    if (event.changes.count { it.pressed } > 1 || change.isConsumed) break
                    if (!change.pressed) {
                        change.consume()
                        val active = drag ?: break
                        val target = reorderDropIndex(active.top + active.height / 2f,
                            visibleRows())
                        if (target != null && target != active.from) {
                            latestMove(active.from, target)
                            haptics.play(PlayerHaptic.SUCCESS)
                        }
                        break
                    }
                    val delta = change.positionChange().y
                    change.consume()
                    drag = drag?.let { it.copy(top = it.top + delta, pointerY = change.position.y) }
                }
            } finally { drag = null }
        }
    }) {
        content(drag?.from)
        drag?.let { active ->
            draggedItem(active.from, Modifier.fillMaxWidth().graphicsLayer { translationY = active.top })
        }
    }
}
