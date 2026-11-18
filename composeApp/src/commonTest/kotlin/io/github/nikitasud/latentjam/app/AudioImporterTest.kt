/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioImporterTest {
    private val temporary = "/var/mobile/Containers/Data/Application/0A1B/tmp/"

    @Test
    fun `a picked copy in the temporary directory is the importers to remove`() {
        assertTrue(isInsideDirectory("/var/mobile/Containers/Data/Application/0A1B/tmp/app-Inbox/Song.mp3", temporary))
        assertTrue(isInsideDirectory("/var/mobile/Containers/Data/Application/0A1B/tmp/Song.mp3", temporary.trimEnd('/')))
    }

    @Test
    fun `a file anywhere else is never the importers to remove`() {
        // The user's own music, a neighbour whose name only begins the same, the directory itself.
        assertFalse(isInsideDirectory("/var/mobile/Containers/Data/Application/0A1B/Documents/Imported/Song.mp3", temporary))
        assertFalse(isInsideDirectory("/var/mobile/Containers/Data/Application/0A1B/tmp-old/Song.mp3", temporary))
        assertFalse(isInsideDirectory("/var/mobile/Containers/Data/Application/0A1B/tmp", temporary))
        assertFalse(isInsideDirectory("/var/mobile/Containers/Data/Application/0A1B/tmp/", temporary))
        assertFalse(isInsideDirectory("/var/mobile/Containers/Data/Application/0A1B/tmp/../Documents/Song.mp3", temporary))
    }

    @Test
    fun `an unknown path or directory is a no`() {
        assertFalse(isInsideDirectory(null, temporary))
        assertFalse(isInsideDirectory("/tmp/Song.mp3", null))
        assertFalse(isInsideDirectory("/tmp/Song.mp3", ""))
        assertFalse(isInsideDirectory("/tmp/Song.mp3", "/"))
        assertFalse(isInsideDirectory("tmp/Song.mp3", "tmp"))
    }
}
