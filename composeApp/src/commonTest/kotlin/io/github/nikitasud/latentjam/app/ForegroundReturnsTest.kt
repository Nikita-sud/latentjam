/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Both platforms report "app became active" at launch as well as on every return from the
 * background. [PlatformForegroundEffect]'s contract is returns only — launch work belongs to the
 * launch effects, and double-firing them would re-query the library twice on every cold start.
 */
internal class ForegroundReturnsTest {

    @Test
    fun launchActivationIsSwallowed() {
        val gate = ForegroundReturns()
        assertFalse(gate.onActivated(), "the first activation is the launch, not a return")
    }

    @Test
    fun everyLaterActivationFires() {
        val gate = ForegroundReturns()
        gate.onActivated()
        assertTrue(gate.onActivated(), "second activation is a genuine return")
        assertTrue(gate.onActivated(), "and so is every one after it")
    }
}
