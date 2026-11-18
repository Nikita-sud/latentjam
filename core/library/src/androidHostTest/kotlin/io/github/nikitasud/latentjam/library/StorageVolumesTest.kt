/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageVolumesTest {
    private val primary = VolumeState("external_primary", "mounted")
    private val library = setOf("external_primary", "1a2b-3c4d")

    private fun card(state: String?) = VolumeState("1a2b-3c4d", state)

    @Test
    fun `every volume mounted leaves nothing missing`() {
        assertFalse(storageMayBeMissing(listOf(primary), library))
        assertFalse(storageMayBeMissing(listOf(primary, card("mounted_ro")), library))
    }

    @Test
    fun `a card with songs of the library taken out or not yet back counts as missing storage`() {
        // Android 11 and later list a card seen within the last week as unmounted once it is out.
        for (state in listOf("unmounted", "removed", "checking", "ejecting", "unmountable", "bad_removal")) {
            assertTrue(storageMayBeMissing(listOf(primary, card(state)), library), state)
        }
    }

    @Test
    fun `a state the system did not give counts as missing storage`() {
        assertTrue(storageMayBeMissing(listOf(primary, card(null)), library))
        assertTrue(storageMayBeMissing(listOf(VolumeState(null, null)), library))
    }

    @Test
    fun `a volume the library has no songs on cannot make a scan incomplete`() {
        // A USB stick with documents, pulled out an hour ago, or a card the phone cannot read.
        val onDeviceOnly = setOf("external_primary")
        assertFalse(storageMayBeMissing(listOf(primary, VolumeState("9f8e-7d6c", "unmounted")), onDeviceOnly))
        assertFalse(storageMayBeMissing(listOf(primary, VolumeState(null, "unmountable")), onDeviceOnly))
        assertFalse(storageMayBeMissing(listOf(primary, card("mounted"), VolumeState("9f8e-7d6c", "unmounted")), library))
        // Nothing registered yet: there is nothing a scan could wrongly take for gone.
        assertFalse(storageMayBeMissing(listOf(primary, VolumeState("9f8e-7d6c", "unmounted")), emptySet()))
    }

    @Test
    fun `an unnamed volume that is away may be the one the library has songs on`() {
        assertTrue(storageMayBeMissing(listOf(primary, VolumeState(null, "unmountable")), library))
        // The library's card is in and readable: the unnamed one is some other volume.
        assertFalse(storageMayBeMissing(listOf(primary, card("mounted"), VolumeState(null, "unmountable")), library))
    }

    @Test
    fun `a registry that cannot be read vouches for nothing`() {
        assertTrue(storageMayBeMissing(listOf(primary, VolumeState("9f8e-7d6c", "unmounted")), null))
        assertFalse(storageMayBeMissing(listOf(primary), null))
    }
}
