/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultListeningHistory
import io.github.nikitasud.latentjam.history.HistoryStore
import io.github.nikitasud.latentjam.library.LibrarySource
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Real ZIPs from java.util.zip: a streaming writer marks DEFLATE entries with a trailing data
 * descriptor and no sizes in the local header, the layout the reader has to walk without seeking.
 */
class ListenBrainzZipTest {

    private fun zip(entries: List<Pair<String, String>>, stored: Set<String> = emptySet()): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, text) in entries) {
                val data = text.toByteArray()
                val entry = ZipEntry(name)
                if (name in stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                out.putNextEntry(entry)
                out.write(data)
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun rows(bytes: ByteArray, chunk: Int): List<ListenBrainzRow> {
        val rows = mutableListOf<ListenBrainzRow>()
        ListenBrainzExportReader.read(ListenBrainzTestExports.input(bytes, chunk)) { rows += it }
        return rows
    }

    private fun month(year: Int, month: Int, count: Int, random: Random): String = (0 until count).joinToString("\n") { i ->
        ListenBrainzTestExports.listen(
            at = 1_600_000_000L + year * 1_000_000L + month * 10_000L + i,
            artist = "Artist ${random.nextInt(500)}",
            title = "Song ${random.nextInt(5_000)} ${"x".repeat(random.nextInt(40))}",
            durationMs = 120_000L + random.nextInt(200_000),
        )
    }

    @Test
    fun readsDeflatedEntriesWithDataDescriptors() {
        val random = Random(7)
        val entries = listOf(
            "user.json" to "{\"user_name\": \"someone\"}",
            "feedback.jsonl" to "{\"recording_msid\": \"x\", \"score\": 1}\n".repeat(2_000),
            "listens/2023/1.jsonl" to month(2023, 1, 9_000, random),
            "listens/2023/2.jsonl" to month(2023, 2, 1, random),
            "pinned_recording.jsonl" to "",
            "listens/2024/1.jsonl" to month(2024, 1, 12_345, random),
        )
        val bytes = zip(entries)
        for (chunk in listOf(1 shl 20, 4_093, 1)) {
            val rows = if (chunk == 1) rows(bytes.copyOf(), 1) else rows(bytes, chunk)
            assertEquals(9_000 + 1 + 12_345, rows.size, "chunk $chunk")
            assertEquals(rows.map { it.listenedAtSeconds }, rows.map { it.listenedAtSeconds }.sorted())
        }
    }

    @Test
    fun readsStoredAndDeflatedEntriesSideBySide() {
        val random = Random(11)
        val bytes = zip(
            listOf(
                "listens/2022/5.jsonl" to month(2022, 5, 300, random),
                "listens/2022/6.jsonl" to month(2022, 6, 200, random),
            ),
            stored = setOf("listens/2022/5.jsonl"),
        )
        assertEquals(500, rows(bytes, 8_192).size)
    }

    @Test
    fun aLargeExportCompactsToTheNewestListensTheHistoryCanHold() = runTest {
        val library = (0 until 2_000).map { TrackDescriptor(id = TrackId("t$it"), artist = "Artist ${it % 50}", title = "Song $it") }
        // 200,000 listens in shuffled months, many repeating the same track and second.
        val months = (1..20).shuffled(Random(5)).map { month ->
            "listens/2020/$month.jsonl" to (0 until 10_000).joinToString("\n") { i ->
                val track = (month * 7 + i) % 2_000
                ListenBrainzTestExports.listen(1_500_000_000L + month * 100_000L + i / 2 * 2, "Artist ${track % 50}", "Song $track")
            }
        }
        val bytes = zip(months)
        val history = DefaultListeningHistory(object : HistoryStore {
            val lines = mutableListOf<String>()
            override suspend fun append(line: String) { lines += line }
            override suspend fun readAll(): List<String> = lines.toList()
            override suspend fun replaceAll(lines: List<String>) { this.lines.clear(); this.lines += lines }
            override suspend fun clear() = lines.clear()
        })
        val library2 = object : MusicLibrary {
            override suspend fun tracks() = library
            override suspend fun hide(trackId: TrackId) = Unit
            override suspend fun unhide(trackId: TrackId) = Unit
            override suspend fun hiddenTracks() = emptyList<TrackDescriptor>()
            override suspend fun hasHiddenTracks() = false
            override suspend fun unhideAll() = Unit
            override suspend fun sources() = emptyList<LibrarySource>()
            override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) = Unit
        }
        val report = ListenBrainzImporter(history, library2, now = { 1_800_000_000_000L }, maxHistoryEvents = 5_000)
            .import(source = { ListenBrainzTestExports.input(bytes, 65_536) })
        assertEquals(200_000, report.listensRead)
        // 1/50 of the bound stays free for later plays.
        assertEquals(4_900, report.imported)
        assertEquals(200_000L, report.imported + report.overLimit + report.alreadyPresent + report.notInLibrary)
        val events = history.allEvents()
        assertEquals(4_900, events.size)
        // The newest month is month 20; everything kept starts at or after its first listen.
        assertEquals(true, events.all { it.startedAtMs >= (1_500_000_000L + 19 * 100_000L) * 1000 })
    }

    @Test
    fun corruptCompressedDataFailsTheRead() {
        val bytes = zip(listOf("listens/2023/1.jsonl" to month(2023, 1, 2_000, Random(3))))
        // Inside the first entry's compressed data, past its 50-byte local header.
        for (i in 80 until 400) bytes[i] = (bytes[i].toInt() xor 0x5A).toByte()
        assertFailsWith<IllegalArgumentException> { rows(bytes, 4_096) }
    }
}
