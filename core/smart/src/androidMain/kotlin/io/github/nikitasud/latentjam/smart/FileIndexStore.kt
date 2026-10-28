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
 * dim, vector rows, then content-keyed failure rows); ~3.3 MB for 854 tracks at 960 dims —
 * loads in tens of milliseconds versus ~an hour of re-embedding. Writes go to
 * a temp file first and are atomically renamed, so a crash mid-save leaves
 * the previous snapshot intact. Any parse problem or version mismatch simply
 * yields `null` (the engine re-indexes).
 *
 * One envelope guards reads and writes alike (`maximumSnapshotBytes`, 64 MiB by default): a snapshot
 * the next launch would refuse is never written, and its rejection leaves the previous file untouched.
 * An index that outgrows the envelope therefore has no durable cache at all — the in-memory index stays
 * complete, so a caller that checkpoints per batch must read the refusal as "not persistable", not as a
 * failed analysis pass. ~17,000 audio rows reach the envelope at 960 dims.
 */
internal class FileIndexStore private constructor(
    private val file: File,
    private val tempFile: File,
    private val maximumSnapshotBytes: Long,
    /**
     * Vectors are kept as halves ([HalfFloat], format v4): half the file, the floats rounded to three significant
     * digits. A float file written by an earlier build is rewritten as halves on its first load, after the new file
     * was written and read back equal ([migrateToHalves]); the old file stays until then.
     */
    private val half: Boolean = false,
) : IndexStore {

    constructor(context: Context, fileName: String = FILE_NAME, half: Boolean = false) : this(
        file = File(context.filesDir, fileName),
        tempFile = File(context.filesDir, "$fileName.tmp"),
        maximumSnapshotBytes = MAX_ANDROID_INDEX_SNAPSHOT_BYTES,
        half = half,
    )

    /** File-based seam for host corruption/atomicity tests; production uses the Context overload. */
    internal constructor(
        directory: File,
        fileName: String = FILE_NAME,
        maximumSnapshotBytes: Long = MAX_ANDROID_INDEX_SNAPSHOT_BYTES,
        half: Boolean = false,
    ) : this(
        file = File(directory, fileName),
        tempFile = File(directory, "$fileName.tmp"),
        maximumSnapshotBytes = maximumSnapshotBytes,
        half = half,
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
            readOrReject {
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
            }?.also { if (half) migrateToHalves(modelVersion) }
        }

    /** This file's format version, or null when it cannot be read. */
    private fun formatVersion(): Int? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC) null else input.readInt()
        }
    }.getOrNull()

    /**
     * A half store's float file (formats 1–3) rewritten as halves, streaming (a 10,000-song index never sits in memory
     * twice): the rows go to a staged file beside the old one while a hash of every (id, halves, identity) is taken,
     * the staged file is read back and must hash the same with the same rows and failures, and only then is it renamed
     * over the old file. Any failure leaves the old file as it was; the next load tries again.
     */
    private fun migrateToHalves(modelVersion: String) {
        if (formatVersion() !in FileIndexStore.LEGACY_FORMAT_VERSION..FileIndexStore.FORMAT_VERSION) return
        val staged = File(file.parentFile, file.name + ".half")
        fun stream(source: File, row: (TrackId, FloatArray, String?) -> Unit): Map<TrackId, String>? =
            DataInputStream(source.inputStream().buffered()).use { input ->
                decodeStoredIndexRows(input, modelVersion, source.length(), maximumSnapshotBytes, row)
            }
        try {
            var count = 0
            var dim = 0
            val failed = stream(file) { _, v, _ -> count++; dim = v.size } ?: return
            val written = RowHash()
            FileOutputStream(staged).use { raw ->
                val output = DataOutputStream(raw.buffered())
                output.writeInt(MAGIC)
                output.writeInt(HALF_FORMAT_VERSION)
                output.writeSizedUtf8(modelVersion)
                output.writeInt(dim)
                output.writeInt(count)
                var rows = 0
                stream(file) { id, vector, identity ->
                    output.writeSizedUtf8(id.value)
                    val halves = ShortArray(vector.size) { HalfFloat.toHalf(vector[it]) }
                    for (h in halves) output.writeShort(h.toInt())
                    output.writeNullableSizedUtf8(identity)
                    written.add(id, halves, identity)
                    rows++
                } ?: return
                check(rows == count) { "The float file changed while it was rewritten" }
                output.writeInt(failed.size)
                for ((id, identity) in failed) {
                    output.writeSizedUtf8(id.value)
                    output.writeSizedUtf8(identity)
                }
                output.flush()
                raw.fd.sync()
            }
            val read = RowHash()
            var readRows = 0
            val readFailed = stream(staged) { id, vector, identity ->
                read.add(id, ShortArray(vector.size) { HalfFloat.toHalf(vector[it]) }, identity)
                readRows++
            }
            if (readFailed == failed && readRows == count && read.value == written.value) {
                check(staged.renameTo(file)) { "Could not replace ${file.name}" }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // The float file stays as it was.
        } finally {
            if (staged.exists()) staged.delete()
        }
    }

    /**
     * Runs a read: the file's own damage (truncated, malformed, over the limit) is null — no usable snapshot — while
     * anything else (out of memory, another I/O error) is [IndexRestoreFailure]: the file may be fine and must survive.
     * Cancellation passes through.
     */
    private inline fun <T> readOrReject(read: () -> T?): T? = try {
        read()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (damage: java.io.EOFException) {
        null
    } catch (damage: java.io.UTFDataFormatException) {
        null
    } catch (damage: java.nio.charset.CharacterCodingException) {
        null
    } catch (damage: IllegalArgumentException) {
        null
    } catch (damage: IllegalStateException) {
        null
    } catch (damage: NegativeArraySizeException) {
        null
    } catch (damage: SnapshotOverLimit) {
        null
    } catch (failure: Throwable) {
        throw IndexRestoreFailure(failure)
    }

    /**
     * [loadSnapshot] one row at a time: the same checks, but each decoded row goes straight to [row] instead of into a
     * map of the whole library. Damage found after some rows were delivered returns null (the receiver discards them);
     * a read that fails for another reason throws [IndexRestoreFailure], as [loadSnapshot] does.
     */
    override suspend fun loadRows(
        modelVersion: String,
        row: (id: TrackId, vector: FloatArray, identity: String?) -> Unit,
    ): Map<TrackId, String>? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val declaredSize = file.length()
        if (!isLoadableAndroidIndexSnapshotSize(declaredSize, maximumSnapshotBytes)) {
            return@withContext null
        }
        readOrReject {
            val bounded = CountingBoundedInputStream(
                input = file.inputStream(),
                byteLimit = maximumSnapshotBytes + 1L,
            )
            val failed = DataInputStream(bounded.buffered()).use { input ->
                decodeStoredIndexRows(
                    input = input,
                    expectedModelVersion = modelVersion,
                    encodedSizeBytes = declaredSize,
                    maximumSnapshotBytes = maximumSnapshotBytes,
                    row = row,
                )
            }
            failed?.takeIf { bounded.bytesRead == declaredSize }
        }?.also { if (half) migrateToHalves(modelVersion) }
    }

    override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>): Unit =
        saveSnapshot(modelVersion, StoredIndexSnapshot(entries))

    override suspend fun saveSnapshot(
        modelVersion: String,
        snapshot: StoredIndexSnapshot,
    ): Unit =
        withContext(Dispatchers.IO) {
            val entries = snapshot.entries
            val failedIdentities = snapshot.failedIdentities
            val dim = entries.values.firstOrNull()?.size ?: 0
            require(entries.size <= MAX_ENTRIES) { "Index has too many entries: ${entries.size}" }
            require(failedIdentities.size <= MAX_ENTRIES) {
                "Index has too many failure entries: ${failedIdentities.size}"
            }
            require(entries.size.toLong() + failedIdentities.size <= MAX_ENTRIES.toLong()) {
                "Index has too many vector and failure entries"
            }
            require(failedIdentities.keys.none(entries::containsKey)) {
                "A track cannot have both a vector and a failed identity"
            }
            require(entries.isEmpty() || dim in 1..MAX_DIMENSION) {
                "Index dimension must be in 1..$MAX_DIMENSION, got $dim"
            }
            require(entries.values.all { vector ->
                vector.size == dim && vector.all(Float::isFinite)
            }) { "Index vectors must have one finite, consistent dimension" }
            val encodedSize = encodedV3SnapshotSize(modelVersion, snapshot, dim, if (half) Short.SIZE_BYTES else Float.SIZE_BYTES)
            // The same envelope as load (see the class KDoc): a snapshot this store would refuse on the
            // next launch is refused here, before the temp file is touched, and it is a size answer
            // rather than an analysis failure.
            require(isLoadableAndroidIndexSnapshotSize(encodedSize, maximumSnapshotBytes)) {
                "Index snapshot is too large: $encodedSize bytes (limit $maximumSnapshotBytes)"
            }
            file.parentFile?.mkdirs()
            try {
                writeSnapshot(tempFile, modelVersion, snapshot)
                check(tempFile.renameTo(file)) { "Could not atomically replace ${file.name}" }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }

    /** Writes [snapshot] to [target] in this store's format (v3 floats, or v4 halves) and syncs it. */
    private fun writeSnapshot(target: File, modelVersion: String, snapshot: StoredIndexSnapshot) {
        val entries = snapshot.entries
        val dim = entries.values.firstOrNull()?.size ?: 0
        FileOutputStream(target).use { raw ->
            val output = DataOutputStream(raw.buffered())
            output.writeInt(MAGIC)
            output.writeInt(if (half) HALF_FORMAT_VERSION else FORMAT_VERSION)
            output.writeSizedUtf8(modelVersion)
            output.writeInt(dim)
            output.writeInt(entries.size)
            for ((id, vector) in entries) {
                output.writeSizedUtf8(id.value)
                if (half) {
                    for (component in vector) output.writeShort(HalfFloat.toHalf(component).toInt())
                } else {
                    for (component in vector) output.writeFloat(component)
                }
                output.writeNullableSizedUtf8(snapshot.identities[id])
            }
            output.writeInt(snapshot.failedIdentities.size)
            for ((id, identity) in snapshot.failedIdentities) {
                output.writeSizedUtf8(id.value)
                output.writeSizedUtf8(identity)
            }
            output.flush()
            raw.fd.sync()
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
        const val IDENTITY_FORMAT_VERSION = 2
        const val FORMAT_VERSION = 3
        /** Format 3 with every vector component a big-endian half ([HalfFloat]). */
        const val HALF_FORMAT_VERSION = 4
        const val MAX_DIMENSION = 4096
        const val MAX_ENTRIES = 1_000_000
        const val MAX_STRING_BYTES = 1_048_576
        const val HEADER_BYTES = Int.SIZE_BYTES * 5
    }
}

/**
 * Host tools (the offline product-check stand): reads one of this store's snapshot files without a Context, or null
 * when the file is missing, damaged or of another [modelVersion].
 */
public suspend fun readIndexSnapshotFile(file: File, modelVersion: String): StoredIndexSnapshot? =
    FileIndexStore(file.absoluteFile.parentFile, file.name, maximumSnapshotBytes = Long.MAX_VALUE - 1).loadSnapshot(modelVersion)

/** Host tools: a snapshot file's vectors as the audio index keeps them, rounded to halves (never rewrites the file). */
public suspend fun readHalfIndexSnapshotFile(file: File, modelVersion: String): StoredIndexSnapshot? =
    readIndexSnapshotFile(file, modelVersion)?.let { s ->
        StoredIndexSnapshot(s.entries.mapValuesTo(LinkedHashMap()) { (_, v) -> FloatArray(v.size) { HalfFloat.round(v[it]) } }, s.identities, s.failedIdentities)
    }

/**
 * Host tools (the offline product-check stand): the phone's similarity engine as DI builds it (in-memory audio and text
 * indexes restored from [store] / [textStore], the e cache from [energyStore]) around a host [backend].
 */
public fun createHostSimilarityEngine(
    config: SmartEngineConfig,
    backend: EmbeddingBackend,
    store: IndexStore,
    textStore: IndexStore?,
    energyStore: IndexStore?,
    artistKnowledge: io.github.nikitasud.latentjam.smart.text.ArtistKnowledge?,
    /** Keep the audio vectors as halves, as the app does from 0.9.3 on (false: the float32 index of earlier builds). */
    half: Boolean = true,
): SimilarityEngine = DefaultSimilarityEngine(
    backend = backend,
    index = InMemoryVectorIndex(config.embeddingDim, if (half) HalfRowStore(config.embeddingDim) else HeapRowStore(config.embeddingDim)),
    store = store,
    config = config,
    dispatcher = kotlinx.coroutines.Dispatchers.Default,
    artistKnowledge = artistKnowledge,
    textIndex = InMemoryVectorIndex(io.github.nikitasud.latentjam.smart.text.TextEncoder.TEXT_DIM),
    textStore = textStore,
    energyStore = energyStore,
)

/** Versioned decoder; v1 has vectors only, v2 adds identities, and v3 adds failure identities. */
internal fun decodeStoredIndexSnapshot(
    input: DataInputStream,
    expectedModelVersion: String,
    encodedSizeBytes: Long = input.available().toLong(),
    maximumSnapshotBytes: Long = MAX_ANDROID_INDEX_SNAPSHOT_BYTES,
): StoredIndexSnapshot? {
    val result = LinkedHashMap<TrackId, FloatArray>()
    val identities = LinkedHashMap<TrackId, String>()
    val failed = decodeStoredIndexRows(input, expectedModelVersion, encodedSizeBytes, maximumSnapshotBytes) { id, vector, identity ->
        result[id] = vector
        if (identity != null) identities[id] = identity
    } ?: return null
    return StoredIndexSnapshot(result, identities, failed)
}

/**
 * The streaming core of [decodeStoredIndexSnapshot]: each finite row goes to [row] as soon as it is read (a fresh
 * array per row). Returns the failed identities, or null for anything the decoder rejects — rows already delivered
 * then belong to a rejected snapshot.
 */
internal fun decodeStoredIndexRows(
    input: DataInputStream,
    expectedModelVersion: String,
    encodedSizeBytes: Long = input.available().toLong(),
    maximumSnapshotBytes: Long = MAX_ANDROID_INDEX_SNAPSHOT_BYTES,
    row: (id: TrackId, vector: FloatArray, identity: String?) -> Unit,
): Map<TrackId, String>? {
    if (!isLoadableAndroidIndexSnapshotSize(encodedSizeBytes, maximumSnapshotBytes)) return null
    if (input.readInt() != FileIndexStore.MAGIC) return null
    val formatVersion = input.readInt()
    if (formatVersion != FileIndexStore.LEGACY_FORMAT_VERSION &&
        formatVersion != FileIndexStore.IDENTITY_FORMAT_VERSION &&
        formatVersion != FileIndexStore.FORMAT_VERSION &&
        formatVersion != FileIndexStore.HALF_FORMAT_VERSION
    ) {
        return null
    }
    val halves = formatVersion == FileIndexStore.HALF_FORMAT_VERSION
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
    val minimumRowBytes = dim.toLong() * (if (halves) Short.SIZE_BYTES else Float.SIZE_BYTES).toLong() + stringOverheadBytes
    if (count.toLong() * minimumRowBytes > encodedSizeBytes) return null
    // Do not trust a corrupt header enough to preallocate a million-entry table before even one
    // complete row has been read. The map grows normally for a genuinely large valid snapshot.
    val seen = HashSet<TrackId>(minOf(count, 1_024))
    repeat(count) {
        val id = TrackId(
            if (formatVersion == FileIndexStore.LEGACY_FORMAT_VERSION) input.readUTF()
            else input.readSizedUtf8(),
        )
        val vector = if (halves) FloatArray(dim) { HalfFloat.toFloat(input.readShort()) } else FloatArray(dim) { input.readFloat() }
        val identity = if (formatVersion >= FileIndexStore.IDENTITY_FORMAT_VERSION) {
            input.readNullableSizedUtf8()
        } else {
            null
        }
        if (!seen.add(id)) return null
        if (vector.all(Float::isFinite)) row(id, vector, identity)
    }
    val failedIdentities = LinkedHashMap<TrackId, String>()
    if (formatVersion >= FileIndexStore.FORMAT_VERSION) {
        val failedCount = input.readInt()
        if (failedCount !in 0..FileIndexStore.MAX_ENTRIES || count + failedCount > FileIndexStore.MAX_ENTRIES) {
            return null
        }
        repeat(failedCount) {
            val id = TrackId(input.readSizedUtf8())
            val identity = input.readSizedUtf8()
            if (!seen.add(id)) return null
            failedIdentities[id] = identity
        }
    }
    if (input.read() != -1) return null
    return failedIdentities
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

private fun encodedV3SnapshotSize(
    modelVersion: String,
    snapshot: StoredIndexSnapshot,
    dimension: Int,
    componentBytes: Int = Float.SIZE_BYTES,
): Long {
    val modelBytes = modelVersion.encodeToByteArray()
    require(modelBytes.size <= FileIndexStore.MAX_STRING_BYTES) { "Model version is too large" }
    var total = FileIndexStore.HEADER_BYTES.toLong() + modelBytes.size.toLong() + Int.SIZE_BYTES
    val vectorBytes = dimension.toLong() * componentBytes.toLong()
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
    for ((id, identity) in snapshot.failedIdentities) {
        val idBytes = id.value.encodeToByteArray()
        val identityBytes = identity.encodeToByteArray()
        require(idBytes.size <= FileIndexStore.MAX_STRING_BYTES) { "Track id is too large" }
        require(identityBytes.size <= FileIndexStore.MAX_STRING_BYTES) {
            "Failure identity is too large"
        }
        total += 2L * Int.SIZE_BYTES.toLong() + idBytes.size + identityBytes.size
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
            throw SnapshotOverLimit()
        }
    }
}

/** The file grew past the read limit while being read: damage (a replaced or growing file), not a failed read. */
internal class SnapshotOverLimit : IOException("Index snapshot exceeded the bounded read limit")

/** A 64-bit hash of a sequence of index rows (id, halves, identity): the migration's read-back check. */
private class RowHash {
    var value: Long = -0x340d631b7bdddcdbL
        private set

    private fun mix(x: Long) {
        value = (value xor x) * 0x100000001b3L
    }

    fun add(id: TrackId, halves: ShortArray, identity: String?) {
        for (c in id.value) mix(c.code.toLong())
        mix(-1L)
        for (h in halves) mix(h.toLong())
        if (identity == null) mix(-2L) else for (c in identity) mix(c.code.toLong())
        mix(-3L)
    }
}
