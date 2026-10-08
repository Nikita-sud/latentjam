/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.nikitasud.latentjam.smart.MediaStoreArtwork

/**
 * The session's bitmap loader: a song's own cover, or its album's when the song has none (see
 * [MediaStoreArtwork]), so the notification, the lock screen and Bluetooth show what the app shows.
 * Every other cover loads through [delegate] unchanged.
 */
@UnstableApi
internal class TrackCoverBitmapLoader(private val delegate: BitmapLoader) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = delegate.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val fallback = MediaStoreArtwork.albumFallback(uri.toString()) ?: return delegate.loadBitmap(uri)
        return Futures.catchingAsync(
            delegate.loadBitmap(uri),
            Exception::class.java,
            { delegate.loadBitmap(Uri.parse(fallback)) },
            MoreExecutors.directExecutor(),
        )
    }
}
