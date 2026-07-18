/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/** A track's accent colour plus a readable foreground for it. */
data class TrackAccent(val container: Color, val onContainer: Color)

/**
 * Platform artwork sampling: the dominant/vibrant colour of the cover, or
 * `null` when there is no artwork (or no sampler on this platform).
 */
@Composable
expect fun rememberArtworkColor(uri: String?): Color?

/**
 * The colour that represents [track] in the UI.
 *
 * Cover art wins when it exists. When it doesn't, the track's **position in
 * the model's latent space** does: indexed tracks get a hue derived from
 * their embedding, so acoustically similar tracks are tinted alike — the
 * similarity engine made visible. Everything else falls back to the theme.
 *
 * The result is animated, so track changes cross-fade rather than snap.
 */
@Composable
fun rememberTrackAccent(track: TrackDescriptor?): TrackAccent {
    val dark = isSystemInDarkTheme()
    val fallback = MaterialTheme.colorScheme.primaryContainer
    val onFallback = MaterialTheme.colorScheme.onPrimaryContainer

    val artworkColor = rememberArtworkColor(track?.artworkUri)
    val latentColor = rememberLatentColor(track.takeIf { artworkColor == null })
    // Every track gets an identity, even before it is indexed: the id hash is
    // arbitrary but stable, so a coverless track is never just grey.
    val identityColor = track?.let { identityColorOf(it.id.value) }

    val seed = artworkColor ?: latentColor ?: identityColor
    val target = seed?.let { toContainer(it, dark) } ?: fallback
    val container by animateColorAsState(target, label = "accent-container")
    val onContainer = if (seed == null) {
        onFallback
    } else {
        if (container.luminance() > 0.45f) Color.Black.copy(alpha = 0.87f) else Color.White
    }
    return TrackAccent(container, onContainer)
}

/**
 * Hue from the track's embedding. Deterministic, so a track always wears the
 * same colour, and neighbours in latent space wear neighbouring hues.
 */
@Composable
private fun rememberLatentColor(track: TrackDescriptor?): Color? {
    var color by remember(track?.id) { mutableStateOf<Color?>(null) }
    LaunchedEffect(track?.id) {
        color = track?.id?.let { id ->
            AppGraph.engine.embedding(id)?.let(::latentColorOf)
        }
    }
    return color
}

/**
 * The embedding's DIRECTION picks the hue.
 *
 * Magnitudes are useless here: these vectors are L2-normalised and roughly
 * isotropic, so any per-slice average lands on the same number and every
 * track comes out the same grey. Signed sums of three slices do vary, so
 * their angle in the plane maps to a hue and the third axis nudges
 * saturation — vivid, well-spread, and still stable per track.
 */
private fun latentColorOf(embedding: FloatArray): Color {
    if (embedding.size < 3) return Color.Gray
    val slice = embedding.size / 3
    fun signedSum(from: Int, to: Int): Float {
        var sum = 0f
        for (i in from until to) sum += embedding[i]
        return sum
    }
    val x = signedSum(0, slice)
    val y = signedSum(slice, slice * 2)
    val z = signedSum(slice * 2, embedding.size)

    val hue = ((atan2(y, x) / (2f * PI.toFloat()) + 1f) % 1f) * 360f
    val saturation = (0.45f + 0.3f * (abs(z) / (abs(x) + abs(y) + abs(z) + 1e-6f)))
        .coerceIn(0.35f, 0.8f)
    return Color.hsl(hue, saturation, 0.5f)
}

/** Stable pseudo-random hue from the track id — an identity, not a meaning. */
private fun identityColorOf(id: String): Color {
    var hash = 0
    for (character in id) hash = hash * 31 + character.code
    val hue = ((hash % 360) + 360) % 360
    return Color.hsl(hue.toFloat(), 0.42f, 0.5f)
}

/** Tames a raw sampled colour into a surface that text can sit on. */
private fun toContainer(seed: Color, dark: Boolean): Color =
    if (dark) lerp(seed, Color.Black, 0.45f) else lerp(seed, Color.White, 0.55f)
