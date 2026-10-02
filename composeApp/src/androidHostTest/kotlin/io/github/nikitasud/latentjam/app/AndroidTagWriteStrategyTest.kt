/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidTagWriteStrategyTest {
    @Test
    fun eachAndroidVersionGetsItsConsentMechanism() {
        assertEquals(TagWriteStrategy.WRITE_PERMISSION, tagWriteStrategy(24))
        assertEquals(TagWriteStrategy.WRITE_PERMISSION, tagWriteStrategy(28))
        assertEquals(TagWriteStrategy.RECOVERABLE_CONSENT, tagWriteStrategy(29))
        assertEquals(TagWriteStrategy.SYSTEM_WRITE_REQUEST, tagWriteStrategy(30))
        assertEquals(TagWriteStrategy.SYSTEM_WRITE_REQUEST, tagWriteStrategy(36))
    }
}
