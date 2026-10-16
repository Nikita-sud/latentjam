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
 * Input rows must already be mean-centered; [LibraryLayout] centers before calling.
 */
internal object Pca {

    /** Subspace iterations. Four is past convergence for embedding covariance spectra. */
    private const val ITERATIONS = 4

    /**
     * Rows between extra [reduce] cancellation polls. One pass over 10,000 x 1344 rows takes
     * seconds on a phone, so polling only per iteration left multi-second cancellation gaps.
     */
    private const val ROW_POLL = 256

    /**
     * @param rows row-major `n x dim`, mean-centered
     * @param isActive cooperative abort hook, checked once per subspace iteration and every
     *   [ROW_POLL] rows of each matrix pass -- see
     *   [Tsne.embed]'s parameter of the same name for why a plain lambda rather than a coroutines
     *   dependency. [ITERATIONS] is only 4, so this matters far less here than in [Tsne.embed], but
     *   the entry point still honours it rather than silently running to completion regardless.
     * @return row-major `n x components`
     */
    fun reduce(
        rows: FloatArray,
        n: Int,
        dim: Int,
        components: Int,
        seed: Int,
        isActive: () -> Boolean = { true },
    ): FloatArray {
        require(n > 0 && dim > 0) { "Empty matrix has no principal components" }
        val k = components.coerceAtMost(dim).coerceAtMost(n)
        val scratch = FloatArray(n * k)
        val basis = fit(rows, n, dim, k, seed, scratch, isActive) ?: return scratch
        val out = FloatArray(n * k)
        multiply(rows, n, dim, basis, k, out, isActive)
        return out
    }

    /**
     * The leading [components] directions of [rows] as a `dim x k` column basis, without
     * projecting the rows; null when cancelled. [project] applies it to one row at a time, so a
     * basis fitted on a sample can reduce a library whose full matrix is never held.
     */
    fun basis(
        rows: FloatArray,
        n: Int,
        dim: Int,
        components: Int,
        seed: Int,
        isActive: () -> Boolean = { true },
    ): FloatArray? {
        require(n > 0 && dim > 0) { "Empty matrix has no principal components" }
        val k = components.coerceAtMost(dim).coerceAtMost(n)
        return fit(rows, n, dim, k, seed, FloatArray(n * k), isActive)
    }

    /** `out[outOffset until outOffset + k] = row * basis` for a `dim x k` [basis]. */
    fun project(row: FloatArray, dim: Int, basis: FloatArray, k: Int, out: FloatArray, outOffset: Int) {
        for (c in 0 until k) out[outOffset + c] = 0f
        for (d in 0 until dim) {
            val value = row[d]
            if (value == 0f) continue
            val basisBase = d * k
            for (c in 0 until k) out[outOffset + c] += value * basis[basisBase + c]
        }
    }

    /**
     * [reduce] over rows supplied one at a time by [read], which must yield the same values
     * [reduce] would find in its matrix. Each subspace iteration is one fused pass: a row is
     * projected and immediately accumulated into `rows^T * scratch`, in the same row and
     * dimension order as [reduce], so the result is bit-identical while only `n x k` is held.
     * Null when cancelled.
     */
    fun reduceStreamed(
        read: (row: Int, out: FloatArray) -> Unit,
        n: Int,
        dim: Int,
        components: Int,
        seed: Int,
        isActive: () -> Boolean = { true },
    ): FloatArray? {
        require(n > 0 && dim > 0) { "Empty matrix has no principal components" }
        val k = components.coerceAtMost(dim).coerceAtMost(n)
        var basis = initialBasis(dim, k, seed)
        val row = FloatArray(dim)
        val projected = FloatArray(k)
        for (iteration in 0 until ITERATIONS) {
            if (!isActive()) return null
            val next = FloatArray(dim * k)
            for (i in 0 until n) {
                if (i % ROW_POLL == 0 && !isActive()) return null
                read(i, row)
                project(row, dim, basis, k, projected, 0)
                for (d in 0 until dim) {
                    val value = row[d]
                    if (value == 0f) continue
                    for (c in 0 until k) next[d * k + c] += value * projected[c]
                }
            }
            basis = next
            orthonormalize(basis, dim, k)
        }
        val out = FloatArray(n * k)
        for (i in 0 until n) {
            if (i % ROW_POLL == 0 && !isActive()) return null
            read(i, row)
            project(row, dim, basis, k, out, i * k)
        }
        return out
    }

    /** Deterministic start: a seeded xorshift rather than kotlin.random, identical everywhere. */
    private fun initialBasis(dim: Int, k: Int, seed: Int): FloatArray {
        var state = (seed.toLong() and 0xFFFFFFFFL) or 1L
        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return ((state ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat() * 2f - 1f
        }
        return FloatArray(dim * k) { next() }.also { orthonormalize(it, dim, k) }
    }

    private fun fit(
        rows: FloatArray,
        n: Int,
        dim: Int,
        k: Int,
        seed: Int,
        scratch: FloatArray,
        isActive: () -> Boolean,
    ): FloatArray? {
        var basis = initialBasis(dim, k, seed)

        for (iteration in 0 until ITERATIONS) {
            // A stopped fit returns null; [reduce] then returns the projection it last computed,
            // all zeros before the first iteration, rather than paying for one more n × dim × k
            // multiplication. Callers check their own isActive before trusting any result, so a
            // cancelled layout is never persisted or drawn.
            if (!isActive()) return null
            // scratch = rows * basis
            if (!multiply(rows, n, dim, basis, k, scratch, isActive)) return null
            // basis = rows^T * scratch
            val next = FloatArray(dim * k)
            for (i in 0 until n) {
                if (i % ROW_POLL == 0 && !isActive()) return null
                for (d in 0 until dim) {
                    val value = rows[i * dim + d]
                    if (value == 0f) continue
                    for (c in 0 until k) next[d * k + c] += value * scratch[i * k + c]
                }
            }
            basis = next
            orthonormalize(basis, dim, k)
        }
        return basis
    }

    private fun multiply(
        rows: FloatArray,
        n: Int,
        dim: Int,
        basis: FloatArray,
        k: Int,
        out: FloatArray,
        isActive: () -> Boolean,
    ): Boolean {
        out.fill(0f)
        for (i in 0 until n) {
            if (i % ROW_POLL == 0 && !isActive()) return false
            val rowBase = i * dim
            val outBase = i * k
            for (d in 0 until dim) {
                val value = rows[rowBase + d]
                if (value == 0f) continue
                val basisBase = d * k
                for (c in 0 until k) out[outBase + c] += value * basis[basisBase + c]
            }
        }
        return true
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
