/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * The colour cloud behind the player: three soft discs in the track's colours that drift
 * slowly and breathe while the music plays.
 *
 * The phase advances at most twenty times a second and only while playing, including on high
 * refresh-rate displays. A paused player schedules no animation work.
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
        animatePlayerCloud { elapsed -> phase.floatValue += elapsed }
    }
    val density = LocalDensity.current
    // A toned container reads on a light surface, but on a dark one it is a dark colour on
    // black: invisible. There the cloud paints with the cover's own colour, lifted out of the
    // shadows when the cover itself is dark.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val primary = if (dark) liftForDarkSurface(accent.vivid) else accent.container
    val companion = rotateHue(primary, COMPANION_HUE_SHIFT)
    val alpha = if (dark) CLOUD_ALPHA_DARK else CLOUD_ALPHA_LIGHT
    return drawWithCache {
        val radii = BLOB_RADII_DP.map { it * density.density }
        val brushes = listOf(primary, companion, primary).mapIndexed { index, colour ->
            Brush.radialGradient(
                colors = listOf(colour.copy(alpha = alpha), colour.copy(alpha = 0f)),
                center = Offset.Zero,
                radius = radii[index],
            )
        }
        val drift = BLOB_DRIFT_DP * density.density
        onDrawBehind {
            val t = phase.floatValue
            // Freeze the exact last appearance on pause; resetting the scale produces a snap.
            val breath = 1f + BREATH_DEPTH * sin(t * TWO_PI / BREATH_PERIOD_S)
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

/** Each callback advances the animation by the displayed time since the preceding update. */
internal suspend fun animatePlayerCloud(onFrame: (elapsedSeconds: Float) -> Unit) {
    var lastNanos = withFrameNanos { it }
    while (true) {
        // Sleep between draws instead of waking for every display frame, most of which used
        // to be discarded. The frame clock still aligns each update with presentation.
        delay(CLOUD_FRAME_INTERVAL_MS)
        withFrameNanos { now ->
            // A suspended frame clock (for example in the background) must not teleport the
            // cloud when the player becomes visible again.
            onFrame(((now - lastNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_DELTA_S))
            lastNanos = now
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
    val (hue, saturation, lightness) = colour.toHsl() ?: return colour
    val rotated = ((hue + degrees) % 360f + 360f) % 360f
    return Color.hsl(rotated, saturation, lightness, colour.alpha)
}

/**
 * A colour that can be seen on black: at least [DARK_MIN_LIGHTNESS] light and
 * [DARK_MIN_SATURATION] saturated, same hue. A grey stays grey, only brighter.
 */
internal fun liftForDarkSurface(colour: Color): Color {
    val hsl = colour.toHsl()
    if (hsl == null) {
        val grey = maxOf(colour.red, DARK_MIN_LIGHTNESS)
        return Color(grey, grey, grey, colour.alpha)
    }
    val (hue, saturation, lightness) = hsl
    return Color.hsl(
        hue = hue,
        saturation = maxOf(saturation, DARK_MIN_SATURATION),
        lightness = maxOf(lightness, DARK_MIN_LIGHTNESS),
        alpha = colour.alpha,
    )
}

/** Hue in degrees, saturation and lightness in 0..1; null for a grey, which has no hue. */
private fun Color.toHsl(): Triple<Float, Float, Float>? {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val lightness = (max + min) / 2f
    if (delta == 0f) return null
    val saturation = delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    val hue = when (max) {
        red -> 60f * (((green - blue) / delta) % 6f)
        green -> 60f * (((blue - red) / delta) + 2f)
        else -> 60f * (((red - green) / delta) + 4f)
    }
    return Triple(((hue % 360f) + 360f) % 360f, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
}

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val CLOUD_FRAME_INTERVAL_MS = 50L
private const val MAX_FRAME_DELTA_S = 0.1f
private const val TWO_PI = (2 * PI).toFloat()
private const val CLOUD_ALPHA_LIGHT = 0.6f
private const val CLOUD_ALPHA_DARK = 0.7f
private const val DARK_MIN_LIGHTNESS = 0.45f
private const val DARK_MIN_SATURATION = 0.45f
private const val COMPANION_HUE_SHIFT = 28f
private const val BREATH_DEPTH = 0.04f
private const val BREATH_PERIOD_S = 0.86f
private const val BLOB_DRIFT_DP = 70f
private val BLOB_RADII_DP = listOf(150f, 130f, 120f)
private val BLOB_PERIODS_S = listOf(19f, 23f, 27f)
private val BLOB_ANCHORS = listOf(Offset(0.15f, 0.12f), Offset(0.78f, 0.3f), Offset(0.35f, 0.64f))
