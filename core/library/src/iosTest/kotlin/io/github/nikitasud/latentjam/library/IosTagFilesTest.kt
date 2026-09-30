/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.nikitasud.latentjam.library

import io.github.nikitasud.latentjam.library.tags.write.DurableWriter
import io.github.nikitasud.latentjam.library.tags.write.WriteFixtures
import io.github.nikitasud.latentjam.library.tags.write.WriteResult
import platform.Foundation.NSDate
import platform.Foundation.NSFileCreationDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.posix.symlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosTagFilesTest {
    private val manager = NSFileManager.defaultManager
    private val root = NSTemporaryDirectory().trimEnd('/') + "/lj-write-" + NSUUID().UUIDString
    private val documents = "$root/Documents"
    private val storeRoot = "$root/store"

    init {
        manager.createDirectoryAtPath(documents, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        manager.removeItemAtPath(root, null)
    }

    private fun put(path: String, bytes: ByteArray) {
        manager.createDirectoryAtPath(path.substringBeforeLast('/'), true, null, null)
        assertTrue(manager.createFileAtPath(path, bytes.toNSData(), null), path)
    }

    private fun bytesAt(path: String): ByteArray = assertNotNull(manager.contentsAtPath(path), path).toByteArray()

    @Test
    fun aPosixFileReadsWritesGrowsAndShrinks() {
        put("$documents/t", byteArrayOf(1, 2, 3, 4))
        assertNotNull(openImportedTrack(documents, "t")).use {
            it.write(2, byteArrayOf(9, 9, 9))
            assertEquals(5, it.length)
            it.write(1, byteArrayOf(7, 8, 7), from = 1, count = 1)
            it.setLength(3)
            it.setLength(6)
            it.force()
            assertContentEquals(byteArrayOf(1, 8, 9, 0, 0, 0), it.read(0, 6))
            assertNull(it.read(4, 3))
            assertContentEquals(byteArrayOf(), it.read(6, 0))
        }
        assertContentEquals(byteArrayOf(1, 8, 9, 0, 0, 0), bytesAt("$documents/t"))
    }

    @Test
    fun theDirectoryCreatesListsAndDeletes() {
        val directory = IosRecoveryDirectory(storeRoot)
        assertTrue(directory.names().isEmpty())
        directory.create("a.patch").use { it.write(0, byteArrayOf(1)) }
        assertEquals(1, assertNotNull(directory.open("a.patch")).use { it.length })
        directory.create("a.patch").use { assertEquals(0, it.length) }
        directory.create("b.staged").close()
        put("$storeRoot/.DS_Store", byteArrayOf(1))
        assertEquals(listOf("a.patch", "b.staged"), directory.names())
        directory.sync()
        directory.delete("a.patch")
        directory.delete("a.patch")
        assertEquals(listOf("b.staged"), directory.names())
        assertNull(directory.open("a.patch"))
        assertTrue(directory.freeBytes() > 0)
    }

    @Test
    fun aStoreFileThatCannotBeOpenedIsNotTakenForAbsent() {
        val directory = IosRecoveryDirectory(storeRoot)
        manager.createDirectoryAtPath("$storeRoot/w1.staged", true, null, null)
        assertFailsWith<IllegalStateException> { directory.open("w1.staged") }
        val files = IosPrivateFiles("$root/requests/covers")
        manager.createDirectoryAtPath("$root/requests/covers/cover-1", true, null, null)
        assertFailsWith<IllegalStateException> { files.read("cover-1") }
    }

    @Test
    fun theStoreRefusesNamesThatEscapeOrHide() {
        val directory = IosRecoveryDirectory(storeRoot)
        assertFailsWith<IllegalArgumentException> { directory.create("../x") }
        assertFailsWith<IllegalArgumentException> { directory.delete(".") }
        assertFailsWith<IllegalArgumentException> { directory.delete("..") }
        assertFailsWith<IllegalArgumentException> { directory.open(".hidden") }
        assertFailsWith<IllegalArgumentException> { directory.open("a/b") }
    }

    @Test
    fun aRealFileIsSavedDurablyInPlaceAndByAtomicReplace() {
        val store = IosRecoveryDirectory(storeRoot)
        var n = 0
        val writer = DurableWriter(store, { "w${++n}" }, iosAtomicReplacer(documents, storeRoot))
        val added = NSDate.dateWithTimeIntervalSince1970(1_000_000_000.0)
        for ((case, rewrite) in WriteFixtures.inPlace.map { it to false } + WriteFixtures.rewrites.map { it to true }) {
            val path = "$documents/Album/track"
            put(path, case.original)
            assertTrue(manager.setAttributes(mapOf<Any?, Any?>(NSFileCreationDate to added), ofItemAtPath = path, error = null))
            val result = assertNotNull(openImportedTrack(documents, "Album/track")).use {
                writer.write("Album/track", it, case.edits, 1L shl 40)
            }
            assertEquals(WriteResult.Saved(WriteFixtures.expected(case).size.toLong(), rewritten = rewrite), result, case.name)
            assertContentEquals(WriteFixtures.expected(case), bytesAt(path), case.name)
            assertTrue(store.names().isEmpty(), case.name)
            // The library's "date added" survives the swap.
            val created = manager.attributesOfItemAtPath(path, null)?.get(NSFileCreationDate) as? NSDate
            assertEquals(added.timeIntervalSince1970, created?.timeIntervalSince1970, case.name)
        }
    }

    @Test
    fun onlyImportedTracksInsideDocumentsOpen() {
        put("$documents/Album/song.mp3", byteArrayOf(1))
        put("$root/outside/secret.mp3", byteArrayOf(2))
        assertEquals(0, symlink("$root/outside", "$documents/escape"))
        assertEquals(0, symlink("$documents/Album", "$documents/inside"))
        assertEquals(0, symlink("$documents/Album/song.mp3", "$documents/link.mp3"))

        assertNotNull(openImportedTrack(documents, "Album/song.mp3")).close()
        // A symlinked folder that stays inside Documents resolves to the real file.
        assertNotNull(openImportedTrack(documents, "inside/song.mp3")).close()
        for (key in listOf(
            "", " ", "/Album/song.mp3", "Album/../Album/song.mp3", "./Album/song.mp3", "Album//song.mp3",
            "ios-media:123", "escape/secret.mp3", "../outside/secret.mp3", "Album",
        )) {
            assertNull(openImportedTrack(documents, key), key)
        }
        assertEquals("$documents/Album/song.mp3".resolved(), writableIosTrackPath(documents, "link.mp3"))
    }

    @Test
    fun onlyAFileThatIsCertainlyNotThereIsGone() {
        put("$documents/Album/song.mp3", byteArrayOf(1))
        assertEquals(0, symlink("$root/nowhere", "$documents/dangling.mp3"))
        assertTrue(importedTrackIsGone(documents, "Album/deleted.mp3"))
        assertTrue(importedTrackIsGone(documents, "Deleted album/song.mp3"))
        assertTrue(importedTrackIsGone(documents, "Album/song.mp3/child"))
        assertFalse(importedTrackIsGone(documents, "Album/song.mp3"))
        assertFalse(importedTrackIsGone(documents, "dangling.mp3"))
        assertFalse(importedTrackIsGone(documents, "ios-media:123"))
        assertFalse(importedTrackIsGone(documents, "../x.mp3"))
        assertFalse(importedTrackIsGone("$root/no-documents", "Album/deleted.mp3"))
    }

    @Test
    fun privateFilesAreWrittenWholeReadAndDeleted() {
        val files = IosPrivateFiles("$root/requests/keys")
        assertTrue(files.names().isEmpty())
        assertNull(files.read("1.keys"))
        files.write("1.keys", byteArrayOf(1, 2, 3))
        files.write("1.keys", byteArrayOf(4))
        files.write("2.keys", byteArrayOf())
        assertContentEquals(byteArrayOf(4), files.read("1.keys"))
        assertContentEquals(byteArrayOf(), files.read("2.keys"))
        put("$root/requests/keys/3.keys.partial", byteArrayOf(9))
        assertEquals(listOf("1.keys", "2.keys", "3.keys.partial"), files.names())
        files.delete("1.keys")
        files.delete("3.keys")
        assertEquals(listOf("2.keys"), files.names())
        assertFailsWith<IllegalArgumentException> { files.write("../x", byteArrayOf()) }
    }

    private fun String.resolved(): String? =
        platform.Foundation.NSURL.fileURLWithPath(this).URLByResolvingSymlinksInPath?.path
}
