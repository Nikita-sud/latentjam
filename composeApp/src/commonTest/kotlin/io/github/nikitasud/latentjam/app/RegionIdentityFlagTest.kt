/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Region identity follows the refresh switch and can be turned off alone (owner decision, 26 October 2026). */
class RegionIdentityFlagTest {
    @Test fun `identity follows the refresh switch`() {
        assertTrue(RegionIdentityFlag.enabled(refreshEnabled = true, switchedOff = false))
        assertFalse(RegionIdentityFlag.enabled(refreshEnabled = false, switchedOff = false))
    }

    @Test fun `identity can be switched off alone`() {
        assertFalse(RegionIdentityFlag.enabled(refreshEnabled = true, switchedOff = true))
    }

    @Test fun `switching identity off never turns it on without the refresh`() {
        assertFalse(RegionIdentityFlag.enabled(refreshEnabled = false, switchedOff = true))
    }

    @Test fun `both are off by default`() {
        assertFalse(MapRefreshFlag.DEFAULT)
    }
}
