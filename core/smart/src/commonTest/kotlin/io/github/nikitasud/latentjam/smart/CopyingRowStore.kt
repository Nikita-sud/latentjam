/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

/** Keeps copies; never exposes them as heap arrays. Compaction builds a new list and leaves old copies as they were. */
internal class CopyingRowStore(override val dim: Int) : RowStore {
    var rows = ArrayList<FloatArray?>()
    var compactions = 0
    override val rowCount: Int get() = rows.size
    override fun append(values: FloatArray): Int {
        rows += values.copyOf()
        return rows.lastIndex
    }
    override fun release(row: Int) {
        rows[row] = null
    }
    override fun row(row: Int): StoredVectorRow {
        val values = checkNotNull(rows[row])
        return object : StoredVectorRow {
            override val size: Int get() = values.size
            override fun get(d: Int): Float = values[d]
            override fun copyInto(out: FloatArray, offset: Int) {
                values.copyInto(out, offset)
            }
        }
    }
    override fun compact(rows: IntArray) {
        compactions++
        this.rows = ArrayList(rows.map { this.rows[it] })
    }
    override fun clear() {
        rows = ArrayList()
    }
}
