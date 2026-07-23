/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.TrackStats
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import kotlin.math.sqrt

/**
 * Conservative personalization for the For You worlds row.
 *
 * This stage deliberately uses only the aggregate feedback the app already stores. Completion is
 * positive evidence, while a skip is a weak negative because it can mean "wrong moment" rather
 * than "wrong music". Starts that are neither completed nor skipped cast no satisfaction vote.
 *
 * Scores also retain freshness and posterior uncertainty as exploration signals. That keeps an
 * accuracy-only loop from repeatedly returning the same familiar corner of the library.
 */
internal object ForYouFeedbackRanker {

    /**
     * Ranks worlds without letting either member count or raw start count vote.
     *
     * Equal scores preserve caller order. On a history-free library every world therefore retains
     * the deterministic largest-cluster-first order produced by LibraryWorlds.
     */
    fun rankWorlds(
        worlds: List<LibraryWorld>,
        stats: Map<TrackId, TrackStats>,
        nowMs: Long,
        quietMs: Long,
        excluded: Set<TrackId> = emptySet(),
    ): List<LibraryWorld> = worlds.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<LibraryWorld>> { indexed ->
                worldScore(
                    tracks = indexed.value.tracks.filterNot { it.id in excluded },
                    stats = stats,
                    nowMs = nowMs,
                    quietMs = quietMs,
                )
            }.thenBy { it.index },
        )
        .map { it.value }

    /**
     * Ranks members inside one world, retaining embedding centrality as the deterministic tie-break.
     *
     * Freshness has more influence here than at world level: selecting a familiar world and then
     * starting it from an unheard central track gives the listener both intent and exploration.
     */
    fun rankTracks(
        tracks: List<TrackDescriptor>,
        stats: Map<TrackId, TrackStats>,
        nowMs: Long,
        quietMs: Long,
    ): List<TrackDescriptor> = tracks.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<TrackDescriptor>> { indexed ->
                val feedback = feedback(stats[indexed.value.id], nowMs, quietMs)
                TRACK_SATISFACTION_WEIGHT * feedback.satisfaction +
                    TRACK_FRESHNESS_WEIGHT * feedback.freshness +
                    TRACK_EXPLORATION_WEIGHT * feedback.exploration
            }.thenBy { it.index },
        )
        .map { it.value }

    /**
     * Robust mean member quality. Unlike a sum, this cannot make a mediocre 100-track cluster beat a
     * focused 10-track cluster merely by being larger.
     */
    internal fun worldScore(
        tracks: List<TrackDescriptor>,
        stats: Map<TrackId, TrackStats>,
        nowMs: Long,
        quietMs: Long,
    ): Double {
        if (tracks.isEmpty()) return Double.NEGATIVE_INFINITY
        val qualities = tracks.map { track ->
            val feedback = feedback(stats[track.id], nowMs, quietMs)
            WORLD_SATISFACTION_WEIGHT * feedback.satisfaction +
                WORLD_FRESHNESS_WEIGHT * feedback.freshness +
                WORLD_EXPLORATION_WEIGHT * feedback.exploration
        }
        return trimmedMean(qualities)
    }

    /** Bayesian posterior mean with a neutral prior and fractional evidence from skips. */
    internal fun satisfaction(stat: TrackStats?): Double {
        val positive = PRIOR_POSITIVE + (stat?.completions ?: 0).coerceAtLeast(0)
        val negative = PRIOR_NEGATIVE +
            (stat?.skips ?: 0).coerceAtLeast(0) * SKIP_EVIDENCE_WEIGHT
        return positive / (positive + negative)
    }

    private fun feedback(stat: TrackStats?, nowMs: Long, quietMs: Long): Feedback {
        require(quietMs > 0) { "quietMs must be positive" }
        val positive = PRIOR_POSITIVE + (stat?.completions ?: 0).coerceAtLeast(0)
        val negative = PRIOR_NEGATIVE +
            (stat?.skips ?: 0).coerceAtLeast(0) * SKIP_EVIDENCE_WEIGHT
        val evidence = positive + negative
        val posteriorDeviation = sqrt(
            positive * negative / (evidence * evidence * (evidence + 1.0)),
        )
        return Feedback(
            satisfaction = positive / evidence,
            freshness = freshness(stat, nowMs, quietMs),
            exploration = (posteriorDeviation / PRIOR_DEVIATION).coerceIn(0.0, 1.0),
        )
    }

    /**
     * Mirrors the existing freshness tiers as numeric utility. Starts affect recency, but neutral
     * starts do not affect satisfaction or posterior confidence.
     */
    private fun freshness(stat: TrackStats?, nowMs: Long, quietMs: Long): Double = when {
        stat == null || stat.plays <= 0 -> 1.0
        stat.lastPlayedAtMs <= 0L -> 0.8
        nowMs - stat.lastPlayedAtMs >= quietMs -> 0.8
        nowMs - stat.lastPlayedAtMs >= THIRTY_DAYS_MS -> 0.55
        nowMs - stat.lastPlayedAtMs >= SEVEN_DAYS_MS -> 0.25
        else -> 0.0
    }

    /**
     * Ten-percent trimming for sufficiently large worlds; ordinary mean for small collections where
     * dropping even one member would discard too much evidence.
     */
    private fun trimmedMean(values: List<Double>): Double {
        if (values.size < MIN_TRIM_SIZE) return values.average()
        val sorted = values.sorted()
        val trim = (values.size / 10).coerceAtLeast(1)
        return sorted.subList(trim, sorted.size - trim).average()
    }

    private data class Feedback(
        val satisfaction: Double,
        val freshness: Double,
        val exploration: Double,
    )

    private const val PRIOR_POSITIVE = 2.0
    private const val PRIOR_NEGATIVE = 2.0
    private const val SKIP_EVIDENCE_WEIGHT = 0.35
    private val PRIOR_DEVIATION = sqrt(
        PRIOR_POSITIVE * PRIOR_NEGATIVE /
            (
                (PRIOR_POSITIVE + PRIOR_NEGATIVE) *
                    (PRIOR_POSITIVE + PRIOR_NEGATIVE) *
                    (PRIOR_POSITIVE + PRIOR_NEGATIVE + 1.0)
                ),
    )

    private const val WORLD_SATISFACTION_WEIGHT = 0.75
    private const val WORLD_FRESHNESS_WEIGHT = 0.15
    private const val WORLD_EXPLORATION_WEIGHT = 0.10

    private const val TRACK_SATISFACTION_WEIGHT = 0.45
    private const val TRACK_FRESHNESS_WEIGHT = 0.35
    private const val TRACK_EXPLORATION_WEIGHT = 0.20

    private const val MIN_TRIM_SIZE = 10
    private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
    private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
}
