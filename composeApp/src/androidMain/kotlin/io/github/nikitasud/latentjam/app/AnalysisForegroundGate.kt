/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/**
 * The analysis foreground service's start, update and stop decisions, all taken under one lock
 * (docs/analysis-094-plan.md §4 1e-min, "FGS lifecycle").
 *
 * Free of Android types so the host can drive every interleaving. The rules it keeps:
 * - Only the first update of an idle service calls `startForegroundService`; every later one posts
 *   through the notification manager. A `startService` from the background throws once the
 *   foreground state is gone (after `onTimeout`, or a stop), which used to end the pass.
 * - A stop that arrives before the service has called `startForeground` is deferred to
 *   [onStartCommand], which calls `startForeground` first and then stops. Stopping a service that was
 *   started with `startForegroundService` before it reaches `startForeground` crashes the app on
 *   API 26+.
 * - Runs hold [Session]s. A run replacing another across a library change opens its session before
 *   it cancels the old one, so the old run's close never stops the service under the new run.
 * - Every post and every stop happens under the lock, so a late update can never re-post the
 *   ongoing notification after a stop, and [onStartCommand] always shows the latest progress.
 */
internal class AnalysisForegroundGate(private val platform: Platform) {

    interface Platform {
        /** `startForegroundService`; throws when the system refuses a background start. */
        fun requestStart()

        /** Posts [content] on the running service's ongoing notification. */
        fun post(content: Content)
    }

    data class Content(val title: String, val text: String, val done: Int, val total: Int)

    /** One run's hold on the foreground state. */
    inner class Session internal constructor() {
        private var open = true

        /** Shows or updates the progress; ignored once closed. Rethrows a refused start. */
        fun show(content: Content) = synchronized(lock) {
            if (!open) return
            latest = content
            when (phase) {
                Phase.Idle -> {
                    phase = Phase.Starting
                    stopPending = false
                    try {
                        platform.requestStart()
                    } catch (failure: RuntimeException) {
                        phase = Phase.Idle
                        throw failure
                    }
                }
                Phase.Starting -> stopPending = false
                is Phase.Foreground -> platform.post(content)
                Phase.Lost -> Unit
            }
        }

        /** Idempotent. The last open session's close stops the service. */
        fun close() = synchronized(lock) {
            if (!open) return
            open = false
            sessions--
            if (sessions == 0 && phase == Phase.Lost) phase = Phase.Idle
            if (sessions == 0) {
                // A stray start later must not show this run's progress.
                latest = null
                stopLocked()
            }
        }
    }

    private sealed interface Phase {
        data object Idle : Phase

        /** `startForegroundService` was called and its `onStartCommand` has not run yet. */
        data object Starting : Phase

        data class Foreground(val stop: () -> Unit) : Phase

        /** The system withdrew the foreground state (`onTimeout`, or a refused `startForeground`). */
        data object Lost : Phase
    }

    private val lock = Any()
    private var sessions = 0
    private var phase: Phase = Phase.Idle
    private var stopPending = false
    private var latest: Content? = null

    fun open(): Session = synchronized(lock) {
        sessions++
        Session()
    }

    /**
     * The service's `onStartCommand`. [startForeground] runs first in every case; [stop] runs at once
     * when no run wants the service any more, and is kept for the later stop otherwise. Rethrows a
     * refused [startForeground] only when a run wanted the service: a refusal on the way to an
     * immediate stop concerns nobody.
     */
    fun onStartCommand(startForeground: (Content) -> Unit, stop: () -> Unit) = synchronized(lock) {
        val content = latest ?: Content(title = "", text = "", done = 0, total = 0)
        if (sessions == 0 || stopPending || phase == Phase.Lost) {
            stopPending = false
            if (phase != Phase.Lost) phase = Phase.Idle
            try {
                startForeground(content)
            } catch (refused: RuntimeException) {
                println("SMART: analysis foreground start refused on the way to a stop: $refused")
            } finally {
                stop()
            }
            return
        }
        try {
            startForeground(content)
            phase = Phase.Foreground(stop)
        } catch (failure: RuntimeException) {
            phase = Phase.Lost
            stop()
            throw failure
        }
    }

    /** The system's `onTimeout`: the foreground state is gone, and [stop] ends the service. */
    fun onTimeout(stop: () -> Unit) = synchronized(lock) {
        phase = Phase.Lost
        stopPending = false
        stop()
    }

    /**
     * The service was destroyed; the next update starts it again. A run that lost the foreground
     * state stays without it until it closes: it is being cancelled, and must not start the service
     * again on its way out.
     */
    fun onDestroy() = synchronized(lock) {
        when (phase) {
            Phase.Starting -> Unit
            Phase.Lost -> if (sessions == 0) phase = Phase.Idle
            else -> phase = Phase.Idle
        }
    }

    private fun stopLocked() {
        when (val current = phase) {
            is Phase.Foreground -> {
                phase = Phase.Idle
                current.stop()
            }
            Phase.Starting -> stopPending = true
            Phase.Idle, Phase.Lost -> Unit
        }
    }
}
