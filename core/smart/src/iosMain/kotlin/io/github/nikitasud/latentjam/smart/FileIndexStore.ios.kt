/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** Atomic, app-private persistence for an iOS vector index. */
@OptIn(ExperimentalForeignApi::class)
internal class IosFileIndexStore(
    private val fileName: String,
    /**
     * Vectors are kept as halves ([HalfFloat], format v4). A float file of an earlier build is rewritten on its first
     * load: the halves are staged beside it, read back and compared, and only then renamed over it.
     */
    private val half: Boolean = false,
) : IndexStore {

    override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? =
        loadSnapshot(modelVersion)?.entries

    override suspend fun loadSnapshot(modelVersion: String): StoredIndexSnapshot? =
        withContext(Dispatchers.Default) {
            val path = indexPath(fileName) ?: return@withContext null
            val bytes = readBoundedSnapshot(path) ?: return@withContext null
            val snapshot = runCatching { decodeIosIndexSnapshot(bytes, modelVersion) }.getOrNull() ?: return@withContext null
            if (half && snapshotFormatVersion(bytes) != HALF_FORMAT_VERSION) migrateToHalves(path, modelVersion, snapshot)
            snapshot
        }

    /**
     * Rewrites a float snapshot as halves without ever losing it: the half file is staged at `<path>.half`, read back
     * and decoded, compared with the halves of [loaded], and renamed over [path] (an atomic replace) only when equal.
     */
    private fun migrateToHalves(path: String, modelVersion: String, loaded: StoredIndexSnapshot) {
        val staged = "$path.half"
        val manager = NSFileManager.defaultManager
        try {
            val encoded = encodeIosIndexSnapshot(modelVersion, loaded, half = true)
            if (runCatching { writeFileSynced(staged) { sink -> sink(encoded, encoded.size) } }.isFailure) return
            val back = readBoundedSnapshot(staged) ?: return
            if (!back.contentEquals(encoded)) return
            val check = decodeIosIndexSnapshot(back, modelVersion) ?: return
            val same = check.entries.size == loaded.entries.size && check.identities == loaded.identities &&
                check.failedIdentities == loaded.failedIdentities &&
                loaded.entries.all { (id, v) ->
                    val got = check.entries[id] ?: return@all false
                    got.size == v.size && v.indices.all { got[it] == HalfFloat.round(v[it]) }
                }
            if (same && platform.posix.rename(staged, path) == 0) syncParentDirectory(path)
        } catch (failure: Throwable) {
            // The float file stays; the next load tries again.
        } finally {
            if (manager.fileExistsAtPath(staged)) manager.removeItemAtPath(staged, null)
        }
    }

    override suspend fun save(
        modelVersion: String,
        entries: Map<TrackId, FloatArray>,
    ): Unit = saveSnapshot(modelVersion, StoredIndexSnapshot(entries))

    override suspend fun saveSnapshot(
        modelVersion: String,
        snapshot: StoredIndexSnapshot,
    ): Unit = withContext(Dispatchers.Default) {
        val path = checkNotNull(indexPath(fileName)) { "Index directory is unavailable" }
        // Through a sibling temporary file, a full sync and a rename, so a crash or power loss cannot
        // replace a valid snapshot with a half-written one; streamed, so the snapshot is never held twice.
        writeDurably(path) { open -> writeIosIndexSnapshot(modelVersion, snapshot, half = half, open = open) }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.Default) {
        val path = checkNotNull(indexPath(fileName)) { "Index directory is unavailable" }
        // The Caches copy a move left behind goes too: a downgrade after a clear must not bring the old vectors back.
        for (copy in listOfNotNull(path, cachesTwin(fileName)?.takeIf { it != path })) {
            val manager = NSFileManager.defaultManager
            if (manager.fileExistsAtPath(copy)) {
                check(manager.removeItemAtPath(copy, null)) { "Could not delete $fileName" }
                syncParentDirectory(copy)
            }
        }
    }
}

/**
 * Reads a regenerable cache only after its metadata proves the one-copy load
 * is bounded. The second size check also turns a concurrent replacement into a cache miss rather
 * than decoding a truncated or extended snapshot.
 */
@OptIn(ExperimentalForeignApi::class)
private fun readBoundedSnapshot(path: String): ByteArray? {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return null
    val declaredSize = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return null
    if (!isLoadableIosIndexSnapshotSize(declaredSize)) return null

    // Read straight into one array of the declared size (no NSData copy beside it), plus a one-byte probe: a file that
    // grew or shrank since the preflight is a cache miss, never a truncated or extended decode.
    val fd = platform.posix.open(path, platform.posix.O_RDONLY)
    if (fd < 0) return null
    try {
        val output = ByteArray(declaredSize.toInt())
        var read = 0
        while (read < output.size) {
            val got = output.usePinned { pinned ->
                platform.posix.read(fd, pinned.addressOf(read), (output.size - read).convert())
            }
            if (got <= 0L) return null
            read += got.toInt()
        }
        val probe = ByteArray(1)
        val extra = probe.usePinned { pinned -> platform.posix.read(fd, pinned.addressOf(0), 1.convert()) }
        return if (extra == 0L) output else null
    } finally {
        platform.posix.close(fd)
    }
}

/**
 * Replaces [path] with what [write] produces: a sibling `.tmp` written through a 64 KiB buffer, `F_FULLFSYNC`ed, renamed
 * over [path], and the directory synced so the rename itself survives a power loss (docs/analysis-094-plan.md 1a).
 */
@OptIn(ExperimentalForeignApi::class)
private fun writeDurably(path: String, write: ((Long) -> SnapshotWriter) -> Unit) {
    val staged = "$path.tmp"
    try {
        writeFileSynced(staged) { sink ->
            write { total -> SnapshotWriter(ByteArray(WRITE_BUFFER_BYTES), expected = total, drain = sink) }
        }
        check(platform.posix.rename(staged, path) == 0) { "Could not atomically replace ${path.substringAfterLast('/')}" }
        syncParentDirectory(path)
    } finally {
        platform.posix.unlink(staged)
    }
}

/** [writeDurably] for bytes already in hand. */
@OptIn(ExperimentalForeignApi::class)
private fun writeBytesDurably(path: String, bytes: ByteArray) {
    val staged = "$path.tmp"
    try {
        writeFileSynced(staged) { sink -> if (bytes.isNotEmpty()) sink(bytes, bytes.size) }
        check(platform.posix.rename(staged, path) == 0) { "Could not atomically replace ${path.substringAfterLast('/')}" }
        syncParentDirectory(path)
    } finally {
        platform.posix.unlink(staged)
    }
}

/** Creates [path] afresh, lets [write] fill it through the sink it is given, and syncs it to stable storage. */
@OptIn(ExperimentalForeignApi::class)
private fun writeFileSynced(path: String, write: (sink: (ByteArray, Int) -> Unit) -> Unit) {
    val fd = platform.posix.open(path, platform.posix.O_WRONLY or platform.posix.O_CREAT or platform.posix.O_TRUNC, 384u)
    check(fd >= 0) { "Could not create ${path.substringAfterLast('/')}" }
    var closed = false
    try {
        write { bytes, count ->
            var done = 0
            while (done < count) {
                val wrote = bytes.usePinned { pinned ->
                    platform.posix.write(fd, pinned.addressOf(done), (count - done).convert())
                }
                check(wrote > 0L) { "Could not write ${path.substringAfterLast('/')}" }
                done += wrote.toInt()
            }
        }
        // F_FULLFSYNC asks the drive to flush its own cache too; plain fsync is the fallback where it is refused.
        if (platform.posix.fcntl(fd, platform.posix.F_FULLFSYNC) != 0) {
            check(platform.posix.fsync(fd) == 0) { "Could not sync ${path.substringAfterLast('/')}" }
        }
        closed = true
        check(platform.posix.close(fd) == 0) { "Could not close ${path.substringAfterLast('/')}" }
    } finally {
        if (!closed) platform.posix.close(fd)
    }
}

/** Best effort, as on Android: the file itself was already synced. */
@OptIn(ExperimentalForeignApi::class)
private fun syncParentDirectory(path: String) {
    val directory = path.substringBeforeLast('/', missingDelimiterValue = ".")
    val fd = platform.posix.open(directory, platform.posix.O_RDONLY)
    if (fd < 0) return
    if (platform.posix.fcntl(fd, platform.posix.F_FULLFSYNC) != 0) platform.posix.fsync(fd)
    platform.posix.close(fd)
}

private const val WRITE_BUFFER_BYTES = 64 * 1024

/** The format version a snapshot's bytes declare, or null. */
private fun snapshotFormatVersion(bytes: ByteArray): Int? {
    if (bytes.size < 8) return null
    fun int(at: Int) = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
        ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
    return if (int(0) != MAGIC) null else int(4)
}

internal fun isLoadableIosIndexSnapshotSize(
    sizeBytes: Long,
    maximumSnapshotBytes: Long = MAX_IOS_INDEX_SNAPSHOT_BYTES,
): Boolean = maximumSnapshotBytes >= 0L && sizeBytes in 0L..maximumSnapshotBytes

@OptIn(ExperimentalForeignApi::class)
private fun indexPath(fileName: String): String? {
    val directory = indexDirectory ?: return null
    return if (directory.endsWith('/')) directory + fileName else "$directory/$fileName"
}

/** [fileName] in Caches, where builds before 0.9.4 kept it. */
@OptIn(ExperimentalForeignApi::class)
private fun cachesTwin(fileName: String): String? =
    (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String)
        ?.let { "${it.trimEnd('/')}/$fileName" }

/**
 * Where the stores live, chosen once per process: Application Support once the one-time copy from Caches is complete,
 * Caches until then (docs/analysis-094-plan.md 1e-ios, "Storage move"). iOS may purge Caches when the disk fills, which
 * threw away hours of analysis; Application Support is never purged.
 */
@OptIn(ExperimentalForeignApi::class)
private val indexDirectory: String? by lazy {
    fun userDirectory(kind: platform.Foundation.NSSearchPathDirectory): String? =
        NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String
    // The index is fully regenerable, can be large, and contains listening-derived vectors: Caches is left out of
    // backups by the OS, and the Application Support copy is marked the same way.
    val caches = userDirectory(NSCachesDirectory) ?: return@lazy null
    val support = userDirectory(NSApplicationSupportDirectory)
    if (support == null) {
        ensureDirectory(caches)
        return@lazy caches
    }
    resolveIndexDirectory(caches = caches, support = support)
}

/**
 * The store directory under [support] once every store file in [caches] has been copied there, else [caches] (created
 * if missing).
 *
 * The copy runs before any store is used and is complete only when its marker is written, after every file is copied
 * through a synced `.tmp` and renamed, and the directory synced: a copy cut short by a kill or a file that cannot be
 * read now (data protection before the first unlock) leaves no marker, so this process keeps using Caches and the next
 * launch empties the target and copies everything again, newer Caches files included. After the marker, Caches is
 * never read. Its files stay there until 0.9.5, so a downgrade to 0.9.3 finds its index (losing what was analysed
 * since).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun resolveIndexDirectory(caches: String, support: String): String {
    val target = "${support.trimEnd('/')}/$INDEX_DIRECTORY_NAME"
    val marker = "$target/$MOVED_MARKER"
    val manager = NSFileManager.defaultManager
    if (manager.fileExistsAtPath(marker)) return target
    return try {
        ensureDirectory(target)
        excludeFromBackup(target)
        // Whatever an earlier, cut-short copy left here is older than Caches, which that process went on writing (and
        // clearing): it must never outlive this copy.
        for (name in (manager.contentsOfDirectoryAtPath(target, null) ?: error("$target cannot be listed")).filterIsInstance<String>()) {
            if (name != MOVED_MARKER) check(manager.removeItemAtPath("$target/$name", null)) { "Could not remove $name" }
        }
        val listed = manager.contentsOfDirectoryAtPath(caches, null)
            ?: if (manager.fileExistsAtPath(caches)) error("Caches cannot be listed") else emptyList<Any?>()
        val names = listed.filterIsInstance<String>().filter(::isIndexStoreFile)
        for (name in names) {
            val source = "${caches.trimEnd('/')}/$name"
            // A file too large to load is a cache miss wherever it lies; it is not worth keeping Caches for.
            val size = (manager.attributesOfItemAtPath(source, null)?.get(NSFileSize) as? NSNumber)?.longLongValue
            if (size == null || !isLoadableIosIndexSnapshotSize(size)) continue
            // A file that is gone or can never be read is a cache miss here, as it would be in Caches, so it does not
            // pin the index to Caches. Anything else, data protection before the first unlock (EPERM) among it, may
            // pass: the move waits for the next launch rather than drop a store for good.
            val probe = platform.posix.open(source, platform.posix.O_RDONLY)
            if (probe < 0) {
                val code = platform.posix.errno
                if (code in SKIPPED_OPEN_ERRORS) continue
                error("$name cannot be opened now (errno $code)")
            }
            platform.posix.close(probe)
            val bytes = readBoundedSnapshot(source) ?: error("$name could not be read whole")
            writeBytesDurably("$target/$name", bytes)
        }
        writeBytesDurably(marker, ByteArray(0))
        target
    } catch (failure: Throwable) {
        println("SMART: index stays in Caches for now: ${failure.message}")
        ensureDirectory(caches)
        caches
    }
}

/** The files [IosFileIndexStore] writes, by the names its stores use; never a staged `.tmp` or `.half`. */
internal fun isIndexStoreFile(name: String): Boolean =
    name.endsWith(".bin") && INDEX_STORE_PREFIXES.any { name == "$it.bin" || name.startsWith("$it.") }

private val INDEX_STORE_PREFIXES = listOf("smart_index", "smart_text_index", "map_layout", "smart_imputation", "smart_energy")
private const val INDEX_DIRECTORY_NAME = "smart"
private const val MOVED_MARKER = ".moved-from-caches"
private val SKIPPED_OPEN_ERRORS = setOf(platform.posix.ENOENT, platform.posix.EACCES, platform.posix.EISDIR, platform.posix.ELOOP)

@OptIn(ExperimentalForeignApi::class)
private fun ensureDirectory(directory: String) {
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(directory)) {
        check(manager.createDirectoryAtPath(directory, true, null, null)) {
            "Could not create SMART index directory"
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun excludeFromBackup(directory: String) {
    platform.Foundation.NSURL.fileURLWithPath(directory, isDirectory = true)
        .setResourceValue(true, forKey = platform.Foundation.NSURLIsExcludedFromBackupKey, error = null)
    // The default class, stated: readable in the background once the device was unlocked after boot.
    NSFileManager.defaultManager.setAttributes(
        mapOf(platform.Foundation.NSFileProtectionKey to platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication),
        ofItemAtPath = directory,
        error = null,
    )
}

/** A compact portable snapshot format; every integer and float is big-endian. */
internal fun encodeIosIndexSnapshot(
    modelVersion: String,
    snapshot: StoredIndexSnapshot,
    maximumSnapshotBytes: Long = MAX_IOS_INDEX_SNAPSHOT_BYTES,
    half: Boolean = false,
): ByteArray {
    var output: ByteArray? = null
    writeIosIndexSnapshot(modelVersion, snapshot, maximumSnapshotBytes, half) { total ->
        SnapshotWriter(ByteArray(total.toInt()).also { output = it })
    }
    return checkNotNull(output)
}

/**
 * Writes the bytes [encodeIosIndexSnapshot] returns, through the writer [open] makes for their exact total size. The
 * file store streams them to disk through a small buffer instead of holding the whole snapshot twice.
 */
internal fun writeIosIndexSnapshot(
    modelVersion: String,
    snapshot: StoredIndexSnapshot,
    maximumSnapshotBytes: Long = MAX_IOS_INDEX_SNAPSHOT_BYTES,
    half: Boolean = false,
    open: (total: Long) -> SnapshotWriter,
) {
    require(maximumSnapshotBytes in 1L..Int.MAX_VALUE.toLong()) {
        "Snapshot byte limit must be positive and fit in a ByteArray"
    }
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
    val versionBytes = modelVersion.encodeToByteArray()
    require(versionBytes.size <= MAX_STRING_BYTES) { "Model version is too large" }
    val ids = LinkedHashMap<TrackId, ByteArray>(minOf(entries.size, 1_024))
    val identityBytes = LinkedHashMap<TrackId, ByteArray?>(minOf(entries.size, 1_024))
    val failedIds = LinkedHashMap<TrackId, ByteArray>(minOf(failedIdentities.size, 1_024))
    val failedIdentityBytes = LinkedHashMap<TrackId, ByteArray>(minOf(failedIdentities.size, 1_024))
    var total = HEADER_BYTES.toLong() + versionBytes.size.toLong() + Int.SIZE_BYTES

    fun requireSupportedSize(candidate: Long) {
        require(isLoadableIosIndexSnapshotSize(candidate, maximumSnapshotBytes)) {
            "Index snapshot is too large: $candidate bytes (limit $maximumSnapshotBytes)"
        }
    }

    requireSupportedSize(total)
    val vectorBytes = dim.toLong() * (if (half) Short.SIZE_BYTES else Float.SIZE_BYTES).toLong()
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
    for ((id, identity) in failedIdentities) {
        val idBytes = id.value.encodeToByteArray()
        val identity = identity.encodeToByteArray()
        require(idBytes.size <= MAX_STRING_BYTES) { "Track id is too large" }
        require(identity.size <= MAX_STRING_BYTES) { "Failure identity is too large" }
        val rowBytes = 2L * Int.SIZE_BYTES.toLong() + idBytes.size + identity.size
        requireSupportedSize(total + rowBytes)
        total += rowBytes
        failedIds[id] = idBytes
        failedIdentityBytes[id] = identity
    }

    // The same envelope guards reads and writes. Check it before allocating the contiguous output
    // buffer so the app can never write a cache that it will reject on the next launch.
    val writer = open(total)
    writer.int(MAGIC)
    writer.int(if (half) HALF_FORMAT_VERSION else FORMAT_VERSION)
    writer.bytesWithLength(versionBytes)
    writer.int(dim)
    writer.int(entries.size)
    for ((id, vector) in entries) {
        writer.bytesWithLength(ids.getValue(id))
        if (half) {
            for (component in vector) writer.short(HalfFloat.toHalf(component))
        } else {
            for (component in vector) writer.int(component.toBits())
        }
        writer.nullableBytesWithLength(identityBytes.getValue(id))
    }
    writer.int(failedIdentities.size)
    for (id in failedIdentities.keys) {
        writer.bytesWithLength(failedIds.getValue(id))
        writer.bytesWithLength(failedIdentityBytes.getValue(id))
    }
    writer.finish()
}

internal fun decodeIosIndexSnapshot(
    bytes: ByteArray,
    expectedModelVersion: String,
): StoredIndexSnapshot? {
    val reader = SnapshotReader(bytes)
    if (reader.int() != MAGIC) return null
    val formatVersion = reader.int()
    if (
        formatVersion != LEGACY_FORMAT_VERSION &&
        formatVersion != IDENTITY_FORMAT_VERSION &&
        formatVersion != FORMAT_VERSION &&
        formatVersion != HALF_FORMAT_VERSION
    ) return null
    val halves = formatVersion == HALF_FORMAT_VERSION
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
        val vector = if (halves) FloatArray(dim) { HalfFloat.toFloat(reader.short()) } else FloatArray(dim) { Float.fromBits(reader.int()) }
        val identity = if (formatVersion >= IDENTITY_FORMAT_VERSION) {
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
    val failedIdentities = LinkedHashMap<TrackId, String>()
    if (formatVersion >= FORMAT_VERSION) {
        val failedCount = reader.int()
        if (failedCount !in 0..MAX_ENTRIES || count + failedCount > MAX_ENTRIES) return null
        repeat(failedCount) {
            val id = TrackId(reader.bytesWithLength().decodeToString())
            val identity = reader.bytesWithLength().decodeToString()
            if (!seen.add(id)) return null
            failedIdentities[id] = identity
        }
    }
    if (!reader.atEnd()) return null
    return StoredIndexSnapshot(result, identities, failedIdentities)
}

/**
 * Big-endian writer into [output]: the whole snapshot when [drain] is null, or a buffer that [drain] empties whenever it
 * fills, which must then see exactly [expected] bytes in all.
 */
internal class SnapshotWriter(
    private val output: ByteArray,
    private val expected: Long = output.size.toLong(),
    private val drain: ((ByteArray, Int) -> Unit)? = null,
) {
    private var position = 0
    private var drained = 0L

    private fun room(bytes: Int) {
        if (drain != null && position > output.size - bytes) {
            drain.invoke(output, position)
            drained += position
            position = 0
        }
        require(position <= output.size - bytes)
    }

    fun int(value: Int) {
        room(Int.SIZE_BYTES)
        output[position++] = (value ushr 24).toByte()
        output[position++] = (value ushr 16).toByte()
        output[position++] = (value ushr 8).toByte()
        output[position++] = value.toByte()
    }

    fun short(value: Short) {
        room(Short.SIZE_BYTES)
        output[position++] = (value.toInt() ushr 8).toByte()
        output[position++] = value.toByte()
    }

    fun bytesWithLength(bytes: ByteArray) {
        int(bytes.size)
        var from = 0
        while (from < bytes.size) {
            val step = if (drain == null) bytes.size - from else minOf(bytes.size - from, output.size)
            room(step)
            bytes.copyInto(output, position, from, from + step)
            position += step
            from += step
        }
    }

    fun nullableBytesWithLength(bytes: ByteArray?) {
        if (bytes == null) {
            int(-1)
        } else {
            bytesWithLength(bytes)
        }
    }

    fun finish() {
        if (drain != null && position > 0) {
            drain.invoke(output, position)
            drained += position
            position = 0
        }
        check(drained + position == expected) { "Snapshot size changed while it was written" }
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

    fun short(): Short {
        require(position <= input.size - Short.SIZE_BYTES)
        return (((input[position++].toInt() and 0xff) shl 8) or (input[position++].toInt() and 0xff)).toShort()
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

private const val MAGIC = 0x4C4A4958 // "LJIX"
private const val LEGACY_FORMAT_VERSION = 1
private const val IDENTITY_FORMAT_VERSION = 2
private const val FORMAT_VERSION = 3
/** Format 3 with every vector component a big-endian half ([HalfFloat]); the same bytes as Android's. */
private const val HALF_FORMAT_VERSION = 4
private const val HEADER_BYTES = Int.SIZE_BYTES * 5
private const val MAX_DIMENSION = 4096
private const val MAX_ENTRIES = 1_000_000
private const val MAX_STRING_BYTES = 1_048_576

/**
 * Bounds a corrupt/regenerable cache to a load a mobile process can reject safely. With the audio index as halves
 * (format v4) 64 MiB holds about 34,000 960-dimensional rows (about 2 KB each with id and identity digest); a larger
 * index is refused at save and the last file that fit stays as it was.
 */
internal const val MAX_IOS_INDEX_SNAPSHOT_BYTES: Long = 64L * 1024L * 1024L
