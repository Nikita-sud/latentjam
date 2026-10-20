/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fgetc
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.fsync
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.rename

/**
 * SMART's impression log in Application Support ([SmartImpressionLog]), excluded from backups like the listening log.
 * Reads and the trim stream through stdio in 64 KB chunks; appends go through [durableAppend].
 */
@OptIn(ExperimentalForeignApi::class)
internal class FileSmartLogStore internal constructor(private val pathOf: () -> String?) : SmartLogStore {

    constructor() : this({ appSupportFile(FILE_NAME) })

    private fun path(): String = pathOf() ?: error("Application Support is unavailable")

    override suspend fun append(text: String): Unit = withContext(Dispatchers.Default) {
        val p = path()
        val separator = if (endsInsideALine(p)) "\n" else ""
        durableAppend(p, separator + text, what = "SMART log")
    }

    override suspend fun size(): Long = withContext(Dispatchers.Default) { sizeOf(path()) }

    override suspend fun keepTail(maxBytes: Long): Unit = withContext(Dispatchers.Default) {
        val p = path()
        val temporary = "$p.tmp"
        val input = fopen(p, "rb") ?: return@withContext
        try {
            fseek(input, 0, SEEK_END)
            val length = ftell(input)
            if (length <= maxBytes) return@withContext
            var start = length - maxBytes
            // Start on a line boundary: after the first line feed at or past the cut.
            fseek(input, start - 1, SEEK_SET)
            while (start < length && fgetc(input) != '\n'.code) start++
            fseek(input, start, SEEK_SET)
            val output = fopen(temporary, "wb") ?: error("Could not trim the SMART log")
            try {
                val buffer = ByteArray(CHUNK)
                buffer.usePinned { pinned ->
                    while (true) {
                        val n = fread(pinned.addressOf(0), 1u, CHUNK.toULong(), input)
                        if (n == 0uL) break
                        check(fwrite(pinned.addressOf(0), 1u, n, output) == n) { "Could not trim the SMART log" }
                    }
                }
                check(fflush(output) == 0) { "Could not trim the SMART log" }
                fsync(fileno(output))
            } finally {
                fclose(output)
            }
        } finally {
            fclose(input)
        }
        if (rename(temporary, p) != 0) {
            remove(temporary)
            error("Could not trim the SMART log")
        }
    }

    override suspend fun forEachLine(line: (String) -> Unit): Unit = withContext(Dispatchers.Default) {
        val input = fopen(path(), "rb") ?: return@withContext
        try {
            val buffer = ByteArray(CHUNK)
            var carry = ByteArray(0)
            buffer.usePinned { pinned ->
                while (true) {
                    val n = fread(pinned.addressOf(0), 1u, CHUNK.toULong(), input).toInt()
                    if (n == 0) break
                    var from = 0
                    for (i in 0 until n) {
                        if (buffer[i] != NEWLINE) continue
                        line((carry + buffer.copyOfRange(from, i)).decodeToString())
                        carry = ByteArray(0)
                        from = i + 1
                    }
                    carry += buffer.copyOfRange(from, n)
                }
            }
            // A torn last line (no line feed) is left out, as the parser would refuse it.
        } finally {
            fclose(input)
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.Default) {
        remove(path())
        Unit
    }

    private fun sizeOf(p: String): Long {
        val f = fopen(p, "rb") ?: return 0L
        try {
            fseek(f, 0, SEEK_END)
            return ftell(f)
        } finally {
            fclose(f)
        }
    }

    private fun endsInsideALine(p: String): Boolean {
        val f = fopen(p, "rb") ?: return false
        try {
            fseek(f, 0, SEEK_END)
            if (ftell(f) == 0L) return false
            fseek(f, -1, SEEK_END)
            return fgetc(f) != '\n'.code
        } finally {
            fclose(f)
        }
    }

    private companion object {
        const val FILE_NAME = "smart_impressions.log"
        const val CHUNK = 64 * 1024
        const val NEWLINE: Byte = 10
    }
}
