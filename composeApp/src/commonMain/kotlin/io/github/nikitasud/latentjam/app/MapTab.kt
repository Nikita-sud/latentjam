/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.count_regions
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.foryou_world_open
import io.github.nikitasud.latentjam.app.generated.resources.map_action_play_region
import io.github.nikitasud.latentjam.app.generated.resources.map_action_play_unheard
import io.github.nikitasud.latentjam.app.generated.resources.map_select_region
import io.github.nikitasud.latentjam.app.generated.resources.map_gesture_hint
import io.github.nikitasud.latentjam.app.generated.resources.map_fullscreen
import io.github.nikitasud.latentjam.app.generated.resources.map_exit_fullscreen
import io.github.nikitasud.latentjam.app.generated.resources.map_reset_view
import io.github.nikitasud.latentjam.app.generated.resources.map_region_unplayed
import io.github.nikitasud.latentjam.app.generated.resources.map_action_smart_here
import io.github.nikitasud.latentjam.app.generated.resources.map_empty_building
import io.github.nikitasud.latentjam.app.generated.resources.map_empty_indexing
import io.github.nikitasud.latentjam.app.generated.resources.map_empty_too_large
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_never_played
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_unplayed_total
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_plays
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_skips
import io.github.nikitasud.latentjam.app.generated.resources.map_headline_worlds_partial
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
import io.github.nikitasud.latentjam.app.generated.resources.tab_map
import io.github.nikitasud.latentjam.history.LibraryListening
import io.github.nikitasud.latentjam.history.RegionListening
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorld
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldNameSource
import io.github.nikitasud.latentjam.smart.cluster.LibraryWorldSemanticTitle
import kotlin.math.max
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Everything the Map draws, cached until geometry, region labels or listening facts change.
 *
 * @property regionNames One name per region, indexed exactly like [MapDot.region] and
 *   [LibraryListening.regions] — `regionNames[i]` names the region whose id is `i`. Built by
 *   [regionDisplayNames] from each region's
 *   [io.github.nikitasud.latentjam.smart.cluster.LibraryWorld]; these are dropped into the
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
) {
    // The page cache retains these across tab returns along with the data they index.
    internal val dotIndex by lazy { MapDotIndex(dots) }
    internal val centroids by lazy { largestRegionCentroids(this, limit = Int.MAX_VALUE) }
}

/** Per-dot facts that change with a lens/selection, but never while the map is panned or zoomed. */
private class MapDotVisuals(
    val paletteIndices: IntArray,
    val radiiPx: FloatArray,
) {
    val maxRadiusPx: Float = radiiPx.maxOrNull() ?: 0f
}

/**
 * Everything the Map tab can be showing instead of a drawn [MapPage], named honestly rather than
 * folded into a single nullable `MapPage?` -- the final-review finding this fixes is exactly that
 * collapsing "indexing incomplete", "computing the layout right now", and "refusing to draw a
 * library this large" into one null all read the same *wrong* string ("Still reading your
 * library…") to someone whose indexing finished minutes ago.
 */
sealed interface MapPageState {
    /** No usable vector space yet: indexing has not produced one, or the tab has not been visited. */
    data object Indexing : MapPageState

    /**
     * Indexing is done and a vector space exists, but [io.github.nikitasud.latentjam.smart.cluster.LibraryLayout]
     * has not finished computing positions for it yet. The only state where real, possibly slow CPU
     * work (PCA + t-SNE, `O(n^2)` per t-SNE iteration) is actually running -- every other state is
     * effectively instant.
     */
    data object Building : MapPageState

    /**
     * The library has more laid-out tracks than
     * [io.github.nikitasud.latentjam.smart.cluster.LibraryLayout.MAX_TRACKS]. The map refuses to
     * draw a silently-truncated picture that would look complete but is not, rather than lie about
     * coverage -- see that constant's doc for why the ceiling exists and where it sits.
     */
    data class TooLarge(val trackCount: Int, val limit: Int) : MapPageState

    /**
     * A page is drawn. `App.kt`'s assembly effect never constructs this with an empty
     * [MapPage.dots] -- see the comment at its one call site -- so [page] is guaranteed non-empty
     * by construction, not merely by convention.
     *
     * @property rebuilding Final review finding (MINOR 1): a library change (e.g. one album added)
     *   used to blank a warm, already-drawn map to [Building]'s placeholder for the whole recompute,
     *   even though the *previous* page was still perfectly good to look at in the meantime. When
     *   true, [page] is that stale-but-still-correct page -- kept on screen while a fresh one
     *   computes underneath it -- rather than nothing. Only the very first build for a library,
     *   when there is no previous page to fall back to, ever shows [Building] instead.
     */
    data class Ready(val page: MapPage, val rebuilding: Boolean = false) : MapPageState
}

/**
 * Removes a transient build marker inherited from a cancelled/restarted assembly effect.
 *
 * The Map effect publishes [MapPageState.Building] or `Ready(rebuilding = true)` before it crosses
 * the suspending vector/layout boundary. Any key change can cancel it there. The replacement effect
 * must start from a stable state, otherwise an early precondition return (index not ready, no
 * regions, user left the tab) leaves a spinner latched forever.
 */
internal fun MapPageState.afterInterruptedMapBuild(): MapPageState = when (this) {
    MapPageState.Building -> MapPageState.Indexing
    is MapPageState.Ready -> if (rebuilding) copy(rebuilding = false) else this
    else -> this
}

/** The transient state shown while a new layout is being computed from [this] stable state. */
internal fun MapPageState.duringMapBuild(): MapPageState = when (this) {
    is MapPageState.Ready -> copy(rebuilding = true)
    else -> MapPageState.Building
}

/**
 * The library as a place.
 *
 * Positions come from [io.github.nikitasud.latentjam.smart.cluster.LibraryLayout] and colours from
 * [MapLenses]; this file only draws. The page is assembled once per visit and does not re-rank
 * underneath the reader — the whole value is a stable shape you learn.
 */
@Composable
fun MapTab(
    state: MapPageState,
    contentPadding: PaddingValues,
    currentAccent: Color? = null,
    /** A track to spotlight: its region gets selected and its dot wears a ring. */
    focusTrackId: TrackId? = null,
    onPlayRegion: (Int) -> Unit,
    onPlayUnheardRegion: (Int) -> Unit,
    onOpenRegion: (Int) -> Unit,
    onSmartFromRegion: (Int) -> Unit,
    onOpenTrack: (TrackId) -> Unit,
) {
    val page = (state as? MapPageState.Ready)?.page
    if (page == null || page.dots.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            val message = when (state) {
                is MapPageState.Building -> stringResource(Res.string.map_empty_building)
                is MapPageState.TooLarge -> stringResource(
                    Res.string.map_empty_too_large,
                    pluralStringResource(Res.plurals.count_tracks, state.trackCount, state.trackCount),
                    pluralStringResource(Res.plurals.count_tracks, state.limit, state.limit),
                )
                MapPageState.Indexing -> stringResource(Res.string.map_empty_indexing)
                // Final review finding (MINOR 3): unreachable in practice -- App.kt's assembly
                // effect never constructs a Ready state with an empty page (see
                // MapPageState.Ready's doc) -- but the `when` above must still stay exhaustive over
                // all four MapPageState cases. Reusing map_empty_indexing's "still reading your
                // library" copy here, as this branch used to, would be exactly the kind of lie this
                // review round exists to remove: a Ready state is proof indexing already finished.
                // Since this case cannot happen, it says nothing instead of saying something false.
                is MapPageState.Ready -> null
            }
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    // `page` is only non-null when `state` is Ready (see the assignment above), which the compiler
    // tracks well enough to smart-cast `state` here without an explicit cast.
    val rebuilding = state.rebuilding
    val reduceMotion = rememberReduceMotion()

    val lenses = remember(page.listening) { MapLenses.availableLenses(page.listening) }
    var savedLens by rememberSaveable { mutableStateOf(MapLens.WORLDS.name) }
    val lens = MapLens.entries.firstOrNull { it.name == savedLens && it in lenses } ?: MapLens.WORLDS
    var savedRegion by rememberSaveable { mutableIntStateOf(largestRegion(page)) }
    var selectionAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedRegion = remember(page.dots, page.listening.regions, savedRegion, selectionAnchor) {
        page.dots.firstOrNull { it.trackId.value == selectionAnchor && it.claimed }?.region
            ?: savedRegion.takeIf { id -> page.listening.regions.any { it.region == id } }
            ?: largestRegion(page)
    }
    val selectRegion: (Int) -> Unit = { region ->
        savedRegion = region
        selectionAnchor = page.dots.firstOrNull { it.region == region }?.trackId?.value
    }
    // "Show on the Map" arrives as a track id; the dot answers where that track lives.
    val focusedDot = remember(page, focusTrackId) {
        focusTrackId?.let { id -> page.dots.firstOrNull { it.trackId == id } }
    }
    LaunchedEffect(focusedDot?.trackId) {
        focusedDot?.takeIf(MapDot::claimed)?.let { selectRegion(it.region) }
    }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    var panY by rememberSaveable { mutableFloatStateOf(0f) }

    // Outline colours disappear as tiny points. Use readable secondary ink in either theme;
    // the selected region keeps the current song's accent and a larger mark.
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f)
    val accent = currentAccent ?: MaterialTheme.colorScheme.primary
    val coolRamp = remember(accent) { rampAlphas().map { accent.copy(alpha = it) } }
    val warmRamp = rememberWarmRamp()
    // A song-color transition changes only this small palette. Lens classification and point
    // sizing below stay cached for the whole library instead of rebuilding on every color frame.
    val dotPalette = remember(neutral, accent, coolRamp, warmRamp) {
        listOf(neutral, accent) + coolRamp + warmRamp
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )
    val density = LocalDensity.current
    val labelSurface = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
    val selectedLabelSurface = accent.copy(
        alpha = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.22f else 0.10f,
    )
    val dotIndex = page.dotIndex
    val centroids = page.centroids
    // Measure all names once. Placement changes with the viewport, so smaller regions become
    // labelled as zoom makes room; the current selection gets first claim on the available space.
    val measuredCentroidLabels = remember(page.regionNames, centroids, labelStyle, measurer, density) {
        centroids.mapNotNull { (region, centre) ->
            page.regionNames.getOrNull(region)?.let { name ->
                region to measurer.measure(
                    name, labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = with(density) { 210.dp.roundToPx() }),
                )
            }
        }.toMap()
    }
    val labelAnchors = remember(centroids, measuredCentroidLabels, selectedRegion, density) {
        centroids.mapNotNull { (region, centre) ->
            measuredCentroidLabels[region]?.let { measured ->
                MapLabelAnchor(
                    region, centre,
                    measured.size.width + with(density) { 10.dp.toPx() },
                    measured.size.height + with(density) { 4.dp.toPx() },
                )
            }
        }.sortedBy { if (it.region == selectedRegion) 0 else 1 }
    }
    val mapDescription = stringResource(Res.string.tab_map)
    val selectedRegionDescription = page.regionNames.getOrNull(selectedRegion).orEmpty()
    // A Canvas otherwise collapses to one opaque, gesture-only accessibility node: TalkBack can
    // announce it, but cannot synthesize a tap at a particular dot. Expose the same finite set of
    // region choices as named custom actions, without adding a second visible control strip to an
    // already height-constrained map. The selected region's name is the state description below,
    // so invoking an action gives immediate spoken confirmation as the panel and semantics update.
    val currentSelectRegion by rememberUpdatedState(selectRegion)
    val currentOpenTrack by rememberUpdatedState<(TrackId) -> Unit> { id ->
        fullscreen = false
        onOpenTrack(id)
    }
    val regionActions = remember(page.regionNames, page.listening.regions) {
        regionAccessibilityActions(page) { region -> currentSelectRegion(region) }
    }

    // Saved in the pager's state holder; returning from tracks/settings keeps the chosen lens
    // and viewport. Pan is a fraction of the plot, so rotation preserves the viewed coordinates.
    val pageScrollState = rememberScrollState()
    val sideScrollState = rememberScrollState()
    val headlines = lenses.associateWith { headline(it, page) }
    val headlineStyle = MaterialTheme.typography.bodyMedium
        val dotVisuals = remember(page, lens, selectedRegion, density.density) {
            val paletteIndices = IntArray(page.dots.size)
            val radiiPx = FloatArray(page.dots.size)
            // A small library should still read as a map. Dense libraries retain their existing
            // point sizes and overlap characteristics; zoom continues to separate those points.
            val sizeScale = if (page.dots.size < 80) 1.45f else 1f
            page.dots.forEachIndexed { index, dot ->
                val ink = MapLenses.ink(
                    lens = lens,
                    dot = dot,
                    selectedRegion = selectedRegion,
                    maxPlays = page.listening.maxPlays,
                )
                paletteIndices[index] = when (ink) {
                    MapInk.Neutral -> 0
                    MapInk.Accent -> 1
                    is MapInk.Ramp -> 2 + ink.step
                    is MapInk.WarmRamp -> 2 + MapLenses.RAMP_STEPS + ink.step
                }
                radiiPx[index] = with(density) {
                    (MapLenses.radius(lens, dot, selectedRegion) * sizeScale).dp.toPx()
                }
            }
            MapDotVisuals(paletteIndices, radiiPx)
        }
    val header: @Composable () -> Unit = {
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            // Measured at the width the Text actually gets, with the indicator's slot subtracted
            // whether or not it is showing (see the Row below) -- a reservation measured at some
            // other width is not a reservation.
            val textWidthPx = with(density) {
                (maxWidth - MAP_HEADER_CONTROL_DP - 8.dp).coerceAtLeast(0.dp).roundToPx()
            }
            val reservedHeight = remember(headlines, textWidthPx, headlineStyle, measurer) {
                headlines.values.maxOfOrNull { text ->
                    measurer.measure(
                        text = text,
                        style = headlineStyle,
                        constraints = Constraints(maxWidth = textWidthPx),
                    ).size.height
                } ?: 0
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = headlines[lens].orEmpty(),
                    style = headlineStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = with(density) { reservedHeight.toDp() }),
                )
                // Keep controls outside the plot: a reset button over its top-right corner hid
                // edge labels and stole the tap that should select their region.
                Box(Modifier.width(MAP_HEADER_CONTROL_DP).height(48.dp), contentAlignment = Alignment.Center) {
                    Row {
                        IconButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }) {
                            Icon(Icons.Rounded.RestartAlt, stringResource(Res.string.map_reset_view))
                        }
                        IconButton(onClick = { fullscreen = !fullscreen }) {
                            Icon(
                                if (fullscreen) Icons.Rounded.Close else Icons.Rounded.Fullscreen,
                                stringResource(if (fullscreen) Res.string.map_exit_fullscreen
                                    else Res.string.map_fullscreen),
                            )
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = rebuilding,
                        modifier = Modifier.align(Alignment.TopEnd),
                        enter = fadeIn(tween(
                            if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS,
                        )),
                        exit = fadeOut(tween(
                            if (reduceMotion) Motion.REDUCED_MS else Motion.REPLACE_MS,
                        )),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    }
                }

            }
        }

        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (candidate in lenses) {
                FilterChip(
                    selected = lens == candidate,
                    onClick = { savedLens = candidate.name },
                    shape = RoundedCornerShape(17.dp),
                    modifier = Modifier.heightIn(min = 34.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                        selectedLabelColor = MaterialTheme.colorScheme.surface,
                    ),
                    label = {
                        Text(
                            text = stringResource(lensLabel(candidate)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

    }
    val plot: @Composable (Modifier, Boolean) -> Unit = { plotModifier, singleFingerPan ->
        Canvas(
            modifier = plotModifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                // Keep dots at normalized 0/1 inside the rounded plot. Drawing and hit testing
                // receive the same inset size, so their shared projection stays unchanged.
                .padding(12.dp)
                .semantics {
                    contentDescription = mapDescription
                    if (selectedRegionDescription.isNotBlank()) {
                        stateDescription = selectedRegionDescription
                    }
                    customActions = regionActions
                }
                .pointerInput(singleFingerPan) {
                    val transform: (Offset, Offset, Float) -> Unit = { centroid, pan, gestureZoom ->
                        val transformed = transformMapViewport(
                            MapViewport(zoom, panX * size.width, panY * size.height), centroid, pan, gestureZoom,
                            size.width.toFloat(), size.height.toFloat(),
                        )
                        zoom = transformed.zoom
                        panX = transformed.panX / size.width.coerceAtLeast(1)
                        panY = transformed.panY / size.height.coerceAtLeast(1)
                    }
                    if (singleFingerPan) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            transform(centroid, pan, gestureZoom)
                        }
                    } else {
                        detectMapTransformGestures(transform)
                    }
                }

                // Only keyed on identity/lens, not on zoom or pan: those are read fresh from their
                // MutableState at tap time regardless, and re-keying on them meant this detector
                // was torn down and relaunched on every frame of an in-flight pinch or drag.
                .pointerInput(page, lens, labelAnchors) {
                    detectTapGestures(
                        onTap = { offset ->
                            val labelRegion = mapLabelPlacements(
                                labelAnchors, size.width.toFloat(), size.height.toFloat(),
                                zoom, panX * size.width, panY * size.height, LABEL_EDGE_MARGIN_DP.toPx(),
                            ).firstOrNull { it.bounds.contains(offset) }?.region
                            val dotRegion = if (labelRegion == null) nearest(
                                page.dots, offset, size.width, size.height, zoom, panX * size.width, panY * size.height,
                                HIT_RADIUS_DP.toPx(),
                            )?.takeIf(MapDot::claimed)?.region else null
                            (labelRegion ?: dotRegion)?.let { currentSelectRegion(it) }
                        },
                        onLongPress = { offset ->
                            nearest(
                                page.dots, offset, size.width, size.height, zoom, panX * size.width, panY * size.height,
                                HIT_RADIUS_DP.toPx(),
                            )?.let { currentOpenTrack(it.trackId) }
                        },
                    )
                },
        ) {
            // Snapshot reads register a draw dependency. Read the viewport once per frame,
            // rather than repeating those reads for every visible dot and label.
            val frameZoom = zoom
            val framePanX = panX * size.width
            val framePanY = panY * size.height
            for (index in dotIndex.visibleIndices(
                size.width, size.height, frameZoom, framePanX, framePanY, dotVisuals.maxRadiusPx,
            )) {
                val dot = page.dots[index]
                val radius = dotVisuals.radiiPx[index]
                val centre = screenPosition(dot, size.width, size.height, frameZoom, framePanX, framePanY)
                // At 6x zoom most of the map is outside the viewport. Canvas clips those circles
                // eventually, but issuing thousands of invisible draw calls still costs a frame.
                if (
                    centre.x + radius < 0f || centre.x - radius > size.width ||
                    centre.y + radius < 0f || centre.y - radius > size.height
                ) {
                    continue
                }
                drawCircle(
                    color = dotPalette[dotVisuals.paletteIndices[index]],
                    // MapLenses supplies unitless dp magnitudes (same contract as HIT_RADIUS_DP), so
                    // this DrawScope -- itself a Density -- converts to px, exactly as the
                    // pointerInput block above does for the hit radius. Screen-constant, not scaled
                    // by zoom: dot spacing (screenPosition, below) already scales with zoom, so a
                    // radius that scaled too would keep every dot's share of the canvas fixed at any
                    // zoom level -- a pure magnification that never reduces overlap, contradicting
                    // spec section 6's reason pinch-zoom is required at all ("~33% of dots overlap
                    // another dot" at rest). Holding the mark size constant on screen is the usual
                    // convention for a zoomable scatter, and it is what actually lets zooming in
                    // separate two dots that started on top of each other.
                    radius = radius,
                    center = centre,
                )
            }
            // The spotlighted track wears a ring: a filled dot would just join the crowd.
            focusedDot?.let { dot ->
                drawCircle(
                    color = accent,
                    radius = (MapLenses.radius(lens, dot, selectedRegion) + 5f).dp.toPx(),
                    center = screenPosition(dot, size.width, size.height, frameZoom, framePanX, framePanY),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            for (label in mapLabelPlacements(
                labelAnchors, size.width, size.height, frameZoom, framePanX, framePanY,
                LABEL_EDGE_MARGIN_DP.toPx(),
            )) {
                val bounds = label.bounds
                val selected = label.region == selectedRegion
                drawRoundRect(
                    color = labelSurface,
                    topLeft = bounds.topLeft,
                    size = bounds.size,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
                if (selected) drawRoundRect(
                    color = selectedLabelSurface,
                    topLeft = bounds.topLeft,
                    size = bounds.size,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
                if (selected) drawRoundRect(
                    color = accent.copy(alpha = 0.72f),
                    topLeft = bounds.topLeft,
                    size = bounds.size,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(1.25.dp.toPx()),
                )
                drawText(
                    textLayoutResult = measuredCentroidLabels.getValue(label.region),
                    color = if (selected) accent else labelStyle.color,
                    topLeft = bounds.topLeft + Offset(5.dp.toPx(), 2.dp.toPx()),
                )
            }
        }

        // The selected label and the region panel identify Worlds directly. Quantitative lenses
        // retain their key, because colour there represents listening data rather than selection.
    }
    val details: @Composable () -> Unit = {
        Text(
            stringResource(Res.string.map_gesture_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        StableMapLegend(MapLenses.legend(lens), neutral, accent, coolRamp, warmRamp)

        val region = page.listening.regions.firstOrNull { it.region == selectedRegion }
        var regionMenuOpen by remember { mutableStateOf(false) }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
        ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    val selectDescription = stringResource(Res.string.map_select_region)
                    TextButton(
                        onClick = { regionMenuOpen = true },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.semantics {
                            contentDescription = selectDescription
                            stateDescription = selectedRegionDescription
                        },
                    ) {
                        Text(
                            text = page.regionNames.getOrElse(selectedRegion) { "" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = regionMenuOpen,
                        onDismissRequest = { regionMenuOpen = false },
                        modifier = Modifier.heightIn(max = 360.dp),
                    ) {
                        for (candidate in page.listening.regions) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(page.regionNames.getOrElse(candidate.region) { "" })
                                        Text(
                                            pluralStringResource(Res.plurals.count_tracks,
                                                candidate.trackCount, candidate.trackCount),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    selectRegion(candidate.region)
                                    regionMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            if (region != null) {
                Text(
                    text = buildList {
                        add(pluralStringResource(Res.plurals.count_tracks,
                            region.trackCount, region.trackCount))
                        if (region.neverPlayed > 0) {
                            add(stringResource(Res.string.map_region_unplayed, region.neverPlayed))
                        }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2,
            ) {
                Button(
                    onClick = {
                        if (lens == MapLens.NEVER_PLAYED) onPlayUnheardRegion(selectedRegion)
                        else onPlayRegion(selectedRegion)
                    },
                    enabled = region != null &&
                        (lens != MapLens.NEVER_PLAYED || region.neverPlayed > 0),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(if (lens == MapLens.NEVER_PLAYED)
                            Res.string.map_action_play_unheard else Res.string.map_action_play_region),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                FilledTonalButton(
                    onClick = { fullscreen = false; onOpenRegion(selectedRegion) },
                    enabled = region != null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(stringResource(Res.string.foryou_world_open),
                        style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = { onSmartFromRegion(selectedRegion) },
                    enabled = region != null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
                ) {
                    Text(stringResource(Res.string.map_action_smart_here),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        }
    }
    val content: @Composable (Boolean) -> Unit = { expanded ->
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .padding(if (expanded) PaddingValues(0.dp) else contentPadding)
            .then(if (expanded) Modifier.safeDrawingPadding() else Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))),
    ) {
        val windowWidth = maxWidth.value
        if (mapUsesSidePanel(windowWidth, maxHeight.value)) {
            Row(Modifier.fillMaxSize()) {
                plot(Modifier.weight(1f).fillMaxSize(), expanded)
                Column(Modifier.width(mapSidePanelWidth(windowWidth, density.fontScale).dp)) {
                    if (expanded) header()
                    Column(
                        Modifier.weight(1f)
                            .fadingListTop { sideScrollState.canScrollBackward }
                            .verticalScroll(sideScrollState),
                    ) {
                        if (!expanded) header()
                        details()
                    }
                }
            }
        } else if (expanded && density.fontScale < 1.5f && maxHeight >= 440.dp) {
            Column(Modifier.fillMaxSize()) {
                header()
                plot(Modifier.weight(1f).fillMaxWidth(), true)
                StableMapLegend(MapLenses.legend(lens), neutral, accent, coolRamp, warmRamp)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        page.regionNames.getOrElse(selectedRegion) { "" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { fullscreen = false; onOpenRegion(selectedRegion) }) {
                        Text(stringResource(Res.string.foryou_world_open))
                    }
                }
            }
        } else {
            // Short/narrow windows and enlarged text retain an accessible scrolling layout.
            Column(Modifier.fillMaxSize()) {
                if (expanded) header()
                Column(Modifier.weight(1f).verticalScroll(pageScrollState)) {
                    if (!expanded) header()
                    plot(Modifier.height(mapPortraitPlotHeight(windowWidth).dp), false)
                    details()
                }
            }
        }
    }
    }
    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = fullscreenDialogProperties(),
        ) {
            PlatformThemeEffect(darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f)
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                content(true)
            }
        }
    } else {
        content(false)
    }
}

/** Measure all small keys but place only the active one: even a translated, wrapping legend
 * cannot resize the full-screen plot. Unplaced children draw nothing and expose no semantics.
 */
@Composable
private fun StableMapLegend(
    legend: MapLegend,
    neutral: Color,
    accent: Color,
    coolRamp: List<Color>,
    warmRamp: List<Color>,
) {
    Box(Modifier.fillMaxWidth()) {
        for (candidate in MapLegend.entries) {
            Box(Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (candidate == legend) placeable.placeRelative(0, 0)
                }
            }) {
                MapLegendRow(candidate, neutral, accent, coolRamp, warmRamp)
            }
        }
    }
}

/**
 * The legend for the active lens.
 *
 * Quantitative lenses need a key: a sequential ramp with no scale is decoration, and never-played
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
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (legend) {
            MapLegend.REGION_SELECTION -> {
                LegendKey(accent, stringResource(Res.string.map_legend_selected))
                LegendKey(neutral, stringResource(Res.string.map_legend_rest))
            }
            MapLegend.NEVER_PLAYED_KEY -> {
                LegendKey(accent, stringResource(Res.string.map_legend_never))
                LegendKey(neutral, stringResource(Res.string.map_legend_played))
            }
            MapLegend.PLAY_RAMP -> {
                LegendLabel(stringResource(Res.string.map_legend_never))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.align(Alignment.CenterVertically)) {
                    for (step in coolRamp) Swatch(step)
                }
                LegendLabel(stringResource(Res.string.map_legend_played))
            }
            MapLegend.SKIP_RAMP -> {
                LegendLabel(stringResource(Res.string.map_legend_never_skips))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.align(Alignment.CenterVertically)) {
                    for (step in warmRamp) Swatch(step)
                }
                LegendLabel(stringResource(Res.string.map_legend_always_skips))
            }
        }
    }
}

@Composable
private fun LegendKey(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Swatch(color)
        LegendLabel(label)
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
 * Unclaimed dots ([MapDot.NO_REGION]) are not a region and have no name: left in, they would group
 * into a pseudo-region that can outrank real ones, take one of the [limit] label slots, and then draw
 * nothing at all when `regionNames` has no entry for it.
 *
 * Pure and Compose-free by construction (it only touches [MapPage] and [Offset] arithmetic), so it
 * is exercised directly in `MapTabTest` rather than through a screenshot.
 */
internal fun largestRegionCentroids(page: MapPage, limit: Int): List<Pair<Int, Offset>> =
    page.dots.filter(MapDot::claimed)
        .groupBy { it.region }
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
 * TalkBack actions for the regions the page can actually select.
 *
 * A Compose [Canvas] has no child semantics for the dots it draws, so coordinate gestures alone
 * make region selection impossible without sight. These named actions expose the same choices on
 * the Canvas node. They follow [io.github.nikitasud.latentjam.history.LibraryListening.regions]
 * rather than every `regionNames` slot: only a region with listening/card data is an honest target
 * for the panel and its Play/SMART buttons. Malformed duplicate or blank entries are ignored rather
 * than creating indistinguishable accessibility actions.
 *
 * Kept outside the composable so the action callbacks themselves can be regression-tested; invoking
 * one must update the same selected-region state a pointer tap updates.
 */
internal fun regionAccessibilityActions(
    page: MapPage,
    onSelect: (Int) -> Unit,
): List<CustomAccessibilityAction> {
    val seen = mutableSetOf<Int>()
    return page.listening.regions.mapNotNull { regionListening ->
        val region = regionListening.region
        val name = page.regionNames.getOrNull(region)?.takeIf(String::isNotBlank)
        if (name == null || !seen.add(region)) {
            null
        } else {
            CustomAccessibilityAction(label = name) {
                onSelect(region)
                true
            }
        }
    }
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

/** Reset control and rebuild indicator share a constant slot to keep the plot height stable. */
private val MAP_HEADER_CONTROL_DP = 96.dp

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
        // Compact, localized count fragments keep the overview clear of instructions. Partial
        // coverage still uses its explanatory sentence so no unclaimed tracks disappear from it.
        MapLens.WORLDS -> {
            // `listening.trackCount` counts the tracks a region claimed (it is `regionOf.size`),
            // which is NOT the number of tracks on screen: unclaimed ones are drawn too (see
            // MapDot.NO_REGION), 90 of 877 on a real library. Reporting only the claimed count next
            // to a map that plainly shows more dots than that invites the obvious reading -- that
            // 787 is the size of the library -- and gets it wrong by a tenth. When the two differ,
            // say both; when every drawn track has a region, the shorter sentence is still exactly
            // true, so nothing changes for a library whose regions cover it. The `>` also means a
            // page with more claimed tracks than dots (region members that never got a position)
            // keeps the old sentence rather than printing "787 of 780".
            val drawn = page.dots.size
            val claimed = listening.trackCount
            val regions = pluralStringResource(
                Res.plurals.count_regions,
                listening.regions.size,
                listening.regions.size,
            )
            if (drawn > claimed) {
                stringResource(
                    Res.string.map_headline_worlds_partial,
                    claimed,
                    pluralStringResource(Res.plurals.count_tracks, drawn, drawn),
                    regions,
                )
            } else {
                pluralStringResource(Res.plurals.count_tracks, claimed, claimed) + " · " + regions
            }
        }
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
            val region = unplayedHeadlineRegion(page)
            val total = pluralStringResource(
                Res.plurals.count_tracks, listening.trackCount, listening.trackCount,
            )
            if (region == null) {
                stringResource(
                    Res.string.map_headline_unplayed_total, listening.neverPlayed, total,
                )
            } else {
                stringResource(
                    Res.string.map_headline_never_played,
                    listening.neverPlayed,
                    total,
                    page.regionNames[region.region],
                    percent(region.neverPlayed, region.trackCount),
                )
            }
        }
        MapLens.SKIPS -> {
            val skippiest = listening.skippiestRegion
            val region = listening.regions.firstOrNull { it.region == skippiest }
            stringResource(
                Res.string.map_headline_skips,
                page.regionNames.getOrElse(skippiest ?: -1) { "" },
                ((region?.skipRate ?: 0f) * 100f).roundToInt(),
            )
        }
    }
}

/** A regional comparison is optional; filtering unplayed tracks never needs to invent one. */
internal fun unplayedHeadlineRegion(page: MapPage): RegionListening? {
    val region = page.listening.darkestRegion ?: return null
    if (page.regionNames.getOrNull(region).isNullOrBlank()) return null
    // The darkest eligible region can be fully played while a smaller one still holds every
    // unplayed track; "X is 0% untouched" would then point at the wrong place.
    return page.listening.regions.firstOrNull { it.region == region }?.takeIf { it.neverPlayed > 0 }
}

private fun percent(part: Int, whole: Int): Int =
    if (whole <= 0) 0 else (part * 100f / whole).roundToInt()

private fun largestRegion(page: MapPage): Int =
    page.listening.regions.maxByOrNull { it.trackCount }?.region ?: 0

/**
 * Localize generated semantic names, then distinguish every repeated label in stable region order.
 * An ordinal distinguishes equal genre/decade claims without inventing finer musical evidence.
 * Generic regions keep the same numbering convention; unique proper names remain unchanged.
 */
internal fun regionDisplayNames(
    regions: List<LibraryWorld>,
    discoveryMixLabel: String,
    semanticLabels: Map<LibraryWorldSemanticTitle, String>,
): List<String> {
    val baseNames = regions.map { world ->
        val semanticTitle = world.semanticTitle
        when {
            semanticTitle != null -> semanticLabels[semanticTitle] ?: world.name
            world.nameSource == LibraryWorldNameSource.GENERIC -> discoveryMixLabel
            else -> world.name
        }.trim()
    }
    val counts = baseNames.groupingBy { it.lowercase() }.eachCount()
    val ordinals = mutableMapOf<String, Int>()
    val used = mutableSetOf<String>()
    return baseNames.map { name ->
        val key = name.lowercase()
        if (counts.getValue(key) == 1) {
            used += key
            name
        } else {
            var ordinal = ordinals[key] ?: 0
            var candidate: String
            do {
                ordinal++
                candidate = "$name $ordinal"
                // A real label ending in a number must not collide with a generated suffix.
            } while (candidate.lowercase() in counts || candidate.lowercase() in used)
            ordinals[key] = ordinal
            used += candidate.lowercase()
            candidate
        }
    }
}

/**
 * Whether [mapFallbackRegions] may stand in for an empty `worlds`.
 *
 * Final review finding (IMPORTANT): `worlds` reads empty for two entirely different reasons, and
 * only one of them is "clustering found nothing for this library":
 * - genuinely below `TrackClustering`'s minimum population, or a small library whose k-means `k`
 *   splits it thin enough that no cluster reaches `MIN_CLUSTER_SIZE` -- [mapFallbackRegions]'
 *   actual target.
 * - `worlds` simply has not been discovered *for this library* yet: it starts as `emptyList()` on
 *   first composition, and both `onRebuildAnalysis` and `onBackupRestored` in `App.kt` reset it
 *   (together with `worldLibraryIds`) to `emptyList()` so a stale discovery is never shown as
 *   current. In every one of those cases the discovery effect simply has not reported back yet.
 *
 * Collapsing these into one `worlds.isEmpty()` check let a user who swipes to the Map in the first
 * seconds after launch -- `libraryMixFeatures` already succeeded, but `LibraryWorlds.discover`
 * has not -- see the fallback's "Discovery mix" claim the whole library is one region, even though
 * real regions were seconds away. Once discovery *did* land, `worlds` changing re-keyed the Map's
 * assembly effect and threw away whatever it had just computed.
 *
 * Mirrors the same guard `App.kt`'s For You effect already uses for its own `requestedWorlds`
 * (`worldLibraryIds == loadedIds`): `worlds` may only be trusted -- empty or not -- once discovery
 * has reported on exactly this library's id set.
 */
internal fun mapFallbackShouldApply(
    worlds: List<LibraryWorld>,
    worldLibraryIds: List<TrackId>,
    loadedIds: List<TrackId>,
): Boolean = worlds.isEmpty() && worldLibraryIds == loadedIds

/**
 * Spec section 8, row 3: "fewer tracks than MIN_CLUSTER_SIZE regions can support → fall back to a
 * single unnamed region." `TrackClustering.cluster` returns an empty list below its own minimum
 * population, and in practice also whenever the requested k splits a small library thin enough
 * that no resulting cluster reaches `MIN_CLUSTER_SIZE` -- both leave `LibraryWorlds.discover` with
 * nothing to offer even though indexing finished and a real vector space exists. Without this, the
 * Map's assembly effect had no regions to key dots against and stayed on the indexing state
 * forever, for a user whose indexing had long since completed.
 *
 * Only called once the caller already has a *successful* vector-space fetch and `LibraryWorlds`
 * came back empty from it -- an empty result here (only possible when [laidOutIds] shares nothing
 * with [library]) means there is genuinely nothing to show yet, not that clustering merely has not
 * run, so the caller keeps its existing state in that case rather than treating this as "ready".
 *
 * The synthetic region's name matches `LibraryWorlds`' own generic label for a cluster with no
 * strong shared claim ([LibraryWorldNameSource.GENERIC], text "Discovery mix") — this fallback
 * makes exactly that claim about the whole library, so the same honest, unnamed-in-spirit name
 * applies. Region names are not localized anywhere in this system today (every `LibraryWorld.name`
 * — genre, artist, or this generic label — is generated in `:core:smart` as plain, unlocalized
 * text), so no new locale strings are needed for it.
 *
 * Pure and Compose-free, so it is exercised directly in `MapTabTest` rather than through the full
 * assembly effect.
 */
internal fun mapFallbackRegions(
    library: List<TrackDescriptor>,
    laidOutIds: List<TrackId>,
): List<LibraryWorld> {
    // See mapFallbackShouldApply's doc for why callers must check that predicate before calling
    // this -- an empty `worlds` list means either "clustering found nothing" (this function's job)
    // or "clustering has not reported on this library yet" (not this function's job), and this
    // function has no way to tell those apart on its own.
    val byId = library.associateBy(TrackDescriptor::id)
    val tracks = laidOutIds.mapNotNull(byId::get)
    if (tracks.isEmpty()) return emptyList()
    return listOf(
        LibraryWorld(
            name = "Discovery mix",
            tracks = tracks,
            nameSource = LibraryWorldNameSource.GENERIC,
        ),
    )
}

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
): Offset = screenPoint(dot.x, dot.y, width, height, zoom, panX, panY)

/**
 * [screenPosition] for a point that is not a dot — a region centroid, for [labelTopLeft].
 *
 * The transform itself lives here so that dots and the labels naming their regions cannot drift out
 * of alignment: a label placed by one copy of this arithmetic and dots drawn by another would
 * disagree about where a region *is* the moment either copy was edited.
 */
internal fun screenPoint(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
): Offset = Offset(
    x = x * width * zoom + panX,
    y = y * height * zoom + panY,
)

/** A viewport shared by gesture handling and drawing. Pan is in screen pixels. */
internal data class MapViewport(val zoom: Float, val panX: Float, val panY: Float)

/** Keep the point under the fingers fixed while pinching, then constrain the map to its viewport. */
internal fun transformMapViewport(
    current: MapViewport,
    centroid: Offset,
    pan: Offset,
    gestureZoom: Float,
    width: Float,
    height: Float,
): MapViewport {
    val newZoom = (current.zoom * gestureZoom).coerceIn(1f, 6f)
    val ratio = newZoom / current.zoom
    return MapViewport(
        zoom = newZoom,
        panX = (centroid.x - (centroid.x - current.panX) * ratio + pan.x)
            .coerceIn(width * (1f - newZoom), 0f),
        panY = (centroid.y - (centroid.y - current.panY) * ratio + pan.y)
            .coerceIn(height * (1f - newZoom), 0f),
    )
}

internal data class MapLabelAnchor(
    val region: Int,
    val centre: Offset,
    val width: Float,
    val height: Float,
)

internal data class MapLabelPlacement(val region: Int, val bounds: Rect)

/**
 * Place labels in priority order (selected region first, then population), omitting collisions.
 * Every region remains available in the selector; zooming reveals labels that could not fit at
 * the overview scale. Hit-testing uses these same bounds, including the label's padded background.
 */
internal fun mapLabelPlacements(
    anchors: List<MapLabelAnchor>,
    width: Float,
    height: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    marginPx: Float,
): List<MapLabelPlacement> {
    val placements = ArrayList<MapLabelPlacement>(anchors.size)
    for (anchor in anchors) {
        val topLeft = visibleLabelTopLeft(
            anchor.centre, anchor.width, anchor.height, width, height,
            zoom, panX, panY, marginPx,
        ) ?: continue
        val bounds = Rect(topLeft, Size(anchor.width, anchor.height))
        if (placements.none { it.bounds.inflate(marginPx).overlaps(bounds) }) {
            placements += MapLabelPlacement(anchor.region, bounds)
        }
    }
    return placements
}

/**
 * Where a region's name is drawn: centred on the region's centroid, then clamped so the whole label
 * stays inside the canvas.
 *
 * A label is a region's only identity on the map — [MapLenses]' colours carry selection and
 * listening, never which region this is — so half a name is a label that has failed. Centring alone
 * cuts one off whenever a centroid lands within half a label of an edge, which is common: t-SNE
 * pushes distinct regions to the rim of the layout by design, so the outermost regions are exactly
 * the ones whose names get cropped.
 *
 * Clamping moves such a label by at most half its own width, which keeps it touching the cluster it
 * names; nudging every label inward instead — or dropping the ones that do not fit — would either
 * detach interior labels from their regions or leave the map's biggest outer regions anonymous.
 *
 * Pure, like [screenPosition] and [nearest] beside it, so `MapTabTest` pins the geometry directly.
 */
internal fun labelTopLeft(
    centre: Offset,
    labelWidth: Float,
    labelHeight: Float,
    width: Float,
    height: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    marginPx: Float,
): Offset {
    val centreOnScreen = screenPoint(centre.x, centre.y, width, height, zoom, panX, panY)
    return Offset(
        // max(margin, …) is not defensive dressing: a label wider than the canvas makes the upper
        // bound fall below the lower one, and coerceIn throws on a range whose end precedes its
        // start. Such a label has no fitting position at all, so it pins to the leading edge and
        // shows what there is room for.
        x = (centreOnScreen.x - labelWidth / 2f)
            .coerceIn(marginPx, max(marginPx, width - labelWidth - marginPx)),
        y = (centreOnScreen.y - labelHeight / 2f)
            .coerceIn(marginPx, max(marginPx, height - labelHeight - marginPx)),
    )
}

/**
 * [labelTopLeft] for a centroid that is still in the viewport.
 *
 * At rest every normalized centroid is visible and edge clamping keeps its full name readable.
 * After zooming and panning, however, several centroids can be far outside the viewport. Clamping
 * those names anyway drags all of them onto the same nearest edge or corner, where unrelated labels
 * pile on top of one another even though none of their regions is on screen. An offscreen region has
 * no honest label position; its name returns when its centroid is panned back into view.
 */
internal fun visibleLabelTopLeft(
    centre: Offset,
    labelWidth: Float,
    labelHeight: Float,
    width: Float,
    height: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    marginPx: Float,
): Offset? {
    val centreOnScreen = screenPoint(centre.x, centre.y, width, height, zoom, panX, panY)
    if (centreOnScreen.x !in 0f..width || centreOnScreen.y !in 0f..height) return null
    return labelTopLeft(
        centre = centre,
        labelWidth = labelWidth,
        labelHeight = labelHeight,
        width = width,
        height = height,
        zoom = zoom,
        panX = panX,
        panY = panY,
        marginPx = marginPx,
    )
}

/**
 * How far a clamped label stays clear of the canvas edge.
 *
 * A clamp to exactly the edge is correct and still reads as broken: measured on the device, the
 * right-hand region's name ended 4 px from a 1080 px screen's border, close enough to the bezel that
 * it looks cut off even though every glyph is drawn. This is only ever spent on labels the clamp
 * actually moved — an interior label is untouched — so it costs nothing in the common case.
 */
internal val LABEL_EDGE_MARGIN_DP = 6.dp

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
internal val HIT_RADIUS_DP = 24.dp

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
