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

    // A pan/zoom redraw can classify thousands of dots every frame. Reuse the finite ramp values
    // instead of allocating one data-class instance per coloured dot per frame.
    private val rampInks = List(RAMP_STEPS, MapInk::Ramp)
    private val warmRampInks = List(RAMP_STEPS, MapInk::WarmRamp)

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
            rampInks[step(fraction.pow(0.4f))]
        }
        MapLens.NEVER_PLAYED -> if (dot.plays <= 0) MapInk.Accent else MapInk.Neutral
        MapLens.SKIPS -> if (dot.plays <= 0) {
            MapInk.Neutral
        } else {
            warmRampInks[step(dot.skipRate.coerceIn(0f, 1f))]
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
     * Unplayed is a useful filter as soon as listening separates heard and unheard tracks, even
     * when every region is too small for a comparative headline. Plays and Skips remain gated by
     * enough events, and Skips still needs a region with enough played tracks for its comparison.
     */
    fun availableLenses(
        listening: LibraryListening,
        minEvents: Int = MIN_EVENTS_FOR_STATS,
    ): List<MapLens> {
        val plays = listening.regions.sumOf { it.plays }
        return buildList {
            add(MapLens.WORLDS)
            if (plays >= minEvents) add(MapLens.PLAYS)
            if (plays > 0 && listening.neverPlayed > 0) add(MapLens.NEVER_PLAYED)
            if (plays >= minEvents && listening.skippiestRegion != null) add(MapLens.SKIPS)
        }
    }

    /** Buckets a fraction already clamped to 0f..1f into 0 until [RAMP_STEPS]. */
    private fun step(fraction: Float): Int =
        (fraction * RAMP_STEPS).toInt().coerceIn(0, RAMP_STEPS - 1)
}

/**
 * A small spatial index for a static map page. Queries keep original dot order, so overlapping
 * marks retain their original paint order. Pan frames within the same cells reuse one index array;
 * full overview frames use the original array without sorting or collecting anything.
 */
internal class MapDotIndex(dots: List<MapDot>) {
    private val offsets = IntArray(GRID_SIDE * GRID_SIDE + 1)
    private val entries: IntArray
    private val all: IntArray
    private var cachedLeft = -1
    private var cachedTop = -1
    private var cachedRight = -1
    private var cachedBottom = -1
    private var cachedIndices = IntArray(0)

    init {
        val valid = dots.indices.filter { dots[it].x.isFinite() && dots[it].y.isFinite() }
        all = valid.toIntArray()
        for (index in valid) offsets[bucket(dots[index]) + 1]++
        for (index in 1 until offsets.size) offsets[index] += offsets[index - 1]
        entries = IntArray(valid.size)
        val next = offsets.copyOf()
        for (index in valid) entries[next[bucket(dots[index])]++] = index
    }

    /** Candidate indices; the renderer applies its exact circle-edge clipping afterwards. */
    fun visibleIndices(
        width: Float,
        height: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
        paddingPx: Float,
    ): IntArray {
        if (width <= 0f || height <= 0f || zoom <= 0f) return EMPTY
        val left = cell((-panX - paddingPx) / (width * zoom))
        val top = cell((-panY - paddingPx) / (height * zoom))
        val right = cell((width - panX + paddingPx) / (width * zoom))
        val bottom = cell((height - panY + paddingPx) / (height * zoom))
        if (left == 0 && top == 0 && right == GRID_SIDE - 1 && bottom == GRID_SIDE - 1) return all
        if (left == cachedLeft && top == cachedTop && right == cachedRight && bottom == cachedBottom) {
            return cachedIndices
        }

        var count = 0
        for (row in top..bottom) {
            val start = row * GRID_SIDE + left
            val end = row * GRID_SIDE + right + 1
            count += offsets[end] - offsets[start]
        }
        val visible = IntArray(count)
        var next = 0
        for (row in top..bottom) {
            val start = offsets[row * GRID_SIDE + left]
            val end = offsets[row * GRID_SIDE + right + 1]
            for (entry in start until end) visible[next++] = entries[entry]
        }
        visible.sort()
        cachedLeft = left
        cachedTop = top
        cachedRight = right
        cachedBottom = bottom
        cachedIndices = visible
        return visible
    }

    private fun bucket(dot: MapDot): Int = cell(dot.y) * GRID_SIDE + cell(dot.x)

    private fun cell(value: Float): Int = (value * GRID_SIDE).toInt().coerceIn(0, GRID_SIDE - 1)

    private companion object {
        const val GRID_SIDE = 16
        val EMPTY = IntArray(0)
    }
}
