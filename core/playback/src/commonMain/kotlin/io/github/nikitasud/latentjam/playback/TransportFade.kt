/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * A change the listener makes by hand while the music plays: the sound leaves over [outMs], the
 * change happens in the silence, and the sound arrives over [inMs]. Shorter than the pause fade:
 * the listener is waiting for the next sound, not for silence.
 */
internal enum class ManualChange(val outMs: Long, val inMs: Long) {
    /** Next, Previous to another row, a queue row picked, a swipe on the cover. */
    TRACK(outMs = 150L, inMs = 150L),

    /** A seek-bar tap or release, Previous restarting the track, a jump to a resume point. */
    SEEK(outMs = 60L, inMs = 100L),
}

/**
 * Main-thread-owned gain envelope shared by both native players. The pause intent outlives its
 * envelope: reaching silence must not turn a second tap into another pause or restore full gain
 * before the backend actually stops. A [ManualChange] holds its silence ([changePending]) until
 * [endChange], whatever the backend does in between.
 */
internal class TransportFadeState {
    var active: Boolean = false
        private set
    var pausePending: Boolean = false
        private set
    var changePending: Boolean = false
        private set
    private var durationMs = TRANSPORT_FADE_OUT_MS

    private var gain = 1f
    private var from = 1f
    private var fadingOut = false
    private var waitingForPlayback = false
    private var startedAtMs = 0L

    fun beginPause(nowMs: Long) {
        from = gainAt(nowMs)
        durationMs = TRANSPORT_FADE_OUT_MS
        changePending = false
        fadingOut = true
        waitingForPlayback = false
        startedAtMs = nowMs
        active = true
        pausePending = true
    }

    fun beginResume(nowMs: Long, startImmediately: Boolean = true) {
        from = if (active || pausePending || changePending) gainAt(nowMs) else 0f
        durationMs = TRANSPORT_FADE_IN_MS
        changePending = false
        gain = from
        fadingOut = false
        waitingForPlayback = !startImmediately
        startedAtMs = nowMs
        active = true
        pausePending = false
    }

    /** The sound leaves over [outMs] from the gain it has, then stays silent until [endChange]. */
    fun beginChange(nowMs: Long, outMs: Long) {
        from = gainAt(nowMs)
        durationMs = outMs
        fadingOut = true
        waitingForPlayback = false
        startedAtMs = nowMs
        active = true
        changePending = true
    }

    /** The change is made: the sound arrives from silence over [inMs], once playback runs. */
    fun endChange(nowMs: Long, inMs: Long, startImmediately: Boolean = true) {
        from = 0f
        gain = 0f
        durationMs = inMs
        fadingOut = false
        waitingForPlayback = !startImmediately
        startedAtMs = nowMs
        active = true
        changePending = false
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
        val duration = durationMs
        val curve = transportFadeFactor(elapsed, duration, fadingOut)
        gain = if (fadingOut) from * curve else from + (1f - from) * curve
        if (elapsed >= duration) active = false
        return gain
    }

    /** Called after a real pause/stop, or before an explicit replacement starts. */
    fun reset() {
        active = false
        pausePending = false
        changePending = false
        waitingForPlayback = false
        gain = 1f
    }
}

/**
 * Runs the listener's manual changes in the silence of a short fade ([ManualChange]). While the
 * music plays, a change waits for the sound to leave; changes that arrive meanwhile join it and all
 * run, in order, at the moment of silence — two quick Nexts still skip two tracks — followed by one
 * fade-in. Otherwise a change runs at once. Another command (a pause, a new queue) calls [flush]
 * first, so what was asked before it happens before it.
 *
 * Main thread only. [playing]: the music is sounding and no pause is on its way. [onGain]: the
 * envelope moved, so the platform should ride it (its gain loop).
 */
internal class ManualChangeFades(
    private val fade: TransportFadeState,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val playing: () -> Boolean,
    private val sounding: () -> Boolean = playing,
    private val onGain: () -> Unit,
) {
    private val queued = ArrayList<suspend () -> Unit>()
    private var inMs = 0L
    private var waiting: Job? = null
    private var running = false

    /** A change is waiting for, or running in, its silence. */
    val pending: Boolean get() = waiting != null || running

    suspend fun run(change: ManualChange, action: suspend () -> Unit) {
        if (!pending && !playing()) {
            action()
            return
        }
        queued += action
        inMs = maxOf(inMs, change.inMs)
        if (pending) return
        fade.beginChange(nowMs(), change.outMs)
        onGain()
        waiting = scope.launch {
            delay(change.outMs)
            waiting = null
            drain(fadeIn = true)
        }
    }

    /** What is waiting runs now, without its fade-in: another command takes over the sound. */
    suspend fun flush() {
        if (waiting == null) return
        waiting?.cancel()
        waiting = null
        drain(fadeIn = false)
    }

    private suspend fun drain(fadeIn: Boolean) {
        if (running) return
        running = true
        try {
            while (queued.isNotEmpty()) queued.removeAt(0)()
        } finally {
            running = false
        }
        val arriveMs = inMs
        inMs = 0L
        if (fadeIn && fade.changePending) {
            fade.endChange(nowMs(), arriveMs, startImmediately = sounding())
            onGain()
        }
    }
}
