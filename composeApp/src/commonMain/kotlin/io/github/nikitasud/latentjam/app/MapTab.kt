/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.count_regions
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.map_action_play_region
import io.github.nikitasud.latentjam.app.generated.resources.map_action_smart_here
import io.github.nikitasud.latentjam.app.generated.resources.map_empty_indexing
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_never_played
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_plays
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_skips
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_worlds
import io.github.nikitasud.latentjam.app.generated.resources.map_lens_never_played
import io.github.nikitasud.latentjam.app.generated.resources.map_lens_plays
import io.github.nikitasud.latentjam.app.generated.resources.map_lens_skips
import io.github.nikitasud.latentjam.app.generated.resources.map_lens_worlds
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_always_skips
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_never
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_never_skips
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_played
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_rest
import io.github.nikitasud.latentjam.app.generated.resources.map_legend_selected
import io.github.nikitasud.latentjam.history.LibraryListening
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Everything the Map draws, assembled once per visit.
 *
 * @property regionNames One name per region, indexed exactly like [MapDot.region] and
 *   [LibraryListening.regions] — `regionNames[i]` names the region whose id is `i`. This file never
 *   generates these names (Task 10 does, from the region's dominant
 *   [io.github.nikitasud.latentjam.smart.cluster.LibraryWorld]); it only drops one into the
 *   `%3$s` / `%1$s` of `map_headline_never_played` / `map_headline_skips`, so that string's
 *   contract is this property's contract too:
 *   - a short noun phrase naming the region — its dominant genre or artist — never a bare number
 *   - never containing the word "region" itself: eight locales already prefix their own head noun
 *     (`Область «%3$s»`) and four more inflect feminine agreement around it
 *   - supplied UNQUOTED: 17 of the 18 locale files wrap it in their own quotation marks
 *     (`«%3$s»`, `„%3$s”`, `「%3$s」`), so a name that arrives pre-quoted would be double-quoted
 *     everywhere except English
 */
data class MapPage(
    val dots: List<MapDot>,
    val regionNames: List<String>,
    val listening: LibraryListening,
)

/**
 * The library as a place.
 *
 * Positions come from [io.github.nikitasud.latentjam.smart.cluster.LibraryLayout] and colours from
 * [MapLenses]; this file only draws. The page is assembled once per visit and does not re-rank
 * underneath the reader — the whole value is a stable shape you learn.
 */
@Composable
fun MapTab(
    page: MapPage?,
    contentPadding: PaddingValues,
    onPlayRegion: (Int) -> Unit,
    onSmartFromRegion: (Int) -> Unit,
    onOpenTrack: (TrackId) -> Unit,
) {
    if (page == null || page.dots.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.map_empty_indexing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val lenses = remember(page.listening) { MapLenses.availableLenses(page.listening) }
    var lens by remember(page) { mutableStateOf(MapLens.WORLDS) }
    var selectedRegion by remember(page) { mutableIntStateOf(largestRegion(page)) }
    var zoom by remember(page) { mutableFloatStateOf(1f) }
    var panX by remember(page) { mutableFloatStateOf(0f) }
    var panY by remember(page) { mutableFloatStateOf(0f) }

    val neutral = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val coolRamp = rememberCoolRamp()
    val warmRamp = rememberWarmRamp()
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val centroids = remember(page) { largestRegionCentroids(page, limit = 5) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (candidate in lenses) {
                FilterChip(
                    selected = lens == candidate,
                    onClick = { lens = candidate },
                    label = { Text(stringResource(lensLabel(candidate))) },
                )
            }
        }

        Text(
            text = headline(lens, page),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(page) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                        panX += pan.x
                        panY += pan.y
                    }
                }
                // Only keyed on identity/lens, not on zoom or pan: those are read fresh from their
                // MutableState at tap time regardless, and re-keying on them meant this detector
                // was torn down and relaunched on every frame of an in-flight pinch or drag.
                .pointerInput(page, lens) {
                    detectTapGestures(
                        onTap = { offset ->
                            nearest(
                                page.dots, offset, size.width, size.height, zoom, panX, panY,
                                HIT_RADIUS_DP.toPx(),
                            )?.let { selectedRegion = it.region }
                        },
                        onLongPress = { offset ->
                            nearest(
                                page.dots, offset, size.width, size.height, zoom, panX, panY,
                                HIT_RADIUS_DP.toPx(),
                            )?.let { onOpenTrack(it.trackId) }
                        },
                    )
                },
        ) {
            for (dot in page.dots) {
                val ink = MapLenses.ink(lens, dot, selectedRegion, page.listening.maxPlays)
                drawCircle(
                    color = when (ink) {
                        MapInk.Neutral -> neutral
                        MapInk.Accent -> accent
                        is MapInk.Ramp -> coolRamp[ink.step]
                        is MapInk.WarmRamp -> warmRamp[ink.step]
                    },
                    radius = MapLenses.radius(lens, dot, selectedRegion) * zoom,
                    center = screenPosition(dot, size.width, size.height, zoom, panX, panY),
                )
            }
            // Spec section 5: regions are named on the map, because colour cannot carry identity
            // here. Only the five largest are labelled — past that the labels collide and the map
            // becomes a word cloud.
            for ((region, centre) in centroids) {
                val name = page.regionNames.getOrNull(region) ?: continue
                val measured = measurer.measure(name, labelStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(
                        x = centre.x * size.width * zoom + panX - measured.size.width / 2f,
                        y = centre.y * size.height * zoom + panY - measured.size.height / 2f,
                    ),
                )
            }
        }

        MapLegendRow(MapLenses.legend(lens), neutral, accent, coolRamp, warmRamp)

        val region = page.listening.regions.getOrNull(selectedRegion)
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = page.regionNames.getOrElse(selectedRegion) { "" },
                style = MaterialTheme.typography.titleMedium,
            )
            if (region != null) {
                Text(
                    // The existing library-wide track-count plural, already translated everywhere.
                    text = pluralStringResource(
                        Res.plurals.count_tracks,
                        region.trackCount,
                        region.trackCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onPlayRegion(selectedRegion) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(Res.string.map_action_play_region)) }
                OutlinedButton(
                    onClick = { onSmartFromRegion(selectedRegion) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(Res.string.map_action_smart_here)) }
            }
        }
    }
}

/**
 * The legend for the active lens.
 *
 * Every lens ships one: a sequential ramp with no scale is decoration, and the never-played lens
 * needs its key stated because size and colour together carry a meaning neither states alone.
 * Sequential legends are discrete swatches rather than a gradient — six steps, matching the six the
 * map draws.
 */
@Composable
private fun MapLegendRow(
    legend: MapLegend,
    neutral: Color,
    accent: Color,
    coolRamp: List<Color>,
    warmRamp: List<Color>,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (legend) {
            MapLegend.REGION_SELECTION -> {
                Swatch(accent)
                LegendLabel(stringResource(Res.string.map_legend_selected))
                Swatch(neutral)
                LegendLabel(stringResource(Res.string.map_legend_rest))
            }
            MapLegend.NEVER_PLAYED_KEY -> {
                Swatch(accent)
                LegendLabel(stringResource(Res.string.map_legend_never))
                Swatch(neutral)
                LegendLabel(stringResource(Res.string.map_legend_played))
            }
            MapLegend.PLAY_RAMP -> {
                LegendLabel(stringResource(Res.string.map_legend_never))
                for (step in coolRamp) Swatch(step)
                LegendLabel(stringResource(Res.string.map_legend_played))
            }
            MapLegend.SKIP_RAMP -> {
                LegendLabel(stringResource(Res.string.map_legend_never_skips))
                for (step in warmRamp) Swatch(step)
                LegendLabel(stringResource(Res.string.map_legend_always_skips))
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(width = 13.dp, height = 9.dp).background(color, RoundedCornerShape(2.dp)))
}

@Composable
private fun LegendLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Centre of each of the [limit] largest regions, for the on-map labels.
 *
 * Pure and Compose-free by construction (it only touches [MapPage] and [Offset] arithmetic), so it
 * is exercised directly in `MapTabTest` rather than through a screenshot.
 */
internal fun largestRegionCentroids(page: MapPage, limit: Int): List<Pair<Int, Offset>> =
    page.dots.groupBy { it.region }
        .entries
        .sortedByDescending { it.value.size }
        .take(limit)
        .map { (region, members) ->
            region to Offset(
                x = members.sumOf { it.x.toDouble() }.toFloat() / members.size,
                y = members.sumOf { it.y.toDouble() }.toFloat() / members.size,
            )
        }

/**
 * The floor of both sequential ramps' alpha range.
 *
 * Not the naive 0 (fully transparent, i.e. background): [MapInk.Ramp] step 0 and [MapInk.Neutral]
 * (`outlineVariant`) both read as "a quiet grey near the surface" by design, and at a 0-anchored
 * ramp they become the *same* grey. Measured against this app's actual [NeutralLightColors] /
 * [NeutralDarkColors] (`primary` = 0x1F1F1F light / 0xE6E6E6 dark, `outlineVariant` = 0xCFCFCF
 * light / 0x3A3A3A dark, blended over a 0xFFFFFF / 0x000000 surface): the step-0 alpha this file
 * shipped with first, 0.18, blends to within 8 of 255 per channel of `outlineVariant` on the light
 * scheme (215 vs 207) and within 17 on the dark one (41 vs 58) — close enough that "barely played"
 * and "never played" are indistinguishable greys on the one lens whose entire job is to separate
 * them. Raising the floor to 0.40 widens that gap to roughly 40 and 34 respectively, which is
 * plainly visible on both schemes; 1.0 (full ink) is unaffected.
 */
private const val RAMP_FLOOR_ALPHA = 0.40f

@Composable
private fun rememberCoolRamp(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) { rampAlphas().map { scheme.primary.copy(alpha = it) } }
}

@Composable
private fun rememberWarmRamp(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) { rampAlphas().map { scheme.error.copy(alpha = it) } }
}

/** [MapLenses.RAMP_STEPS] alphas, evenly spaced from [RAMP_FLOOR_ALPHA] to fully opaque. */
private fun rampAlphas(): List<Float> {
    val steps = MapLenses.RAMP_STEPS
    return List(steps) { i -> RAMP_FLOOR_ALPHA + (1f - RAMP_FLOOR_ALPHA) * i / (steps - 1) }
}

private fun lensLabel(lens: MapLens) = when (lens) {
    MapLens.WORLDS -> Res.string.map_lens_worlds
    MapLens.PLAYS -> Res.string.map_lens_plays
    MapLens.NEVER_PLAYED -> Res.string.map_lens_never_played
    MapLens.SKIPS -> Res.string.map_lens_skips
}

@Composable
private fun headline(lens: MapLens, page: MapPage): String {
    val listening = page.listening
    return when (lens) {
        // %1$s / %2$s in map_headline_worlds are count_tracks / count_regions fragments, already
        // pluralized ("873 tracks", "12 regions") — the string's own comment forbids feeding it a
        // bare number here, because the noun that agrees with that number only exists inside the
        // plural resource.
        MapLens.WORLDS -> stringResource(
            Res.string.map_headline_worlds,
            pluralStringResource(
                Res.plurals.count_tracks,
                listening.trackCount,
                listening.trackCount,
            ),
            pluralStringResource(
                Res.plurals.count_regions,
                listening.regions.size,
                listening.regions.size,
            ),
        )
        // %1$s is the same count_tracks contract: "half your plays come from 12 tracks", not "12".
        MapLens.PLAYS -> stringResource(
            Res.string.map_headline_plays,
            pluralStringResource(
                Res.plurals.count_tracks,
                listening.tracksForHalfOfPlays,
                listening.tracksForHalfOfPlays,
            ),
            percent(listening.tracksForHalfOfPlays, listening.trackCount),
        )
        MapLens.NEVER_PLAYED -> {
            val darkest = listening.darkestRegion
            val region = listening.regions.getOrNull(darkest ?: -1)
            stringResource(
                Res.string.map_headline_never_played,
                listening.neverPlayed,
                // %2$s: "of 873 tracks", the count_tracks fragment again — %1$d stays a bare count
                // because unlike %2$s it has no noun of its own to agree with in this sentence.
                pluralStringResource(
                    Res.plurals.count_tracks,
                    listening.trackCount,
                    listening.trackCount,
                ),
                page.regionNames.getOrElse(darkest ?: -1) { "" },
                percent(region?.neverPlayed ?: 0, region?.trackCount ?: 1),
            )
        }
        MapLens.SKIPS -> {
            val skippiest = listening.skippiestRegion
            val region = listening.regions.getOrNull(skippiest ?: -1)
            stringResource(
                Res.string.map_headline_skips,
                page.regionNames.getOrElse(skippiest ?: -1) { "" },
                ((region?.skipRate ?: 0f) * 100f).roundToInt(),
            )
        }
    }
}

private fun percent(part: Int, whole: Int): Int =
    if (whole <= 0) 0 else (part * 100f / whole).roundToInt()

private fun largestRegion(page: MapPage): Int =
    page.listening.regions.maxByOrNull { it.trackCount }?.region ?: 0

/**
 * Where [dot] lands on screen, in the same zoomed/panned space for every reader of the map: the
 * `Canvas` draw loop (which centres each dot here) and [nearest] (which hit-tests against the same
 * point) both call this rather than each keeping its own copy of the arithmetic, so the two cannot
 * drift apart the way two independently-written copies could.
 */
internal fun screenPosition(
    dot: MapDot,
    width: Float,
    height: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
): Offset = Offset(
    x = dot.x * width * zoom + panX,
    y = dot.y * height * zoom + panY,
)

/**
 * Screen-space hit radius for a tap, sized to Material's ~48dp minimum touch target so a finger —
 * not the 2 px dot — sets the tap tolerance, consistently across every screen density.
 *
 * Expressed in dp rather than raw px: a raw-px radius is a different physical size on every device
 * (24 raw px, this file's previous value, is a generous target at 1x density but only ~8dp — well
 * under any touch-target guidance — at 3x). Converted to px at the call site via the pointerInput
 * block's own [androidx.compose.ui.unit.Density] so [nearest] itself stays a pure function over
 * plain numbers.
 */
internal val HIT_RADIUS_DP = 48.dp

/**
 * The dot nearest a tap, within [hitRadiusPx], in the same zoomed/panned space [MapTab] draws in
 * (via [screenPosition]).
 *
 * Pure: takes the transform and the hit radius as plain numbers rather than reading
 * [androidx.compose.ui.input.pointer] state or [androidx.compose.ui.unit.Density] directly, so
 * `MapTabTest` can pin down that this formula — the same one the `Canvas` draw loop uses — resolves
 * a tap correctly, without going through Compose UI at all.
 */
internal fun nearest(
    dots: List<MapDot>,
    tap: Offset,
    width: Int,
    height: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
    hitRadiusPx: Float,
): MapDot? {
    var best: MapDot? = null
    var bestDistance = Float.MAX_VALUE
    for (dot in dots) {
        val position = screenPosition(dot, width.toFloat(), height.toFloat(), zoom, panX, panY)
        val dx = position.x - tap.x
        val dy = position.y - tap.y
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = dot
        }
    }
    return best.takeIf { bestDistance < hitRadiusPx * hitRadiusPx }
}
