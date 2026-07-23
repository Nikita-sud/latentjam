/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataToEndOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/** Atomic, app-private persistence for an iOS vector index. */
@OptIn(ExperimentalForeignApi::class)
internal class IosFileIndexStore(
    private val fileName: String,
) : IndexStore {

    override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? =
        withContext(Dispatchers.Default) {
            val path = indexPath(fileName) ?: return@withContext null
            val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return@withContext null
            val data = handle.readDataToEndOfFile()
            handle.closeFile()
            runCatching { decodeSnapshot(data.toByteArray(), modelVersion) }.getOrNull()
        }

    override suspend fun save(
        modelVersion: String,
        entries: Map<TrackId, FloatArray>,
    ): Unit = withContext(Dispatchers.Default) {
        val path = indexPath(fileName) ?: return@withContext
        runCatching {
            // NSData's atomically=true writes through a sibling temporary file and rename, so a
            // crash cannot replace a valid snapshot with a half-written one.
            encodeSnapshot(modelVersion, entries).toNSData().writeToFile(path, true)
        }
        Unit
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.Default) {
        val path = indexPath(fileName) ?: return@withContext
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        Unit
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun indexPath(fileName: String): String? {
    val directory = NSSearchPathForDirectoriesInDomains(
        // The index is fully regenerable, can be large, and contains listening-derived vectors.
        // Caches is excluded from iCloud/device backup by the OS and may safely be purged.
        NSCachesDirectory,
        NSUserDomainMask,
        true,
    ).firstOrNull() as? String ?: return null
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(directory)) {
        manager.createDirectoryAtPath(directory, true, null, null)
    }
    return if (directory.endsWith('/')) directory + fileName else "$directory/$fileName"
}

/** A compact portable snapshot format; every integer and float is big-endian. */
private fun encodeSnapshot(
    modelVersion: String,
    entries: Map<TrackId, FloatArray>,
): ByteArray {
    val dim = entries.values.firstOrNull()?.size ?: 0
    val consistent = entries.filterValues { it.size == dim }
    val versionBytes = modelVersion.encodeToByteArray()
    val ids = consistent.keys.associateWith { it.value.encodeToByteArray() }
    val total = HEADER_BYTES.toLong() + versionBytes.size + consistent.entries.sumOf { (id, _) ->
        Int.SIZE_BYTES.toLong() + ids.getValue(id).size + dim.toLong() * Float.SIZE_BYTES
    }
    require(total <= Int.MAX_VALUE) { "Index snapshot is too large" }
    val writer = SnapshotWriter(ByteArray(total.toInt()))
    writer.int(MAGIC)
    writer.int(FORMAT_VERSION)
    writer.bytesWithLength(versionBytes)
    writer.int(dim)
    writer.int(consistent.size)
    for ((id, vector) in consistent) {
        writer.bytesWithLength(ids.getValue(id))
        for (component in vector) writer.int(component.toBits())
    }
    return writer.result()
}

private fun decodeSnapshot(
    bytes: ByteArray,
    expectedModelVersion: String,
): Map<TrackId, FloatArray>? {
    val reader = SnapshotReader(bytes)
    if (reader.int() != MAGIC) return null
    if (reader.int() != FORMAT_VERSION) return null
    if (reader.bytesWithLength().decodeToString() != expectedModelVersion) return null
    val dim = reader.int()
    val count = reader.int()
    if (dim !in 1..MAX_DIMENSION || count !in 0..MAX_ENTRIES) return null
    val result = LinkedHashMap<TrackId, FloatArray>(count)
    repeat(count) {
        val id = TrackId(reader.bytesWithLength().decodeToString())
        val vector = FloatArray(dim) { Float.fromBits(reader.int()) }
        if (vector.all(Float::isFinite)) result[id] = vector
    }
    if (!reader.atEnd()) return null
    return result
}

private class SnapshotWriter(private val output: ByteArray) {
    private var position = 0

    fun int(value: Int) {
        require(position <= output.size - Int.SIZE_BYTES)
        output[position++] = (value ushr 24).toByte()
        output[position++] = (value ushr 16).toByte()
        output[position++] = (value ushr 8).toByte()
        output[position++] = value.toByte()
    }

    fun bytesWithLength(bytes: ByteArray) {
        int(bytes.size)
        require(position <= output.size - bytes.size)
        bytes.copyInto(output, position)
        position += bytes.size
    }

    fun result(): ByteArray {
        check(position == output.size)
        return output
    }
}

private class SnapshotReader(private val input: ByteArray) {
    private var position = 0

    fun int(): Int {
        require(position <= input.size - Int.SIZE_BYTES)
        return ((input[position++].toInt() and 0xff) shl 24) or
            ((input[position++].toInt() and 0xff) shl 16) or
            ((input[position++].toInt() and 0xff) shl 8) or
            (input[position++].toInt() and 0xff)
    }

    fun bytesWithLength(): ByteArray {
        val length = int()
        require(length >= 0 && position <= input.size - length)
        return input.copyOfRange(position, position + length).also { position += length }
    }

    fun atEnd(): Boolean = position == input.size
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val output = ByteArray(size)
    output.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return output
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
}

private const val MAGIC = 0x4C4A4958 // "LJIX"
private const val FORMAT_VERSION = 1
private const val HEADER_BYTES = Int.SIZE_BYTES * 5
private const val MAX_DIMENSION = 4096
private const val MAX_ENTRIES = 1_000_000
