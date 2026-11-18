/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * The parts of the player's controls a short window may go without, in the order they are given up: the cover,
 * where it stands above the controls in the same column (a window too short for both shows what can be used);
 * what comes next (the queue's own strip under the player says the same); where the queue came from; the spaces
 * between the rows; and last the artist and album (the queue and the notification still name them). The title,
 * the seek bar and the transport are always shown.
 */
internal enum class PlayerControlsPart {
    /** The cover above the controls, and the space under it. */
    COVER,

    /** The "next up" row and the space that sets it off. */
    NEXT_UP,

    /** The "playing from" chip. */
    SOURCE,

    /** The spaces between the rows. */
    GAPS,

    /** The artist and album row. */
    ARTIST,
}

/**
 * Which of the controls' rows are shown in a window [available] pixels tall. [heights] are the rows' own heights,
 * top to bottom, and [parts] says which of them may be left out (null: never). Rows are given up in the order of
 * [PlayerControlsPart], a whole part at a time, until the rest fits; when even the rows that must stay do not
 * fit, those are all shown and the caller scrolls.
 *
 * Measured, not estimated: the rows' heights depend on the font, the text scale and the language, and a phone on
 * its side has so little height that a guess a few pixels off put the play button under the queue.
 */
internal fun fittedPlayerControls(heights: List<Int>, parts: List<PlayerControlsPart?>, available: Int): List<Boolean> {
    val shown = MutableList(heights.size) { true }
    for (part in PlayerControlsPart.entries) {
        val needed = heights.indices.sumOf { if (shown[it]) heights[it] else 0 }
        if (needed <= available) break
        for (row in heights.indices) if (parts[row] == part) shown[row] = false
    }
    return shown
}

/**
 * The player's controls as one column that fits a window [available] tall by leaving out what a short window can
 * do without ([fittedPlayerControls]). [content]'s children are the rows; one that may be left out says so with
 * [Modifier.layoutId] and its [PlayerControlsPart]. A row left out is neither drawn nor touchable.
 */
@Composable
internal fun FittedPlayerControls(available: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val row = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(row) }
        val shown = fittedPlayerControls(
            heights = placeables.map { it.height },
            parts = measurables.map { it.layoutId as? PlayerControlsPart },
            available = available.roundToPx(),
        )
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.indices.sumOf { if (shown[it]) placeables[it].height else 0 }
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            for (index in placeables.indices) {
                if (!shown[index]) continue
                val placeable = placeables[index]
                placeable.placeRelative((width - placeable.width) / 2, y)
                y += placeable.height
            }
        }
    }
}
