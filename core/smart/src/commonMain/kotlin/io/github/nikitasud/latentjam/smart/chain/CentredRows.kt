/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.RowStore

/**
 * A centred, renormalised space of the snapshot, read row by row instead of held as one matrix.
 *
 * At 10,000 songs the three centred matrices were 69 MB, the largest thing on a 48 MB heap and twice the size of the
 * indexes they were computed from. Every value here is the float such a matrix held: [RecomputedCentredRows] repeats
 * the build's own operations for each element (`(raw − mean) × rowScale`, in that order), [IndexedCentredRows] stores
 * one row per distinct vector, and every sum runs over d in ascending order as before. So every cosine, every
 * ranking and every queue is the same, bit for bit (SmartSnapshotMatrixFreeTest).
 *
 * A row without a vector ([has] false) reads as zeros, as the matrix's untouched rows did.
 */
internal abstract class CentredRows(val size: Int, val dim: Int, val has: BooleanArray?) {

    fun present(row: Int): Boolean = has == null || has[row]

    /** Writes [row]'s centred floats into [out] from [offset]; zeros for a row without a vector. */
    abstract fun copyRow(row: Int, out: FloatArray, offset: Int = 0)

    fun row(row: Int): FloatArray = FloatArray(dim).also { copyRow(row, it) }

    /** `Σ c[a][d] · c[b][d]`, d ascending: the matrix loop's float. */
    abstract fun dot(rowA: Int, rowB: Int): Float

    /** `Σ query[d] · c[row][d]`, d ascending. */
    abstract fun dotWith(query: FloatArray, row: Int): Float

    /**
     * `out[rows[i]] = dot(reference, rows[i])` for the first [count] of [rows], through [kernel] where the whole-matrix
     * call used it (2,048 rows or more): rows are centred [BLOCK] at a time into one reused buffer, so the kernel sees
     * the same floats per row as it did in the matrix. Portable and platform kernels give each row's sum in d order.
     */
    fun dots(reference: Int, rows: IntArray, count: Int, out: FloatArray, kernel: BatchDotProducts?) {
        if (count <= 0) return
        val query = row(reference)
        val blockRows = minOf(BLOCK, count)
        val buffer = FloatArray(blockRows * dim)
        val local = IntArray(blockRows) { it }
        val blockOut = FloatArray(blockRows)
        val useKernel = kernel != null && count >= KERNEL_MIN_ROWS && dim >= 16
        var start = 0
        var kernelWorks = useKernel
        while (start < count) {
            val n = minOf(blockRows, count - start)
            for (j in 0 until n) copyRow(rows[start + j], buffer, j * dim)
            val done = kernelWorks && kernel!!.compute(buffer, dim, query, 0, local, n, blockOut)
            if (!done) {
                kernelWorks = false
                batchDots(buffer, dim, query, 0, local, n, blockOut)
            }
            for (j in 0 until n) out[rows[start + j]] = blockOut[j]
            start += n
        }
    }

    companion object {
        /** Rows centred per kernel call: 256 × 960 floats, a 1 MB buffer instead of the whole library. */
        const val BLOCK = 256

        /** The whole-matrix call took the platform kernel from this many rows (SmartSnapshot.batchDots). */
        const val KERNEL_MIN_ROWS = 2_048
    }
}

/** A dense matrix of centred rows (kept for the hub-penalty anchors and for tests). */
internal class MatrixCentredRows(
    private val matrix: FloatArray,
    dim: Int,
    has: BooleanArray? = null,
) : CentredRows(matrix.size / dim, dim, has) {
    override fun copyRow(row: Int, out: FloatArray, offset: Int) {
        matrix.copyInto(out, offset, row * dim, (row + 1) * dim)
    }

    override fun dot(rowA: Int, rowB: Int): Float {
        var dot = 0f
        val baseA = rowA * dim
        val baseB = rowB * dim
        for (d in 0 until dim) dot += matrix[baseA + d] * matrix[baseB + d]
        return dot
    }

    override fun dotWith(query: FloatArray, row: Int): Float {
        var dot = 0f
        val base = row * dim
        for (d in 0 until dim) dot += query[d] * matrix[base + d]
        return dot
    }
}

/**
 * Centred rows recomputed from the raw rows they were built from: element d of row r is
 * `(raw[r][d] − mean[d]) × scales[r]`, where `raw[r][d]` is [ScaledRows]'s own float. Holds the corpus mean and one
 * scale per row; the vectors stay where they already are (the index's arrays).
 */
internal class RecomputedCentredRows(
    private val raw: ScaledRows,
    private val mean: FloatArray,
    private val scales: FloatArray,
    has: BooleanArray?,
) : CentredRows(raw.size, raw.dim, has) {

    private fun at(row: Int, d: Int): Float = (raw[row, d] - mean[d]) * scales[row]

    override fun copyRow(row: Int, out: FloatArray, offset: Int) {
        if (!present(row)) {
            out.fill(0f, offset, offset + dim)
            return
        }
        raw.copyInto(row, out, offset)
        val scale = scales[row]
        for (d in 0 until dim) out[offset + d] = (out[offset + d] - mean[d]) * scale
    }

    override fun dot(rowA: Int, rowB: Int): Float {
        if (!present(rowA) || !present(rowB)) return zeroDot()
        var dot = 0f
        for (d in 0 until dim) dot += at(rowA, d) * at(rowB, d)
        return dot
    }

    override fun dotWith(query: FloatArray, row: Int): Float {
        if (!present(row)) {
            var dot = 0f
            for (d in 0 until dim) dot += query[d] * 0f
            return dot
        }
        var dot = 0f
        for (d in 0 until dim) dot += query[d] * at(row, d)
        return dot
    }

    /** What the matrix loop summed for an absent (all-zero) row: 0 · x, d times. */
    private fun zeroDot(): Float = 0f
}

/**
 * Centred rows stored once per distinct vector: [index] maps a row to its stored row, −1 for a row without one.
 * Equal input vectors give equal centred rows (same floats, same operations), so many songs of one artist cost one row.
 * The rows live in [store] (off the Java heap on Android); a row is read where it is, or copied when it is not an array.
 */
internal class IndexedCentredRows(
    private val store: RowStore,
    dim: Int,
    private val index: IntArray,
    has: BooleanArray,
) : CentredRows(index.size, dim, has) {
    /** Distinct rows actually held. */
    val storedRows: Int get() = store.rowCount

    private fun values(u: Int): FloatArray = store.heapArray(u) ?: store.row(u).toFloatArray()

    override fun copyRow(row: Int, out: FloatArray, offset: Int) {
        val u = index[row]
        if (u < 0) {
            out.fill(0f, offset, offset + dim)
            return
        }
        store.row(u).copyInto(out, offset)
    }

    override fun dot(rowA: Int, rowB: Int): Float {
        val a = index[rowA]
        val b = index[rowB]
        if (a < 0 || b < 0) return 0f
        val va = values(a)
        val vb = if (b == a) va else values(b)
        var dot = 0f
        for (d in 0 until dim) dot += va[d] * vb[d]
        return dot
    }

    override fun dotWith(query: FloatArray, row: Int): Float {
        val u = index[row]
        var dot = 0f
        if (u < 0) {
            for (d in 0 until dim) dot += query[d] * 0f
            return dot
        }
        val v = values(u)
        for (d in 0 until dim) dot += query[d] * v[d]
        return dot
    }
}
