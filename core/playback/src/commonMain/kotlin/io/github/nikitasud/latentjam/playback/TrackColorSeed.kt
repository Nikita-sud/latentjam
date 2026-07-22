/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Platform-neutral HSL seed used by both the player UI and system media artwork. */
public data class TrackColorSeed(
    public val hueDegrees: Float,
    public val saturation: Float,
)

/**
 * Maps an embedding direction to a stable colour.
 *
 * Magnitudes are deliberately ignored: the audio vectors are L2-normalised. Signed sums of
 * three slices retain direction, so acoustically nearby tracks receive nearby hues.
 */
public fun latentTrackColorSeed(embedding: FloatArray): TrackColorSeed {
    if (embedding.size < 3) return TrackColorSeed(hueDegrees = 0f, saturation = 0f)
    val slice = embedding.size / 3
    fun signedSum(from: Int, to: Int): Float {
        var sum = 0f
        for (index in from until to) sum += embedding[index]
        return sum
    }

    val x = signedSum(0, slice)
    val y = signedSum(slice, slice * 2)
    val z = signedSum(slice * 2, embedding.size)
    val hue = ((atan2(y, x) / (2f * PI.toFloat()) + 1f) % 1f) * 360f
    val saturation = (0.45f + 0.3f * (abs(z) / (abs(x) + abs(y) + abs(z) + 1e-6f)))
        .coerceIn(0.35f, 0.8f)
    return TrackColorSeed(hueDegrees = hue, saturation = saturation)
}

/** Stable identity colour used before an embedding exists. */
public fun identityTrackColorSeed(id: String): TrackColorSeed {
    var hash = 0
    for (character in id) hash = hash * 31 + character.code
    val hue = ((hash % 360) + 360) % 360
    return TrackColorSeed(hueDegrees = hue.toFloat(), saturation = 0.42f)
}

/** Converts this HSL seed to an opaque Android-compatible ARGB integer. */
public fun TrackColorSeed.toArgb(lightness: Float = 0.5f): Int {
    val hue = ((hueDegrees % 360f) + 360f) % 360f
    val sat = saturation.coerceIn(0f, 1f)
    val light = lightness.coerceIn(0f, 1f)
    val chroma = (1f - kotlin.math.abs(2f * light - 1f)) * sat
    val section = hue / 60f
    val secondary = chroma * (1f - kotlin.math.abs(section % 2f - 1f))
    val (redPrime, greenPrime, bluePrime) = when (section.toInt()) {
        0 -> Triple(chroma, secondary, 0f)
        1 -> Triple(secondary, chroma, 0f)
        2 -> Triple(0f, chroma, secondary)
        3 -> Triple(0f, secondary, chroma)
        4 -> Triple(secondary, 0f, chroma)
        else -> Triple(chroma, 0f, secondary)
    }
    val match = light - chroma / 2f
    val red = ((redPrime + match) * 255f).roundToInt().coerceIn(0, 255)
    val green = ((greenPrime + match) * 255f).roundToInt().coerceIn(0, 255)
    val blue = ((bluePrime + match) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
