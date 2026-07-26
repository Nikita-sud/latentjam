/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards against a layout that still runs but is quietly *worse* -- the failure mode determinism
 * tests (see [LibraryLayoutTest]) cannot see, because the whole Map rests on one measured claim:
 * that on-screen neighbours are real neighbours (docs/map-page.md section 3).
 *
 * Both thresholds below were measured against this implementation, not carried over from the task
 * brief: [LibraryLayout.normalize] became a uniform-scale box fit, [LibraryLayout.reduceForEmbedding]
 * gained a second unitize pass, and [Tsne]'s perplexity clamp were all added after the brief was
 * written, and each changes the layout's actual output. See each constant's doc for the measured
 * numbers behind it, on both the JVM and iOS.
 */
class LayoutQualityTest {

    private companion object {
        const val GROUPS = 10
        const val PER_GROUP = 18
        const val DIM = 48
        const val NEIGHBOURS = 15
        const val NOISE_SCALE = 0.6f

        /** Every 20th track dropped is a 5% cut, matching spec section 3.1. */
        const val DROP_EVERY = 20

        const val QUALITY_SEEDS = 8
        const val DRIFT_SEEDS = 10

        /**
         * Floor for the mean share of a track's [NEIGHBOURS] nearest on-screen neighbours drawn
         * from its own planted group, averaged over [QUALITY_SEEDS] independent noise draws of the
         * planted structure ([PER_GROUP] - 1 = 17 >= [NEIGHBOURS], so a perfect layout can reach
         * precision 1.0 -- unlike, say, a 15-per-group fixture, which was measured to cap out at
         * 14/15 = 0.933 regardless of layout quality, a structural ceiling rather than a quality
         * signal).
         *
         * Measured: every one of [QUALITY_SEEDS] seeds scored *exactly* 1.0 on the JVM, and the same
         * held on iOS -- a 20-seed sweep (more than committed here) gave 1.0 on every seed on both
         * platforms, zero variance observed. A fully shuffled layout on this same fixture (positions
         * randomly reassigned across tracks, carrying no group structure at all) measured precision
         * 0.083 on the JVM and 0.084 on iOS -- close to a group's 1-in-10 population share, as
         * expected for a position uncorrelated with group. 0.85 sits 0.15 below every measured
         * "correct" run (i.e. below the entire observed range, not merely its mean) and about 10x
         * above the blob baseline, so it fails only for a layout meaningfully worse than either
         * extreme measured here.
         */
        const val FLOOR = 0.85f

        /**
         * Ceiling for the mean per-track drift (as a fraction of the normalized 0..1 canvas),
         * averaged over [DRIFT_SEEDS] independent noise draws, after dropping 5% of the library and
         * recomputing warm-started and Procrustes-aligned.
         *
         * Measured (this fixture, [DRIFT_SEEDS] seeds): mean 0.238 on the JVM, 0.219 on iOS. A wider
         * 20-seed sweep put the aggregate at 0.230 / 0.212, with individual seeds ranging
         * ~0.11-0.33 on either platform -- and no single seed's value agreeing between platforms
         * closely enough to trust (e.g. one seed measured 0.113 on the JVM against 0.331 on iOS for
         * the *same* synthetic draw), which is exactly why this asserts the seed-aggregate rather
         * than any one draw. Recomputing the same 5% drop with no previous layout at all -- no warm
         * start, no alignment, the failure mode this test exists to catch -- measured mean drift
         * 0.43-0.61 across three seeds on each platform. 0.35 sits above every aggregate measured on
         * either platform (0.238 JVM / 0.219 iOS at [DRIFT_SEEDS], 0.230 / 0.212 at the wider sweep)
         * with room to spare, and clearly below where an unanchored recompute actually lands.
         */
        const val DRIFT_CEILING = 0.35f
    }

    @Test
    fun `layout keeps planted groups together on screen`() {
        var total = 0f
        for (seed in 1..QUALITY_SEEDS) total += groupPrecision(seed)
        val mean = total / QUALITY_SEEDS
        assertTrue(
            mean >= FLOOR,
            "mean group precision over $QUALITY_SEEDS seeds fell to $mean, floor is $FLOOR",
        )
    }

    // Spec section 3.1: a map people learn must survive the library changing. Recompute after
    // dropping 5% of the tracks, warm-started and Procrustes-aligned, and assert that surviving
    // tracks land near where they were. Without the warm start and alignment this fails outright,
    // because t-SNE is free to rotate or mirror the whole picture between runs -- see DRIFT_CEILING's
    // "no previous" measurement above for what that failure actually looks like on this fixture.
    @Test
    fun `layout stays put when the library changes by five percent`() {
        var total = 0f
        for (seed in 1..DRIFT_SEEDS) total += meanDrift(seed)
        val mean = total / DRIFT_SEEDS
        assertTrue(
            mean < DRIFT_CEILING,
            "mean drift over $DRIFT_SEEDS seeds was $mean of the canvas, ceiling is $DRIFT_CEILING",
        )
    }

    private fun groupPrecision(seed: Int): Float {
        val ids = (0 until GROUPS * PER_GROUP).map { TrackId("t$it") }
        val points = LibraryLayout.compute(plantedSpace(ids, seed))
        val byId = points.associateBy { it.trackId }
        val ordered = ids.map { byId.getValue(it) }

        var total = 0f
        ordered.forEachIndexed { index, point ->
            val group = index / PER_GROUP
            val neighbours = ordered.asSequence()
                .withIndex()
                .filter { it.index != index }
                .sortedBy { squaredDistance(point, it.value) }
                .take(NEIGHBOURS)
                .count { it.index / PER_GROUP == group }
            total += neighbours.toFloat() / NEIGHBOURS
        }
        return total / ordered.size
    }

    private fun meanDrift(seed: Int): Float {
        val ids = (0 until GROUPS * PER_GROUP).map { TrackId("t$it") }
        val full = LibraryLayout.compute(plantedSpace(ids, seed))
        val previous = full.associate { it.trackId to floatArrayOf(it.x, it.y) }

        val kept = ids.filterIndexed { index, _ -> index % DROP_EVERY != 0 }
        val moved = LibraryLayout.compute(plantedSpace(kept, seed), previous = previous)
        val movedById = moved.associateBy { it.trackId }

        var drift = 0f
        for (id in kept) {
            val before = previous.getValue(id)
            val after = movedById.getValue(id)
            val dx = before[0] - after.x
            val dy = before[1] - after.y
            drift += sqrt(dx * dx + dy * dy)
        }
        return drift / kept.size
    }

    /**
     * Plants [GROUPS] groups of [PER_GROUP] tracks each, at a fixed corner of a [DIM]-dimensional
     * cube plus noise scaled by [NOISE_SCALE], seeded independently per [seed] for the aggregate
     * above.
     *
     * A track's group is derived from the numeral embedded in its own id, not from its position in
     * [ids] -- so when the stability test drops tracks before calling this again, a surviving
     * track's planted group cannot shift with it. Deriving it from list position instead would
     * silently reshuffle the planting on every drop and the stability test would measure nothing.
     */
    private fun plantedSpace(ids: List<TrackId>, seed: Int): LibraryVectorSpace {
        val audio = HashMap<TrackId, FloatArray>()
        val metadata = HashMap<TrackId, FloatArray>()
        for (id in ids) {
            val index = id.value.removePrefix("t").toInt()
            val group = index / PER_GROUP
            var state = seed.toLong() * 2654435761L + index.toLong() * 6364136223846793005L + 1L
            fun noise(): Float {
                state = state xor (state shl 13)
                state = state xor (state ushr 7)
                state = state xor (state shl 17)
                return ((state ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat() * 2f - 1f
            }
            audio[id] = FloatArray(DIM) { d -> (if (d == group) 8f else 0f) + noise() * NOISE_SCALE }
            metadata[id] = FloatArray(DIM) { d -> (if (d == group) 8f else 0f) + noise() * NOISE_SCALE }
        }
        return requireNotNull(
            LibraryVectorFusion.build(ids, audio, metadata, audioDim = DIM, metadataDim = DIM),
        )
    }

    private fun squaredDistance(a: LayoutPoint, b: LayoutPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
