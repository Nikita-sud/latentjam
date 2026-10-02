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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.input.pointer.PointerId
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

    /** The end the player belongs at: what the app shows as open, and where every settle goes. */
    var target by mutableStateOf(open)
        private set

    /** True from a drag's first move to its release: the ends must not be torn down under a finger. */
    var dragging by mutableStateOf(false)
        private set

    /** An animation towards [target] is running. */
    var settling by mutableStateOf(false)
        private set

    /** How far the surface's top edge travels between the two ends, in px; set by the sheet's layout. */
    var travelPx = 0f

    private var job: Job? = null
    private var heading: Boolean? = null

    /** An explicit open or close (Back, a "go to" action) that arrived while a finger was down. */
    private var requestedWhileDragging: Boolean? = null

    /**
     * The app asks for an end: a tap on the pill, Back, a navigation action. Mid-drag it is only
     * remembered, so it neither fights the finger nor is lost when the finger lifts.
     */
    fun request(open: Boolean, reduceMotion: Boolean) {
        if (dragging) {
            requestedWhileDragging = open
            target = open
            return
        }
        target = open
        settle(open, reduceMotion)
    }

    /** Follows the finger one-to-one: [downPx] down moves the surface's top edge exactly that far. */
    fun dragBy(downPx: Float) {
        if (travelPx <= 0f) return
        stop()
        if (!dragging) dragging = true
        value.floatValue = (progress - downPx / travelPx).coerceIn(0f, 1f)
    }

    /**
     * Settles a released drag and returns the end it goes to, which the caller must report to the
     * app whichever way it went. A request made during the drag wins; otherwise distance or fling
     * decides, measured from the end the player belonged to when the finger came down.
     */
    fun release(downVelocity: Float, commitPx: Float, flingPx: Float, reduceMotion: Boolean): Boolean {
        if (!dragging) return target
        dragging = false
        val travel = travelPx
        val velocity = if (travel > 0f) -downVelocity / travel else 0f
        val requested = requestedWhileDragging
        requestedWhileDragging = null
        val open = requested ?: if (travel <= 0f) {
            target
        } else {
            expansionSettlesOpen(progress, velocity, target, commitPx / travel, flingPx / travel)
        }
        target = open
        settle(open, reduceMotion, velocity, fromGesture = requested == null)
        return open
    }

    /**
     * A gesture that vanished without an up or a cancel (its node was removed, its coroutine was
     * cancelled). Nothing was decided, so the player goes back to the end it belongs at.
     */
    fun abandonDrag(reduceMotion: Boolean) {
        if (!dragging) return
        dragging = false
        requestedWhileDragging = null
        settle(target, reduceMotion)
    }

    /**
     * The safety net: whenever no finger and no animation is moving the progress, it must sit
     * exactly at [target]. No sequence of gestures and requests can leave a drawn-but-closed (or
     * open-but-hidden) player behind.
     */
    fun reconcile(reduceMotion: Boolean) {
        if (dragging || settling) return
        if (progress != endOf(target)) settle(target, reduceMotion)
    }

    /** Whether [reconcile] has anything to do; read through snapshotFlow. */
    val outOfPlace: Boolean get() = !dragging && !settling && progress != endOf(target)

    /**
     * Animates to an end. A gesture hands over its speed to a spring; a tap or Back uses the same
     * finite clock the morph always had. Reduced motion jumps.
     */
    private fun settle(open: Boolean, reduceMotion: Boolean, velocity: Float = 0f, fromGesture: Boolean = false) {
        val end = endOf(open)
        if (heading == open && job?.isActive == true) return
        stop()
        if (reduceMotion || progress == end) {
            value.floatValue = end
            return
        }
        heading = open
        settling = true
        val launched = scope.launch {
            try {
                animate(
                    initialValue = progress,
                    targetValue = end,
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
                value.floatValue = end
            } finally {
                if (job === coroutineContext[Job]) {
                    job = null
                    heading = null
                    settling = false
                }
            }
        }
        job = launched
    }

    private fun stop() {
        job?.cancel()
        job = null
        heading = null
        settling = false
    }

    private fun endOf(open: Boolean): Float = if (open) 1f else 0f
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
    /** The player is about to cover the library: a drag up from the pill has begun. */
    onCoverStart: () -> Unit,
    mini: @Composable (live: Boolean, thumbnailAlpha: () -> Float) -> Unit,
    full: @Composable (
        revealed: Boolean,
        artworkModifier: Modifier,
        onCollapseDrag: (Float) -> Unit,
        onCollapseRelease: (Float) -> Boolean,
        onCollapseAbandon: () -> Unit,
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
    val currentOnCoverStart by rememberUpdatedState(onCoverStart)
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    val geometry = remember { PlayerSheetGeometry() }

    // Taps on the pill, Back, and every "go to" action only change [open]; this moves the progress.
    LaunchedEffect(open, reduceMotion) { expansion.request(open, reduceMotion) }
    // Whatever happened to a gesture, a resting progress always ends up at the open state.
    LaunchedEffect(expansion) {
        snapshotFlow { expansion.outOfPlace }.collect { stray ->
            if (stray) expansion.reconcile(currentReduceMotion)
        }
    }

    // The two ends are the only moments that change what is composed.
    val atFull by remember { derivedStateOf { expansion.progress >= 1f && !expansion.dragging } }
    val atMini by remember { derivedStateOf { expansion.progress <= 0f && !expansion.dragging } }
    // A finger on the pill may be the start of a drag up: the player starts following playback
    // (its own recomposition) now, inside the touch slop, not on the first frame that moves.
    var pillPressed by remember { mutableStateOf(false) }
    // A dragged (or merely pressed) node stays placed until its finger lifts. Unplaced, Compose
    // would stop sending it events without a cancel: the drag would never be released, or the
    // press never lifted.
    val pillPlaced = remember {
        derivedStateOf { expansion.progress < 1f || expansion.dragging || pillPressed }
    }
    val inFlight = remember {
        derivedStateOf { expansion.progress > 0f && (expansion.progress < 1f || expansion.dragging) }
    }
    // The player is on screen or on its way there.
    val revealed = open || !atMini || pillPressed
    // Back while a finger lifts the closed player from the pill: nothing is open to close, so it
    // means "not now", and the release goes back to the mini player instead of leaving the app.
    // Switched at the touch, not at the first move: registering a platform back callback costs a
    // call into the system that must not land on a frame of the drag.
    // Composed only while needed, so it is registered at the touch and outranks every handler
    // composed before it (search, a collection page, settings): the page under the rising sheet
    // must not be the one that Back closes.
    if (!open && (pillPressed || !atMini)) {
        PlatformBackHandler(enabled = true) {
            expansion.request(open = false, reduceMotion = currentReduceMotion)
        }
    }
    // Composing and first drawing the full player is the most expensive thing the sheet does
    // (about 100 ms on an emulator). It happens once, on the first touch of the pill or the first
    // open, and is then kept: below the pill, inactive and drawn at zero alpha it costs nothing
    // per frame. The pill and the flying cover are kept with it, so no drag ever starts by
    // composing anything.
    var warmRequested by remember { mutableStateOf(false) }
    val warmth = remember { PlayerWarmth() }
    if (revealed || warmRequested) warmth.warm = true
    val warm = warmth.warm

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
    // Stable across the recompositions at the ends, so the player and the pill skip them.
    val collapseDrag: (Float) -> Unit = remember(expansion) { { expansion.dragBy(it) } }
    val collapseRelease: (Float) -> Boolean = remember(expansion, commitPx, flingPx) {
        { velocity ->
            val opens = expansion.release(velocity, commitPx, flingPx, currentReduceMotion)
            currentOnOpenChange(opens)
            !opens
        }
    }
    // An abandoned drag is reported like a release: the app hears where the player ends up.
    val collapseAbandon: () -> Unit = remember(expansion) {
        {
            expansion.abandonDrag(currentReduceMotion)
            currentOnOpenChange(expansion.target)
        }
    }
    val artworkModifier = remember(expansion, geometry) {
        Modifier
            .onPlaced {
                geometry.artwork = it
                geometry.refresh()
            }
            // Between the ends the flying copy is the cover.
            .graphicsLayer {
                alpha = if (expansion.progress >= 1f || geometry.cover == null) 1f else 0f
            }
    }
    val thumbnailAlpha: () -> Float = remember(expansion, geometry) {
        { if (expansion.progress > 0f && geometry.cover != null) 0f else 1f }
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
            if (warm) {
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
                        },
                ) {
                    full(revealed, artworkModifier, collapseDrag, collapseRelease, collapseAbandon)
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .placedWhile(pillPlaced)
                        .graphicsLayer {
                            val progress = expansion.progress
                            translationY = -expansion.travelPx * progress
                            alpha = miniPlayerContentAlpha(progress)
                        }
                        // The first touch of a session composes the player behind the pill, and every
                        // touch wakes it, while the finger is still inside the touch slop. It watches
                        // and never consumes.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (!warmRequested) warmRequested = true
                                pillPressed = true
                                try {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    pillPressed = false
                                }
                            }
                        }
                        .playerExpansionDrag(
                            onDrag = { downPx ->
                                if (!expansion.dragging) currentOnCoverStart()
                                expansion.dragBy(downPx)
                            },
                            onRelease = { velocity ->
                                currentOnOpenChange(
                                    expansion.release(velocity, commitPx, flingPx, currentReduceMotion),
                                )
                            },
                            onAbandon = {
                                expansion.abandonDrag(currentReduceMotion)
                                currentOnOpenChange(expansion.target)
                            },
                        )
                        // While the player is (becoming) the surface, the fading pill above it
                        // must not take its taps or its place in TalkBack.
                        .inactiveForMotion(open),
                ) {
                    mini(!atFull, thumbnailAlpha)
                }
            }
            if (warm) {
                FlyingCover(
                    shown = { inFlight.value },
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
    /** The gesture ended with neither an up nor a decision: cancelled, or its node went away. */
    onAbandon: () -> Unit,
): Modifier {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnAbandon by rememberUpdatedState(onAbandon)
    val placed = remember { PlacedCoordinates() }
    return onPlaced { placed.coordinates = it }.pointerInput(Unit) {
        val tracker = VelocityTracker()
        var lastY = Float.NaN
        var lastPointer: PointerId? = null
        var travelled = 0f
        var inDrag = false
        try {
            detectVerticalDragGestures(
                onDragStart = {
                    tracker.resetTracking()
                    lastY = Float.NaN
                    lastPointer = null
                    travelled = 0f
                    inDrag = true
                },
                onDragEnd = {
                    inDrag = false
                    currentOnRelease(tracker.calculateVelocity().y)
                },
                onDragCancel = {
                    inDrag = false
                    currentOnAbandon()
                },
            ) { change, amount ->
                change.consume()
                val y = placed.windowY(change.position)
                // The first move carries what is left of the slop, and when the tracked finger
                // lifts the detector follows another one: both continue from the detector's own
                // amount instead of jumping by the distance between two fingers.
                val step = if (lastY.isNaN() || change.id != lastPointer) amount else y - lastY
                lastY = y
                lastPointer = change.id
                travelled += step
                tracker.addPosition(change.uptimeMillis, Offset(0f, travelled))
                currentOnDrag(step)
            }
        } finally {
            // Removed or restarted mid-drag: no up will ever arrive.
            if (inDrag) currentOnAbandon()
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
