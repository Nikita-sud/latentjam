/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import io.github.nikitasud.latentjam.library.tags.RandomAccessSource
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.smart.MediaStoreArtwork
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Semaphore
import kotlin.math.ceil

/**
 * Artwork shared by the app, widgets and media session. An unusable provider thumbnail falls
 * back to the song's embedded picture, then its album's thumbnail. Saved file overrides stay
 * file overrides, and removed artwork stays null: neither can trigger an embedded lookup.
 *
 * Call off the main thread. Reads and bitmap allocation have fixed ceilings; no audio file is
 * copied in full and no extracted picture is written into the cover-edit override store.
 */
public object AndroidTrackArtwork {
    public const val MAX_DIMENSION: Int = 2048

    // Decoding happens in fetchers as well as media-session workers, outside Coil's decoder
    // semaphore. Share one permit so scrolling cannot multiply the metadata/bitmap ceilings.
    private val readPermit = Semaphore(1)

    public fun loadBitmap(resolver: ContentResolver, uri: Uri, maxDimension: Int = MAX_DIMENSION): Bitmap? =
        loadBitmap(resolver, uri, maxDimension.coerceIn(1, MAX_DIMENSION), null)

    /**
     * Decode to the display's pixel bounds, preserving aspect ratio and optional center-crop.
     * BitmapFactory combines subsampling and density scaling so Coil retains display-sized
     * pixels instead of an oversized power-of-two bitmap. Android may allocate both sampled
     * and scaled pixels temporarily; both obey the global dimension ceiling. Small source
     * pictures and samples limited by that ceiling are never enlarged.
     */
    public fun loadDisplayBitmap(
        resolver: ContentResolver,
        uri: Uri,
        width: Int,
        height: Int,
        crop: Boolean,
    ): Bitmap? = loadBitmap(
        resolver, uri, MAX_DIMENSION,
        ArtworkDisplaySize(width.coerceIn(1, MAX_DIMENSION), height.coerceIn(1, MAX_DIMENSION), crop),
    )

    private fun loadBitmap(
        resolver: ContentResolver,
        uri: Uri,
        dimension: Int,
        displaySize: ArtworkDisplaySize?,
    ): Bitmap? {
        readPermit.acquire()
        try {
            return readBitmap(resolver, uri, dimension, displaySize)
        } finally {
            readPermit.release()
        }
    }

    private fun readBitmap(
        resolver: ContentResolver,
        uri: Uri,
        dimension: Int,
        displaySize: ArtworkDisplaySize?,
    ): Bitmap? =
        loadArtworkWithFallback(
            uri = uri.toString(),
            provider = { locator ->
                resolver.openInputStream(Uri.parse(locator))?.use { input ->
                    readArtworkBytes(input)?.let { decodeArtwork(it, dimension, displaySize) }
                }
            },
            embedded = { audio ->
                resolver.openFileDescriptor(Uri.parse(audio), "r")?.use { descriptor ->
                    // The ParcelFileDescriptor owns the descriptor; its stream must not close it twice.
                    val channel = FileInputStream(descriptor.fileDescriptor).channel
                    decodeEmbeddedArtwork(ArtworkChannelSource(channel), dimension, displaySize)
                }
            },
        )
}

/** Shared by the descriptor adapter and real Android codec/bitmap regression fixtures. */
internal fun decodeEmbeddedArtwork(
    source: RandomAccessSource,
    maxDimension: Int,
    displaySize: ArtworkDisplaySize? = null,
): Bitmap? = TagCodecs.readCover(BoundedArtworkSource(source))?.bytes?.let {
    decodeArtwork(it, maxDimension, displaySize)
}

/** A decode failure is absence too. Permission, I/O and cancellation failures remain failures. */
internal fun <T : Any> loadArtworkWithFallback(
    uri: String,
    provider: (String) -> T?,
    embedded: (String) -> T?,
): T? {
    missingArtwork { provider(uri) }?.let { return it }
    MediaStoreArtwork.audioFallback(uri)?.let { audio ->
        missingArtwork { embedded(audio) }?.let { return it }
    }
    return MediaStoreArtwork.albumFallback(uri)?.let { album -> missingArtwork { provider(album) } }
}

private inline fun <T> missingArtwork(read: () -> T?): T? = try {
    checkArtworkInterrupted()
    read()
} catch (_: FileNotFoundException) {
    null
}

/** One extra byte distinguishes an exactly-at-limit picture from a larger one. */
internal fun readArtworkBytes(input: InputStream, maxBytes: Int = TagCodecs.MAX_COVER_BYTES): ByteArray? {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val chunk = ByteArray(minOf(maxBytes, 8192))
    while (output.size() < maxBytes) {
        checkArtworkInterrupted()
        val count = input.read(chunk, 0, minOf(chunk.size, maxBytes - output.size()))
        if (count < 0) return output.toByteArray().takeIf { it.isNotEmpty() }
        if (count == 0) return null
        output.write(chunk, 0, count)
    }
    checkArtworkInterrupted()
    return if (input.read() == -1) output.toByteArray() else null
}

/** Bounds metadata reads before the codecs allocate their buffers, not just their final picture. */
internal class BoundedArtworkSource(
    private val source: RandomAccessSource,
    private val maxReadBytes: Int = TagCodecs.MAX_COVER_BYTES * 2,
    private val maxTotalBytes: Long = TagCodecs.MAX_COVER_BYTES.toLong() * 4,
) : RandomAccessSource {
    override val length: Long get() = source.length
    private var remaining = maxTotalBytes

    override fun read(offset: Long, count: Int): ByteArray? {
        checkArtworkInterrupted()
        if (offset < 0 || offset > length || count < 0 || count > length - offset ||
            count > maxReadBytes || count > remaining
        ) return null
        remaining -= count
        return source.read(offset, count)
    }
}

private class ArtworkChannelSource(private val channel: FileChannel) : RandomAccessSource {
    override val length: Long = channel.size()

    override fun read(offset: Long, count: Int): ByteArray? {
        if (offset < 0 || offset > length || count < 0 || count > length - offset) return null
        val buffer = ByteBuffer.allocate(count)
        var position = offset
        while (buffer.hasRemaining()) {
            checkArtworkInterrupted()
            val read = channel.read(buffer, position)
            if (read <= 0) return null
            position += read
        }
        return buffer.array()
    }
}

internal fun artworkSampleSize(width: Int, height: Int, maxDimension: Int): Int? {
    if (width !in 1..100_000 || height !in 1..100_000 || maxDimension <= 0) return null
    var sample = 1
    while ((maxOf(width, height).toLong() + sample - 1) / sample > maxDimension) sample *= 2
    return sample
}

internal data class ArtworkDisplaySize(val width: Int, val height: Int, val crop: Boolean)

internal data class ArtworkDecodeScale(val sampleSize: Int, val density: Int, val targetDensity: Int)

/**
 * Like Coil's BitmapFactoryDecoder, retain the smallest sufficient power-of-two sample and
 * perform the remaining downscale using BitmapFactory's density ratio. Width/height here are
 * encoded dimensions; the display request is swapped before this call for 90/270-degree EXIF.
 */
internal fun artworkDisplayScale(
    width: Int,
    height: Int,
    display: ArtworkDisplaySize,
    maxDimension: Int = AndroidTrackArtwork.MAX_DIMENSION,
): ArtworkDecodeScale? {
    val minimumSample = artworkSampleSize(width, height, maxDimension) ?: return null
    if (display.width <= 0 || display.height <= 0) return null
    val horizontal = display.width.toDouble() / width
    val vertical = display.height.toDouble() / height
    val requestedScale = if (display.crop) maxOf(horizontal, vertical) else minOf(horizontal, vertical)
    val scale = minOf(1.0, requestedScale, maxDimension.toDouble() / maxOf(width, height))
    var sample = 1
    while (1.0 / (sample * 2) >= scale) sample *= 2
    // Density scaling can allocate both the sampled and final bitmap inside BitmapFactory.
    // Preserve the existing ceiling before either allocation, even when that means a large
    // crop request receives fewer pixels than it asked for. Never upscale the bounded sample.
    sample = maxOf(sample, minimumSample)
    // JPEG and other subsampled formats may round a non-divisible edge up. Using the ceiling
    // also keeps odd-sized images inside the hard limit when density scaling follows sampling.
    val sampledMax = (maxOf(width, height) + sample - 1) / sample
    val targetMax = ceil(maxOf(width, height) * scale).toInt().coerceIn(1, maxDimension)
    return ArtworkDecodeScale(sample, sampledMax, minOf(sampledMax, targetMax))
}

private fun decodeArtwork(
    bytes: ByteArray,
    maxDimension: Int,
    displaySize: ArtworkDisplaySize? = null,
): Bitmap? {
    checkArtworkInterrupted()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = artworkSampleSize(bounds.outWidth, bounds.outHeight, maxDimension) ?: return null
    val transform = readArtworkTransform(bytes)
    val displayScale = displaySize?.let { display ->
        val encodedDisplay = if (transform.rotation % 180 != 0) {
            display.copy(width = display.height, height = display.width)
        } else display
        artworkDisplayScale(bounds.outWidth, bounds.outHeight, encodedDisplay, maxDimension)
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = displayScale?.sampleSize ?: sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        if (displayScale != null && displayScale.targetDensity < displayScale.density) {
            inScaled = true
            inDensity = displayScale.density
            inTargetDensity = displayScale.targetDensity
        }
    }
    checkArtworkInterrupted()
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    // Density is only the decoder's scaling mechanism; callers use the actual pixel dimensions.
    if (displayScale != null) bitmap.density = Bitmap.DENSITY_NONE
    if (maxOf(bitmap.width, bitmap.height) > maxDimension) {
        bitmap.recycle()
        return null
    }
    return orientArtwork(bitmap, transform)
}

internal data class ArtworkTransform(val rotation: Int = 0, val flipped: Boolean = false)

/** Flip first, then rotate clockwise, matching the EXIF display transform. */
internal fun artworkTransform(orientation: Int): ArtworkTransform = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ArtworkTransform(flipped = true)
    ExifInterface.ORIENTATION_ROTATE_180 -> ArtworkTransform(rotation = 180)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ArtworkTransform(rotation = 180, flipped = true)
    ExifInterface.ORIENTATION_TRANSPOSE -> ArtworkTransform(rotation = 270, flipped = true)
    ExifInterface.ORIENTATION_ROTATE_90 -> ArtworkTransform(rotation = 90)
    ExifInterface.ORIENTATION_TRANSVERSE -> ArtworkTransform(rotation = 90, flipped = true)
    ExifInterface.ORIENTATION_ROTATE_270 -> ArtworkTransform(rotation = 270)
    else -> ArtworkTransform()
}

private fun readArtworkTransform(bytes: ByteArray): ArtworkTransform {
    val orientation = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (_: IOException) {
        // Malformed/unsupported optional EXIF must not hide an otherwise decodable picture.
        ExifInterface.ORIENTATION_NORMAL
    }
    return artworkTransform(orientation)
}

private fun orientArtwork(bitmap: Bitmap, transform: ArtworkTransform): Bitmap {
    if (!transform.flipped && transform.rotation == 0) return bitmap
    val matrix = Matrix().apply {
        if (transform.flipped) postScale(-1f, 1f)
        if (transform.rotation != 0) postRotate(transform.rotation.toFloat())
    }
    var oriented: Bitmap? = null
    try {
        // Only right-angle rotation/reflection: dimensions stay within the decode ceiling.
        oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return oriented
    } finally {
        if (oriented !== bitmap) bitmap.recycle()
    }
}

private fun checkArtworkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Artwork read interrupted")
}
