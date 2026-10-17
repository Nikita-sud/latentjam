/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/*
 * The app's controls, each defined once. They take their look from the player — the ink is
 * onSurface, never Material's primary/secondary roles — so a later accent setting has one place to
 * reach every control, and a stock default can no longer slip in screen by screen.
 */

/** The controls' ink: the listener's own accent when they chose one, else onSurface. */
@Composable
@ReadOnlyComposable
internal fun controlInk(): Color = LocalControlInk.current ?: MaterialTheme.colorScheme.onSurface

/** What reads on top of [controlInk]: the floor colour for neutral ink, black or white for an accent. */
@Composable
@ReadOnlyComposable
internal fun onControlInk(): Color = LocalControlInk.current
    ?.let { if (it.luminance() > 0.4f) Color(0xFF141414) else Color.White }
    ?: MaterialTheme.colorScheme.surface

/** Every Play button: the shuffle FAB's light disc with a dark glyph. Shape stays the caller's. */
@Composable
internal fun playButtonColors(): IconButtonColors = IconButtonDefaults.filledIconButtonColors(
    containerColor = controlInk(),
    contentColor = onControlInk(),
)

@Composable
internal fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = onControlInk(),
            checkedTrackColor = controlInk(),
            checkedBorderColor = controlInk(),
            uncheckedThumbColor = colors.onSurfaceVariant,
            uncheckedTrackColor = colors.surfaceContainerHighest,
            uncheckedBorderColor = colors.outline,
        ),
    )
}

/**
 * The player's seek bar as a setting: a 4 dp line, a 4 x 26 dp bar for a handle with a 6 dp gap
 * on each side, the rest of the line at 22 %. No stop dot; ticks only on stepped sliders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val ink = controlInk()
    val line = MaterialTheme.colorScheme.onSurface
    val sliderColors = SliderDefaults.colors(
        thumbColor = ink,
        activeTrackColor = ink,
        inactiveTrackColor = line.copy(alpha = 0.22f),
        activeTickColor = onControlInk().copy(alpha = 0.6f),
        inactiveTickColor = line.copy(alpha = 0.5f),
    )
    val interactionSource = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = sliderColors,
        interactionSource = interactionSource,
        steps = steps,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                colors = sliderColors,
                enabled = enabled,
                thumbSize = DpSize(4.dp, 26.dp),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(4.dp),
                colors = sliderColors,
                enabled = enabled,
                drawStopIndicator = null,
                thumbTrackGapSize = 6.dp,
            )
        },
        valueRange = valueRange,
    )
}

/** One choice among a few (a period, a lens, a preset): outlined pill, the choice filled with ink. */
@Composable
internal fun AppChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.heightIn(min = 34.dp),
        shape = RoundedCornerShape(17.dp.corner()),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.onSurfaceVariant,
            selectedContainerColor = controlInk(),
            selectedLabelColor = onControlInk(),
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = colors.outlineVariant,
            selectedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
internal fun AppRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    RadioButton(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = RadioButtonDefaults.colors(
            selectedColor = controlInk(),
            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
internal fun AppCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = controlInk(),
            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkmarkColor = onControlInk(),
        ),
    )
}

/**
 * A progress line in the mini player's manner: one ink line on a faint track, round ends, no gap
 * and no stop dot. [progress] null is the indeterminate form.
 */
@Composable
internal fun AppLinearProgress(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
) {
    val ink = controlInk()
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    if (progress != null) {
        LinearProgressIndicator(
            progress = progress,
            modifier = modifier,
            color = ink,
            trackColor = track,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier,
            color = ink,
            trackColor = track,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    }
}

@Composable
internal fun AppSpinner(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 4.dp,
    color: Color = controlInk(),
) {
    CircularProgressIndicator(
        modifier = modifier,
        color = color,
        strokeWidth = strokeWidth,
        trackColor = Color.Transparent,
        strokeCap = StrokeCap.Round,
    )
}

/** The one rule between groups: the letter seam's 7 % ink, on any surface. */
@Composable
internal fun AppDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
}
