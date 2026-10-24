/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PayloadFileTest {
    private val directory: File = Files.createTempDirectory("payload").toFile()
    private var legacy: String? = "v6|a\nv6|b"
    private var removed = 0

    private fun payload(file: File = File(directory, "cache.txt")) =
        PayloadFile(file, readLegacy = { legacy }, removeLegacy = { removed++; legacy = null })

    @Test
    fun theLegacyValueMovesOnceAndIsRemoved() {
        val payload = payload()
        assertEquals("v6|a\nv6|b", payload.readText())
        assertEquals(listOf("v6|a", "v6|b"), payload.readLines { it.toList() })
        assertEquals(1, removed)
        assertEquals("v6|a\nv6|b", File(directory, "cache.txt").readText())
    }

    @Test
    fun aFileAlreadyThereWinsOverTheLegacyValue() {
        File(directory, "cache.txt").writeText("v6|new")
        assertEquals("v6|new", payload().readText())
        assertEquals(1, removed)
    }

    @Test
    fun aFailedMoveServesTheLegacyValueUntilAWriteSupersedesIt() {
        // A plain file where the cache's directory should be: nothing can be written there.
        val blocked = File(File(directory, "plain").apply { writeText("x") }, "cache.txt")
        val payload = payload(blocked)
        assertEquals("v6|a\nv6|b", payload.readText())
        assertEquals(listOf("v6|a", "v6|b"), payload.readLines { it.toList() })
        assertEquals(0, removed, "the key is kept for the next launch")
        assertFailsWith<Exception> { payload.write { it.write("v6|c") } }
        assertEquals("v6|a\nv6|b", payload.readText(), "a failed write changes nothing")
        assertEquals(0, removed)
    }

    @Test
    fun aWriteAfterAFailedMoveSupersedesTheLegacyValue() {
        // A plain file where the cache's directory should be fails the move; once the directory
        // can exist, a write lands and the legacy value goes.
        val parent = File(directory, "late").apply { writeText("x") }
        val file = File(parent, "cache.txt")
        val payload = payload(file)
        assertEquals("v6|a\nv6|b", payload.readText())
        assertEquals(0, removed)
        parent.delete()
        payload.write { it.write("v6|c") }
        assertEquals("v6|c", payload.readText())
        assertEquals(1, removed)
        assertTrue(file.exists())
    }

    @Test
    fun aReadThatFailsInsideAWriteFailsTheWriteAndKeepsTheOldFile() {
        legacy = null
        // A directory where the file is: it exists, but cannot be opened for reading.
        val file = File(directory, "unreadable").apply { mkdirs() }
        val payload = payload(file)
        assertNull(payload.readLines { it.toList() }, "outside a write, it reads as none")
        assertFailsWith<java.io.IOException> {
            payload.write { writer -> payload.readLines { lines -> lines.forEach(writer::write) } }
        }
        assertTrue(file.isDirectory, "the old file is untouched")
    }

    @Test
    fun aCallersOwnFailureInsideAReadIsNotTakenForAMissingPayload() {
        File(directory, "cache.txt").writeText("v6|x")
        legacy = null
        assertFailsWith<IllegalStateException> { payload().readLines { error("the caller's") } }
    }

    @Test
    fun nothingAnywhereReadsAsNoPayload() {
        legacy = null
        val payload = payload()
        assertNull(payload.readText())
        assertNull(payload.readLines { it.toList() })
        assertEquals(0, removed)
    }
}
