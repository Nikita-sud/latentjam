/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * The audio index as consecutive segments of its row order, one file each, committed by a manifest
 * (docs/analysis-094-segments.md, LIFE-03).
 *
 * One file has a 64 MiB envelope, about 34,000 rows: above it every save was refused and every cold start embedded the
 * rest again. Here a segment holds at most [segmentRows] rows (and about [SEGMENT_BYTES]) in its own [segment] store,
 * and only segments whose content changed are written. The index's order changes only by appends, removals and
 * in-place replacements, so its rows are runs of segments; read back in the manifest's order they restore that order
 * exactly, which SMART's tie-breaks depend on.
 *
 * Files, for a store whose [fileName] is `X.bin`:
 * - `X.s<n>.bin`: one segment's rows, or (with no rows) the remembered failures. n is never reused: a changed segment
 *   is written under a new number, so a file the committed manifest names is never overwritten.
 * - `X.manifest.bin`: the segments in order with their row counts and digests, the failures file and its digest. It is
 *   written last, atomically, and is the commit: a crash anywhere in a save leaves the previous manifest and the files
 *   it names untouched, so a load restores exactly the old snapshot or exactly the new one.
 * - `X.bin`: the single file of earlier builds, read while there is no manifest. The first committed save records its
 *   digest and deletes it; one found later with another digest was written by an earlier build after a downgrade, and
 *   wins.
 * A load checks each segment against the manifest's digest, so a damaged segment (cut short or overwritten) is skipped
 * and only its rows are analysed again.
 *
 * The manifest is kept in the platform store's own format, as the failure entries of a file with no rows: the same
 * atomic write, bounds and decoding as every other file, on both platforms. [names] lists the file names beside
 * [fileName]. Not thread-safe: the engine serializes every call, as for any [IndexStore].
 */
public class SegmentedIndexStore(
    private val fileName: String,
    private val segment: (fileName: String) -> IndexStore,
    private val names: () -> List<String>,
    private val segmentRows: Int = SEGMENT_ROWS,
    /** A segment is closed at about this many bytes (tests make it small). */
    private val segmentBytes: Long = SEGMENT_BYTES,
    /**
     * The platform store reads a file whole before handing over any row (iOS): each file is then read once, and its
     * digest taken from what was read, instead of a checking read and a delivering one.
     */
    private val readsWhole: Boolean = false,
) : IndexStore {
    init {
        require(fileName.endsWith(".bin")) { "segmented store file must end in .bin: $fileName" }
        require(segmentRows > 0)
    }

    private val stem = fileName.removeSuffix(".bin")
    private val single: IndexStore by lazy { segment(fileName) }
    private val manifestStore: IndexStore by lazy { segment("$stem.manifest.bin") }
    private val stores = HashMap<Int, IndexStore>()

    private fun store(number: Int): IndexStore =
        stores.getOrPut(number) { segment("$stem.s${number.toString().padStart(6, '0')}.bin") }

    /**
     * The numbers of the `X.s<n>.bin` files on disk, ascending; with [leftovers], also of their stores' temp and staging
     * files a killed write left (`.bin.tmp`, `.bin.half`), which the store's clear deletes with the file.
     */
    private fun numbersOnDisk(leftovers: Boolean = false): List<Int> {
        val prefix = "$stem.s"
        val suffixes = if (leftovers) listOf(".bin", ".bin.tmp", ".bin.half") else listOf(".bin")
        return names().mapNotNull { name ->
            if (!name.startsWith(prefix)) return@mapNotNull null
            val suffix = suffixes.firstOrNull(name::endsWith) ?: return@mapNotNull null
            val digits = name.substring(prefix.length, name.length - suffix.length)
            digits.takeIf { it.isNotEmpty() && it.length <= 9 && it.all(Char::isDigit) }?.toInt()
        }.distinct().sorted()
    }

    private class Segment(val number: Int, val rows: Int, val digest: Digest)

    /** What the committed manifest says, as of the last load or save that completed. */
    private class State(
        val segments: List<Segment>,
        val failures: Segment?,
        /** The digest of the single file this set was made from ("-" for none). */
        val migratedFrom: String,
        hashes: LongArray,
        positions: IntArray,
    ) {
        /** Every row's id hash, sorted, and the position (in [segments]) of the segment holding it. */
        private val lookupHashes: LongArray
        private val lookupPositions: IntArray

        init {
            val order = hashes.indices.sortedBy { hashes[it] }
            lookupHashes = LongArray(order.size) { hashes[order[it]] }
            lookupPositions = IntArray(order.size) { positions[order[it]] }
        }

        fun positionOf(id: TrackId): Int {
            val hash = idHash(id)
            var low = 0
            var high = lookupHashes.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val value = lookupHashes[mid]
                when {
                    value < hash -> low = mid + 1
                    value > hash -> high = mid - 1
                    else -> return lookupPositions[mid]
                }
            }
            return -1
        }

        fun numbers(): List<Int> = segments.map { it.number } + listOfNotNull(failures?.number)
    }

    private var state: State? = null
    /** Set by a load that read the single file: its digest, recorded by the first committed save. */
    private var pendingMigration: String? = null

    override suspend fun load(modelVersion: String): Map<TrackId, FloatArray>? = loadSnapshot(modelVersion)?.entries

    override suspend fun loadSnapshot(modelVersion: String): StoredIndexSnapshot? {
        val entries = LinkedHashMap<TrackId, FloatArray>()
        val identities = LinkedHashMap<TrackId, String>()
        val failed = loadRows(modelVersion) { id, vector, identity ->
            if (id !in entries) {
                entries[id] = vector
                if (identity != null) identities[id] = identity
            }
        } ?: return null
        return StoredIndexSnapshot(entries, identities, failed.filterKeys { it !in entries })
    }

    override suspend fun loadRows(
        modelVersion: String,
        row: (id: TrackId, vector: FloatArray, identity: String?) -> Unit,
    ): Map<TrackId, String>? {
        state = null
        pendingMigration = null
        val manifest = readManifest(modelVersion)
        val singleDigest = checkedDigest(single, modelVersion)
        val downgraded = manifest != null && manifest.migratedFrom != CLEARED &&
            singleDigest != null && singleDigest.hex() != manifest.migratedFrom
        if (manifest == null || downgraded) {
            // No commit yet, or a downgraded build wrote the single file since: the single file, as before. Without
            // either there is nothing to trust; segment files no manifest names are leftovers, never rows.
            if (singleDigest == null) return null
            val failed = deliverChecked(single, modelVersion, singleDigest, row)
            pendingMigration = singleDigest.hex()
            return failed
        }
        val hashes = LongArrayBuilder()
        val positions = IntArrayBuilder()
        val seen = HashSet<TrackId>()
        val segments = ArrayList<Segment>(manifest.segments.size)
        for (listed in manifest.segments) {
            val store = store(listed.number)
            val digest = checkedDigest(store, modelVersion)
            if (digest == null || digest != listed.digest) continue // damaged: only its rows are analysed again
            val position = segments.size
            deliverChecked(store, modelVersion, digest) { id, vector, identity ->
                if (seen.add(id)) {
                    hashes.add(idHash(id))
                    positions.add(position)
                }
                row(id, vector, identity)
            }
            segments += listed
        }
        var failed: Map<TrackId, String> = emptyMap()
        var failures: Segment? = null
        manifest.failures?.let { listed ->
            val store = store(listed.number)
            if (checkedDigest(store, modelVersion) == listed.digest) {
                failed = deliverChecked(store, modelVersion, listed.digest) { _, _, _ -> }
                failures = listed
            }
        }
        state = State(segments, failures, manifest.migratedFrom, hashes.build(), positions.build())
        whole = null
        return failed
    }

    /** What [readsWhole] read for the next [deliverChecked] of the same store. */
    private var whole: Pair<IndexStore, StoredIndexSnapshot>? = null

    /** [store]'s file read whole once without delivering its rows: its digest, or null when it does not read. */
    private suspend fun checkedDigest(store: IndexStore, modelVersion: String): Digest? {
        whole = null
        if (readsWhole) {
            val snapshot = store.loadSnapshot(modelVersion) ?: return null
            val digest = DigestBuilder(modelVersion)
            for ((id, vector) in snapshot.entries) digest.add(id, snapshot.identities[id], vector)
            digest.addFailures(snapshot.failedIdentities)
            whole = store to snapshot
            return digest.build()
        }
        val digest = DigestBuilder(modelVersion)
        val failed = store.loadRows(modelVersion) { id, vector, identity -> digest.add(id, identity, vector) } ?: return null
        digest.addFailures(failed)
        return digest.build()
    }

    /** [store]'s rows delivered, read a second time and checked against [expected]. */
    private suspend fun deliverChecked(
        store: IndexStore,
        modelVersion: String,
        expected: Digest,
        row: (id: TrackId, vector: FloatArray, identity: String?) -> Unit,
    ): Map<TrackId, String> {
        whole?.takeIf { it.first === store }?.let { (_, snapshot) ->
            // Read once already, and its digest taken from exactly these rows.
            whole = null
            for ((id, vector) in snapshot.entries) row(id, vector, snapshot.identities[id])
            return snapshot.failedIdentities
        }
        val digest = DigestBuilder(modelVersion)
        val failed = store.loadRows(modelVersion) { id, vector, identity ->
            digest.add(id, identity, vector)
            row(id, vector, identity)
        } ?: throw IndexRestoreFailure(IllegalStateException("a segment stopped reading between two reads"))
        digest.addFailures(failed)
        // Nothing in this process writes the file between the two reads: a difference is the storage failing now.
        if (digest.build() != expected) throw IndexRestoreFailure(IllegalStateException("a segment changed between two reads"))
        return failed
    }

    override suspend fun save(modelVersion: String, entries: Map<TrackId, FloatArray>): Unit =
        saveSnapshot(modelVersion, StoredIndexSnapshot(entries))

    /**
     * One pass over [snapshot]'s rows in order: rows keep their segment, rows new to the store fill the last segment and
     * then new ones, and a segment whose rows or content changed is written under a new number. The manifest, written
     * last, commits; files it no longer names go after it. Should the order not be "old order, then new rows" (a seed
     * restored ahead of the stored rows does that), the whole snapshot is written afresh.
     */
    override suspend fun saveSnapshot(modelVersion: String, snapshot: StoredIndexSnapshot) {
        val entries = snapshot.entries
        // The whole-snapshot checks the platform stores make, before any file is written: a refusal is all or nothing.
        val dim = entries.values.firstOrNull()?.size ?: 0
        require(snapshot.failedIdentities.keys.none(entries::containsKey)) {
            "A track cannot have both a vector and a failed identity"
        }
        require(entries.values.all { it.size == dim }) { "Index vectors must share one dimension" }
        val previous = state
        val first = maxOf(numbersOnDisk().lastOrNull() ?: -1, previous?.numbers()?.maxOrNull() ?: -1)
        val written = previous?.let { writeKeeping(modelVersion, snapshot, dim, it, first) }
            ?: writeAfresh(modelVersion, snapshot, dim, first)
        var next = written.next
        val failuresDigest = DigestBuilder(modelVersion).apply { addFailures(snapshot.failedIdentities) }.build()
        val failures = when {
            snapshot.failedIdentities.isEmpty() -> null
            previous?.failures?.digest == failuresDigest -> previous.failures
            else -> Segment(++next, 0, failuresDigest).also {
                store(it.number).saveSnapshot(modelVersion, StoredIndexSnapshot(emptyMap(), emptyMap(), snapshot.failedIdentities))
            }
        }
        val migratedFrom = pendingMigration ?: previous?.migratedFrom?.takeIf { it != CLEARED } ?: NONE
        writeManifest(modelVersion, written.segments, failures, migratedFrom)
        state = State(written.segments, failures, migratedFrom, written.hashes, written.positions)
        pendingMigration = null
        // After the commit: every file the manifest does not name, and the single file it was made from.
        val keep = state!!.numbers().toHashSet()
        val leftovers = runCatching { numbersOnDisk(leftovers = true) }.getOrDefault(emptyList())
        for (number in leftovers) if (number !in keep) runCatching { store(number).clear() }
        stores.keys.retainAll(keep)
        // Whatever single file is here now was read by this process's load (and is in the commit) or is stale.
        runCatching { single.clear() }
    }

    private class Written(val segments: List<Segment>, val hashes: LongArray, val positions: IntArray, val next: Int)

    /** Rows kept in their segments; null when the order is not "old order, then new rows". */
    private suspend fun writeKeeping(
        modelVersion: String,
        snapshot: StoredIndexSnapshot,
        dim: Int,
        old: State,
        first: Int,
    ): Written? {
        var next = first
        val segments = ArrayList<Segment>()
        val hashes = LongArrayBuilder()
        val positions = IntArrayBuilder()
        var group: Group? = null
        var lastOld = -1
        var tail = false
        for ((id, vector) in snapshot.entries) {
            val identity = snapshot.identities[id]
            val source = old.positionOf(id)
            if (source >= 0) {
                if (tail || source < lastOld) return null
                lastOld = source
            } else {
                tail = true
            }
            val current = group
            // An old row stays with its segment; a new one fills whatever segment is open (the last), then new ones.
            val joins = current != null && if (source >= 0) current.source == source else !current.full(id, identity, dim)
            if (!joins) {
                if (current != null) segments += current.close(snapshot) { ++next }
                group = if (source >= 0) Group(source, old.segments[source], modelVersion) else Group(-1, null, modelVersion)
            }
            group!!.add(id, identity, vector)
            hashes.add(idHash(id))
            positions.add(segments.size)
        }
        group?.let { segments += it.close(snapshot) { ++next } }
        return Written(segments, hashes.build(), positions.build(), next)
    }

    /** The whole snapshot in segments under new numbers, all written. */
    private suspend fun writeAfresh(modelVersion: String, snapshot: StoredIndexSnapshot, dim: Int, first: Int): Written {
        var next = first
        val segments = ArrayList<Segment>()
        val hashes = LongArrayBuilder()
        val positions = IntArrayBuilder()
        var group: Group? = null
        for ((id, vector) in snapshot.entries) {
            val identity = snapshot.identities[id]
            val current = group
            if (current == null || current.full(id, identity, dim)) {
                if (current != null) segments += current.close(snapshot) { ++next }
                group = Group(-1, null, modelVersion)
            }
            group!!.add(id, identity, vector)
            hashes.add(idHash(id))
            positions.add(segments.size)
        }
        group?.let { segments += it.close(snapshot) { ++next } }
        return Written(segments, hashes.build(), positions.build(), next)
    }

    /**
     * One segment's rows as they are collected, a contiguous run of the snapshot's order: their ids, the digest of what
     * they hold, and about how many bytes they take. [source] is the old segment's position, -1 for new rows only.
     */
    private inner class Group(val source: Int, private val previous: Segment?, private val modelVersion: String) {
        private val ids = ArrayList<TrackId>()
        private val digest = DigestBuilder(modelVersion)
        private var bytes = 0L

        /** Whether a row would not fit: [segmentRows] rows, and about [segmentBytes] (one row always fits). */
        fun full(id: TrackId, identity: String?, dim: Int): Boolean =
            ids.size >= segmentRows || (ids.isNotEmpty() && bytes + rowBytes(id, identity, dim) > segmentBytes)

        fun add(id: TrackId, identity: String?, vector: FloatArray) {
            ids += id
            digest.add(id, identity, vector)
            bytes += rowBytes(id, identity, vector.size)
        }

        /** The segment on disk when its rows are unchanged, else these rows written under a new number. */
        suspend fun close(snapshot: StoredIndexSnapshot, newNumber: () -> Int): Segment {
            digest.addFailures(emptyMap())
            val value = digest.build()
            if (previous != null && previous.rows == ids.size && previous.digest == value) return previous
            val segment = Segment(newNumber(), ids.size, value)
            store(segment.number).saveSnapshot(
                modelVersion,
                StoredIndexSnapshot(RowsView(ids, snapshot.entries), snapshot.identities, emptyMap()),
            )
            return segment
        }
    }

    // The manifest, as the failure entries of a file with no rows (see the class KDoc).

    private class Manifest(val segments: List<Segment>, val failures: Segment?, val migratedFrom: String)

    private suspend fun writeManifest(modelVersion: String, segments: List<Segment>, failures: Segment?, migratedFrom: String) {
        val entries = LinkedHashMap<TrackId, String>()
        entries[TrackId(MANIFEST_VERSION_KEY)] = MANIFEST_VERSION
        entries[TrackId(MIGRATED_KEY)] = migratedFrom
        for ((position, segment) in segments.withIndex()) entries[TrackId("$SEGMENT_KEY$position")] = segment.encode()
        failures?.let { entries[TrackId(FAILURES_KEY)] = it.encode() }
        manifestStore.saveSnapshot(modelVersion, StoredIndexSnapshot(emptyMap(), emptyMap(), entries))
    }

    private fun Segment.encode(): String = "$number:$rows:${digest.hex()}"

    private suspend fun readManifest(modelVersion: String): Manifest? {
        val entries = manifestStore.loadRows(modelVersion) { _, _, _ -> }
            ?: manifestStore.loadRows(ANY_VERSION) { _, _, _ -> }?.takeIf { it[TrackId(MIGRATED_KEY)] == CLEARED }
            ?: return null
        val raw = entries.mapKeys { it.key.value }
        if (raw[MANIFEST_VERSION_KEY] != MANIFEST_VERSION) return null
        fun parse(value: String): Segment? {
            val parts = value.split(':')
            if (parts.size != 3) return null
            return Segment(
                parts[0].toIntOrNull() ?: return null,
                parts[1].toIntOrNull() ?: return null,
                Digest.parse(parts[2]) ?: return null,
            )
        }
        val segments = ArrayList<Segment>()
        while (true) segments += parse(raw["$SEGMENT_KEY${segments.size}"] ?: break) ?: return null
        val failures = raw[FAILURES_KEY]?.let { parse(it) ?: return null }
        return Manifest(segments, failures, raw[MIGRATED_KEY] ?: return null)
    }

    /**
     * An empty manifest is committed first, so a clear cut anywhere restores nothing (the files it had not deleted yet
     * are leftovers no manifest names, and the single file is ignored after it), then every file goes, the manifest last.
     */
    override suspend fun clear() {
        // Forget first: a clear that fails partway must leave nothing a repair save would trust.
        state = null
        pendingMigration = null
        writeManifest(ANY_VERSION, emptyList(), null, CLEARED)
        for (number in numbersOnDisk(leftovers = true)) store(number).clear()
        single.clear()
        stores.clear()
        manifestStore.clear()
    }

    public companion object {
        /** 4,096 half rows of 960 floats: about 8 MB a file, an eighth of the envelope and of an iOS load's peak. */
        public const val SEGMENT_ROWS: Int = 4_096

        /** A segment is closed at about this size, half the platform stores' 64 MiB envelope. */
        internal const val SEGMENT_BYTES: Long = 32L * 1024L * 1024L

        private const val NONE = "-"
        /** The tombstone a clear commits: no segments, and no single file to read. */
        private const val CLEARED = "cleared"
        /** A tombstone is version-independent: any version's load reads it as "nothing here". */
        private const val ANY_VERSION = "*"
        private const val MANIFEST_VERSION_KEY = "manifest"
        private const val MANIFEST_VERSION = "1"
        private const val MIGRATED_KEY = "migrated-from"
        private const val SEGMENT_KEY = "segment."
        private const val FAILURES_KEY = "failures"
    }
}

/** A row's bytes in an LJIX v4 file, at most: UTF-8 strings at three bytes a UTF-16 unit. */
private fun rowBytes(id: TrackId, identity: String?, dim: Int): Long =
    8L + 3L * id.value.length + 2L * dim + 3L * (identity?.length ?: 0)

/** [ids]' rows of [rows], in that order: one segment's part of a snapshot, without copying the snapshot. */
private class RowsView(
    private val ids: List<TrackId>,
    private val rows: Map<TrackId, FloatArray>,
) : AbstractMap<TrackId, FloatArray>() {
    private val members: Set<TrackId> by lazy { ids.toHashSet() }
    override val size: Int get() = ids.size
    override fun containsKey(key: TrackId): Boolean = key in members
    override fun get(key: TrackId): FloatArray? = if (key in members) rows[key] else null
    override val entries: Set<Map.Entry<TrackId, FloatArray>>
        get() = object : AbstractSet<Map.Entry<TrackId, FloatArray>>() {
            override val size: Int get() = ids.size
            override fun iterator(): Iterator<Map.Entry<TrackId, FloatArray>> {
                val source = ids.iterator()
                return object : Iterator<Map.Entry<TrackId, FloatArray>> {
                    override fun hasNext(): Boolean = source.hasNext()
                    override fun next(): Map.Entry<TrackId, FloatArray> {
                        val id = source.next()
                        return Row(id, checkNotNull(rows[id]) { "row ${id.value} left the snapshot" })
                    }
                }
            }
        }

    private class Row(override val key: TrackId, override val value: FloatArray) : Map.Entry<TrackId, FloatArray>
}

/** 128 bits: two 64-bit lanes with different seeds and multipliers, the second finished by a xorshift-multiply mix. */
private data class Digest(val high: Long, val low: Long) {
    fun hex(): String = high.toULong().toString(16).padStart(16, '0') + low.toULong().toString(16).padStart(16, '0')

    companion object {
        fun parse(text: String): Digest? {
            if (text.length != 32) return null
            val high = text.substring(0, 16).toULongOrNull(16) ?: return null
            val low = text.substring(16).toULongOrNull(16) ?: return null
            return Digest(high.toLong(), low.toLong())
        }
    }
}

/**
 * The digest of a file's content as the platform store keeps it: the model version, each row's id (UTF-16), identity
 * (a marker for none), dimension and components as the halves written, the row count, then the failures in order.
 */
private class DigestBuilder(modelVersion: String) {
    private var a = FNV_OFFSET
    private var b = LANE_B_OFFSET
    private var rows = 0L

    init {
        mixString(modelVersion)
    }

    fun add(id: TrackId, identity: String?, vector: FloatArray) {
        rows++
        mixString(id.value)
        if (identity == null) mix(-1L) else mixString(identity)
        mix(vector.size.toLong())
        for (component in vector) mix(HalfFloat.toHalf(component).toLong())
    }

    fun addFailures(failed: Map<TrackId, String>) {
        mix(rows)
        mix(failed.size.toLong())
        for ((id, identity) in failed) {
            mixString(id.value)
            mixString(identity)
        }
    }

    fun build(): Digest {
        var z = b
        z = (z xor (z ushr 33)) * -0xae502812aa7333L
        z = (z xor (z ushr 33)) * -0x3b314601e57a13adL
        return Digest(a, z xor (z ushr 33))
    }

    private fun mixString(text: String) {
        mix(text.length.toLong())
        for (unit in text) mix(unit.code.toLong())
    }

    private fun mix(part: Long) {
        a = (a xor part) * FNV_PRIME
        b = (b + part) * LANE_B_MULTIPLIER
        b = b xor (b ushr 29)
    }
}

private fun idHash(id: TrackId): Long {
    var value = FNV_OFFSET
    for (unit in id.value) value = (value xor unit.code.toLong()) * FNV_PRIME
    return value
}

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
private const val LANE_B_OFFSET = 0x6c62272e07bb0142L
private const val LANE_B_MULTIPLIER = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15, odd

private class LongArrayBuilder {
    private var values = LongArray(16)
    private var size = 0
    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }
    fun build(): LongArray = values.copyOf(size)
}

private class IntArrayBuilder {
    private var values = IntArray(16)
    private var size = 0
    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }
    fun build(): IntArray = values.copyOf(size)
}
