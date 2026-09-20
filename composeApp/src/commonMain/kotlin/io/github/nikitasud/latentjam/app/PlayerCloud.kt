/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The colour cloud behind the player: three soft discs in the track's colours that drift
 * slowly and breathe while the music plays.
 *
 * The phase advances on at most every third frame and only while playing, so a paused player
 * costs nothing and a playing one draws three gradient fills about twenty times a second.
 * The phase is read inside the draw lambda alone: nothing recomposes for the motion. Reduce
 * Motion leaves the cloud still where it is. Colours are the accent the screen already animates
 * between tracks, so a skip recolours the cloud on the same fade.
 */
@Composable
internal fun Modifier.playerCloud(accent: TrackAccent, playing: Boolean): Modifier {
    val reduceMotion = rememberReduceMotion()
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, reduceMotion) {
        if (!playing || reduceMotion) return@LaunchedEffect
        var lastNanos = 0L
        var pending = 0f
        var frame = 0
        while (true) {
            withFrameNanos { now ->
                if (lastNanos != 0L) pending += (now - lastNanos) / NANOS_PER_SECOND
                lastNanos = now
                if (++frame % FRAME_DIVISOR == 0) {
                    phase.floatValue += pending
                    pending = 0f
                }
            }
        }
    }
    val density = LocalDensity.current
    val primary = accent.container
    val companion = rotateHue(accent.container, COMPANION_HUE_SHIFT)
    return drawWithCache {
        val radii = BLOB_RADII_DP.map { it * density.density }
        val brushes = listOf(primary, companion, primary).mapIndexed { index, colour ->
            Brush.radialGradient(
                colors = listOf(colour.copy(alpha = CLOUD_ALPHA), colour.copy(alpha = 0f)),
                center = Offset.Zero,
                radius = radii[index],
            )
        }
        val drift = BLOB_DRIFT_DP * density.density
        onDrawBehind {
            val t = phase.floatValue
            val breath = if (playing) 1f + BREATH_DEPTH * sin(t * TWO_PI / BREATH_PERIOD_S) else 1f
            for (index in brushes.indices) {
                val anchor = BLOB_ANCHORS[index]
                val period = BLOB_PERIODS_S[index]
                val cx = size.width * anchor.x + drift * sin(t * TWO_PI / period + index)
                val cy = size.height * anchor.y + drift * cos(t * TWO_PI / (period + 4f) + index)
                blob(brushes[index], radii[index], cx, cy, breath)
            }
        }
    }
}

private fun DrawScope.blob(brush: Brush, radius: Float, cx: Float, cy: Float, breath: Float) {
    translate(cx, cy) {
        scale(breath, breath, pivot = Offset.Zero) {
            drawCircle(brush = brush, radius = radius, center = Offset.Zero)
        }
    }
}

/** The same colour a little way round the wheel, so the cloud has two tones of one mood. */
internal fun rotateHue(colour: Color, degrees: Float): Color {
    val r = colour.red
    val g = colour.green
    val b = colour.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val lightness = (max + min) / 2f
    if (delta == 0f) return colour
    val saturation = delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    val hue = when (max) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    val rotated = ((hue + degrees) % 360f + 360f) % 360f
    return Color.hsl(rotated, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f), colour.alpha)
}

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val FRAME_DIVISOR = 3
private const val TWO_PI = (2 * PI).toFloat()
private const val CLOUD_ALPHA = 0.6f
private const val COMPANION_HUE_SHIFT = 28f
private const val BREATH_DEPTH = 0.04f
private const val BREATH_PERIOD_S = 0.86f
private const val BLOB_DRIFT_DP = 70f
private val BLOB_RADII_DP = listOf(150f, 130f, 120f)
private val BLOB_PERIODS_S = listOf(19f, 23f, 27f)
private val BLOB_ANCHORS = listOf(Offset(0.15f, 0.12f), Offset(0.78f, 0.3f), Offset(0.35f, 0.64f))
