/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

/** Writes and syncs a sibling first, then atomically replaces the live private file. */
internal fun File.atomicReplaceText(contents: String) {
    parentFile?.mkdirs()
    val temporary = File(parentFile, ".$name.tmp")
    try {
        FileOutputStream(temporary).use { stream ->
            val writer = stream.writer(Charsets.UTF_8)
            writer.write(contents)
            writer.flush()
            stream.fd.sync()
        }
        check(temporary.renameTo(this)) { "Could not atomically replace $name" }
        // The temporary's own bytes are on storage, but the directory entry that now names them is
        // not: after a power cut the rename could be lost and the old file read back instead.
        syncDirectory(parentFile)
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}

/**
 * fsync of the directory itself, so the rename above survives a power cut (API 21+).
 *
 * Best effort on purpose: the file has already been replaced, and no private index is worth failing
 * a save over, so a filesystem that refuses to open or sync a directory leaves the replacement as
 * it is.
 */
private fun syncDirectory(directory: File?) {
    val target = directory ?: return
    try {
        val descriptor = Os.open(target.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    } catch (_: Exception) {
        // Nothing left to do: the replacement itself already happened.
    }
}
