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
        color = uri?.let { withContext(Dispatchers.IO) { sampleArtwork(context, it) } }
    }
    return color
}

private fun sampleArtwork(context: Context, uri: String): Color? = runCatching {
    val options = BitmapFactory.Options().apply { inSampleSize = 8 }
    val bitmap = context.contentResolver.openInputStream(Uri.parse(uri)).use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    } ?: return null
    val accent = dominantAccent(bitmap)
    bitmap.recycle()
    accent
}.getOrNull()

private fun dominantAccent(bitmap: Bitmap): Color? {
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
    return Color(
        red = sum[0] / count / 255f,
        green = sum[1] / count / 255f,
        blue = sum[2] / count / 255f,
    )
}
