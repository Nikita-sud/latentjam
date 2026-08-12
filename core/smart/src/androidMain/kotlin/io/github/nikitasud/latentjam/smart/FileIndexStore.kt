/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
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
internal class FileIndexStore private constructor(
    private val file: File,
    private val tempFile: File,
    private val maximumSnapshotBytes: Long,
) : IndexStore {

    constructor(context: Context, fileName: String = FILE_NAME) : this(
        file = File(context.filesDir, fileName),
        tempFile = File(context.filesDir, "$fileName.tmp"),
        maximumSnapshotBytes = MAX_ANDROID_INDEX_SNAPSHOT_BYTES,
    )

    /** File-based seam for host corruption/atomicity tests; production uses the Context overload. */
    internal constructor(
        directory: File,
        fileName: String = FILE_NAME,
        maximumSnapshotBytes: Long = MAX_ANDROID_INDEX_SNAPSHOT_BYTES,
    ) : this(
        file = File(directory, fileName),
        tempFile = File(directory, "$fileName.tmp"),
        maximumSnapshotBytes = maximumSnapshotBytes,
    )

    init {
        require(maximumSnapshotBytes in 1 until Long.MAX_VALUE) {
            "Snapshot byte limit must be positive and leave room for a guard byte"
        }
    }

    override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? =
        loadSnapshot(modelVersion)?.entries

    override suspend fun loadSnapshot(modelVersion: String): StoredIndexSnapshot? =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            val declaredSize = file.length()
            if (!isLoadableAndroidIndexSnapshotSize(declaredSize, maximumSnapshotBytes)) {
                return@withContext null
            }
            runCatching {
                // Read at most one byte beyond the configured limit. This closes the TOCTOU gap
                // between the length preflight and opening a file that may have been replaced or
                // extended in the meantime, without ever materializing the whole snapshot.
                val bounded = CountingBoundedInputStream(
                    input = file.inputStream(),
                    byteLimit = maximumSnapshotBytes + 1L,
                )
                val decoded = DataInputStream(bounded.buffered()).use { input ->
                    decodeStoredIndexSnapshot(
                        input = input,
                        expectedModelVersion = modelVersion,
                        encodedSizeBytes = declaredSize,
                        maximumSnapshotBytes = maximumSnapshotBytes,
                    )
                }
                decoded?.takeIf { bounded.bytesRead == declaredSize }
            }.getOrNull()
        }

    override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>): Unit =
        saveSnapshot(modelVersion, StoredIndexSnapshot(entries))

    override suspend fun saveSnapshot(
        modelVersion: String,
        snapshot: StoredIndexSnapshot,
    ): Unit =
        withContext(Dispatchers.IO) {
            val entries = snapshot.entries
            val dim = entries.values.firstOrNull()?.size ?: 0
            require(entries.size <= MAX_ENTRIES) { "Index has too many entries: ${entries.size}" }
            require(entries.isEmpty() || dim in 1..MAX_DIMENSION) {
                "Index dimension must be in 1..$MAX_DIMENSION, got $dim"
            }
            require(entries.values.all { vector ->
                vector.size == dim && vector.all(Float::isFinite)
            }) { "Index vectors must have one finite, consistent dimension" }
            val encodedSize = encodedV2SnapshotSize(modelVersion, snapshot, dim)
            require(isLoadableAndroidIndexSnapshotSize(encodedSize, maximumSnapshotBytes)) {
                "Index snapshot is too large: $encodedSize bytes (limit $maximumSnapshotBytes)"
            }
            file.parentFile?.mkdirs()
            try {
                FileOutputStream(tempFile).use { raw ->
                    val output = DataOutputStream(raw.buffered())
                    output.writeInt(MAGIC)
                    output.writeInt(FORMAT_VERSION)
                    output.writeSizedUtf8(modelVersion)
                    output.writeInt(dim)
                    output.writeInt(entries.size)
                    for ((id, vector) in entries) {
                        output.writeSizedUtf8(id.value)
                        for (component in vector) output.writeFloat(component)
                        output.writeNullableSizedUtf8(snapshot.identities[id])
                    }
                    output.flush()
                    raw.fd.sync()
                }
                check(tempFile.renameTo(file)) { "Could not atomically replace ${file.name}" }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        check(!file.exists() || file.delete()) { "Could not delete ${file.name}" }
        check(!tempFile.exists() || tempFile.delete()) { "Could not delete ${tempFile.name}" }
    }

    internal companion object {
        const val FILE_NAME = "smart_index.bin"
        const val MAGIC = 0x4C4A4958 // "LJIX"
        const val LEGACY_FORMAT_VERSION = 1
        const val FORMAT_VERSION = 2
        const val MAX_DIMENSION = 4096
        const val MAX_ENTRIES = 1_000_000
        const val MAX_STRING_BYTES = 1_048_576
        const val HEADER_BYTES = Int.SIZE_BYTES * 5
    }
}

/** Strict bounded decoder shared with Android host corruption tests. */
internal fun decodeIndexSnapshot(
    input: DataInputStream,
    expectedModelVersion: String,
    encodedSizeBytes: Long = input.available().toLong(),
): Map<TrackId, FloatArray>? = decodeStoredIndexSnapshot(
    input = input,
    expectedModelVersion = expectedModelVersion,
    encodedSizeBytes = encodedSizeBytes,
)?.entries

/** Versioned decoder; v1 rows deliberately return no descriptor identities. */
internal fun decodeStoredIndexSnapshot(
    input: DataInputStream,
    expectedModelVersion: String,
    encodedSizeBytes: Long = input.available().toLong(),
    maximumSnapshotBytes: Long = MAX_ANDROID_INDEX_SNAPSHOT_BYTES,
): StoredIndexSnapshot? {
    if (!isLoadableAndroidIndexSnapshotSize(encodedSizeBytes, maximumSnapshotBytes)) return null
    if (input.readInt() != FileIndexStore.MAGIC) return null
    val formatVersion = input.readInt()
    if (formatVersion != FileIndexStore.LEGACY_FORMAT_VERSION &&
        formatVersion != FileIndexStore.FORMAT_VERSION
    ) {
        return null
    }
    val modelVersion = if (formatVersion == FileIndexStore.LEGACY_FORMAT_VERSION) {
        input.readUTF()
    } else {
        input.readSizedUtf8()
    }
    if (modelVersion != expectedModelVersion) return null
    val dim = input.readInt()
    val count = input.readInt()
    if (
        (dim !in 1..FileIndexStore.MAX_DIMENSION && !(dim == 0 && count == 0)) ||
        count !in 0..FileIndexStore.MAX_ENTRIES
    ) {
        return null
    }
    val stringOverheadBytes = if (formatVersion == FileIndexStore.LEGACY_FORMAT_VERSION) {
        java.lang.Short.BYTES.toLong()
    } else {
        2L * Int.SIZE_BYTES.toLong()
    }
    val minimumRowBytes = dim.toLong() * Float.SIZE_BYTES.toLong() + stringOverheadBytes
    if (count.toLong() * minimumRowBytes > encodedSizeBytes) return null
    // Do not trust a corrupt header enough to preallocate a million-entry table before even one
    // complete row has been read. The map grows normally for a genuinely large valid snapshot.
    val result = LinkedHashMap<TrackId, FloatArray>(minOf(count, 1_024))
    val identities = LinkedHashMap<TrackId, String>(minOf(count, 1_024))
    val seen = HashSet<TrackId>(minOf(count, 1_024))
    repeat(count) {
        val id = TrackId(
            if (formatVersion == FileIndexStore.LEGACY_FORMAT_VERSION) input.readUTF()
            else input.readSizedUtf8(),
        )
        val vector = FloatArray(dim) { input.readFloat() }
        val identity = if (formatVersion == FileIndexStore.FORMAT_VERSION) {
            input.readNullableSizedUtf8()
        } else {
            null
        }
        if (!seen.add(id)) return null
        if (vector.all(Float::isFinite)) {
            result[id] = vector
            if (identity != null) identities[id] = identity
        }
    }
    if (input.read() != -1) return null
    return StoredIndexSnapshot(result, identities)
}

private fun DataOutputStream.writeSizedUtf8(value: String) {
    val bytes = value.encodeToByteArray()
    require(bytes.size <= FileIndexStore.MAX_STRING_BYTES) { "Index string is too large" }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.writeNullableSizedUtf8(value: String?) {
    if (value == null) {
        writeInt(-1)
    } else {
        writeSizedUtf8(value)
    }
}

private fun DataInputStream.readSizedUtf8(): String {
    val size = readInt()
    require(size in 0..FileIndexStore.MAX_STRING_BYTES) { "Invalid index string size: $size" }
    return ByteArray(size).also(::readFully).decodeToString()
}

private fun DataInputStream.readNullableSizedUtf8(): String? {
    val size = readInt()
    if (size == -1) return null
    require(size in 0..FileIndexStore.MAX_STRING_BYTES) { "Invalid index string size: $size" }
    return ByteArray(size).also(::readFully).decodeToString()
}

/** Android uses the same 64 MiB durable-cache envelope as iOS. */
internal const val MAX_ANDROID_INDEX_SNAPSHOT_BYTES: Long = 64L * 1024L * 1024L

internal fun isLoadableAndroidIndexSnapshotSize(
    sizeBytes: Long,
    maximumSnapshotBytes: Long = MAX_ANDROID_INDEX_SNAPSHOT_BYTES,
): Boolean = sizeBytes in 0L..maximumSnapshotBytes

private fun encodedV2SnapshotSize(
    modelVersion: String,
    snapshot: StoredIndexSnapshot,
    dimension: Int,
): Long {
    val modelBytes = modelVersion.encodeToByteArray()
    require(modelBytes.size <= FileIndexStore.MAX_STRING_BYTES) { "Model version is too large" }
    var total = FileIndexStore.HEADER_BYTES.toLong() + modelBytes.size.toLong()
    val vectorBytes = dimension.toLong() * Float.SIZE_BYTES.toLong()
    for (id in snapshot.entries.keys) {
        val idBytes = id.value.encodeToByteArray()
        require(idBytes.size <= FileIndexStore.MAX_STRING_BYTES) { "Track id is too large" }
        val identityBytes = snapshot.identities[id]?.encodeToByteArray()
        require(identityBytes == null || identityBytes.size <= FileIndexStore.MAX_STRING_BYTES) {
            "Vector identity is too large"
        }
        total += Int.SIZE_BYTES.toLong() + idBytes.size.toLong() + vectorBytes +
            Int.SIZE_BYTES.toLong() + (identityBytes?.size ?: 0).toLong()
    }
    return total
}

/** Counts bytes while enforcing a hard stream ceiling, including concurrent file growth. */
private class CountingBoundedInputStream(
    input: InputStream,
    private val byteLimit: Long,
) : FilterInputStream(input) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        ensureWithinLimit()
        return super.read().also { value ->
            if (value >= 0) bytesRead++
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        ensureWithinLimit()
        val allowed = minOf(length.toLong(), byteLimit - bytesRead).toInt()
        return super.read(buffer, offset, allowed).also { count ->
            if (count > 0) bytesRead += count
        }
    }

    private fun ensureWithinLimit() {
        if (bytesRead >= byteLimit) {
            throw IOException("Index snapshot exceeded the bounded read limit")
        }
    }
}
