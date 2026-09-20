/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.cd_mode_button
import io.github.nikitasud.latentjam.app.generated.resources.cd_skip_next_hold
import io.github.nikitasud.latentjam.app.generated.resources.cd_skip_previous_hold
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_queue_length
import io.github.nikitasud.latentjam.app.generated.resources.player_mode_off
import io.github.nikitasud.latentjam.app.generated.resources.player_mode_shuffle
import io.github.nikitasud.latentjam.app.generated.resources.player_mode_smart
import io.github.nikitasud.latentjam.app.generated.resources.player_mode_title
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.ShuffleMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A button that answers a tap and a hold differently, with the ripple and press scale of an
 * ordinary button.
 *
 * The [interactionSource] receives the press so `indication` and [scaleOnPress] see it. A
 * release before [holdMs] is [onTap]; holding past it runs [onHoldStart] once and [onHoldEnd]
 * on the release that follows. A press the parent takes over (a scroll) is neither.
 */
internal fun Modifier.tapOrHold(
    interactionSource: MutableInteractionSource,
    holdMs: Long,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
): Modifier = pointerInput(interactionSource, holdMs) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val press = PressInteraction.Press(down.position)
        interactionSource.tryEmit(press)
        var taken = false
        val up = withTimeoutOrNull(holdMs) {
            waitForUpOrCancellation().also { if (it == null) taken = true }
        }
        when {
            up != null -> {
                interactionSource.tryEmit(PressInteraction.Release(press))
                onTap()
            }
            taken -> interactionSource.tryEmit(PressInteraction.Cancel(press))
            else -> {
                onHoldStart()
                waitForUpOrCancellation()
                interactionSource.tryEmit(PressInteraction.Release(press))
                onHoldEnd()
            }
        }
    }
}

/**
 * Previous/next: a tap skips, a hold scans through the track at 4× and faster.
 *
 * Scanning seeks every quarter second from a position this button keeps for itself, started
 * from the last known position; nothing here observes the ticker.
 */
@Composable
internal fun SkipButton(
    forward: Boolean,
    playback: PlaybackController,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val currentDuration by rememberUpdatedState(durationMs)
    var scan by remember { mutableStateOf<Job?>(null) }
    val description = stringResource(
        if (forward) Res.string.cd_skip_next_hold else Res.string.cd_skip_previous_hold,
    )
    val skip: () -> Unit = {
        haptics.play(PlayerHaptic.TAP)
        scope.launch { if (forward) playback.next() else playback.previous() }
    }
    Box(
        modifier = modifier
            .size(SKIP_BUTTON_SIZE)
            .clip(CircleShape)
            .indication(interaction, ripple(bounded = false, radius = SKIP_BUTTON_SIZE / 2))
            .scaleOnPress(interaction)
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick { skip(); true }
            }
            .tapOrHold(
                interactionSource = interaction,
                holdMs = SKIP_HOLD_MS,
                onTap = skip,
                onHoldStart = {
                    haptics.play(PlayerHaptic.HOLD)
                    scan?.cancel()
                    scan = scope.launch {
                        val direction = if (forward) 1 else -1
                        val duration = currentDuration.coerceAtLeast(0L)
                        var position = playback.state.value.positionMs.coerceIn(0L, duration)
                        var heldMs = 0L
                        var edgeReported = false
                        while (isActive) {
                            val step = SCAN_TICK_MS * skipHoldMultiplier(heldMs) * direction
                            position = (position + step).coerceIn(0L, duration)
                            playback.seekTo(position)
                            val atEdge = position == 0L || position == duration
                            if (atEdge && !edgeReported) {
                                haptics.play(PlayerHaptic.EDGE)
                                edgeReported = true
                            }
                            delay(SCAN_TICK_MS)
                            heldMs += SCAN_TICK_MS
                        }
                    }
                },
                onHoldEnd = {
                    scan?.cancel()
                    scan = null
                    haptics.play(PlayerHaptic.RELEASE)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (forward) Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious,
            contentDescription = null,
            modifier = Modifier.size(SKIP_ICON_SIZE),
        )
    }
}

/**
 * The playback-mode button: in order, shuffle, or SMART.
 *
 * A tap cycles the three, as it always has. A hold opens the choice directly — the three modes
 * with the current one ticked, and below them how long a SMART queue should be. SMART wears
 * the app's own mark; plain shuffle keeps the standard glyph, so the states never rely on tint.
 */
@Composable
internal fun ModeButton(
    mode: ShuffleMode,
    onCycle: () -> Unit,
    onSelect: (ShuffleMode) -> Unit,
    smartQueueLength: Int,
    onSmartQueueLength: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    var menuOpen by remember { mutableStateOf(false) }
    val tint by animateColorAsState(
        targetValue = when (mode) {
            ShuffleMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
            ShuffleMode.ON -> MaterialTheme.colorScheme.primary
            ShuffleMode.SMART -> MaterialTheme.colorScheme.tertiary
        },
        animationSpec = tween(if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS),
        label = "mode-tint",
    )
    val modeName = stringResource(mode.labelRes())
    val description = stringResource(Res.string.cd_mode_button, modeName)
    val cycle: () -> Unit = {
        haptics.play(PlayerHaptic.TAP)
        onCycle()
    }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(MODE_BUTTON_SIZE)
                .clip(CircleShape)
                .indication(interaction, ripple(bounded = false, radius = MODE_BUTTON_SIZE / 2))
                .scaleOnPress(interaction)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                    onClick { cycle(); true }
                }
                .tapOrHold(
                    interactionSource = interaction,
                    holdMs = MODE_HOLD_MS,
                    onTap = cycle,
                    onHoldStart = {
                        haptics.play(PlayerHaptic.HOLD)
                        menuOpen = true
                    },
                    onHoldEnd = {},
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = { motionIconTransform(reduceMotion) },
                label = "mode-glyph",
            ) { shownMode ->
                Icon(
                    imageVector = shownMode.glyph(),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.inactiveForMotion(shownMode != mode),
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                text = stringResource(Res.string.player_mode_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            ShuffleMode.entries.forEach { option ->
                val selected = option == mode
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    leadingIcon = {
                        Icon(
                            imageVector = option.glyph(),
                            contentDescription = null,
                            tint = if (option == ShuffleMode.SMART) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        menuOpen = false
                        haptics.play(PlayerHaptic.TAP)
                        if (!selected) onSelect(option)
                    },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(Res.string.intelligence_queue_length),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SMART_QUEUE_LENGTH_OPTIONS.forEach { length ->
                    FilterChip(
                        selected = length == smartQueueLength,
                        onClick = {
                            haptics.play(PlayerHaptic.TAP)
                            onSmartQueueLength(length)
                        },
                        label = {
                            Text(pluralStringResource(Res.plurals.count_tracks, length, length))
                        },
                    )
                }
            }
        }
    }
}

private fun ShuffleMode.labelRes() = when (this) {
    ShuffleMode.OFF -> Res.string.player_mode_off
    ShuffleMode.ON -> Res.string.player_mode_shuffle
    ShuffleMode.SMART -> Res.string.player_mode_smart
}

private fun ShuffleMode.glyph(): ImageVector = when (this) {
    ShuffleMode.OFF -> Icons.Rounded.Straight
    ShuffleMode.ON -> Icons.Rounded.Shuffle
    ShuffleMode.SMART -> LatentJamMark
}

private val SKIP_BUTTON_SIZE = 56.dp
private val SKIP_ICON_SIZE = 36.dp
private val MODE_BUTTON_SIZE = 48.dp
private const val SKIP_HOLD_MS = 420L
private const val MODE_HOLD_MS = 450L
private const val SCAN_TICK_MS = 250L
