/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MapRefreshStateStore] in a private directory. Reads map the file read-only (the mapping is not
 * Java heap); writes go to a temp file that is synced and renamed over the old one, so a crash
 * mid-write leaves the previous state intact. Plain `java.nio`, so host tests use it as is.
 */
internal class FileMapRefreshStateStore(
    private val directory: File,
    private val fileName: String = FILE_NAME,
) : MapRefreshStateStore {
    private val file get() = File(directory, fileName)
    private val temp get() = File(directory, "$fileName.tmp")

    override suspend fun load(): MapRefreshState? = withContext(Dispatchers.IO) {
        val target = file
        if (!target.isFile || target.length() < 16 || target.length() > MAX_BYTES) return@withContext null
        val mapped = runCatching {
            RandomAccessFile(target, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
            }
        }.getOrNull() ?: return@withContext null
        MapRefreshStateCodec.read(MappedBytes(mapped))
    }

    override suspend fun save(snapshot: MapRefreshSnapshot): Unit = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val staging = temp
        FileOutputStream(staging).use { stream ->
            val buffered = BufferedOutputStream(stream, 1 shl 16)
            MapRefreshStateCodec.write(snapshot, StreamSink(buffered))
            buffered.flush()
            stream.fd.sync()
        }
        if (!staging.renameTo(file)) {
            staging.delete()
            error("Could not replace $fileName")
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        file.delete()
        temp.delete()
    }

    private class MappedBytes(private val buffer: ByteBuffer) : MapRefreshBytes {
        override val size: Long = buffer.capacity().toLong()
        override fun byte(at: Long): Byte = buffer.get(at.toInt())
        override fun int(at: Long): Int = buffer.getInt(at.toInt())
        override fun long(at: Long): Long = buffer.getLong(at.toInt())
        override fun float(at: Long): Float = buffer.getFloat(at.toInt())
        // A mapping is released when the buffer is collected; there is no portable unmap.
        override fun close() = Unit
    }

    private class StreamSink(private val out: BufferedOutputStream) : MapRefreshSink {
        override fun byte(value: Byte) = out.write(value.toInt())
        override fun int(value: Int) { for (s in 0 until 4) out.write(value ushr (8 * s)) }
        override fun long(value: Long) { for (s in 0 until 8) out.write((value ushr (8 * s)).toInt()) }
    }

    companion object {
        const val FILE_NAME: String = "map_refresh_state.bin"
        /** Far above a 20,000-track state (~9 MB); anything bigger is not ours. */
        private const val MAX_BYTES: Long = 256L * 1024 * 1024
    }
}
