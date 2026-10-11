/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.DefaultFavorites
import io.github.nikitasud.latentjam.history.DefaultListeningHistory
import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.history.FavoritesStore
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
import io.github.nikitasud.latentjam.library.SongSortDirection
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
                songSort = SortChoice(SongSort.RECENT, SongSortDirection.ASCENDING),
                albumSort = SortChoice(AlbumSort.ARTIST, SongSortDirection.DESCENDING),
                artistAlbumSort = SortChoice(AlbumSort.TITLE, SongSortDirection.DESCENDING),
                groupSortOrders = GroupSortOrders(genres = SongSortDirection.DESCENDING),
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
        assertTrue(encoded.startsWith("LATENTJAM-LOCAL-BACKUP\t9\n"))
        assertFalse(encoded.contains("Группа крови"), "User strings must be safely encoded")
    }

    @Test
    fun groupOrderSurvivesBackupRestoreAndVersionSevenDefaultsToAscending() = runTest {
        val source = fixture(emptyList())
        val orders = GroupSortOrders(SongSortDirection.DESCENDING, SongSortDirection.DESCENDING)
        source.settings.setGroupSortOrders(orders)
        val destination = fixture(emptyList())
        destination.service.importEncoded(source.service.exportEncoded(), LocalBackupRestoreMode.REPLACE)
        assertEquals(orders, destination.settings.groupSortOrders.value)

        val legacy = emptySnapshot().copy(formatVersion = 7)
        val encoded = LocalBackupCodec.encode(legacy)
        assertFalse(encoded.lineSequence().any { it.startsWith("G\t") })
        destination.service.importEncoded(encoded, LocalBackupRestoreMode.REPLACE)
        assertEquals(GroupSortOrders(), destination.settings.groupSortOrders.value)
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.encode(legacy.copy(settings = legacy.settings.copy(groupSortOrders = orders)))
        }
    }

    @Test
    fun groupOrderRejectsInvalidDuplicateAndPrematureRecords() {
        val encoded = LocalBackupCodec.encode(emptySnapshot())
        val record = "G\tASCENDING,ASCENDING,ASCENDING"
        for (bad in listOf("G\tASCENDING", "G\tDESCENDING,unknown,ASCENDING", "$record\n$record")) {
            assertFailsWith<LocalBackupFormatException> {
                LocalBackupCodec.decode(encoded.replace(record, bad))
            }
        }
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.decode(encoded.replaceFirst("LOCAL-BACKUP\t9", "LOCAL-BACKUP\t7"))
        }
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
    fun sortOrdersArrivedWithTheV7BumpWhileAnInterimV6FileStillReads() {
        val v5 =
            "LATENTJAM-LOCAL-BACKUP\t5\n" +
                "C\t1\n" +
                "S\tSYSTEM\ttracks\tdynamic\t20\t1\t1\t0\t0\t0\n"
        val sorts = "O\ttitle:ascending\trecent:descending\tyear:ascending\n"

        // The record belongs to v7: a v5 file cannot carry it, and neither can a v5 snapshot.
        assertFailsWith<LocalBackupFormatException> { LocalBackupCodec.decode(v5 + sorts) }
        val base = emptySnapshot()
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.validate(
                base.copy(
                    formatVersion = 5,
                    settings = base.settings.copy(
                        songSort = SortChoice(SongSort.RECENT, SongSortDirection.ASCENDING),
                    ),
                ),
            )
        }

        // A v6 file without the record — the shape every build before the sort fix wrote — restores
        // the defaults, and one an interim branch build already wrote the record into keeps its
        // orders: the bump changes what new exports declare, not what those v6 files mean.
        val v6 = v5.replaceFirst("\t5\n", "\t6\n")
        assertEquals(DEFAULT_SONG_SORT, LocalBackupCodec.decode(v6).settings.songSort)
        val interim = LocalBackupCodec.decode(v6 + sorts)
        assertEquals(SortChoice(SongSort.TITLE, SongSortDirection.ASCENDING), interim.settings.songSort)
        assertEquals(SortChoice(AlbumSort.RECENT, SongSortDirection.DESCENDING), interim.settings.albumSort)
        assertEquals(SortChoice(AlbumSort.YEAR, SongSortDirection.ASCENDING), interim.settings.artistAlbumSort)

        // A v7 file reads the same record, while a value the app decoder would have "repaired" is
        // refused: a repaired order is not one this format wrote. The artist page sorts by year or
        // title only, so its ARTIST field is repaired away and refused as well.
        val v7 = v5.replaceFirst("\t5\n", "\t7\n")
        assertEquals(interim.settings, LocalBackupCodec.decode(v7 + sorts).settings)
        for (malformed in listOf(
            "O\ttitle\trecent:descending\tyear:ascending\n",
            "O\tbpm:descending\trecent:descending\tyear:ascending\n",
            "O\ttitle:sideways\trecent:descending\tyear:ascending\n",
            "O\ttitle:ascending\trecent:descending\tartist:descending\n",
            "O\ttitle:ascending\trecent:descending\tyear:ascending\n" +
                "O\ttitle:ascending\trecent:descending\tyear:ascending\n",
        )) {
            assertFailsWith<LocalBackupFormatException>(malformed) {
                LocalBackupCodec.decode(v7 + malformed)
            }
        }
    }

    @Test
    fun codecReadsAFrozenHistoricalV6FixtureWithAndWithoutTheSortRecord() {
        // A literal v6 file with its frozen header: artist variety without list orders. Both shapes
        // of that version must keep reading — the one above, and the interim one carrying "O".
        val fixture =
            "LATENTJAM-LOCAL-BACKUP\t6\n" +
                "C\t17\n" +
                "S\tDARK\tmap\tsmart\t40\t1\t1\t1\t0\t5\n" +
                "L\tLJPL1|map,tracks,albums,artists,genres,folders,playlists,for_you|genres\n" +
                "V\t3\n"

        val withoutSortRecord = LocalBackupCodec.decode(fixture)

        assertEquals(6, withoutSortRecord.formatVersion)
        assertEquals(17L, withoutSortRecord.createdAtMs)
        assertEquals(3, withoutSortRecord.settings.artistVariety)
        assertEquals(DEFAULT_SONG_SORT, withoutSortRecord.settings.songSort)
        assertEquals(DEFAULT_ALBUM_SORT, withoutSortRecord.settings.albumSort)
        assertEquals(DEFAULT_ARTIST_ALBUM_SORT, withoutSortRecord.settings.artistAlbumSort)

        val interim = LocalBackupCodec.decode(
            fixture + "O\ttitle:ascending\trecent:descending\tyear:ascending\n",
        )

        assertEquals(SortChoice(SongSort.TITLE, SongSortDirection.ASCENDING), interim.settings.songSort)
        assertEquals(SortChoice(AlbumSort.RECENT, SongSortDirection.DESCENDING), interim.settings.albumSort)
        assertEquals(SortChoice(AlbumSort.YEAR, SongSortDirection.ASCENDING), interim.settings.artistAlbumSort)
    }

    @Test
    fun aVersionSixSnapshotKeepsItsSortOrdersOnARoundTrip() {
        // decode() reads the record in v6 and validate() accepts it there, so a pinned-version write
        // must keep it instead of dropping the choice; every export the app makes is v7, which older
        // builds refuse with "Unsupported backup version".
        val pinned = emptySnapshot().copy(
            formatVersion = 6,
            settings = emptySnapshot().settings.copy(
                songSort = SortChoice(SongSort.RECENT, SongSortDirection.ASCENDING),
            ),
        )

        val encoded = LocalBackupCodec.encode(pinned)

        assertTrue(encoded.startsWith("LATENTJAM-LOCAL-BACKUP\t6\n"))
        assertContains(encoded, "O\trecent:ascending\ttitle:ascending\tyear:descending\n")
        assertEquals(pinned, LocalBackupCodec.decode(encoded))
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
        assertTrue(encoded.startsWith("LATENTJAM-LOCAL-BACKUP\t9\n"))
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
        // One version past the current format: a file from a newer build is refused whole, before
        // any record of it is read.
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.decode(valid.replaceFirst("LOCAL-BACKUP\t9", "LOCAL-BACKUP\t10"))
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

        assertTrue(encoded.startsWith("LATENTJAM-LOCAL-BACKUP\t9\n"))
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
        source.settings.setSongSort(SortChoice(SongSort.RECENT, SongSortDirection.DESCENDING))
        source.settings.setAlbumSort(SortChoice(AlbumSort.ARTIST, SongSortDirection.ASCENDING))
        source.settings.setArtistAlbumSort(SortChoice(AlbumSort.YEAR, SongSortDirection.ASCENDING))
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
        assertEquals(
            SortChoice(SongSort.RECENT, SongSortDirection.DESCENDING),
            destination.settings.songSort.value,
        )
        assertEquals(
            SortChoice(AlbumSort.ARTIST, SongSortDirection.ASCENDING),
            destination.settings.albumSort.value,
        )
        assertEquals(
            SortChoice(AlbumSort.YEAR, SongSortDirection.ASCENDING),
            destination.settings.artistAlbumSort.value,
        )
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

    // ------------------------------------------------- v9: favourites and duplicate decisions

    @Test
    fun codecRoundTripsFavoritesNewestFirstAndDuplicateDecisionsInV9() {
        // The codec writes track references in id order, so the snapshot lists them that way.
        val references = listOf("b", "c", "d", "file:\nКИНО\t1").map {
            LocalBackupTrackReference(it, "Title $it", "Artist", null, 200_000)
        }
        val snapshot = emptySnapshot().copy(
            tracks = references,
            favoriteTrackReferenceIds = listOf("c", "file:\nКИНО\t1", "b"),
            duplicateVerdicts = listOf(listOf("b", "c"), listOf("file:\nКИНО\t1", "c", "d")),
        )

        val encoded = LocalBackupCodec.encode(snapshot)

        assertEquals(snapshot, LocalBackupCodec.decode(encoded))
        assertTrue(encoded.startsWith("LATENTJAM-LOCAL-BACKUP\t9\n"))
        // One record per heart, newest first; one per verdict, every track of it on the line.
        assertEquals(
            listOf("F\ts63", "F\ts${"file:\nКИНО\t1".hex()}", "F\ts62"),
            encoded.lines().filter { it.startsWith("F\t") },
        )
        assertEquals(
            listOf("N\ts62\ts63", "N\ts${"file:\nКИНО\t1".hex()}\ts63\ts64"),
            encoded.lines().filter { it.startsWith("N\t") },
        )
        assertTrue(LocalBackupCodec.decode(encoded).carriesFavoritesAndDuplicateDecisions)
    }

    @Test
    fun aFrozenV9FileReadsItsFavoritesAndDecisions() {
        // A literal fixture: hex("a") = 61, hex("b") = 62.
        val fixture =
            "LATENTJAM-LOCAL-BACKUP\t9\n" +
                "C\t5\n" +
                "S\tSYSTEM\ttracks\tdynamic\t20\t1\t1\t0\t0\t0\n" +
                "T\ts61\ts41\tn\tn\t1000\n" +
                "T\ts62\ts42\tn\tn\t2000\n" +
                "F\ts62\n" +
                "F\ts61\n" +
                "N\ts61\ts62\n"

        val decoded = LocalBackupCodec.decode(fixture)

        assertEquals(listOf("b", "a"), decoded.favoriteTrackReferenceIds)
        assertEquals(listOf(listOf("a", "b")), decoded.duplicateVerdicts)
    }

    @Test
    fun favoritesAndDecisionsAreV9RecordsThatEveryMalformedShapeFailsWhole() {
        val v9 =
            "LATENTJAM-LOCAL-BACKUP\t9\nC\t1\n" +
                "S\tSYSTEM\ttracks\tdynamic\t20\t1\t1\t0\t0\t0\n" +
                "T\ts61\tn\tn\tn\tn\nT\ts62\tn\tn\tn\tn\n"
        val v8 = v9.replaceFirst("\t9\n", "\t8\n")
        assertEquals(1, LocalBackupCodec.decode(v9 + "F\ts61\n").favoriteTrackReferenceIds.size)
        for (malformed in listOf(
            v8 + "F\ts61\n", // a v8 file cannot carry favourites,
            v8 + "N\ts61\ts62\n", // nor duplicate decisions
            v9 + "F\ts61\nF\ts61\n", // a heart repeated loses which copy is newer
            v9 + "F\ts7a\n", // a heart without its track reference
            v9 + "F\ts61\ts62\n", // one heart per record
            v9 + "F\tnot-hex\n",
            v9 + "N\ts61\n", // a verdict needs two recordings
            v9 + "N\ts61\ts61\n",
            v9 + "N\ts61\ts7a\n",
        )) {
            assertFailsWith<LocalBackupFormatException>(malformed.lines().dropLast(1).last()) {
                LocalBackupCodec.decode(malformed)
            }
        }
        val base = emptySnapshot().copy(
            tracks = listOf("a", "b").map { LocalBackupTrackReference(it, null, null, null, null) },
        )
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.encode(base.copy(formatVersion = 8, favoriteTrackReferenceIds = listOf("a")))
        }
        assertFailsWith<LocalBackupFormatException> {
            LocalBackupCodec.validate(base.copy(duplicateVerdicts = List(2_001) { listOf("a", "b") }))
        }
    }

    @Test
    fun everyOlderVersionStillReadsAndCarriesNoFavoritesOrDecisions() {
        // The frozen shapes above, one per version, plus v7 and v8 as the last builds wrote them.
        val settings3 = "S\tSYSTEM\ttracks\tdynamic\t20\t1\t1\t0\t0\t0\n"
        val fixtures = mapOf(
            1 to "C\t1\nS\tSYSTEM\ttracks\tdynamic\t20\t1\t1\nP\ts6c6567616379\ts4c6567616379206d6978\t2\n",
            2 to "C\t1\nS\tSYSTEM\ttracks\tdynamic\t20\t1\t1\nT\ts747261636b\tn\tn\tn\tn\n" +
                "P\ts6964\ts6d6978\t2\t1\ts747261636b\nH\ts747261636b\t10\t20\tn\t0\t0\tn\n",
            3 to "C\t1\n$settings3",
            4 to "C\t0\n$settings3" + "L\tLJPL1|map,tracks,albums,artists,genres,folders,playlists,for_you|genres\n" +
                "T\ts74\tn\tn\tn\tn\nH\ts74\t10\t20\tn\t0\t0\tn\t15\n",
            5 to "C\t1\n$settings3" + "T\ts74\tn\tn\tn\tn\n" +
                "H\ts74\t10\t20\tn\t0\t0\tn\t15\t1\ts534b49505f4e455854\t2\ts616c62756d3a6b\n",
            6 to "C\t17\nS\tDARK\tmap\tsmart\t40\t1\t1\t1\t0\t5\n" +
                "L\tLJPL1|map,tracks,albums,artists,genres,folders,playlists,for_you|genres\nV\t3\n",
            7 to "C\t1\n$settings3" + "V\t3\nO\ttitle:ascending\trecent:descending\tyear:ascending\n" +
                "T\ts74\ts54\tn\tn\t1000\nD\ts74\nX\ts74\nA\ts56\n",
            8 to "C\t1\n$settings3" + "G\tDESCENDING,ASCENDING,ASCENDING\nV\t3\n" +
                "O\ttitle:ascending\trecent:descending\tyear:ascending\n" +
                "T\ts74\ts54\tn\tn\t1000\nP\ts6964\ts6d6978\t2\t0\ts74\nQ\ts71\nD\ts74\n",
        )
        assertEquals((1..8).toSet(), fixtures.keys)
        for ((version, body) in fixtures) {
            val decoded = LocalBackupCodec.decode("LATENTJAM-LOCAL-BACKUP\t$version\n$body")
            assertEquals(version, decoded.formatVersion)
            assertFalse(decoded.carriesFavoritesAndDuplicateDecisions, "v$version")
            assertTrue(decoded.favoriteTrackReferenceIds.isEmpty(), "v$version")
            assertTrue(decoded.duplicateVerdicts.isEmpty(), "v$version")
        }
        val v8 = LocalBackupCodec.decode("LATENTJAM-LOCAL-BACKUP\t8\n" + fixtures.getValue(8))
        assertEquals(GroupSortOrders(artists = SongSortDirection.DESCENDING), v8.settings.groupSortOrders)
        assertEquals(listOf("t"), v8.playlists.single().trackReferenceIds)
    }

    @Test
    fun restoringAnOlderFileLeavesFavoritesAndDecisionsExactlyAsTheyWere() = runTest {
        val song = track("t", "T", "Artist", "Album", 1_000)
        val other = track("u", "U", "Artist", "Album", 2_000)
        val fixture = fixture(listOf(song, other))
        fixture.favorites.replace(listOf(other.id, song.id))
        DuplicateDismissals.rememberIn(fixture.settings, listOf(song.id, other.id))
        val verdictsBefore = fixture.settings.readDuplicateDismissalsPayload()
        // A v8 file that names the same track in a playlist, hidden and SMART exclusion: a REPLACE
        // restore of it must leave the hearts and the verdicts alone, as every restore did before.
        val v8 = "LATENTJAM-LOCAL-BACKUP\t8\nC\t1\nS\tSYSTEM\ttracks\tdynamic\t20\t1\t1\t0\t0\t0\n" +
            "T\ts74\ts54\tn\tn\t1000\nP\ts6964\ts6d6978\t2\t0\ts74\nD\ts74\n"

        for (mode in LocalBackupRestoreMode.entries) {
            val report = fixture.service.importEncoded(v8, mode)

            assertEquals(listOf(other.id, song.id), fixture.favorites.all(), "$mode")
            assertEquals(verdictsBefore, fixture.settings.readDuplicateDismissalsPayload(), "$mode")
            assertFalse(LocalBackupSection.FAVORITES in report.completedSections)
            assertFalse(LocalBackupSection.DUPLICATE_DECISIONS in report.completedSections)
            assertTrue(LocalBackupSection.PLAYLISTS in report.completedSections)
            assertEquals(0, report.favoritesApplied)
        }
        assertContentEquals(listOf("t"), fixture.playlists.all().single().trackIds)
    }

    @Test
    fun favoritesAndDecisionsTravelToAnotherDeviceInTheirOrder() = runTest {
        val tracks = listOf("a", "b", "c", "d").map { track("old-$it", "Song $it", "Artist", "Album", 100_000) }
        val (a, b, c, d) = tracks
        val source = fixture(tracks)
        listOf(a, b, c).forEach { source.favorites.toggle(it.id) } // newest first: c, b, a
        DuplicateDismissals.rememberIn(source.settings, listOf(a.id, b.id))
        DuplicateDismissals.rememberIn(source.settings, listOf(c.id, d.id, b.id))

        val encoded = source.service.exportEncoded()
        // Another device: the same songs under other ids, so every reference matches by its tags.
        val destination = fixture(tracks.map { it.copy(id = TrackId(it.id.value.replace("old", "new"))) })
        val report = destination.service.importEncoded(encoded, LocalBackupRestoreMode.MERGE)

        assertEquals(listOf("new-c", "new-b", "new-a"), destination.favorites.all().map { it.value })
        assertEquals(
            listOf(listOf("new-a", "new-b"), listOf("new-b", "new-c", "new-d")),
            DuplicateDismissals.verdicts(destination.settings.readDuplicateDismissalsPayload())
                .map { verdict -> verdict.map { it.value } },
        )
        assertEquals(3, report.favoritesApplied)
        assertEquals(2, report.duplicateDecisionsApplied)
        assertEquals(0, report.unresolvedTrackReferences)
        assertTrue(LocalBackupSection.FAVORITES in report.completedSections)
        assertTrue(LocalBackupSection.DUPLICATE_DECISIONS in report.completedSections)
    }

    @Test
    fun aMergeKeepsEveryCurrentHeartInPlaceAndAddsTheRestoredOnesAfterInTheirOrder() = runTest {
        val tracks = listOf("a", "b", "c", "x").map { track(it, "Song $it", "Artist", "Album", 100_000) }
        val (a, b, c, x) = tracks
        val fixture = fixture(tracks)
        fixture.favorites.replace(listOf(c.id, b.id, a.id))
        val encoded = fixture.service.exportEncoded()
        // Since that backup: a un-hearted, x hearted, so the device now reads x, c, b.
        fixture.favorites.replace(listOf(x.id, c.id, b.id))

        fixture.service.importEncoded(encoded, LocalBackupRestoreMode.MERGE)
        assertEquals(listOf(x.id, c.id, b.id, a.id), fixture.favorites.all())

        // The same file again changes nothing: every heart in it is already here.
        fixture.service.importEncoded(encoded, LocalBackupRestoreMode.MERGE)
        assertEquals(listOf(x.id, c.id, b.id, a.id), fixture.favorites.all())

        assertEquals(listOf(b.id, c.id, x.id), mergedFavorites(listOf(b.id), listOf(c.id, b.id, x.id)))
        assertEquals(listOf(c.id), mergedFavorites(emptyList(), listOf(c.id)))
    }

    @Test
    fun aReplaceRestoreBringsBackTheBackupsHeartsExactly() = runTest {
        val tracks = listOf("a", "b", "x").map { track(it, "Song $it", "Artist", "Album", 100_000) }
        val (a, b, x) = tracks
        val fixture = fixture(tracks)
        fixture.favorites.replace(listOf(b.id, a.id))
        DuplicateDismissals.rememberIn(fixture.settings, listOf(a.id, b.id))
        val encoded = fixture.service.exportEncoded()
        fixture.favorites.replace(listOf(x.id))
        DuplicateDismissals.rememberIn(fixture.settings, listOf(a.id, x.id))

        fixture.service.importEncoded(encoded, LocalBackupRestoreMode.REPLACE)

        assertEquals(listOf(b.id, a.id), fixture.favorites.all())
        assertEquals(
            listOf(listOf(a.id, b.id)),
            DuplicateDismissals.verdicts(fixture.settings.readDuplicateDismissalsPayload()),
        )
    }

    @Test
    fun heartsAndVerdictMembersThatMatchNothingAreSkippedAndCounted() = runTest {
        val a = track("a", "Song a", "Artist", "Album", 100_000)
        val b = track("b", "Song b", "Artist", "Album", 110_000)
        val gone = track("gone", "Lost song", "Artist", "Album", 120_000)
        val source = fixture(listOf(a, b, gone))
        source.favorites.replace(listOf(gone.id, a.id))
        DuplicateDismissals.rememberIn(source.settings, listOf(a.id, gone.id))
        DuplicateDismissals.rememberIn(source.settings, listOf(a.id, b.id, gone.id))
        val encoded = source.service.exportEncoded()

        val destination = fixture(listOf(a, b))
        val report = destination.service.importEncoded(encoded, LocalBackupRestoreMode.MERGE)

        // One reference (the lost song) is unmatched, however many records name it.
        assertEquals(1, report.unresolvedTrackReferences)
        assertEquals(listOf(a.id), destination.favorites.all())
        assertEquals(1, report.favoritesApplied)
        // {a, gone} keeps nothing apart here; {a, b, gone} still keeps a and b apart.
        assertEquals(
            listOf(listOf(a.id, b.id)),
            DuplicateDismissals.verdicts(destination.settings.readDuplicateDismissalsPayload()),
        )
        assertEquals(1, report.duplicateDecisionsApplied)
    }

    @Test
    fun aRestoreNeverEmptiesFavoritesOrDecisionsWhileTheLibraryIsNotLoaded() = runTest {
        val song = track("song", "Song", "Artist", "Album", 120_000)
        val other = track("other", "Other", "Artist", "Album", 130_000)
        val source = fixture(listOf(song, other))
        source.favorites.replace(listOf(song.id))
        DuplicateDismissals.rememberIn(source.settings, listOf(song.id, other.id))
        val encoded = source.service.exportEncoded()

        // A scan without the media permission returns nothing, which is not proof of deletion.
        val destination = fixture(emptyList())
        destination.favorites.replace(listOf(TrackId("kept")))
        DuplicateDismissals.rememberIn(destination.settings, listOf(TrackId("k1"), TrackId("k2")))
        val verdictsBefore = destination.settings.readDuplicateDismissalsPayload()
        val onlyTheNewSections = noSections().copy(favorites = true, duplicateDecisions = true)

        val failure = assertFailsWith<LocalBackupRestoreException> {
            destination.service.importEncoded(encoded, LocalBackupRestoreMode.REPLACE, onlyTheNewSections)
        }
        assertTrue(failure.completedSections.isEmpty())
        assertTrue(failure.cause is LocalBackupLibraryUnavailableException)
        assertEquals(listOf(TrackId("kept")), destination.favorites.all())
        assertEquals(verdictsBefore, destination.settings.readDuplicateDismissalsPayload())

        // A merge adds whatever the library confirms, here nothing, and takes nothing away.
        val report = destination.service.importEncoded(encoded, LocalBackupRestoreMode.MERGE, onlyTheNewSections)
        assertEquals(2, report.unresolvedTrackReferences)
        assertEquals(listOf(TrackId("kept")), destination.favorites.all())
        assertEquals(verdictsBefore, destination.settings.readDuplicateDismissalsPayload())
    }

    @Test
    fun aHeartChangedWhileTheMergeRunsIsKeptNotOverwritten() = runTest {
        val tracks = listOf("a", "b", "x").map { track(it, "Song $it", "Artist", "Album", 100_000) }
        val (a, b, x) = tracks
        val source = fixture(tracks)
        source.favorites.replace(listOf(b.id, a.id))
        val encoded = source.service.exportEncoded()

        val stored = DefaultFavorites(InMemoryFavoritesStore())
        // The listener hearts x between the merge's read and its write.
        val racing = object : Favorites by stored {
            var raced = false
            override suspend fun replaceIfUnchanged(expected: List<TrackId>, replacement: List<TrackId>): Boolean {
                if (!raced) {
                    raced = true
                    stored.toggle(x.id)
                }
                return stored.replaceIfUnchanged(expected, replacement)
            }
        }
        val destination = fixture(tracks)
        val service = LocalBackupService(
            destination.settings,
            destination.playlists,
            destination.history,
            destination.searches,
            destination.library,
            destination.exclusions,
            racing,
        )

        service.importEncoded(encoded, LocalBackupRestoreMode.MERGE)

        assertEquals(listOf(x.id, b.id, a.id), stored.all())
    }

    @Test
    fun theFindersVerdictAndARestoreWriteThroughTheSameStore() = runTest {
        val settings = FakeSettings()
        DuplicateDismissals.rememberIn(settings, listOf(TrackId("b"), TrackId("a")))
        DuplicateDismissals.restore(settings, listOf(listOf(TrackId("c"), TrackId("d"))), replace = false)
        assertEquals(
            listOf(listOf(TrackId("c"), TrackId("d")), listOf(TrackId("a"), TrackId("b"))),
            DuplicateDismissals.verdicts(settings.readDuplicateDismissalsPayload()),
        )

        DuplicateDismissals.restore(settings, listOf(listOf(TrackId("f"), TrackId("e"))), replace = true)
        assertEquals(listOf(listOf(TrackId("e"), TrackId("f"))), DuplicateDismissals.verdicts(settings.readDuplicateDismissalsPayload()))
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
        favorites = false,
        duplicateDecisions = false,
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
        val favorites = DefaultFavorites(InMemoryFavoritesStore())
        return Fixture(
            settings,
            playlists,
            history,
            searches,
            library,
            exclusions,
            favorites,
            LocalBackupService(settings, playlists, history, searches, library, exclusions, favorites),
        )
    }

    private data class Fixture(
        val settings: FakeSettings,
        val playlists: DefaultPlaylists,
        val history: DefaultListeningHistory,
        val searches: DefaultRecentSearches,
        val library: FakeLibrary,
        val exclusions: SmartExclusions,
        val favorites: DefaultFavorites,
        val service: LocalBackupService,
    )

    private class InMemoryFavoritesStore : FavoritesStore {
        private var ids = emptyList<String>()
        override suspend fun read(): List<String> = ids
        override suspend fun write(ids: List<String>) { this.ids = ids.toList() }
    }

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
        override val relativePlaylistPaths: MutableStateFlow<Boolean> = MutableStateFlow(false)
        override fun setRelativePlaylistPaths(enabled: Boolean) { relativePlaylistPaths.value = enabled }
        override val songSort: MutableStateFlow<SortChoice<SongSort>> = MutableStateFlow(DEFAULT_SONG_SORT)
        override fun setSongSort(choice: SortChoice<SongSort>) { songSort.value = choice }
        override val albumSort: MutableStateFlow<SortChoice<AlbumSort>> = MutableStateFlow(DEFAULT_ALBUM_SORT)
        override fun setAlbumSort(choice: SortChoice<AlbumSort>) { albumSort.value = choice }
        override val artistAlbumSort: MutableStateFlow<SortChoice<AlbumSort>> =
            MutableStateFlow(DEFAULT_ARTIST_ALBUM_SORT)
        override val groupSortOrders = MutableStateFlow(GroupSortOrders())
        override fun setGroupSortOrders(orders: GroupSortOrders) { groupSortOrders.value = orders }
        override fun setArtistAlbumSort(choice: SortChoice<AlbumSort>) { artistAlbumSort.value = choice }
        override val playlistSortOrders = MutableStateFlow(PlaylistSortOrders())
        override fun setPlaylistSortOrders(orders: PlaylistSortOrders) { playlistSortOrders.value = orders }
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

    private fun String.hex(): String = encodeToByteArray().joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

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
