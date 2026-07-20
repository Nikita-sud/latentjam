/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIImage
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes the cached embedded cover and runs the same small colour histogram
 * used on Android. Keeping this native and dependency-free avoids sending the
 * artwork through the model merely to colour the player.
 */
@Composable
actual fun rememberArtworkColor(uri: String?): Color? {
    var color by remember(uri) { mutableStateOf<Color?>(null) }
    LaunchedEffect(uri) {
        color = uri?.let { withContext(Dispatchers.Default) { sampleArtwork(it) } }
    }
    return color
}

@OptIn(ExperimentalForeignApi::class)
private fun sampleArtwork(uri: String): Color? = runCatching {
    val url = NSURL.URLWithString(uri) ?: return null
    val data = NSData.dataWithContentsOfURL(url) ?: return null
    val image = UIImage.imageWithData(data) ?: return null
    val cgImage = image.CGImage ?: return null
    dominantAccent(cgImage)
}.getOrNull()

@OptIn(ExperimentalForeignApi::class)
private fun dominantAccent(image: CGImageRef): Color? {
    val side = 64
    val bytesPerPixel = 4
    val bytesPerRow = side * bytesPerPixel
    val pixels = ByteArray(bytesPerRow * side)
    val colorSpace = CGColorSpaceCreateDeviceRGB()
    val rendered = pixels.usePinned { pinned ->
        val context = CGBitmapContextCreate(
            data = pinned.addressOf(0),
            width = side.toULong(),
            height = side.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = bytesPerRow.toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
        ) ?: return@usePinned false
        CGContextDrawImage(context, CGRectMake(0.0, 0.0, side.toDouble(), side.toDouble()), image)
        CGContextRelease(context)
        true
    }
    CGColorSpaceRelease(colorSpace)
    if (!rendered) return null

    val counts = HashMap<Int, Int>()
    val sums = HashMap<Int, IntArray>()
    for (offset in pixels.indices step bytesPerPixel) {
        val red = pixels[offset].toUByte().toInt()
        val green = pixels[offset + 1].toUByte().toInt()
        val blue = pixels[offset + 2].toUByte().toInt()
        val maxChannel = max(red, max(green, blue))
        val minChannel = min(red, min(green, blue))
        if (maxChannel < 24 || minChannel > 236 || maxChannel - minChannel < 24) continue
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
