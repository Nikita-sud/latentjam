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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.cd_player_artwork
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * The cover as the player's main control surface.
 *
 * A tap turns it over to the track's details, a hold opens the actions sheet, a sideways swipe
 * carries the cover off with the neighbour peeking in behind it, and a pull downwards hands the
 * whole player to [onCollapseDrag]. Every per-frame value — travel, tilt, press scale, the hold
 * ring, the flip angle — lives in an [Animatable] read inside a layer or draw lambda, so a gesture
 * never recomposes the screen. Composition changes only when a gesture starts or ends, when the
 * card turns over, or when the track itself changes.
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
    /** Cover of the track a swipe in that direction would reach; null shows nothing peeking. */
    neighbourArtwork: (forward: Boolean) -> String?,
    /** Called with the resisted pull in px while the finger drags down, and with 0f on release. */
    onCollapseDrag: (Float) -> Unit,
    onCollapse: () -> Unit,
    details: @Composable () -> Unit,
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
    // Neighbour covers are composed only while a swipe is in flight: a decoded bitmap on each
    // side of every player open would be paid for by every listener who never swipes.
    var swiping by remember { mutableStateOf(false) }
    var pendingSkip by remember { mutableStateOf<Boolean?>(null) }
    val currentFlipped by rememberUpdatedState(flipped)
    val currentOnFlip by rememberUpdatedState(onFlip)
    val currentOnHold by rememberUpdatedState(onHold)
    val currentOnSkip by rememberUpdatedState(onSkip)
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
    LaunchedEffect(track?.id) {
        if (pendingSkip != null) {
            travel.snapTo(0f)
            pendingSkip = null
            swiping = false
        }
    }
    LaunchedEffect(pendingSkip) {
        if (pendingSkip == null) return@LaunchedEffect
        withTimeoutOrNull(SKIP_SETTLE_TIMEOUT_MS) { awaitCancellation() } ?: run {
            pendingSkip = null
            swiping = false
            travel.animateTo(0f, SPRING_BACK)
        }
    }

    val description = stringResource(Res.string.cd_player_artwork)
    val ringColor = MaterialTheme.colorScheme.onSurface
    val ringTrack = ringColor.copy(alpha = 0.18f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                val slop = ARTWORK_SLOP.toPx()
                val collapseThreshold = COLLAPSE_THRESHOLD.toPx()
                val rejectTravel = REJECT_TRAVEL.toPx()
                val gap = GHOST_GAP.toPx()
                var holdJob: Job? = null
                fun settlePress() {
                    holdJob?.cancel()
                    scope.launch { holdRing.snapTo(0f) }
                    scope.launch { pressScale.animateTo(1f, SPRING_BACK) }
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (pendingSkip != null) return@awaitEachGesture
                    val width = size.width.toFloat()
                    holdCenter = down.position
                    holdJob = scope.launch {
                        launch { pressScale.animateTo(HOLD_SINK, tween(HOLD_MS, easing = LinearEasing)) }
                        holdRing.snapTo(0f)
                        holdRing.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                    }
                    var axis: ArtworkDragAxis? = null
                    var lastChange: PointerInputChange = down
                    var upSeen = false
                    // Undecided: the finger is still where it landed. A hold fires here; movement
                    // past the slop decides an axis; a release is a tap.
                    val holdDeadline = down.uptimeMillis + HOLD_MS
                    while (axis == null && !upSeen) {
                        val remaining = holdDeadline - lastChange.uptimeMillis
                        val event = withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
                        if (event == null) {
                            // The hold took: the sheet opens, the ring completes, the cover rises.
                            haptics.play(PlayerHaptic.HOLD)
                            settlePress()
                            currentOnHold()
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        lastChange = change
                        if (change.changedToUpIgnoreConsumed()) {
                            upSeen = true
                            break
                        }
                        val delta = change.position - down.position
                        axis = artworkDragAxis(delta.x, delta.y, slop)
                    }
                    if (upSeen) {
                        settlePress()
                        haptics.play(PlayerHaptic.TAP)
                        currentOnFlip(!currentFlipped)
                        return@awaitEachGesture
                    }
                    holdJob?.cancel()
                    scope.launch { holdRing.snapTo(0f) }
                    when (axis) {
                        ArtworkDragAxis.HORIZONTAL -> {
                            swiping = true
                            scope.launch { pressScale.animateTo(HOLD_SINK, SPRING_BACK) }
                            var armed = false
                            var rejected = false
                            var dx = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                                if (change.changedToUpIgnoreConsumed()) break
                                if (change.positionChange() != Offset.Zero) change.consume()
                                dx = change.position.x - down.position.x
                                val blocked = (dx > 0f && !currentCanBackward) ||
                                    (dx < 0f && !currentCanForward)
                                val nowArmed = swipeCommits(dx, width, blocked)
                                if (nowArmed && !armed) haptics.play(PlayerHaptic.THRESHOLD)
                                armed = nowArmed
                                if (blocked && abs(dx) > rejectTravel) rejected = true
                                val shown = swipeShown(dx, blocked)
                                scope.launch {
                                    travel.snapTo(shown)
                                    tilt.snapTo(shown / width * SWIPE_TILT_DEGREES)
                                }
                            }
                            scope.launch { pressScale.animateTo(1f, SPRING_BACK) }
                            if (armed) {
                                val forward = dx < 0f
                                haptics.play(PlayerHaptic.TAP)
                                pendingSkip = forward
                                scope.launch { tilt.animateTo(0f, tween(SWIPE_OUT_MS)) }
                                scope.launch {
                                    travel.animateTo(
                                        targetValue = if (forward) -(width + gap) else width + gap,
                                        animationSpec = tween(SWIPE_OUT_MS),
                                    )
                                    currentOnSkip(forward)
                                }
                            } else {
                                if (rejected) haptics.play(PlayerHaptic.REJECT)
                                scope.launch { tilt.animateTo(0f, SPRING_BACK) }
                                scope.launch {
                                    travel.animateTo(0f, SPRING_BACK)
                                    swiping = false
                                }
                            }
                        }
                        ArtworkDragAxis.VERTICAL -> {
                            scope.launch { pressScale.animateTo(1f, SPRING_BACK) }
                            var dy = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                                if (change.changedToUpIgnoreConsumed()) break
                                if (change.positionChange() != Offset.Zero) change.consume()
                                dy = change.position.y - down.position.y
                                currentOnCollapseDrag(collapseShown(dy))
                            }
                            if (collapseCommits(dy, collapseThreshold)) {
                                haptics.play(PlayerHaptic.TAP)
                                currentOnCollapse()
                            } else {
                                haptics.play(PlayerHaptic.RELEASE)
                                currentOnCollapseDrag(0f)
                            }
                        }
                        null -> settlePress()
                    }
                }
            }
            .drawWithContent {
                drawContent()
                val progress = holdRing.value
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
                    translationX = travel.value
                    scaleX = pressScale.value
                    scaleY = pressScale.value
                },
        ) {
            if (swiping) {
                val gapPx = with(density) { GHOST_GAP.toPx() }
                GhostCover(uri = neighbourArtwork(false), offsetX = { width -> -(width + gapPx) })
                GhostCover(uri = neighbourArtwork(true), offsetX = { width -> width + gapPx })
            }
            AnimatedContent(
                targetState = track,
                modifier = Modifier.graphicsLayer { rotationZ = tilt.value },
                contentKey = { it?.id },
                transitionSpec = {
                    // Swipes already moved the cover; the real one just takes the ghost's place.
                    if (pendingSkip != null) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        motionFadeThrough(reduceMotion)
                    }
                },
                label = "player-artwork",
            ) { shown ->
                FlipCard(
                    angle = { angle.value },
                    flipped = flipped,
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
    flipped: Boolean,
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
        // The back exists only while the card is turned, so its buttons can never be hit through
        // the cover, and the flip itself costs one composition per turn.
        if (flipped) {
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
private fun CoverFace(uri: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .shadow(elevation = COVER_ELEVATION, shape = RoundedCornerShape(COVER_RADIUS), clip = false)
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
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** A neighbour's cover, parked one card-width to the side so a swipe reveals it. */
@Composable
private fun GhostCover(uri: String?, offsetX: (widthPx: Float) -> Float) {
    if (uri == null) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = offsetX(size.width) }
            .clip(RoundedCornerShape(COVER_RADIUS)),
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
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
private const val SWIPE_OUT_MS = 280
private const val SKIP_SETTLE_TIMEOUT_MS = 1_500L
private const val FLIP_MS = 360
private const val FLIP_CAMERA_DISTANCE = 12f
private const val SWIPE_TILT_DEGREES = 6f
private val FLIP_TWEEN = tween<Float>(FLIP_MS, easing = FastOutSlowInEasing)
private val SPRING_BACK = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
