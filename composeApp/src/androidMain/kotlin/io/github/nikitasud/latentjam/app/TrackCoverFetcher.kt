/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.ContentMetadata
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toAndroidUri
import io.github.nikitasud.latentjam.smart.MediaStoreArtwork
import okio.buffer
import okio.source

/**
 * Loads a song's own cover, or its album's when the song has none (see [MediaStoreArtwork]).
 *
 * Coil's own content fetcher would fail on a song without a cover of its own and cache nothing,
 * so the row would stay blank where its album has a cover. This one opens the album's cover under
 * the song's URI instead, which the memory cache then keeps under that same key. Every other URI
 * goes to Coil's fetchers unchanged.
 */
internal class TrackCoverFetcher(
    private val data: Uri,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val descriptor = MediaStoreArtwork.openDescriptor(options.context.contentResolver, data.toAndroidUri())
        checkNotNull(descriptor) { "Unable to open '$data'." }
        return SourceFetchResult(
            source = ImageSource(
                source = descriptor.createInputStream().source().buffer(),
                fileSystem = options.fileSystem,
                metadata = ContentMetadata(data, descriptor),
            ),
            // MediaProvider's covers are JPEG thumbnails; the decoder sniffs the bytes either way.
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (MediaStoreArtwork.albumFallback(data.toString()) != null) TrackCoverFetcher(data, options) else null
    }
}
