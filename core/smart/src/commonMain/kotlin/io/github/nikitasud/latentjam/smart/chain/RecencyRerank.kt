/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.SmartHistoryEvent
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * A bounded novelty prior over the private on-device history.
 *
 * Relevance still decides among tracks of similar age; this multiplier only makes a recently heard
 * candidate need a meaningfully better acoustic/context score to repeat. It decays smoothly back
 * to neutral and never bans a track, which matters for small libraries and repeat-heavy listeners.
 * The newest event is the planning-time seed supplied by the app, so its timestamp is the local
 * reference clock without adding a platform clock dependency to the chain.
 */
internal class RecencyRerank(events: List<SmartHistoryEvent>) {

    private val referenceMs: Long = events.maxOfOrNull { it.startedAtMs } ?: Long.MIN_VALUE
    private val latestByTrack: Map<TrackId, Long> = buildMap {
        for (event in events) {
            val previous = get(event.trackId) ?: Long.MIN_VALUE
            if (event.startedAtMs > previous) put(event.trackId, event.startedAtMs)
        }
    }

    fun multiplier(trackId: TrackId): Float {
        val latest = latestByTrack[trackId] ?: return 1f
        if (referenceMs == Long.MIN_VALUE) return 1f
        val ageMs = (referenceMs - latest).coerceAtLeast(0L)
        return when {
            ageMs <= SESSION_MS -> 0.10f
            ageMs < DAY_MS -> 0.25f
            ageMs < WEEK_MS -> 0.55f
            ageMs < MONTH_MS -> 0.80f
            ageMs < QUARTER_MS -> 0.92f
            else -> 1f
        }
    }

    private companion object {
        const val SESSION_MS = 30L * 60 * 1_000
        const val DAY_MS = 24L * 60 * 60 * 1_000
        const val WEEK_MS = 7L * DAY_MS
        const val MONTH_MS = 30L * DAY_MS
        const val QUARTER_MS = 90L * DAY_MS
    }
}
