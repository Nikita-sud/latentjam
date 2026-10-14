// SPDX-License-Identifier: Apache-2.0
package io.github.nikitasud.latentjam.smart.cluster

import org.junit.Test
import org.junit.Assert.*

/** JNI/packaging contracts only. This is not a normal-heap memory/performance benchmark. */
class MapNativeDeviceTest {
    @Test fun deterministicFiniteCoordinatesAndCooperativeCancellation() {
        val n = 80
        val dim = 7
        val rows = FloatArray(n * dim) { ((it * 19 % 101) - 50) / 50f }
        val first = FloatArray(n * 2)
        val second = FloatArray(n * 2)
        assertEquals(0, nativeMapEmbed(rows, n, dim, null, first, MapCancellation { true }))
        assertTrue(first.all(Float::isFinite))
        assertEquals(0, nativeMapEmbed(rows, n, dim, null, second, MapCancellation { true }))
        assertArrayEquals(first, second, 0f)
        for (stopAfter in listOf(0, 3, 95)) {
            var checks = 0
            val sentinel = FloatArray(n * 2) { 123f }
            assertEquals(1, nativeMapEmbed(rows, n, dim, first, sentinel,
                MapCancellation { checks++ < stopAfter }))
            assertTrue(sentinel.all { it == 123f })
        }
        assertEquals(2, nativeMapEmbed(FloatArray(3), n, dim, null, first, MapCancellation { true }))
    }
}
