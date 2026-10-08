/* Copyright (c) 2026 LatentJam Project; SPDX-License-Identifier: Apache-2.0 */
package io.github.nikitasud.latentjam.smart.chain

internal object AndroidBatchDotProducts : BatchDotProducts {
    private val available by lazy { runCatching { System.loadLibrary("ljq4") }.isSuccess }

    override fun compute(
        matrix: FloatArray, dim: Int, query: FloatArray, offset: Int,
        rows: IntArray, count: Int, out: FloatArray,
    ): Boolean {
        if (!available) return false
        require(dim > 0 && offset >= 0 && offset <= query.size - dim)
        require(count in 0..rows.size && matrix.size % dim == 0)
        require(out !== matrix && out !== query)
        val size = matrix.size / dim
        for (i in 0 until count) require(rows[i] in 0 until size && rows[i] < out.size)
        if (count == 0) return true
        return nativeCompute(matrix, dim, query, offset, rows, count, out)
    }

    private external fun nativeCompute(
        matrix: FloatArray, dim: Int, query: FloatArray, offset: Int,
        rows: IntArray, count: Int, out: FloatArray,
    ): Boolean
}
