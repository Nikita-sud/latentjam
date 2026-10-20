/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SMART's impression log in private storage ([SmartImpressionLog]); every pass over it is streamed in 64 KB chunks. */
internal class FileSmartLogStore internal constructor(private val file: File) : SmartLogStore {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    override suspend fun append(text: String): Unit = withContext(Dispatchers.IO) {
        // A torn tail (crash mid-append) is closed first, so the new lines start on a line of their own.
        val separator = if (endsInsideALine()) "\n" else ""
        file.durableAppendText(separator + text)
    }

    override suspend fun size(): Long = withContext(Dispatchers.IO) { if (file.exists()) file.length() else 0L }

    override suspend fun keepTail(maxBytes: Long): Unit = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= maxBytes) return@withContext
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        try {
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                var start = length - maxBytes
                // Start on a line boundary: after the first line feed at or past the cut.
                input.seek(start - 1)
                while (start < length && input.read() != '\n'.code) start++
                FileOutputStream(temporary).use { out ->
                    val buffer = ByteArray(CHUNK)
                    input.seek(start)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            check(temporary.renameTo(file)) { "Could not trim the SMART log" }
            syncDir(file.parentFile)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override suspend fun forEachLine(line: (String) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        // A torn last line (no line feed) is left out, as the parser would refuse it.
        val torn = endsInsideALine()
        file.bufferedReader(Charsets.UTF_8, CHUNK).useLines { lines ->
            var pending: String? = null
            for (l in lines) {
                pending?.let(line)
                pending = l
            }
            if (!torn) pending?.let(line)
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        if (file.exists() && file.delete()) syncDir(file.parentFile)
    }

    private fun endsInsideALine(): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
    }

    private companion object {
        const val FILE_NAME = "smart_impressions.log"
        const val CHUNK = 64 * 1024

        /** Best-effort directory sync after the rename (the same rule as the history log's). */
        fun syncDir(directory: File?) {
            val target = directory ?: return
            try {
                val fd = Os.open(target.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
            } catch (_: Exception) {
            }
        }
    }
}
