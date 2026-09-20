/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** How long the sound takes to leave when the listener pauses. */
public const val TRANSPORT_FADE_OUT_MS: Long = 160

/** How long the sound takes to arrive when the listener resumes. */
public const val TRANSPORT_FADE_IN_MS: Long = 220

/**
 * Gain for a transport fade: 1 → 0 over [durationMs] on the way out, 0 → 1 on the way in.
 *
 * Equal-power curves rather than straight lines: a linear ramp sounds like a dip in the middle,
 * where the ear hears the level fall faster than the number. Past the end, and for a duration
 * of zero, the terminal value.
 */
public fun transportFadeFactor(elapsedMs: Long, durationMs: Long, fadingOut: Boolean): Float {
    val terminal = if (fadingOut) 0f else 1f
    if (durationMs <= 0L || elapsedMs >= durationMs) return terminal
    val progress = (elapsedMs.coerceAtLeast(0L).toDouble() / durationMs).coerceIn(0.0, 1.0)
    val angle = progress * PI / 2
    return (if (fadingOut) cos(angle) else sin(angle)).toFloat().coerceIn(0f, 1f)
}
