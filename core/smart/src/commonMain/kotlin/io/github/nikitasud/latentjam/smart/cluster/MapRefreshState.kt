/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackId

/**
 * What a full Map build leaves behind so a later small library change can be refreshed instead of
 * rebuilt (docs/map-cost-research-2026-10-25.md, implementation plan, piece 0): the centring mean
 * and PCA basis, every track's reduced row, its 60 nearest neighbours by row index (no distances:
 * they are recomputed from the reduced rows with [BarnesHutTsne.nearestNeighbours]' arithmetic) and
 * a hash of the raw fused row it was built from. All in layout order, fp32.
 */
public class MapRefreshSnapshot(
    /** Caller-defined identity of the inputs (layout version, encoders, fusion); any change rebuilds. */
    public val inputVersion: String,
    public val source: LibraryVectorSource,
    /** The coverage fingerprint the layout was filed under. */
    public val layoutFingerprint: Long,
    public val ids: List<TrackId>,
    public val dim: Int,
    public val components: Int,
    public val neighbours: Int,
    /** RMS radius of the raw t-SNE output before alignment and normalization. */
    public val rawSpread: Float,
    public val mean: FloatArray,
    public val basis: FloatArray,
    public val reduced: FloatArray,
    public val neighbourIds: IntArray,
    public val hashes: LongArray,
) {
    init {
        val n = ids.size
        require(dim > 0 && components > 0 && neighbours in 0 until maxOf(n, 1)) { "Bad refresh state shape" }
        require(mean.size == dim && basis.size == dim * components) { "Mean or basis size mismatch" }
        require(reduced.size == n * components && neighbourIds.size == n * neighbours && hashes.size == n) {
            "Per-track arrays do not match ${n} tracks"
        }
        require(rawSpread.isFinite() && rawSpread > 0f) { "Raw spread must be positive" }
    }
}

/**
 * A stored [MapRefreshSnapshot], read through a memory-mapped view where the platform has one, so
 * the per-track arrays never sit on the managed heap. Only the mean, the basis and the id list are
 * copied out. [close] releases the mapping; reads after it are invalid.
 */
public class MapRefreshState internal constructor(
    private val bytes: MapRefreshBytes,
    public val inputVersion: String,
    public val source: LibraryVectorSource,
    public val layoutFingerprint: Long,
    public val ids: List<TrackId>,
    public val dim: Int,
    public val components: Int,
    public val neighbours: Int,
    public val rawSpread: Float,
    public val mean: FloatArray,
    public val basis: FloatArray,
    private val reducedAt: Long,
    private val neighboursAt: Long,
    private val hashesAt: Long,
) {
    public val size: Int get() = ids.size

    /** Copies track [row]'s reduced row into `out[offset until offset + components]`. */
    public fun reducedRow(row: Int, out: FloatArray, offset: Int = 0) {
        val base = reducedAt + 4L * row * components
        for (c in 0 until components) out[offset + c] = bytes.float(base + 4L * c)
    }

    /** Row index of track [row]'s [slot]-th nearest neighbour (nearest first). */
    public fun neighbour(row: Int, slot: Int): Int = bytes.int(neighboursAt + 4L * (row.toLong() * neighbours + slot))

    /** Hash of the raw fused row track [row] was built from. */
    public fun hash(row: Int): Long = bytes.long(hashesAt + 8L * row)

    public fun close(): Unit = bytes.close()
}

/** Where a [MapRefreshSnapshot] is kept. Implementations write atomically and reject anything malformed. */
public interface MapRefreshStateStore {
    /** The stored state, or null when absent, truncated, corrupt or of another format. */
    public suspend fun load(): MapRefreshState?

    public suspend fun save(snapshot: MapRefreshSnapshot)

    public suspend fun clear()
}

/** No persistence: every change rebuilds, exactly as without the refresh. */
public object NoMapRefreshStateStore : MapRefreshStateStore {
    override suspend fun load(): MapRefreshState? = null
    override suspend fun save(snapshot: MapRefreshSnapshot): Unit = Unit
    override suspend fun clear(): Unit = Unit
}

/** Little-endian random reads over a stored state (a mapped file, or a byte array in tests). */
internal interface MapRefreshBytes {
    val size: Long
    fun byte(at: Long): Byte
    fun int(at: Long): Int
    fun long(at: Long): Long
    fun float(at: Long): Float = Float.fromBits(int(at))
    fun close()
}

/** Little-endian sequential writes. */
internal interface MapRefreshSink {
    fun byte(value: Byte)
    fun int(value: Int)
    fun long(value: Long)
    fun float(value: Float) = int(value.toRawBits())
}

/** [MapRefreshBytes] over a byte array (host tests, and platforms without a mapping). */
internal class ArrayMapRefreshBytes(private val array: ByteArray) : MapRefreshBytes {
    override val size: Long get() = array.size.toLong()
    override fun byte(at: Long): Byte = array[at.toInt()]
    override fun int(at: Long): Int {
        val i = at.toInt()
        return (array[i].toInt() and 0xFF) or ((array[i + 1].toInt() and 0xFF) shl 8) or
            ((array[i + 2].toInt() and 0xFF) shl 16) or ((array[i + 3].toInt() and 0xFF) shl 24)
    }
    override fun long(at: Long): Long = (int(at).toLong() and 0xFFFFFFFFL) or (int(at + 4).toLong() shl 32)
    override fun close() = Unit
}

/** [MapRefreshSink] into a growable byte array (host tests, and the iOS writer). */
internal class ArrayMapRefreshSink(initialCapacity: Int = 1 shl 16) : MapRefreshSink {
    private var array = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    override fun byte(value: Byte) {
        if (size == array.size) array = array.copyOf(array.size * 2)
        array[size++] = value
    }
    override fun int(value: Int) { for (s in 0 until 4) byte((value ushr (8 * s)).toByte()) }
    override fun long(value: Long) { for (s in 0 until 8) byte((value ushr (8 * s)).toByte()) }
    fun toByteArray(): ByteArray = array.copyOf(size)
}

/**
 * The stored layout (little-endian):
 * magic, format, header strings and numbers, mean, basis, ids, then the per-track blocks at 8-byte
 * aligned offsets (reduced rows, neighbour ids, hashes), then an FNV-1a 64 checksum of every
 * preceding byte.
 */
internal object MapRefreshStateCodec {
    const val MAGIC: Int = 0x524D4A4C // "LJMR"
    const val FORMAT: Int = 1
    private const val FNV_OFFSET: Long = -0x340d631b7bdddcdbL
    private const val FNV_PRIME: Long = 0x100000001b3L
    /** Refuses absurd shapes before allocating anything. */
    private const val MAX_TRACKS: Int = 200_000
    private const val MAX_DIM: Int = 8_192
    private const val MAX_STRING: Int = 4_096

    fun write(snapshot: MapRefreshSnapshot, sink: MapRefreshSink) {
        val out = ChecksummedSink(sink)
        out.int(MAGIC)
        out.int(FORMAT)
        out.string(snapshot.inputVersion)
        out.string(snapshot.source.name)
        out.long(snapshot.layoutFingerprint)
        out.int(snapshot.ids.size)
        out.int(snapshot.dim)
        out.int(snapshot.components)
        out.int(snapshot.neighbours)
        out.float(snapshot.rawSpread)
        for (v in snapshot.mean) out.float(v)
        for (v in snapshot.basis) out.float(v)
        for (id in snapshot.ids) out.string(id.value)
        out.align()
        for (v in snapshot.reduced) out.float(v)
        out.align()
        for (v in snapshot.neighbourIds) out.int(v)
        out.align()
        for (v in snapshot.hashes) out.long(v)
        sink.long(out.checksum)
    }

    /** Null for anything but an intact state of this format. Closes [bytes] when rejecting. */
    fun read(bytes: MapRefreshBytes): MapRefreshState? {
        val state = runCatching { parse(bytes) }.getOrNull()
        if (state == null) bytes.close()
        return state
    }

    private fun parse(bytes: MapRefreshBytes): MapRefreshState? {
        if (bytes.size < 16) return null
        val total = bytes.size
        val body = total - 8
        // Checksum first: everything below may then trust the bytes.
        var hash = FNV_OFFSET
        for (at in 0L until body) hash = (hash xor (bytes.byte(at).toLong() and 0xFF)) * FNV_PRIME
        if (hash != bytes.long(body)) return null
        val reader = Reader(bytes, body)
        if (reader.int() != MAGIC || reader.int() != FORMAT) return null
        val inputVersion = reader.string() ?: return null
        val source = runCatching { LibraryVectorSource.valueOf(reader.string() ?: return null) }.getOrNull() ?: return null
        val fingerprint = reader.long()
        val n = reader.int()
        val dim = reader.int()
        val components = reader.int()
        val neighbours = reader.int()
        if (n !in 1..MAX_TRACKS || dim !in 1..MAX_DIM || components !in 1..dim || neighbours !in 0 until n) return null
        val rawSpread = reader.float()
        if (!rawSpread.isFinite() || rawSpread <= 0f) return null
        val mean = FloatArray(dim) { reader.float() }
        val basis = FloatArray(dim * components) { reader.float() }
        val ids = List(n) { TrackId(reader.string() ?: return null) }
        reader.align()
        val reducedAt = reader.at
        reader.skip(4L * n * components)
        reader.align()
        val neighboursAt = reader.at
        reader.skip(4L * n * neighbours)
        reader.align()
        val hashesAt = reader.at
        reader.skip(8L * n)
        if (reader.at != body) return null
        return MapRefreshState(
            bytes, inputVersion, source, fingerprint, ids, dim, components, neighbours, rawSpread, mean, basis,
            reducedAt, neighboursAt, hashesAt,
        ).takeIf { state -> (0 until n).all { row -> (0 until neighbours).all { state.neighbour(row, it) in 0 until n } } }
    }

    private class ChecksummedSink(private val sink: MapRefreshSink) : MapRefreshSink {
        var checksum: Long = FNV_OFFSET
            private set
        private var written = 0L

        override fun byte(value: Byte) {
            checksum = (checksum xor (value.toLong() and 0xFF)) * FNV_PRIME
            written++
            sink.byte(value)
        }
        override fun int(value: Int) { for (s in 0 until 4) byte((value ushr (8 * s)).toByte()) }
        override fun long(value: Long) { for (s in 0 until 8) byte((value ushr (8 * s)).toByte()) }
        fun string(value: String) {
            val utf8 = value.encodeToByteArray()
            require(utf8.size <= MAX_STRING) { "String too long for the refresh state" }
            int(utf8.size)
            for (b in utf8) byte(b)
        }
        fun align() { while (written % 8 != 0L) byte(0) }
    }

    private class Reader(private val bytes: MapRefreshBytes, private val end: Long) {
        var at = 0L
            private set
        private fun need(count: Long) { if (count < 0 || at + count > end) throw IndexOutOfBoundsException() }
        fun int(): Int { need(4); return bytes.int(at).also { at += 4 } }
        fun long(): Long { need(8); return bytes.long(at).also { at += 8 } }
        fun float(): Float = Float.fromBits(int())
        fun string(): String? {
            val length = int()
            if (length !in 0..MAX_STRING) return null
            need(length.toLong())
            val utf8 = ByteArray(length) { bytes.byte(at + it) }
            at += length
            return utf8.decodeToString()
        }
        fun skip(count: Long) { need(count); at += count }
        fun align() { while (at % 8 != 0L) { need(1); at++ } }
    }
}
