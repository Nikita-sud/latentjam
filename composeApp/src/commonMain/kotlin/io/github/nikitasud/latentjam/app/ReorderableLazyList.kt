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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
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

/** The intersection of the reorderable rows and the unobscured list viewport. */
internal data class ReorderBounds(val top: Float, val bottom: Float) {
    fun rowTop(requested: Float, height: Int): Float =
        requested.coerceIn(top, maxOf(top, bottom - height))
}

internal fun reorderBounds(rows: List<ReorderableRow>, viewportBottom: Int): ReorderBounds? {
    val first = rows.firstOrNull() ?: return null
    val last = rows.last()
    val top = maxOf(0f, first.top.toFloat())
    val bottom = minOf(viewportBottom.toFloat(), (last.top + last.height).toFloat())
    return if (bottom > top) ReorderBounds(top, bottom) else null
}

/** Do not scroll the first/last slot away just because a header/footer can still scroll. */
internal fun reorderScrollDelta(
    requested: Float, rows: List<ReorderableRow>, itemCount: Int, viewportBottom: Int,
): Float {
    if (rows.isEmpty()) return 0f
    val first = rows.first()
    val last = rows.last()
    return when {
        requested < 0f && first.index == 0 -> requested.coerceAtLeast(minOf(0f, first.top.toFloat()))
        requested > 0f && last.index == itemCount - 1 ->
            requested.coerceAtMost(maxOf(0f, (last.top + last.height - viewportBottom).toFloat()))
        else -> requested
    }
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
    fun viewportBottom(): Int {
        val layout = listState.layoutInfo
        // Bottom padding reserves space for the floating player/navigation controls.
        return layout.viewportEndOffset - layout.viewportStartOffset - layout.afterContentPadding
    }
    LaunchedEffect(drag?.from, identity, canReorder, itemCount, leadingItems) {
        if (drag == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag != null) {
            val now = withFrameNanos { it }
            val elapsed = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previous = now
            val active = drag ?: break
            val bottom = viewportBottom()
            val speed = reorderEdgeScrollSpeed(active.pointerY, 0, bottom, edge, maximum)
            val delta = reorderScrollDelta(speed * elapsed, visibleRows(), itemCount, bottom)
            if (delta != 0f) listState.scrollBy(delta)
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
                        val rows = visibleRows()
                        val bounds = reorderBounds(rows, viewportBottom()) ?: break
                        val top = bounds.rowTop(active.top, active.height)
                        val target = reorderDropIndex(top + active.height / 2f, rows)
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
            val bounds = reorderBounds(visibleRows(), viewportBottom())
            if (bounds != null) {
                // Clamp the preview, not the pointer: holding beyond an edge still scrolls.
                // Clip unusually tall rows too, so they cannot cover headers or player controls.
                Box(Modifier.matchParentSize().drawWithContent {
                    clipRect(top = bounds.top, bottom = bounds.bottom) { this@drawWithContent.drawContent() }
                }) {
                    draggedItem(active.from, Modifier.fillMaxWidth().graphicsLayer {
                        translationY = bounds.rowTop(active.top, active.height)
                    })
                }
            }
        }
    }
}
