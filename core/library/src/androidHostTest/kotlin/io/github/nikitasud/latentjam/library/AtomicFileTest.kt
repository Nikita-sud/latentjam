/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AtomicFileTest {
    @Test fun aReplacementThatCannotBeMovedIntoPlaceIsAnIoFailure() {
        val directory = Files.createTempDirectory("atomic-file").toFile()
        try {
            // A name that cannot be replaced by a file: a folder with something in it.
            val target = File(directory, "store.txt").apply { mkdir() }
            File(target, "kept").writeText("old")
            // Every caller that survives a full or failing disk catches IOException; anything else passed them all.
            assertFailsWith<IOException> { target.atomicReplaceText("new") }
            assertEquals("old", File(target, "kept").readText())
            assertFalse(File(directory, ".store.txt.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
