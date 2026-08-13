/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

/** The slider's contract: transitions can soften up to this many seconds each side. */
public const val MAX_CROSSFADE_SECONDS: Int = 12

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
