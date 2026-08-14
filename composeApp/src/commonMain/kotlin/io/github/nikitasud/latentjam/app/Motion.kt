/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection

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

    /** Full-surface transformations are the only motion allowed to take this long. */
    const val EMPHASIZED_MS = 320

    /** A brief dissolve keeps reduced-motion state changes legible without spatial travel. */
    const val REDUCED_MS = 80

    /** Pressed-card scale: noticeable under the finger, invisible in screenshots. */
    const val PRESS_SCALE = 0.98f
}

/** Standard enter for content that arrives in place (sections, pages, results). */
@Composable
internal fun motionAppearEnter(): EnterTransition {
    if (rememberReduceMotion()) return fadeIn(tween(Motion.REDUCED_MS))
    return fadeIn(tween(Motion.APPEAR_MS)) +
        slideInVertically(
            animationSpec = tween(Motion.APPEAR_MS),
            initialOffsetY = { it / 12 },
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
 * Forward pages enter from the reading direction and move the old page gently away; back reverses
 * the relationship. The short travel preserves context without making a phone-sized page feel as
 * though it crossed the whole display. RTL is a real reversal rather than a mirrored screenshot.
 */
internal fun motionPageTransform(
    forward: Boolean,
    reduceMotion: Boolean,
    layoutDirection: LayoutDirection,
): ContentTransform =
    ContentTransform(
        targetContentEnter = motionPageEnter(forward, reduceMotion, layoutDirection),
        initialContentExit = motionPageExit(forward, reduceMotion, layoutDirection),
        sizeTransform = motionSizeTransform(reduceMotion, Motion.APPEAR_MS),
    )

internal fun motionPageEnter(
    forward: Boolean,
    reduceMotion: Boolean,
    layoutDirection: LayoutDirection,
): EnterTransition {
    if (reduceMotion) return fadeIn(tween(Motion.REDUCED_MS))
    val direction = motionDirection(forward, layoutDirection)
    return slideInHorizontally(
        tween(Motion.APPEAR_MS, easing = LinearOutSlowInEasing),
    ) { width -> width / 8 * direction } +
        fadeIn(tween(Motion.APPEAR_MS, easing = LinearOutSlowInEasing))
}

internal fun motionPageExit(
    forward: Boolean,
    reduceMotion: Boolean,
    layoutDirection: LayoutDirection,
): ExitTransition {
    if (reduceMotion) return fadeOut(tween(Motion.REDUCED_MS))
    val direction = motionDirection(forward, layoutDirection)
    return slideOutHorizontally(
        tween(Motion.REPLACE_MS, easing = FastOutLinearInEasing),
    ) { width -> -width / 10 * direction } +
        fadeOut(tween(Motion.REPLACE_MS, easing = FastOutLinearInEasing))
}

private fun motionDirection(forward: Boolean, layoutDirection: LayoutDirection): Int {
    val readingDirection = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
    return if (forward) readingDirection else -readingDirection
}

/** Fade-through for content states that replace one another without changing navigation depth. */
internal fun motionFadeThrough(reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) {
        return ContentTransform(
            targetContentEnter = fadeIn(tween(Motion.REDUCED_MS)),
            initialContentExit = fadeOut(tween(Motion.REDUCED_MS)),
            sizeTransform = motionSizeTransform(true, Motion.REDUCED_MS),
        )
    }
    return ContentTransform(
        targetContentEnter = fadeIn(tween(durationMillis = 160, delayMillis = 40)) +
            scaleIn(tween(Motion.APPEAR_MS), initialScale = 0.985f),
        initialContentExit = fadeOut(tween(Motion.QUICK_MS)),
        sizeTransform = motionSizeTransform(false, Motion.APPEAR_MS),
    )
}

/** Compact swap for stateful glyphs such as play/pause, repeat, and selection ticks. */
internal fun motionIconTransform(reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) {
        return ContentTransform(
            targetContentEnter = fadeIn(tween(Motion.REDUCED_MS)),
            initialContentExit = fadeOut(tween(Motion.REDUCED_MS)),
            sizeTransform = motionSizeTransform(true, Motion.REDUCED_MS),
        )
    }
    return ContentTransform(
        targetContentEnter = fadeIn(tween(Motion.QUICK_MS)) +
            scaleIn(tween(Motion.QUICK_MS), initialScale = 0.88f),
        initialContentExit = fadeOut(tween(Motion.REDUCED_MS)),
        sizeTransform = motionSizeTransform(false, Motion.QUICK_MS),
    )
}

internal fun motionSizeTransform(reduceMotion: Boolean, durationMillis: Int): SizeTransform =
    SizeTransform(clip = false) { _, _ ->
        if (reduceMotion) snap() else tween(
            durationMillis = durationMillis,
            easing = LinearOutSlowInEasing,
        )
    }

/** Keeps shared player geometry on the same finite clock as its surface crossfade. */
internal fun motionBoundsTransform(): BoundsTransform = BoundsTransform { _, _ ->
    tween(
        durationMillis = Motion.EMPHASIZED_MS,
        easing = LinearOutSlowInEasing,
    )
}

/** Keeps an outgoing animation frame drawable while removing its input and accessibility tree. */
internal fun Modifier.inactiveForMotion(inactive: Boolean): Modifier = if (!inactive) {
    this
} else {
    clearAndSetSemantics { }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
}

/**
 * Card press feedback: a small spring scale under the finger, alongside the ripple.
 *
 * The caller owns the [MutableInteractionSource] and passes the same instance to `clickable`,
 * so scale and ripple always agree about what is being pressed.
 */
@Composable
internal fun Modifier.scaleOnPress(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) Motion.PRESS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "press-scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
