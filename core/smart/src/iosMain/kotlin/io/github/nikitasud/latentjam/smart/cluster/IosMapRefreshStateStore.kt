/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedAlways
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * [MapRefreshStateStore] in the app's Caches directory (regenerable, excluded from backup). Reads
 * use a mapped `NSData` (`NSDataReadingMappedAlways`), so the per-track arrays are not Kotlin heap;
 * writes are atomic (`writeToFile(atomically = true)`).
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosMapRefreshStateStore(private val fileName: String = "map_refresh_state.bin") : MapRefreshStateStore {

    override suspend fun load(): MapRefreshState? = withContext(Dispatchers.Default) {
        val path = path() ?: return@withContext null
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
        val data = NSData.dataWithContentsOfFile(path, NSDataReadingMappedAlways, null) ?: return@withContext null
        if (data.length.toLong() < 16) return@withContext null
        MapRefreshStateCodec.read(MappedBytes(data))
    }

    override suspend fun save(snapshot: MapRefreshSnapshot): Unit = withContext(Dispatchers.Default) {
        val path = checkNotNull(path()) { "Caches directory is unavailable" }
        val sink = ArrayMapRefreshSink(1 shl 20)
        MapRefreshStateCodec.write(snapshot, sink)
        val bytes = sink.toByteArray()
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        check(data.writeToFile(path, true)) { "Could not write $fileName" }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.Default) {
        val path = path() ?: return@withContext
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        Unit
    }

    private fun path(): String? {
        val directory = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
            ?: return null
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(directory)) manager.createDirectoryAtPath(directory, true, null, null)
        return if (directory.endsWith('/')) directory + fileName else "$directory/$fileName"
    }

    /** Keeps [data] (and so the mapping) alive; reads compose bytes, so alignment does not matter. */
    private class MappedBytes(private val data: NSData) : MapRefreshBytes {
        private val base: CPointer<ByteVar> = checkNotNull(data.bytes).reinterpret()
        override val size: Long = data.length.toLong()
        override fun byte(at: Long): Byte = base[at]
        override fun int(at: Long): Int =
            (base[at].toInt() and 0xFF) or ((base[at + 1].toInt() and 0xFF) shl 8) or
                ((base[at + 2].toInt() and 0xFF) shl 16) or ((base[at + 3].toInt() and 0xFF) shl 24)
        override fun long(at: Long): Long = (int(at).toLong() and 0xFFFFFFFFL) or (int(at + 4).toLong() shl 32)
        override fun close() = Unit
    }
}
