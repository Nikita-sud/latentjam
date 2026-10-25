/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertTrue

class SmoothTransitionsIosTest {
    /**
     * The iOS file player overlaps and fades its tracks, and this flag is what shows the slider
     * and hands the setting to playback (AppGraph). Off, none of it could run in the app; the
     * wiring itself was checked by running the app in a simulator.
     */
    @Test
    fun iosDeclaresSmoothTransitions() {
        assertTrue(boundaryFadeControlsAvailable)
    }
}
