/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bulk hearts through the very file the iOS store keeps them in, read back as a cold start does. */
@OptIn(ExperimentalForeignApi::class)
class IosFileFavoritesStoreTest {

    private val path: String = run {
        val directory = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: error("Application Support is unavailable")
        if (directory.endsWith("/")) "${directory}favorites.txt" else "$directory/favorites.txt"
    }

    @BeforeTest
    fun startWithoutTheFile() = removeTheFile()

    @AfterTest
    fun leaveNoFileBehind() = removeTheFile()

    private fun removeTheFile() {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    @Test
    fun aBulkAddSurvivesAReloadInItsOrderAndUndoRestoresIt() = runTest {
        val first = DefaultFavorites(FileFavoritesStore())
        first.toggle(TrackId("old"))
        val added = (1..400).map { TrackId("t$it") }

        val change = first.addAll(added + TrackId("old"))

        assertEquals(added + TrackId("old"), DefaultFavorites(FileFavoritesStore()).all())

        assertTrue(first.replaceIfUnchanged(expected = change.after, replacement = change.before))
        assertEquals(listOf(TrackId("old")), DefaultFavorites(FileFavoritesStore()).all())
    }

    @Test
    fun aBulkRemoveSurvivesAReload() = runTest {
        val favorites = DefaultFavorites(FileFavoritesStore())
        favorites.addAll(listOf(TrackId("a"), TrackId("b"), TrackId("c")))

        favorites.removeAll(setOf(TrackId("a"), TrackId("c")))

        assertEquals(listOf(TrackId("b")), DefaultFavorites(FileFavoritesStore()).all())
    }
}
