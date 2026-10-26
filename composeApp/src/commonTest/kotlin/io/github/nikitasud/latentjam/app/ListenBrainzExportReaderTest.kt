/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListenBrainzExportReaderTest {

    private fun read(bytes: ByteArray, chunk: Int = Int.MAX_VALUE): Pair<List<ListenBrainzRow>, ListenBrainzReadSummary> {
        val rows = mutableListOf<ListenBrainzRow>()
        val summary = ListenBrainzExportReader.read(ListenBrainzTestExports.input(bytes, chunk)) { rows += it }
        return rows to summary
    }

    @Test
    fun readsJsonLinesAndSkipsBrokenLines() {
        val text = listOf(
            ListenBrainzTestExports.listen(1_700_000_000, "Artist", "Song", release = "Album", durationMs = 200_000),
            "{\"listened_at\": 1700000100, \"track_metadata\": {broken",
            "{\"user_name\": \"someone\"}",
            ListenBrainzTestExports.listen(1_700_000_200, "Other", "Tune"),
            "",
        ).joinToString("\n")
        val (rows, summary) = read(text.encodeToByteArray())
        assertEquals(listOf("Song", "Tune"), rows.map { it.title })
        assertEquals(2, summary.rows)
        assertEquals(1, summary.unreadable)
        assertFalse(summary.truncated)
        assertEquals(ListenBrainzRow(1_700_000_000, "Artist", "Song", "Album", durationMs = 200_000), rows[0])
    }

    @Test
    fun readsTheOlderJsonArrayWithAByteOrderMark() {
        val text = "﻿[\n" + listOf(
            ListenBrainzTestExports.listen(1_600_000_000, "A", "One"),
            ListenBrainzTestExports.listen(1_600_000_300, "B", "Two"),
        ).joinToString(",\n") + "\n]\n"
        val (rows, summary) = read(text.encodeToByteArray(), chunk = 7)
        assertEquals(listOf("One", "Two"), rows.map { it.title })
        assertEquals(2, summary.rows)
    }

    @Test
    fun aBrokenJsonArrayFailsTheRead() {
        val text = "[" + ListenBrainzTestExports.listen(1_600_000_000, "A", "One") + ", {\"listened_at\": "
        assertFailsWith<IllegalArgumentException> { read(text.encodeToByteArray()) }
    }

    @Test
    fun decodesEscapesAndMultibyteText() {
        val line = "{\"listened_at\":1700000000,\"track_metadata\":{\"artist_name\":\"Bj\\u00f6rk \\ud83c\\udfb5\"," +
            "\"track_name\":\"Привет \\\"мир\\\" ✓\"}}"
        val (rows, _) = read(line.encodeToByteArray(), chunk = 3)
        assertEquals("Björk 🎵", rows.single().artist)
        assertEquals("Привет \"мир\" ✓", rows.single().title)
    }

    @Test
    fun readsDurationsMbidsAndMappedNames() {
        val line = """{"listened_at": 1700000000, "track_metadata": {"artist_name": "a", "track_name": "t",
            "additional_info": {"duration": 215.5},
            "mbid_mapping": {"recording_mbid": "0F8D2E36-7C4B-4C8C-9F5E-1A2B3C4D5E6F", "recording_name": "Mapped",
            "artists": [{"artist_credit_name": "X", "join_phrase": " & "}, {"artist_credit_name": "Y", "join_phrase": ""}]}}}"""
            .replace("\n", " ")
        val row = read(line.encodeToByteArray()).first.single()
        assertEquals(215_500L, row.durationMs)
        assertEquals("0f8d2e36-7c4b-4c8c-9f5e-1a2b3c4d5e6f", row.recordingMbid)
        assertEquals("Mapped", row.mappedTitle)
        assertEquals("X & Y", row.mappedArtist)
    }

    @Test
    fun readsTimestampsInSecondsMillisecondsAndIso() {
        assertEquals(1_700_000_000L, ListenBrainzExportReader.listenedAtSeconds(1_700_000_000L))
        assertEquals(1_700_000_000L, ListenBrainzExportReader.listenedAtSeconds(1_700_000_000_123L))
        assertEquals(1_700_000_000L, ListenBrainzExportReader.listenedAtSeconds("1700000000"))
        assertEquals(1_700_000_000L, ListenBrainzExportReader.listenedAtSeconds("2023-11-14T22:13:20Z"))
        assertEquals(1_700_000_000L, ListenBrainzExportReader.listenedAtSeconds("2023-11-15T00:13:20+02:00"))
        assertEquals(1_700_000_000L, ListenBrainzExportReader.listenedAtSeconds("2023-11-14 22:13:20.5"))
        assertNull(ListenBrainzExportReader.listenedAtSeconds("yesterday"))
        assertNull(ListenBrainzExportReader.listenedAtSeconds(null))
    }

    @Test
    fun readsTheApiEnvelope() {
        val text = "{\"payload\": {\"count\": 2, \"listens\": [" +
            ListenBrainzTestExports.listen(1_700_000_000, "A", "One") + "," +
            ListenBrainzTestExports.listen(1_700_000_001, "A", "Two") + "]}}"
        assertEquals(2, read(text.encodeToByteArray()).first.size)
    }

    @Test
    fun readsTheListenFilesOfAStoredZipAndSkipsTheRest() {
        val zip = ListenBrainzTestExports.storedZip(
            "user.json" to "{\"user_name\": \"someone\", \"listened_at\": 1}",
            "feedback.jsonl" to ListenBrainzTestExports.listen(1_700_000_009, "Nope", "Feedback"),
            "listens/" to "",
            "listens/2023/11.jsonl" to ListenBrainzTestExports.listen(1_700_000_000, "A", "One") + "\n",
            "listens/2023/12.jsonl" to ListenBrainzTestExports.listen(1_702_000_000, "B", "Two") + "\n" +
                ListenBrainzTestExports.listen(1_702_000_500, "C", "Three"),
        )
        val (rows, summary) = read(zip, chunk = 11)
        assertEquals(listOf("One", "Two", "Three"), rows.map { it.title })
        assertEquals(3, summary.rows)
        assertEquals(0, summary.unreadable)
    }

    @Test
    fun picksListenEntriesByName() {
        assertTrue(ListenBrainzExportReader.isListenEntry("listens/2024/1.jsonl"))
        assertTrue(ListenBrainzExportReader.isListenEntry("someone_lb-2023-01-01.json"))
        assertFalse(ListenBrainzExportReader.isListenEntry("user.json"))
        assertFalse(ListenBrainzExportReader.isListenEntry("pinned_recording.jsonl"))
        assertFalse(ListenBrainzExportReader.isListenEntry("__MACOSX/listens/._1.jsonl"))
        assertFalse(ListenBrainzExportReader.isListenEntry("listens/"))
    }

    @Test
    fun anEmptyFileHasNoListens() {
        val (rows, summary) = read(ByteArray(0))
        assertTrue(rows.isEmpty())
        assertEquals(0, summary.rows)
    }
}

/** Small synthetic ListenBrainz exports for the import tests. */
internal object ListenBrainzTestExports {
    fun listen(
        at: Long,
        artist: String,
        title: String,
        release: String? = null,
        durationMs: Long? = null,
    ): String = buildString {
        append("{\"listened_at\": ").append(at).append(", \"recording_msid\": \"x\", \"track_metadata\": {")
        append("\"artist_name\": \"").append(artist).append("\", \"track_name\": \"").append(title).append('"')
        if (release != null) append(", \"release_name\": \"").append(release).append('"')
        if (durationMs != null) append(", \"additional_info\": {\"duration_ms\": ").append(durationMs).append('}')
        append("}}")
    }

    /** Serves [bytes] at most [chunk] at a time, as a slow provider stream would. */
    fun input(bytes: ByteArray, chunk: Int = Int.MAX_VALUE): ExportByteSource = object : ExportByteSource {
        var at = 0
        var closed = false
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!closed)
            if (at == bytes.size) return -1
            val count = minOf(length, chunk, bytes.size - at)
            bytes.copyInto(buffer, offset, at, at + count)
            at += count
            return count
        }
        override fun close() {
            closed = true
        }
    }

    /** A ZIP of uncompressed entries with sizes in the local headers (CRCs are not checked). */
    fun storedZip(vararg entries: Pair<String, String>): ByteArray {
        val out = mutableListOf<Byte>()
        fun le16(value: Int) { out += (value and 0xFF).toByte(); out += ((value shr 8) and 0xFF).toByte() }
        fun le32(value: Int) { le16(value and 0xFFFF); le16((value ushr 16) and 0xFFFF) }
        for ((name, text) in entries) {
            val nameBytes = name.encodeToByteArray()
            val data = text.encodeToByteArray()
            le32(0x04034b50); le16(20); le16(0); le16(0); le16(0); le16(0)
            le32(0); le32(data.size); le32(data.size); le16(nameBytes.size); le16(0)
            out += nameBytes.toList(); out += data.toList()
        }
        // The central directory is never read; its signature ends the walk.
        le32(0x02014b50)
        repeat(42) { out += 0 }
        return out.toByteArray()
    }
}
