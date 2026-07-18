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
import kotlin.math.abs

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

    val seed = artworkColor ?: latentColor
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
 * Hue from the track's embedding: three wide slices of the vector are summed
 * into pseudo-RGB, then pushed to a usable saturation. Deterministic, so a
 * track always wears the same colour, and neighbours in latent space wear
 * neighbouring colours.
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

private fun latentColorOf(embedding: FloatArray): Color {
    if (embedding.isEmpty()) return Color.Gray
    val slice = embedding.size / 3
    fun channel(from: Int, to: Int): Float {
        var sum = 0f
        for (i in from until to) sum += abs(embedding[i])
        val mean = sum / (to - from).coerceAtLeast(1)
        // Embedding components are small; scale into a usable range.
        return (mean * 12f).coerceIn(0.15f, 1f)
    }
    return Color(
        red = channel(0, slice),
        green = channel(slice, slice * 2),
        blue = channel(slice * 2, embedding.size),
    )
}

/** Tames a raw sampled colour into a surface that text can sit on. */
private fun toContainer(seed: Color, dark: Boolean): Color =
    if (dark) lerp(seed, Color.Black, 0.45f) else lerp(seed, Color.White, 0.55f)
