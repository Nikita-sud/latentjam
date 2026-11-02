/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class WindowCoverageTest {
    private val size = 320_000

    @Test
    fun aWindowWithinTheSlackIsUsedAsDecoded() {
        assertEquals(WindowCoverage.Use.AS_DECODED, WindowCoverage.use(size, size, mayBeShort = false))
        assertEquals(WindowCoverage.Use.AS_DECODED, WindowCoverage.use(size - 3_200, size, mayBeShort = false))
        assertEquals(WindowCoverage.Use.REPEATED, WindowCoverage.use(size - 3_201, size, mayBeShort = false))
    }

    @Test
    fun aLongTracksWindowUnderHalfIsDroppedAndAShortTracksIsRepeated() {
        assertEquals(WindowCoverage.Use.REPEATED, WindowCoverage.use(size / 2, size, mayBeShort = false))
        assertEquals(WindowCoverage.Use.DROPPED, WindowCoverage.use(size / 2 - 1, size, mayBeShort = false))
        assertEquals(WindowCoverage.Use.REPEATED, WindowCoverage.use(1, size, mayBeShort = true))
        assertEquals(WindowCoverage.Use.DROPPED, WindowCoverage.use(0, size, mayBeShort = true))
    }

    @Test
    fun repeatingFillsTheWindowWithTheAudioFromItsStart() {
        val samples = floatArrayOf(1f, 2f, 3f, 0f, 0f, 0f, 0f, 0f)
        WindowCoverage.repeat(samples, 3)
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 1f, 2f, 3f, 1f, 2f), samples)
        val full = floatArrayOf(4f, 5f)
        WindowCoverage.repeat(full, 2)
        assertContentEquals(floatArrayOf(4f, 5f), full)
    }

    @Test
    fun oneWindowTracksAreTheUnknownAndTheShortOnes() {
        assertEquals(true, WindowCoverage.isSingleWindow(null))
        assertEquals(true, WindowCoverage.isSingleWindow(-1))
        assertEquals(true, WindowCoverage.isSingleWindow(10_000))
        assertEquals(false, WindowCoverage.isSingleWindow(10_001))
    }
}
