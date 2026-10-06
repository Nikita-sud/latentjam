/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import kotlin.math.sqrt

/**
 * Orders already selected recommendations into smoother local journeys. Selection still belongs
 * to [SmartChain]; this pass cannot add a track, repeat one, or shorten the queue.
 *
 * Work in short windows, retaining each window's first pick and destination. This preserves the
 * immediate recommendation and the tail from which playback plans its next batch. A plan that
 * continues a walk (`from`) has no immediate recommendation to keep: its first window starts from
 * the track already playing, so the step into the plan is smoothed like any other. Squared
 * distance makes one abrupt transition more expensive than several moderate ones. Descriptor
 * similarity helps keep musical themes together, while audio supplies most of the distance.
 *
 * The 0.35 descriptor weight was selected from 0/0.35/0.65 on separate discovery seeds, then
 * checked on 180 unseen seeds in three libraries. It improves adjacent audio/descriptor cosine;
 * those are continuity proxies, not a claim that listeners always prefer the reordered journey.
 */
internal object JourneySequencer {
    private const val WINDOW_SIZE = 12
    private const val SEMANTIC_WEIGHT = 0.35f
    private const val MIN_GAIN = 1e-6f

    /**
     * @param from the row playing right before [rows] when they continue a walk (the queue's last
     *   track), never one of [rows]; null keeps every window's first pick
     * @param sameArtistCost what two neighbours by one artist add to the journey's cost, in standard
     *   deviations of the window's step costs ([ChainTuning.artistRunPenalty]); 0 leaves the order alone
     */
    fun order(snapshot: SmartSnapshot, rows: List<Int>, from: Int? = null, sameArtistCost: Float = 0f): List<Int> =
        rows.chunked(WINDOW_SIZE).flatMapIndexed { index, window ->
            if (index == 0 && from != null) {
                orderWindow(snapshot, listOf(from) + window, sameArtistCost).drop(1)
            } else {
                orderWindow(snapshot, window, sameArtistCost)
            }
        }

    private fun orderWindow(snapshot: SmartSnapshot, rows: List<Int>, sameArtistCost: Float): List<Int> {
        if (rows.size < 4) return rows
        val n = rows.size
        val costs = Array(n) { FloatArray(n) }
        for (a in 0 until n) {
            for (b in 0 until a) {
                val audio = snapshot.centeredCosine(rows[a], rows[b]).coerceIn(-1f, 1f)
                val semantic = snapshot.descriptorCosine(rows[a], rows[b])
                val similarity = if (semantic == null) audio else
                    audio * (1f - SEMANTIC_WEIGHT) +
                        semantic.coerceIn(-1f, 1f) * SEMANTIC_WEIGHT
                val distance = 1f - similarity
                costs[a][b] = distance * distance
                costs[b][a] = costs[a][b]
            }
        }
        if (sameArtistCost > 0f) separateArtists(snapshot, rows, costs, sameArtistCost)
        val route = rows.indices.toMutableList()
        // Symmetric costs make a reversal's gain depend only on its two boundary edges.
        // Bound the work even for unusual inputs; equal-cost alternatives retain original order.
        repeat(n * 2) {
            var gain = MIN_GAIN
            var left = -1
            var right = -1
            for (i in 1 until n - 2) {
                for (j in i + 1 until n - 1) {
                    val before = costs[route[i - 1]][route[i]] + costs[route[j]][route[j + 1]]
                    val after = costs[route[i - 1]][route[j]] + costs[route[i]][route[j + 1]]
                    if (before - after > gain) {
                        gain = before - after
                        left = i
                        right = j
                    }
                }
            }
            if (left < 0) return route.map { rows[it] }
            while (left < right) {
                val previous = route[left]
                route[left] = route[right]
                route[right] = previous
                left++
                right--
            }
        }
        return route.map { rows[it] }
    }

    private fun separateArtists(snapshot: SmartSnapshot, rows: List<Int>, costs: Array<FloatArray>, weight: Float) {
        val n = rows.size
        var sum = 0.0
        var squares = 0.0
        var count = 0
        for (a in 0 until n) for (b in 0 until a) {
            sum += costs[a][b]
            squares += costs[a][b].toDouble() * costs[a][b]
            count++
        }
        val mean = sum / count
        val spread = sqrt((squares / count - mean * mean).coerceAtLeast(0.0)).toFloat()
        if (spread <= 0f) return
        for (a in 0 until n) for (b in 0 until a) {
            val artist = snapshot.tracks[rows[a]].meta.artistKey
            if (artist.isNotEmpty() && artist == snapshot.tracks[rows[b]].meta.artistKey) {
                costs[a][b] += weight * spread
                costs[b][a] = costs[a][b]
            }
        }
    }
}
