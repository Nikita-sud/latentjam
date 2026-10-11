/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bulk hearts through the file the app keeps them in, read back the way a cold start reads them. */
internal class FileFavoritesStoreTest {

    @Test
    fun aBulkAddSurvivesAReloadInItsOrder() = runTest {
        withTemporaryFile { file ->
            file.writeText("old1\nold2")
            val added = (1..400).map { TrackId("t$it") }

            val change = DefaultFavorites(FileFavoritesStore(file)).addAll(added + TrackId("old2"))

            val reloaded = DefaultFavorites(FileFavoritesStore(file)).all()
            assertEquals(added + listOf(TrackId("old1"), TrackId("old2")), reloaded)
            assertEquals(change.after, reloaded)
            assertEquals(402, file.readLines().size)
        }
    }

    @Test
    fun undoRestoresTheFileExactly() = runTest {
        withTemporaryFile { file ->
            file.writeText("old1\nold2")
            val favorites = DefaultFavorites(FileFavoritesStore(file))
            val change = favorites.addAll(listOf(TrackId("new")))

            assertTrue(favorites.replaceIfUnchanged(expected = change.after, replacement = change.before))

            assertEquals("old1\nold2", file.readText())
            assertEquals(
                listOf(TrackId("old1"), TrackId("old2")),
                DefaultFavorites(FileFavoritesStore(file)).all(),
            )
        }
    }

    @Test
    fun aBulkRemoveSurvivesAReload() = runTest {
        withTemporaryFile { file ->
            file.writeText("a\nb\nc")
            DefaultFavorites(FileFavoritesStore(file)).removeAll(setOf(TrackId("a"), TrackId("c")))

            assertEquals(listOf(TrackId("b")), DefaultFavorites(FileFavoritesStore(file)).all())
        }
    }

    private suspend fun withTemporaryFile(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("latentjam-favorites-store-").toFile()
        try {
            block(File(directory, "favorites.txt"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
