/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TargetFile] over positional channel reads and writes. Android hands a track over as a
 * `ParcelFileDescriptor`, whose descriptor yields a read channel and a write channel; a file in the
 * store is opened directly.
 *
 * [onClose] owns closing both channels and the descriptor behind them: [close] calls it exactly
 * once, however often [close] itself is called, and it should be idempotent all the same. Closing a
 * channel closes its descriptor, so an Android caller with a `ParcelFileDescriptor` closes the
 * channels and the descriptor there and nowhere else.
 *
 * Saves must not run under thread interruption (`runInterruptible`, `shutdownNow`): a [FileChannel]
 * closes itself when its thread is interrupted, which would cut a save off mid-write.
 */
public class ChannelTargetFile(
    private val reader: FileChannel,
    private val writer: FileChannel,
    private val onClose: () -> Unit,
) : TargetFile {
    private val closed = AtomicBoolean(false)

    override val length: Long get() = writer.size()

    override fun read(offset: Long, count: Int): ByteArray? {
        if (offset < 0 || count < 0 || offset > length || count > length - offset) return null
        val buffer = ByteBuffer.allocate(count)
        var at = offset
        while (buffer.hasRemaining()) {
            val read = reader.read(buffer, at)
            if (read < 0) return null
            at += read
        }
        return buffer.array()
    }

    override fun write(offset: Long, bytes: ByteArray, from: Int, count: Int) {
        val buffer = ByteBuffer.wrap(bytes, from, count)
        var at = offset
        try {
            while (buffer.hasRemaining()) at += writer.write(buffer, at)
        } catch (e: IOException) {
            throw noSpaceOr(e)
        }
    }

    override fun setLength(length: Long) {
        val size = writer.size()
        when {
            length < size -> writer.truncate(length)
            length > size -> write(length - 1, byteArrayOf(0))
        }
    }

    override fun force() {
        // With delayed allocation the disk-full error surfaces here, not at write.
        try {
            writer.force(true)
        } catch (e: IOException) {
            throw noSpaceOr(e)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }

    public companion object {
        public fun open(file: File): ChannelTargetFile {
            val access = RandomAccessFile(file, "rw")
            return ChannelTargetFile(access.channel, access.channel) { access.close() }
        }

        internal fun isNoSpace(e: IOException): Boolean =
            e.message?.let { "ENOSPC" in it || "No space left" in it || "EDQUOT" in it || "Disk quota exceeded" in it } == true

        private fun noSpaceOr(e: IOException): Exception =
            if (isNoSpace(e)) StorageFullException(e.message ?: "no space") else e
    }
}
