/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
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

    /**
     * Null only when the file is certainly not there. `File.isFile` also answers false when the stat
     * itself fails, and a journal read as absent would let a sweep delete the saved bytes it names.
     * The probe opens read-only because "rw" would create a missing file. Between the probe and the
     * open only this store's own writers could delete the name, and they run under the store lock.
     */
    override fun open(name: String): TargetFile? {
        val file = file(name)
        try {
            RandomAccessFile(file, "r").close()
        } catch (failure: FileNotFoundException) {
            if (failure.isNoSuchFile()) return null
            throw failure
        }
        return ChannelTargetFile.open(file)
    }

    /** Android says "open failed: ENOENT (No such file or directory)", a desktop JVM "(No such file or directory)". */
    private fun FileNotFoundException.isNoSuchFile(): Boolean =
        message?.let { "ENOENT" in it || "No such file or directory" in it } == true

    /**
     * Throws when the file is still there afterwards. A delete that failed silently could remove a
     * save's saved bytes after its record failed to go, leaving a record that can never be finished.
     */
    override fun delete(name: String) {
        val file = file(name)
        if (!file.delete() && file.exists()) throw IOException("cannot delete $file")
    }

    /**
     * Only files this store could have made; anything else in the directory (`.nfs*`, `.DS_Store`)
     * is not ours. Empty only when there is no store yet. `File.list()` answers null for any error
     * (EMFILE, ENOMEM, EIO), and a failed listing read as empty would make every open save look
     * finished: a sweep would delete its only way back.
     */
    override fun names(): List<String> {
        val names = root.list() ?: if (root.exists()) throw IOException("cannot list $root") else return emptyList()
        return names.filter(NAME::matches).sorted()
    }

    override fun sync() {
        root.mkdirs()
        syncDirectory(root)
    }

    /**
     * Free space on the store's volume. `File.usableSpace` answers 0 both for a volume with nothing
     * left and for a stat that failed — it swallows the error — and a failed stat read as "no space"
     * refused every save on a disk that had room. `totalSpace` comes from the same stat, so a zero
     * total means the answer is unknown, not zero: that is passed on as room to spare, and a volume
     * that really is full still fails at the write, where an ENOSPC becomes a [StorageFullException]
     * and the save is refused with the track left whole.
     */
    override fun freeBytes(): Long {
        root.mkdirs()
        val usable = root.usableSpace
        if (usable == 0L && root.totalSpace == 0L) return Long.MAX_VALUE
        return usable
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")
    }
}
