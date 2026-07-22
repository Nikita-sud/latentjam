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
import io.github.nikitasud.latentjam.playback.TrackColorSeed
import io.github.nikitasud.latentjam.playback.identityTrackColorSeed
import io.github.nikitasud.latentjam.playback.latentTrackColorSeed
import io.github.nikitasud.latentjam.smart.TrackDescriptor

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
    val identityColor = track?.let { identityTrackColorSeed(it.id.value).toComposeColor() }

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
            AppGraph.engine.embedding(id)?.let { latentTrackColorSeed(it).toComposeColor() }
        }
    }
    return color
}

private fun TrackColorSeed.toComposeColor(): Color =
    Color.hsl(hueDegrees, saturation, 0.5f)

/** Tames a raw sampled colour into a surface that text can sit on. */
private fun toContainer(seed: Color, dark: Boolean): Color =
    if (dark) lerp(seed, Color.Black, 0.45f) else lerp(seed, Color.White, 0.55f)
