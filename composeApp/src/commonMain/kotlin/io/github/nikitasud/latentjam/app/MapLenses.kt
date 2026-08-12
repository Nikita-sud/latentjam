/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.LibraryListening
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.pow

/** Which fact the Map is currently painting. */
enum class MapLens { WORLDS, PLAYS, NEVER_PLAYED, SKIPS }

/** One track's position and listening signal, everything a dot needs and nothing more. */
data class MapDot(
    val trackId: TrackId,
    val x: Float,
    val y: Float,
    /** The region that claimed this track, or [NO_REGION] if none did. */
    val region: Int,
    val plays: Int,
    val skipRate: Float,
) {
    val claimed: Boolean get() = region != NO_REGION

    companion object {
        /**
         * [region] for a track no region claimed.
         *
         * `LibraryWorlds` admits a track to a region only on evidence, and drops it at five separate
         * points otherwise: confidence trimming with no backfill, a cluster whose confident
         * remainder is under `MIN_CLUSTER_SIZE`, a thin music subgroup, a name whose claim the track
         * does not satisfy (`admitted = tracks.filter(label.accepts)`), and a thin special-content
         * route. Every one of those is right for *naming* a region — and on a real 877-track library
         * they together left 90 tracks, better than a tenth of it, in no region at all.
         *
         * Those 90 used to be dropped from the map entirely: no dot, and absent from its counts, on
         * a page whose whole claim is to be "the library as a place". They are drawn now, in the
         * neutral ink the legend already calls "the rest of your library", which is exactly what
         * they are. Deliberately negative so it can never collide with a real region index (region
         * ids are indices into `regions`), and so the `dot.region == selectedRegion` tests in [ink]
         * and [radius] keep an unclaimed dot neutral and small without needing a special case.
         */
        const val NO_REGION: Int = -1
    }
}

/**
 * A dot's ink, named by role rather than by colour so the palette lives in the theme and this file
 * stays testable without Compose.
 */
sealed interface MapInk {
    data object Neutral : MapInk
    data object Accent : MapInk
    /** Cool sequential ramp, 0 = nearest the surface. */
    data class Ramp(val step: Int) : MapInk
    /** Warm sequential ramp, used where the quantity is unwelcome. */
    data class WarmRamp(val step: Int) : MapInk
}

enum class MapLegend { REGION_SELECTION, PLAY_RAMP, NEVER_PLAYED_KEY, SKIP_RAMP }

/**
 * Every colour and size decision the Map makes.
 *
 * The governing rule, forced by accessibility rather than taste: a scatter plot supports only three
 * categorical hues at colourblind-safe separation across all pairs, and a library clusters into
 * eight or more regions. Colouring regions by hue is therefore not on the table, so **colour always
 * encodes a number** and identity is carried by the selection state and the labels.
 */
object MapLenses {

    /** Steps in each sequential ramp. */
    const val RAMP_STEPS: Int = 6

    /** Below this many plays the statistical lenses cannot say anything true. */
    const val MIN_EVENTS_FOR_STATS: Int = 50

    private const val BASE_RADIUS = 2.2f

    /**
     * An unclaimed track's dot, on every lens: the same size an unselected region's dot has on the
     * Worlds lens, so the library it is part of reads as quiet background rather than as a mark
     * competing with whatever the current lens is actually about.
     */
    private const val UNCLAIMED_RADIUS = 1.8f

    fun ink(lens: MapLens, dot: MapDot, selectedRegion: Int, maxPlays: Int): MapInk =
        // An unclaimed track is drawn on every lens but counted by none of them: the listening
        // figures each headline quotes come from LibraryListeningStats.summarize, which is keyed by
        // region, so a track no region claimed is absent from all of them. Painting such a track into
        // a ramp would put colour on the plot that the sentence above it does not account for -- 91
        // tracks' worth on a real library, e.g. more accented "never played" dots than the "342"
        // beside them. Neutral says what is true of it here: present, and outside this statistic.
        // (RAMP_FLOOR_ALPHA keeps Neutral distinguishable from a ramp's own zero step, so this reads
        // as "not counted" rather than as "counted, lowest value".)
        if (!dot.claimed) MapInk.Neutral else inkOfClaimed(lens, dot, selectedRegion, maxPlays)

    private fun inkOfClaimed(
        lens: MapLens,
        dot: MapDot,
        selectedRegion: Int,
        maxPlays: Int,
    ): MapInk = when (lens) {
        MapLens.WORLDS -> if (dot.region == selectedRegion) MapInk.Accent else MapInk.Neutral
        MapLens.PLAYS -> if (dot.plays <= 0) {
            MapInk.Neutral
        } else {
            // Compressed so the long tail of once-played tracks still separates from the top.
            val fraction = (dot.plays.toFloat() / maxOf(maxPlays, 1)).coerceIn(0f, 1f)
            MapInk.Ramp(step(fraction.pow(0.4f)))
        }
        MapLens.NEVER_PLAYED -> if (dot.plays <= 0) MapInk.Accent else MapInk.Neutral
        MapLens.SKIPS -> if (dot.plays <= 0) {
            MapInk.Neutral
        } else {
            MapInk.WarmRamp(step(dot.skipRate.coerceIn(0f, 1f)))
        }
    }

    fun radius(lens: MapLens, dot: MapDot, selectedRegion: Int): Float = when {
        // Size follows colour for the same reason (see [ink]): on the never-played lens size is half
        // the encoding, so leaving an uncounted track at the large "never played" radius would make
        // it read as one of the tracks that figure counts.
        !dot.claimed -> UNCLAIMED_RADIUS
        lens == MapLens.NEVER_PLAYED -> if (dot.plays <= 0) 3.0f else 1.6f
        lens == MapLens.WORLDS -> if (dot.region == selectedRegion) 2.8f else 1.8f
        else -> BASE_RADIUS
    }

    fun legend(lens: MapLens): MapLegend = when (lens) {
        MapLens.WORLDS -> MapLegend.REGION_SELECTION
        MapLens.PLAYS -> MapLegend.PLAY_RAMP
        MapLens.NEVER_PLAYED -> MapLegend.NEVER_PLAYED_KEY
        MapLens.SKIPS -> MapLegend.SKIP_RAMP
    }

    /**
     * Worlds always works; the rest wait until the log can support a true sentence. Hidden rather
     * than empty — an empty chart is a broken promise, a missing chip is not.
     *
     * A lens whose headline names a region ([MapLens.NEVER_PLAYED] needs [LibraryListening]'s
     * `darkestRegion`, [MapLens.SKIPS] needs `skippiestRegion`) is only offered once that specific
     * region id is non-null, not merely once total plays clear [minEvents]. The two thresholds are
     * very different in practice: `darkestRegion` needs one region with >= 8 tracks, but
     * `skippiestRegion` needs one region with >= 10 *played* tracks -- a far harder bar on a library
     * with many regions and thin-per-region history, so `plays >= minEvents` clearing does not imply
     * `skippiestRegion` is non-null. Without this, the chip appears and its headline renders with an
     * empty region name and a 0% that names nothing true (spec section 8: "The stat lenses appear
     * once the listening log can support a true sentence").
     */
    fun availableLenses(
        listening: LibraryListening,
        minEvents: Int = MIN_EVENTS_FOR_STATS,
    ): List<MapLens> {
        val plays = listening.regions.sumOf { it.plays }
        if (plays < minEvents) return listOf(MapLens.WORLDS)
        return buildList {
            add(MapLens.WORLDS)
            add(MapLens.PLAYS)
            if (listening.darkestRegion != null) add(MapLens.NEVER_PLAYED)
            if (listening.skippiestRegion != null) add(MapLens.SKIPS)
        }
    }

    /** Buckets a fraction already clamped to 0f..1f into 0 until [RAMP_STEPS]. */
    private fun step(fraction: Float): Int =
        (fraction * RAMP_STEPS).toInt().coerceIn(0, RAMP_STEPS - 1)
}
