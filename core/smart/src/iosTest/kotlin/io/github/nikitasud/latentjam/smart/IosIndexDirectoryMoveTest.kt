/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one-time move of the index from Caches to Application Support (docs/analysis-094-plan.md 1e-ios). */
@OptIn(ExperimentalForeignApi::class)
internal class IosIndexDirectoryMoveTest {

    private val root = "${NSTemporaryDirectory().trimEnd('/')}/lj-move-${NSUUID().UUIDString}"
    private val caches = "$root/Caches"
    private val support = "$root/Application Support"
    private val target = "$support/smart"
    private val manager = NSFileManager.defaultManager

    init {
        manager.createDirectoryAtPath(caches, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        platform.posix.chmod("$caches/smart_text_index.bin", 420u)
        manager.removeItemAtPath(root, null)
    }

    @Test
    fun everyStoreFileIsCopiedThenTheMarkerIsWritten() {
        write("$caches/smart_index.model-v1.bin", byteArrayOf(1, 2, 3))
        write("$caches/smart_index.bin", byteArrayOf(4))
        write("$caches/smart_text_index.bin", byteArrayOf(5, 6))
        write("$caches/smart_energy.e-v1.bin", ByteArray(0))
        write("$caches/smart_text_index.bin.tmp", byteArrayOf(9))
        write("$caches/lyrics.json", byteArrayOf(9))

        assertEquals(target, resolveIndexDirectory(caches, support))

        assertContentEquals(byteArrayOf(1, 2, 3), read("$target/smart_index.model-v1.bin"))
        assertContentEquals(byteArrayOf(4), read("$target/smart_index.bin"))
        assertContentEquals(byteArrayOf(5, 6), read("$target/smart_text_index.bin"))
        assertContentEquals(ByteArray(0), read("$target/smart_energy.e-v1.bin"))
        assertNull(read("$target/smart_text_index.bin.tmp"))
        assertNull(read("$target/lyrics.json"))
        assertTrue(manager.fileExistsAtPath("$target/.moved-from-caches"))
        // Caches keeps its copy for a downgrade.
        assertContentEquals(byteArrayOf(1, 2, 3), read("$caches/smart_index.model-v1.bin"))
    }

    @Test
    fun afterTheMarkerCachesIsNeverReadAgain() {
        write("$caches/map_layout.bin", byteArrayOf(1))
        resolveIndexDirectory(caches, support)
        write("$caches/map_layout.bin", byteArrayOf(2))
        write("$caches/smart_imputation.bin", byteArrayOf(3))

        assertEquals(target, resolveIndexDirectory(caches, support))
        assertContentEquals(byteArrayOf(1), read("$target/map_layout.bin"))
        assertNull(read("$target/smart_imputation.bin"))
    }

    @Test
    fun aFileRefusedForAnyReasonButProtectionIsACacheMissAndDoesNotHoldTheMoveBack() {
        // chmod 0 refuses with EACCES; data protection's EPERM (keep Caches, retry next launch) has no simulator stand-in.
        write("$caches/smart_index.bin", byteArrayOf(1))
        write("$caches/smart_text_index.bin", byteArrayOf(2))
        platform.posix.chmod("$caches/smart_text_index.bin", 0u)

        assertEquals(target, resolveIndexDirectory(caches, support))
        assertContentEquals(byteArrayOf(1), read("$target/smart_index.bin"))
        assertNull(read("$target/smart_text_index.bin"))
    }

    @Test
    fun whatACutShortCopyLeftBehindNeverOutlivesTheNextCopy() {
        // A killed copy left these, then that process cleared the text index in Caches and wrote on.
        manager.createDirectoryAtPath(target, true, null, null)
        write("$target/smart_text_index.bin", byteArrayOf(9))
        write("$target/smart_index.bin", byteArrayOf(8))
        write("$target/smart_index.bin.tmp", byteArrayOf(7))
        write("$caches/smart_index.bin", byteArrayOf(1))

        assertEquals(target, resolveIndexDirectory(caches, support))
        assertContentEquals(byteArrayOf(1), read("$target/smart_index.bin"))
        assertNull(read("$target/smart_text_index.bin"))
        assertNull(read("$target/smart_index.bin.tmp"))
    }

    @Test
    fun anEmptyCachesStillMovesSoNewWritesGoToApplicationSupport() {
        assertEquals(target, resolveIndexDirectory(caches, support))
        assertTrue(manager.fileExistsAtPath("$target/.moved-from-caches"))
    }

    @Test
    fun storeFileNames() {
        assertTrue(isIndexStoreFile("smart_index.bin"))
        assertTrue(isIndexStoreFile("smart_index.mnv4-960-clean-a0-fma-v1-p50q4aq.bin"))
        assertTrue(isIndexStoreFile("smart_energy.semantic-energy-v1-x.bin"))
        assertTrue(isIndexStoreFile("map_layout.bin"))
        assertFalse(isIndexStoreFile("smart_index.bin.tmp"))
        assertFalse(isIndexStoreFile("smart_index.x.bin.half"))
        assertFalse(isIndexStoreFile("smart_indexer.bin"))
        assertFalse(isIndexStoreFile("map_refresh_state.json"))
    }

    private fun write(path: String, bytes: ByteArray) {
        val fd = platform.posix.open(path, platform.posix.O_WRONLY or platform.posix.O_CREAT or platform.posix.O_TRUNC, 420u)
        check(fd >= 0)
        if (bytes.isNotEmpty()) bytes.usePinned { platform.posix.write(fd, it.addressOf(0), bytes.size.convert()) }
        platform.posix.close(fd)
    }

    private fun read(path: String): ByteArray? {
        if (!manager.fileExistsAtPath(path)) return null
        val fd = platform.posix.open(path, platform.posix.O_RDONLY)
        if (fd < 0) return null
        try {
            val out = ArrayList<Byte>()
            val buffer = ByteArray(4096)
            while (true) {
                val got = buffer.usePinned { platform.posix.read(fd, it.addressOf(0), buffer.size.convert()) }
                if (got <= 0L) break
                for (i in 0 until got.toInt()) out += buffer[i]
            }
            return out.toByteArray()
        } finally {
            platform.posix.close(fd)
        }
    }
}
