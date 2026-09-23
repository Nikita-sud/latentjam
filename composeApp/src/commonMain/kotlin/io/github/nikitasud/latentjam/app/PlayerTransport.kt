/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.cd_mode_button
import io.github.nikitasud.latentjam.app.generated.resources.cd_skip_next_hold
import io.github.nikitasud.latentjam.app.generated.resources.cd_skip_previous_hold
import io.github.nikitasud.latentjam.app.generated.resources.count_tracks
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_queue_length
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_how_it_works
import io.github.nikitasud.latentjam.app.generated.resources.intelligence_how_title
import io.github.nikitasud.latentjam.app.generated.resources.player_mode_off
import io.github.nikitasud.latentjam.app.generated.resources.player_mode_shuffle
import io.github.nikitasud.latentjam.app.generated.resources.player_mode_smart
import io.github.nikitasud.latentjam.app.generated.resources.player_mode_title
import io.github.nikitasud.latentjam.playback.PlaybackController
import io.github.nikitasud.latentjam.playback.ShuffleMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A button that answers a tap and a hold differently, with the ripple and press scale of an
 * ordinary button.
 *
 * The [interactionSource] receives the press so `indication` and [scaleOnPress] see it. A
 * release before [holdMs] is [onTap]; holding past it runs [onHoldStart] once and [onHoldEnd]
 * on every exit, with whether the pointer was released normally. A press the parent takes over
 * before the hold threshold (a scroll) is neither.
 */
@Composable
internal fun Modifier.tapOrHold(
    interactionSource: MutableInteractionSource,
    holdMs: Long,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: (released: Boolean) -> Unit,
    gestureKey: Any? = null,
): Modifier {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnHoldStart by rememberUpdatedState(onHoldStart)
    val currentOnHoldEnd by rememberUpdatedState(onHoldEnd)
    return pointerInput(interactionSource, holdMs, gestureKey) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val press = PressInteraction.Press(down.position)
            interactionSource.tryEmit(press)
            var holding = false
            var released = false
            try {
                var taken = false
                val up = withTimeoutOrNull(holdMs) {
                    waitForUpOrCancellation().also { if (it == null) taken = true }
                }
                when {
                    up != null -> {
                        up.consume()
                        released = true
                        currentOnTap()
                    }
                    !taken -> {
                        holding = true
                        currentOnHoldStart()
                        val release = waitForUpOrCancellation()
                        release?.consume()
                        released = release != null
                    }
                }
            } finally {
                interactionSource.tryEmit(
                    if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                )
                // Pointer input can be cancelled without an up event when a sheet opens or the
                // surface leaves composition. A held scan must stop on every exit path.
                if (holding) currentOnHoldEnd(released)
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
    active: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val currentDuration by rememberUpdatedState(durationMs)
    val trackIdentity by remember(playback) {
        playback.state.map { it.track?.id to it.queueIndex }.distinctUntilChanged()
    }.collectPlayerState(playback.state.value.let { it.track?.id to it.queueIndex }, active)
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
                gestureKey = trackIdentity to active,
                onTap = skip,
                onHoldStart = {
                    scan?.cancel()
                    scan = null
                    val live = playback.state.value
                    val duration = currentDuration
                    if (live.track != null && duration > 0L) {
                        val atEdge = if (forward) live.positionMs >= duration else live.positionMs <= 0L
                        if (atEdge) {
                            haptics.play(PlayerHaptic.EDGE)
                        } else {
                            haptics.play(PlayerHaptic.HOLD)
                            scan = scope.launch {
                                scanPlayerTrack(
                                    forward = forward,
                                    durationMs = duration,
                                    state = { playback.state.value },
                                    seek = playback::seekTo,
                                    onEdge = { haptics.play(PlayerHaptic.EDGE) },
                                )
                            }
                        }
                    }
                },
                onHoldEnd = { released ->
                    val wasScanning = scan?.isActive == true
                    scan?.cancel()
                    scan = null
                    // Cancellation still stops the scan, but only a real release ends the
                    // gesture with feedback. An edge already supplied its own final tick.
                    if (released && wasScanning) haptics.play(PlayerHaptic.RELEASE)
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
 * A tap cycles modes immediately; a hold opens the named choices and SMART queue settings.
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
    val menuDescription = stringResource(Res.string.player_mode_title)
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
                    onLongClick(label = menuDescription) { menuOpen = true; true }
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
        PlaybackModeMenu(
            expanded = menuOpen,
            mode = mode,
            smartQueueLength = smartQueueLength,
            onDismiss = { menuOpen = false },
            onSelect = { option ->
                menuOpen = false
                if (option != mode) {
                    haptics.play(PlayerHaptic.TAP)
                    onSelect(option)
                }
            },
            onSmartQueueLength = { length ->
                if (length != smartQueueLength) {
                    haptics.play(PlayerHaptic.TAP)
                    onSmartQueueLength(length)
                }
            },
        )
    }
}

/** An anchored, scrollable chooser: quick choices first, explanation only when requested. */
@Composable
private fun PlaybackModeMenu(
    expanded: Boolean,
    mode: ShuffleMode,
    smartQueueLength: Int,
    onDismiss: () -> Unit,
    onSelect: (ShuffleMode) -> Unit,
    onSmartQueueLength: (Int) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    var showExplanation by remember(expanded) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (showExplanation) 180f else 0f,
        animationSpec = tween(if (reduceMotion) Motion.REDUCED_MS else Motion.QUICK_MS),
        label = "mode-help-chevron",
    )
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(304.dp),
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = stringResource(Res.string.player_mode_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        Column(Modifier.padding(horizontal = 8.dp).selectableGroup()) {
            ShuffleMode.entries.forEach { option ->
                val selected = option == mode
                val ink = if (option == ShuffleMode.SMART) {
                    MaterialTheme.colorScheme.tertiary
                } else MaterialTheme.colorScheme.onSurface
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) ink.copy(alpha = 0.08f) else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton) { onSelect(option) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(option.glyph(), null, Modifier.size(22.dp), tint = ink)
                    Text(
                        stringResource(option.labelRes()),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        if (selected) Icon(Icons.Outlined.Check, null, tint = ink)
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Text(
            text = stringResource(Res.string.intelligence_queue_length),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SMART_QUEUE_LENGTH_OPTIONS.forEach { length ->
                val selected = length == smartQueueLength
                val label = pluralStringResource(Res.plurals.count_tracks, length, length)
                val background by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    tween(if (reduceMotion) Motion.REDUCED_MS else Motion.QUICK_MS),
                    label = "queue-length-$length",
                )
                Box(
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(background)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            onSmartQueueLength(length)
                        }
                        .semantics { contentDescription = label }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        length.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        TextButton(
            onClick = { showExplanation = !showExplanation },
            modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Text(
                stringResource(Res.string.intelligence_how_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.ExpandMore, null,
                Modifier.size(20.dp).graphicsLayer { rotationZ = chevronRotation })
        }
        AnimatedVisibility(
            visible = showExplanation,
            enter = motionAppearEnter(),
            exit = motionAppearExit(),
        ) {
            Text(
                stringResource(Res.string.intelligence_how_it_works),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            )
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
