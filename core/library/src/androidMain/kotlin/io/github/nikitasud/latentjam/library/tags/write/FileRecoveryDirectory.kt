/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import java.io.File
import java.io.RandomAccessFile

/**
 * The recovery store as a plain directory. [syncDirectory] makes entries durable: Android passes an
 * `Os.open` + `Os.fsync` of the directory (java.nio.file is not available below API 26); host tests
 * pass a no-op.
 *
 * Files are opened through [ChannelTargetFile], so saves must not run under thread interruption
 * (`runInterruptible`, `shutdownNow`): a `FileChannel` closes itself when its thread is interrupted.
 */
public class FileRecoveryDirectory(
    private val root: File,
    private val syncDirectory: (File) -> Unit,
) : RecoveryDirectory {

    private fun file(name: String): File {
        require(NAME.matches(name)) { "unsafe store name $name" }
        root.mkdirs()
        return File(root, name)
    }

    override fun create(name: String): TargetFile {
        val file = file(name)
        RandomAccessFile(file, "rw").use { it.setLength(0) }
        return ChannelTargetFile.open(file)
    }

    override fun open(name: String): TargetFile? = file(name).takeIf { it.isFile }?.let { ChannelTargetFile.open(it) }

    override fun delete(name: String) {
        file(name).delete()
    }

    /** Only files this store could have made; anything else in the directory (`.nfs*`, `.DS_Store`) is not ours. */
    override fun names(): List<String> = root.list()?.filter(NAME::matches)?.sorted().orEmpty()

    override fun sync() {
        root.mkdirs()
        syncDirectory(root)
    }

    override fun freeBytes(): Long {
        root.mkdirs()
        return root.usableSpace
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")
    }
}
