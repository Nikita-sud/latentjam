/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultListeningHistory
import io.github.nikitasud.latentjam.history.DefaultRecentSearches
import io.github.nikitasud.latentjam.history.HistoryStore
import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.RecentSearchStore
import io.github.nikitasud.latentjam.history.SmartExclusionStore
import io.github.nikitasud.latentjam.history.SmartExclusions
import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.DefaultPlaylists
import io.github.nikitasud.latentjam.library.LibrarySource
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.library.PlaylistStore
import io.github.nikitasud.latentjam.library.SongSort
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
import kotlin.test.assertNull
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
                includeNoveltyMixes = true,
                normalizeVolume = true,
                crossfadeSeconds = 7,
                pageLayout = PageLayout()
                    .withPageEnabled(StartPage.MAP, true)
                    .withPageEnabled(StartPage.GENRES, false)
                    .movePage(StartPage.ALBUMS, -4),
                artistVariety = 4,
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
                    listenedMs = 275_000,
                ),
            ),
            recentSearches = listOf("русский рок\n80-е"),
            hiddenTrackReferenceIds = setOf("file:\nКИНО\t1"),
            smartExcludedTrackReferenceIds = setOf("file:\nКИНО\t1"),
            smartExcludedArtists = setOf("Не предлагать\tсейчас"),
        )

        val encoded = LocalBackupCodec.encode(snapshot)

        assertEquals(snapshot, LocalBackupCodec.decode(encoded))
        assertTrue(encoded.startsWith("LATENTJAM-LOCAL-BACKUP\t6\n"))
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
        assertFalse(decoded.settings.includeNoveltyMixes)
        assertFalse(decoded.settings.normalizeVolume)
        assertEquals(0, decoded.settings.crossfadeSeconds)
        assertNull(decoded.listeningHistory.firstOrNull()?.listenedMs)
    }

    @Test
    fun artistVarietyIsAV6RecordThatOlderBackupsCannotCarry() {
        val v5 =
            "LATENTJAM-LOCAL-BACKUP\t5\n" +
                "C\t1\n" +
                "S\tSYSTEM\ttracks\tdynamic\t20\t1\t1\t0\t0\t0\n"
        assertEquals(DEFAULT_ARTIST_VARIETY, LocalBackupCodec.decode(v5).settings.artistVariety)
        assertFailsWith<LocalBackupFormatException> { LocalBackupCodec.decode(v5 + "V\t3\n") }
        val v6 = v5.replaceFirst("\t5\n", "\t6\n")
        assertEquals(3, LocalBackupCodec.decode(v6 + "V\t3\n").settings.artistVariety)
        assertFailsWith<LocalBackupFormatException> { LocalBackupCodec.decode(v6 + "V\t9\n") }
        assertFailsWith<LocalBackupFormatException> { LocalBackupCodec.decode(v6 + "V\t3\nV\t3\n") }
    }

    @Test
    fun codecReadsAFrozenHistoricalV2FixtureWithNewSettingsDisabled() {
        val fixture =
                "LATENTJAM-LOCAL-BACKUP\t2\n" +
                "C\t1\n" +
                "S\tSYSTEM\ttracks\tdynamic\t20\t1\t1\n" +
                "T\ts747261636b\tn\tn\tn\tn\n" +
                "P\ts6964\ts6d6978\t2\t1\n" +
                "H\ts747261636b\t10\t20\tn\t0\t0\tn\n"

        val decoded = LocalBackupCodec.decode(fixture)

        assertEquals(2, decoded.formatVersion)
        assertTrue(decoded.playlists.single().includeInSmart)
        assertFalse(decoded.settings.includeNoveltyMixes)
        assertFalse(decoded.settings.normalizeVolume)
        assertEquals(0, decoded.settings.crossfadeSeconds)
        assertNull(decoded.listeningHistory.single().listenedMs)
    }

    @Test
    fun codecRoundTripsListeningOriginsAndKeepsLegacyEventsUnknown() {
        val track = LocalBackupTrackReference("t", "Title", "Artist", null, 200_000)
        fun listen(startedAtMs: Long, origin: ListenOrigin?) = LocalBackupListenEvent(
            trackReferenceId = "t",
            startedAtMs = startedAtMs,
            playedMs = 1,
            trackDurationMs = 200_000,
            completed = false,
            skipped = true,
            shuffleMode = "SMART",
            listenedMs = 1,
            origin = origin,
        )
        val snapshot = emptySnapshot().copy(
            tracks = listOf(track),
            listeningHistory = listOf(
                listen(1, ListenOrigin(ListenStart.AUTO_ADVANCE, 3, "folder:/Music/Кино\tLive")),
                listen(2, ListenOrigin()),
                listen(3, origin = null),
            ),
        )

        val encoded = LocalBackupCodec.encode(snapshot)

        assertEquals(snapshot, LocalBackupCodec.decode(encoded))
        assertTrue(encoded.startsWith("LATENTJAM-LOCAL-BACKUP\t6\n"))
    }

    @Test
    fun codecReadsFrozenHistoryRecordsWithAndWithoutAnOrigin() {
        val header = "C\t1\nS\tSYSTEM\ttracks\tdynamic\t20\t1\t1\t0\t0\t0\nT\ts74\tn\tn\tn\tn\n"
        val v4 = LocalBackupCodec.decode(
            "LATENTJAM-LOCAL-BACKUP\t4\n" + header + "H\ts74\t10\t20\tn\t0\t0\tn\t15\n",
        )
        assertNull(v4.listeningHistory.single().origin)

        // hex("SKIP_NEXT") = 534b49505f4e455854, hex("album:k") = 616c62756d3a6b.
        val v5 = LocalBackupCodec.decode(
            "LATENTJAM-LOCAL-BACKUP\t5\n" + header +
                "H\ts74\t10\t20\tn\t0\t0\tn\t15\t1\ts534b49505f4e455854\t2\ts616c62756d3a6b\n" +
                "H\ts74\t30\t20\tn\t0\t0\tn\t15\t0\tn\tn\tn\n" +
                // A start named by a newer build reads as unknown instead of failing the restore.
                "H\ts74\t50\t20\tn\t0\t0\tn\t15\t1\ts4c41544552\tn\tn\n",
        )
        assertEquals(
            listOf(ListenOrigin(ListenStart.SKIP_NEXT, 2, "album:k"), null, ListenOrigin()),
            v5.listeningHistory.map { it.origin },
        )
    }

    @Test
    fun codecRejectsMalformedListeningOrigins() {
        val prefix = "LATENTJAM-LOCAL-BACKUP\t5\nC\t1\n" +
            "S\tSYSTEM\ttracks\tdynamic\t20\t1\t1\t0\t0\t0\nT\ts74\tn\tn\tn\tn\n" +
            "H\ts74\t10\t20\tn\t0\t0\tn\t15\t"
        for (origin in listOf("0\tn\t2\tn", "1\tn\t0\tn", "1\tn\tx\tn", "1\tn\tn\ts", "2\tn\tn\tn", "1\tn\tn")) {
            assertFailsWith<LocalBackupFormatException>(origin) {
                LocalBackupCodec.decode(prefix + origin + "\n")
            }
        }
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.validate(
                emptySnapshot().copy(
                    formatVersion = 4,
                    tracks = listOf(LocalBackupTrackReference("t", null, null, null, null)),
                    listeningHistory = listOf(
                        LocalBackupListenEvent("t", 1, 1, null, false, false, null, origin = ListenOrigin()),
                    ),
                ),
            )
        }
    }

    @Test
    fun codecRejectsFutureVersionsCorruptionAndDanglingReferences() {
        val valid = LocalBackupCodec.encode(emptySnapshot())
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.decode(valid.replaceFirst("LOCAL-BACKUP\t6", "LOCAL-BACKUP\t9"))
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
        assertFailsWith<LocalBackupFormatException> {
            val base = emptySnapshot()
            LocalBackupCodec.validate(
                base.copy(
                    formatVersion = 2,
                    settings = base.settings.copy(includeNoveltyMixes = true),
                ),
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
    fun legacyBackupsRestoreDefaultPagesAndRepairAHiddenMapStart() = runTest {
        val legacy = LocalBackupCodec.decode(
            "LATENTJAM-LOCAL-BACKUP\t3\n" +
                "C\t1\n" +
                "S\tSYSTEM\tmap\tdynamic\t20\t1\t1\t0\t0\t0\n",
        )
        assertEquals(PageLayout(), legacy.settings.pageLayout)
        val destination = fixture(emptyList())
        destination.settings.setPageLayout(PageLayout(hiddenPages = emptySet()))
        destination.settings.setStartPage(StartPage.MAP)

        destination.service.restore(legacy, LocalBackupRestoreMode.REPLACE)

        assertEquals(PageLayout(), destination.settings.pageLayout.value)
        assertEquals(StartPage.FOR_YOU, destination.settings.startPage.value)
    }

    @Test
    fun backupRestoresLayoutBeforeSelectingAnEnabledMapStart() = runTest {
        val source = fixture(emptyList())
        val layout = PageLayout()
            .withPageEnabled(StartPage.MAP, true)
            .withPageEnabled(StartPage.FOLDERS, false)
            .movePage(StartPage.MAP, -1)
        source.settings.setPageLayout(layout)
        source.settings.setStartPage(StartPage.MAP)
        val destination = fixture(emptyList())

        destination.service.importEncoded(source.service.exportEncoded(), LocalBackupRestoreMode.REPLACE)

        assertEquals(layout, destination.settings.pageLayout.value)
        assertEquals(StartPage.MAP, destination.settings.startPage.value)
    }

    @Test
    fun backupRestoresOptionalStatisticsOrderVisibilityAndStartPage() = runTest {
        val source = fixture(emptyList())
        val layout = PageLayout()
            .withPageEnabled(StartPage.STATISTICS, true)
            .withPageEnabled(StartPage.FOLDERS, false)
            .movePage(StartPage.STATISTICS, Int.MIN_VALUE)
        source.settings.setPageLayout(layout)
        source.settings.setStartPage(StartPage.STATISTICS)
        val destination = fixture(emptyList())

        val encoded = source.service.exportEncoded()
        destination.service.importEncoded(encoded, LocalBackupRestoreMode.REPLACE)

        assertTrue(encoded.startsWith("LATENTJAM-LOCAL-BACKUP\t6\n"))
        assertEquals(layout, destination.settings.pageLayout.value)
        assertEquals(StartPage.STATISTICS, destination.settings.startPage.value)
    }

    @Test
    fun olderVersionFourBackupsKeepStatisticsHiddenAndCustomPagesUnchanged() = runTest {
        val legacy = LocalBackupCodec.decode(
            "LATENTJAM-LOCAL-BACKUP\t4\nC\t0\n" +
                "S\tSYSTEM\tmap\tdynamic\t20\t1\t1\t0\t0\t0\n" +
                "L\tLJPL1|map,tracks,albums,artists,genres,folders,playlists,for_you|genres\n",
        )
        val destination = fixture(emptyList())

        destination.service.restore(legacy, LocalBackupRestoreMode.REPLACE)

        val layout = destination.settings.pageLayout.value
        assertEquals(StartPage.MAP, layout.visiblePages.first())
        assertEquals(StartPage.STATISTICS, layout.order.last())
        assertEquals(setOf(StartPage.GENRES, StartPage.STATISTICS), layout.hiddenPages)
        assertEquals(StartPage.MAP, destination.settings.startPage.value)
    }

    @Test
    fun restoringWithoutSettingsPreservesTheCurrentPageLayout() = runTest {
        val destination = fixture(emptyList())
        val layout = PageLayout().movePage(StartPage.ALBUMS, -4)
        destination.settings.setPageLayout(layout)
        destination.settings.setStartPage(StartPage.ALBUMS)

        destination.service.restore(emptySnapshot(), LocalBackupRestoreMode.REPLACE, noSections())

        assertEquals(layout, destination.settings.pageLayout.value)
        assertEquals(StartPage.ALBUMS, destination.settings.startPage.value)
    }

    @Test
    fun pageLayoutRecordIsOptionalButDuplicateAndMalformedRecordsAreRejected() {
        val encoded = LocalBackupCodec.encode(emptySnapshot())
        val withoutLayout = encoded.lineSequence().filterNot { it.startsWith("L\t") }.joinToString("\n")
        assertEquals(PageLayout(), LocalBackupCodec.decode(withoutLayout).settings.pageLayout)
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.decode(encoded + "L\tLJPL1|tracks|map\n")
        }
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.decode(withoutLayout + "\nL\tbroken\n")
        }
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
        source.settings.setIncludeNoveltyMixes(true)
        source.settings.setNormalizeVolume(true)
        source.settings.setCrossfadeSeconds(9)
        source.settings.setArtistVariety(0)
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
        assertTrue(destination.settings.includeNoveltyMixes.value)
        assertTrue(destination.settings.normalizeVolume.value)
        assertEquals(9, destination.settings.crossfadeSeconds.value)
        assertEquals(0, destination.settings.artistVariety.value)
        assertFalse(destination.settings.saveListeningHistory.value)
    }

    @Test
    fun mergeIsIdempotentForHistoryAndMergesSameNamedPlaylist() = runTest {
        val current = track("current", "Current", "Artist", "Album", 120_000)
        val imported = track("imported", "Imported", "Artist", "Album", 121_000)
        val fixture = fixture(listOf(current, imported))
        val existingPlaylist = fixture.playlists.create("Mix")
        fixture.playlists.addTracks(existingPlaylist.id, listOf(current.id))
        fixture.playlists.setArtwork(existingPlaylist.id, "local-cover.jpg")
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
        assertEquals("local-cover.jpg", mergedPlaylist.customArtworkRef)
        assertContentEquals(
            listOf(existingEvent, event(imported.id, 200)),
            fixture.history.recentEvents(Int.MAX_VALUE).asReversed(),
        )
    }

    @Test
    fun mergingABackupBackInKeepsOneCopyOfEachListenWithItsOrigin() = runTest {
        val song = track("song", "Song", "Artist", "Album", 120_000)
        val fixture = fixture(listOf(song))
        val listen = event(song.id, 100).copy(
            origin = ListenOrigin(ListenStart.USER_PICK, parentId = "playlist:p"),
        )
        fixture.history.record(listen)

        fixture.service.importEncoded(fixture.service.exportEncoded(), LocalBackupRestoreMode.MERGE)

        assertContentEquals(listOf(listen), fixture.history.recentEvents(Int.MAX_VALUE))
    }

    @Test
    fun metadataBackupRestoresAutomaticArtworkWithoutExportingLocalCoverReferences() = runTest {
        val member = track("one", "One", "Artist", "Album", 120_000)
        val fixture = fixture(listOf(member))
        val playlist = fixture.playlists.create("Custom cover", listOf(member.id))
        val coverReference = "b174a44e-3e93-42f5-a26a-0f4ad2577591.jpg"
        fixture.playlists.setArtwork(playlist.id, coverReference)

        val encoded = fixture.service.exportEncoded()
        val captured = LocalBackupCodec.decode(encoded).playlists.single()
        assertEquals(
            LocalBackupPlaylist(playlist.id, playlist.name, playlist.createdAtMs, playlist.trackIds),
            captured,
        )
        assertFalse(encoded.contains(coverReference))
        assertFalse(encoded.contains(coverReference.encodeToByteArray().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }))

        fixture.service.importEncoded(encoded, LocalBackupRestoreMode.REPLACE)

        val restored = fixture.playlists.all().single()
        assertEquals(playlist.id, restored.id)
        assertEquals(playlist.name, restored.name)
        assertEquals(playlist.trackIds, restored.trackIds)
        assertNull(restored.customArtworkRef)
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

    @Test
    fun idOnlyReferencesRestoreWhileTheDeviceStillConfirmsTheIds() = runTest {
        // An export taken while the library was unavailable stores bare ids, and a track whose tags
        // MediaStore could not read keeps its duration alone. Both must come back on the same device.
        val snapshot = emptySnapshot().copy(
            tracks = listOf(
                LocalBackupTrackReference("bare", null, null, null, null),
                LocalBackupTrackReference("untagged", null, null, null, 180_000),
            ),
            playlists = listOf(
                LocalBackupPlaylist("mix", "Mix", 1, listOf("bare", "untagged")),
            ),
        )
        val destination = fixture(
            listOf(
                track("bare", "Title", "Artist", "Album", 120_000),
                TrackDescriptor(TrackId("untagged"), durationMs = 180_000),
            ),
        )

        val report = destination.service.restore(
            snapshot,
            LocalBackupRestoreMode.REPLACE,
            noSections().copy(playlists = true),
        )

        assertEquals(2, report.resolvedTrackReferences)
        assertEquals(0, report.unresolvedTrackReferences)
        assertContentEquals(listOf("bare", "untagged"), destination.playlists.all().single().trackIds)
    }

    @Test
    fun anIdOnlyReferenceStaysUnresolvedWhenTheDeviceIdsWereReassigned() = runTest {
        // A described reference no longer fits the track that holds its id, so the ids in this
        // backup were reused; the bare id of a track the export could not describe must not claim one.
        val snapshot = emptySnapshot().copy(
            tracks = listOf(
                LocalBackupTrackReference("moved", "Old title", "Artist", "Album", 100_000),
                LocalBackupTrackReference("bare", null, null, null, null),
            ),
        )
        val destination = fixture(
            listOf(
                track("moved", "New title", "Artist", "Album", 100_000),
                track("bare", "Something else", "Other", "Other", 100_000),
            ),
        )

        val report = destination.service.restore(snapshot, LocalBackupRestoreMode.REPLACE, noSections())

        assertEquals(0, report.resolvedTrackReferences)
        assertEquals(2, report.unresolvedTrackReferences)
    }

    @Test
    fun anIdOnlyReferenceStaysUnresolvedWhenADescribedReferenceLostItsTrack() = runTest {
        // A described reference has no track under its id at all, so this snapshot's ids are not
        // confirmed: the bare id must not claim whatever unrelated track the device keeps under it.
        val snapshot = emptySnapshot().copy(
            tracks = listOf(
                LocalBackupTrackReference("gone", "Old title", "Artist", "Album", 100_000),
                LocalBackupTrackReference("bare", null, null, null, null),
            ),
        )
        val destination = fixture(listOf(track("bare", "Something else", "Other", "Other", 100_000)))

        val report = destination.service.restore(snapshot, LocalBackupRestoreMode.REPLACE, noSections())

        assertEquals(0, report.resolvedTrackReferences)
        assertEquals(2, report.unresolvedTrackReferences)
    }

    @Test
    fun idOnlySnapshotWithoutAnyDescribedReferenceRestoresOnTheSameDevice() = runTest {
        // An export taken while the library was unavailable describes no reference at all, so there
        // is no evidence against its ids and the same device must still bring the bare ids back.
        val snapshot = emptySnapshot().copy(
            tracks = listOf(LocalBackupTrackReference("bare", null, null, null, null)),
            playlists = listOf(LocalBackupPlaylist("mix", "Mix", 1, listOf("bare"))),
        )
        val destination = fixture(listOf(track("bare", "Title", "Artist", "Album", 120_000)))

        val report = destination.service.restore(
            snapshot,
            LocalBackupRestoreMode.REPLACE,
            noSections().copy(playlists = true),
        )

        assertEquals(1, report.resolvedTrackReferences)
        assertEquals(0, report.unresolvedTrackReferences)
        assertContentEquals(listOf("bare"), destination.playlists.all().single().trackIds)
    }

    @Test
    fun replaceRestoreIsRefusedWhileTheDeviceLibraryReportsNoTracks() = runTest {
        val song = track("song", "Song", "Artist", "Album", 120_000)
        val source = fixture(listOf(song))
        source.playlists.create("Mix", listOf(song.id))
        source.history.record(event(song.id, 100))
        source.library.hide(song.id)
        source.exclusions.excludeTrack(song.id)
        val encoded = source.service.exportEncoded()

        // A scan without the media permission returns nothing, which is not proof of deletion.
        val destination = fixture(emptyList())
        destination.playlists.create("Kept")
        destination.history.record(event(TrackId("kept"), 50))
        destination.library.hide(TrackId("kept"))
        destination.exclusions.excludeTrack(TrackId("kept"))

        val failure = assertFailsWith<LocalBackupRestoreException> {
            destination.service.importEncoded(encoded, LocalBackupRestoreMode.REPLACE)
        }

        assertTrue(failure.completedSections.isEmpty())
        assertTrue(failure.cause is LocalBackupLibraryUnavailableException)
        assertEquals(listOf("Kept"), destination.playlists.all().map { it.name })
        assertContentEquals(
            listOf("kept"),
            destination.history.recentEvents(Int.MAX_VALUE).map { it.trackId.value },
        )
        assertEquals(setOf(TrackId("kept")), destination.library.hiddenTrackIds())
        assertEquals(setOf(TrackId("kept")), destination.exclusions.load().trackIds)
    }

    @Test
    fun replaceRestoreIsRefusedWhenNothingMatchesANonEmptyLibrary() = runTest {
        val song = track("song", "Song", "Artist", "Album", 120_000)
        val source = fixture(listOf(song))
        source.playlists.create("Mix", listOf(song.id))
        source.history.record(event(song.id, 100))
        source.library.hide(song.id)
        source.exclusions.excludeTrack(song.id)
        val encoded = source.service.exportEncoded()

        // The library is not empty, but the media database was rebuilt under unrelated ids, so no
        // reference can be confirmed: replacing the stored sets would still drop them.
        val destination = fixture(
            listOf(track("other", "Other", "Other artist", "Other album", 90_000)),
        )
        destination.playlists.create("Kept", listOf(TrackId("kept")))
        destination.history.record(event(TrackId("kept"), 50))
        destination.library.hide(TrackId("kept"))
        destination.exclusions.excludeTrack(TrackId("kept"))

        val failure = assertFailsWith<LocalBackupRestoreException> {
            destination.service.importEncoded(encoded, LocalBackupRestoreMode.REPLACE)
        }

        assertTrue(failure.completedSections.isEmpty())
        assertTrue(failure.cause is LocalBackupLibraryUnavailableException)
        assertEquals(listOf("Kept"), destination.playlists.all().map { it.name })
        assertContentEquals(
            listOf("kept"),
            destination.history.recentEvents(Int.MAX_VALUE).map { it.trackId.value },
        )
        assertEquals(setOf(TrackId("kept")), destination.library.hiddenTrackIds())
        assertEquals(setOf(TrackId("kept")), destination.exclusions.load().trackIds)
    }

    @Test
    fun mergeRestoreStillImportsFromAnEmptyDeviceLibrary() = runTest {
        val song = track("song", "Song", "Artist", "Album", 120_000)
        val source = fixture(listOf(song))
        source.playlists.create("Mix", listOf(song.id))
        source.history.record(event(song.id, 100))
        val encoded = source.service.exportEncoded()

        val destination = fixture(emptyList())
        val kept = destination.playlists.create("Kept")
        destination.playlists.addTracks(kept.id, listOf(TrackId("kept")))
        destination.history.record(event(TrackId("kept"), 50))

        val report = destination.service.importEncoded(encoded, LocalBackupRestoreMode.MERGE)

        assertEquals(0, report.resolvedTrackReferences)
        assertEquals(1, report.unresolvedTrackReferences)
        assertEquals(2, destination.playlists.all().size)
        assertContains(destination.playlists.all().map { it.name }, "Kept")
        assertContains(destination.playlists.all().map { it.name }, "Mix")
        assertContentEquals(
            listOf("kept"),
            destination.history.recentEvents(Int.MAX_VALUE).map { it.trackId.value },
        )
    }

    @Test
    fun replaceRestoreOfSectionsWithoutTrackReferencesWorksWithoutALibrary() = runTest {
        val source = fixture(listOf(track("song", "Song", "Artist", "Album", 120_000)))
        source.settings.setThemeMode(ThemeMode.DARK)
        source.searches.record("Виктор Цой")
        val encoded = source.service.exportEncoded()

        val destination = fixture(emptyList())
        val report = destination.service.importEncoded(
            encoded,
            LocalBackupRestoreMode.REPLACE,
            noSections().copy(settings = true, recentSearches = true),
        )

        assertEquals(
            setOf(LocalBackupSection.SETTINGS, LocalBackupSection.RECENT_SEARCHES),
            report.completedSections,
        )
        assertEquals(ThemeMode.DARK, destination.settings.themeMode.value)
        assertContentEquals(listOf("Виктор Цой"), destination.searches.recent(Int.MAX_VALUE))
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
        override fun setStartPage(page: StartPage) {
            if (page in pageLayout.value.visiblePages) startPage.value = page
        }
        override val pageLayout = MutableStateFlow(PageLayout())
        override fun setPageLayout(layout: PageLayout) {
            pageLayout.value = layout.normalized()
            startPage.value = pageLayout.value.resolveStartPage(startPage.value)
        }
        override val trackColorMode: MutableStateFlow<TrackColorMode> = MutableStateFlow(TrackColorMode.DYNAMIC)
        override fun setTrackColorMode(mode: TrackColorMode) { trackColorMode.value = mode }
        override val smartQueueLength: MutableStateFlow<Int> = MutableStateFlow(DEFAULT_SMART_QUEUE_LENGTH)
        override fun setSmartQueueLength(length: Int) { smartQueueLength.value = sanitizeSmartQueueLength(length) }
        override val artistVariety: MutableStateFlow<Int> = MutableStateFlow(DEFAULT_ARTIST_VARIETY)
        override fun setArtistVariety(level: Int) { artistVariety.value = sanitizeArtistVariety(level) }
        override val includeNoveltyMixes: MutableStateFlow<Boolean> = MutableStateFlow(false)
        override fun setIncludeNoveltyMixes(enabled: Boolean) { includeNoveltyMixes.value = enabled }
        override val normalizeVolume: MutableStateFlow<Boolean> = MutableStateFlow(false)
        override fun setNormalizeVolume(enabled: Boolean) { normalizeVolume.value = enabled }
        override val crossfadeSeconds: MutableStateFlow<Int> = MutableStateFlow(0)
        override fun setCrossfadeSeconds(seconds: Int) { crossfadeSeconds.value = seconds }
        override val songSort: MutableStateFlow<SortChoice<SongSort>> = MutableStateFlow(DEFAULT_SONG_SORT)
        override fun setSongSort(choice: SortChoice<SongSort>) { songSort.value = choice }
        override val albumSort: MutableStateFlow<SortChoice<AlbumSort>> = MutableStateFlow(DEFAULT_ALBUM_SORT)
        override fun setAlbumSort(choice: SortChoice<AlbumSort>) { albumSort.value = choice }
        override val artistAlbumSort: MutableStateFlow<SortChoice<AlbumSort>> =
            MutableStateFlow(DEFAULT_ARTIST_ALBUM_SORT)
        override fun setArtistAlbumSort(choice: SortChoice<AlbumSort>) { artistAlbumSort.value = choice }
        private var trackLoudnessPayload: String? = null
        private var trackGenresPayload: String? = null
        override fun readTrackLoudnessPayload(): String? = trackLoudnessPayload
        override fun writeTrackLoudnessPayload(payload: String) { trackLoudnessPayload = payload }

        override fun readTrackGenresPayload(): String? = trackGenresPayload
        override fun writeTrackGenresPayload(payload: String) {
            trackGenresPayload = payload
        }
        override fun readAudioCarryOversPayload(): String? = null
        override fun writeAudioCarryOversPayload(payload: String) = Unit
        private var duplicateDismissalsPayload: String? = null
        override fun readDuplicateDismissalsPayload(): String? = duplicateDismissalsPayload
        override fun writeDuplicateDismissalsPayload(payload: String) {
            duplicateDismissalsPayload = payload
        }
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
        listenedMs = listenedMs,
    )
}
