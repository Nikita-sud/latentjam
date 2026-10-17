/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.github.nikitasud.latentjam.smart.EngineState
import io.github.nikitasud.latentjam.smart.SimilarityEngine
import io.github.nikitasud.latentjam.smart.TrackId
import io.github.nikitasud.latentjam.smart.cluster.LatentColorAxes
import io.github.nikitasud.latentjam.smart.cluster.LatentPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * SMART colours: a track's place among the listener's own tracks, as a colour. Similar tracks
 * sit at neighbouring hues and very different ones across the wheel; how unusual a track is for
 * this library sets how saturated it is. See [LatentColorAxes] for the place itself.
 *
 * The axes are fitted once on a sample of the library and refitted only when the indexed library
 * has grown by a sixth since, so a track keeps its colour from play to play.
 */
internal object LatentPalette {
    private val mutableAxes = MutableStateFlow<LatentColorAxes?>(null)
    val axes: StateFlow<LatentColorAxes?> = mutableAxes.asStateFlow()
    private var fittedAt = 0
    private val lock = Mutex()

    suspend fun refresh(engine: SimilarityEngine, ids: List<TrackId>, indexed: Int) = lock.withLock {
        if (indexed < LatentColorAxes.MIN_TRACKS || ids.isEmpty()) return@withLock
        if (mutableAxes.value != null && indexed < fittedAt * REFIT_GROWTH) return@withLock
        // A fixed, spread-out sample: the same library always fits the same axes.
        val sample = if (ids.size <= SAMPLE_SIZE) ids else ids.sortedBy { mix(it.value.hashCode()) }.take(SAMPLE_SIZE)
        val rows = sample.mapNotNull { engine.embedding(it) }
        val fitted = withContext(Dispatchers.Default) { LatentColorAxes.fit(rows) } ?: return@withLock
        fittedAt = indexed
        mutableAxes.value = fitted
    }

    /** [embedding]'s colour under [axes]; null when the vector does not belong to them. */
    fun colorOf(axes: LatentColorAxes, embedding: FloatArray): Color? = axes.place(embedding)?.toColor()

    private fun LatentPlace.toColor(): Color = oklch(
        lightness = PALETTE_LIGHTNESS,
        chroma = MIN_CHROMA + (MAX_CHROMA - MIN_CHROMA) * spread,
        hueDegrees = angle * 360f + HUE_OFFSET,
    )

    private fun mix(value: Int): Int {
        var x = value
        x = x xor (x ushr 16)
        x *= 0x7feb352d
        x = x xor (x ushr 15)
        return x
    }

    private const val SAMPLE_SIZE = 1_200
    private const val REFIT_GROWTH = 7f / 6f
    private const val PALETTE_LIGHTNESS = 0.70f
    private const val MIN_CHROMA = 0.09f
    private const val MAX_CHROMA = 0.17f

    /** Where the wheel starts; any value works, this one opens on a warm red. */
    private const val HUE_OFFSET = 20f
}

/** Keeps [LatentPalette] fitted to the library as it is indexed, without recomposing anything. */
@Composable
internal fun LatentPaletteUpdater(engine: SimilarityEngine, ids: List<TrackId>) {
    LaunchedEffect(engine, ids) {
        engine.state
            .map { state -> (state as? EngineState.Ready)?.indexedCount ?: 0 }
            .distinctUntilChanged()
            .collectLatest { indexed -> LatentPalette.refresh(engine, ids, indexed) }
    }
}

/**
 * OKLCH to sRGB. Equal steps of hue look like equal steps, unlike HSL, where a third of the wheel
 * is green. A colour the screen cannot show loses chroma until it can, keeping its hue.
 */
internal fun oklch(lightness: Float, chroma: Float, hueDegrees: Float): Color {
    val radians = Math_PI_OVER_180 * hueDegrees
    var c = chroma
    repeat(24) {
        val a = c * cos(radians)
        val b = c * sin(radians)
        val l = (lightness + 0.3963377774f * a + 0.2158037573f * b).pow(3)
        val m = (lightness - 0.1055613458f * a - 0.0638541728f * b).pow(3)
        val s = (lightness - 0.0894841775f * a - 1.2914855480f * b).pow(3)
        val red = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val green = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val blue = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        if (red in 0f..1f && green in 0f..1f && blue in 0f..1f) {
            return Color(gamma(red), gamma(green), gamma(blue))
        }
        c *= 0.9f
    }
    val grey = gamma(lightness.pow(3))
    return Color(grey, grey, grey)
}

private fun gamma(linear: Float): Float =
    (if (linear <= 0.0031308f) 12.92f * linear else 1.055f * linear.pow(1f / 2.4f) - 0.055f).coerceIn(0f, 1f)

private const val Math_PI_OVER_180 = (kotlin.math.PI / 180.0).toFloat()
