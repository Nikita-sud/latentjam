/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Samples cover art for its most characteristic colour.
 *
 * Deliberately dependency-free: the cover is decoded subsampled (it is only
 * needed as a colour histogram), quantized into a coarse RGB grid, and the
 * bucket with the best population×saturation score wins — near-white,
 * near-black and washed-out pixels are ignored so the accent comes from the
 * artwork's actual character rather than its background. Failures (missing
 * album-art row, unreadable file) simply yield `null`.
 */
@Composable
actual fun rememberArtworkColor(uri: String?): Color? {
    val context = LocalContext.current
    var color by remember(uri) { mutableStateOf<Color?>(null) }
    LaunchedEffect(uri) {
        color = uri?.let { artworkUri ->
            val cached = artworkColorCache[artworkUri]?.takeIf { entry ->
                entry.value != null || entry.storedAt.elapsedNow() < NEGATIVE_CACHE_TTL
            }
            if (cached != null) {
                cached.value
            } else {
                withContext(Dispatchers.IO) {
                    sampleArtwork(context, artworkUri)
                }.also { sampled ->
                    if (artworkColorCache.size >= ARTWORK_COLOR_CACHE_SIZE) {
                        artworkColorCache.keys.firstOrNull()?.let(artworkColorCache::remove)
                    }
                    artworkColorCache[artworkUri] = CachedArtworkColor(
                        value = sampled,
                        storedAt = TimeSource.Monotonic.markNow(),
                    )
                }
            }
        }
    }
    return color
}

private fun sampleArtwork(context: Context, uri: String): Color? = runCatching {
    val parsed = Uri.parse(uri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed).use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (
        bounds.outWidth / (sample * 2) >= ARTWORK_SAMPLE_SIDE ||
        bounds.outHeight / (sample * 2) >= ARTWORK_SAMPLE_SIDE
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = context.contentResolver.openInputStream(Uri.parse(uri)).use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    } ?: return null
    val accent = dominantAccent(bitmap)
    bitmap.recycle()
    accent
}.getOrNull()

private fun dominantAccent(bitmap: Bitmap): Color? =
    sampleArtworkAccentArgb(bitmap)?.let { argb ->
        Color(
            red = ((argb shr 16) and 0xFF) / 255f,
            green = ((argb shr 8) and 0xFF) / 255f,
            blue = (argb and 0xFF) / 255f,
        )
    }

/**
 * The app's accent sampler as raw ARGB, shared with the widgets so the home screen wears the
 * SAME colour the player derives for the same cover.
 */
internal fun sampleArtworkAccentArgb(bitmap: Bitmap): Int? {
    val width = bitmap.width
    val height = bitmap.height
    if (width == 0 || height == 0) return null

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    // 5 bits per channel keeps buckets tight enough to stay faithful and
    // loose enough that gradients in a cover still land together.
    val counts = HashMap<Int, Int>()
    val sums = HashMap<Int, IntArray>()
    for (pixel in pixels) {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        val maxChannel = max(red, max(green, blue))
        val minChannel = min(red, min(green, blue))
        if (maxChannel < 24 || minChannel > 236) continue // near-black / near-white
        if (maxChannel - minChannel < 24) continue // greyscale
        val key = (red shr 3 shl 10) or (green shr 3 shl 5) or (blue shr 3)
        counts[key] = (counts[key] ?: 0) + 1
        val sum = sums.getOrPut(key) { IntArray(3) }
        sum[0] += red
        sum[1] += green
        sum[2] += blue
    }
    if (counts.isEmpty()) return null

    val best = counts.maxByOrNull { (key, count) ->
        val sum = sums.getValue(key)
        val red = sum[0] / count
        val green = sum[1] / count
        val blue = sum[2] / count
        val saturation = max(red, max(green, blue)) - min(red, min(green, blue))
        // Frequency decides, but a vivid runner-up can still take it.
        count * (1.0 + saturation / 255.0)
    }?.key ?: return null

    val count = counts.getValue(best)
    val sum = sums.getValue(best)
    return (0xFF shl 24) or ((sum[0] / count) shl 16) or ((sum[1] / count) shl 8) or (sum[2] / count)
}

private data class CachedArtworkColor(val value: Color?, val storedAt: TimeMark)

/** Includes negative results so missing MediaStore album-art rows are not decoded repeatedly. */
private val artworkColorCache = LinkedHashMap<String, CachedArtworkColor>()
private const val ARTWORK_COLOR_CACHE_SIZE = 64
private const val ARTWORK_SAMPLE_SIDE = 96
private val NEGATIVE_CACHE_TTL = 30.seconds
