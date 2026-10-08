/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the file-backed stores the way the app uses them: through the shared Foundation read
 * path, against the very files the stores own. Files are written as raw bytes because that is the
 * only way to reproduce the damage this covers — Foundation will not encode text that is not valid
 * UTF-8 in the first place.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFileHistoryStoreTest {

    private val manager = NSFileManager.defaultManager

    private val directory = NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory,
        NSUserDomainMask,
        true,
    ).firstOrNull() as? String ?: error("Application Support is unavailable")

    private fun path(name: String) = if (directory.endsWith("/")) directory + name else "$directory/$name"

    // Hand-encoded fixtures, as in ListeningHistoryTest: hex("42") = 3432,
    // hex("4343") = 34333433, hex("SMART") = 534d415254.
    private val firstLine = "v3|3432|1|2||1|0|534d415254|2"
    private val recordedLine = "v3|34333433|7|8||1|0|534d415254|9"

    /**
     * The event of [firstLine] without listenedMs — what a legacy event looks like. The optional
     * field is written empty (HistoryTypes appends the number or nothing), not as a zero.
     */
    private val firstLineWithoutListenedMs = "v3|3432|1|2||1|0|534d415254|"

    /** A line holding two bytes that can never appear in UTF-8: no app writer can produce it. */
    private val damagedLine = "v3|".encodeToByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
        "\n".encodeToByteArray()

    private val bareEvent = ListenEvent(
        trackId = TrackId("4343"),
        startedAtMs = 7,
        playedMs = 8,
        trackDurationMs = null,
        completed = true,
        skipped = false,
        shuffleMode = "SMART",
        listenedMs = 9,
    ) // serializes to recordedLine

    private fun seed(name: String, bytes: ByteArray) {
        manager.createDirectoryAtPath(directory, true, null, null)
        assertTrue(manager.createFileAtPath(path(name), bytes.toNSData(), null), name)
    }

    private fun seedText(name: String, text: String) = seed(name, text.encodeToByteArray())

    private fun bytesAt(name: String): ByteArray =
        checkNotNull(manager.contentsAtPath(path(name))) { "no file at ${path(name)}" }.toByteArray()

    private fun exists(name: String) = manager.fileExistsAtPath(path(name))

    @Test
    fun oneInvalidStretchOfBytesDoesNotHideTheIntactEventsOrBlockLaterWrites() = runTest {
        val intact = "$firstLine\n".encodeToByteArray()
        // Damage the middle of the log while both neighbours stay perfectly parseable.
        seed("listening_history.log", intact + damagedLine + intact)

        val history = DefaultListeningHistory(FileHistoryStore())
        // Both intact lines sit around the damage, so both are counted.
        assertEquals(2, history.stats()[TrackId("42")]?.plays)

        // Recording used to be impossible as well: ensureLoaded() threw before the append.
        history.record(bareEvent)
        assertEquals(1, history.stats()[TrackId("4343")]?.plays)

        val expected = intact + damagedLine + "$firstLine\n".encodeToByteArray() +
            "$recordedLine\n".encodeToByteArray()
        assertContentEquals(expected, bytesAt("listening_history.log"))
    }

    @Test
    fun aFileHoldingNothingButDamagedBytesStillLoadsAsEmpty() = runTest {
        seed("listening_history.log", damagedLine + byteArrayOf(0x80.toByte(), 0x81.toByte()))

        val history = DefaultListeningHistory(FileHistoryStore())
        assertTrue(history.allEvents().isEmpty())
    }

    @Test
    fun clearRemovesAFileItCannotDecodeAndKeepsWorkingAfterwards() = runTest {
        seed("listening_history.log", damagedLine)

        val history = DefaultListeningHistory(FileHistoryStore())
        history.clear()
        assertFalse(exists("listening_history.log"), "a damaged log must not survive a clear")

        // The next record starts a clean log.
        history.record(bareEvent)
        assertContentEquals("$recordedLine\n".encodeToByteArray(), bytesAt("listening_history.log"))
    }

    @Test
    fun replaceRewritesALogItCannotDecode() = runTest {
        seed("listening_history.log", damagedLine)

        DefaultListeningHistory(FileHistoryStore()).replace(
            listOf(
                ListenEvent(
                    trackId = TrackId("42"),
                    startedAtMs = 1,
                    playedMs = 2,
                    trackDurationMs = null,
                    completed = true,
                    skipped = false,
                    shuffleMode = "SMART",
                ),
            ),
        )

        // The replacement carries no listenedMs, so its line ends with an empty last field.
        assertContentEquals(
            "$firstLineWithoutListenedMs\n".encodeToByteArray(),
            bytesAt("listening_history.log"),
        )
    }

    @Test
    fun everyLineStoreReadsPastAnUndecodableLine() = runTest {
        // One damaged line and one intact line. Each byte that can never appear in UTF-8 becomes
        // its own U+FFFD, and the damaged line still arrives instead of failing the whole read,
        // which is what used to take favorites and exclusions down too.
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "\nsmart\n".encodeToByteArray()
        val damaged = "\uFFFD\uFFFD"

        seed("smart_exclusions.txt", bytes)
        assertEquals(listOf(damaged, "smart"), FileSmartExclusionStore().read())

        seed("foryou_impressions.txt", bytes)
        assertEquals(listOf(damaged, "smart"), FileForYouImpressionStore().read())

        seed("favorites.txt", bytes)
        assertEquals(listOf(damaged, "smart"), FileFavoritesStore().read())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val output = ByteArray(size)
    output.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return output
}
