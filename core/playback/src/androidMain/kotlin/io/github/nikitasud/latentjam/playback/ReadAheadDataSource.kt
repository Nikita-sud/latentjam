/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * [inner] with small reads served from a read-ahead buffer.
 *
 * Extractors read straight from the data source. Most read in large blocks, but a seek in a FLAC file
 * without a seek table searches the file for frame headers a few bytes at a time: on a Galaxy S24
 * Ultra that was over 100,000 reads per seek in a 24-bit/96 kHz track, close to two seconds of
 * silence after every jump. Each read through a media-store file costs a trip through the storage
 * layer, so the bytes are fetched in blocks of [bufferSize] and handed out from memory; a read at
 * least [DIRECT_READ_BYTES] long, as playback makes, goes to [inner] directly once the buffer is empty.
 */
@OptIn(UnstableApi::class)
internal class ReadAheadDataSource(
    private val inner: DataSource,
    bufferSize: Int = DEFAULT_BUFFER_BYTES,
) : DataSource {
    private val buffer = ByteArray(bufferSize)
    private var position = 0
    private var limit = 0

    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        position = 0
        limit = 0
        return inner.open(dataSpec)
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position == limit) {
            if (length >= DIRECT_READ_BYTES) return inner.read(target, offset, length)
            val read = inner.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            position = 0
            limit = read
        }
        val count = minOf(length, limit - position)
        System.arraycopy(buffer, position, target, offset, count)
        position += count
        return count
    }

    override fun getUri(): Uri? = inner.uri

    override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

    override fun close() {
        position = 0
        limit = 0
        inner.close()
    }

    class Factory(private val inner: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ReadAheadDataSource(inner.createDataSource())
    }

    companion object {
        const val DEFAULT_BUFFER_BYTES: Int = 64 * 1024
        const val DIRECT_READ_BYTES: Int = 8 * 1024
    }
}
