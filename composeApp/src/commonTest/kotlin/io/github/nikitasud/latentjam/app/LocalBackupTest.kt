/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultListeningHistory
import io.github.nikitasud.latentjam.history.DefaultRecentSearches
import io.github.nikitasud.latentjam.history.HistoryStore
import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.RecentSearchStore
import io.github.nikitasud.latentjam.history.SmartExclusionStore
import io.github.nikitasud.latentjam.history.SmartExclusions
import io.github.nikitasud.latentjam.library.DefaultPlaylists
import io.github.nikitasud.latentjam.library.LibrarySource
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.library.PlaylistStore
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class LocalBackupTest {

    @Test
    fun codecRoundTripsUnicodeDelimitersAndEverySection() {
        val snapshot = LocalBackupSnapshot(
            createdAtMs = 1_234,
            settings = LocalBackupSettings(
                themeMode = ThemeMode.DARK,
                startPage = StartPage.FOR_YOU,
                trackColorMode = TrackColorMode.SMART,
                smartQueueLength = 40,
                saveListeningHistory = false,
                rememberSearches = true,
            ),
            tracks = listOf(
                LocalBackupTrackReference(
                    originalId = "file:\nКИНО\t1",
                    title = "Группа\nкрови",
                    artist = "КИНО | Kino",
                    album = null,
                    durationMs = 286_000,
                ),
            ),
            playlists = listOf(
                LocalBackupPlaylist(
                    id = "mix\t1",
                    name = "Для дороги\n🚗",
                    createdAtMs = 1_000,
                    trackReferenceIds = listOf("file:\nКИНО\t1"),
                    includeInSmart = true,
                ),
            ),
            listeningHistory = listOf(
                LocalBackupListenEvent(
                    trackReferenceId = "file:\nКИНО\t1",
                    startedAtMs = 1_100,
                    playedMs = 280_000,
                    trackDurationMs = 286_000,
                    completed = true,
                    skipped = false,
                    shuffleMode = "SMART\tlocal",
                ),
            ),
            recentSearches = listOf("русский рок\n80-е"),
            hiddenTrackReferenceIds = setOf("file:\nКИНО\t1"),
            smartExcludedTrackReferenceIds = setOf("file:\nКИНО\t1"),
            smartExcludedArtists = setOf("Не предлагать\tсейчас"),
        )

        val encoded = LocalBackupCodec.encode(snapshot)

        assertEquals(snapshot, LocalBackupCodec.decode(encoded))
        assertTrue(encoded.startsWith("LATENTJAM-LOCAL-BACKUP\t2\n"))
        assertFalse(encoded.contains("Группа крови"), "User strings must be safely encoded")
    }

    @Test
    fun codecImportsV1PlaylistsAsNotOptedIn() {
        val legacy = emptySnapshot().copy(
            formatVersion = 1,
            playlists = listOf(
                LocalBackupPlaylist(
                    id = "legacy",
                    name = "Legacy mix",
                    createdAtMs = 2,
                    trackReferenceIds = emptyList(),
                ),
            ),
        )

        val decoded = LocalBackupCodec.decode(LocalBackupCodec.encode(legacy))

        assertEquals(1, decoded.formatVersion)
        assertFalse(decoded.playlists.single().includeInSmart)
    }

    @Test
    fun codecReadsAFrozenHistoricalV1PlaylistFixture() {
        // A literal fixture catches accidental agreement between today's v1 encoder and decoder.
        val fixture =
            "LATENTJAM-LOCAL-BACKUP\t1\n" +
                "C\t1\n" +
                "S\tSYSTEM\ttracks\tdynamic\t20\t1\t1\n" +
                "P\ts6c6567616379\ts4c6567616379206d6978\t2\n"

        val decoded = LocalBackupCodec.decode(fixture)

        assertEquals(1, decoded.formatVersion)
        assertEquals("legacy", decoded.playlists.single().id)
        assertEquals("Legacy mix", decoded.playlists.single().name)
        assertFalse(decoded.playlists.single().includeInSmart)
    }

    @Test
    fun codecRejectsFutureVersionsCorruptionAndDanglingReferences() {
        val valid = LocalBackupCodec.encode(emptySnapshot())
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.decode(valid.replaceFirst("LOCAL-BACKUP\t2", "LOCAL-BACKUP\t9"))
        }
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.decode(valid + "Q\tnot-hex\n")
        }
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.decode(
                "LATENTJAM-LOCAL-BACKUP\t2\n" +
                    "C\t1\n" +
                    "S\tSYSTEM\ttracks\tdynamic\t20\t1\t1\n" +
                    "P\ts6964\ts6e616d65\t2\tmaybe\n",
            )
        }
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.validate(
                emptySnapshot().copy(hiddenTrackReferenceIds = setOf("missing")),
            )
        }
    }

    @Test
    fun codecScansCrLfAndRejectsCardinalityBeforeDecodingOverflowRecord() {
        val valid = LocalBackupCodec.encode(emptySnapshot())
        assertEquals(emptySnapshot(), LocalBackupCodec.decode(valid.replace("\n", "\r\n")))

        val overloaded = buildString {
            append(valid)
            repeat(100) { append("Q\ts61\n") }
            // Deliberately corrupt: the count limit must reject this before attempting hex decode.
            append("Q\tnot-hex\n")
        }
        val failure = assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.decode(overloaded)
        }
        assertContains(failure.message.orEmpty(), "Too many recent searches")
    }

    @Test
    fun serviceRestoresPortableMatchesAndReportsUnsafeUnresolvedTracks() = runTest {
        val sourceTrack = track(
            id = "old-kino-id",
            title = "Группа крови",
            artist = "КИНО",
            album = "Группа крови",
            durationMs = 286_000,
        )
        val source = fixture(listOf(sourceTrack))
        val playlist = source.playlists.create("Русский рок")
        source.playlists.addTracks(playlist.id, listOf(sourceTrack.id, TrackId("missing-old-id")))
        assertTrue(source.playlists.toggleIncludeInSmart(playlist.id))
        source.history.record(event(sourceTrack.id, 100))
        source.history.record(event(TrackId("missing-old-id"), 200))
        source.searches.record("Виктор Цой")
        source.library.hide(sourceTrack.id)
        source.exclusions.excludeTrack(sourceTrack.id)
        source.exclusions.excludeArtist("Various Artists")
        source.settings.setThemeMode(ThemeMode.DARK)
        source.settings.setStartPage(StartPage.FOR_YOU)
        source.settings.setTrackColorMode(TrackColorMode.SMART)
        source.settings.setSmartQueueLength(40)
        source.settings.setSaveListeningHistory(false).getOrThrow()

        val encoded = source.service.exportEncoded()

        // Media ids changed. The old id is also reused by an unrelated track, which must never win.
        val destinationTrack = sourceTrack.copy(id = TrackId("new-kino-id"))
        val unrelatedCollision = track(
            id = "old-kino-id",
            title = "Completely different",
            artist = "Someone else",
            album = "Other",
            durationMs = 100_000,
        )
        val unsafeIdOnlyCollision = unrelatedCollision.copy(id = TrackId("missing-old-id"))
        val destination = fixture(listOf(destinationTrack, unrelatedCollision, unsafeIdOnlyCollision))

        val report = destination.service.importEncoded(
            encoded = encoded,
            mode = LocalBackupRestoreMode.REPLACE,
        )

        assertEquals(1, report.resolvedTrackReferences)
        assertEquals(1, report.unresolvedTrackReferences)
        assertEquals(LocalBackupSection.entries.toSet(), report.completedSections)
        assertContentEquals(
            listOf("new-kino-id"),
            destination.playlists.all().single().trackIds,
        )
        assertTrue(destination.playlists.all().single().includeInSmart)
        assertContentEquals(
            listOf("new-kino-id"),
            destination.history.recentEvents(Int.MAX_VALUE).map { it.trackId.value },
        )
        assertContentEquals(listOf("Виктор Цой"), destination.searches.recent(Int.MAX_VALUE))
        assertEquals(setOf(TrackId("new-kino-id")), destination.library.hiddenTrackIds())
        assertEquals(setOf(TrackId("new-kino-id")), destination.exclusions.load().trackIds)
        assertContains(destination.exclusions.state.value.artists, "Various Artists")
        assertEquals(ThemeMode.DARK, destination.settings.themeMode.value)
        assertEquals(StartPage.FOR_YOU, destination.settings.startPage.value)
        assertEquals(TrackColorMode.SMART, destination.settings.trackColorMode.value)
        assertEquals(40, destination.settings.smartQueueLength.value)
        assertFalse(destination.settings.saveListeningHistory.value)
    }

    @Test
    fun mergeIsIdempotentForHistoryAndMergesSameNamedPlaylist() = runTest {
        val current = track("current", "Current", "Artist", "Album", 120_000)
        val imported = track("imported", "Imported", "Artist", "Album", 121_000)
        val fixture = fixture(listOf(current, imported))
        val existingPlaylist = fixture.playlists.create("Mix")
        fixture.playlists.addTracks(existingPlaylist.id, listOf(current.id))
        val existingEvent = event(current.id, 100)
        fixture.history.record(existingEvent)

        val snapshot = emptySnapshot().copy(
            tracks = listOf(imported.toReference()),
            playlists = listOf(
                LocalBackupPlaylist(
                    id = "foreign",
                    name = "mix",
                    createdAtMs = 5,
                    trackReferenceIds = listOf(imported.id.value),
                    includeInSmart = true,
                ),
            ),
            listeningHistory = listOf(event(imported.id, 200).toBackup()),
        )

        fixture.service.restore(snapshot, LocalBackupRestoreMode.MERGE)
        fixture.service.restore(snapshot, LocalBackupRestoreMode.MERGE)

        val mergedPlaylist = fixture.playlists.all().single()
        assertContentEquals(listOf("current", "imported"), mergedPlaylist.trackIds)
        assertTrue(mergedPlaylist.includeInSmart)
        assertContentEquals(
            listOf(existingEvent, event(imported.id, 200)),
            fixture.history.recentEvents(Int.MAX_VALUE).asReversed(),
        )
    }

    @Test
    fun disabledSourceTracksKeepPortableMetadataAndRestoreTheirReferences() = runTest {
        val visible = track("visible", "Visible", "Artist", "Album", 120_000)
        val disabled = track("disabled", "Deep Cut", "Artist", "Album", 180_000)
        val source = fixture(
            tracks = listOf(visible, disabled),
            visibleTrackIds = setOf(visible.id),
        )
        val playlist = source.playlists.create("All sources")
        source.playlists.addTracks(playlist.id, listOf(disabled.id))
        source.history.record(event(disabled.id, 100))

        val encoded = source.service.exportEncoded()
        val captured = LocalBackupCodec.decode(encoded)
        assertEquals("Deep Cut", captured.tracks.single { it.originalId == "disabled" }.title)

        val destination = fixture(
            tracks = listOf(visible, disabled),
            visibleTrackIds = setOf(visible.id),
        )
        val report = destination.service.importEncoded(encoded, LocalBackupRestoreMode.REPLACE)

        assertEquals(1, report.resolvedTrackReferences)
        assertEquals(0, report.unresolvedTrackReferences)
        assertContentEquals(
            listOf("disabled"),
            destination.playlists.all().single().trackIds,
        )
        assertContentEquals(
            listOf("disabled"),
            destination.history.recentEvents(Int.MAX_VALUE).map { it.trackId.value },
        )
    }

    @Test
    fun indexedResolverPreservesDurationUniquenessAndAmbiguity() = runTest {
        val destination = fixture(
            listOf(
                track("new-a", "Shared", "Artist", "Album", 100_000),
                track("new-b", "Shared", "Artist", "Album", 101_000),
                track("new-c", "Shared", "Artist", "Album", 110_000),
            ),
        )
        val snapshot = emptySnapshot().copy(
            tracks = listOf(
                LocalBackupTrackReference("old-ambiguous", " shared ", "ARTIST", "Album", 100_500),
                LocalBackupTrackReference("old-unique", "Shared", "Artist", "Album", 110_000),
            ),
        )

        val report = destination.service.restore(snapshot, LocalBackupRestoreMode.REPLACE, noSections())

        assertEquals(1, report.resolvedTrackReferences)
        assertEquals(1, report.unresolvedTrackReferences)
    }

    @Test
    fun indexedResolverHandlesLargeMetadataFallbackWithoutLibraryWideScanPerReference() = runTest {
        val count = 5_000
        val destinationTracks = List(count) { index ->
            track("new-$index", "Shared", "Artist", "Album", index * 5_000L)
        }
        val snapshot = emptySnapshot().copy(
            tracks = List(count) { index ->
                LocalBackupTrackReference(
                    originalId = "old-$index",
                    title = "Shared",
                    artist = "Artist",
                    album = "Album",
                    durationMs = index * 5_000L,
                )
            },
        )

        val report = fixture(destinationTracks).service.restore(
            snapshot,
            LocalBackupRestoreMode.REPLACE,
            noSections(),
        )

        assertEquals(count, report.resolvedTrackReferences)
        assertEquals(0, report.unresolvedTrackReferences)
    }

    private fun emptySnapshot() = LocalBackupSnapshot(
        createdAtMs = 1,
        settings = LocalBackupSettings(
            ThemeMode.SYSTEM,
            StartPage.TRACKS,
            TrackColorMode.DYNAMIC,
            DEFAULT_SMART_QUEUE_LENGTH,
            saveListeningHistory = true,
            rememberSearches = true,
        ),
        tracks = emptyList(),
        playlists = emptyList(),
        listeningHistory = emptyList(),
        recentSearches = emptyList(),
        hiddenTrackReferenceIds = emptySet(),
        smartExcludedTrackReferenceIds = emptySet(),
        smartExcludedArtists = emptySet(),
    )

    private fun noSections() = LocalBackupSections(
        settings = false,
        playlists = false,
        listeningHistory = false,
        recentSearches = false,
        hiddenTracks = false,
        smartExclusions = false,
    )

    private fun fixture(
        tracks: List<TrackDescriptor>,
        visibleTrackIds: Set<TrackId> = tracks.mapTo(linkedSetOf()) { it.id },
    ): Fixture {
        val settings = FakeSettings()
        val playlists = DefaultPlaylists(InMemoryPlaylistStore())
        val history = DefaultListeningHistory(InMemoryHistoryStore())
        val searches = DefaultRecentSearches(InMemorySearchStore())
        val library = FakeLibrary(tracks, visibleTrackIds)
        val exclusions = SmartExclusions(InMemoryExclusionStore())
        return Fixture(
            settings,
            playlists,
            history,
            searches,
            library,
            exclusions,
            LocalBackupService(settings, playlists, history, searches, library, exclusions),
        )
    }

    private data class Fixture(
        val settings: FakeSettings,
        val playlists: DefaultPlaylists,
        val history: DefaultListeningHistory,
        val searches: DefaultRecentSearches,
        val library: FakeLibrary,
        val exclusions: SmartExclusions,
        val service: LocalBackupService,
    )

    private class InMemoryPlaylistStore : PlaylistStore {
        private var lines = emptyList<String>()
        override suspend fun read(): List<String> = lines
        override suspend fun write(lines: List<String>) { this.lines = lines.toList() }
    }

    private class InMemoryHistoryStore : HistoryStore {
        private val lines = mutableListOf<String>()
        override suspend fun append(line: String) { lines += line }
        override suspend fun readAll(): List<String> = lines.toList()
        override suspend fun replaceAll(lines: List<String>) {
            this.lines.clear()
            this.lines += lines
        }
        override suspend fun clear() { lines.clear() }
    }

    private class InMemorySearchStore : RecentSearchStore {
        private var queries = emptyList<String>()
        override suspend fun read(): List<String> = queries
        override suspend fun write(queries: List<String>) { this.queries = queries.toList() }
    }

    private class InMemoryExclusionStore : SmartExclusionStore {
        private var lines = emptyList<String>()
        override suspend fun read(): List<String> = lines
        override suspend fun write(lines: List<String>) { this.lines = lines.toList() }
    }

    private class FakeLibrary(
        private val allTracks: List<TrackDescriptor>,
        private val visibleTrackIds: Set<TrackId>,
    ) : MusicLibrary {
        private val hidden = linkedSetOf<TrackId>()
        override suspend fun tracks(): List<TrackDescriptor> = allTracks.filter {
            it.id in visibleTrackIds && it.id !in hidden
        }
        override suspend fun allKnownTracks(): List<TrackDescriptor> = allTracks
        override suspend fun hide(trackId: TrackId) { hidden += trackId }
        override suspend fun unhide(trackId: TrackId) { hidden -= trackId }
        override suspend fun hiddenTracks(): List<TrackDescriptor> = allTracks.filter { it.id in hidden }
        override suspend fun hiddenTrackIds(): Set<TrackId> = hidden.toSet()
        override suspend fun hasHiddenTracks(): Boolean = hidden.isNotEmpty()
        override suspend fun unhideAll() { hidden.clear() }
        override suspend fun replaceHidden(trackIds: Set<TrackId>) {
            hidden.clear()
            hidden += trackIds
        }
        override suspend fun sources(): List<LibrarySource> = emptyList()
        override suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) = Unit
    }

    private class FakeSettings : AppSettings {
        override val themeMode: MutableStateFlow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)
        override fun setThemeMode(mode: ThemeMode) { themeMode.value = mode }
        override val startPage: MutableStateFlow<StartPage> = MutableStateFlow(StartPage.TRACKS)
        override fun setStartPage(page: StartPage) { startPage.value = page }
        override val trackColorMode: MutableStateFlow<TrackColorMode> = MutableStateFlow(TrackColorMode.DYNAMIC)
        override fun setTrackColorMode(mode: TrackColorMode) { trackColorMode.value = mode }
        override val smartQueueLength: MutableStateFlow<Int> = MutableStateFlow(DEFAULT_SMART_QUEUE_LENGTH)
        override fun setSmartQueueLength(length: Int) { smartQueueLength.value = sanitizeSmartQueueLength(length) }
        override val includeNoveltyMixes: MutableStateFlow<Boolean> = MutableStateFlow(false)
        override fun setIncludeNoveltyMixes(enabled: Boolean) { includeNoveltyMixes.value = enabled }
        override val saveListeningHistory: MutableStateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun setSaveListeningHistory(enabled: Boolean): Result<Unit> =
            Result.success(Unit).also { saveListeningHistory.value = enabled }
        override val rememberSearches: MutableStateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun setRememberSearches(enabled: Boolean): Result<Unit> =
            Result.success(Unit).also { rememberSearches.value = enabled }
        override val resumePlayback: MutableStateFlow<ResumePlayback?> = MutableStateFlow(null)
        override fun setResumePlayback(state: ResumePlayback?) { resumePlayback.value = state }
    }

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
    ) = TrackDescriptor(TrackId(id), title, artist, album, durationMs = durationMs)

    private fun event(id: TrackId, startedAtMs: Long) = ListenEvent(
        trackId = id,
        startedAtMs = startedAtMs,
        playedMs = 60_000,
        trackDurationMs = 120_000,
        completed = false,
        skipped = false,
        shuffleMode = "SMART",
    )

    private fun TrackDescriptor.toReference() = LocalBackupTrackReference(
        originalId = id.value,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
    )

    private fun ListenEvent.toBackup() = LocalBackupListenEvent(
        trackReferenceId = trackId.value,
        startedAtMs = startedAtMs,
        playedMs = playedMs,
        trackDurationMs = trackDurationMs,
        completed = completed,
        skipped = skipped,
        shuffleMode = shuffleMode,
    )
}
