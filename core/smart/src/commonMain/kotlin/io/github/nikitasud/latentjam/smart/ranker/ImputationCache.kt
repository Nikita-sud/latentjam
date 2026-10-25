/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.IndexStore
import io.github.nikitasud.latentjam.smart.RowStore
import io.github.nikitasud.latentjam.smart.RowStoreFactory
import io.github.nikitasud.latentjam.smart.StoredIndexSnapshot
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The audio tagger's output per track, computed once from the track's stored vector, with no time budget, and kept
 * beside the vector and e (audits 04 #2 / 05 #5). Plans only read it, so the imputation a plan sees no longer depends
 * on how fast the phone was at that moment.
 *
 * Each entry is tied to the exact vector it was computed from (a 64-bit fingerprint of the vector's bits), so a
 * re-analysed track is recomputed, never served a stale result. Persisted under [version] (encoder + tagger), so a new
 * encoder or head discards the file.
 *
 * Stored per track: the descriptor (float32, as [AudioTagger.Imputed.descriptor] is) and the class probabilities,
 * each float64 split exactly into three 22-bit integer chunks held as floats, so the read-back result is bit-identical
 * to [AudioTagger.impute]. Held in memory in that same stored form and decoded per lookup (a plan reads its seed and
 * pool, not the library): a decoded entry cost ~0.6 KB more per track, and a launch decoded every stored row. The rows
 * live in a [RowStore] from [rowStores] (Android: a mapped file, off the Java heap); the heap keeps only id → row.
 */
public class ImputationCache(
    private val tagger: AudioTagger,
    private val store: IndexStore?,
    private val version: String,
    private val rowStores: RowStoreFactory = RowStoreFactory.Heap,
) {
    /** [row] numbers the stored row in [rows] ([encode], or [noOutput] when the head gave nothing). */
    private class Entry(val fingerprint: Long, val row: Int)

    private val width: Int get() = tagger.descriptorDim + 3 * tagger.classCount
    private val rows: RowStore by lazy { rowStores.create(width, "imputation") }

    private val mutex = Mutex()
    private val entries = HashMap<TrackId, Entry>()
    private var restored = false
    private var dirty = false

    /** The tagger's output for [id] whose vector is [vector] when cached; null when not computed yet. */
    internal suspend fun cached(id: TrackId, vector: FloatArray): Lookup = mutex.withLock {
        restore()
        val e = entries[id]
        if (e != null && e.fingerprint == fingerprint(vector)) Lookup(true, decoded(rowOf(e.row))) else Lookup(false, null)
    }

    internal class Lookup(val hit: Boolean, val imputed: AudioTagger.Imputed?)

    /** Computes and keeps the output for [vector] (the plan-time fallback, or the backfill). */
    internal suspend fun compute(id: TrackId, vector: FloatArray): AudioTagger.Imputed? {
        val out = tagger.impute(vector)
        mutex.withLock {
            val old = entries.put(id, Entry(fingerprint(vector), rows.append(out?.let(::encode) ?: noOutput())))
            if (old != null) rows.release(old.row)
            dirty = true
        }
        return out
    }

    /**
     * Fills every track of [ids] whose vector [vectorOf] returns and whose output is missing or stale; then persists.
     * Returns how many were computed. Runs in the background after indexing; no budget.
     */
    public suspend fun backfill(ids: Collection<TrackId>, vectorOf: suspend (TrackId) -> FloatArray?): Int {
        var computed = 0
        for (id in ids) {
            val v = vectorOf(id) ?: continue
            if (cached(id, v).hit) continue
            compute(id, v)
            computed++
        }
        persist()
        return computed
    }

    /** Writes the cache when it changed since the last write. */
    public suspend fun persist() {
        val target = store ?: return
        // Written under the lock: the snapshot's rows are a view (one row copied at a time while the store reads it).
        mutex.withLock {
            if (!dirty) return
            dirty = false
            val ids = HashMap<TrackId, String>(entries.size * 2)
            for ((id, e) in entries) ids[id] = e.fingerprint.toULong().toString(16)
            runCatching { target.saveSnapshot(version, StoredIndexSnapshot(entries = storedView, identities = ids)) }
        }
    }

    public suspend fun size(): Int = mutex.withLock { restore(); entries.size }

    private suspend fun restore() {
        if (restored) return
        restored = true
        val target = store ?: return
        // Streamed one row at a time into the row store: a launch never holds the whole file as arrays.
        val loaded = runCatching {
            target.loadRows(version) { id, row, identity ->
                val fp = identity?.toULongOrNull(16)?.toLong()
                if (fp != null && row.size == width) entries[id] = Entry(fp, rows.append(row))
            }
        }
        if (loaded.getOrNull() == null) {
            println("SMART: tagger outputs not restored (${loaded.exceptionOrNull()?.let { it::class.simpleName } ?: "no usable file"})")
            entries.clear()
            rows.clear()
        } else {
            println("SMART: tagger outputs restored for ${entries.size} tracks")
        }
    }

    private fun rowOf(row: Int): FloatArray = rows.heapArray(row) ?: rows.row(row).toFloatArray()

    /** The entries as the store reads them: each value a stored row (a heap array, or one row copied from the file). */
    private val storedView: Map<TrackId, FloatArray> = object : AbstractMap<TrackId, FloatArray>() {
        override val size: Int get() = this@ImputationCache.entries.size
        override fun containsKey(key: TrackId): Boolean = key in this@ImputationCache.entries
        override fun get(key: TrackId): FloatArray? = this@ImputationCache.entries[key]?.let { rowOf(it.row) }
        override val entries: Set<Map.Entry<TrackId, FloatArray>>
            get() = object : AbstractSet<Map.Entry<TrackId, FloatArray>>() {
                override val size: Int get() = this@ImputationCache.entries.size
                override fun iterator(): Iterator<Map.Entry<TrackId, FloatArray>> {
                    val source = this@ImputationCache.entries.entries.iterator()
                    return object : Iterator<Map.Entry<TrackId, FloatArray>> {
                        override fun hasNext(): Boolean = source.hasNext()
                        override fun next(): Map.Entry<TrackId, FloatArray> {
                            val (id, e) = source.next()
                            return StoredEntry(id, rowOf(e.row))
                        }
                    }
                }
            }
    }

    private class StoredEntry(override val key: TrackId, override val value: FloatArray) : Map.Entry<TrackId, FloatArray>

    /**
     * The head gave nothing for this vector (wrong size, zero, non-finite): kept as a row starting with [NO_OUTPUT],
     * a value a unit-length descriptor never holds (finite, so any store keeps it).
     */
    private fun noOutput(): FloatArray = FloatArray(tagger.descriptorDim + 3 * tagger.classCount).also { it[0] = NO_OUTPUT }

    /** The output a stored row holds: null for a [noOutput] row. Rows are size-checked on restore, so [decode] succeeds. */
    private fun decoded(row: FloatArray): AudioTagger.Imputed? = if (row[0] == NO_OUTPUT) null else decode(row)

    private fun encode(i: AudioTagger.Imputed): FloatArray {
        val d = i.descriptor
        val p = i.probabilities
        val out = FloatArray(d.size + 3 * p.size)
        d.copyInto(out)
        for (c in p.indices) {
            val bits = p[c].toRawBits()
            out[d.size + 3 * c] = (bits ushr 42).toInt().toFloat()
            out[d.size + 3 * c + 1] = ((bits ushr 21) and MASK21).toInt().toFloat()
            out[d.size + 3 * c + 2] = (bits and MASK21).toInt().toFloat()
        }
        return out
    }

    private fun decode(row: FloatArray): AudioTagger.Imputed? {
        val classes = tagger.classCount
        val dim = row.size - 3 * classes
        if (dim <= 0) return null
        val p = DoubleArray(classes) { c ->
            val hi = row[dim + 3 * c].toLong()
            val mid = row[dim + 3 * c + 1].toLong()
            val lo = row[dim + 3 * c + 2].toLong()
            Double.fromBits((hi shl 42) or (mid shl 21) or lo)
        }
        return tagger.fromProbabilities(p, row.copyOf(dim))
    }

    public companion object {
        private const val MASK21 = (1L shl 21) - 1
        private const val NO_OUTPUT = 2f

        /** FNV-1a over the vector's float bits: equal only for the same vector. */
        internal fun fingerprint(v: FloatArray): Long {
            var h = -0x340d631b7bdddcdbL
            for (x in v) {
                h = (h xor x.toRawBits().toLong()) * 0x100000001b3L
            }
            return h xor v.size.toLong()
        }
    }
}
