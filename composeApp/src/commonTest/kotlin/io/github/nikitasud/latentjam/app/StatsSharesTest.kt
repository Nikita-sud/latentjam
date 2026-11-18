/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class StatsSharesTest {

    @Test
    fun oneFinishedAndSevenSkippedOfEightStartsAddUpToAHundred() {
        // 12.5 and 87.5 both round up: 13 + 0 + 88 was 101.
        assertEquals(Triple(13, 0, 87), listeningShares(1 / 8f, 7 / 8f))
    }

    @Test
    fun theThreeSharesAlwaysAddUpToAHundred() {
        for (starts in 1..120) {
            for (finished in 0..starts) {
                for (skipped in 0..(starts - finished)) {
                    val (f, p, s) = listeningShares(finished.toFloat() / starts, skipped.toFloat() / starts)
                    assertTrue(f >= 0 && p >= 0 && s >= 0, "$finished and $skipped of $starts")
                    assertEquals(100, f + p + s, "$finished and $skipped of $starts")
                }
            }
        }
    }

    @Test
    fun sharesThatNeedNoCorrectionStayAsTheyRound() {
        assertEquals(Triple(33, 34, 33), listeningShares(1 / 3f, 1 / 3f))
        assertEquals(Triple(100, 0, 0), listeningShares(1f, 0f))
        assertEquals(Triple(0, 100, 0), listeningShares(0f, 0f))
    }
}
