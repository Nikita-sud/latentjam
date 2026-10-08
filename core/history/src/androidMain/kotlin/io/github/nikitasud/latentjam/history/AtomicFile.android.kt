/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

/**
 * Writes and syncs a sibling first, then atomically replaces the live private file.
 *
 * The directory entry left behind by the replacement is synced as well: history, favourites, recent
 * searches and SMART exclusions exist nowhere but in these files, so a rename still sitting in a
 * cache when the device loses power would silently roll the newest state back to the old one.
 */
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
 * Appends one already-framed record and asks the filesystem to make it durable before returning.
 *
 * An append to a log that does not exist yet also adds a directory entry, and that entry is only
 * durable once its directory is synced; the first record of a fresh log therefore ends with the
 * same sync. Later appends reuse an entry that is already on storage and skip it.
 */
internal fun File.durableAppendText(contents: String) {
    parentFile?.mkdirs()
    val created = !exists()
    FileOutputStream(this, true).use { stream ->
        val writer = stream.writer(Charsets.UTF_8)
        writer.write(contents)
        writer.flush()
        stream.fd.sync()
    }
    // A record whose own file entry never reached storage is lost whole after a power cut, not just
    // torn at the tail, so the whole newest listening would disappear rather than its last bytes.
    if (created) syncDirectory(parentFile)
}

/**
 * fsync of the directory itself, so the replacement or the new entry above survives a power cut
 * (API 21+).
 *
 * Best effort on purpose: the file has already been replaced or appended to, and no private index
 * is worth failing a save over, so a filesystem that refuses to open or sync a directory leaves the
 * write as it is.
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
        // Nothing left to do: the write itself already happened.
    }
}
