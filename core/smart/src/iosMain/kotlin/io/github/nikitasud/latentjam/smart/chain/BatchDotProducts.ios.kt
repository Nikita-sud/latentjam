/* Copyright (c) 2026 LatentJam Project; SPDX-License-Identifier: Apache-2.0 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package io.github.nikitasud.latentjam.smart.chain

import io.github.nikitasud.latentjam.smart.IosInferenceRegistry
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned

internal object IosBatchDotProducts : BatchDotProducts {
    override fun compute(
        matrix: FloatArray, dim: Int, query: FloatArray, offset: Int,
        rows: IntArray, count: Int, out: FloatArray,
    ): Boolean {
        val provider = IosInferenceRegistry.current() ?: return false
        require(dim > 0 && offset >= 0 && offset <= query.size - dim)
        require(count in 0..rows.size && matrix.size % dim == 0)
        require(out !== matrix && out !== query)
        val size = matrix.size / dim
        for (i in 0 until count) require(rows[i] in 0 until size && rows[i] < out.size)
        if (count == 0) return true
        return matrix.usePinned { m -> query.usePinned { q -> rows.usePinned { r -> out.usePinned { o ->
            provider.batchDots(
                m.addressOf(0).rawValue.toLong(), dim, q.addressOf(offset).rawValue.toLong(),
                r.addressOf(0).rawValue.toLong(), count, o.addressOf(0).rawValue.toLong(),
            )
        } } } }
    }
}
