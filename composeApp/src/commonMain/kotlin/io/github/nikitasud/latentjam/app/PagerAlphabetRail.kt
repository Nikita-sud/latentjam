/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A page publishes its index, but only the pager's stationary host draws/handles it. */
internal class PageRailSlot {
    var presentation by mutableStateOf<PageRailPresentation?>(null)
    var viewport by mutableStateOf<RailViewport?>(null)
}

internal data class RailViewport(val top: Float, val bottom: Float)

internal data class PageRailPresentation(
    val buckets: List<String>,
    val catalogKey: Any,
    val bottomPadding: Dp,
    val activeBucket: String?,
    val onPreviewJump: (Int) -> Unit,
    val onJump: suspend (Int, Boolean) -> Unit,
    val onScrubbingChange: (Boolean) -> Unit,
)

internal val LocalPageRailSlot = staticCompositionLocalOf<PageRailSlot?> { null }

@Composable
internal fun PublishPageRail(
    slot: PageRailSlot,
    buckets: List<String>,
    catalogKey: Any,
    bottomPadding: Dp,
    activeBucket: String?,
    onPreviewJump: (Int) -> Unit,
    onJump: suspend (Int, Boolean) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
) {
    val preview = rememberUpdatedState(onPreviewJump)
    val jump = rememberUpdatedState(onJump)
    val scrubbing = rememberUpdatedState(onScrubbingChange)
    // Stable callbacks avoid a publish/recompose loop while still observing the latest list.
    val callbacks = remember(catalogKey) {
        Triple<(Int) -> Unit, suspend (Int, Boolean) -> Unit, (Boolean) -> Unit>(
            { preview.value(it) },
            { index, animated -> jump.value(index, animated) },
            { scrubbing.value(it) },
        )
    }
    SideEffect {
        slot.presentation = PageRailPresentation(
            buckets, catalogKey, bottomPadding, activeBucket,
            callbacks.first, callbacks.second, callbacks.third,
        )
    }
    DisposableEffect(slot, catalogKey) {
        onDispose {
            if (slot.presentation?.catalogKey === catalogKey) slot.presentation = null
        }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        // Ignore horizontal movement: swiping pages must not produce per-frame registrations.
        val top = it.positionInRoot().y
        slot.viewport = RailViewport(top, top + it.size.height)
    })
}

@Composable
internal fun PagerAlphabetRail(
    slot: PageRailSlot?,
    paging: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    var hostViewport by remember { mutableStateOf<RailViewport?>(null) }
    val presentation = slot?.presentation
    val viewport = slot?.viewport
    val visible = presentation != null && viewport != null
    var retained by remember { mutableStateOf<PageRailPresentation?>(null) }
    var retainedViewport by remember { mutableStateOf<RailViewport?>(null) }
    SideEffect {
        if (visible) {
            retained = presentation
            retainedViewport = viewport
        }
    }
    val shown = presentation ?: retained
    val shownViewport = viewport.takeIf { visible } ?: retainedViewport
    val top by animateDpAsState(
        targetValue = with(density) {
            ((shownViewport?.top ?: 0f) - (hostViewport?.top ?: 0f)).coerceAtLeast(0f).toDp()
        },
        animationSpec = if (reduceMotion) snap() else tween(Motion.NAVIGATION_MS),
        label = "rail-page-top",
    )
    val bottom by animateDpAsState(
        targetValue = with(density) {
            ((hostViewport?.bottom ?: 0f) - (shownViewport?.bottom ?: 0f))
                .coerceAtLeast(0f).toDp()
        },
        animationSpec = if (reduceMotion) snap() else tween(Motion.NAVIGATION_MS),
        label = "rail-page-bottom",
    )
    Box(modifier.fillMaxSize().onGloballyPositioned {
        val y = it.positionInRoot().y
        hostViewport = RailViewport(y, y + it.size.height)
    }) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS)),
            exit = fadeOut(tween(if (reduceMotion) Motion.REDUCED_MS else Motion.REPLACE_MS)),
        ) {
            Box(Modifier.fillMaxSize().padding(top = top, bottom = bottom)) {
                shown?.let {
                    StandaloneAlphabetRailOverlay(
                        buckets = it.buckets,
                        catalogKey = it.catalogKey,
                        bottomPadding = it.bottomPadding,
                        activeBucket = it.activeBucket,
                        onPreviewJump = it.onPreviewJump,
                        onJump = it.onJump,
                        onScrubbingChange = it.onScrubbingChange,
                        interactive = visible && !paging,
                    )
                }
            }
        }
    }
}
