/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.test.Test
import kotlin.test.assertEquals

class TextMetadataTest {

    @Test
    fun `genre bait in a title cannot enter the trusted text embedding`() {
        val clean = TextEncoder.metadataString("Soul", "Example Artist", "Ordinary Song", 1972)
        val bait = TextEncoder.metadataString("Soul", "Example Artist", "Hard Techno Mix", 1972)

        assertEquals("Soul; Example Artist; 1972", clean)
        assertEquals(clean, bait)
    }
}
