/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId

/** Listening aggregates for one region of the Map. */
public data class RegionListening(
    public val region: Int,
    public val trackCount: Int,
    public val neverPlayed: Int,
    public val plays: Int,
    /**
     * Share of starts in this region that ended in a skip: total skips divided by total plays,
     * pooled across every played track in the region — not a per-track average. This is the
     * number the "X% of starts" headline names, so a region's few heavily played tracks weigh
     * in proportion to how often they were actually played, not as much as a single one-play
     * track.
     */
    public val skipRate: Float,
)

/** Everything the Map's headlines and selection card need, and nothing about how to word it. */
public data class LibraryListening(
    public val trackCount: Int,
    public val neverPlayed: Int,
    public val tracksForHalfOfPlays: Int,
    public val regions: List<RegionListening>,
    public val darkestRegion: Int?,
    public val skippiestRegion: Int?,
    public val maxPlays: Int,
)

/**
 * Turns the listening log into the handful of facts worth showing.
 *
 * Every figure here is one a plain sorted list could not produce — that is the bar the Map page is
 * held to. Counts of plays and top artists are deliberately absent: the Tracks and Artists tabs
 * already answer those.
 */
public object LibraryListeningStats {

    /** Below this a region's never-played rate is noise rather than a finding. */
    private const val MIN_REGION_FOR_DARKEST = 8

    /** Skip rate needs a real denominator before it can be quoted in a sentence. */
    private const val MIN_PLAYED_FOR_SKIPPIEST = 10

    public fun summarize(
        regionOf: Map<TrackId, Int>,
        stats: Map<TrackId, TrackStats>,
    ): LibraryListening {
        val playsOf = { id: TrackId -> stats[id]?.plays ?: 0 }
        val trackCount = regionOf.size
        val neverPlayed = regionOf.keys.count { playsOf(it) == 0 }
        val maxPlays = regionOf.keys.maxOfOrNull(playsOf) ?: 0

        val descending = regionOf.keys.map(playsOf).sortedDescending()
        val total = descending.sum()
        var running = 0
        var half = 0
        for (plays in descending) {
            if (running * 2 >= total) break
            running += plays
            half++
        }

        // Map.toSortedMap() is JVM-only (backed by java.util.TreeMap); sortedBy is the
        // cross-platform way to get a deterministic, iteration-order-independent region list.
        val regions = regionOf.entries
            .groupBy({ it.value }, { it.key })
            .entries
            .sortedBy { it.key }
            .map { (region, members) ->
                val played = members.filter { playsOf(it) > 0 }
                val totalPlays = members.sumOf(playsOf)
                val totalSkips = played.sumOf { stats.getValue(it).skips }
                RegionListening(
                    region = region,
                    trackCount = members.size,
                    neverPlayed = members.size - played.size,
                    plays = totalPlays,
                    skipRate = if (totalPlays == 0) 0f else totalSkips.toFloat() / totalPlays,
                )
            }

        return LibraryListening(
            trackCount = trackCount,
            neverPlayed = neverPlayed,
            tracksForHalfOfPlays = half,
            regions = regions,
            darkestRegion = regions
                .filter { it.trackCount >= MIN_REGION_FOR_DARKEST }
                .maxByOrNull { it.neverPlayed.toFloat() / it.trackCount }
                ?.region,
            skippiestRegion = regions
                .filter { it.trackCount - it.neverPlayed >= MIN_PLAYED_FOR_SKIPPIEST }
                .maxByOrNull { it.skipRate }
                ?.region,
            maxPlays = maxPlays,
        )
    }
}
