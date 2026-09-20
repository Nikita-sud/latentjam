/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.cd_seek_position
import io.github.nikitasud.latentjam.app.generated.resources.cd_time_toggle
import io.github.nikitasud.latentjam.app.generated.resources.seek_fine_half
import io.github.nikitasud.latentjam.app.generated.resources.seek_fine_quarter
import io.github.nikitasud.latentjam.library.tags.Lyrics
import io.github.nikitasud.latentjam.playback.PlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A thin seek line with a bar for a handle, alive under the finger.
 *
 * Touching the band puts the handle there; dragging moves it, and sliding the finger below the
 * band slows the scrub to half and then a quarter speed for landing on a moment. A bubble above
 * the handle shows the time being scrubbed to, and the lyric line at that time when the song
 * carries timed lyrics that are already loaded. The right-hand time toggles between the total
 * and the remaining time.
 *
 * This is the only expanded-player subtree that observes the coarse position ticker.
 */
@Composable
internal fun PlayerSeekBar(
    playback: PlaybackController,
    durationMs: Long,
    lyrics: Lyrics?,
    modifier: Modifier = Modifier,
) {
    val positionMs by remember(playback) {
        playback.state.map { it.positionMs }.distinctUntilChanged()
    }.collectAsState(playback.state.value.positionMs)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val duration = durationMs.coerceAtLeast(1L)
    // The finger's own position while scrubbing, kept a moment after release so the coarse
    // ticker cannot snap the handle back to where it was before the seek landed.
    var overrideMs by remember { mutableStateOf<Long?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var fine by remember { mutableIntStateOf(1) }
    var showRemaining by remember { mutableStateOf(false) }
    var bandWidth by remember { mutableIntStateOf(0) }
    val shownMs = (overrideMs ?: positionMs).coerceIn(0L, duration)
    LaunchedEffect(overrideMs, scrubbing) {
        val held = overrideMs ?: return@LaunchedEffect
        if (scrubbing) return@LaunchedEffect
        delay(SEEK_SETTLE_MS)
        if (overrideMs == held) overrideMs = null
    }

    val lineHeight by animateDpAsState(
        targetValue = if (scrubbing) LINE_SCRUBBING else LINE_RESTING,
        animationSpec = tween(Motion.QUICK_MS),
        label = "seek-line",
    )
    val handleHeight by animateDpAsState(
        targetValue = if (scrubbing) HANDLE_SCRUBBING else HANDLE_RESTING,
        animationSpec = tween(Motion.QUICK_MS),
        label = "seek-handle",
    )
    val timeline = remember(lyrics) {
        lyrics?.takeIf { it.synced }?.let { LyricsTimeline(it.lines) }
    }
    val lyricAtHandle = if (scrubbing && timeline != null && lyrics != null) {
        lyrics.lines.getOrNull(timeline.activeIndexAt(shownMs))?.text?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    val playedColor = MaterialTheme.colorScheme.onSurface
    val remainingColor = playedColor.copy(alpha = REMAINING_ALPHA)
    val seekDescription = stringResource(Res.string.cd_seek_position)
    val toggleDescription = stringResource(Res.string.cd_time_toggle)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAND_HEIGHT)
                .onSizeChanged { bandWidth = it.width }
                .semantics {
                    contentDescription = seekDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = shownMs.toFloat(),
                        range = 0f..duration.toFloat(),
                    )
                    setProgress { target ->
                        val clamped = target.roundToLong().coerceIn(0L, duration)
                        overrideMs = clamped
                        scope.launch { playback.seekTo(clamped) }
                        true
                    }
                }
                .pointerInput(duration) {
                    val halfAt = SCRUB_HALF_AT.toPx()
                    val quarterAt = SCRUB_QUARTER_AT.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val width = size.width.toFloat()
                        val bandCentre = size.height / 2f
                        var target = ((down.position.x / width).coerceIn(0f, 1f) * duration)
                            .roundToLong()
                        haptics.play(PlayerHaptic.TAP)
                        scrubbing = true
                        fine = 1
                        overrideMs = target
                        var lastX = down.position.x
                        var edge = 0
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            if (change.changedToUpIgnoreConsumed()) break
                            change.consume()
                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            val factor = scrubFineFactor(
                                dyBelowBar = change.position.y - bandCentre,
                                halfAt = halfAt,
                                quarterAt = quarterAt,
                            )
                            target = (target + scrubDeltaMs(dx, width, duration, factor))
                                .coerceIn(0L, duration)
                            val atEdge = when (target) {
                                0L -> -1
                                duration -> 1
                                else -> 0
                            }
                            if (atEdge != 0 && atEdge != edge) haptics.play(PlayerHaptic.EDGE)
                            edge = atEdge
                            fine = factor
                            overrideMs = target
                        }
                        haptics.play(PlayerHaptic.RELEASE)
                        scrubbing = false
                        scope.launch { playback.seekTo(target) }
                    }
                }
                .drawBehind {
                    val line = lineHeight.toPx()
                    val handleW = HANDLE_WIDTH.toPx()
                    val handleH = handleHeight.toPx()
                    val gap = SEGMENT_GAP.toPx()
                    val centreY = size.height / 2f
                    val handleX = (shownMs.toFloat() / duration) * (size.width - handleW)
                    val remainingStart = handleX + handleW + gap
                    if (remainingStart < size.width) {
                        drawRoundRect(
                            color = remainingColor,
                            topLeft = Offset(remainingStart, centreY - line / 2f),
                            size = Size(size.width - remainingStart, line),
                            cornerRadius = CornerRadius(line / 2f),
                        )
                    }
                    val playedWidth = handleX - gap
                    if (playedWidth > 0f) {
                        drawRoundRect(
                            color = playedColor,
                            topLeft = Offset(0f, centreY - line / 2f),
                            size = Size(playedWidth, line),
                            cornerRadius = CornerRadius(line / 2f),
                        )
                    }
                    drawRoundRect(
                        color = playedColor,
                        topLeft = Offset(handleX, centreY - handleH / 2f),
                        size = Size(handleW, handleH),
                        cornerRadius = CornerRadius(handleW / 2f),
                    )
                },
        ) {
            if (scrubbing) {
                val handleCentre = with(androidx.compose.ui.platform.LocalDensity.current) {
                    val handleW = HANDLE_WIDTH.toPx()
                    (shownMs.toFloat() / duration) * (bandWidth - handleW) + handleW / 2f
                }
                ScrubBubble(
                    time = formatDuration(shownMs),
                    fineLabel = when (fine) {
                        2 -> stringResource(Res.string.seek_fine_half)
                        4 -> stringResource(Res.string.seek_fine_quarter)
                        else -> null
                    },
                    lyric = lyricAtHandle,
                    handleCentreX = handleCentre,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatDuration(shownMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (showRemaining) {
                    "−" + formatDuration(duration - shownMs)
                } else {
                    formatDuration(durationMs)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = toggleDescription) {
                        haptics.play(PlayerHaptic.TAP)
                        showRemaining = !showRemaining
                    }
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

/** Floats above the handle, clamped to the band's edges so it never leaves the screen. */
@Composable
private fun ScrubBubble(
    time: String,
    fineLabel: String?,
    lyric: String?,
    handleCentreX: Float,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val gap = BUBBLE_GAP.roundToPx()
            layout(constraints.maxWidth, 0) {
                val x = (handleCentreX - placeable.width / 2f).roundToLong().toInt()
                    .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                placeable.placeRelative(x, -placeable.height - gap)
            }
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = time, style = MaterialTheme.typography.titleSmall)
                if (fineLabel != null) {
                    Text(
                        text = fineLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
            }
            if (lyric != null) {
                Text(
                    text = lyric,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = LYRIC_MAX_WIDTH),
                )
            }
        }
    }
}

private val BAND_HEIGHT: Dp = 44.dp
private val LINE_RESTING: Dp = 4.dp
private val LINE_SCRUBBING: Dp = 6.dp
private val HANDLE_RESTING: Dp = 26.dp
private val HANDLE_SCRUBBING: Dp = 34.dp
private val HANDLE_WIDTH: Dp = 4.dp
private val SEGMENT_GAP: Dp = 6.dp
private val SCRUB_HALF_AT: Dp = 40.dp
private val SCRUB_QUARTER_AT: Dp = 90.dp
private val BUBBLE_GAP: Dp = 8.dp
private val LYRIC_MAX_WIDTH: Dp = 230.dp
private const val REMAINING_ALPHA = 0.22f
private const val SEEK_SETTLE_MS = 700L
