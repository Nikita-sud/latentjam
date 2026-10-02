/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.util.lerp as lerpFloat

/**
 * The one continuous state between the mini player (0) and the full player (1).
 *
 * Everything the sheet draws is a function of [progress], so a drag, a fling, a tap on the pill and
 * Back all move the same value and there is never a second, timed animation to hand over to. The
 * value is written from gestures and animations only and read in layout, draw and layer lambdas
 * (or through derivedStateOf at the two ends), so no frame of motion recomposes anything.
 */
@Stable
internal class PlayerExpansion(open: Boolean, private val scope: CoroutineScope) {
    private val value = mutableFloatStateOf(if (open) 1f else 0f)
    val progress: Float get() = value.floatValue

    /** True from a drag's first move to its release: the ends must not be torn down under a finger. */
    var dragging by mutableStateOf(false)
        private set

    /**
     * A finger is down on the pill or the player. Whatever a drag would need first (the player
     * behind the pill, the pill and the flying cover behind the player) is composed now, while the
     * finger is still inside the touch slop, so the first frame that moves pays no composition.
     */
    var armed by mutableStateOf(false)

    /** How far the surface's top edge travels between the two ends, in px; set by the sheet's layout. */
    var travelPx = 0f

    private var job: Job? = null
    private var heading: Boolean? = null

    /** Follows the finger one-to-one: [downPx] down moves the surface's top edge exactly that far. */
    fun dragBy(downPx: Float) {
        if (travelPx <= 0f) return
        stop()
        if (!dragging) dragging = true
        value.floatValue = (progress - downPx / travelPx).coerceIn(0f, 1f)
    }

    /** Settles a released drag by distance or fling; returns whether the player ends up open. */
    fun release(
        downVelocity: Float,
        wasOpen: Boolean,
        commitPx: Float,
        flingPx: Float,
        reduceMotion: Boolean,
    ): Boolean {
        dragging = false
        val travel = travelPx
        if (travel <= 0f) return wasOpen
        val velocity = -downVelocity / travel
        val open = expansionSettlesOpen(progress, velocity, wasOpen, commitPx / travel, flingPx / travel)
        settle(open, reduceMotion, velocity, fromGesture = true)
        return open
    }

    /**
     * Animates to an end. A gesture hands over its speed to a spring; a tap or Back uses the same
     * finite clock the morph always had. Reduced motion jumps.
     */
    fun settle(open: Boolean, reduceMotion: Boolean, velocity: Float = 0f, fromGesture: Boolean = false) {
        val target = if (open) 1f else 0f
        if (heading == open && job?.isActive == true) return
        stop()
        if (reduceMotion || progress == target) {
            value.floatValue = target
            return
        }
        heading = open
        job = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = if (fromGesture) {
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                        // Progress spans the whole screen: the default 0.01 would end with a jump.
                        visibilityThreshold = 0.0005f,
                    )
                } else {
                    tween(Motion.EMPHASIZED_MS, easing = Motion.NavigationEasing)
                },
            ) { x, _ -> value.floatValue = x }
            // Exactly at the end, so "fully open" and "fully closed" are exact comparisons.
            value.floatValue = target
            heading = null
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        heading = null
    }
}

/** Set once the full player has been composed; plain, because only composition writes it. */
private class PlayerWarmth {
    var warm = false
}

/** Where the full cover sits inside the player, for the copy that flies to and from the pill. */
private class PlayerSheetGeometry {
    var content: LayoutCoordinates? = null
    var artwork: LayoutCoordinates? = null

    /** In the sheet's own coordinates; the player's layer offset is not part of it. */
    var cover by mutableStateOf<Rect?>(null)

    fun refresh() {
        val content = content?.takeIf { it.isAttached } ?: return
        val artwork = artwork?.takeIf { it.isAttached } ?: return
        cover = content.localBoundingBoxOf(artwork, clipBounds = false)
    }
}

/**
 * The mini player and the full player as one surface over the library.
 *
 * At 0 it is the pill; as the progress rises the surface grows from the pill's card to the whole
 * window, its colour turns from the pill's to the player's floor, the pill's own content fades
 * out early and the player's fades in over the middle, and the cover flies from the thumbnail to
 * its place. The library underneath stays drawn (dimmed) at every progress below 1.
 *
 * [mini] is the pill (it gets the alpha to apply to its own thumbnail while the copy flies);
 * [full] is the player, given the modifier for its cover and the handlers for a downward drag.
 */
@Composable
internal fun PlayerSheet(
    expansion: PlayerExpansion,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    hasTrack: Boolean,
    pillHeight: Dp,
    pillColor: Color,
    coverUri: String?,
    mini: @Composable (thumbnailAlpha: () -> Float) -> Unit,
    full: @Composable (
        revealed: Boolean,
        artworkModifier: Modifier,
        onCollapseDrag: (Float) -> Unit,
        onCollapseRelease: (Float) -> Boolean,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val navigationBars = WindowInsets.navigationBars
    val floorColor = MaterialTheme.colorScheme.surface
    val darkFloor = floorColor.luminance() < 0.5f
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    val geometry = remember { PlayerSheetGeometry() }

    // Taps on the pill, Back, and every "go to" action only change [open]; this moves the progress.
    LaunchedEffect(open, reduceMotion) { expansion.settle(open, reduceMotion) }

    // The two ends are the only moments that change what is composed.
    val atFull by remember { derivedStateOf { expansion.progress >= 1f && !expansion.dragging } }
    val atMini by remember { derivedStateOf { expansion.progress <= 0f && !expansion.dragging } }
    // Composed ahead of a possible drag, but only placed (seen, touched, read by TalkBack) between
    // the ends.
    val belowFull = remember { derivedStateOf { expansion.progress < 1f } }
    val aboveMini = remember { derivedStateOf { expansion.progress > 0f } }
    val armed = expansion.armed
    // The player is on screen, or a finger is down and may be about to bring it there.
    val revealed = open || !atMini || armed
    // Composing and first drawing the full player is the most expensive thing the sheet does
    // (about 100 ms on an emulator). Once done it is kept: below the pill, inactive (no ticker,
    // no cloud) and drawn at zero alpha, it costs nothing per frame, and no later press pays
    // that frame again.
    val warmth = remember { PlayerWarmth() }
    if (revealed) warmth.warm = true
    val fullComposed = warmth.warm
    val miniComposed = !atFull || armed
    val flightComposed = (!atMini && !atFull) || (armed && open)

    fun Density.pill(size: Size): Rect = miniPlayerBounds(
        width = size.width,
        height = size.height,
        insetLeft = navigationBars.getLeft(this, layoutDirection).toFloat(),
        insetRight = navigationBars.getRight(this, layoutDirection).toFloat(),
        insetBottom = navigationBars.getBottom(this).toFloat(),
        margin = PILL_MARGIN.toPx(),
        pillHeight = (pillHeight - PILL_MARGIN * 2).toPx(),
    )
    fun Density.surface(size: Size, progress: Float): Rect =
        lerp(pill(size), Rect(Offset.Zero, size), progress)

    val commitPx = with(density) { PLAYER_COMMIT_DISTANCE.toPx() }
    val flingPx = with(density) { PLAYER_FLING_VELOCITY.toPx() }
    val collapseDrag: (Float) -> Unit = { expansion.dragBy(it) }
    val collapseRelease: (Float) -> Boolean = { velocity ->
        val stays = expansion.release(velocity, wasOpen = true, commitPx, flingPx, reduceMotion)
        if (!stays) currentOnOpenChange(false)
        !stays
    }

    AnimatedVisibility(
        visible = hasTrack || revealed,
        modifier = modifier.fillMaxSize(),
        enter = if (reduceMotion) {
            fadeIn(tween(Motion.REDUCED_MS))
        } else {
            fadeIn(tween(Motion.APPEAR_MS)) +
                slideInVertically(tween(Motion.APPEAR_MS)) { dockSlide(density, pillHeight) }
        },
        exit = if (reduceMotion) {
            fadeOut(tween(Motion.REDUCED_MS))
        } else {
            fadeOut(tween(Motion.REPLACE_MS)) +
                slideOutVertically(tween(Motion.REPLACE_MS)) { dockSlide(density, pillHeight) }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    expansion.travelPx = pill(Size(placeable.width.toFloat(), placeable.height.toFloat())).top
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
        ) {
            // The surface between the two ends. At either end the pill or the player's own floor
            // is the surface, so nothing is drawn twice while at rest.
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .drawBehind {
                        val progress = expansion.progress
                        if (progress <= 0f || progress >= 1f) return@drawBehind
                        val rect = surface(size, progress)
                        drawRoundRect(
                            color = lerp(pillColor, floorColor, playerSurfaceColorFraction(progress)),
                            topLeft = rect.topLeft,
                            size = rect.size,
                            cornerRadius = CornerRadius(lerpFloat(PILL_RADIUS.toPx(), 0f, progress)),
                        )
                    },
            )
            if (fullComposed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // The player keeps its full-screen layout; only this layer moves, fades
                        // and is cut to the growing surface.
                        .graphicsLayer {
                            val progress = expansion.progress
                            val rect = surface(size, progress)
                            translationY = rect.top
                            alpha = fullPlayerContentAlpha(progress)
                            if (progress < 1f) {
                                clip = true
                                shape = SheetWindowShape(
                                    Rect(rect.left, 0f, rect.right, rect.height),
                                    lerpFloat(PILL_RADIUS.toPx(), 0f, progress),
                                )
                            } else {
                                clip = false
                                shape = RectangleShape
                            }
                        }
                        .onPlaced {
                            geometry.content = it
                            geometry.refresh()
                        }
                        .armsExpansion(expansion),
                ) {
                    full(
                        revealed,
                        Modifier
                            .onPlaced {
                                geometry.artwork = it
                                geometry.refresh()
                            }
                            // Between the ends the flying copy is the cover.
                            .graphicsLayer {
                                alpha = if (expansion.progress >= 1f || geometry.cover == null) 1f else 0f
                            },
                        collapseDrag,
                        collapseRelease,
                    )
                }
            }
            if (miniComposed) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .placedWhile(belowFull)
                            .armsExpansion(expansion)
                            .graphicsLayer {
                                val progress = expansion.progress
                                translationY = -expansion.travelPx * progress
                                alpha = miniPlayerContentAlpha(progress)
                            }
                            .playerExpansionDrag(
                                onDrag = { expansion.dragBy(it) },
                                onRelease = { velocity ->
                                    val opened = expansion.release(
                                        velocity,
                                        wasOpen = false,
                                        commitPx,
                                        flingPx,
                                        reduceMotion,
                                    )
                                    if (opened) currentOnOpenChange(true)
                                },
                            )
                            // While the player is (becoming) the surface, the fading pill above it
                            // must not take its taps or its place in TalkBack.
                            .inactiveForMotion(open),
                    ) {
                        mini { if (expansion.progress > 0f && geometry.cover != null) 0f else 1f }
                    }
                }
            }
            if (flightComposed) {
                FlyingCover(
                    shown = { aboveMini.value && belowFull.value },
                    uri = coverUri,
                    cover = { geometry.cover },
                    thumbnail = { size ->
                        miniPlayerThumbnailBounds(
                            pill(size),
                            startPadding = THUMBNAIL_START.toPx(),
                            size = THUMBNAIL_SIZE.toPx(),
                            rtl = layoutDirection == LayoutDirection.Rtl,
                        )
                    },
                    progress = { expansion.progress },
                    elevation = if (darkFloor) COVER_ELEVATION else 8.dp,
                )
            }
        }
    }
}

/**
 * The cover between the ends: laid out at the full cover's size and place, and moved, scaled and
 * rounded by its layer alone, so the flight never re-measures the image.
 */
@Composable
private fun FlyingCover(
    shown: () -> Boolean,
    uri: String?,
    cover: () -> Rect?,
    thumbnail: Density.(Size) -> Rect,
    progress: () -> Float,
    elevation: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val bounds = cover()
                if (bounds == null || bounds.width <= 0f) {
                    return@layout layout(constraints.maxWidth, constraints.maxHeight) {}
                }
                val placeable = measurable.measure(
                    Constraints.fixed(bounds.width.roundToInt(), bounds.height.roundToInt()),
                )
                val sheet = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // Measured (so its image is resolved) as soon as it is composed; placed only
                    // between the ends.
                    if (shown()) placeable.placeWithLayer(bounds.left.roundToInt(), bounds.top.roundToInt()) {
                        val p = progress()
                        val target = lerp(thumbnail(sheet), bounds, p)
                        val scale = target.width / bounds.width
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = target.left - bounds.left.roundToInt()
                        translationY = target.top - bounds.top.roundToInt()
                        scaleX = scale
                        scaleY = scale
                        // Corners and lift belong to the size on screen, not the layer's.
                        val radius = lerpFloat(THUMBNAIL_RADIUS.toPx(), COVER_RADIUS.toPx(), p) / scale
                        shape = RoundedCornerShape(CornerSize(radius))
                        clip = true
                        shadowElevation = elevation.toPx() * p
                    }
                }
            },
    ) {
        PlayerCoverFace(uri = uri, modifier = Modifier.fillMaxSize())
    }
}

/** The growing surface's outline in the player layer's own (translated) coordinates. */
private class SheetWindowShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * A vertical drag that hands its screen-space moves and release speed to the expansion. The
 * handler usually rides a layer that follows the same finger, so positions are taken through that
 * layer into the window (see [PlacedCoordinates]); local ones would lag behind by the layer's move.
 */
@Composable
internal fun Modifier.playerExpansionDrag(
    onDrag: (downPx: Float) -> Unit,
    onRelease: (downVelocity: Float) -> Unit,
): Modifier {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val placed = remember { PlacedCoordinates() }
    return onPlaced { placed.coordinates = it }.pointerInput(Unit) {
        val tracker = VelocityTracker()
        var lastY = Float.NaN
        var travelled = 0f
        detectVerticalDragGestures(
            onDragStart = {
                tracker.resetTracking()
                lastY = Float.NaN
                travelled = 0f
            },
            onDragEnd = { currentOnRelease(tracker.calculateVelocity().y) },
            onDragCancel = { currentOnRelease(0f) },
        ) { change, amount ->
            change.consume()
            val y = placed.windowY(change.position)
            // The first move carries what is left of the slop; later ones are window deltas.
            val step = if (lastY.isNaN()) amount else y - lastY
            lastY = y
            travelled += step
            tracker.addPosition(change.uptimeMillis, Offset(0f, travelled))
            currentOnDrag(step)
        }
    }
}

/** Laid out always, placed only while [placed] holds: unplaced, it is neither drawn nor touched. */
private fun Modifier.placedWhile(placed: androidx.compose.runtime.State<Boolean>): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            if (placed.value) placeable.place(0, 0)
        }
    }

/** Arms [PlayerExpansion] while any finger is down here; it watches and never consumes. */
private fun Modifier.armsExpansion(expansion: PlayerExpansion): Modifier =
    pointerInput(expansion) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            expansion.armed = true
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                expansion.armed = false
            }
        }
    }

private fun dockSlide(density: Density, pillHeight: Dp): Int =
    with(density) { (pillHeight + 24.dp).roundToPx() / 3 }

/** Matches the pill's own padding and shape (MiniPlayerPill). */
private val PILL_MARGIN = 8.dp
private val PILL_RADIUS = 24.dp
private val THUMBNAIL_START = 10.dp
private val THUMBNAIL_SIZE = 48.dp
private val THUMBNAIL_RADIUS = 12.dp

/** A release this far from where the drag started, either way, carries on to the other end. */
internal val PLAYER_COMMIT_DISTANCE = 140.dp

/** Faster than this per second, a release goes the way it was thrown regardless of distance. */
private val PLAYER_FLING_VELOCITY = 250.dp
