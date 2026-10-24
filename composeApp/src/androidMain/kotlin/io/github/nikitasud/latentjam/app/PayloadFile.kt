/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import android.system.Os
import android.system.OsConstants
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * A rebuildable payload with a line per song (the tag and loudness caches), kept in a file of its
 * own: in SharedPreferences a 10,000-song genre cache was a 1.2 MB string held for the life of the
 * process, and every unrelated setting's apply() serialized it into the XML again.
 *
 * It takes over its old SharedPreferences value ([readLegacy]) the first time it is used. Until
 * that move succeeds (storage full, a rename refused) the old value is kept and read in its place,
 * so a failed move never fails a read or loses the payload; the next launch tries again. A file
 * that cannot be read reads as no payload: the caller rebuilds it, which is what a cache is for.
 */
internal class PayloadFile(
    private val file: File,
    private val readLegacy: () -> String?,
    private val removeLegacy: () -> Unit,
) {
    private var migrated = false

    /** The legacy payload while the move has not succeeded; null once it has, or never existed. */
    private var pending: String? = null

    @Synchronized
    fun readText(): String? {
        migrate()
        pending?.let { return it }
        return try {
            if (file.exists()) file.readText() else null
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    fun <T> readLines(read: (Sequence<String>) -> T): T? {
        migrate()
        pending?.let { return read(it.lineSequence()) }
        if (!file.exists()) return null
        val reader = try {
            file.bufferedReader()
        } catch (_: IOException) {
            return null
        }
        return try {
            reader.use { read(it.lineSequence()) }
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    fun write(contents: (Writer) -> Unit) {
        migrate()
        replace(contents)
        // A new payload supersedes the legacy one whether or not the move itself succeeded.
        if (pending != null) {
            pending = null
            removeLegacy()
        }
    }

    private fun replace(contents: (Writer) -> Unit) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 16 * 1024)
                contents(writer)
                writer.flush()
                stream.fd.sync()
            }
            check(temporary.renameTo(file)) { "Could not replace ${file.name}" }
            // The bytes are on storage but the directory entry naming them is not: after a power cut
            // the rename could be lost. Best effort, as the replacement already happened.
            file.parentFile?.let { directory ->
                runCatching {
                    val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
                    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun migrate() {
        if (migrated) return
        migrated = true
        val legacy = readLegacy() ?: return
        if (!file.exists()) {
            try {
                replace { it.write(legacy) }
            } catch (_: Exception) {
                pending = legacy
                return
            }
        }
        removeLegacy()
    }
}
