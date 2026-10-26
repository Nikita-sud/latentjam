/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId

/**
 * What region identity keeps between runs (plan piece 4): the regions, and for every track of the
 * library they were last brought up to date with its row hash and whether it changed since the last
 * rediscovery (a "pending" track is not settled, so a rebuild may place it freely).
 */
public class RegionIdentitySnapshot(
    /**
     * The caller's version of the inputs (the app: layout cache version, source, dim). Not every input
     * is in it: an encoder or fusion-weight change instead changes every row hash, which sends the next
     * call to a full rediscovery with nothing settled (new ids for all regions).
     */
    public val inputVersion: String,
    public val ids: List<TrackId>,
    public val hashes: LongArray,
    public val pending: BooleanArray,
    public val regions: List<IdentityRegion>,
    /** High-water mark: the next new region's id ([RegionIdentity.rediscover]'s `nextId`). */
    public val nextId: Int = RegionIdentity.nextId(regions),
    /** The caller's non-vector inputs (the app: playlists, novelty preference, readiness); a join needs them unchanged. */
    public val context: String = "",
    /** Per track, a hash of the caller's descriptor facts (the app: tags as discovery reads them); 0 when unused. */
    public val tagHashes: LongArray = LongArray(ids.size),
    /** Per track: only its tags changed since the last rediscovery. It still owes a rebuild but stays settled. */
    public val tagPending: BooleanArray = BooleanArray(ids.size),
) {
    init {
        require(hashes.size == ids.size && pending.size == ids.size && tagHashes.size == ids.size && tagPending.size == ids.size) {
            "One hash, tag hash and pending flag per track"
        }
    }

    /** Tracks that owe the rebuild: a new or changed vector ([pending]) or new tags ([tagPending]). */
    public val pendingCount: Int get() = ids.indices.count { pending[it] || tagPending[it] }
}

/** Raw bytes on disk; a missing, short or corrupt file reads as null and costs one rediscovery. */
public interface RegionIdentityStore {
    public suspend fun load(): ByteArray?
    public suspend fun save(bytes: ByteArray)
    public suspend fun clear()
}

public object NoRegionIdentityStore : RegionIdentityStore {
    override suspend fun load(): ByteArray? = null
    override suspend fun save(bytes: ByteArray): Unit = Unit
    override suspend fun clear(): Unit = Unit
}

/**
 * Little-endian, length-prefixed UTF-8 strings, FNV-1a 64 checksum over everything before it. The
 * worlds' structured names (name, [LibraryWorldNameSource], content, semantic title, blend
 * families) are stored, never a localized string. Members are indices into the id list.
 */
public object RegionIdentityCodec {
    private const val MAGIC: Int = 0x494A4C52 // "RLJI"
    private const val FORMAT: Int = 3
    private const val MAX_STRING: Int = 1 shl 20

    public fun encode(snapshot: RegionIdentitySnapshot): ByteArray {
        val out = Writer()
        out.int(MAGIC); out.int(FORMAT)
        out.string(snapshot.inputVersion)
        out.int(snapshot.nextId)
        out.string(snapshot.context)
        out.int(snapshot.ids.size)
        val index = HashMap<TrackId, Int>(snapshot.ids.size * 2)
        snapshot.ids.forEachIndexed { i, id ->
            out.string(id.value); out.long(snapshot.hashes[i]); out.long(snapshot.tagHashes[i])
            out.byte((if (snapshot.pending[i]) 1 else 0) or (if (snapshot.tagPending[i]) 2 else 0))
            index[id] = i
        }
        out.int(snapshot.regions.size)
        val dim = snapshot.regions.firstOrNull()?.centroid?.size ?: 0
        out.int(dim)
        for (r in snapshot.regions) {
            require(r.centroid.size == dim) { "Centroids of one width" }
            out.int(r.id); out.int(r.colour); out.float(r.floor)
            for (v in r.centroid) out.float(v)
            val w = r.world
            out.string(w.name); out.string(w.nameSource.name); out.string(w.content.name); out.string(w.semanticTitle?.name ?: "")
            out.int(w.blendFamilies.size); for (f in w.blendFamilies) out.string(f)
            val members = w.tracks.mapNotNull { index[it.id] }
            out.int(members.size); for (m in members) out.int(m)
        }
        out.long(out.checksum())
        return out.bytes()
    }

    /** Null on any mismatch; members missing from [descriptorOf] keep a bare descriptor. */
    public fun decode(bytes: ByteArray, descriptorOf: (TrackId) -> TrackDescriptor? = { null }): RegionIdentitySnapshot? = runCatching {
        if (bytes.size < 16) return null
        val body = bytes.size - 8
        if (Reader(bytes, body).long() != fnv(bytes, body)) return null
        val r = Reader(bytes, 0)
        if (r.int() != MAGIC || r.int() != FORMAT) return null
        val inputVersion = r.string()
        val nextId = r.int()
        val context = r.string()
        val n = r.int(); require(n in 0..body)
        val ids = ArrayList<TrackId>(n); val hashes = LongArray(n); val tags = LongArray(n); val pending = BooleanArray(n); val tagPending = BooleanArray(n)
        for (i in 0 until n) {
            ids += TrackId(r.string()); hashes[i] = r.long(); tags[i] = r.long()
            val flags = r.byte().toInt(); pending[i] = flags and 1 != 0; tagPending[i] = flags and 2 != 0
        }
        val count = r.int(); require(count in 0..body)
        val dim = r.int(); require(dim in 0..body)
        val regions = ArrayList<IdentityRegion>(count)
        repeat(count) {
            val id = r.int(); val colour = r.int(); val floor = r.float()
            val centroid = FloatArray(dim) { r.float() }
            val name = r.string()
            val nameSource = LibraryWorldNameSource.valueOf(r.string())
            val content = LibraryWorldContent.valueOf(r.string())
            val semantic = r.string().takeIf(String::isNotEmpty)?.let(LibraryWorldSemanticTitle::valueOf)
            val families = (0 until r.int()).mapTo(LinkedHashSet()) { r.string() }
            val members = (0 until r.int()).map { val m = r.int(); require(m in 0 until n); ids[m] }
            if (members.isEmpty()) return null
            val world = LibraryWorld(name, members.map { descriptorOf(it) ?: TrackDescriptor(it) }, nameSource, content, semantic, families)
            regions += IdentityRegion(id, colour, world, centroid, floor)
        }
        if (r.at != body) return null
        RegionIdentitySnapshot(inputVersion, ids, hashes, pending, regions, maxOf(nextId, RegionIdentity.nextId(regions)), context, tags, tagPending)
    }.getOrNull()

    private fun fnv(bytes: ByteArray, end: Int): Long {
        var h = -0x340d631b7bdddcdbL
        for (i in 0 until end) h = (h xor (bytes[i].toLong() and 0xFF)) * 0x100000001b3L
        return h
    }

    private class Writer {
        private var buffer = ByteArray(1 shl 16)
        private var size = 0
        private fun ensure(extra: Int) { if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra)) }
        fun byte(v: Int) { ensure(1); buffer[size++] = v.toByte() }
        fun int(v: Int) { ensure(4); for (s in 0 until 4) buffer[size++] = (v ushr (8 * s)).toByte() }
        fun long(v: Long) { ensure(8); for (s in 0 until 8) buffer[size++] = (v ushr (8 * s)).toByte() }
        fun float(v: Float) = int(v.toRawBits())
        fun string(v: String) { val b = v.encodeToByteArray(); int(b.size); ensure(b.size); b.copyInto(buffer, size); size += b.size }
        fun checksum(): Long = fnv(buffer, size)
        fun bytes(): ByteArray = buffer.copyOf(size)
    }

    private class Reader(private val bytes: ByteArray, var at: Int) {
        fun byte(): Byte = bytes[at++]
        fun int(): Int { var v = 0; for (s in 0 until 4) v = v or ((bytes[at++].toInt() and 0xFF) shl (8 * s)); return v }
        fun long(): Long { var v = 0L; for (s in 0 until 8) v = v or ((bytes[at++].toLong() and 0xFF) shl (8 * s)); return v }
        fun float(): Float = Float.fromBits(int())
        fun string(): String { val len = int(); require(len in 0..MAX_STRING && at + len <= bytes.size); return bytes.decodeToString(at, at + len).also { at += len } }
    }
}
