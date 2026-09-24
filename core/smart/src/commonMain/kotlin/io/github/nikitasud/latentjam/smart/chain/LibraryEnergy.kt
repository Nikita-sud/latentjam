/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.SemanticLabel
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.TrackSemantics

/**
 * How energetic the semantic head finds a track: its high-energy score minus its low-energy score.
 * Raw scores are only comparable within one library, so the chain reads them through
 * [libraryEnergyRanks].
 */
internal fun TrackSemantics.energyScore(): Float =
    probability(SemanticLabel.ENERGY_HIGH) - probability(SemanticLabel.ENERGY_LOW)

/**
 * Energy for the chain's smoothness term: each track's [energyScore] as a percentile of the library
 * the chain walks, in `[0, 1]`. Ranking makes the term's 0.2 dead band mean twenty percentile points
 * in any library, however loud or quiet it is as a whole. Equal scores are ordered by track id, so
 * the result never depends on map order; non-finite scores are left out and stay unknown.
 */
internal fun libraryEnergyRanks(scores: Map<TrackId, Float>): Map<TrackId, Float> {
    val ordered = scores.entries
        .filter { it.value.isFinite() }
        .sortedWith(compareBy<Map.Entry<TrackId, Float>> { it.value }.thenBy { it.key.value })
    val last = (ordered.size - 1).coerceAtLeast(1)
    return ordered.withIndex().associate { (rank, entry) -> entry.key to rank.toFloat() / last }
}

/**
 * [rows] with the energy the chain's smoothness term reads: each row's [libraryEnergyRanks] percentile
 * among the rows [semantics] covers. A row that already carries energy keeps it, and a row without
 * semantics keeps NaN, which the term ignores.
 */
internal fun applyLibraryEnergy(rows: List<SmartTrack>, semantics: Map<TrackId, TrackSemantics>): List<SmartTrack> {
    val ranks = libraryEnergyRanks(
        rows.mapNotNull { row -> semantics[row.id]?.let { row.id to it.energyScore() } }.toMap(),
    )
    if (ranks.isEmpty()) return rows
    return rows.map { row -> if (!row.energy.isNaN()) row else ranks[row.id]?.let { row.copy(energy = it) } ?: row }
}
