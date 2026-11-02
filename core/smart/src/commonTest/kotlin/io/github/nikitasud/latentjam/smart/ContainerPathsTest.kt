/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ContainerPathsTest {
    private val a = "file:///private/var/mobile/Containers/Data/Application/0A1B2C3D-4E5F-6071-8293-A4B5C6D7E8F9/"
    private val b = "file:///var/mobile/Containers/Data/Application/FFFFFFFF-0000-1111-2222-333333333333/"
    private val sim = "file:///Users/x/Library/Developer/CoreSimulator/Devices/090F8455-FC8B-4B64-B145-2B71531D29CD/data/Containers/Data/Application/D880EF7C-10A3-4CDE-92AC-60220096AF9A/"

    @Test
    fun aFileInAnyContainerHasOneRelativeForm() {
        val relative = ContainerPaths.relative("${a}Documents/Imported/Song%20One.mp3")
        assertEquals("app-container:/Documents/Imported/Song%20One.mp3", relative)
        assertEquals(relative, ContainerPaths.relative("${b}Documents/Imported/Song%20One.mp3"))
        assertEquals(relative, ContainerPaths.relative("${sim}Documents/Imported/Song%20One.mp3"))
    }

    @Test
    fun everyOtherUriIsLeftAsItIs() {
        for (uri in listOf(
            "content://media/external/audio/media/1000012345",
            "file:///storage/emulated/0/Music/a.mp3",
            "ipod-library://item/item.mp3?id=123456789",
            "file:///private/var/mobile/Containers/Shared/AppGroup/0A1B2C3D-4E5F-6071-8293-A4B5C6D7E8F9/a.mp3",
            "test://seed",
        )) assertEquals(uri, ContainerPaths.relative(uri))
        assertNull(ContainerPaths.relative(null))
        assertNull(ContainerPaths.root("content://media/external/audio/media/1"))
    }

    @Test
    fun aUriMovesBetweenRootsAndRootsAreFoundInText() {
        assertEquals(a, ContainerPaths.root("${a}Documents/x.mp3"))
        assertEquals("${b}Documents/x.mp3", ContainerPaths.moved("${a}Documents/x.mp3", b))
        assertEquals(setOf(a, b), ContainerPaths.rootsIn("loudness|40:${a}Documents/x.mp3|6:200000|x|${b}Documents/y.mp3"))
    }
}
