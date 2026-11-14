/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class ShuffleModeCycleTest {
    @Test
    fun theCycleIsOffShuffleSmartAndBack() {
        assertEquals(ShuffleMode.ON, ShuffleMode.OFF.next())
        assertEquals(ShuffleMode.SMART, ShuffleMode.ON.next())
        assertEquals(ShuffleMode.OFF, ShuffleMode.SMART.next())
    }

    @Test
    fun withSmartSwitchedOffTheCycleHasNoSmartStep() {
        assertEquals(ShuffleMode.ON, ShuffleMode.OFF.next(smartAvailable = false))
        assertEquals(ShuffleMode.OFF, ShuffleMode.ON.next(smartAvailable = false))
        // Wherever it starts, pressing on never reaches SMART.
        for (start in ShuffleMode.entries) {
            var mode = start
            repeat(6) {
                mode = mode.next(smartAvailable = false)
                assertEquals(false, mode == ShuffleMode.SMART, "from $start")
            }
        }
    }

    @Test
    fun aSavedOrRequestedSmartModeBecomesPlainOrderWhileSmartIsOff() {
        assertEquals(ShuffleMode.OFF, ShuffleMode.SMART.allowed(smartAvailable = false))
        assertEquals(ShuffleMode.ON, ShuffleMode.ON.allowed(smartAvailable = false))
        assertEquals(ShuffleMode.OFF, ShuffleMode.OFF.allowed(smartAvailable = false))
        for (mode in ShuffleMode.entries) assertEquals(mode, mode.allowed(smartAvailable = true))
    }
}
