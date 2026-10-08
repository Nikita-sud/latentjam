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
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Decodes the cached embedded cover and runs the same small colour histogram
 * used on Android. Keeping this native and dependency-free avoids sending the
 * artwork through the model merely to colour the player.
 */
@Composable
actual fun rememberArtworkColor(uri: String?): ArtworkColorState {
    var state by remember(uri) {
        mutableStateOf(ArtworkColorState(color = null, resolved = uri == null))
    }
    LaunchedEffect(uri) {
        val color = uri?.let { artworkUri ->
            val cached = artworkColorCache[artworkUri]?.takeIf { entry ->
                entry.value != null || entry.storedAt.elapsedNow() < NEGATIVE_CACHE_TTL
            }
            if (cached != null) {
                cached.value
            } else {
                withContext(Dispatchers.Default) { sampleArtwork(artworkUri) }.also { sampled ->
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
        state = ArtworkColorState(color = color, resolved = true)
    }
    return state
}

@OptIn(ExperimentalForeignApi::class)
private fun sampleArtwork(uri: String): Color? = runCatching {
    // Every iOS artwork URI is a cached file written by the library's `cacheArtwork`, so a
    // non-file URL is not a cover: fall back to the identity colour rather than fetching it.
    val url = NSURL.URLWithString(uri)?.takeIf { it.isFileURL() } ?: return null
    if (!artworkWithinLimit(url)) return null
    val image = decodeThumbnail(url, ARTWORK_SAMPLE_SIDE) ?: return null
    try {
        dominantAccent(image)
    } finally {
        CGImageRelease(image)
    }
}.getOrNull()

/**
 * Whether the cached cover is small enough to sample.
 *
 * The library refuses to write a cover over [TagCodecs.MAX_COVER_BYTES], so this only turns away
 * files a previous version cached and anything that has since gone missing — before ImageIO opens
 * them, because a colour histogram is never worth a multi-gigabyte read.
 */
@OptIn(ExperimentalForeignApi::class)
private fun artworkWithinLimit(url: NSURL): Boolean {
    val path = url.path ?: return false
    val bytes = (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        ?.get(NSFileSize) as? NSNumber)?.unsignedLongLongValue ?: return false
    return bytes in 1uL..TagCodecs.MAX_COVER_BYTES.toULong()
}

/**
 * Decodes the image at [url] with its longest side capped at [maxPixelSize].
 *
 * The histogram needs exactly [ARTWORK_SAMPLE_SIDE] pixels, but a cached cover can be a
 * multi-megapixel scan: `UIImage(data:)` would decode all of it — tens of megabytes of pixels for
 * one accent colour. ImageIO builds the thumbnail while decoding, so the full bitmap never exists.
 *
 * @return an image the caller owns and must release with `CGImageRelease`, or null when the file is
 *   unreadable or not an image.
 */
@OptIn(ExperimentalForeignApi::class)
private fun decodeThumbnail(url: NSURL, maxPixelSize: Int): CGImageRef? {
    val retainedUrl = CFBridgingRetain(url) ?: return null
    val options = CFDictionaryCreateMutable(
        null,
        0,
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    ) ?: run {
        CFRelease(retainedUrl)
        return null
    }
    try {
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        // Rotation from EXIF is irrelevant to a histogram, but applying it keeps the sampled frame
        // the same one the player shows.
        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
        val dimension = memScoped {
            val value = alloc<IntVar> { this.value = maxPixelSize }
            CFNumberCreate(null, kCFNumberIntType, value.ptr)
        } ?: return null
        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, dimension)
        CFRelease(dimension)
        val source = CGImageSourceCreateWithURL(retainedUrl.reinterpret(), null) ?: return null
        try {
            return CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
        } finally {
            CFRelease(source)
        }
    } finally {
        CFRelease(options)
        CFRelease(retainedUrl)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun dominantAccent(image: CGImageRef): Color? {
    val side = ARTWORK_SAMPLE_SIDE
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

private data class CachedArtworkColor(val value: Color?, val storedAt: TimeMark)

private val artworkColorCache = LinkedHashMap<String, CachedArtworkColor>()
private const val ARTWORK_COLOR_CACHE_SIZE = 64

/** The one frame the histogram needs; the cover itself is never decoded at full size. */
private const val ARTWORK_SAMPLE_SIDE = 64
private val NEGATIVE_CACHE_TTL = 30.seconds
