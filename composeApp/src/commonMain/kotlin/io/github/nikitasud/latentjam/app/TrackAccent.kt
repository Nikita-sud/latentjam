/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import io.github.nikitasud.latentjam.smart.EngineState
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
 * [TrackColorMode.DYNAMIC] prefers cover art, [TrackColorMode.SMART] makes the model's latent
 * space visible, and [TrackColorMode.THEME] deliberately keeps the player neutral. Coverless and
 * not-yet-indexed tracks retain a stable identity colour in the first two modes.
 *
 * The result is animated, so track changes cross-fade rather than snap.
 */
@Composable
fun rememberTrackAccent(
    track: TrackDescriptor?,
    mode: TrackColorMode = TrackColorMode.DYNAMIC,
    darkTheme: Boolean,
): TrackAccent {
    val fallback = MaterialTheme.colorScheme.primaryContainer
    val onFallback = MaterialTheme.colorScheme.onPrimaryContainer

    val artworkColor = rememberArtworkColor(
        track?.artworkUri.takeIf { mode == TrackColorMode.DYNAMIC },
    )
    val seededColor = rememberSeededColor(
        track.takeIf { mode != TrackColorMode.THEME && artworkColor == null },
    )

    val seed = when (mode) {
        TrackColorMode.DYNAMIC -> artworkColor ?: seededColor
        TrackColorMode.SMART -> seededColor
        TrackColorMode.THEME -> null
    }
    val target = seed?.let { toContainer(it, darkTheme) } ?: fallback
    val container by animateColorAsState(target, label = "accent-container")
    val onContainer = if (seed == null) {
        onFallback
    } else {
        if (container.luminance() > 0.45f) Color.Black.copy(alpha = 0.87f) else Color.White
    }
    return TrackAccent(container, onContainer)
}

/**
 * A resolved seed colour, remembered for the process. [latent] records which arm produced it,
 * so an identity colour can still upgrade to the embedding's once indexing reaches the track,
 * while a latent colour — deterministic for a given embedding — is never re-resolved.
 */
private class SeededAccent(val color: Color, val latent: Boolean)

/**
 * Process-wide, so a track wears ONE colour for the app's lifetime. Before this cache the
 * resolution was per-composition: every player open showed the id-hash identity colour first,
 * then the async embedding query landed and the accent visibly crossed to a different hue —
 * the same coverless track appearing to change colour on every single play.
 *
 * Main-thread confined (composition and effects), bounded by library size.
 */
private val seededAccents = HashMap<String, SeededAccent>()

/**
 * Hue from the track's embedding, falling back to a stable id-hash identity for tracks the
 * index does not know. Deterministic either way, so a track always wears the same colour and
 * latent-space neighbours wear neighbouring hues. Unresolved (first encounter only) is null —
 * the theme fallback — rather than a colour that is about to be replaced.
 */
@Composable
private fun rememberSeededColor(track: TrackDescriptor?): Color? {
    val engineState by AppGraph.engine.state.collectAsState()
    val indexRevision = (engineState as? EngineState.Ready)?.indexedCount ?: 0
    var accent by remember(track?.id) {
        mutableStateOf(track?.id?.value?.let(seededAccents::get))
    }
    LaunchedEffect(track?.id, indexRevision) {
        val id = track?.id ?: return@LaunchedEffect
        // A latent colour is final; only an identity placeholder can improve, and only after
        // more of the library was indexed (indexRevision keys the retry).
        if (accent?.latent == true) return@LaunchedEffect
        val embedding = AppGraph.engine.embedding(id)
        val resolved = if (embedding != null) {
            SeededAccent(latentTrackColorSeed(embedding).toComposeColor(), latent = true)
        } else {
            SeededAccent(identityTrackColorSeed(id.value).toComposeColor(), latent = false)
        }
        seededAccents[id.value] = resolved
        accent = resolved
    }
    return accent?.color
}

private fun TrackColorSeed.toComposeColor(): Color =
    Color.hsl(hueDegrees, saturation, 0.5f)

/** Tames a raw sampled colour into a surface that text can sit on. */
private fun toContainer(seed: Color, dark: Boolean): Color =
    if (dark) lerp(seed, Color.Black, 0.45f) else lerp(seed, Color.White, 0.55f)
