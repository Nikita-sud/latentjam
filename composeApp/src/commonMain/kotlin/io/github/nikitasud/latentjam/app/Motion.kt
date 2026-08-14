/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The app's shared motion vocabulary.
 *
 * One place instead of per-screen literals, so every surface breathes at the same tempo and the
 * system-wide reduce-motion preference is honoured everywhere by construction. Durations stay
 * short: motion here confirms and orients, it never performs.
 */
internal object Motion {
    /** Content appearing in place: quick fade with a small settle upward. */
    const val APPEAR_MS = 220

    /** Content being replaced: outgoing half as long as incoming, so the swap reads forward. */
    const val REPLACE_MS = 180

    /** Pressed-card scale: noticeable under the finger, invisible in screenshots. */
    const val PRESS_SCALE = 0.965f
}

/** Standard enter for content that arrives in place (sections, pages, results). */
@Composable
internal fun motionAppearEnter(): EnterTransition {
    if (rememberReduceMotion()) return fadeIn(tween(90))
    return fadeIn(tween(Motion.APPEAR_MS)) +
        slideInVertically(
            animationSpec = tween(Motion.APPEAR_MS),
            initialOffsetY = { it / 12 },
        )
}

/** Standard exit for content that leaves in place. */
@Composable
internal fun motionAppearExit(): ExitTransition = fadeOut(tween(Motion.REPLACE_MS / 2))

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
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "press-scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
