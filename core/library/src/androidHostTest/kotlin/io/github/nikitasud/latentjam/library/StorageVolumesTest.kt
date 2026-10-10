/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageVolumesTest {

    @Test
    fun `every volume mounted leaves nothing missing`() {
        assertFalse(storageMayBeMissing(listOf("mounted")))
        assertFalse(storageMayBeMissing(listOf("mounted", "mounted_ro")))
    }

    @Test
    fun `a card taken out or not yet back counts as missing storage`() {
        // Android 11 and later list a card seen within the last week as unmounted once it is out.
        assertTrue(storageMayBeMissing(listOf("mounted", "unmounted")))
        assertTrue(storageMayBeMissing(listOf("mounted", "removed")))
        assertTrue(storageMayBeMissing(listOf("mounted", "checking")))
        assertTrue(storageMayBeMissing(listOf("mounted", "ejecting")))
        assertTrue(storageMayBeMissing(listOf("mounted", "unmountable")))
        assertTrue(storageMayBeMissing(listOf("mounted", "bad_removal")))
    }

    @Test
    fun `a state the system did not give counts as missing storage`() {
        assertTrue(storageMayBeMissing(listOf("mounted", null)))
        assertTrue(storageMayBeMissing(listOf(null)))
    }
}
