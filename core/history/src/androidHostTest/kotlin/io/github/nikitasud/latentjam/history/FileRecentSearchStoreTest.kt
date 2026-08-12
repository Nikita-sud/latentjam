/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

internal class FileRecentSearchStoreTest {

    @Test
    fun newlineQueriesSurviveAStoreReload() = runTest {
        withTemporaryFile { file ->
            FileRecentSearchStore(file).write(listOf("one\ntwo", "КИНО 🚗"))

            assertContentEquals(
                listOf("one\ntwo", "КИНО 🚗"),
                FileRecentSearchStore(file).read(),
            )
            assertTrue(file.readText().startsWith("LATENTJAM-RECENT-SEARCHES\t1\n"))
        }
    }

    @Test
    fun legacyRawLineFilesRemainReadable() = runTest {
        withTemporaryFile { file ->
            file.writeText("aria\nmodern\n")

            assertContentEquals(listOf("aria", "modern"), FileRecentSearchStore(file).read())
        }
    }

    private suspend fun withTemporaryFile(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("latentjam-search-store-").toFile()
        try {
            block(File(directory, "recent_searches.txt"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
