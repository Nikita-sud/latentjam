/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.os.SystemClock
import android.util.Log
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Scale
import coil3.size.pxOrElse
import coil3.toAndroidUri
import io.github.nikitasud.latentjam.library.AndroidTrackArtwork
import io.github.nikitasud.latentjam.smart.MediaStoreArtwork
import kotlinx.coroutines.runInterruptible

/** Uses the same bounded provider / embedded / album decode as widgets and the media session. */
internal class TrackCoverFetcher(
    private val data: Uri,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult = runInterruptible {
        val started = SystemClock.elapsedRealtime()
        val width = options.size.width.pxOrElse { AndroidTrackArtwork.MAX_DIMENSION }
        val height = options.size.height.pxOrElse { AndroidTrackArtwork.MAX_DIMENSION }
        val crop = options.scale == Scale.FILL
        val key = data.toString()
        val cache = CoverThumbCache.get(options.context)
        val bucket = cache.bucketFor(width, height, crop)
        if (bucket != null) {
            // A stored thumbnail skips the shared extraction permit entirely: it is a small file.
            when (val stored = cache.read(key, bucket, width)) {
                is CoverThumbCache.Lookup.Hit -> {
                    logCover("cache", width, started)
                    return@runInterruptible ImageFetchResult(
                        image = stored.bitmap.asImage(),
                        isSampled = true,
                        dataSource = DataSource.DISK,
                    )
                }
                CoverThumbCache.Lookup.Missing -> {
                    logCover("none", width, started)
                    error("No cover for '$data'.")
                }
                CoverThumbCache.Lookup.Unknown -> Unit
            }
        }
        val decoded = AndroidTrackArtwork.loadDisplayBitmap(
            resolver = options.context.contentResolver,
            uri = data.toAndroidUri(),
            width = bucket ?: width,
            height = bucket ?: height,
            crop = crop,
        )
        if (decoded == null) {
            // A cover that is genuinely absent (failures throw instead) is remembered as absent.
            if (bucket != null) cache.markMissing(key)
            error("Unable to decode '$data'.")
        }
        val bitmap = if (bucket != null) {
            cache.write(key, bucket, decoded)
            cache.fit(decoded, width)
        } else decoded
        logCover("extract", maxOf(width, height), started)
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (MediaStoreArtwork.audioFallback(data.toString()) != null) TrackCoverFetcher(data, options) else null
    }
}

/**
 * Cover timing for profiling, off unless `adb shell setprop log.tag.LJArt DEBUG`: one line per
 * fetch with how it was served, the requested size and the milliseconds from start to bitmap.
 */
internal fun logCover(source: String, size: Int, startedAt: Long) {
    if (Log.isLoggable(COVER_LOG_TAG, Log.DEBUG)) {
        Log.d(COVER_LOG_TAG, "$source size=$size ms=${SystemClock.elapsedRealtime() - startedAt}")
    }
}

private const val COVER_LOG_TAG = "LJArt"
