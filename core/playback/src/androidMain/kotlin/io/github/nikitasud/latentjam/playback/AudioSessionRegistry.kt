/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

/**
 * Where the playback service announces the audio session its player is using.
 *
 * A process-wide holder rather than an injected dependency, because the two ends genuinely cannot
 * see each other: [PlaybackService] is constructed by the system, on the system's schedule, and this
 * app's Koin graph is scoped to the UI entry point rather than installed globally — so the service
 * cannot resolve anything, and may well be created before the graph exists at all.
 *
 * Both orderings work: a listener registering after the session was published is handed the current
 * value immediately.
 */
internal object AudioSessionRegistry {

    private val lock = Any()
    private var currentSessionId: Int? = null
    private var listener: ((Int?) -> Unit)? = null

    /** Called by the service when its player is built, and with null when it is torn down. */
    fun publish(audioSessionId: Int?) {
        val notify: ((Int?) -> Unit)?
        synchronized(lock) {
            currentSessionId = audioSessionId
            notify = listener
        }
        notify?.invoke(audioSessionId)
    }

    /** Replaces the single listener and immediately replays the current session, if any. */
    fun observe(onChange: (Int?) -> Unit) {
        val existing: Int?
        synchronized(lock) {
            listener = onChange
            existing = currentSessionId
        }
        if (existing != null) onChange(existing)
    }
}
