/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.cluster

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class LayoutAnchorTest {

    // A layout that is the reference rotated by 40 degrees must come back essentially on top of it.
    @Test
    fun `align undoes a rotation`() {
        val n = 25
        val reference = FloatArray(n * 2) { ((it * 13) % 17).toFloat() - 8f }
        val angle = 0.698f
        val rotated = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = reference[i * 2]
            val y = reference[i * 2 + 1]
            rotated[i * 2] = x * cos(angle) - y * sin(angle)
            rotated[i * 2 + 1] = x * sin(angle) + y * cos(angle)
        }
        val aligned = LayoutAnchor.align(rotated, reference, n)
        assertTrue(rmse(aligned, reference) < 1e-3f, "rmse was ${rmse(aligned, reference)}")
    }

    // Mirroring is the failure that matters most: it leaves every learned location wrong while
    // every fidelity metric stays identical.
    @Test
    fun `align undoes a reflection`() {
        val n = 25
        val reference = FloatArray(n * 2) { ((it * 7) % 11).toFloat() - 5f }
        val mirrored = FloatArray(n * 2)
        for (i in 0 until n) {
            mirrored[i * 2] = -reference[i * 2]
            mirrored[i * 2 + 1] = reference[i * 2 + 1]
        }
        val aligned = LayoutAnchor.align(mirrored, reference, n)
        assertTrue(rmse(aligned, reference) < 1e-3f, "rmse was ${rmse(aligned, reference)}")
    }

    private fun rmse(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val delta = a[i] - b[i]
            sum += delta * delta
        }
        return sqrt(sum / a.size)
    }
}
