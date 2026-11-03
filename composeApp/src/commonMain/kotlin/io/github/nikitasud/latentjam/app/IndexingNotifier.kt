/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import org.koin.core.module.Module

/**
 * Surfaces library analysis outside the app's own UI.
 *
 * Analysis is the one operation here that runs for minutes rather than
 * milliseconds, and the one a user is most likely to walk away from. Without a
 * notification it is invisible: a phone quietly working through several hundred
 * tracks looks exactly like a phone quietly doing nothing.
 *
 * Deliberately not a Compose concept. Analysis runs on [AppGraph.appScope] and
 * outlives any composition, so a reporter scoped to the UI would fall silent
 * precisely when the user leaves the screen — which is when it is needed.
 *
 * Takes already-formatted text rather than resources: the caller resolves
 * strings in a context where the app's Compose resources and locale are
 * available, which keeps every platform's notification code free of i18n
 * plumbing.
 */
interface IndexingNotifier {

    /**
     * Opens one run's hold on the report.
     *
     * A run that replaces another opens its session before it cancels the old run, so the old
     * run's [IndexingSession.close] cannot take the foreground state away from the new one.
     */
    fun open(): IndexingSession

    /**
     * A plain, dismissible notice after a run stopped ("Paused by you"), apart from the progress
     * report so it outlives it; [continueLabel] adds a Continue action that opens the app. Best effort:
     * platforms without such a surface, or without the permission, show nothing.
     */
    fun notice(title: String, text: String, continueLabel: String?) = Unit

    /** Removes the last [notice], once the user continued. */
    fun clearNotice() = Unit
}

/** One analysis run's report. */
interface IndexingSession {

    /**
     * Shows or updates the progress report.
     *
     * The first call is also what promotes the work to the foreground on
     * platforms that require it, so it must happen before the first chunk
     * rather than after it. Ignored after [close].
     */
    fun show(title: String, text: String, done: Int, total: Int, pauseLabel: String? = null)

    /** The run has stopped, whether it completed or was abandoned. Idempotent. */
    fun close()
}

/**
 * Rolling estimate of how much analysis is left.
 *
 * Kept pure and separate so the arithmetic is testable without a device, and so
 * every platform shows the same number rather than each inventing its own.
 *
 * Averages over the whole run rather than the last chunk: per-chunk timings
 * swing widely with track length and codec, and an estimate that jumps between
 * "2 minutes" and "20 minutes" reads as broken even when each figure is
 * defensible.
 */
class IndexingEta(private var startedAtMs: Long) {

    /** Leaves [ms] (a wait) out of the pace. */
    fun skip(ms: Long) {
        startedAtMs += ms
    }

    /**
     * Milliseconds remaining, or `null` when there is not enough evidence yet.
     *
     * Null rather than a guess for the first chunk: an estimate derived from a
     * single sample is noise, and showing nothing is more honest than showing
     * a number that will immediately be contradicted.
     */
    fun remainingMs(done: Int, total: Int, nowMs: Long): Long? {
        if (done <= 0 || done >= total) return null
        val elapsed = nowMs - startedAtMs
        if (elapsed <= 0) return null
        val perTrack = elapsed.toDouble() / done
        return ((total - done) * perTrack).toLong()
    }

    companion object {
        /** Rounded up: "1 minute left" that takes 90 seconds is worse than "2". */
        fun minutesFrom(remainingMs: Long): Int =
            ((remainingMs + 59_999) / 60_000).toInt().coerceAtLeast(1)
    }
}

/** Koin bindings for [IndexingNotifier] on this platform. */
expect fun indexingNotifierModule(): Module
