/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.exp
import kotlin.math.ln

/**
 * Exact t-distributed stochastic neighbour embedding.
 *
 * Chosen over UMAP, PaCMAP, PCA and a kNN force layout by measurement on the real library, not by
 * reputation: UMAP and PaCMAP were both significantly worse under a paired bootstrap over
 * hand-made playlists, and PCA was far worse. See docs/map-page.md section 3.
 *
 * O(n^2) rather than Barnes-Hut. At library scale the full pair sweep is a couple of seconds, and
 * the layout is computed once per library change and cached, so the tree would add complexity with
 * no user-visible benefit.
 */
internal object Tsne {

    /** Validated on the real library. Changing this re-shapes every existing map. */
    const val PERPLEXITY: Float = 20f

    const val ITERATIONS: Int = 1000

    /** Iterations during which P is inflated, which opens gaps between clusters early. */
    private const val EXAGGERATION_ITERATIONS = 250
    private const val EXAGGERATION = 12f

    private const val LEARNING_RATE = 200f
    private const val EARLY_MOMENTUM = 0.5f
    private const val LATE_MOMENTUM = 0.8f
    private const val MOMENTUM_SWITCH = 250

    /** Binary-search bounds for the per-point bandwidth that hits [PERPLEXITY]. */
    private const val PERPLEXITY_STEPS = 60
    private const val PERPLEXITY_TOLERANCE = 1e-5f

    /**
     * @param rows row-major `n x dim`; rows are unit-normalized by the caller so squared euclidean
     *   distance is a monotone function of cosine distance
     * @param initial optional row-major `n x 2` warm start
     * @return row-major `n x 2`
     */
    fun embed(
        rows: FloatArray,
        n: Int,
        dim: Int,
        seed: Int,
        initial: FloatArray? = null,
    ): FloatArray {
        require(n > 0 && dim > 0) { "Cannot embed an empty matrix" }
        require(rows.size == n * dim) {
            "rows.size was ${rows.size}, expected n * dim = ${n * dim} (n=$n, dim=$dim)"
        }
        if (n < 3) return FloatArray(n * 2)

        val p = affinities(rows, n, dim)

        var state = (seed.toLong() and 0xFFFFFFFFL) or 1L
        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return ((state ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat() * 2f - 1f
        }

        val y = FloatArray(n * 2)
        if (initial != null && initial.size == n * 2) {
            initial.copyInto(y)
            // A warm start still needs a nudge, or points that start exactly coincident have zero
            // gradient between them and never separate.
            for (i in y.indices) y[i] += next() * 1e-3f
        } else {
            for (i in y.indices) y[i] = next() * 1e-2f
        }

        val gains = FloatArray(n * 2) { 1f }
        val velocity = FloatArray(n * 2)
        val grad = FloatArray(n * 2)
        val q = FloatArray(n * n)

        for (iteration in 0 until ITERATIONS) {
            val scale = if (iteration < EXAGGERATION_ITERATIONS) EXAGGERATION else 1f

            // Student-t affinities in the embedding, and their normalizer.
            var sum = 0f
            for (i in 0 until n) {
                q[i * n + i] = 0f
                for (j in i + 1 until n) {
                    val dx = y[i * 2] - y[j * 2]
                    val dy = y[i * 2 + 1] - y[j * 2 + 1]
                    val value = 1f / (1f + dx * dx + dy * dy)
                    q[i * n + j] = value
                    q[j * n + i] = value
                    sum += 2f * value
                }
            }
            if (sum <= 0f) sum = 1e-12f

            grad.fill(0f)
            for (i in 0 until n) {
                for (j in 0 until n) {
                    if (i == j) continue
                    val qij = q[i * n + j]
                    val force = (scale * p[i * n + j] - qij / sum) * qij
                    grad[i * 2] += 4f * force * (y[i * 2] - y[j * 2])
                    grad[i * 2 + 1] += 4f * force * (y[i * 2 + 1] - y[j * 2 + 1])
                }
            }

            val momentum = if (iteration < MOMENTUM_SWITCH) EARLY_MOMENTUM else LATE_MOMENTUM
            for (i in y.indices) {
                // Jacobs' adaptive gains: grow while the gradient keeps its sign, shrink when it
                // flips. Standard t-SNE, and without it 1000 iterations is not enough to settle.
                gains[i] = if ((grad[i] > 0f) != (velocity[i] > 0f)) {
                    gains[i] + 0.2f
                } else {
                    (gains[i] * 0.8f).coerceAtLeast(0.01f)
                }
                velocity[i] = momentum * velocity[i] - LEARNING_RATE * gains[i] * grad[i]
                y[i] += velocity[i]
            }

            // Recenter every pass so the embedding cannot drift off toward infinity.
            var mx = 0f
            var my = 0f
            for (i in 0 until n) {
                mx += y[i * 2]
                my += y[i * 2 + 1]
            }
            mx /= n
            my /= n
            for (i in 0 until n) {
                y[i * 2] -= mx
                y[i * 2 + 1] -= my
            }
        }
        return y
    }

    /**
     * Symmetric joint probabilities, one bandwidth per point chosen so its conditional
     * distribution has the target perplexity.
     */
    private fun affinities(rows: FloatArray, n: Int, dim: Int): FloatArray {
        val distances = FloatArray(n * n)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var sum = 0f
                val a = i * dim
                val b = j * dim
                for (d in 0 until dim) {
                    val delta = rows[a + d] - rows[b + d]
                    sum += delta * delta
                }
                distances[i * n + j] = sum
                distances[j * n + i] = sum
            }
        }

        val p = FloatArray(n * n)
        val target = ln(PERPLEXITY)
        val row = FloatArray(n)
        for (i in 0 until n) {
            var low = 0f
            var high = Float.MAX_VALUE
            var beta = 1f
            // A real loop with a real break, not `repeat { return@repeat }`: `return@repeat` only
            // returns from that one lambda invocation (a `continue`, not a `break`), so it used to
            // skip the write on the very iteration where convergence was first detected -- and
            // because entropy is monotone in beta, every later iteration re-converged too, so the
            // row was stuck on its last *pre-convergence* estimate for the rest of the search. In
            // the degenerate case where a point is equidistant from every other point (duplicate
            // embeddings, or small n), entropy doesn't depend on beta at all, convergence is
            // detected on step 0, and the write never happened even once -- the row stayed all
            // zero forever. Writing `p[i * n + j]` unconditionally on every step, before the
            // convergence check, means a `break` can never discard a result.
            perplexitySearch@ for (step in 0 until PERPLEXITY_STEPS) {
                var sum = 0f
                var entropySum = 0f
                for (j in 0 until n) {
                    if (i == j) {
                        row[j] = 0f
                        continue
                    }
                    val value = exp(-distances[i * n + j] * beta)
                    row[j] = value
                    sum += value
                    entropySum += distances[i * n + j] * value
                }
                if (sum <= 0f) sum = 1e-12f
                val entropy = ln(sum) + beta * entropySum / sum
                val error = entropy - target
                for (j in 0 until n) p[i * n + j] = row[j] / sum
                if (error > 0f) {
                    low = beta
                    beta = if (high == Float.MAX_VALUE) beta * 2f else (beta + high) / 2f
                } else {
                    high = beta
                    beta = (beta + low) / 2f
                }
                if (error < PERPLEXITY_TOLERANCE && error > -PERPLEXITY_TOLERANCE) break@perplexitySearch
            }
        }

        // Symmetrize and normalize to a joint distribution, with a floor so no pair contributes a
        // zero gradient.
        val out = FloatArray(n * n)
        for (i in 0 until n) {
            for (j in 0 until n) {
                out[i * n + j] = ((p[i * n + j] + p[j * n + i]) / (2f * n)).coerceAtLeast(1e-12f)
            }
        }
        return out
    }
}
