/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nikitasud.latentjam.library.tags.ByteArraySource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Android decoding: a header probe alone cannot verify EXIF or bitmap allocation limits. */
class AndroidTrackArtworkDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun rotatedJpegIsDisplayedUpright() = withJpeg(80, 40, ExifInterface.ORIENTATION_ROTATE_90) { file ->
        val bitmap = AndroidTrackArtwork.loadBitmap(context.contentResolver, Uri.fromFile(file), 128)!!
        try {
            assertEquals(40, bitmap.width)
            assertEquals(80, bitmap.height)
            assertRed(bitmap.getPixel(20, 20))
            assertBlue(bitmap.getPixel(20, 60))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun mirroredJpegPreservesItsExifReflection() = withJpeg(80, 40, ExifInterface.ORIENTATION_FLIP_HORIZONTAL) { file ->
        val bitmap = AndroidTrackArtwork.loadBitmap(context.contentResolver, Uri.fromFile(file), 128)!!
        try {
            assertEquals(80, bitmap.width)
            assertEquals(40, bitmap.height)
            assertBlue(bitmap.getPixel(20, 20))
            assertRed(bitmap.getPixel(60, 20))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun largeRotatedImageStaysWithinBothDimensionLimits() = withJpeg(2049, 257, ExifInterface.ORIENTATION_ROTATE_270) { file ->
        val bitmap = AndroidTrackArtwork.loadBitmap(context.contentResolver, Uri.fromFile(file), 64)
        assertNotNull(bitmap)
        try {
            assertTrue(bitmap!!.width in 1..64)
            assertTrue(bitmap.height in 1..64)
            assertTrue(bitmap.height > bitmap.width)
        } finally {
            bitmap?.recycle()
        }
    }

    @Test
    fun emptyAndUndecodableProviderFilesHaveNoBitmap() {
        val file = File.createTempFile("artwork-invalid-", ".jpg", context.cacheDir)
        try {
            assertNull(AndroidTrackArtwork.loadBitmap(context.contentResolver, Uri.fromFile(file), 64))
            file.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3))
            assertNull(AndroidTrackArtwork.loadBitmap(context.contentResolver, Uri.fromFile(file), 64))
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingThumbnailDecodesTheMp3EmbeddedPictureBeforeTheAlbum() =
        withJpeg(80, 40, ExifInterface.ORIENTATION_ROTATE_90) { file ->
            val song = "content://media/external/audio/media/42/albumart?album=7&v=abc"
            val jpeg = file.readBytes()
            val apic = byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0) + jpeg
            val frame = "APIC".toByteArray() + intBytes(apic.size) + byteArrayOf(0, 0) + apic
            val size = frame.size
            val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + byteArrayOf(
                ((size shr 21) and 127).toByte(), ((size shr 14) and 127).toByte(),
                ((size shr 7) and 127).toByte(), (size and 127).toByte(),
            ) + frame + byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64) + ByteArray(128)
            val bitmap = loadArtworkWithFallback(song, { locator ->
                assertEquals("album must not replace the file's own picture", song, locator)
                null
            }, { audio ->
                assertEquals("content://media/external/audio/media/42", audio)
                decodeEmbeddedArtwork(ByteArraySource(tag), 128)
            })!!
            try {
                assertEquals(40, bitmap.width)
                assertEquals(80, bitmap.height)
                assertRed(bitmap.getPixel(20, 20))
                assertBlue(bitmap.getPixel(20, 60))
            } finally {
                bitmap.recycle()
            }
        }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
    )

    private fun withJpeg(width: Int, height: Int, orientation: Int, check: (File) -> Unit) {
        val file = File.createTempFile("artwork-exif-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.RED)
                Canvas(bitmap).drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.BLUE })
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            check(file)
        } finally {
            file.delete()
        }
    }

    private fun assertRed(color: Int) = assertTrue(Color.red(color) > Color.blue(color) + 100)
    private fun assertBlue(color: Int) = assertTrue(Color.blue(color) > Color.red(color) + 100)
}
