/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.sqrt

/**
 * Randomized subspace iteration: enough of a PCA to feed t-SNE, and nothing more.
 *
 * A full eigendecomposition of a 1344 x 1344 covariance matrix is thousands of lines and
 * milliseconds of no consequence — the layout is computed once per library change. This finds the
 * leading [components] directions by repeatedly multiplying a random basis through the covariance
 * and re-orthonormalizing, which converges quickly because embedding spectra decay fast.
 *
 * Input rows must already be mean-centered; the caller centers before calling.
 */
internal object Pca {

    /** Subspace iterations. Four is past convergence for embedding covariance spectra. */
    private const val ITERATIONS = 4

    /**
     * @param rows row-major `n x dim`, mean-centered
     * @return row-major `n x components`
     */
    fun reduce(rows: FloatArray, n: Int, dim: Int, components: Int, seed: Int): FloatArray {
        require(n > 0 && dim > 0) { "Empty matrix has no principal components" }
        val k = components.coerceAtMost(dim).coerceAtMost(n)

        // Deterministic basis: a seeded xorshift rather than kotlin.random, so the same library
        // yields the same axes on every platform and every run.
        var state = (seed.toLong() and 0xFFFFFFFFL) or 1L
        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return ((state ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat() * 2f - 1f
        }

        var basis = FloatArray(dim * k) { next() }
        orthonormalize(basis, dim, k)

        val scratch = FloatArray(n * k)
        repeat(ITERATIONS) {
            // scratch = rows * basis
            multiply(rows, n, dim, basis, k, scratch)
            // basis = rows^T * scratch
            val next = FloatArray(dim * k)
            for (i in 0 until n) {
                for (d in 0 until dim) {
                    val value = rows[i * dim + d]
                    if (value == 0f) continue
                    for (c in 0 until k) next[d * k + c] += value * scratch[i * k + c]
                }
            }
            basis = next
            orthonormalize(basis, dim, k)
        }

        val out = FloatArray(n * k)
        multiply(rows, n, dim, basis, k, out)
        return out
    }

    private fun multiply(
        rows: FloatArray,
        n: Int,
        dim: Int,
        basis: FloatArray,
        k: Int,
        out: FloatArray,
    ) {
        out.fill(0f)
        for (i in 0 until n) {
            val rowBase = i * dim
            val outBase = i * k
            for (d in 0 until dim) {
                val value = rows[rowBase + d]
                if (value == 0f) continue
                val basisBase = d * k
                for (c in 0 until k) out[outBase + c] += value * basis[basisBase + c]
            }
        }
    }

    /** Modified Gram-Schmidt over the columns of a `dim x k` matrix. */
    private fun orthonormalize(basis: FloatArray, dim: Int, k: Int) {
        for (c in 0 until k) {
            for (prev in 0 until c) {
                var dot = 0f
                for (d in 0 until dim) dot += basis[d * k + c] * basis[d * k + prev]
                for (d in 0 until dim) basis[d * k + c] -= dot * basis[d * k + prev]
            }
            var norm = 0f
            for (d in 0 until dim) {
                val value = basis[d * k + c]
                norm += value * value
            }
            norm = sqrt(norm)
            if (norm < 1e-6f) {
                // A collapsed direction carries no variance; park it on an axis so the basis stays
                // full rank instead of propagating NaN through the projection.
                for (d in 0 until dim) basis[d * k + c] = if (d == c % dim) 1f else 0f
            } else {
                for (d in 0 until dim) basis[d * k + c] /= norm
            }
        }
    }
}
