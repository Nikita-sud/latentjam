/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioCoverageTest {
    @Test
    fun describesWhatThePlanCouldRead() {
        val c = AudioCoverage(true, false, 12, -1, 50, setOf("vectors", "R1"))
        assertEquals("seed=v,- pool=12/40v r1=50 timeout=R1+vectors", c.describe(40))
        assertEquals("seed=-,- r1=0", AudioCoverage.NONE.describe(40))
    }
}
