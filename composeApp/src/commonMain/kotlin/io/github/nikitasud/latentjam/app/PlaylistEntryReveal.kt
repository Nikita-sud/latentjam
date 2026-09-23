/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.flow.first

/** Lazy item identity, including the hero and any section headers, in the displayed order. */
internal fun playlistEntryTrackIndex(selection: CollectionSelection, trackId: TrackId?): Int? {
    if (trackId == null || !(selection.playlistId != null ||
            selection.routeId.startsWith("auto:"))) return null
    val sections = selection.sections
    if (sections == null) {
        return selection.tracks.indexOfFirst { it.id == trackId }
            .takeIf { it >= 0 }?.plus(1)
    }
    var emitted = 1 // Hero.
    for (section in sections) {
        emitted++ // Section heading.
        val index = section.tracks.indexOfFirst { it.id == trackId }
        if (index >= 0) return emitted + index
        emitted += section.tracks.size
    }
    return null
}

/** The bottom content inset belongs to the floating player, not to the readable viewport. */
internal fun playlistEntryRowVisible(
    offset: Int,
    size: Int,
    viewportStart: Int,
    viewportEnd: Int,
    bottomInset: Int,
): Boolean {
    val readableEnd = viewportEnd - bottomInset
    val readableHeight = readableEnd - viewportStart
    if (size <= 0 || readableHeight <= 0) return false
    // With very large text/short windows, revealing one readable viewport is enough.
    return offset >= viewportStart && offset + minOf(size, readableHeight) <= readableEnd
}

/**
 * One reveal per visit, after both the page and shared artwork settle. Saved completion belongs
 * to the browse shell, so returning from Now Playing never restarts it or follows the next song.
 * The pointer observer does not consume events; even a tap cancels pending/active scrolling.
 */
@Composable
internal fun playlistEntryRevealModifier(
    selection: CollectionSelection,
    currentTrackId: TrackId?,
    listState: LazyListState,
    active: Boolean,
    entrySettled: Boolean,
): Modifier {
    if (selection.playlistId == null && !selection.routeId.startsWith("auto:")) return Modifier

    var handled by rememberSaveable(selection.routeId) { mutableStateOf(false) }
    var touched by rememberSaveable(selection.routeId) { mutableStateOf(false) }
    var running by remember(selection.routeId) { mutableStateOf(false) }
    val latestSelection by rememberUpdatedState(selection)
    val latestTrackId by rememberUpdatedState(currentTrackId)
    val reduceMotion = rememberReduceMotion()

    LaunchedEffect(selection.routeId, active, entrySettled, touched) {
        if (handled) return@LaunchedEffect
        if (!active || touched) {
            handled = true
            return@LaunchedEffect
        }
        if (!entrySettled) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount > 0 }.first { it }
        handled = true
        // A restored position or accessibility/keyboard scroll also takes precedence.
        if (listState.firstVisibleItemIndex != 0 ||
            listState.firstVisibleItemScrollOffset != 0 || listState.isScrollInProgress
        ) return@LaunchedEffect
        val target = playlistEntryTrackIndex(latestSelection, latestTrackId)
            ?: return@LaunchedEffect
        val layout = listState.layoutInfo
        if (target >= layout.totalItemsCount) return@LaunchedEffect
        val row = layout.visibleItemsInfo.firstOrNull { it.index == target }
        if (row != null && playlistEntryRowVisible(
                row.offset, row.size, layout.viewportStartOffset, layout.viewportEndOffset,
                layout.afterContentPadding,
            )) return@LaunchedEffect

        val readableHeight = layout.viewportEndOffset - layout.viewportStartOffset -
            layout.afterContentPadding
        if (readableHeight <= 0) return@LaunchedEffect
        running = true
        try {
            // Leave some preceding context. LazyList handles distant targets without laying
            // out the intervening library, and its scroll mutation remains user-cancellable.
            val offset = -(readableHeight / 5)
            if (reduceMotion) listState.scrollToItem(target, offset)
            else listState.animateScrollToItem(target, offset)
        } finally {
            running = false
        }
    }

    return if (!handled || running) Modifier.pointerInput(selection.routeId) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll ||
                    event.changes.any { it.pressed && !it.previousPressed }
                ) touched = true
            }
        }
    } else Modifier
}
