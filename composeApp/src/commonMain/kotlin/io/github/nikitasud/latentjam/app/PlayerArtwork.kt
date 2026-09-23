/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.cd_player_artwork
import io.github.nikitasud.latentjam.app.generated.resources.action_track_options
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * The cover as the player's main control surface.
 *
 * A tap turns it over to the track's details, a hold opens the actions sheet, a sideways swipe
 * carries the cover off with the neighbour peeking in behind it, and a pull downwards hands the
 * whole player to [onCollapseDrag]. Every per-frame value — travel, tilt, press scale, the hold
 * ring, the flip angle — is read inside a layer or draw lambda, so movement never recomposes the
 * screen. Composition changes only when a gesture starts or ends, when the card turns over, or
 * when the track itself changes.
 */
@Composable
internal fun PlayerArtworkCard(
    track: TrackDescriptor?,
    flipped: Boolean,
    onFlip: (Boolean) -> Unit,
    onHold: () -> Unit,
    onSkip: (forward: Boolean) -> Unit,
    canSkipForward: Boolean,
    canSkipBackward: Boolean,
    /** Track a swipe in that direction would reach; a missing image still gets a cover face. */
    neighbourTrack: (forward: Boolean) -> TrackDescriptor?,
    /** Called with the resisted pull in px while the finger drags down, and with 0f on release. */
    onCollapseDrag: (Float) -> Unit,
    onCollapse: () -> Unit,
    details: @Composable () -> Unit,
    queueIndex: Int = -1,
    /** Restarts keep the current cover and return it immediately after the skip action. */
    skipChangesTrack: (forward: Boolean) -> Boolean = { true },
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val travel = remember { Animatable(0f) }
    // The tilt belongs to the card alone. Tilting the whole group would swing the neighbour's
    // cover around the card's pivot, a card-width away, and it would lurch up and down.
    val tilt = remember { Animatable(0f) }
    val pressScale = remember { Animatable(1f) }
    val holdRing = remember { Animatable(0f) }
    val angle = remember { Animatable(0f) }
    var holdCenter by remember { mutableStateOf(Offset.Zero) }
    // Only the two immediate neighbours are prepared while this player is open. Their layers
    // stay transparent at rest, so image decoding never starts halfway through a swipe.
    var swiping by remember { mutableStateOf(false) }
    var pendingSkip by remember { mutableStateOf<Boolean?>(null) }
    var skipJob by remember { mutableStateOf<Job?>(null) }
    var dragTravel by remember { mutableStateOf<Float?>(null) }
    val entry = track?.id to queueIndex
    var motionEntry by remember { mutableStateOf(entry) }
    // Track/preview content changes during composition; coroutine cleanup happens afterward.
    // Never draw the new entry at the previous entry's parked swipe position, even for one frame.
    val ownsMotion = motionEntry == entry
    // Keep this decision for the arriving entry: clearing pendingSkip must not introduce a fade
    // halfway through replacing the fully visible preview with the main cover.
    val arrivedBySwipe = remember(entry) { pendingSkip != null }
    val backFacing by remember { derivedStateOf { angle.value > 90f } }
    val currentFlipped by rememberUpdatedState(flipped)
    val currentOnFlip by rememberUpdatedState(onFlip)
    val currentOnHold by rememberUpdatedState(onHold)
    val currentOnSkip by rememberUpdatedState(onSkip)
    val currentSkipChangesTrack by rememberUpdatedState(skipChangesTrack)
    val currentOnCollapseDrag by rememberUpdatedState(onCollapseDrag)
    val currentOnCollapse by rememberUpdatedState(onCollapse)
    val currentCanForward by rememberUpdatedState(canSkipForward)
    val currentCanBackward by rememberUpdatedState(canSkipBackward)

    LaunchedEffect(flipped, reduceMotion) {
        val target = if (flipped) 180f else 0f
        if (reduceMotion) angle.snapTo(target) else angle.animateTo(target, FLIP_TWEEN)
    }
    // A committed swipe parks the cover off-screen with the neighbour's cover in its place. The
    // moment the track changes the real cover takes over at zero travel; nothing moves on screen
    // because the ghost stood exactly there. A skip that never lands springs back instead.
    LaunchedEffect(entry) {
        skipJob?.cancel()
        travel.snapTo(0f)
        tilt.snapTo(0f)
        pressScale.snapTo(1f)
        holdRing.snapTo(0f)
        angle.snapTo(if (flipped) 180f else 0f)
        dragTravel = null
        pendingSkip = null
        swiping = false
        motionEntry = entry
    }
    LaunchedEffect(pendingSkip) {
        if (pendingSkip == null) return@LaunchedEffect
        delay(SKIP_SETTLE_TIMEOUT_MS)
        // Clear this effect's key only after the return completes; clearing it first would
        // cancel the animation on recomposition and leave an unsuccessful skip off-screen.
        travel.animateTo(0f, if (reduceMotion) tween(0) else SPRING_BACK)
        pendingSkip = null
        swiping = false
    }

    val description = stringResource(Res.string.cd_player_artwork)
    val actionsDescription = stringResource(Res.string.action_track_options)
    val ringColor = MaterialTheme.colorScheme.onSurface
    val ringTrack = ringColor.copy(alpha = 0.18f)
    Box(
        // Square, as tall as the space allows and never wider than the screen: the column above
        // hands the cover the spare height, and this is what lets it shrink.
        modifier = modifier
            .layout { measurable, constraints ->
                // aspectRatio falls back to width when remaining height is zero. On a short
                // player that can draw a full-width cover over every control below its slot.
                val side = when {
                    constraints.hasBoundedWidth && constraints.hasBoundedHeight ->
                        minOf(constraints.maxWidth, constraints.maxHeight)
                    constraints.hasBoundedWidth -> constraints.maxWidth
                    constraints.hasBoundedHeight -> constraints.maxHeight
                    else -> 0
                }
                val placeable = measurable.measure(Constraints.fixed(side, side))
                layout(side, side) { placeable.placeRelative(0, 0) }
            }
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick { currentOnFlip(!currentFlipped); true }
                onLongClick(label = actionsDescription) { currentOnHold(); true }
            }
            .pointerInput(track?.id, queueIndex, reduceMotion, haptics) {
                val slop = ARTWORK_SLOP.toPx()
                val collapseThreshold = COLLAPSE_THRESHOLD.toPx()
                val rejectTravel = REJECT_TRAVEL.toPx()
                val gap = GHOST_GAP.toPx()
                val returnSpec = if (reduceMotion) tween<Float>(0) else SPRING_BACK
                var holdJob: Job? = null
                fun settlePress() {
                    holdJob?.cancel()
                    scope.launch { holdRing.snapTo(0f) }
                    scope.launch { pressScale.animateTo(1f, returnSpec) }
                }
                awaitEachGesture {
                    // Child buttons on the details face own their consumed taps and holds.
                    val down = awaitFirstDown()
                    if (pendingSkip != null) return@awaitEachGesture
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture
                    holdCenter = down.position
                    holdJob = scope.launch {
                        if (!reduceMotion) {
                            launch { pressScale.animateTo(HOLD_SINK, tween(HOLD_MS, easing = LinearEasing)) }
                        }
                        holdRing.snapTo(0f)
                        holdRing.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                    }
                    var finished = false
                    var collapsing = false
                    try {
                        var axis: ArtworkDragAxis? = null
                        var change = down
                        val holdDeadline = down.uptimeMillis + HOLD_MS
                        while (axis == null) {
                            val remaining = holdDeadline - change.uptimeMillis
                            val event = withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
                            if (event == null) {
                                haptics.play(PlayerHaptic.HOLD)
                                settlePress()
                                currentOnHold()
                                waitForUpOrCancellation()
                                return@awaitEachGesture
                            }
                            change = event.changes.firstOrNull { it.id == down.id }
                                ?: return@awaitEachGesture
                            if (change.isConsumed) return@awaitEachGesture
                            val delta = change.position - down.position
                            axis = artworkDragAxis(delta.x, delta.y, slop)
                            if (axis == null && change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                haptics.play(PlayerHaptic.TAP)
                                currentOnFlip(!currentFlipped)
                                finished = true
                                return@awaitEachGesture
                            }
                        }
                        settlePress()
                        when (axis) {
                            ArtworkDragAxis.HORIZONTAL -> {
                                // A new tap/hold must let an earlier rejected swipe finish
                                // returning. Only another horizontal drag takes over its travel.
                                skipJob?.cancel()
                                swiping = true
                                var armed = false
                                var thresholdAnnounced = false
                                var rejected = false
                                var dx: Float
                                while (true) {
                                    if (change.isConsumed) return@awaitEachGesture
                                    dx = change.position.x - down.position.x
                                    val blocked = (dx > 0f && !currentCanBackward) ||
                                        (dx < 0f && !currentCanForward)
                                    val nowArmed = swipeCommits(dx, width, blocked)
                                    if (nowArmed && !thresholdAnnounced && change.pressed) {
                                        haptics.play(PlayerHaptic.THRESHOLD)
                                        thresholdAnnounced = true
                                    }
                                    armed = nowArmed
                                    if (blocked && abs(dx) > rejectTravel) rejected = true
                                    // Finger movement is read directly by the layer; no coroutine
                                    // or animation is allocated for each pointer event.
                                    dragTravel = swipeShown(dx, blocked)
                                    change.consume()
                                    if (change.changedToUpIgnoreConsumed()) break
                                    change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                        ?: return@awaitEachGesture
                                }
                                val shown = dragTravel ?: 0f
                                val forward = dx < 0f
                                val changesTrack = armed && currentSkipChangesTrack(forward)
                                if (armed) {
                                    haptics.play(PlayerHaptic.TAP)
                                    if (changesTrack) pendingSkip = forward
                                } else if (rejected) {
                                    haptics.play(PlayerHaptic.REJECT)
                                }
                                skipJob = scope.launch {
                                    travel.snapTo(shown)
                                    tilt.snapTo(if (reduceMotion) 0f else shown / width * SWIPE_TILT_DEGREES)
                                    dragTravel = null
                                    launch { tilt.animateTo(0f, returnSpec) }
                                    if (changesTrack) {
                                        travel.animateTo(
                                            targetValue = if (forward) -(width + gap) else width + gap,
                                            animationSpec = tween(if (reduceMotion) 0 else SWIPE_OUT_MS),
                                        )
                                        // Playback can cross the previous-button restart
                                        // threshold while the outgoing cover is animating.
                                        val stillChangesTrack = currentSkipChangesTrack(forward)
                                        currentOnSkip(forward)
                                        if (!stillChangesTrack) {
                                            pendingSkip = null
                                            travel.animateTo(0f, returnSpec)
                                            swiping = false
                                        }
                                    } else {
                                        if (armed) currentOnSkip(forward)
                                        travel.animateTo(0f, returnSpec)
                                        swiping = false
                                    }
                                }
                                finished = true
                            }
                            ArtworkDragAxis.VERTICAL -> {
                                collapsing = true
                                var thresholdAnnounced = false
                                var dy: Float
                                while (true) {
                                    if (change.isConsumed) return@awaitEachGesture
                                    dy = change.position.y - down.position.y
                                    if (collapseCommits(dy, collapseThreshold) && !thresholdAnnounced && change.pressed) {
                                        haptics.play(PlayerHaptic.THRESHOLD)
                                        thresholdAnnounced = true
                                    }
                                    currentOnCollapseDrag(collapseShown(dy))
                                    change.consume()
                                    if (change.changedToUpIgnoreConsumed()) break
                                    change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                        ?: return@awaitEachGesture
                                }
                                if (collapseCommits(dy, collapseThreshold)) {
                                    haptics.play(PlayerHaptic.TAP)
                                    currentOnCollapse()
                                } else {
                                    currentOnCollapseDrag(0f)
                                }
                                finished = true
                            }
                            ArtworkDragAxis.CANCELLED -> Unit
                        }
                    } finally {
                        settlePress()
                        if (!finished) {
                            if (collapsing) currentOnCollapseDrag(0f)
                            val shown = dragTravel
                            dragTravel = null
                            if (shown != null) {
                                skipJob = scope.launch {
                                    travel.snapTo(shown)
                                    launch { tilt.animateTo(0f, returnSpec) }
                                    travel.animateTo(0f, returnSpec)
                                    swiping = false
                                }
                            }
                        }
                    }
                }
            }
            .drawWithContent {
                drawContent()
                val progress = if (ownsMotion) holdRing.value else 0f
                if (progress <= 0f) return@drawWithContent
                val radius = HOLD_RING_RADIUS.toPx()
                val stroke = Stroke(width = HOLD_RING_STROKE.toPx())
                drawCircle(color = ringTrack, radius = radius, center = holdCenter, style = stroke)
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(holdCenter.x - radius, holdCenter.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = stroke,
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = if (ownsMotion) dragTravel ?: travel.value else 0f
                    scaleX = if (ownsMotion) pressScale.value else 1f
                    scaleY = scaleX
                },
        ) {
            val gapPx = with(density) { GHOST_GAP.toPx() }
            neighbourTrack(false)?.let { neighbour ->
                GhostCover(
                    uri = neighbour.artworkUri,
                    forward = false,
                    gapPx = gapPx,
                    travel = { if (ownsMotion && swiping) dragTravel ?: travel.value else 0f },
                    reduceMotion = reduceMotion,
                )
            }
            neighbourTrack(true)?.let { neighbour ->
                GhostCover(
                    uri = neighbour.artworkUri,
                    forward = true,
                    gapPx = gapPx,
                    travel = { if (ownsMotion && swiping) dragTravel ?: travel.value else 0f },
                    reduceMotion = reduceMotion,
                )
            }
            AnimatedContent(
                targetState = track,
                modifier = Modifier.graphicsLayer {
                    rotationZ = if (reduceMotion || !ownsMotion) 0f else {
                        dragTravel?.let { it / size.width * SWIPE_TILT_DEGREES } ?: tilt.value
                    }
                },
                contentKey = { it?.id },
                transitionSpec = {
                    // Swipes already moved the cover; the real one just takes the ghost's place.
                    if (arrivedBySwipe) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        motionFadeThrough(reduceMotion)
                    }
                },
                label = "player-artwork",
            ) { shown ->
                FlipCard(
                    angle = { if (ownsMotion) angle.value else if (flipped) 180f else 0f },
                    backFacing = if (ownsMotion) backFacing else flipped,
                    front = { CoverFace(uri = shown?.artworkUri) },
                    back = details,
                    modifier = Modifier.inactiveForMotion(shown?.id != track?.id),
                )
            }
        }
    }
}

/** Front and back of one card, turned about the vertical axis; only the nearer face is drawn. */
@Composable
private fun FlipCard(
    angle: () -> Float,
    backFacing: Boolean,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = angle()
                cameraDistance = FLIP_CAMERA_DISTANCE * density
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (angle() <= 90f) 1f else 0f },
        ) {
            front()
        }
        // Compose the details only after the back faces the listener, retaining it until the
        // reverse flip passes halfway. This also prevents invisible buttons receiving input.
        if (backFacing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (angle() > 90f) 1f else 0f
                        rotationY = 180f
                    },
            ) {
                back()
            }
        }
    }
}

@Composable
private fun CoverFace(uri: String?, fadeIn: Boolean = false) {
    val context = LocalPlatformContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val request = remember(context, uri, fadeIn) {
        ImageRequest.Builder(context)
            .data(uri)
            // The incoming preview already decoded this image. Reuse it synchronously when
            // AnimatedContent installs the real card, including its first loading frame.
            .memoryCacheKey("player-cover:$uri")
            .placeholderMemoryCacheKey("player-cover:$uri")
            .crossfade(if (fadeIn) 120 else 0)
            .build()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .shadow(
                elevation = if (dark) COVER_ELEVATION else 8.dp,
                shape = RoundedCornerShape(COVER_RADIUS),
                clip = false,
                ambientColor = if (dark) Color.Black else Color.Black.copy(alpha = 0.12f),
                spotColor = if (dark) Color.Black else Color.Black.copy(alpha = 0.18f),
            )
            .clip(RoundedCornerShape(COVER_RADIUS))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (uri != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Prepared offscreen; distance-driven opacity makes both reveal and cancellation continuous. */
@Composable
private fun GhostCover(
    uri: String?,
    forward: Boolean,
    gapPx: Float,
    travel: () -> Float,
    reduceMotion: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = (size.width + gapPx) * if (forward) 1f else -1f
                val reveal = artworkNeighbourReveal(travel(), size.width, forward)
                alpha = reveal
                val scale = if (reduceMotion) 1f else 0.97f + 0.03f * reveal
                scaleX = scale
                scaleY = scale
            },
    ) {
        CoverFace(uri = uri, fadeIn = !reduceMotion)
    }
}

private val ARTWORK_SLOP: Dp = 12.dp
private val COLLAPSE_THRESHOLD: Dp = 140.dp
private val REJECT_TRAVEL: Dp = 40.dp
private val GHOST_GAP: Dp = 24.dp
private val COVER_RADIUS: Dp = 24.dp
private val COVER_ELEVATION: Dp = 18.dp
private val HOLD_RING_RADIUS: Dp = 27.dp
private val HOLD_RING_STROKE: Dp = 3.dp
private const val HOLD_MS = 450
private const val HOLD_SINK = 0.97f
private const val SWIPE_OUT_MS = 180
private const val SKIP_SETTLE_TIMEOUT_MS = 1_500L
private const val FLIP_MS = 360
private const val FLIP_CAMERA_DISTANCE = 12f
private const val SWIPE_TILT_DEGREES = 6f
private val FLIP_TWEEN = tween<Float>(FLIP_MS, easing = FastOutSlowInEasing)
private val SPRING_BACK = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
