/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
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
        val side = maxOf(
            options.size.width.pxOrElse { AndroidTrackArtwork.MAX_DIMENSION },
            options.size.height.pxOrElse { AndroidTrackArtwork.MAX_DIMENSION },
        ).coerceAtMost(AndroidTrackArtwork.MAX_DIMENSION / 2) * 2
        val bitmap = checkNotNull(AndroidTrackArtwork.loadBitmap(options.context.contentResolver, data.toAndroidUri(), side)) {
            "Unable to decode '$data'."
        }
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (MediaStoreArtwork.audioFallback(data.toString()) != null) TrackCoverFetcher(data, options) else null
    }
}
