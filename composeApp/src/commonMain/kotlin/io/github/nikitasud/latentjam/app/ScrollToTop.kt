/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.cd_scroll_to_top
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Fast travel back to the top of a long list.
 *
 * The teleport-to-now-playing answers "where am I listening"; this answers "show me the
 * beginning" — both are legitimate places to want, so the second gets a small button instead
 * of costing the first. It appears only once the list is genuinely scrolled (past
 * [SCROLL_TO_TOP_THRESHOLD] rows), sits bottom-start where neither the alphabet rail (end)
 * nor the mini player (center) lives, and long distances snap most of the way before the
 * final animated settle so a thousand-row list does not scroll for seconds.
 */
@Composable
internal fun BoxScope.ScrollToTopButton(
    listState: LazyListState,
    bottomInset: Dp = 0.dp,
) {
    val visible by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > SCROLL_TO_TOP_THRESHOLD }
    }
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 16.dp, bottom = bottomInset + 16.dp),
        enter = if (reduceMotion) {
            fadeIn(tween(Motion.REDUCED_MS))
        } else {
            fadeIn(tween(Motion.APPEAR_MS)) + scaleIn(tween(Motion.APPEAR_MS), initialScale = 0.8f)
        },
        exit = if (reduceMotion) {
            fadeOut(tween(Motion.REDUCED_MS))
        } else {
            fadeOut(tween(Motion.REPLACE_MS)) + scaleOut(tween(Motion.REPLACE_MS), targetScale = 0.8f)
        },
    ) {
        SmallFloatingActionButton(
            onClick = {
                scope.launch {
                    if (reduceMotion) {
                        listState.scrollToItem(0)
                    } else {
                        if (listState.firstVisibleItemIndex > SCROLL_TO_TOP_SNAP_FROM) {
                            listState.scrollToItem(SCROLL_TO_TOP_SNAP_FROM)
                        }
                        listState.animateScrollToItem(0)
                    }
                }
            },
            // A circle in inverse surface, like the play/shuffle circles: the button floats
            // over artwork tiles that are grey rounded squares of the same size, so any
            // surface-toned square camouflages — verified on device. Shape plus inversion is
            // what the neutral palette has instead of an accent.
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Icon(
                imageVector = Icons.Rounded.VerticalAlignTop,
                contentDescription = stringResource(Res.string.cd_scroll_to_top),
            )
        }
    }
}

/** Rows out of view before offering the shortcut; less is noise on a barely scrolled list. */
private const val SCROLL_TO_TOP_THRESHOLD = 12

/** Beyond this the return snaps close first, then settles — animation, not a journey. */
private const val SCROLL_TO_TOP_SNAP_FROM = 30

/**
 * What a sort control calls with the listener's new order, so the list then starts at its top.
 *
 * A lazy list follows its first visible item's key through a re-sort, so without this the row
 * the listener was looking at stays on screen and the new order looks as if nothing happened.
 * [shown] is the order the list displays right now: the request waits until it is the chosen
 * one, because a scroll made while the old order is still on screen is undone by that same key
 * following once the new order arrives (the Albums tab may derive its order off the main thread).
 *
 * [requestScrollToTop] must be a `requestScrollToItem(0)`: it runs as a side effect of the very
 * composition that first shows the new order, and so reaches the list's next measure, the one
 * that lays that order out, which then starts at the top instead of following the old first
 * row. A suspending `scrollToItem` launched from an effect would land a frame or more later,
 * after one frame of the new order scrolled to wherever the old top row went.
 *
 * Only a listener's choice scrolls; a restored setting, a rescan or a return to the tab keeps
 * the position.
 */
@Composable
internal fun <T : Any> rememberScrollToTopOnSort(
    shown: T?,
    requestScrollToTop: () -> Unit,
): (T) -> Unit {
    val scroll = remember { PendingSortScroll<T>() }
    val currentRequest by rememberUpdatedState(requestScrollToTop)
    // Read in composition so the arrival of the chosen order recomposes this scope.
    val pending = scroll.pending
    SideEffect {
        if (pending != null && scroll.takeIfShown(shown)) currentRequest()
    }
    return remember(scroll) { { choice -> scroll.request(choice) } }
}

/** The order a listener chose and is waiting to see from the top. */
internal class PendingSortScroll<T : Any> {
    var pending: T? by mutableStateOf(null)
        private set

    fun request(choice: T) {
        pending = choice
    }

    /** True exactly once: the first time [shown] is the chosen order. */
    fun takeIfShown(shown: T?): Boolean {
        val chosen = pending ?: return false
        if (chosen != shown) return false
        pending = null
        return true
    }
}
