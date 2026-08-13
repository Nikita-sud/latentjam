/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.pow

/** The slider's contract: transitions can soften up to this many seconds each side. */
public const val MAX_CROSSFADE_SECONDS: Int = 12

/** Fine enough to keep short gain ramps smooth instead of stepping audibly. */
internal const val GAIN_RAMP_TICK_MS: Long = 20L

/** How long a newly learned normalization value takes to reach its target. */
internal const val NORMALIZATION_RAMP_MS: Long = 400L

/** A persisted or programmatic value degrades to the nearest legal one, never to a crash. */
public fun sanitizeCrossfadeSeconds(value: Int): Int = value.coerceIn(0, MAX_CROSSFADE_SECONDS)

/**
 * Amplitude factor for smooth transitions: rises over the first [fadeMs] of a track and falls
 * over its last [fadeMs].
 *
 * This is a single-player fade, not a two-stream overlap: ExoPlayer's gapless join stays, the
 * seam just stops being abrupt. Tracks shorter than two fades scale both slopes to halves so a
 * rise and a fall always fit. An unknown duration fades in only — never a surprise mute in the
 * middle of a stream whose end the player cannot see.
 */
public fun crossfadeFactor(positionMs: Long, durationMs: Long, fadeMs: Long): Float {
    if (fadeMs <= 0) return 1f
    val position = positionMs.coerceAtLeast(0)
    if (durationMs <= 0) {
        return (position.coerceAtMost(fadeMs).toFloat() / fadeMs).coerceIn(0f, 1f)
    }
    val effectiveFade = minOf(fadeMs, durationMs / 2)
    if (effectiveFade <= 0) return 1f
    val fadeIn = position.coerceAtMost(effectiveFade).toFloat() / effectiveFade
    val remaining = (durationMs - position).coerceAtLeast(0)
    val fadeOut = remaining.coerceAtMost(effectiveFade).toFloat() / effectiveFade
    return minOf(fadeIn, fadeOut).coerceIn(0f, 1f)
}

/**
 * Delay until the fade envelope needs another sample.
 *
 * Boundary ramps use a 20 ms cadence. The middle of a known track sleeps until fade-out begins,
 * avoiding ten wakeups per second for minutes of constant gain. Unknown durations are rechecked
 * coarsely because a prepared player may publish its duration after playback starts.
 */
internal fun crossfadeUpdateDelayMs(positionMs: Long, durationMs: Long, fadeMs: Long): Long {
    if (fadeMs <= 0L) return Long.MAX_VALUE
    val position = positionMs.coerceAtLeast(0L)
    if (durationMs <= 0L) {
        return if (position < fadeMs) GAIN_RAMP_TICK_MS else UNKNOWN_DURATION_RECHECK_MS
    }
    val effectiveFade = minOf(fadeMs, durationMs / 2L)
    if (effectiveFade <= 0L) return Long.MAX_VALUE
    val fadeOutStartsAt = durationMs - effectiveFade
    return when {
        position < effectiveFade -> GAIN_RAMP_TICK_MS
        position >= fadeOutStartsAt -> GAIN_RAMP_TICK_MS
        else -> (fadeOutStartsAt - position).coerceAtLeast(GAIN_RAMP_TICK_MS)
    }
}

/** dB-linear interpolation avoids a newly measured track dropping several dB in one audio buffer. */
internal fun normalizationRampVolume(
    from: Float,
    to: Float,
    elapsedMs: Long,
    durationMs: Long = NORMALIZATION_RAMP_MS,
): Float {
    val start = from.coerceIn(0f, 1f)
    val target = to.coerceIn(0f, 1f)
    if (durationMs <= 0L || elapsedMs >= durationMs) return target
    if (elapsedMs <= 0L || start == target) return start
    val progress = elapsedMs.toFloat() / durationMs
    return if (start > 0f && target > 0f) {
        (start * (target / start).pow(progress)).coerceIn(0f, 1f)
    } else {
        (start + (target - start) * progress).coerceIn(0f, 1f)
    }
}

private const val UNKNOWN_DURATION_RECHECK_MS: Long = 500L
