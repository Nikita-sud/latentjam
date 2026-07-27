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
    val region: Int,
    val plays: Int,
    val skipRate: Float,
)

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

    fun ink(lens: MapLens, dot: MapDot, selectedRegion: Int, maxPlays: Int): MapInk = when (lens) {
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

    fun radius(lens: MapLens, dot: MapDot, selectedRegion: Int): Float = when (lens) {
        // Size carries the never-played distinction alongside colour, so the one lens whose colour
        // means membership rather than magnitude never rests on hue alone.
        MapLens.NEVER_PLAYED -> if (dot.plays <= 0) 3.0f else 1.6f
        MapLens.WORLDS -> if (dot.region == selectedRegion) 2.8f else 1.8f
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
