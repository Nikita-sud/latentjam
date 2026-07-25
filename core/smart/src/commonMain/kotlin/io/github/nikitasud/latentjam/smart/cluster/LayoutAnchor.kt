/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.sqrt

/**
 * Puts a freshly computed layout back into the orientation of the one it replaces.
 *
 * t-SNE is invariant to rotation and reflection, so two runs over almost the same library can
 * produce the same picture mirrored. Every fidelity metric is identical and every location the
 * reader learned is wrong, which is the worst kind of regression: invisible to tests that only
 * measure quality.
 *
 * Closed-form orthogonal Procrustes over 2-D, which needs no SVD library: the optimal rotation
 * comes straight from the cross-covariance, and reflection is decided by comparing the residual of
 * the rotation against the residual of the mirrored rotation.
 */
internal object LayoutAnchor {

    /**
     * @param candidate row-major `n x 2`, the new layout
     * @param reference row-major `n x 2`, the previous layout for the same rows in the same order
     * @return an aligned copy of [candidate]
     */
    fun align(candidate: FloatArray, reference: FloatArray, n: Int): FloatArray {
        if (n < 2) return candidate.copyOf()

        val (cx, cy) = centroid(candidate, n)
        val (rx, ry) = centroid(reference, n)

        var sxx = 0f
        var sxy = 0f
        var syx = 0f
        var syy = 0f
        var candidateNorm = 0f
        for (i in 0 until n) {
            val ax = candidate[i * 2] - cx
            val ay = candidate[i * 2 + 1] - cy
            val bx = reference[i * 2] - rx
            val by = reference[i * 2 + 1] - ry
            sxx += ax * bx
            sxy += ax * by
            syx += ay * bx
            syy += ay * by
            candidateNorm += ax * ax + ay * ay
        }
        if (candidateNorm < 1e-12f) return candidate.copyOf()

        // Direct rotation: the angle whose cross-covariance trace is maximal.
        val rotationCos = sxx + syy
        val rotationSin = sxy - syx
        val rotationScale = sqrt(rotationCos * rotationCos + rotationSin * rotationSin)

        // Mirrored rotation: the same construction after flipping the candidate's x axis.
        val mirrorCos = sxx - syy
        val mirrorSin = sxy + syx
        val mirrorScale = sqrt(mirrorCos * mirrorCos + mirrorSin * mirrorSin)

        val mirrored = mirrorScale > rotationScale
        val scale = if (mirrored) mirrorScale else rotationScale
        if (scale < 1e-12f) return candidate.copyOf()

        // With the x axis flipped, the alignment score as a function of (cos, sin) is
        // -(cos * mirrorCos + sin * mirrorSin) (the sign flips relative to the direct case because
        // ax enters every cross term negated), so maximizing it takes (cos, sin) in the *opposite*
        // direction from (mirrorCos, mirrorSin), not the same one.
        val c = (if (mirrored) -mirrorCos else rotationCos) / scale
        val s = (if (mirrored) -mirrorSin else rotationSin) / scale

        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val ax0 = candidate[i * 2] - cx
            val ay = candidate[i * 2 + 1] - cy
            val ax = if (mirrored) -ax0 else ax0
            out[i * 2] = (ax * c - ay * s) + rx
            out[i * 2 + 1] = (ax * s + ay * c) + ry
        }
        return out
    }

    private fun centroid(rows: FloatArray, n: Int): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (i in 0 until n) {
            x += rows[i * 2]
            y += rows[i * 2 + 1]
        }
        return (x / n) to (y / n)
    }
}
