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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LastFmCsvTest {
    private fun read(text: String, chunk: Int = 7, bom: Boolean = false): Pair<List<ListenBrainzRow>, ListenBrainzReadSummary> {
        val bytes = (if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) else ByteArray(0)) + text.encodeToByteArray()
        val rows = mutableListOf<ListenBrainzRow>()
        val summary = ListenBrainzExportReader.read(ListenBrainzTestExports.input(bytes, chunk)) { rows += it }
        return rows to summary
    }

    /** 03 Nov 2026 15:29 UTC. */
    private val nov3 = 1_793_719_740L

    @Test
    fun theCurrentToolsFileWithItsBomQuotesAndNowPlayingRow() {
        val (rows, summary) = read(
            "Now Artist,Now Album,Now Playing,\n" +
                "TEEN,In Limbo,Roses & Wine,\"03 Nov 2026, 15:29\"\n" +
                "\"Crosby, Stills & Nash\",,\"Suite: \"\"Judy Blue Eyes\"\"\",\"03 Nov 2026, 15:20\"\n" +
                "Земфира,Вендетта,\"Line\nbreak\",\"02 Nov 2026, 09:05\"\n" +
                "坂本龍一,,戦場のメリークリスマス,\"01 Jan 2020, 00:00\"\n",
            bom = true,
        )
        assertEquals(4, rows.size)
        assertEquals(0, summary.unreadable)
        assertEquals(ListenBrainzRow(nov3, "TEEN", "Roses & Wine", "In Limbo"), rows[0])
        assertEquals("Crosby, Stills & Nash", rows[1].artist)
        assertEquals("Suite: \"Judy Blue Eyes\"", rows[1].title)
        assertNull(rows[1].release)
        assertEquals(nov3 - 9 * 60, rows[1].listenedAtSeconds)
        assertEquals("Земфира", rows[2].artist)
        assertEquals("Line\nbreak", rows[2].title)
        assertEquals("戦場のメリークリスマス", rows[3].title)
        assertEquals(1_577_836_800L, rows[3].listenedAtSeconds)
    }

    @Test
    fun theOlderFilesWithoutTheCommaOrQuotesAndWithCrlf() {
        val (rows, summary) = read("Myrkur,,Hævnen,23 Feb 2016 17:55\r\nSaltillo,,Remember Me?,22 Feb 2016 18:08\r\n")
        assertEquals(0, summary.unreadable)
        assertEquals(listOf("Hævnen", "Remember Me?"), rows.map { it.title })
        assertEquals(listOf(1_456_250_100L, 1_456_164_480L), rows.map { it.listenedAtSeconds })
        assertNull(rows[0].release)
    }

    @Test
    fun theFirstWeeksThreeColumns() {
        val (rows, _) = read("Band,Song,22 May 2014 20:11\nBand,Other,22 May 2014 20:07\n")
        assertEquals(listOf("Song", "Other"), rows.map { it.title })
        assertEquals(1_400_789_460L, rows[0].listenedAtSeconds)
        assertNull(rows[0].release)
    }

    @Test
    fun aHeaderNamesTheColumnsAndUnixSecondsWin() {
        val (rows, _) = read(
            "uts,utc_time,artist,artist_mbid,album,album_mbid,track,track_mbid\r\n" +
                "1793719740,\"03 Nov 2026, 15:29\",Artist,,Album,,Song,0F2E9C33-1234-4C1A-9ABC-0123456789AB\r\n" +
                ",,Now,,,,Playing,\r\n" +
                "1793719000,,Artist,,,,Second,\r\n",
        )
        assertEquals(2, rows.size)
        assertEquals(ListenBrainzRow(nov3, "Artist", "Song", "Album", recordingMbid = "0f2e9c33-1234-4c1a-9abc-0123456789ab"), rows[0])
        assertEquals(1_793_719_000L, rows[1].listenedAtSeconds)
        // Another exporter's order and names.
        val (other, _) = read("artist,album,name,date,mbid\nA,B,C,\"03 Nov 2026, 15:29\",\n")
        assertEquals(listOf(ListenBrainzRow(nov3, "A", "C", "B")), other)
    }

    @Test
    fun anEmptyArtistStillCountsAndBrokenRowsAreUnreadable() {
        val (rows, summary) = read(
            ",,Untitled,03 Nov 2026 15:29\n" +
                "A,B,C,yesterday\n" +
                "A,B\n" +
                "\n" +
                "A,B,C,D,E\n" +
                "A,B,C,03 Nov 2026 15:29\n",
        )
        assertEquals(listOf(null, "A"), rows.map { it.artist })
        assertEquals(3, summary.unreadable)
    }

    @Test
    fun aRecordLongerThanTheLimitIsSkippedAndTheNextOneRead() {
        val long = "x".repeat(ListenBrainzExportReader.MAX_RECORD_BYTES + 10)
        val (rows, summary) = read("A,B,C,03 Nov 2026 15:29\nA,B,\"$long\",03 Nov 2026 15:28\nA,B,D,03 Nov 2026 15:27\n", chunk = 65_536)
        assertEquals(listOf("C", "D"), rows.map { it.title })
        assertEquals(1, summary.unreadable)
    }

    @Test
    fun whatIsNotCsvIsRefused() {
        assertFailsWith<ListenBrainzFormatException> { read("\u0000\u0001binary junk,with,commas\n") }
        assertFailsWith<ListenBrainzFormatException> { read("#EXTM3U\n#EXTINF:123,Artist - Song\n/music/song.mp3\n") }
        assertFailsWith<ListenBrainzFormatException> { read("just some words, and more words\n") }
        // JSON is still JSON.
        val (rows, _) = read(ListenBrainzTestExports.listen(1_700_000_000, "A", "B"))
        assertEquals(1, rows.size)
    }

    @Test
    fun anArtistInBracketsOrBracesIsNoJson() {
        val (rows, _) = read("[dunkelbunt],Album,Song,\"03 Nov 2026, 15:29\"\n{Artist},,Other,\"03 Nov 2026, 15:20\"\n", bom = true)
        assertEquals(listOf("[dunkelbunt]", "{Artist}"), rows.map { it.artist })
        val (braced, _) = read("{Artist},,Other,03 Nov 2026 15:20\n")
        assertEquals(1, braced.size)
        // A JSON array or object still opens as one.
        assertEquals(1, read("[\n  " + ListenBrainzTestExports.listen(1_700_000_000, "A", "B") + "\n]").first.size)
    }

    @Test
    fun aNumberIsNoDateAndNothingPredatesScrobbling() {
        // A track list with years, or with lengths: no Last.fm history.
        assertFailsWith<ListenBrainzFormatException> { read("Daft Punk,Discovery,One More Time,2001\nA,B,C,1999\n") }
        assertFailsWith<ListenBrainzFormatException> { read("Band,Song,320\n") }
        val (rows, summary) = read("A,B,C,03 Nov 2026 15:29\nA,B,D,2001\nA,B,E,\"01 Jan 1999, 10:00\"\nA,B,F,01 Jan 1970 00:10\n")
        assertEquals(listOf("C"), rows.map { it.title })
        assertEquals(3, summary.unreadable)
    }

    @Test
    fun proseWithCommasIsRefusedEvenWhenItEndsInOne() {
        assertFailsWith<ListenBrainzFormatException> { read("Hello, how are you, today,\nI am fine, thanks, and you,\n") }
        assertFailsWith<ListenBrainzFormatException> { read("Hello, how are you, today,\nFine thanks\n") }
        // A file holding only the track playing when it was saved is a Last.fm CSV with no listens.
        assertEquals(0, read("Artist,Album,Song,\n").first.size)
    }

    @Test
    fun aRecordOfManyEmptyFieldsIsUnreadableNotCollected() {
        val (rows, summary) = read("A,B,C,03 Nov 2026 15:29\n" + ",".repeat(200_000) + "\nA,B,D,03 Nov 2026 15:28\n", chunk = 65_536)
        assertEquals(listOf("C", "D"), rows.map { it.title })
        assertEquals(1, summary.unreadable)
    }

    @Test
    fun lastFmDates() {
        assertEquals(nov3, LastFmCsvReader.dateSeconds("03 Nov 2026, 15:29"))
        assertEquals(nov3, LastFmCsvReader.dateSeconds("3 nov 2026 15:29"))
        assertEquals(nov3, LastFmCsvReader.dateSeconds("2026-11-03T15:29:00Z"))
        assertNull(LastFmCsvReader.dateSeconds("31 Foo 2026 15:29"))
        assertNull(LastFmCsvReader.dateSeconds("03 Nov 2026 25:29"))
        assertNull(LastFmCsvReader.dateSeconds("1793719740"))
    }

    @Test
    fun aHundredThousandRowsStreamThroughASmallBuffer() {
        val input = GeneratedCsv(100_000)
        var rows = 0L
        var last = 0L
        val summary = ListenBrainzExportReader.read(input) { row ->
            rows++
            last = row.listenedAtSeconds
        }
        assertEquals(100_000, rows)
        assertEquals(100_000, summary.rows)
        assertFalse(summary.truncated)
        assertTrue(last > 0)
    }

    private val tracks = listOf(
        TrackDescriptor(id = TrackId("t1"), artist = "Crosby, Stills & Nash", title = "Suite: Judy Blue Eyes", durationMs = 450_000L),
        TrackDescriptor(id = TrackId("t2"), artist = "Земфира", title = "Хочешь?", durationMs = 220_000L),
        TrackDescriptor(
            id = TrackId("t3"), artist = "Calvin Harris & Dua Lipa", title = "One Kiss",
            artists = listOf("Calvin Harris", "Dua Lipa"), durationMs = 214_000L,
        ),
    )

    private fun csv(text: String) = PickedExport(opener = { ListenBrainzTestExports.input(text.encodeToByteArray(), chunk = 4096) })

    @Test
    fun aCsvImportMatchesWritesAndDoesNotDoubleOnReimport() = runTest {
        val history = DefaultListeningHistory(MemoryStore())
        val file = "Now,,Playing,\n" +
            "\"Crosby, Stills & Nash\",CSN,\"Suite: Judy Blue Eyes\",\"03 Nov 2026, 15:29\"\n" +
            "Земфира,Вендетта,Хочешь?,\"03 Nov 2026, 15:20\"\n" +
            "Calvin Harris,One Kiss,One Kiss,\"02 Nov 2026, 10:00\"\n" +
            "Somebody Else,,Unknown,\"02 Nov 2026, 09:00\"\n"
        val importer = ListenBrainzImporter(history, Library(tracks), now = { 1_800_000_000_000L })
        val first = importer.import(source = csv(file)::open)
        assertEquals(4, first.listensRead)
        assertEquals(3, first.imported)
        assertEquals(1, first.notInLibrary)
        assertEquals(0, first.unreadable)
        assertEquals(setOf("t1", "t2", "t3"), history.allEvents().map { it.trackId.value }.toSet())
        assertEquals(nov3 * 1000, history.allEvents().maxOf { it.startedAtMs })

        val before = history.allEvents()
        val again = ListenBrainzImporter(history, Library(tracks), now = { 1_800_000_000_000L }).import(source = csv(file)::open)
        assertEquals(0, again.imported)
        assertEquals(3, again.alreadyPresent)
        assertEquals(before, history.allEvents())
    }

    @Test
    fun repeatsAWholePlayApartStayTwoListensAtMinutePrecision() = runTest {
        val short = TrackDescriptor(id = TrackId("s"), artist = "A", title = "S", durationMs = 150_000L)
        val shorter = TrackDescriptor(id = TrackId("r"), artist = "A", title = "R", durationMs = 100_000L)
        val history = DefaultListeningHistory(MemoryStore())
        // Played 15:29:10 and 15:31:40 (150 s), 15:29:05 and 15:30:45 (100 s); one play scrobbled twice in one minute.
        val report = ListenBrainzImporter(history, Library(listOf(short, shorter)), now = { 1_800_000_000_000L }).import(
            source = csv(
                "A,,S,\"03 Nov 2026, 15:31\"\nA,,S,\"03 Nov 2026, 15:29\"\n" +
                    "A,,R,\"02 Nov 2026, 15:30\"\nA,,R,\"02 Nov 2026, 15:29\"\n" +
                    "A,,S,\"01 Nov 2026, 10:00\"\nA,,S,\"01 Nov 2026, 10:00\"\n",
            )::open,
        )
        assertEquals(5, report.imported)
        assertEquals(1, report.alreadyPresent)
    }

    /** `artist,album,name,date` rows produced on demand: the file is never held. */
    internal class GeneratedCsv(private val rows: Int) : ExportByteSource {
        private var row = 0
        private var pending = ByteArray(0)
        private var at = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at == pending.size) {
                if (row == rows) return -1
                val seconds = 1_700_000_000L - row * 180L
                val song = row % 3001
                pending = ("\"Artist ${song % 997}, the Band\",Album ${row % 83},Song $song," +
                    "${LastFmCsvTestDates.format(seconds)}\n").encodeToByteArray()
                at = 0
                row++
            }
            val count = minOf(length, pending.size - at)
            pending.copyInto(buffer, offset, at, at + count)
            at += count
            return count
        }

        override fun close() = Unit
    }

    private class MemoryStore : HistoryStore {
        private val lines = mutableListOf<String>()
        override suspend fun append(line: String) { lines += line }
        override suspend fun readAll(): List<String> = lines.toList()
        override suspend fun replaceAll(lines: List<String>) {
            this.lines.clear()
            this.lines += lines
        }
        override suspend fun clear() { lines.clear() }
    }

    private class Library(private val all: List<TrackDescriptor>) : MusicLibrary {
        override suspend fun tracks(): List<TrackDescriptor> = all
        override suspend fun allKnownTracks(): List<TrackDescriptor> = all
        override suspend fun hide(trackId: TrackId) = Unit
        override suspend fun unhide(trackId: TrackId) = Unit
        override suspend fun hiddenTracks(): List<TrackDescriptor> = emptyList()
        override suspend fun hasHiddenTracks(): Boolean = false
        override suspend fun unhideAll() = Unit
        override suspend fun sources(): List<LibrarySource> = emptyList()
        override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) = Unit
    }
}

/** Last.fm's date text for epoch [seconds], UTC: `14 Nov 2023, 22:13`. */
internal object LastFmCsvTestDates {
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun format(seconds: Long): String {
        val days = seconds.floorDiv(86_400L)
        val inDay = seconds - days * 86_400L
        // Howard Hinnant's civil_from_days.
        val z = days + 719_468
        val era = z.floorDiv(146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        val hour = inDay / 3600
        val minute = (inDay % 3600) / 60
        return "\"${day.toString().padStart(2, '0')} ${months[(month - 1).toInt()]} $year, " +
            "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}\""
    }
}
