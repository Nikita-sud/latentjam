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

/**
 * Main-thread-owned gain envelope shared by both native players. The pause intent outlives its
 * envelope: reaching silence must not turn a second tap into another pause or restore full gain
 * before the backend actually stops.
 */
internal class TransportFadeState {
    var active: Boolean = false
        private set
    var pausePending: Boolean = false
        private set

    private var gain = 1f
    private var from = 1f
    private var fadingOut = false
    private var waitingForPlayback = false
    private var startedAtMs = 0L

    fun beginPause(nowMs: Long) {
        from = gainAt(nowMs)
        fadingOut = true
        waitingForPlayback = false
        startedAtMs = nowMs
        active = true
        pausePending = true
    }

    fun beginResume(nowMs: Long, startImmediately: Boolean = true) {
        from = if (active || pausePending) gainAt(nowMs) else 0f
        gain = from
        fadingOut = false
        waitingForPlayback = !startImmediately
        startedAtMs = nowMs
        active = true
        pausePending = false
    }

    /** Decoding/focus acquisition can take longer than the whole ramp; hold silence until then. */
    fun onPlaybackStarted(nowMs: Long) {
        if (!waitingForPlayback) return
        waitingForPlayback = false
        startedAtMs = nowMs
    }

    fun gainAt(nowMs: Long): Float {
        if (!active || waitingForPlayback) return gain
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
        val duration = if (fadingOut) TRANSPORT_FADE_OUT_MS else TRANSPORT_FADE_IN_MS
        val curve = transportFadeFactor(elapsed, duration, fadingOut)
        gain = if (fadingOut) from * curve else from + (1f - from) * curve
        if (elapsed >= duration) active = false
        return gain
    }

    /** Called after a real pause/stop, or before an explicit replacement starts. */
    fun reset() {
        active = false
        pausePending = false
        waitingForPlayback = false
        gain = 1f
    }
}
