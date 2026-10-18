/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * List-sized covers kept on disk as small JPEGs, so a cover is extracted from its audio file once
 * and every later launch reads a few kilobytes instead of parsing tags and decoding the full
 * embedded picture again. Tracks with no cover leave an empty marker, so scrolling past them does
 * not retry the provider, the embedded picture and the album fallback every time.
 *
 * Keys are the cover URI itself. Track cover URIs carry their album's revision (`?v=…`), so a tag
 * edit, a new embedded picture or an external change gives the cover a new key and the old file
 * simply ages out. Only square, centre-cropped requests up to [MAX_CACHED_PX] are kept: list rows,
 * grid cards, the mini player. The large player cover is still decoded fresh at full size.
 */
internal class CoverThumbCache(context: Context) {
    private val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
    private val writes = AtomicInteger()

    /** The bucket a request of [size] px is stored at, or null when it is not cached. */
    fun bucketFor(width: Int, height: Int, crop: Boolean): Int? {
        if (!crop || width != height) return null
        return BUCKETS.firstOrNull { it >= width }
    }

    sealed interface Lookup {
        data class Hit(val bitmap: Bitmap) : Lookup
        data object Missing : Lookup
        data object Unknown : Lookup
    }

    /** A stored cover decoded straight to [target] px, the "no cover" marker, or nothing yet. */
    fun read(uri: String, bucket: Int, target: Int): Lookup {
        val file = fileFor(uri, bucket)
        if (!file.exists()) {
            return if (markerFor(uri).exists()) Lookup.Missing else Lookup.Unknown
        }
        val stored = BitmapFactory.decodeFile(file.path)
        if (stored == null) {
            file.delete()
            return Lookup.Unknown
        }
        touch(file)
        // A small source picture is stored at its own size, never enlarged to the bucket.
        return Lookup.Hit(stored.scaledDownTo(target))
    }

    /** Stores [bitmap], decoded at [bucket] px or smaller. */
    fun write(uri: String, bucket: Int, bitmap: Bitmap) {
        val file = fileFor(uri, bucket)
        // Written beside and renamed into place: a crash mid-write leaves no half file to serve.
        val partial = File(directory, file.name + ".part")
        val written = runCatching {
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        }.getOrDefault(false)
        if (written && partial.renameTo(file)) markerFor(uri).delete() else partial.delete()
        afterWrite()
    }

    fun markMissing(uri: String) {
        runCatching { markerFor(uri).createNewFile() }
        afterWrite()
    }

    private fun Bitmap.scaledDownTo(target: Int): Bitmap {
        if (width <= target || target <= 0) return this
        val scaled = Bitmap.createScaledBitmap(this, target, (height.toLong() * target / width).toInt().coerceAtLeast(1), true)
        if (scaled !== this) recycle()
        return scaled
    }

    /** [bitmap] at no more than [target] px across, for the memory cache. */
    fun fit(bitmap: Bitmap, target: Int): Bitmap = bitmap.scaledDownTo(target)

    private fun fileFor(uri: String, bucket: Int) = File(directory, "${digest(uri)}-$bucket.jpg")

    private fun markerFor(uri: String) = File(directory, "${digest(uri)}.none")

    /** Least-recently-used order without a write per scroll: refresh a file's time once a day. */
    private fun touch(file: File) {
        val now = System.currentTimeMillis()
        if (now - file.lastModified() > TOUCH_INTERVAL_MS) file.setLastModified(now)
    }

    private fun afterWrite() {
        if (writes.incrementAndGet() % PRUNE_EVERY != 0) return
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES * 8 / 10) break
            total -= file.length()
            file.delete()
        }
    }

    private fun digest(uri: String): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    companion object {
        private const val DIRECTORY = "cover_thumbs"
        private val BUCKETS = listOf(128, 256, 512)
        const val MAX_CACHED_PX = 512
        private const val JPEG_QUALITY = 90
        private const val MAX_BYTES = 64L * 1024 * 1024
        private const val PRUNE_EVERY = 64
        private const val TOUCH_INTERVAL_MS = 24L * 60 * 60 * 1000

        @Volatile private var instance: CoverThumbCache? = null

        fun get(context: Context): CoverThumbCache =
            instance ?: synchronized(this) {
                instance ?: CoverThumbCache(context.applicationContext).also { instance = it }
            }
    }
}
