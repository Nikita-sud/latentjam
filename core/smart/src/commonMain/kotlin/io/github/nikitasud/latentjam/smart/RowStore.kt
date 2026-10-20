/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/**
 * One stored vector, read where it lives — a heap array, or a row of a memory-mapped file — instead of copied out.
 * Element d is exactly the float that was stored.
 */
public interface StoredVectorRow {
    public val size: Int

    public operator fun get(d: Int): Float

    /** Writes the row into [out] from [offset]. */
    public fun copyInto(out: FloatArray, offset: Int = 0)

    /** A fresh array holding the row. */
    public fun toFloatArray(): FloatArray = FloatArray(size).also { copyInto(it) }
}

/** A row that is a heap array; readers may use [values] directly. */
public class ArrayVectorRow(public val values: FloatArray) : StoredVectorRow {
    override val size: Int get() = values.size
    override fun get(d: Int): Float = values[d]
    override fun copyInto(out: FloatArray, offset: Int) {
        values.copyInto(out, offset)
    }
}

/**
 * Where an [InMemoryVectorIndex] keeps its rows. The heap store keeps the arrays it is given (what the index always
 * did); a platform may keep them off the Java heap instead (Android maps a file), so that a 10,000-song library's
 * 55 MB of vectors do not sit on a 48 MB heap. Not thread-safe: the index's callers serialize access.
 */
public interface RowStore {
    public val dim: Int

    /** Rows written so far, including ones the index no longer refers to. */
    public val rowCount: Int

    /**
     * Stores [values] as a new row and returns its number. The store may keep [values] itself. A row once written is
     * never written again, so a [StoredVectorRow] handed out keeps reading the same floats (a snapshot holds them).
     */
    public fun append(values: FloatArray): Int

    /** Forgets row [row]'s contents (the index no longer refers to it). */
    public fun release(row: Int) {}

    public fun row(row: Int): StoredVectorRow

    /** The row's heap array when this store keeps one, else null (then read through [row]). */
    public fun heapArray(row: Int): FloatArray? = null

    /**
     * Renumbers the store to only [rows], in that order: afterwards row `i` is what row `rows[i]` was. Lets an index
     * reclaim the space of replaced and removed rows; rows handed out before keep reading what they read.
     */
    public fun compact(rows: IntArray)

    public fun clear()

    /**
     * No more rows will be appended: a store may give back what it needs only for writing (Android's mapped store
     * closes its file). Rows stay readable.
     */
    public fun seal() {}
}

/** Makes the scratch [RowStore]s a snapshot build keeps its own rows in: off the Java heap where the platform can. */
public fun interface RowStoreFactory {
    public fun create(dim: Int, name: String): RowStore

    public companion object {
        public val Heap: RowStoreFactory = RowStoreFactory { dim, _ -> HeapRowStore(dim) }
    }
}

/** The Java-heap [RowStore]: the arrays themselves. */
public class HeapRowStore(override val dim: Int) : RowStore {
    private val rows = ArrayList<FloatArray?>()

    override val rowCount: Int get() = rows.size

    override fun append(values: FloatArray): Int {
        rows += values
        return rows.lastIndex
    }

    override fun release(row: Int) {
        rows[row] = null
    }

    override fun row(row: Int): StoredVectorRow = ArrayVectorRow(checkNotNull(rows[row]) { "row $row was released" })

    override fun heapArray(row: Int): FloatArray? = rows[row]

    override fun compact(rows: IntArray) {
        val kept = ArrayList<FloatArray?>(rows.size)
        for (r in rows) kept += this.rows[r]
        this.rows.clear()
        this.rows.addAll(kept)
    }

    override fun clear() {
        rows.clear()
    }
}
