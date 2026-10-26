/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [RegionIdentityStore] beside [FileMapRefreshStateStore]: temp file, sync, rename. */
internal class FileRegionIdentityStore(
    private val directory: File,
    private val fileName: String = FILE_NAME,
) : RegionIdentityStore {
    private val file get() = File(directory, fileName)
    private val temp get() = File(directory, "$fileName.tmp")

    override suspend fun load(): ByteArray? = withContext(Dispatchers.IO) {
        val target = file
        if (!target.isFile || target.length() > MAX_BYTES) null else runCatching { target.readBytes() }.getOrNull()
    }

    override suspend fun save(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val staging = temp
        FileOutputStream(staging).use { stream ->
            stream.write(bytes)
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

    companion object {
        const val FILE_NAME: String = "map_region_identity.bin"
        private const val MAX_BYTES: Long = 64L * 1024 * 1024
    }
}
