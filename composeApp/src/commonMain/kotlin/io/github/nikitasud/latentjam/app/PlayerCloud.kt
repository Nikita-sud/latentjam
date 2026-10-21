/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import coil3.compose.AsyncImagePainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import coil3.request.crossfade
import coil3.request.ImageRequest
import coil3.compose.LocalPlatformContext
import coil3.compose.AsyncImage
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
    val appearance = LocalAppearance.current
    val strength = appearance.cloud.alphaScale
    if (strength == 0f) return this
    // A still cloud is the listener's choice as much as Reduce Motion is.
    val reduceMotion = rememberReduceMotion() || !appearance.cloudMotion
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, reduceMotion, lifecycle) {
        if (!playing || reduceMotion) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            animatePlayerCloud { elapsed -> phase.floatValue += elapsed }
        }
    }
    val density = LocalDensity.current
    // A dark surface needs a lifted cover colour; a light one needs a bounded pastel of the
    // same hue. Reusing the already-whitened container washed pale covers almost to white.
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val alpha = ((if (dark) CLOUD_ALPHA_DARK else CLOUD_ALPHA_LIGHT) * strength).coerceAtMost(1f)
    // Blobs drift over each other: where two overlap the cloud covers 1 - (1 - alpha)^2 of the
    // floor, so the titles' contrast is checked against that, not against one blob.
    val coverage = 1f - (1f - alpha) * (1f - alpha)
    val primary = if (dark) {
        liftForDarkCloud(accent.vivid, coverage, colors.surface, colors.onSurface)
    } else {
        tintForLightSurface(accent.vivid)
    }
    val companion = if (dark) {
        liftForDarkCloud(rotateHue(primary, COMPANION_HUE_SHIFT), coverage, colors.surface, colors.onSurface)
    } else {
        rotateHue(primary, 12f)
    }
    return drawWithCache {
        // A nearly neutral light floor lets each drifting cloud remain distinct. A stronger
        // same-hue floor erases the visible movement, even while the cloud clock is advancing.
        val baseWash = primary.copy(alpha = CLOUD_BASE_ALPHA_LIGHT)
        val radiusScale = if (dark) 1f else LIGHT_BLOB_RADIUS_SCALE
        val radii = BLOB_RADII_DP.map { it * density.density * radiusScale }
        val brushes = listOf(primary, companion, primary).mapIndexed { index, colour ->
            Brush.radialGradient(
                colors = listOf(colour.copy(alpha = alpha), colour.copy(alpha = 0f)),
                center = Offset.Zero,
                radius = radii[index],
            )
        }
        val drift = BLOB_DRIFT_DP * density.density
        onDrawBehind {
            if (!dark) drawRect(baseWash)
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

/**
 * [liftForDarkSurface] for a cloud drawn at [alpha] on the dark [floor]: where a blob is densest,
 * [text] still holds [DARK_CLOUD_MIN_TEXT_CONTRAST]. A lift never capped a pale cover, and at
 * Vivid's full alpha the player's white titles fell to about 1.4:1 on it. Covers that already
 * leave the text readable keep their colour.
 */
internal fun liftForDarkCloud(colour: Color, alpha: Float, floor: Color, text: Color): Color {
    val lifted = liftForDarkSurface(colour)
    val (hue, saturation, start) = lifted.toHsl() ?: Triple(0f, 0f, lifted.red)
    var lightness = start
    var cloud = lifted
    while (contrastRatio(text, cloud.over(floor, alpha)) < DARK_CLOUD_MIN_TEXT_CONTRAST && lightness > 0f) {
        lightness = (lightness - 0.02f).coerceAtLeast(0f)
        cloud = Color.hsl(hue, saturation, lightness, colour.alpha)
    }
    return cloud
}

/** This colour drawn at [alpha] over an opaque [floor]. */
internal fun Color.over(floor: Color, alpha: Float): Color = Color(
    red = red * alpha + floor.red * (1f - alpha),
    green = green * alpha + floor.green * (1f - alpha),
    blue = blue * alpha + floor.blue * (1f - alpha),
)

/**
 * A clearly coloured light surface, including very pale and very dark sampled covers.
 * At L=.80/S<=.72 every RGB channel is at least .656. Even arbitrary cloud overlaps followed
 * by a 10% black current-row wash retain over 4.5:1 with the light theme's #2E2E2E secondary ink.
 */
internal fun tintForLightSurface(colour: Color): Color {
    // A truly neutral cover/theme stays neutral instead of acquiring an invented hue.
    val hsl = colour.toHsl() ?: return Color(0.92f, 0.92f, 0.92f, colour.alpha)
    val (hue, saturation) = hsl
    return Color.hsl(
        hue = hue,
        saturation = saturation.coerceIn(0.58f, 0.72f),
        lightness = 0.80f,
        alpha = colour.alpha,
    )
}

/**
 * The listener's accent as the ink of controls on a light [surface]: the same hue, darkened
 * until it holds [CONTROL_INK_MIN_CONTRAST] against the floor. [tintForLightSurface] is a cloud
 * pastel; as a switch, a slider or a Play disc it all but vanished (yellow came out at 1.3:1),
 * and a Play disc in the cloud's own colour melted into the blob behind it.
 */
internal fun inkForLightSurface(colour: Color, surface: Color): Color {
    val hsl = colour.toHsl()
    val hue = hsl?.first ?: 0f
    val saturation = hsl?.second ?: 0f
    var lightness = minOf(hsl?.third ?: colour.red, CONTROL_INK_MAX_LIGHTNESS)
    var ink = Color.hsl(hue, saturation, lightness, colour.alpha)
    while (contrastRatio(ink, surface) < CONTROL_INK_MIN_CONTRAST && lightness > 0f) {
        lightness = (lightness - 0.02f).coerceAtLeast(0f)
        ink = Color.hsl(hue, saturation, lightness, colour.alpha)
    }
    return ink
}

/** WCAG contrast between two opaque colours, 1..21. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
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
private const val CLOUD_BASE_ALPHA_LIGHT = 0.04f
private const val CLOUD_ALPHA_LIGHT = 0.98f
private const val LIGHT_BLOB_RADIUS_SCALE = 1.15f
private const val CLOUD_ALPHA_DARK = 0.7f
private const val DARK_MIN_LIGHTNESS = 0.45f
private const val DARK_MIN_SATURATION = 0.45f
private const val CONTROL_INK_MIN_CONTRAST = 3f
private const val DARK_CLOUD_MIN_TEXT_CONTRAST = 3f
private const val CONTROL_INK_MAX_LIGHTNESS = 0.5f
private const val COMPANION_HUE_SHIFT = 28f
private const val BREATH_DEPTH = 0.04f
private const val BREATH_PERIOD_S = 0.86f
private const val BLOB_DRIFT_DP = 70f
private val BLOB_RADII_DP = listOf(150f, 130f, 120f)
private val BLOB_PERIODS_S = listOf(19f, 23f, 27f)
private val BLOB_ANCHORS = listOf(Offset(0.15f, 0.12f), Offset(0.78f, 0.3f), Offset(0.35f, 0.64f))

/**
 * The player's other floor: the cover itself, blurred and dimmed. The image is decoded at a few
 * dozen pixels, so even where Compose cannot blur (Android before 12) the stretched thumbnail
 * already reads as a soft wash; [Modifier.blur] smooths it further where it can.
 */
@Composable
internal fun PlayerCoverBackdrop(
    uri: String,
    modifier: Modifier = Modifier,
    /** Whether a cover is actually on screen; until it is (or if it never loads), keep the cloud. */
    onShownChange: (Boolean) -> Unit = {},
) {
    val context = LocalPlatformContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    var shown by remember(uri) { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val request = remember(uri, reduceMotion) {
        ImageRequest.Builder(context)
            .data(uri)
            .size(BACKDROP_SOURCE_PX)
            .memoryCacheKey("player-backdrop:$uri")
            .crossfade(if (reduceMotion) Motion.REDUCED_MS else Motion.EMPHASIZED_MS)
            .build()
    }
    Box(modifier) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { state ->
                shown = state is AsyncImagePainter.State.Success
                onShownChange(shown)
            },
            modifier = Modifier.fillMaxSize()
                .graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }
                .blur(BACKDROP_BLUR, BlurredEdgeTreatment.Rectangle),
        )
        if (shown) Box(
            Modifier.fillMaxSize().background(
                MaterialTheme.colorScheme.surface.copy(alpha = if (dark) BACKDROP_DIM_DARK else BACKDROP_DIM_LIGHT),
            ),
        )
    }
}

private const val BACKDROP_SOURCE_PX = 24
private val BACKDROP_BLUR = 40.dp
private const val BACKDROP_DIM_DARK = 0.55f
private const val BACKDROP_DIM_LIGHT = 0.5f
