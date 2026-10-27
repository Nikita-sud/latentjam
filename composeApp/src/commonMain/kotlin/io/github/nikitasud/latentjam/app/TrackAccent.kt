/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.cluster.LatentColorAxes
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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

/**
 * A track's accent colour plus a readable foreground for it. [vivid] is the same accent before
 * it was toned into a surface: the cover's own colour, for places that paint with it rather
 * than write on it.
 */
data class TrackAccent(val container: Color, val onContainer: Color, val vivid: Color = container)

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
    /** The listener's own colour, used by [TrackColorMode.CUSTOM]. */
    customColor: Color? = null,
): TrackAccent = rememberTrackAccentState(track, mode, darkTheme, customColor).value

/**
 * [rememberTrackAccent] as a state the caller reads where it paints. The cross-fade moves this
 * state on every frame for [Motion.APPEAR_MS]; a caller that only passes it on (the app shell)
 * then recomposes once per track instead of once per frame of the fade.
 */
@Composable
fun rememberTrackAccentState(
    track: TrackDescriptor?,
    mode: TrackColorMode = TrackColorMode.DYNAMIC,
    darkTheme: Boolean,
    customColor: Color? = null,
): State<TrackAccent> {
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
        TrackColorMode.CUSTOM -> customColor
    }
    val resolvedTarget = seed?.let { toContainer(it, darkTheme) } ?: fallback
    val vividTarget = seed ?: fallback
    val artworkPending = mode == TrackColorMode.DYNAMIC &&
        track?.artworkUri != null && !artwork.resolved
    // Sampling starts at null for every new URI. Preserve the settled surface during that pending
    // frame so a track change produces one deliberate colour transition, not cover→theme→cover.
    var settledTarget by remember { mutableStateOf(resolvedTarget) }
    var settledVivid by remember { mutableStateOf(vividTarget) }
    val target = if (artworkPending) settledTarget else resolvedTarget
    val vividGoal = if (artworkPending) settledVivid else vividTarget
    SideEffect {
        if (!artworkPending) {
            settledTarget = resolvedTarget
            settledVivid = vividTarget
        }
    }
    val reduceMotion = rememberReduceMotion()
    val duration = if (reduceMotion) Motion.REDUCED_MS else Motion.APPEAR_MS
    val container = animateColorAsState(
        targetValue = target,
        animationSpec = tween(duration),
        label = "accent-container",
    )
    val vivid = animateColorAsState(
        targetValue = vividGoal,
        animationSpec = tween(duration),
        label = "accent-vivid",
    )
    return remember(container, vivid) {
        derivedStateOf {
            val surface = container.value
            // Pick from the animated surface on every frame. Interpolating black and white
            // independently passes through mid-gray exactly while the background is changing, which
            // can briefly erase control contrast. 0.179 is the luminance crossover where black and
            // white have equal contrast.
            val onContainer = if (surface.luminance() > 0.179f) Color.Black else Color.White
            TrackAccent(surface, onContainer, vivid.value)
        }
    }
}

/**
 * A resolved seed colour, remembered for the process. [latent] records which arm produced it,
 * so an identity colour can still upgrade to the embedding's once indexing reaches the track,
 * while a latent colour — deterministic for a given embedding — is never re-resolved.
 */
private data class SeededAccent(
    val color: Color,
    val latent: Boolean,
    /** The library axes the colour was placed with; a refit retires the colour. */
    val axes: LatentColorAxes?,
)

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
    val axes by LatentPalette.axes.collectAsState()
    // A refit retires the cached colour, but it stands in until the new one resolves: starting
    // from null dipped the player cloud and the mini player to the fallback grey and back.
    var accent by remember(track?.id, axes) {
        mutableStateOf(track?.id?.value?.let(seededAccents::get))
    }
    LaunchedEffect(track?.id, axes) {
        val id = track?.id ?: return@LaunchedEffect
        // A latent colour placed with these axes is final. Do not keep observing the progressive
        // index after the answer for this track is already cached.
        if (accent?.let { it.latent && it.axes === axes } == true) return@LaunchedEffect
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
                // Final only once the library's axes exist: before that the old fixed projection
                // stands in, and the colour moves once to its place when the axes arrive.
                val placed = axes?.let { fitted -> embedding?.let { LatentPalette.colorOf(fitted, it) } }
                val resolved = when {
                    placed != null -> SeededAccent(placed, latent = true, axes = axes)
                    embedding != null -> SeededAccent(latentTrackColorSeed(embedding).toComposeColor(), latent = false, axes = axes)
                    else -> SeededAccent(identityTrackColorSeed(id.value).toComposeColor(), latent = false, axes = axes)
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
    if (dark) lerp(seed, Color.Black, 0.45f) else pastelForLightSurface(seed)
