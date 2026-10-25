/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.PressInteraction
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The app's shared motion vocabulary.
 *
 * One place instead of per-screen literals, so every surface breathes at the same tempo and the
 * system-wide reduce-motion preference is honoured everywhere by construction. Durations stay
 * short: motion here confirms and orients, it never performs.
 */
internal object Motion {
    /** Glyph swaps and direct feedback under the finger. */
    const val QUICK_MS = 120

    /** Content appearing in place: quick fade with a small settle upward. */
    const val APPEAR_MS = 220

    /** Content being replaced: slightly shorter than incoming, so the swap reads forward. */
    const val REPLACE_MS = 180

    /** Page depth and its initiating control share one short, finite settle. */
    const val NAVIGATION_MS = 260

    /** Quick response followed by a smooth stop, without overshoot or a spring tail. */
    val NavigationEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Full-surface transformations are the only motion allowed to take this long. */
    const val EMPHASIZED_MS = 320

    /** A brief dissolve keeps reduced-motion state changes legible without spatial travel. */
    const val REDUCED_MS = 80

    /** Pressed-card scale: noticeable under the finger, invisible in screenshots. */
    const val PRESS_SCALE = 0.98f

    /** The player's transport buttons sink further: they are pressed on purpose, often blind. */
    const val BUTTON_PRESS_SCALE = 0.88f

    /** A press lands at once; the release eases back, so the control "gives" under the finger. */
    const val PRESS_IN_MS = 60
    const val PRESS_OUT_MS = 360
}

/** The closing path retraces the opening path, rather than vanishing before the morph finishes. */
internal fun chromeNavigationSpec(reduceMotion: Boolean, opening: Boolean): FiniteAnimationSpec<Float> =
    if (reduceMotion) snap() else tween(
        durationMillis = if (opening) Motion.EMPHASIZED_MS else Motion.NAVIGATION_MS,
        easing = if (opening) Motion.NavigationEasing else
            Easing { 1f - Motion.NavigationEasing.transform(1f - it) },
    )

/**
 * A hardware keyboard or D-pad must not move focus into content that cannot be seen or touched:
 * entering the group is refused, so Tab and the arrows skip over it.
 */
internal fun Modifier.unfocusable(): Modifier =
    focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()

/** Standard enter for content that arrives in place (sections, pages, results). */
@Composable
internal fun motionAppearEnter(): EnterTransition {
    if (rememberReduceMotion()) return fadeIn(tween(Motion.REDUCED_MS))
    val maxRisePx = with(LocalDensity.current) { 12.dp.roundToPx() }
    return fadeIn(tween(Motion.REPLACE_MS, easing = LinearEasing)) +
        slideInVertically(
            animationSpec = tween(Motion.APPEAR_MS, easing = Motion.NavigationEasing),
            initialOffsetY = { minOf(it / 12, maxRisePx) },
        )
}

/** Standard exit for content that leaves in place. */
@Composable
internal fun motionAppearExit(): ExitTransition = fadeOut(
    tween(if (rememberReduceMotion()) Motion.REDUCED_MS else Motion.REPLACE_MS / 2),
)

/**
 * Shared-axis transition for navigation depth.
 *
 * Forward pages move farther than the receding page; back returns that page from the shallower
 * offset. Only position and opacity animate, so full-screen lazy content keeps stable constraints.
 * RTL reverses the same depth relationship.
 */
internal fun motionPageTransform(
    forward: Boolean,
    reduceMotion: Boolean,
    layoutDirection: LayoutDirection,
): ContentTransform =
    ContentTransform(
        targetContentEnter = motionPageEnter(forward, reduceMotion, layoutDirection),
        initialContentExit = motionPageExit(forward, reduceMotion, layoutDirection),
        targetContentZIndex = if (forward) 1f else 0f,
        sizeTransform = null,
    )

internal fun motionPageEnter(
    forward: Boolean,
    reduceMotion: Boolean,
    layoutDirection: LayoutDirection,
): EnterTransition {
    if (reduceMotion) return fadeIn(tween(Motion.REDUCED_MS))
    val direction = motionDirection(forward, layoutDirection)
    return slideInHorizontally(
        tween(Motion.NAVIGATION_MS, easing = Motion.NavigationEasing),
    ) { width -> width / (if (forward) 12 else 24) * direction } +
        fadeIn(tween(Motion.REPLACE_MS, delayMillis = 30, easing = LinearEasing))
}

internal fun motionPageExit(
    forward: Boolean,
    reduceMotion: Boolean,
    layoutDirection: LayoutDirection,
): ExitTransition {
    if (reduceMotion) return fadeOut(tween(Motion.REDUCED_MS))
    val direction = motionDirection(forward, layoutDirection)
    return slideOutHorizontally(
        tween(Motion.REPLACE_MS, easing = Motion.NavigationEasing),
    ) { width -> -width / (if (forward) 24 else 12) * direction } +
        fadeOut(tween(Motion.REPLACE_MS / 2, easing = FastOutLinearInEasing))
}

private fun motionDirection(forward: Boolean, layoutDirection: LayoutDirection): Int {
    val readingDirection = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
    return if (forward) readingDirection else -readingDirection
}

/**
 * Content replacement without a depth change. Clear old text before the new text becomes opaque;
 * never scale an entire list. Small forms can opt into size interpolation, but pages do no animated
 * measurement by default.
 */
internal fun motionFadeThrough(reduceMotion: Boolean, animateSize: Boolean = false): ContentTransform {
    val size = if (animateSize) motionSizeTransform(reduceMotion, Motion.APPEAR_MS) else null
    if (reduceMotion) {
        return ContentTransform(
            targetContentEnter = fadeIn(tween(Motion.REDUCED_MS)),
            initialContentExit = fadeOut(tween(Motion.REDUCED_MS)),
            sizeTransform = size,
        )
    }
    return ContentTransform(
        targetContentEnter = fadeIn(tween(
            durationMillis = Motion.APPEAR_MS - Motion.QUICK_MS / 2,
            delayMillis = Motion.QUICK_MS / 2,
            easing = LinearEasing,
        )),
        initialContentExit = fadeOut(tween(Motion.REDUCED_MS, easing = LinearEasing)),
        sizeTransform = size,
    )
}

/** Compact swap for stateful glyphs such as play/pause, repeat, and selection ticks. */
internal fun motionIconTransform(reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) {
        return ContentTransform(
            targetContentEnter = fadeIn(tween(Motion.REDUCED_MS)),
            initialContentExit = fadeOut(tween(Motion.REDUCED_MS)),
            sizeTransform = null,
        )
    }
    return ContentTransform(
        targetContentEnter = fadeIn(tween(Motion.QUICK_MS, easing = LinearEasing)) +
            scaleIn(tween(Motion.QUICK_MS, easing = Motion.NavigationEasing), initialScale = 0.92f),
        initialContentExit = fadeOut(tween(Motion.REDUCED_MS, easing = LinearEasing)) +
            scaleOut(tween(Motion.REDUCED_MS), targetScale = 0.92f),
        sizeTransform = null,
    )
}

internal fun motionSizeTransform(reduceMotion: Boolean, durationMillis: Int): SizeTransform =
    SizeTransform(clip = false) { _, _ ->
        if (reduceMotion) snap() else tween(
            durationMillis = durationMillis,
            easing = Motion.NavigationEasing,
        )
    }

/** Keeps shared player geometry on the same finite clock as its surface crossfade. */
internal fun motionBoundsTransform(): BoundsTransform = BoundsTransform { _, _ ->
    tween(
        durationMillis = Motion.EMPHASIZED_MS,
        easing = Motion.NavigationEasing,
    )
}

/** Keeps an outgoing animation frame drawable while removing its input and accessibility tree. */
internal fun Modifier.inactiveForMotion(inactive: Boolean): Modifier = if (!inactive) {
    this
} else {
    unfocusable()
        .clearAndSetSemantics { }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
}

/** One press source for a control and its [scaleOnPress]: pass the same instance to both. */
@Composable
internal fun rememberPressSource(): MutableInteractionSource = remember { MutableInteractionSource() }

/**
 * Press feedback: a scale under the finger, alongside the ripple. Asymmetric on purpose: the
 * press arrives in [Motion.PRESS_IN_MS] and the release eases back over [Motion.PRESS_OUT_MS], so
 * the response is immediate and the recovery soft; a symmetric spring felt late on a tap.
 *
 * A press always reaches its full depth before it eases back. Inside a scrolling container Compose
 * holds a press back until it knows the touch is not a scroll, so a quick tap delivers press and
 * release in the same frame; following the pressed state then showed nothing at all.
 *
 * The caller owns the [MutableInteractionSource] and passes the same instance to `clickable`,
 * so scale and ripple always agree about what is being pressed.
 */
@Composable
internal fun Modifier.scaleOnPress(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = Motion.PRESS_SCALE,
): Modifier {
    val reduceMotion = rememberReduceMotion()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interactionSource, pressedScale, reduceMotion) {
        if (reduceMotion) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        var pressing: Job? = null
        val held = HashSet<PressInteraction.Press>()
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    held += interaction
                    pressing = launch {
                        scale.animateTo(pressedScale, tween(Motion.PRESS_IN_MS, easing = LinearOutSlowInEasing))
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val press = (interaction as? PressInteraction.Release)?.press
                        ?: (interaction as PressInteraction.Cancel).press
                    held -= press
                    if (held.isEmpty()) {
                        val landing = pressing
                        launch {
                            // Let the press land first, then ease back: the tap is always seen.
                            landing?.join()
                            if (held.isEmpty()) {
                                scale.animateTo(1f, tween(Motion.PRESS_OUT_MS, easing = Motion.NavigationEasing))
                            }
                        }
                    }
                }
            }
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * A grid's first appearance as a diagonal wave: each cell fades in and rises a little, delayed by
 * its row plus column from the top-left cell on screen, so the page arrives as one sweep instead
 * of every cover popping at once. [progress] runs 0 to 1 once per arrival (see [GRID_WAVE_MS]);
 * cells laid out after it finishes, by scrolling, are simply there. Draw-phase only: the wave
 * never recomposes a cell.
 */
internal fun Modifier.gridWave(progress: () -> Float, state: LazyGridState, key: Any): Modifier =
    graphicsLayer {
        val p = progress()
        if (p >= 1f) return@graphicsLayer
        val visible = state.layoutInfo.visibleItemsInfo
        val cell = visible.firstOrNull { it.key == key }
        if (cell == null) {
            alpha = 0f
            return@graphicsLayer
        }
        val firstRow = visible.minOf { it.row }
        val delay = minOf((cell.row - firstRow + cell.column) * GRID_WAVE_STEP_MS, GRID_WAVE_MAX_DELAY_MS)
        val t = ((p * GRID_WAVE_MS - delay) / GRID_WAVE_FADE_MS).coerceIn(0f, 1f)
        val eased = Motion.NavigationEasing.transform(t)
        alpha = eased
        translationY = (1f - eased) * GRID_WAVE_RISE_DP * density
    }

/** One arrival of the wave: the longest delay plus one cell's fade. */
internal const val GRID_WAVE_MS = 440
private const val GRID_WAVE_STEP_MS = 40
private const val GRID_WAVE_MAX_DELAY_MS = 220
private const val GRID_WAVE_FADE_MS = 220f
private const val GRID_WAVE_RISE_DP = 10f
