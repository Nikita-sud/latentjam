/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/** [RegionIdentityStore] in Caches beside [IosMapRefreshStateStore]; writes are atomic. */
@OptIn(ExperimentalForeignApi::class)
internal class IosRegionIdentityStore(private val fileName: String = "map_region_identity.bin") : RegionIdentityStore {

    override suspend fun load(): ByteArray? = withContext(Dispatchers.Default) {
        val path = path() ?: return@withContext null
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        val size = data.length.toInt()
        if (size == 0) return@withContext null
        ByteArray(size).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
    }

    override suspend fun save(bytes: ByteArray): Unit = withContext(Dispatchers.Default) {
        val path = checkNotNull(path()) { "Caches directory is unavailable" }
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
}
