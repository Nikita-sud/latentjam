/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.nikitasud.latentjam.playback.TrackColorSeed
import io.github.nikitasud.latentjam.playback.identityTrackColorSeed
import io.github.nikitasud.latentjam.playback.latentTrackColorSeed
import io.github.nikitasud.latentjam.smart.EngineState
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A track's accent colour plus a readable foreground for it. */
data class TrackAccent(val container: Color, val onContainer: Color)

/**
 * Platform artwork sampling: the dominant/vibrant colour of the cover, or
 * `null` when there is no artwork (or no sampler on this platform).
 */
@Composable
expect fun rememberArtworkColor(uri: String?): ArtworkColorState

/** Distinguishes an in-flight sample from a completed cover with no usable accent. */
data class ArtworkColorState(val color: Color?, val resolved: Boolean)

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

    val artwork = rememberArtworkColor(
        track?.artworkUri.takeIf { mode == TrackColorMode.DYNAMIC },
    )
    val seededColor = rememberSeededColor(
        track?.takeIf {
            mode == TrackColorMode.SMART ||
                (mode == TrackColorMode.DYNAMIC && it.artworkUri == null)
        },
    )

    val seed = when (mode) {
        TrackColorMode.DYNAMIC -> artwork.color ?: seededColor
        TrackColorMode.SMART -> seededColor
        TrackColorMode.THEME -> null
    }
    val resolvedTarget = seed?.let { toContainer(it, darkTheme) } ?: fallback
    val artworkPending = mode == TrackColorMode.DYNAMIC &&
        track?.artworkUri != null && !artwork.resolved
    // Sampling starts at null for every new URI. Preserve the settled surface during that pending
    // frame so a track change produces one deliberate colour transition, not cover→theme→cover.
    var settledTarget by remember { mutableStateOf(resolvedTarget) }
    val target = if (artworkPending) settledTarget else resolvedTarget
    SideEffect {
        if (!artworkPending) settledTarget = resolvedTarget
    }
    val reduceMotion = rememberReduceMotion()
    val duration = if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS
    val container by animateColorAsState(
        targetValue = target,
        animationSpec = tween(duration),
        label = "accent-container",
    )
    // Pick from the animated surface on every frame. Interpolating black and white independently
    // passes through mid-gray exactly while the background is changing, which can briefly erase
    // control contrast. 0.179 is the luminance crossover where black and white have equal contrast.
    val onContainer = if (container.luminance() > 0.179f) Color.Black else Color.White
    return TrackAccent(container, onContainer)
}

/**
 * A resolved seed colour, remembered for the process. [latent] records which arm produced it,
 * so an identity colour can still upgrade to the embedding's once indexing reaches the track,
 * while a latent colour — deterministic for a given embedding — is never re-resolved.
 */
private data class SeededAccent(val color: Color, val latent: Boolean)

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
    var accent by remember(track?.id) {
        mutableStateOf(track?.id?.value?.let(seededAccents::get))
    }
    LaunchedEffect(track?.id) {
        val id = track?.id ?: return@LaunchedEffect
        // A latent colour is final. Do not keep observing the progressive index after the answer
        // for this track is already cached.
        if (accent?.latent == true) return@LaunchedEffect
        val engine = AppGraph.engine
        // Automatic indexing advances Ready.indexedCount once per small persisted chunk. Observe
        // that progress inside this effect instead of as Compose state: until THIS track gains an
        // embedding the visible identity colour does not change, so those checkpoints must not
        // invalidate the player UI. `first` ends the subscription as soon as the latent colour is
        // available; a permanently unindexed track simply keeps its deterministic fallback.
        engine.state
            .map { state -> (state as? EngineState.Ready)?.indexedCount ?: 0 }
            .distinctUntilChanged()
            .first {
                val embedding = engine.embedding(id)
                val resolved = if (embedding != null) {
                    SeededAccent(latentTrackColorSeed(embedding).toComposeColor(), latent = true)
                } else {
                    SeededAccent(identityTrackColorSeed(id.value).toComposeColor(), latent = false)
                }
                seededAccents[id.value] = resolved
                if (accent != resolved) accent = resolved
                resolved.latent
            }
    }
    return accent?.color
}

private fun TrackColorSeed.toComposeColor(): Color =
    Color.hsl(hueDegrees, saturation, 0.5f)

/** Tames a raw sampled colour into a surface that text can sit on. */
private fun toContainer(seed: Color, dark: Boolean): Color =
    if (dark) lerp(seed, Color.Black, 0.45f) else lerp(seed, Color.White, 0.55f)
