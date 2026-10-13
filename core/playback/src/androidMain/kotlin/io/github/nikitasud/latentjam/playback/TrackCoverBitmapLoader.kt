/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.nikitasud.latentjam.library.AndroidTrackArtwork
import io.github.nikitasud.latentjam.smart.MediaStoreArtwork
import java.util.concurrent.Executors

/** Shares the app's embedded fallback and bounded decoding with notification / lock-screen art. */
@UnstableApi
internal class TrackCoverBitmapLoader(
    private val delegate: BitmapLoader,
    private val resolver: ContentResolver,
    private val maxDimension: Int,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = delegate.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (MediaStoreArtwork.audioFallback(uri.toString()) == null) return delegate.loadBitmap(uri)
        return artworkExecutor.submit<Bitmap> {
            checkNotNull(AndroidTrackArtwork.loadBitmap(resolver, uri, maxDimension)) { "Unable to decode '$uri'." }
        }
    }

    private companion object {
        // One worker for the process; cancellation interrupts its bounded reads.
        val artworkExecutor = MoreExecutors.listeningDecorator(
            Executors.newSingleThreadExecutor { work -> Thread(work, "latentjam-artwork").apply { isDaemon = true } },
        )
    }
}
