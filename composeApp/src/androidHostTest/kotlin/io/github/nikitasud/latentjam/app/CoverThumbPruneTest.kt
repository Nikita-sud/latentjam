/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverThumbPruneTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun withCache(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("cover-thumbs").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun File.entry(name: String, bytes: Int, age: Long): File =
        File(this, name).also {
            it.writeBytes(ByteArray(bytes))
            it.setLastModified(now - age)
        }

    @Test
    fun aNoCoverMarkerPastItsDayIsDeletedWhateverTheCacheWeighs() = withCache { directory ->
        // A marker is an empty file under a locator nobody asks again once the song's revision moved on.
        directory.entry("aaaa.none", 0, age = 3 * day)
        directory.entry("bbbb.none", 0, age = day + 60_000)
        directory.entry("cccc.none", 0, age = day - 60_000)
        directory.entry("dddd-256.jpg", 4_096, age = 30 * day)

        pruneCoverThumbs(directory, now, maxBytes = 64L * 1024 * 1024, markerTtlMs = day)

        assertEquals(listOf("cccc.none", "dddd-256.jpg"), directory.list()!!.sorted())
    }

    @Test
    fun aCacheOverItsBoundStillLosesItsOldestCoversFirst() = withCache { directory ->
        directory.entry("old-256.jpg", 600, age = 9 * day)
        directory.entry("mid-256.jpg", 600, age = 5 * day)
        directory.entry("new-256.jpg", 600, age = day / 2)
        directory.entry("fresh.none", 0, age = 60_000)

        pruneCoverThumbs(directory, now, maxBytes = 1_500, markerTtlMs = day)

        assertEquals(listOf("fresh.none", "mid-256.jpg", "new-256.jpg"), directory.list()!!.sorted())
    }
}
