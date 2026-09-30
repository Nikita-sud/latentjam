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

/**
 * [TargetFile] over positional channel reads and writes. Android hands a track over as a
 * `ParcelFileDescriptor`, whose descriptor yields a read channel and a write channel; a file in the
 * store is opened directly.
 */
public class ChannelTargetFile(
    private val reader: FileChannel,
    private val writer: FileChannel,
    private val onClose: () -> Unit,
) : TargetFile {
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
            throw if (e.isNoSpace()) StorageFullException(e.message ?: "no space") else e
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
        writer.force(true)
    }

    override fun close() {
        onClose()
    }

    public companion object {
        public fun open(file: File): ChannelTargetFile {
            val access = RandomAccessFile(file, "rw")
            return ChannelTargetFile(access.channel, access.channel) { access.close() }
        }

        private fun IOException.isNoSpace(): Boolean =
            message?.let { "ENOSPC" in it || "No space left" in it } == true
    }
}
