/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Caches copy kept for a downgrade to 0.9.3: a migration's retire keeps it, a clear removes it. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal class IosIndexRetireTest {
    private val manager = NSFileManager.defaultManager

    @Test
    fun retireKeepsTheCachesCopyAndClearRemovesIt() = runTest {
        val name = "smart_index.retire-${NSUUID().UUIDString}.bin"
        val path = checkNotNull(iosIndexFilePath(name))
        val twin = checkNotNull(iosCachesTwinPath(name))
        // In a process whose stores still live in Caches the two are one file: nothing to tell apart.
        if (path == twin) return@runTest
        val snapshot = StoredIndexSnapshot(mapOf(TrackId("a") to floatArrayOf(1f, 0f)))
        try {
            IosFileIndexStore(name).saveSnapshot("v", snapshot)
            manager.createDirectoryAtPath(twin.substringBeforeLast('/'), true, null, null)
            assertTrue(manager.createFileAtPath(twin, platform.Foundation.NSData(), null))

            IosFileIndexStore(name).retire()
            assertFalse(manager.fileExistsAtPath(path), "the migrated file goes")
            assertTrue(manager.fileExistsAtPath(twin), "the downgrade copy stays")

            IosFileIndexStore(name).saveSnapshot("v", snapshot)
            IosFileIndexStore(name).clear()
            assertFalse(manager.fileExistsAtPath(path))
            assertFalse(manager.fileExistsAtPath(twin), "a clear takes the downgrade copy too")
        } finally {
            manager.removeItemAtPath(path, null)
            manager.removeItemAtPath(twin, null)
        }
    }
}
