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
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.writeToFile
import platform.posix.memcpy

/** Atomic, app-private persistence for an iOS vector index. */
@OptIn(ExperimentalForeignApi::class)
internal class IosFileIndexStore(
    private val fileName: String,
) : IndexStore {

    override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? =
        loadSnapshot(modelVersion)?.entries

    override suspend fun loadSnapshot(modelVersion: String): StoredIndexSnapshot? =
        withContext(Dispatchers.Default) {
            val path = indexPath(fileName) ?: return@withContext null
            val bytes = readBoundedSnapshot(path) ?: return@withContext null
            runCatching { decodeSnapshot(bytes, modelVersion) }.getOrNull()
        }

    override suspend fun save(
        modelVersion: String,
        entries: Map<TrackId, FloatArray>,
    ): Unit = saveSnapshot(modelVersion, StoredIndexSnapshot(entries))

    override suspend fun saveSnapshot(
        modelVersion: String,
        snapshot: StoredIndexSnapshot,
    ): Unit = withContext(Dispatchers.Default) {
        val path = checkNotNull(indexPath(fileName)) { "Caches directory is unavailable" }
        // NSData's atomically=true writes through a sibling temporary file and rename, so a crash
        // cannot replace a valid snapshot with a half-written one.
        check(encodeIosIndexSnapshot(modelVersion, snapshot).toNSData().writeToFile(path, true)) {
            "Could not atomically replace $fileName"
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.Default) {
        val path = checkNotNull(indexPath(fileName)) { "Caches directory is unavailable" }
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(path)) {
            check(manager.removeItemAtPath(path, null)) { "Could not delete $fileName" }
        }
    }
}

/**
 * Reads a regenerable cache only after its metadata proves the two-copy NSData -> ByteArray load
 * is bounded. The second size check also turns a concurrent replacement into a cache miss rather
 * than decoding a truncated or extended snapshot.
 */
@OptIn(ExperimentalForeignApi::class)
private fun readBoundedSnapshot(path: String): ByteArray? {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return null
    val declaredSize = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return null
    if (!isLoadableIosIndexSnapshotSize(declaredSize)) return null

    val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
    var closed = false
    val data = try {
        // Read one byte beyond the accepted maximum. Even if the file grows after the metadata
        // preflight, Foundation can never allocate an unbounded NSData for this cache.
        handle.readDataUpToLength((MAX_IOS_INDEX_SNAPSHOT_BYTES + 1L).toULong(), null)
    } finally {
        closed = handle.closeAndReturnError(null)
    }
    if (!closed || data == null) return null
    val observedSize = data.length.toLong()
    if (observedSize != declaredSize || !isLoadableIosIndexSnapshotSize(observedSize)) return null
    return data.toByteArray()
}

internal fun isLoadableIosIndexSnapshotSize(
    sizeBytes: Long,
    maximumSnapshotBytes: Long = MAX_IOS_INDEX_SNAPSHOT_BYTES,
): Boolean = maximumSnapshotBytes >= 0L && sizeBytes in 0L..maximumSnapshotBytes

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
        check(manager.createDirectoryAtPath(directory, true, null, null)) {
            "Could not create SMART cache directory"
        }
    }
    return if (directory.endsWith('/')) directory + fileName else "$directory/$fileName"
}

/** A compact portable snapshot format; every integer and float is big-endian. */
internal fun encodeIosIndexSnapshot(
    modelVersion: String,
    snapshot: StoredIndexSnapshot,
    maximumSnapshotBytes: Long = MAX_IOS_INDEX_SNAPSHOT_BYTES,
): ByteArray {
    require(maximumSnapshotBytes in 1L..Int.MAX_VALUE.toLong()) {
        "Snapshot byte limit must be positive and fit in a ByteArray"
    }
    val entries = snapshot.entries
    val dim = entries.values.firstOrNull()?.size ?: 0
    require(entries.size <= MAX_ENTRIES) { "Index has too many entries: ${entries.size}" }
    require(entries.isEmpty() || dim in 1..MAX_DIMENSION) {
        "Index dimension must be in 1..$MAX_DIMENSION, got $dim"
    }
    require(entries.values.all { vector ->
        vector.size == dim && vector.all(Float::isFinite)
    }) { "Index vectors must have one finite, consistent dimension" }
    val versionBytes = modelVersion.encodeToByteArray()
    require(versionBytes.size <= MAX_STRING_BYTES) { "Model version is too large" }
    val ids = LinkedHashMap<TrackId, ByteArray>(minOf(entries.size, 1_024))
    val identityBytes = LinkedHashMap<TrackId, ByteArray?>(minOf(entries.size, 1_024))
    var total = HEADER_BYTES.toLong() + versionBytes.size.toLong()

    fun requireSupportedSize(candidate: Long) {
        require(isLoadableIosIndexSnapshotSize(candidate, maximumSnapshotBytes)) {
            "Index snapshot is too large: $candidate bytes (limit $maximumSnapshotBytes)"
        }
    }

    requireSupportedSize(total)
    val vectorBytes = dim.toLong() * Float.SIZE_BYTES.toLong()
    for (id in entries.keys) {
        val idBytes = id.value.encodeToByteArray()
        require(idBytes.size <= MAX_STRING_BYTES) { "Track id is too large" }
        val identity = snapshot.identities[id]?.encodeToByteArray()
        require(identity == null || identity.size <= MAX_STRING_BYTES) {
            "Vector identity is too large"
        }
        val rowBytes = Int.SIZE_BYTES.toLong() + idBytes.size.toLong() + vectorBytes +
            Int.SIZE_BYTES.toLong() + (identity?.size ?: 0).toLong()
        requireSupportedSize(total + rowBytes)
        total += rowBytes
        ids[id] = idBytes
        identityBytes[id] = identity
    }

    // The same envelope guards reads and writes. Check it before allocating the contiguous output
    // buffer so the app can never write a cache that it will reject on the next launch.
    val writer = SnapshotWriter(ByteArray(total.toInt()))
    writer.int(MAGIC)
    writer.int(FORMAT_VERSION)
    writer.bytesWithLength(versionBytes)
    writer.int(dim)
    writer.int(entries.size)
    for ((id, vector) in entries) {
        writer.bytesWithLength(ids.getValue(id))
        for (component in vector) writer.int(component.toBits())
        writer.nullableBytesWithLength(identityBytes.getValue(id))
    }
    return writer.result()
}

private fun decodeSnapshot(
    bytes: ByteArray,
    expectedModelVersion: String,
): StoredIndexSnapshot? {
    val reader = SnapshotReader(bytes)
    if (reader.int() != MAGIC) return null
    val formatVersion = reader.int()
    if (formatVersion != LEGACY_FORMAT_VERSION && formatVersion != FORMAT_VERSION) return null
    if (reader.bytesWithLength().decodeToString() != expectedModelVersion) return null
    val dim = reader.int()
    val count = reader.int()
    if (
        (dim !in 1..MAX_DIMENSION && !(dim == 0 && count == 0)) ||
        count !in 0..MAX_ENTRIES
    ) return null
    val result = LinkedHashMap<TrackId, FloatArray>(minOf(count, 1_024))
    val identities = LinkedHashMap<TrackId, String>(minOf(count, 1_024))
    val seen = HashSet<TrackId>(minOf(count, 1_024))
    repeat(count) {
        val id = TrackId(reader.bytesWithLength().decodeToString())
        val vector = FloatArray(dim) { Float.fromBits(reader.int()) }
        val identity = if (formatVersion == FORMAT_VERSION) {
            reader.nullableBytesWithLength()?.decodeToString()
        } else {
            null
        }
        if (!seen.add(id)) return null
        if (vector.all(Float::isFinite)) {
            result[id] = vector
            if (identity != null) identities[id] = identity
        }
    }
    if (!reader.atEnd()) return null
    return StoredIndexSnapshot(result, identities)
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

    fun nullableBytesWithLength(bytes: ByteArray?) {
        if (bytes == null) {
            int(-1)
        } else {
            bytesWithLength(bytes)
        }
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
        require(length in 0..MAX_STRING_BYTES && position <= input.size - length)
        return input.copyOfRange(position, position + length).also { position += length }
    }

    fun nullableBytesWithLength(): ByteArray? {
        val length = int()
        if (length == -1) return null
        require(length in 0..MAX_STRING_BYTES && position <= input.size - length)
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
private const val LEGACY_FORMAT_VERSION = 1
private const val FORMAT_VERSION = 2
private const val HEADER_BYTES = Int.SIZE_BYTES * 5
private const val MAX_DIMENSION = 4096
private const val MAX_ENTRIES = 1_000_000
private const val MAX_STRING_BYTES = 1_048_576

/**
 * Whole-file iOS loads briefly hold both NSData and ByteArray copies. 64 MiB still accommodates
 * roughly 17,000 960-dimensional tracks while bounding a corrupt/regenerable cache to a load a
 * mobile process can reject safely.
 */
internal const val MAX_IOS_INDEX_SNAPSHOT_BYTES: Long = 64L * 1024L * 1024L
