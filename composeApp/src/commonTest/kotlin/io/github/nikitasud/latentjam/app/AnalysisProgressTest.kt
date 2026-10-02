/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisProgressTest {

    @Test
    fun aPassAfterAnUpgradeStartsFromTheTracksAlreadyAnalysed() {
        // 568 fingerprints survive the upgrade; the re-enrichment only re-encodes text vectors.
        val progress = AnalysisProgress(total = 10_000)
        progress.started(missing = 10_000 - 568)
        assertEquals(568, progress.analysed)
    }

    @Test
    fun visitingTracksAlreadyAnalysedNeverMovesItAndNewOnesAddUp() {
        val progress = AnalysisProgress(total = 100)
        progress.started(missing = 60)
        progress.visited(missingBefore = 0) // a chunk of tracks fingerprinted before this pass
        assertEquals(40, progress.analysed)
        progress.visited(missingBefore = 8)
        progress.visited(missingBefore = 3)
        assertEquals(51, progress.analysed)
    }

    @Test
    fun itNeverDropsAndEndsAtTheWholeLibrary() {
        val progress = AnalysisProgress(total = 40)
        progress.started(missing = 25)
        var last = progress.analysed
        // Chunks of eight in a spread order: some already analysed, some not, 25 missing in all.
        for (missingBefore in listOf(3, 0, 8, 2, 0, 5, 7)) {
            progress.visited(missingBefore)
            assertTrue(progress.analysed >= last)
            last = progress.analysed
        }
        assertEquals(40, progress.analysed)
    }

    @Test
    fun anUnknownStartIsZeroAndNothingOverflowsTheLibrary() {
        val progress = AnalysisProgress(total = 10)
        assertEquals(0, progress.analysed)
        progress.started(missing = 0)
        progress.visited(missingBefore = 4)
        assertEquals(10, progress.analysed)
    }
}
