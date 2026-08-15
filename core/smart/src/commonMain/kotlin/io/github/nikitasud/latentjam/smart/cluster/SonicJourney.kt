/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt

/**
 * A mix that travels: evenly spaced waypoints on the straight line between two tracks'
 * embeddings, each snapped to the nearest library track not yet on the path.
 *
 * The point is narrative — the listen audibly goes somewhere, and it walks the listener into
 * unexplored regions along a gradient instead of a cold drop. Prototyped both ways on a real
 * library before this shipped: true interpolation read as a story (mean adjacent cos 0.35,
 * passing through the slowed cover of the destination on the way to the original), while a
 * cheap worlds-order path measured a meaningless −0.003 and picked up a voice memo. Only the
 * real geometry earns the card.
 *
 * A [Session] must be created BEFORE the vector space's one-shot clustering handoff; it copies
 * what it needs (centered unit rows) and holds no reference to the space afterwards.
 */
public class SonicJourney private constructor(
    private val trackIds: List<TrackId>,
    private val unit: FloatArray,
    private val dim: Int,
) {
    private val rowOf: Map<TrackId, Int> =
        trackIds.withIndex().associate { (index, id) -> id to index }

    public companion object {
        /** Endpoints plus eight steps: long enough to feel like travel, short enough to finish. */
        public const val LENGTH: Int = 10

        /**
         * Builds the read-only geometry for plotting journeys. Centers the rows against the
         * library mean — the same normalization clustering applies — into a private copy, so the
         * space itself stays untouched for its later one-shot handoff.
         */
        public fun session(space: LibraryVectorSpace): SonicJourney {
            val rows = space.peekRows()
            val dim = space.dim
            val count = space.trackIds.size
            val mean = FloatArray(dim)
            for (row in 0 until count) {
                val base = row * dim
                for (d in 0 until dim) mean[d] += rows[base + d]
            }
            for (d in 0 until dim) mean[d] /= count
            val unit = FloatArray(count * dim)
            for (row in 0 until count) {
                val base = row * dim
                var norm = 0.0
                for (d in 0 until dim) {
                    val v = rows[base + d] - mean[d]
                    unit[base + d] = v
                    norm += v * v
                }
                val scale = (1.0 / sqrt(norm)).toFloat()
                if (scale.isFinite()) {
                    for (d in 0 until dim) unit[base + d] *= scale
                }
            }
            return SonicJourney(space.trackIds, unit, dim)
        }
    }

    private fun cosTo(row: Int, direction: FloatArray): Double {
        val base = row * dim
        var s = 0.0
        for (d in 0 until dim) s += unit[base + d] * direction[d]
        return s
    }

    /** The candidate farthest from [from] — the most travel the library can offer. */
    public fun farthestFrom(from: TrackId, candidates: Collection<TrackId>): TrackId? {
        val fromRow = rowOf[from] ?: return null
        val direction = FloatArray(dim) { d -> unit[fromRow * dim + d] }
        return candidates
            .mapNotNull { id -> rowOf[id]?.let { row -> id to cosTo(row, direction) } }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * Plots [from] → [to]: for each interior waypoint the nearest track not yet used and not by
     * the previous stop's artist. Returns null when either endpoint has no vector or the library
     * cannot fill the interior. The path length is at most [LENGTH]; duplicate snaps shrink it.
     */
    public fun plot(
        from: TrackId,
        to: TrackId,
        artistKeyOf: (TrackId) -> String?,
    ): List<TrackId>? {
        val fromRow = rowOf[from] ?: return null
        val toRow = rowOf[to] ?: return null
        if (from == to) return null
        val a = FloatArray(dim) { d -> unit[fromRow * dim + d] }
        val b = FloatArray(dim) { d -> unit[toRow * dim + d] }
        val path = mutableListOf(from)
        val used = hashSetOf(from, to)
        for (step in 1 until LENGTH - 1) {
            val f = step.toFloat() / (LENGTH - 1)
            val waypoint = FloatArray(dim)
            var norm = 0.0
            for (d in 0 until dim) {
                val v = a[d] * (1 - f) + b[d] * f
                waypoint[d] = v
                norm += v * v
            }
            val scale = (1.0 / sqrt(norm)).toFloat()
            if (!scale.isFinite()) continue
            for (d in 0 until dim) waypoint[d] *= scale
            val previousArtist = artistKeyOf(path.last())
            var bestRow = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for (row in trackIds.indices) {
                val id = trackIds[row]
                if (id in used) continue
                if (previousArtist != null && artistKeyOf(id) == previousArtist) continue
                val score = cosTo(row, waypoint)
                if (score > bestScore) {
                    bestScore = score
                    bestRow = row
                }
            }
            if (bestRow < 0) continue
            val snapped = trackIds[bestRow]
            path.add(snapped)
            used.add(snapped)
        }
        path.add(to)
        // A journey of three is a segue, not a trip; below that the card over-promises.
        return path.takeIf { it.size >= 5 }
    }
}
