/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertTrue

class SmoothTransitionsIosTest {
    /**
     * The iOS file player overlaps and fades its tracks. Off, this flag hid the slider and never
     * handed the setting to playback, so none of it could run in the app.
     */
    @Test
    fun theSmoothTransitionsSettingReachesIosPlayback() {
        assertTrue(boundaryFadeControlsAvailable)
    }
}
