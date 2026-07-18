/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File-backed [IndexStore] in the app's private files directory.
 *
 * Simple length-prefixed binary format (magic, format version, model version,
 * dim, count, then id/vector pairs); ~3.3 MB for 854 tracks at 960 dims —
 * loads in tens of milliseconds versus ~an hour of re-embedding. Writes go to
 * a temp file first and are atomically renamed, so a crash mid-save leaves
 * the previous snapshot intact. Any parse problem or version mismatch simply
 * yields `null` (the engine re-indexes).
 */
internal class FileIndexStore(context: Context) : IndexStore {

    private val file = File(context.filesDir, FILE_NAME)
    private val tempFile = File(context.filesDir, "$FILE_NAME.tmp")

    override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            runCatching {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    if (input.readInt() != MAGIC) return@use null
                    if (input.readInt() != FORMAT_VERSION) return@use null
                    if (input.readUTF() != modelVersion) return@use null
                    val dim = input.readInt()
                    val count = input.readInt()
                    if (dim <= 0 || count < 0) return@use null
                    buildMap<TrackId, FloatArray>(count) {
                        repeat(count) {
                            val id = TrackId(input.readUTF())
                            val vector = FloatArray(dim) { input.readFloat() }
                            put(id, vector)
                        }
                    }
                }
            }.getOrNull()
        }

    override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>): Unit =
        withContext(Dispatchers.IO) {
            val dim = entries.values.firstOrNull()?.size ?: 0
            val consistent = entries.filterValues { it.size == dim }
            runCatching {
                DataOutputStream(tempFile.outputStream().buffered()).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeUTF(modelVersion)
                    output.writeInt(dim)
                    output.writeInt(consistent.size)
                    for ((id, vector) in consistent) {
                        output.writeUTF(id.value)
                        for (component in vector) output.writeFloat(component)
                    }
                }
                if (!tempFile.renameTo(file)) {
                    tempFile.copyTo(file, overwrite = true)
                    tempFile.delete()
                }
            }
        }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        file.delete()
        tempFile.delete()
    }

    private companion object {
        const val FILE_NAME = "smart_index.bin"
        const val MAGIC = 0x4C4A4958 // "LJIX"
        const val FORMAT_VERSION = 1
    }
}
