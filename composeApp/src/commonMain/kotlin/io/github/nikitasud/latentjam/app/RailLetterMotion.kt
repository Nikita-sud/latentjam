/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Label centres, bounded even for mixed-script libraries and very short landscape rails. */
internal fun railLetterPositions(
    buckets: List<String>,
    activeBucket: String?,
    height: Float,
    labelHeight: Float,
    padding: Float,
): Map<String, Float> {
    if (buckets.isEmpty() || height <= 0f || labelHeight <= 0f) return emptyMap()
    val inner = (height - padding * 2f).coerceAtLeast(0f)
    if (buckets.size * labelHeight <= inner) {
        return buckets.mapIndexed { index, label ->
            label to padding + (index + 0.5f) * inner / buckets.size
        }.toMap()
    }
    // Too many letters for the rail: show an even selection at an even rhythm, the way a printed
    // index does. Placing each chosen letter at its own alphabet position made the rounding of
    // the selection visible as clumps and holes ("E G · J L N · R T"), and clearing room around
    // the current letter punched a further hole beside it.
    val slots = (inner / labelHeight).toInt().coerceIn(2, 32).coerceAtMost(buckets.size)
    if (slots < 2 || inner < labelHeight * 2f) {
        // Room for one label at most: the current letter, centred.
        return mapOf((activeBucket?.takeIf { it in buckets } ?: buckets.first()) to height / 2f)
    }
    val last = buckets.lastIndex
    val chosen = IntArray(slots) { slot -> (slot.toFloat() / (slots - 1) * last).roundToInt() }
    // The current letter takes over the slot whose stretch of the alphabet it falls in, so the
    // rail names exactly where the list is without moving anything else.
    val active = buckets.indexOf(activeBucket)
    if (active >= 0) {
        val slot = chosen.indices.minByOrNull { abs(chosen[it] - active) } ?: 0
        chosen[slot] = active
    }
    val step = (inner - labelHeight) / (slots - 1)
    return buildMap {
        chosen.forEachIndexed { slot, index ->
            put(buckets[index], padding + labelHeight / 2f + slot * step)
        }
    }
}

/** Common letters keep their identity and move; missing/new letters fade in the same pill. */
@Composable
internal fun MorphingRailLetters(
    buckets: List<String>,
    activeBucket: String?,
    railHeightPx: Int,
    labelHeight: Dp,
) {
    // The first layout is not an alphabet change. Starting a transition with an empty map
    // faded every letter in again whenever the player released and restored the browse shell.
    // Subsequent measured layouts still morph normally (page changes, rotation, font size).
    if (railHeightPx <= 0) return
    val density = LocalDensity.current
    val reduceMotion = rememberReduceMotion()
    val labelPx = with(density) { labelHeight.toPx() }
    val paddingPx = with(density) { 6.dp.toPx() }
    val positions = remember(buckets, activeBucket, railHeightPx, labelPx, paddingPx) {
        railLetterPositions(buckets, activeBucket, railHeightPx.toFloat(), labelPx, paddingPx)
    }
    val transition = updateTransition(positions, label = "rail-page-letters")
    val letters = remember(transition.currentState, transition.targetState) {
        (transition.currentState.keys + transition.targetState.keys).toList()
    }
    Box(Modifier.fillMaxSize()) {
        letters.forEach { letter -> key(letter) {
            val fallback = positions[letter] ?: transition.currentState[letter] ?: 0f
            val y = transition.animateFloat(
                transitionSpec = {
                    if (reduceMotion) snap() else tween(Motion.NAVIGATION_MS, easing = Motion.NavigationEasing)
                },
                label = "rail-letter-position",
            ) { it[letter] ?: fallback }
            val opacity = transition.animateFloat(
                transitionSpec = {
                    tween(if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS)
                },
                label = "rail-letter-opacity",
            ) { if (letter in it) 1f else 0f }
            val active = letter == activeBucket
            val color by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(if (reduceMotion) Motion.REDUCED_MS else Motion.QUICK_MS),
                label = "rail-active-letter",
            )
            Box(
                Modifier.fillMaxWidth().height(labelHeight).graphicsLayer {
                    translationY = y.value - labelPx / 2f
                    alpha = opacity.value
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = color,
                    maxLines = 1,
                )
            }
        } }
    }
}
