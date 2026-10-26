/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.TransitionFit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * The crossfade a seam gets when SMART adapts its length (issue #25): the slider is the longest
 * blend, given to a next track that belongs with the current one; a change of sound or pace gets a
 * shorter one, down to [ADAPTIVE_SHORTEST_SHARE] of the slider. An unknown fit keeps the slider.
 *
 * Closeness counts from a typical library track (0.5, no blend gained) to one of the closest
 * (0.95, the full slider). An energy gap takes up to half of what closeness gave: a calm song into
 * a loud one is a change even when the two are otherwise alike.
 */
public fun fittedCrossfadeMs(sliderMs: Long, fit: TransitionFit?): Long {
    if (sliderMs <= 0L || fit == null) return sliderMs.coerceAtLeast(0L)
    val near = ((fit.closeness - ADAPTIVE_TYPICAL) / (ADAPTIVE_CLOSEST - ADAPTIVE_TYPICAL)).coerceIn(0f, 1f)
    val smooth = near * (1f - ADAPTIVE_ENERGY_WEIGHT * (fit.energyGap ?: 0f))
    val share = ADAPTIVE_SHORTEST_SHARE + (1f - ADAPTIVE_SHORTEST_SHARE) * smooth
    val shortest = minOf(sliderMs, ADAPTIVE_FLOOR_MS)
    val fitted = (sliderMs * share / ADAPTIVE_STEP_MS).roundToLong() * ADAPTIVE_STEP_MS
    return fitted.coerceIn(shortest, sliderMs)
}

/**
 * Per-seam crossfade lengths for the controller's player, looked up while it plans a seam.
 * [lengthFor] answers at once, with the slider until the engine has read the pair, so the planner
 * never waits; it starts that read on first sight of the pair, which is the start of the outgoing
 * track, well before its overlap is cued. Main thread only.
 */
internal class SeamCrossfades(
    private val scope: CoroutineScope,
    private val engine: SimilarityEngine,
) {
    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            pair = null
            fit = null
            job?.cancel()
        }

    private var pair: Pair<TrackId, TrackId>? = null
    private var fit: TransitionFit? = null
    private var job: Job? = null

    fun lengthFor(outgoing: TrackId, incoming: TrackId, sliderMs: Long): Long {
        if (!enabled || sliderMs <= 0L) return sliderMs
        val wanted = outgoing to incoming
        if (pair != wanted) {
            pair = wanted
            fit = null
            job?.cancel()
            job = scope.launch {
                val read = runCatching { engine.transitionFit(outgoing, incoming) }.getOrNull()
                if (pair == wanted) {
                    fit = read
                    // No ids: nothing about the library leaves the app through the system log.
                    println("Crossfade: fit=$read length=${fittedCrossfadeMs(sliderMs, read)} ms")
                }
            }
            return sliderMs
        }
        return fittedCrossfadeMs(sliderMs, fit)
    }
}

/** Below a typical library track's closeness, the seam is treated as a full change. */
private const val ADAPTIVE_TYPICAL = 0.5f

/** One of the closest tracks: from here the seam gets the whole slider. */
private const val ADAPTIVE_CLOSEST = 0.95f

/** The shortest blend, as a share of the slider. */
internal const val ADAPTIVE_SHORTEST_SHARE: Float = 0.3f

/** How much of the closeness a full energy gap takes back. */
private const val ADAPTIVE_ENERGY_WEIGHT = 0.5f

/** Never shorter than this, unless the slider itself is. */
internal const val ADAPTIVE_FLOOR_MS: Long = 1_000L

/** Lengths are whole tenths of a second, so a seam does not re-plan over a few milliseconds. */
private const val ADAPTIVE_STEP_MS = 100L
