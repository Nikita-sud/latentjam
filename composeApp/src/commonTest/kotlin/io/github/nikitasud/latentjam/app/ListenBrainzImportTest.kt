/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultListeningHistory
import io.github.nikitasud.latentjam.history.HistoryStore
import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.library.LibrarySource
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ListenBrainzMatcherTest {
    private fun track(id: String, artist: String, title: String, album: String? = null, durationMs: Long? = 200_000L) =
        TrackDescriptor(id = TrackId(id), artist = artist, title = title, album = album, durationMs = durationMs)

    private fun row(artist: String?, title: String?, release: String? = null, durationMs: Long? = null) =
        ListenBrainzRow(1_700_000_000, artist, title, release, durationMs)

    private fun ListenBrainzMatcher.id(row: ListenBrainzRow): String? = match(row).takeIf { it >= 0 }?.let { tracks[it].id.value }

    @Test
    fun matchesCaseAndWhitespaceAndUnicodeForms() {
        val matcher = ListenBrainzMatcher(listOf(track("1", "Sigur Rós", "Hoppípolla"), track("2", "AC/DC", "T.N.T.")))
        // A decomposed ó and í, other case, extra spaces.
        assertEquals("1", matcher.id(row("  sigur rós ", "HOPPÍPOLLA")))
        assertEquals("2", matcher.id(row("ac/dc", "t.n.t.")))
    }

    @Test
    fun aSecondPassDropsCreditsVersionSuffixesDiacriticsAndPunctuation() {
        val matcher = ListenBrainzMatcher(
            listOf(
                track("1", "The Beatles", "Here Comes the Sun"),
                track("2", "Daft Punk", "Get Lucky"),
                track("3", "Beyoncé", "Halo"),
                track("4", "Simon & Garfunkel", "The Boxer"),
            ),
        )
        assertEquals("1", matcher.id(row("The Beatles", "Here Comes the Sun (Remastered 2009)")))
        assertEquals("1", matcher.id(row("The Beatles", "Here Comes the Sun - 2019 Mix")))
        assertEquals("2", matcher.id(row("Daft Punk feat. Pharrell Williams", "Get Lucky (feat. Pharrell Williams)")))
        assertEquals("2", matcher.id(row("Daft Punk ft. Pharrell", "Get Lucky [Radio Edit]")))
        assertEquals("3", matcher.id(row("Beyonce", "Halo")))
        assertEquals("4", matcher.id(row("Simon and Garfunkel", "The Boxer")))
        assertEquals(ListenBrainzMatcher.NONE, matcher.match(row("The Beatles", "Something")))
        assertEquals(ListenBrainzMatcher.NONE, matcher.match(row(null, "Halo")))
    }

    @Test
    fun theTracksOwnCreditedArtistsAreKeysToo() {
        val solo = TrackDescriptor(
            id = TrackId("1"), artist = "Calvin Harris & Dua Lipa", title = "One Kiss", artists = listOf("Calvin Harris", "Dua Lipa"),
        )
        val matcher = ListenBrainzMatcher(listOf(solo))
        assertEquals("1", matcher.id(row("Calvin Harris", "One Kiss")))
        assertEquals("1", matcher.id(row("Calvin Harris & Dua Lipa", "One Kiss")))
    }

    @Test
    fun durationPicksTheClosestVersionWithinTheTolerance() {
        val matcher = ListenBrainzMatcher(
            listOf(track("a", "Band", "Song", durationMs = 180_000), track("b", "Band", "Song", durationMs = 420_000)),
        )
        assertEquals("a", matcher.id(row("Band", "Song", durationMs = 183_000)))
        assertEquals("b", matcher.id(row("Band", "Song", durationMs = 415_000)))
        assertEquals(ListenBrainzMatcher.AMBIGUOUS, matcher.match(row("Band", "Song", durationMs = 300_000)))
        // Without a duration two different lengths cannot be told apart.
        assertEquals(ListenBrainzMatcher.AMBIGUOUS, matcher.match(row("Band", "Song")))
    }

    @Test
    fun theReleaseNarrowsCandidatesFirst() {
        val matcher = ListenBrainzMatcher(
            listOf(
                track("a", "Band", "Song", album = "Live at Home", durationMs = 300_000),
                track("b", "Band", "Song", album = "Studio", durationMs = 200_000),
            ),
        )
        assertEquals("a", matcher.id(row("Band", "Song", release = "live at home")))
        assertEquals("b", matcher.id(row("Band", "Song", release = "Studio")))
    }

    @Test
    fun copiesOfOneRecordingResolveToTheFirstIdWithoutADuration() {
        val matcher = ListenBrainzMatcher(
            listOf(track("z", "Band", "Song", durationMs = 200_500), track("m", "Band", "Song", durationMs = 200_000)),
        )
        assertEquals("m", matcher.id(row("Band", "Song")))
    }

    @Test
    fun aLoneCandidateFarFromTheListenedLengthIsAnotherVersion() {
        val matcher = ListenBrainzMatcher(listOf(track("1", "Band", "Song", durationMs = 200_000)))
        // An extended mix ("Song (Extended Mix)" loses its suffix in the loose pass) is not this track.
        assertEquals(ListenBrainzMatcher.AMBIGUOUS, matcher.match(row("Band", "Song (Extended Mix)", durationMs = 420_000)))
        // Rounded or edition-to-edition differences still match.
        assertEquals("1", matcher.id(row("Band", "Song", durationMs = 225_000)))
        assertEquals("1", matcher.id(row("Band", "Song")))
    }

    @Test
    fun mappedNamesAreTriedWhenTheSubmittedOnesFindNothing() {
        val matcher = ListenBrainzMatcher(listOf(track("1", "Кино", "Группа крови")))
        val mapped = ListenBrainzRow(1_700_000_000, "Kino", "Gruppa krovi", null, mappedArtist = "Кино", mappedTitle = "Группа крови")
        assertEquals("1", matcher.id(mapped))
    }
}

class ListenBrainzImportTest {
    private val now = 1_800_000_000_000L
    private val tracks = listOf(
        TrackDescriptor(id = TrackId("t1"), artist = "Artist", title = "First", durationMs = 200_000L),
        TrackDescriptor(id = TrackId("t2"), artist = "Artist", title = "Second", durationMs = 240_000L),
        TrackDescriptor(id = TrackId("t3"), artist = "Other", title = "Third", durationMs = null),
    )

    private fun export(vararg lines: String): PickedExport {
        val bytes = lines.joinToString("\n").encodeToByteArray()
        return PickedExport(opener = { ListenBrainzTestExports.input(bytes, chunk = 4096) })
    }

    private fun importer(history: DefaultListeningHistory, maxHistoryEvents: Int = ListenBrainzImporter.MAX_HISTORY_EVENTS) =
        ListenBrainzImporter(history, FakeLibrary(tracks), now = { now }, maxHistoryEvents = maxHistoryEvents)

    @Test
    fun importsMatchedListensInTheImportShapeAndCountsTheRest() = runTest {
        val history = DefaultListeningHistory(MemoryHistoryStore())
        val picked = export(
            ListenBrainzTestExports.listen(1_700_000_000, "Artist", "First"),
            ListenBrainzTestExports.listen(1_700_000_300, "artist", "Second (Remastered)"),
            ListenBrainzTestExports.listen(1_700_000_600, "Artist", "First"),
            ListenBrainzTestExports.listen(1_700_000_900, "Other", "Third"),
            ListenBrainzTestExports.listen(1_700_001_200, "Nobody", "Unknown"),
            // The same row twice in one export is one listen.
            ListenBrainzTestExports.listen(1_700_001_500, "Artist", "Second"),
            ListenBrainzTestExports.listen(1_700_001_500, "Artist", "Second"),
            // Far in the future: a broken clock, not a listen.
            ListenBrainzTestExports.listen(1_900_000_000, "Artist", "First"),
        )
        val report = importer(history).import(source = picked::open)
        assertEquals(8, report.listensRead)
        assertEquals(5, report.imported)
        assertEquals(3, report.songs)
        assertEquals(1, report.notInLibrary)
        assertEquals(1, report.alreadyPresent)
        assertEquals(1, report.unreadable)
        assertEquals(0, report.overLimit)

        val events = history.allEvents()
        assertEquals(events.map { it.startedAtMs }, events.map { it.startedAtMs }.sorted())
        val first = events.first()
        assertEquals(
            ListenEvent(
                trackId = TrackId("t1"), startedAtMs = 1_700_000_000_000L, playedMs = 200_000L,
                trackDurationMs = 200_000L, completed = true, skipped = false,
            ),
            first,
        )
        // Exactly what TransGraph and the habit planner read as an import.
        assertTrue(events.all { it.origin == null && it.listenedMs == null && !it.skipped && it.shuffleMode == null })
        val unknownLength = events.single { it.trackId == TrackId("t3") }
        assertEquals(0L, unknownLength.playedMs)
        assertEquals(false, unknownLength.completed)
    }

    @Test
    fun importingTheSameExportAgainAddsNothing() = runTest {
        val history = DefaultListeningHistory(MemoryHistoryStore())
        val lines = arrayOf(
            ListenBrainzTestExports.listen(1_700_000_000, "Artist", "First"),
            ListenBrainzTestExports.listen(1_700_000_300, "Artist", "Second"),
        )
        assertEquals(2, importer(history).import(source = export(*lines)::open).imported)
        val before = history.allEvents()
        val again = importer(history).import(source = export(*lines)::open)
        assertEquals(0, again.imported)
        assertEquals(2, again.alreadyPresent)
        assertEquals(before, history.allEvents())
    }

    @Test
    fun aListenNearAPlayRecordedInTheAppIsThatPlay() = runTest {
        val history = DefaultListeningHistory(MemoryHistoryStore())
        val inApp = ListenEvent(
            trackId = TrackId("t1"), startedAtMs = 1_700_000_000_000L - 90_000L, playedMs = 200_000L,
            trackDurationMs = 200_000L, completed = true, skipped = false, listenedMs = 200_000L,
            origin = ListenOrigin(start = ListenStart.USER_PICK),
        )
        history.record(inApp)
        val report = importer(history).import(
            source = export(
                // 90 s after the in-app start: the same play, scrobbled.
                ListenBrainzTestExports.listen(1_700_000_000, "Artist", "First"),
                // 10 minutes later: a new listen.
                ListenBrainzTestExports.listen(1_700_000_600, "Artist", "First"),
                // Another track at the same moment is unaffected.
                ListenBrainzTestExports.listen(1_700_000_000, "Artist", "Second"),
            )::open,
        )
        assertEquals(2, report.imported)
        assertEquals(1, report.alreadyPresent)
        assertEquals(3, history.allEvents().size)
        assertEquals(inApp, history.allEvents().first())
    }

    @Test
    fun aFullHistoryKeepsTheNewestImportedListens() = runTest {
        val history = DefaultListeningHistory(MemoryHistoryStore())
        val lines = (0 until 10).map { ListenBrainzTestExports.listen(1_700_000_000L + it * 1_000, "Artist", "First") }
        val report = importer(history, maxHistoryEvents = 4).import(source = export(*lines.toTypedArray())::open)
        assertEquals(4, report.imported)
        assertEquals(6, report.overLimit)
        assertEquals((6 until 10).map { (1_700_000_000L + it * 1_000) * 1000 }, history.allEvents().map { it.startedAtMs })
    }

    @Test
    fun aListenAlreadyCreditedToAnotherCopyOfTheSongIsNotAddedAgain() = runTest {
        val history = DefaultListeningHistory(MemoryHistoryStore())
        val album = TrackDescriptor(id = TrackId("c1"), artist = "Artist", title = "Song", album = "Album", durationMs = 200_000L)
        val compilation = TrackDescriptor(id = TrackId("c2"), artist = "Artist", title = "Song", album = "Hits", durationMs = 200_000L)
        val line = ListenBrainzTestExports.listen(1_700_000_000, "Artist", "Song", release = "Hits")
        val first = ListenBrainzImporter(history, FakeLibrary(listOf(album)), now = { now }).import(source = export(line)::open)
        assertEquals(1, first.imported)
        // The compilation joins the library; the same listen now narrows to it by release.
        val again = ListenBrainzImporter(history, FakeLibrary(listOf(album, compilation)), now = { now })
            .import(source = export(line)::open)
        assertEquals(0, again.imported)
        assertEquals(1, again.alreadyPresent)
        assertEquals(listOf(TrackId("c1")), history.allEvents().map { it.trackId })
    }

    @Test
    fun theImportLeavesRoomForLaterPlaysAndANoOpReimportStillWorks() = runTest {
        val history = DefaultListeningHistory(MemoryHistoryStore())
        val lines = (0 until 120).map { ListenBrainzTestExports.listen(1_700_000_000L + it * 1_000, "Artist", "First") }
        // 100 allowed, 1/50 of it kept free.
        val report = importer(history, maxHistoryEvents = 100).import(source = export(*lines.toTypedArray())::open)
        assertEquals(98, report.imported)
        // Plays recorded later may pass the bound; importing again must not then fail.
        repeat(5) {
            history.record(
                ListenEvent(
                    trackId = TrackId("t2"), startedAtMs = now + it, playedMs = 1L,
                    trackDurationMs = 240_000L, completed = false, skipped = true,
                ),
            )
        }
        val again = importer(history, maxHistoryEvents = 100).import(source = export(*lines.toTypedArray())::open)
        assertEquals(0, again.imported)
        assertEquals(103, history.allEvents().size)
    }

    @Test
    fun anEmptyLibraryIsReportedRatherThanImportingNothing() = runTest {
        val importer = ListenBrainzImporter(DefaultListeningHistory(MemoryHistoryStore()), FakeLibrary(emptyList()), now = { now })
        assertFailsWith<ListenBrainzLibraryEmptyException> {
            importer.import(source = export(ListenBrainzTestExports.listen(1_700_000_000, "Artist", "First"))::open)
        }
    }

    @Test
    fun theSourceIsClosedAfterTheImport() = runTest {
        var closed = false
        val bytes = ListenBrainzTestExports.listen(1_700_000_000, "Artist", "First").encodeToByteArray()
        val inner = ListenBrainzTestExports.input(bytes)
        val picked = PickedExport(
            opener = {
                object : ExportByteSource {
                    override fun read(buffer: ByteArray, offset: Int, length: Int) = inner.read(buffer, offset, length)
                    override fun close() {
                        closed = true
                    }
                }
            },
        )
        importer(DefaultListeningHistory(MemoryHistoryStore())).import(source = picked::open)
        assertTrue(closed)
    }

    @Test
    fun aZipExportGoesThroughTheSameEntryPoint() = runTest {
        val history = DefaultListeningHistory(MemoryHistoryStore())
        val zip = ListenBrainzTestExports.storedZip(
            "user.json" to "{\"user_name\": \"someone\"}",
            "listens/2023/11.jsonl" to ListenBrainzTestExports.listen(1_700_000_000, "Artist", "First"),
            "listens/2023/12.jsonl" to ListenBrainzTestExports.listen(1_702_000_000, "Artist", "Second"),
        )
        val report = importer(history).import(source = PickedExport(opener = { ListenBrainzTestExports.input(zip, 13) })::open)
        assertEquals(2, report.imported)
        assertEquals(listOf(TrackId("t1"), TrackId("t2")), history.allEvents().map { it.trackId })
    }

    private class MemoryHistoryStore : HistoryStore {
        private val lines = mutableListOf<String>()
        override suspend fun append(line: String) { lines += line }
        override suspend fun readAll(): List<String> = lines.toList()
        override suspend fun replaceAll(lines: List<String>) {
            this.lines.clear()
            this.lines += lines
        }
        override suspend fun clear() { lines.clear() }
    }

    private class FakeLibrary(private val all: List<TrackDescriptor>) : MusicLibrary {
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
