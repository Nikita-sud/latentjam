/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagRecoveryPromptStateTest {
    @Test
    fun thePromptShowsOnlyWhenSomethingWaitsAndNothingIsRunning() {
        assertTrue(recoveryPromptVisible(pending = 2, busy = false, dismissed = false))
        assertFalse(recoveryPromptVisible(pending = 0, busy = false, dismissed = false))
        assertFalse(recoveryPromptVisible(pending = 2, busy = true, dismissed = false))
        assertFalse(recoveryPromptVisible(pending = 2, busy = false, dismissed = true))
    }
}
