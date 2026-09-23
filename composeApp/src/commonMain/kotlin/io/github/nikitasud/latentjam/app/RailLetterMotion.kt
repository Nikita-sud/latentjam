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
    val active = buckets.indexOf(activeBucket).coerceAtLeast(0)
    val available = (inner - labelHeight).coerceAtLeast(0f)
    val guideCount = (available / labelHeight).toInt().coerceIn(2, 32)
    val last = buckets.lastIndex.coerceAtLeast(1)
    fun centre(index: Int) = padding + labelHeight / 2f + index.toFloat() / last * available
    return buildMap {
        (0 until guideCount).forEach { guide ->
            val index = (guide.toFloat() / (guideCount - 1) * buckets.lastIndex).roundToInt()
            if (abs(centre(index) - centre(active)) >= labelHeight) {
                put(buckets[index], centre(index))
            }
        }
        put(buckets[active], centre(active).coerceAtMost(height / 2f + available / 2f))
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
