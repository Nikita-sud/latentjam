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
import kotlin.test.assertNull

/**
 * The ListenBrainz import check's findings (2026-10-29), as regression tests on a synthetic library: credits as artist
 * sets (M1), double scrobbles (L1), other versions (L2), a broken JSONL line (L3) and future rows (L4).
 */
class ListenBrainzFixesTest {

    private fun track(id: String, artist: String, title: String, artists: List<String> = emptyList(), durationMs: Long? = 200_000L) =
        TrackDescriptor(id = TrackId(id), artist = artist, artists = artists, title = title, durationMs = durationMs)

    private fun row(artist: String, title: String, durationMs: Long? = null) = ListenBrainzRow(1_700_000_000, artist, title, null, durationMs)

    private fun ListenBrainzMatcher.id(row: ListenBrainzRow): String? = match(row).takeIf { it >= 0 }?.let { tracks[it].id.value }

    // ---- M1: a credit is a set of artists

    /** Two-artist tracks, tagged with an ARTISTS list or with the joined credit only. */
    private val duets = (0 until 30).map { i ->
        val a = "Singer ${'A' + i % 26}${i / 26} One"
        val b = "Player ${'B' + i % 25}${i / 25} Two"
        if (i % 2 == 0) track("d$i", "$a & $b", "Duet number ${i + 1}", artists = listOf(a, b))
        else track("d$i", "$a & $b", "Duet number ${i + 1}")
    }

    @Test
    fun multiArtistCreditsMatchWhateverTheSeparator() {
        val matcher = ListenBrainzMatcher(duets + track("solo", "Singer A0 One", "Alone"))
        for ((i, t) in duets.withIndex()) {
            val a = "Singer ${'A' + i % 26}${i / 26} One"
            val b = "Player ${'B' + i % 25}${i / 25} Two"
            for (credit in listOf("$a, $b", "$a & $b", "$a x $b", "$a; $b", "$a feat. $b", "$a and $b", "$a with $b", "$a / $b")) {
                assertEquals(t.id.value, matcher.id(row(credit, t.title!!)), "[$credit] for ${t.artist}")
            }
            // The other order of a listed duet: the split finds the same two artists.
            if (t.artists.isNotEmpty()) assertEquals(t.id.value, matcher.id(row("$b, $a", t.title!!)), "$b, $a")
        }
    }

    @Test
    fun aCreditOfOtherArtistsNeverMatches() {
        val matcher = ListenBrainzMatcher(duets)
        assertNull(matcher.id(row("Totally Different Band", "Duet number 1")))
        assertNull(matcher.id(row("Someone, Else", "Duet number 1")))
        // A shared guest in the title is not a shared artist.
        val guest = ListenBrainzMatcher(listOf(track("p", "Herr Kuchen feat. Punjizz", "Plastikshit II (feat. Punjizz)")))
        assertNull(guest.id(row("Totally Different Band", "Plastikshit II (feat. Punjizz)")))
    }

    @Test
    fun theEmulatorCasesOfTheCheck() {
        val matcher = ListenBrainzMatcher(listOf(
            track("m", "Marvin Gaye & Tammi Terrell", "Ain't No Mountain High Enough"),
            track("t", "Tom Jones & Mousse T.", "Sex Bomb"),
        ))
        assertEquals("m", matcher.id(row("Marvin Gaye, Tammi Terrell", "Ain't No Mountain High Enough")))
        assertEquals("t", matcher.id(row("Tom Jones, Mousse T.", "Sex Bomb")))
    }

    @Test
    fun aListenCreditingMoreArtistsThanTheTrackStillMatches() {
        val matcher = ListenBrainzMatcher(listOf(
            track("w", "Karan Aujla", "Winning Speech"),
            track("i", "Faheem Abdullah, Rauhan Malik & Amir Ameer", "Ishq"),
        ))
        // The guest is unknown, so the credit stays whole: its first name qualifies only with a close length.
        assertEquals("w", matcher.id(row("Karan Aujla, Seshnolan", "Winning Speech", durationMs = 201_000L)))
        assertNull(matcher.id(row("Karan Aujla, Seshnolan", "Winning Speech")))
        assertNull(matcher.id(row("Karan Aujla, Seshnolan", "Winning Speech", durationMs = 210_000L)))
        assertEquals("i", matcher.id(row("Faheem Abdullah, Rauhan Malik, Amir Ameer", "Ishq - From \"Lost;Found\"")))
        // Sharing an artist is not enough under another title.
        assertNull(matcher.id(row("Karan Aujla, Seshnolan", "Other Speech")))
    }

    // ---- L7-2: only the listen's whole credit or its lead artist qualifies a track

    @Test
    fun theFirstNameOfABandIsNotAnotherArtistsSong() {
        // A one-word artist named like a band's first word, with the same title and another length.
        val matcher = ListenBrainzMatcher(listOf(
            track("earth", "Earth", "September", durationMs = 180_000L),
            track("tyler", "Tyler", "See You Again", durationMs = 150_000L),
            track("simon", "Simon", "America", durationMs = 240_000L),
            track("chase", "Chase", "Blind Faith", durationMs = 200_000L),
        ))
        assertNull(matcher.id(row("Earth, Wind & Fire", "September", durationMs = 215_000L)))
        assertNull(matcher.id(row("Tyler, The Creator", "See You Again", durationMs = 180_000L)))
        assertNull(matcher.id(row("Simon & Garfunkel", "America")))
        assertNull(matcher.id(row("Chase & Status", "Blind Faith", durationMs = 213_000L)))
    }

    @Test
    fun aGuestCreditedWithAnyWhitespaceStillCounts() {
        val matcher = ListenBrainzMatcher(listOf(track("c", "Calvin Harris", "Summer")))
        assertEquals("c", matcher.id(row("Calvin Harris　x　Rihanna", "Summer", durationMs = 200_500L)))
    }

    @Test
    fun aSecondArtistAGuestOrAPieceOfABandNameDoesNotQualifyATrack() {
        fun refused(trackArtist: String, title: String, listenArtist: String) {
            val matcher = ListenBrainzMatcher(listOf(track("t", trackArtist, title)))
            assertNull(matcher.id(row(listenArtist, title)), "[$listenArtist] for [$trackArtist]")
        }
        refused("Drake", "Intro", "Future & Drake")
        refused("Adele", "Hello", "Lionel Richie feat. Adele")
        refused("Sebastian", "Intro", "Belle and Sebastian")
        refused("Belle and Sebastian", "Intro", "Sebastian")
        refused("Earth, Wind & Fire", "September", "Fire")
        refused("Various Artists", "Intro", "Drake, Various Artists")
        refused("Unknown Artist", "Track 01", "Somebody & Unknown Artist")
        refused("X", "Hello", "Adele x X")
        // The lead artist still qualifies.
        // Future is unknown to this library, so the credit stays whole and its first name needs a close length.
        assertEquals("j", ListenBrainzMatcher(listOf(track("j", "Drake", "Jumpman"))).id(row("Drake, Future", "Jumpman", durationMs = 199_000L)))
    }

    // ---- L7-3: misses are remembered as hashes only

    @Test
    fun missingLookupsDoNotFillTheNameCaches() {
        val matcher = ListenBrainzMatcher(listOf(track("s", "Band", "Song")))
        assertEquals("s", matcher.id(row("Band", "Song")))
        val found = matcher.rememberedLookups
        for (i in 0 until 2_000) {
            val missing = ListenBrainzRow(1_700_000_000, "Nobody $i", "Nothing $i", null, null, mappedArtist = "No One $i", mappedTitle = "None $i")
            assertEquals(ListenBrainzMatcher.NONE, matcher.match(missing))
            assertEquals(ListenBrainzMatcher.NONE, matcher.match(missing))
        }
        assertEquals(found, matcher.rememberedLookups)
        assertEquals("s", matcher.id(row("Band", "Song")))
    }

    // ---- L7-5: only a "From" naming a film, a show or a quoted work is a suffix

    @Test
    fun aFromSuffixIsDroppedOnlyForAQuotedWorkOrAFilm() {
        val matcher = ListenBrainzMatcher(listOf(track("l", "Band", "Letters"), track("g", "Idina Menzel", "Let It Go")))
        assertNull(matcher.id(row("Band", "Letters - From Home")))
        assertEquals("g", matcher.id(row("Idina Menzel", "Let It Go - From \"Frozen\"")))
        assertEquals("g", matcher.id(row("Idina Menzel", "Let It Go - From the Motion Picture Frozen")))
    }

    // ---- L2: another version is not this track

    @Test
    fun otherVersionsAtTheSameLengthAreRefused() {
        val matcher = ListenBrainzMatcher(listOf(track("s", "Band", "Song"), track("x", "Band", "Other Song (Live)")))
        for (title in listOf("Song (Live)", "Song (Instrumental)", "Song (Someone Remix)", "Song - Acoustic", "Song (Sped Up Version)",
            "Song (Slowed + Reverb)", "Song Sped Up", "Song (Demo)", "Song (Karaoke)", "Song (Extended Mix)")) {
            assertNull(matcher.id(row("Band", title, durationMs = 200_000)), title)
        }
        // A remaster, a single edit or a mono mix is the same version.
        for (title in listOf("Song (Remastered 2011)", "Song - 2011 Remaster", "Song (Single Version)", "Song (Mono)", "Song (Radio Edit)")) {
            assertEquals("s", matcher.id(row("Band", title, durationMs = 200_000)), title)
        }
        // A live listen finds the live track; an unmarked listen does not take it.
        assertEquals("x", matcher.id(row("Band", "Other Song (Live)")))
        assertEquals("x", matcher.id(row("Band", "Other Song - Live")))
        assertNull(matcher.id(row("Band", "Other Song")))
    }

    @Test
    fun aMappedTitleKeepsTheSubmittedVersion() {
        fun mapped(artist: String, title: String, mappedTitle: String) =
            ListenBrainzRow(1_700_000_000, artist, title, null, null, mappedArtist = artist, mappedTitle = mappedTitle)
        val studio = ListenBrainzMatcher(listOf(track("q", "Queen", "Somebody to Love"), track("s", "Band", "Song")))
        assertNull(studio.id(mapped("Queen", "Somebody to Love - Live", "Somebody to Love")))
        assertNull(studio.id(mapped("Band", "Song (DJ Remix)", "Song")))
        assertEquals("s", studio.id(mapped("Band", "Song", "Song")))
        val live = ListenBrainzMatcher(listOf(track("l", "Band", "Song (Live)")))
        assertEquals("l", live.id(mapped("Band", "Song - Live", "Song")))
    }

    @Test
    fun theWrongVersionCasesOfTheCheck() {
        val matcher = ListenBrainzMatcher(listOf(track("g", "TWICE", "Me gustas tu (Sped Up version)")))
        assertNull(matcher.id(row("TWICE", "Me Gustas Tu")))
        assertEquals("g", matcher.id(row("TWICE", "Me gustas tu (sped up version)")))
        assertEquals(listOf("", "live", "instrumental,live", "remix", "slowed"), listOf(
            ListenBrainzMatcher.versionMarks("Song (Remastered)"),
            ListenBrainzMatcher.versionMarks("Song (Live at Wembley)"),
            ListenBrainzMatcher.versionMarks("Song (Live) [Instrumental]"),
            ListenBrainzMatcher.versionMarks("Song (DJ Someone Rmx)"),
            ListenBrainzMatcher.versionMarks("Song slowed"),
        ))
        // A title that merely contains a marker word is no version.
        assertEquals("", ListenBrainzMatcher.versionMarks("Live and Let Die"))
    }

    // ---- L1, L4: the importer

    private val now = 1_800_000_000_000L
    private val library = listOf(
        track("long", "Artist", "Long", durationMs = 200_000L),
        track("short", "Artist", "Short", durationMs = 90_000L),
        track("unknown", "Artist", "Unknown length", durationMs = null),
    )

    private suspend fun import(vararg lines: String): Pair<ListenBrainzImportReport, DefaultListeningHistory> {
        val history = DefaultListeningHistory(MemoryHistoryStore())
        val bytes = lines.joinToString("\n").encodeToByteArray()
        val report = ListenBrainzImporter(history, FakeLibrary(library), now = { now })
            .import(source = { ListenBrainzTestExports.input(bytes, chunk = 4096) })
        return report to history
    }

    @Test
    fun aDoubleScrobbleIsOneListenAndARepeatIsTwo() = runTest {
        val (report, history) = import(
            // Two scrobblers, 5 s and 60 s after the first: one listen.
            ListenBrainzTestExports.listen(1_700_000_000, "Artist", "Long"),
            ListenBrainzTestExports.listen(1_700_000_005, "Artist", "Long"),
            ListenBrainzTestExports.listen(1_700_000_060, "Artist", "Long"),
            // Back to back: the whole track apart, two listens.
            ListenBrainzTestExports.listen(1_700_000_200, "Artist", "Long"),
            // A 90-second track repeated 100 s later: two listens (more than its length apart).
            ListenBrainzTestExports.listen(1_700_001_000, "Artist", "Short"),
            ListenBrainzTestExports.listen(1_700_001_100, "Artist", "Short"),
            // No length known: 120 s.
            ListenBrainzTestExports.listen(1_700_002_000, "Artist", "Unknown length"),
            ListenBrainzTestExports.listen(1_700_002_100, "Artist", "Unknown length"),
            ListenBrainzTestExports.listen(1_700_002_300, "Artist", "Unknown length"),
        )
        assertEquals(6, report.imported)
        assertEquals(3, report.alreadyPresent)
        assertEquals(
            listOf(1_700_000_000L, 1_700_000_200L, 1_700_001_000L, 1_700_001_100L, 1_700_002_000L, 1_700_002_300L),
            history.allEvents().map { it.startedAtMs / 1000 },
        )
        // A second export from the other scrobbler, seconds off: nothing new.
        val again = ListenBrainzImporter(history, FakeLibrary(library), now = { now })
            .import(source = { ListenBrainzTestExports.input(ListenBrainzTestExports.listen(1_700_000_003, "Artist", "Long").encodeToByteArray()) })
        assertEquals(0, again.imported)
    }

    @Test
    fun rowsAheadOfNowBeyondTenMinutesAreRefused() = runTest {
        val nowSeconds = now / 1000
        val (report, _) = import(
            ListenBrainzTestExports.listen(nowSeconds + 5 * 60, "Artist", "Long"),
            ListenBrainzTestExports.listen(nowSeconds + 60 * 60, "Artist", "Short"),
        )
        assertEquals(1, report.imported)
        assertEquals(1, report.unreadable)
    }

    // ---- L3: the reader

    @Test
    fun aRecordBrokenByARawLineFeedDoesNotTakeTheNextLine() {
        val text = listOf(
            ListenBrainzTestExports.listen(1_700_000_000, "A", "One"),
            ListenBrainzTestExports.listen(1_700_000_100, "A", "Two"),
            ListenBrainzTestExports.listen(1_700_000_200, "A", "Three"),
            "{\"listened_at\": 1700000300, \"track_metadata\": {\"artist_name\": \"A\", \"track_name\": \"bro",
            "ken\"}}",
            ListenBrainzTestExports.listen(1_700_000_400, "A", "Five"),
        ).joinToString("\n")
        val rows = mutableListOf<ListenBrainzRow>()
        val summary = ListenBrainzExportReader.read(ListenBrainzTestExports.input(text.encodeToByteArray(), 7)) { rows += it }
        assertEquals(listOf("One", "Two", "Three", "Five"), rows.map { it.title })
        assertEquals(2, summary.unreadable)
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
