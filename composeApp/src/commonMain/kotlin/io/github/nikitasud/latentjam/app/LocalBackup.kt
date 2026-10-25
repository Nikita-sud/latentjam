/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.Favorites
import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListenOrigin
import io.github.nikitasud.latentjam.history.ListenStart
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.RecentSearches
import io.github.nikitasud.latentjam.history.SmartExclusionState
import io.github.nikitasud.latentjam.history.SmartExclusions
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.library.AlbumSort
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.library.Playlists
import io.github.nikitasud.latentjam.library.reconcilePlaylistAddedOrder
import io.github.nikitasud.latentjam.library.SongSort
import io.github.nikitasud.latentjam.playback.MAX_CROSSFADE_SECONDS
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** File extension offered by the future platform document picker. The payload is always local. */
internal const val LOCAL_BACKUP_FILE_EXTENSION: String = "ljbackup"

/** Import behavior exposed separately so Settings can offer an honest merge/replace confirmation. */
internal enum class LocalBackupRestoreMode { MERGE, REPLACE }

/** Sections can be selected independently by a future import confirmation sheet. */
internal data class LocalBackupSections(
    val settings: Boolean = true,
    val playlists: Boolean = true,
    val listeningHistory: Boolean = true,
    val recentSearches: Boolean = true,
    val hiddenTracks: Boolean = true,
    val smartExclusions: Boolean = true,
    /** Added with backup v9; an older file carries no favourites, and the current ones stay. */
    val favorites: Boolean = true,
    /** The duplicate finder's "Not duplicates" verdicts; like [favorites], from v9 on. */
    val duplicateDecisions: Boolean = true,
)

internal data class LocalBackupSettings(
    val themeMode: ThemeMode,
    val startPage: StartPage,
    val trackColorMode: TrackColorMode,
    val smartQueueLength: Int,
    val saveListeningHistory: Boolean,
    val rememberSearches: Boolean,
    /** Added in backup v3; legacy snapshots restore the safe disabled default. */
    val includeNoveltyMixes: Boolean = false,
    /** Added in backup v3; legacy snapshots restore the safe disabled default. */
    val normalizeVolume: Boolean = false,
    /** Added in backup v3; legacy snapshots restore the safe disabled default. */
    val crossfadeSeconds: Int = 0,
    /** Added in backup v4; older snapshots keep Map disabled by default. */
    val pageLayout: PageLayout = PageLayout(),
    /** Added in backup v6; older snapshots restore the default. */
    val artistVariety: Int = DEFAULT_ARTIST_VARIETY,
    /**
     * The Tracks tab's order, added in backup v7. Travels as its own record, so a v6 file written
     * before that record existed — like every older format — restores the default; the interim v6
     * files that already carry it keep their orders.
     */
    val songSort: SortChoice<SongSort> = DEFAULT_SONG_SORT,
    /** The Albums tab's order; see [songSort]. */
    val albumSort: SortChoice<AlbumSort> = DEFAULT_ALBUM_SORT,
    /** An artist's page order; see [songSort]. */
    val artistAlbumSort: SortChoice<AlbumSort> = DEFAULT_ARTIST_ALBUM_SORT,
    val groupSortOrders: GroupSortOrders = GroupSortOrders(),
    /** Added in backup v11 with the "K" record; older snapshots restore the default look. */
    val appearance: Appearance = Appearance(),
    /** The browse header's style; see [appearance]. */
    val pageHeaderStyle: PageHeaderStyle = PageHeaderStyle.DOTS,
)

/**
 * A portable identity hint, never an audio locator.
 *
 * The original id makes same-device restores exact. A reference captured without a single field (the
 * library was unavailable at export time) is that id alone, so it is matched only while the id space
 * is confirmed: every described reference in the same snapshot still fits the track that holds its
 * id. One described reference that lost its track, or found a different one under the same id, means
 * the ids were reassigned, and the bare ids of that snapshot stay unresolved. A snapshot with no
 * described reference at all carries no evidence against its ids, so those bare ids are matched.
 * Metadata permits a conservative unique match after MediaStore or imported-file ids change. Audio
 * paths, artwork paths, and model embeddings are deliberately absent from backups.
 */
internal data class LocalBackupTrackReference(
    val originalId: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

internal data class LocalBackupPlaylist(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val trackReferenceIds: List<String>,
    val includeInSmart: Boolean = false,
    val addedTrackReferenceIds: List<String> = trackReferenceIds,
)

internal data class LocalBackupListenEvent(
    val trackReferenceId: String,
    val startedAtMs: Long,
    val playedMs: Long,
    val trackDurationMs: Long?,
    val completed: Boolean,
    val skipped: Boolean,
    val shuffleMode: String?,
    /** Added in backup v3; null means the legacy playhead approximation must be used. */
    val listenedMs: Long? = null,
    /** Added in backup v5; null for listens recorded before origins were logged. */
    val origin: ListenOrigin? = null,
)

/** Versioned, platform-neutral state. It contains no music files and implies no cloud storage. */
internal data class LocalBackupSnapshot(
    val formatVersion: Int = LocalBackupCodec.FORMAT_VERSION,
    val createdAtMs: Long,
    val settings: LocalBackupSettings,
    val tracks: List<LocalBackupTrackReference>,
    val playlists: List<LocalBackupPlaylist>,
    /** Oldest first, matching [ListeningHistory.replace]. */
    val listeningHistory: List<LocalBackupListenEvent>,
    /** Newest first, matching [RecentSearches.replace]. */
    val recentSearches: List<String>,
    val hiddenTrackReferenceIds: Set<String>,
    val smartExcludedTrackReferenceIds: Set<String>,
    val smartExcludedArtists: Set<String>,
    /**
     * The hearts, newest first, matching [Favorites.all]. Added in backup v9: an older snapshot
     * says nothing about favourites, so restoring it leaves the current ones exactly as they are.
     */
    val favoriteTrackReferenceIds: List<String> = emptyList(),
    /**
     * The duplicate finder's "Not duplicates" verdicts, oldest first as the finder keeps them. Each
     * is a group of at least two references the listener said are different recordings, so the
     * finder never offers them as copies of one another again. Added in backup v9, like
     * [favoriteTrackReferenceIds].
     */
    val duplicateVerdicts: List<List<String>> = emptyList(),
) {
    /** Whether this snapshot speaks for favourites and duplicate decisions at all (v9 on). */
    val carriesFavoritesAndDuplicateDecisions: Boolean
        get() = formatVersion >= FIRST_VERSION_WITH_FAVORITES

    /** v11 on: the look (appearance and page-header style); older files leave it as it is. */
    val carriesAppearance: Boolean
        get() = formatVersion >= FIRST_VERSION_WITH_APPEARANCE
}

/** The format version that brought the favourites ("F") and duplicate decision ("N") records. */
private const val FIRST_VERSION_WITH_FAVORITES = 9

/** The format version that brought the look ("K") record and the custom track colour mode. */
private const val FIRST_VERSION_WITH_APPEARANCE = 11

internal enum class LocalBackupSection {
    PLAYLISTS,
    LISTENING_HISTORY,
    RECENT_SEARCHES,
    HIDDEN_TRACKS,
    SMART_EXCLUSIONS,
    FAVORITES,
    DUPLICATE_DECISIONS,
    SETTINGS,
}

internal data class LocalBackupRestoreReport(
    val completedSections: Set<LocalBackupSection>,
    val resolvedTrackReferences: Int,
    val unresolvedTrackReferences: Int,
    val playlistsApplied: Int,
    val listeningEventsApplied: Int,
    val recentSearchesApplied: Int,
    val hiddenTracksApplied: Int,
    val smartTrackExclusionsApplied: Int,
    val smartArtistExclusionsApplied: Int,
    /** Restored hearts that matched a track on this device; 0 for a file older than v9. */
    val favoritesApplied: Int = 0,
    /** Restored verdicts the store keeps, each with at least two matched tracks; 0 before v9. */
    val duplicateDecisionsApplied: Int = 0,
    /**
     * Restored verdicts with at least two matched tracks that the store could not keep: under this
     * device's ids the record outgrew the store's bound, or a merge found the store full.
     */
    val duplicateDecisionsNotKept: Int = 0,
)

/** A restore stopped after [completedSections] had already been durably requested. */
internal class LocalBackupRestoreException(
    val completedSections: Set<LocalBackupSection>,
    cause: Throwable,
) : IllegalStateException("Local backup restore stopped after ${completedSections.size} sections", cause)

/** What the listener is told once a restore attempt ends. */
internal enum class LocalBackupRestoreMessage { RESTORED, RESTORED_UNMATCHED, PARTIAL, FAILED, INVALID }

/**
 * How Settings closes a restore attempt. [reloadApp] says whether the app has to re-read what the
 * restore wrote. It does after a complete restore, and just as much after one that stopped part-way:
 * the sections applied before the failure are already in their stores, and favourites, playlists,
 * history and the rest would otherwise keep showing their old state until the next restart. Only an
 * attempt that wrote nothing leaves the app as it was.
 */
internal data class LocalBackupRestoreOutcome(
    val reloadApp: Boolean,
    val message: LocalBackupRestoreMessage,
    val unmatchedTrackReferences: Int = 0,
    /** Restored duplicate finder decisions this device could not keep; said beside [message]. */
    val duplicateDecisionsNotKept: Int = 0,
)

internal fun localBackupRestoreOutcome(result: Result<LocalBackupRestoreReport>): LocalBackupRestoreOutcome {
    val report = result.getOrElse { failure ->
        return when {
            failure is LocalBackupFormatException ->
                LocalBackupRestoreOutcome(reloadApp = false, message = LocalBackupRestoreMessage.INVALID)
            failure is LocalBackupRestoreException && failure.completedSections.isNotEmpty() ->
                LocalBackupRestoreOutcome(reloadApp = true, message = LocalBackupRestoreMessage.PARTIAL)
            else -> LocalBackupRestoreOutcome(reloadApp = false, message = LocalBackupRestoreMessage.FAILED)
        }
    }
    return LocalBackupRestoreOutcome(
        reloadApp = true,
        message = if (report.unresolvedTrackReferences == 0) {
            LocalBackupRestoreMessage.RESTORED
        } else {
            LocalBackupRestoreMessage.RESTORED_UNMATCHED
        },
        unmatchedTrackReferences = report.unresolvedTrackReferences,
        duplicateDecisionsNotKept = report.duplicateDecisionsNotKept,
    )
}

/**
 * The device library matched none of the snapshot track references while the snapshot carries some.
 * A scan without matches is not proof that the tracks are gone, so a replace restore that would drop
 * those references is refused.
 */
internal class LocalBackupLibraryUnavailableException(message: String) : IllegalStateException(message)

internal class LocalBackupFormatException(message: String) : IllegalArgumentException(message)

/**
 * Compact line codec designed for local document export.
 *
 * Every user string is UTF-8 hex, so tabs, newlines, Unicode, and control characters round-trip
 * without depending on a JSON library. The strict version/header, record limits, and reference
 * validation make an arbitrary document-picker input safe to reject before any app state changes.
 */
internal object LocalBackupCodec {
    /**
     * The version every export writes. A record arrives together with the version number that
     * carries it — "L" with v4, "V" with v6, "O" with v7, "G" with v8, the favourites ("F") and
     * the duplicate finder's verdicts ("N") with v9, the look ("K") with v11 — so a build that knows only an older
     * version refuses such a file with "Unsupported backup version" instead of tripping over a
     * record it does not know ("Unknown backup record at line N").
     */
    const val FORMAT_VERSION: Int = 11
    private const val LEGACY_FORMAT_VERSION: Int = 1
    private const val HEADER = "LATENTJAM-LOCAL-BACKUP"
    private const val MAX_TEXT_CHARS = 64 * 1024 * 1024
    private const val MAX_TRACKS = 200_000
    private const val MAX_PLAYLISTS = 10_000
    private const val MAX_PLAYLIST_TRACKS = 200_000
    private const val MAX_HISTORY_EVENTS = 500_000
    private const val MAX_SEARCHES = 100
    private const val MAX_ARTIST_EXCLUSIONS = 100_000

    /** The finder keeps no more verdicts than this, so a file with more was not written by it. */
    private const val MAX_DUPLICATE_VERDICTS = DuplicateDismissals.MAX_VERDICTS
    private const val MAX_DUPLICATE_VERDICT_MEMBERS = 200_000
    private const val MAX_ENCODED_FIELD_CHARS = 2 * 1024 * 1024 + 1
    private const val MAX_RECORD_CHARS = 16 * 1024 * 1024

    /**
     * The per-query bounds the recent-search store enforces when it writes its own file
     * (`RecentSearchFileCodec.MAX_QUERY_BYTES` applies to the character count and to the UTF-8 byte
     * count alike). A snapshot past either bound would decode cleanly here and then fail that write,
     * after the sections before it had already been applied.
     */
    private const val MAX_RECENT_SEARCH_QUERY_CHARS = 16 * 1024
    private const val MAX_RECENT_SEARCH_QUERY_BYTES = 16 * 1024

    fun encode(snapshot: LocalBackupSnapshot): String {
        validate(snapshot)
        return buildString {
            appendRecord(HEADER, snapshot.formatVersion.toString())
            appendRecord("C", snapshot.createdAtMs.toString())
            with(snapshot.settings) {
                appendRecord(
                    "S",
                    themeMode.name,
                    startPage.persistedValue,
                    trackColorMode.persistedValue,
                    smartQueueLength.toString(),
                    saveListeningHistory.toBit(),
                    rememberSearches.toBit(),
                    *if (snapshot.formatVersion >= 3) {
                        arrayOf(
                            includeNoveltyMixes.toBit(),
                            normalizeVolume.toBit(),
                            crossfadeSeconds.toString(),
                        )
                    } else {
                        emptyArray()
                    },
                )
                if (snapshot.formatVersion >= 8) appendRecord("G", encodeGroupSortOrders(groupSortOrders))
                if (snapshot.formatVersion >= 4) appendRecord("L", encodePageLayout(pageLayout))
                if (snapshot.formatVersion >= 6) appendRecord("V", artistVariety.toString())
                if (snapshot.formatVersion >= 6) {
                    // The list orders, introduced by v7. Written for a v6 snapshot as well: builds
                    // between the v6 and v7 bumps wrote this record into v6 files, decode() reads
                    // that shape, and validate() accepts it in v6 — so a writer that skipped it for
                    // v6 would drop a choice this codec accepts. Every export the app makes is v8.
                    appendRecord(
                        "O",
                        encodeSortChoice(songSort),
                        encodeSortChoice(albumSort),
                        encodeSortChoice(artistAlbumSort),
                    )
                }
                if (snapshot.formatVersion >= FIRST_VERSION_WITH_APPEARANCE) {
                    appendRecord("K", encodeAppearance(appearance).encodeField(), pageHeaderStyle.persistedValue)
                }
            }
            snapshot.tracks.sortedBy(LocalBackupTrackReference::originalId).forEach { track ->
                appendRecord(
                    "T",
                    track.originalId.encodeField(),
                    track.title.encodeNullableField(),
                    track.artist.encodeNullableField(),
                    track.album.encodeNullableField(),
                    track.durationMs.encodeNullableLong(),
                )
            }
            snapshot.playlists.forEach { playlist ->
                val fixedFields = buildList {
                    add("P")
                    add(playlist.id.encodeField())
                    add(playlist.name.encodeField())
                    add(playlist.createdAtMs.toString())
                    if (snapshot.formatVersion >= 2) add(playlist.includeInSmart.toBit())
                    if (snapshot.formatVersion >= 10) add(playlist.trackReferenceIds.size.toString())
                }
                appendRecord(
                    fixedFields + playlist.trackReferenceIds.map { it.encodeField() } +
                        if (snapshot.formatVersion >= 10) playlist.addedTrackReferenceIds.map { it.encodeField() } else emptyList(),
                )
            }
            snapshot.listeningHistory.forEach { event ->
                appendRecord(
                    buildList {
                        add("H")
                        add(event.trackReferenceId.encodeField())
                        add(event.startedAtMs.toString())
                        add(event.playedMs.toString())
                        add(event.trackDurationMs.encodeNullableLong())
                        add(event.completed.toBit())
                        add(event.skipped.toBit())
                        add(event.shuffleMode.encodeNullableField())
                        if (snapshot.formatVersion >= 3) add(event.listenedMs.encodeNullableLong())
                        if (snapshot.formatVersion >= 5) {
                            // A presence bit first: a legacy listen (no origin) must stay distinct
                            // from one observed with every origin field unknown.
                            val origin = event.origin
                            add((origin != null).toBit())
                            add(origin?.start?.name.encodeNullableField())
                            add(origin?.smartPlanPosition?.toLong().encodeNullableLong())
                            add(origin?.parentId.encodeNullableField())
                        }
                    },
                )
            }
            snapshot.recentSearches.forEach { appendRecord("Q", it.encodeField()) }
            snapshot.hiddenTrackReferenceIds.sorted().forEach { appendRecord("D", it.encodeField()) }
            snapshot.smartExcludedTrackReferenceIds.sorted().forEach { appendRecord("X", it.encodeField()) }
            snapshot.smartExcludedArtists.sortedBy(String::lowercase).forEach {
                appendRecord("A", it.encodeField())
            }
            // Order is meaning for both: the hearts newest first, the verdicts oldest first.
            snapshot.favoriteTrackReferenceIds.forEach { appendRecord("F", it.encodeField()) }
            snapshot.duplicateVerdicts.forEach { verdict ->
                appendRecord(listOf("N") + verdict.map { it.encodeField() })
            }
        }.also { encoded ->
            if (encoded.length > MAX_TEXT_CHARS) formatError("Backup exceeds the supported size")
        }
    }

    fun decode(encoded: String): LocalBackupSnapshot {
        if (encoded.length > MAX_TEXT_CHARS) formatError("Backup exceeds the supported size")
        val records = RecordCursor(encoded)
        val header = records.next() ?: formatError("Backup is empty")
        header.requireFieldCount(2)
        if (header.nextField() != HEADER) formatError("Not a LatentJam local backup")
        val version = header.nextField().parseInt("format version")
        if (version !in LEGACY_FORMAT_VERSION..FORMAT_VERSION) {
            formatError("Unsupported backup version: $version")
        }

        var createdAtMs: Long? = null
        var settings: LocalBackupSettings? = null
        var pageLayout: PageLayout? = null
        var artistVariety: Int? = null
        var sortOrders: SortOrders? = null
        var groupSortOrders: GroupSortOrders? = null
        var look: Pair<Appearance, PageHeaderStyle>? = null
        val tracks = mutableListOf<LocalBackupTrackReference>()
        val playlists = mutableListOf<LocalBackupPlaylist>()
        val history = mutableListOf<LocalBackupListenEvent>()
        val searches = mutableListOf<String>()
        val hidden = linkedSetOf<String>()
        val excludedTracks = linkedSetOf<String>()
        val excludedArtists = linkedSetOf<String>()
        val favorites = mutableListOf<String>()
        val verdicts = mutableListOf<List<String>>()
        var verdictMemberCount = 0
        var playlistTrackCount = 0
        var hiddenRecordCount = 0
        var excludedTrackRecordCount = 0
        var excludedArtistRecordCount = 0

        while (true) {
            val record = records.next() ?: break
            when (record.nextField()) {
                "C" -> {
                    record.requireFieldCount(2)
                    if (createdAtMs != null) formatError("Duplicate creation record")
                    createdAtMs = record.nextField().parseLong("creation time")
                }
                "S" -> {
                    record.requireFieldCount(if (version >= 3) 10 else 7)
                    if (settings != null) formatError("Duplicate settings record")
                    settings = LocalBackupSettings(
                        themeMode = enumValue<ThemeMode>(record.nextField(), "theme"),
                        startPage = record.nextField().let { persisted ->
                            startPageFromPersisted(persisted).also {
                                if (it.persistedValue != persisted) formatError("Unknown start page")
                            }
                        },
                        trackColorMode = record.nextField().let { persisted ->
                            trackColorModeFromPersisted(persisted).also {
                                if (it.persistedValue != persisted) formatError("Unknown track colour mode")
                            }
                        },
                        smartQueueLength = record.nextField().parseInt("SMART queue length"),
                        saveListeningHistory = record.nextField().parseBit("save listening history"),
                        rememberSearches = record.nextField().parseBit("remember searches"),
                        includeNoveltyMixes = if (version >= 3) {
                            record.nextField().parseBit("include novelty mixes")
                        } else {
                            false
                        },
                        normalizeVolume = if (version >= 3) {
                            record.nextField().parseBit("normalize volume")
                        } else {
                            false
                        },
                        crossfadeSeconds = if (version >= 3) {
                            record.nextField().parseInt("crossfade seconds")
                        } else {
                            0
                        },
                    )
                }
                "L" -> {
                    record.requireFieldCount(2)
                    if (version < 4) formatError("Legacy backups cannot encode page layouts")
                    if (pageLayout != null) formatError("Duplicate page layout record")
                    pageLayout = decodePageLayout(record.nextField())
                        ?: formatError("Invalid page layout")
                }
                "V" -> {
                    record.requireFieldCount(2)
                    if (version < 6) formatError("Older backups cannot encode artist variety")
                    if (artistVariety != null) formatError("Duplicate artist variety record")
                    artistVariety = record.nextField().parseInt("artist variety")
                }
                "G" -> {
                    record.requireFieldCount(2)
                    if (version < 8) formatError("Older backups cannot encode group sort orders")
                    if (groupSortOrders != null) formatError("Duplicate group sort orders")
                    val encoded = record.nextField()
                    groupSortOrders = groupSortOrdersFromPersisted(encoded)
                    if (encodeGroupSortOrders(groupSortOrders) != encoded) formatError("Unknown group sort orders")
                }
                "O" -> {
                    record.requireFieldCount(4)
                    // v6 as well as v7: the record arrived with the v7 bump, but a v6 file written
                    // by a build between the two bumps already carries it and keeps its orders.
                    if (version < 6) formatError("Older backups cannot encode sort orders")
                    if (sortOrders != null) formatError("Duplicate sort order record")
                    sortOrders = SortOrders(
                        song = record.nextField().decodeSortChoice(
                            "song sort",
                            ::songSortFromPersisted,
                        ),
                        album = record.nextField().decodeSortChoice(
                            "album sort",
                            ::albumSortFromPersisted,
                        ),
                        artistAlbum = record.nextField().decodeSortChoice(
                            "artist album sort",
                            ::artistAlbumSortFromPersisted,
                        ),
                    )
                }
                "K" -> {
                    record.requireFieldCount(3)
                    if (version < FIRST_VERSION_WITH_APPEARANCE) formatError("Older backups cannot encode appearance")
                    if (look != null) formatError("Duplicate appearance record")
                    // The look is a tolerant record of its own: a key from a later build, or one a
                    // later build dropped, falls back field by field the way the app's own settings
                    // do. Demanding an exact round trip would make every new appearance option
                    // break the restore of every v11 backup made before it.
                    val encodedAppearance = record.nextField().decodeField("appearance")
                    if (!encodedAppearance.startsWith(APPEARANCE_PREFIX)) formatError("Unknown appearance")
                    val appearance = decodeAppearance(encodedAppearance)
                    // Tolerant like the appearance: a header style from a later build falls back to
                    // the default instead of refusing the whole restore.
                    val style = pageHeaderStyleFromPersisted(record.nextField())
                    look = appearance to style
                }
                "T" -> {
                    record.requireFieldCount(6)
                    if (tracks.size >= MAX_TRACKS) formatError("Too many track references")
                    tracks += LocalBackupTrackReference(
                        originalId = record.nextField().decodeField("track id"),
                        title = record.nextField().decodeNullableField("track title"),
                        artist = record.nextField().decodeNullableField("track artist"),
                        album = record.nextField().decodeNullableField("track album"),
                        durationMs = record.nextField().decodeNullableLong("track duration"),
                    )
                }
                "P" -> {
                    val fixedFieldCount = if (version >= 10) 6 else if (version >= 2) 5 else 4
                    val membershipFields = record.fieldCount - fixedFieldCount
                    if (membershipFields < 0 || (version >= 10 && membershipFields % 2 != 0)) record.invalid()
                    val trackCount = if (version >= 10) membershipFields / 2 else membershipFields
                    if (playlists.size >= MAX_PLAYLISTS) formatError("Too many playlists")
                    if (trackCount > MAX_PLAYLIST_TRACKS - playlistTrackCount) {
                        formatError("Too many playlist entries")
                    }
                    playlistTrackCount += trackCount
                    val id = record.nextField().decodeField("playlist id")
                    val name = record.nextField().decodeField("playlist name")
                    val createdAtMs = record.nextField().parseLong("playlist creation time")
                    val includeInSmart = version >= 2 && record.nextField().parseBit("playlist SMART flag")
                    if (version >= 10 && record.nextField().parseInt("playlist size") != trackCount) record.invalid()
                    val manual = List(trackCount) { record.nextField().decodeField("playlist track id") }
                    val added = if (version >= 10) {
                        List(trackCount) { record.nextField().decodeField("playlist addition id") }
                    } else manual
                    playlists += LocalBackupPlaylist(id, name, createdAtMs, manual, includeInSmart, added)
                }
                "H" -> {
                    record.requireFieldCount(
                        when {
                            version >= 5 -> 13
                            version >= 3 -> 9
                            else -> 8
                        },
                    )
                    if (history.size >= MAX_HISTORY_EVENTS) formatError("Too many history events")
                    history += LocalBackupListenEvent(
                        trackReferenceId = record.nextField().decodeField("history track id"),
                        startedAtMs = record.nextField().parseLong("history start time"),
                        playedMs = record.nextField().parseLong("history played time"),
                        trackDurationMs = record.nextField().decodeNullableLong("history duration"),
                        completed = record.nextField().parseBit("history completed flag"),
                        skipped = record.nextField().parseBit("history skipped flag"),
                        shuffleMode = record.nextField().decodeNullableField("history shuffle mode"),
                        listenedMs = if (version >= 3) {
                            record.nextField().decodeNullableLong("history listened time")
                        } else {
                            null
                        },
                        origin = if (version >= 5) record.decodeListenOrigin() else null,
                    )
                }
                "Q" -> {
                    record.requireFieldCount(2)
                    if (searches.size >= MAX_SEARCHES) formatError("Too many recent searches")
                    searches += record.nextField().decodeField("recent search")
                }
                "D" -> {
                    record.requireFieldCount(2)
                    if (hiddenRecordCount >= MAX_TRACKS) formatError("Too many hidden track records")
                    hiddenRecordCount++
                    hidden += record.nextField().decodeField("hidden track id")
                }
                "X" -> {
                    record.requireFieldCount(2)
                    if (excludedTrackRecordCount >= MAX_TRACKS) {
                        formatError("Too many excluded track records")
                    }
                    excludedTrackRecordCount++
                    excludedTracks += record.nextField().decodeField("excluded track id")
                }
                "A" -> {
                    record.requireFieldCount(2)
                    if (excludedArtistRecordCount >= MAX_ARTIST_EXCLUSIONS) {
                        formatError("Too many artist exclusion records")
                    }
                    excludedArtistRecordCount++
                    excludedArtists += record.nextField().decodeField("excluded artist")
                }
                "F" -> {
                    record.requireFieldCount(2)
                    if (version < FIRST_VERSION_WITH_FAVORITES) {
                        formatError("Older backups cannot encode favourites")
                    }
                    if (favorites.size >= MAX_TRACKS) formatError("Too many favourites")
                    favorites += record.nextField().decodeField("favourite track id")
                }
                "N" -> {
                    if (version < FIRST_VERSION_WITH_FAVORITES) {
                        formatError("Older backups cannot encode duplicate decisions")
                    }
                    // A verdict keeps at least two recordings apart; one alone says nothing.
                    val memberCount = record.fieldCount - 1
                    if (memberCount < 2) record.invalid()
                    if (verdicts.size >= MAX_DUPLICATE_VERDICTS) formatError("Too many duplicate decisions")
                    if (memberCount > MAX_DUPLICATE_VERDICT_MEMBERS - verdictMemberCount) {
                        formatError("Too many duplicate decision entries")
                    }
                    verdictMemberCount += memberCount
                    verdicts += List(memberCount) { record.nextField().decodeField("duplicate decision track id") }
                }
                else -> formatError("Unknown backup record at line ${record.lineNumber}")
            }
        }

        val snapshot = LocalBackupSnapshot(
            formatVersion = version,
            createdAtMs = createdAtMs ?: formatError("Missing creation record"),
            settings = (settings ?: formatError("Missing settings record"))
                .copy(
                    pageLayout = pageLayout ?: PageLayout(),
                    artistVariety = artistVariety ?: DEFAULT_ARTIST_VARIETY,
                    songSort = sortOrders?.song ?: DEFAULT_SONG_SORT,
                    albumSort = sortOrders?.album ?: DEFAULT_ALBUM_SORT,
                    artistAlbumSort = sortOrders?.artistAlbum ?: DEFAULT_ARTIST_ALBUM_SORT,
                    groupSortOrders = groupSortOrders ?: GroupSortOrders(),
                    appearance = look?.first ?: Appearance(),
                    pageHeaderStyle = look?.second ?: PageHeaderStyle.DOTS,
                ),
            tracks = tracks,
            playlists = playlists,
            listeningHistory = history,
            recentSearches = searches,
            hiddenTrackReferenceIds = hidden,
            smartExcludedTrackReferenceIds = excludedTracks,
            smartExcludedArtists = excludedArtists,
            favoriteTrackReferenceIds = favorites,
            duplicateVerdicts = verdicts,
        )
        validate(snapshot)
        return snapshot
    }

    fun validate(snapshot: LocalBackupSnapshot) {
        if (snapshot.formatVersion !in LEGACY_FORMAT_VERSION..FORMAT_VERSION) {
            formatError("Unsupported backup version")
        }
        if (snapshot.createdAtMs < 0) formatError("Invalid backup creation time")
        if (snapshot.settings.smartQueueLength !in SMART_QUEUE_LENGTH_OPTIONS) {
            formatError("Unsupported SMART queue length")
        }
        if (snapshot.settings.crossfadeSeconds !in 0..MAX_CROSSFADE_SECONDS) {
            formatError("Unsupported crossfade duration")
        }
        if (snapshot.settings.artistVariety !in ARTIST_VARIETY_PENALTIES.indices) {
            formatError("Unsupported artist variety")
        }
        if (snapshot.formatVersion < 6 && snapshot.settings.artistVariety != DEFAULT_ARTIST_VARIETY) {
            formatError("Older backups cannot encode artist variety")
        }
        // An artist's page only offers these fields; anything else would be dropped by the setter.
        if (snapshot.formatVersion < 8 && snapshot.settings.groupSortOrders != GroupSortOrders()) {
            formatError("Older backups cannot encode group sort orders")
        }
        if (snapshot.settings.artistAlbumSort.sort !in ARTIST_ALBUM_SORTS) {
            formatError("Unsupported artist album sort")
        }
        // v6, not v7, is the floor although the record arrived with v7: decode() accepts a v6 file
        // that already carries it, so validate() must accept what decode() produces, and the encoder
        // writes the record for v6 too rather than dropping a choice validate() has allowed.
        if (snapshot.formatVersion < 6 &&
            (snapshot.settings.songSort != DEFAULT_SONG_SORT ||
                snapshot.settings.albumSort != DEFAULT_ALBUM_SORT ||
                snapshot.settings.artistAlbumSort != DEFAULT_ARTIST_ALBUM_SORT)
        ) {
            formatError("Older backups cannot encode sort orders")
        }
        // An older build refuses "custom" as an unknown colour mode, so only v11 may carry it.
        if (snapshot.formatVersion < FIRST_VERSION_WITH_APPEARANCE &&
            (snapshot.settings.trackColorMode == TrackColorMode.CUSTOM ||
                snapshot.settings.appearance != Appearance() ||
                snapshot.settings.pageHeaderStyle != PageHeaderStyle.DOTS)
        ) {
            formatError("Older backups cannot encode appearance")
        }
        // Only what the app's own reader gives back unchanged (palette accents, offered columns).
        if (decodeAppearance(encodeAppearance(snapshot.settings.appearance)) != snapshot.settings.appearance) {
            formatError("Unsupported appearance")
        }
        if (snapshot.settings.pageLayout != snapshot.settings.pageLayout.normalized()) {
            formatError("Invalid page layout")
        }
        if (snapshot.formatVersion < 4 && snapshot.settings.pageLayout != PageLayout()) {
            formatError("Legacy backups cannot encode page layouts")
        }
        if (
            snapshot.formatVersion < 3 &&
            (snapshot.settings.includeNoveltyMixes || snapshot.settings.normalizeVolume ||
                snapshot.settings.crossfadeSeconds != 0)
        ) {
            formatError("Legacy backups cannot encode playback processing settings")
        }
        if (snapshot.tracks.size > MAX_TRACKS) formatError("Too many track references")
        if (snapshot.playlists.size > MAX_PLAYLISTS) formatError("Too many playlists")
        if (
            snapshot.formatVersion == LEGACY_FORMAT_VERSION &&
            snapshot.playlists.any(LocalBackupPlaylist::includeInSmart)
        ) {
            formatError("Legacy backups cannot encode playlist SMART flags")
        }
        if (snapshot.playlists.sumOf { it.trackReferenceIds.size.toLong() } > MAX_PLAYLIST_TRACKS) {
            formatError("Too many playlist entries")
        }
        if (snapshot.listeningHistory.size > MAX_HISTORY_EVENTS) formatError("Too many history events")
        if (snapshot.recentSearches.size > MAX_SEARCHES) formatError("Too many recent searches")
        if (snapshot.smartExcludedArtists.size > MAX_ARTIST_EXCLUSIONS) {
            formatError("Too many artist exclusions")
        }

        val trackIds = snapshot.tracks.map(LocalBackupTrackReference::originalId)
        if (trackIds.any(String::isBlank) || trackIds.toSet().size != trackIds.size) {
            formatError("Track reference ids must be non-blank and unique")
        }
        snapshot.tracks.forEach { track ->
            if (track.durationMs != null && track.durationMs < 0) formatError("Invalid track duration")
        }
        val knownTracks = trackIds.toSet()
        val playlistIds = snapshot.playlists.map(LocalBackupPlaylist::id)
        if (playlistIds.any(String::isBlank) || playlistIds.toSet().size != playlistIds.size) {
            formatError("Playlist ids must be non-blank and unique")
        }
        snapshot.playlists.forEach { playlist ->
            if (playlist.createdAtMs < 0 || playlist.trackReferenceIds.any { it !in knownTracks }) {
                formatError("Invalid playlist record")
            }
            if (playlist.addedTrackReferenceIds.size != playlist.trackReferenceIds.size ||
                playlist.addedTrackReferenceIds.groupingBy { it }.eachCount() != playlist.trackReferenceIds.groupingBy { it }.eachCount()
            ) formatError("Playlist orders must contain the same occurrences")
            if (snapshot.formatVersion < 10 && playlist.addedTrackReferenceIds != playlist.trackReferenceIds) {
                formatError("Older backups cannot encode independent playlist orders")
            }
        }
        snapshot.listeningHistory.forEach { event ->
            if (event.trackReferenceId !in knownTracks || event.startedAtMs < 0 || event.playedMs < 0 ||
                (event.trackDurationMs != null && event.trackDurationMs < 0) ||
                (event.listenedMs != null && event.listenedMs < 0) ||
                // The same bounds the listening log enforces, so a restored event always reloads.
                (event.origin?.smartPlanPosition?.let { it < 1 } == true) ||
                event.origin?.parentId?.isEmpty() == true
            ) {
                formatError("Invalid listening event")
            }
        }
        if (snapshot.formatVersion < 5 && snapshot.listeningHistory.any { it.origin != null }) {
            formatError("Legacy backups cannot encode listening origins")
        }
        snapshot.recentSearches.forEach { search ->
            // The store refuses a query past these bounds, so a foreign document must be refused
            // here, before any section is applied, rather than mid-restore.
            if (search.isBlank() ||
                search.length > MAX_RECENT_SEARCH_QUERY_CHARS ||
                search.encodeToByteArray().size > MAX_RECENT_SEARCH_QUERY_BYTES
            ) {
                formatError("Invalid recent search")
            }
        }
        if (snapshot.hiddenTrackReferenceIds.any { it !in knownTracks } ||
            snapshot.smartExcludedTrackReferenceIds.any { it !in knownTracks }
        ) {
            formatError("Unknown track reference")
        }
        if (snapshot.smartExcludedArtists.any(String::isBlank)) formatError("Excluded artists cannot be blank")
        validateFavoritesAndDuplicateDecisions(snapshot, knownTracks)
    }

    private fun validateFavoritesAndDuplicateDecisions(snapshot: LocalBackupSnapshot, knownTracks: Set<String>) {
        val favorites = snapshot.favoriteTrackReferenceIds
        val verdicts = snapshot.duplicateVerdicts
        if (!snapshot.carriesFavoritesAndDuplicateDecisions && (favorites.isNotEmpty() || verdicts.isNotEmpty())) {
            formatError("Older backups cannot encode favourites or duplicate decisions")
        }
        if (favorites.size > MAX_TRACKS) formatError("Too many favourites")
        // The store keeps each heart once; a repeat would mean the order no longer says which is newer.
        if (favorites.any { it !in knownTracks } || favorites.toSet().size != favorites.size) {
            formatError("Invalid favourite record")
        }
        if (verdicts.size > MAX_DUPLICATE_VERDICTS) formatError("Too many duplicate decisions")
        if (verdicts.sumOf { it.size.toLong() } > MAX_DUPLICATE_VERDICT_MEMBERS) {
            formatError("Too many duplicate decision entries")
        }
        verdicts.forEach { verdict ->
            if (verdict.size < 2 || verdict.toSet().size != verdict.size || verdict.any { it !in knownTracks }) {
                formatError("Invalid duplicate decision record")
            }
        }
    }

    /**
     * The three list orders of one "O" record, introduced by v7. A file without that record — every
     * v6 file written before it existed — restores the defaults; an interim v6 file that carries it
     * keeps its orders.
     */
    private data class SortOrders(
        val song: SortChoice<SongSort>,
        val album: SortChoice<AlbumSort>,
        val artistAlbum: SortChoice<AlbumSort>,
    )

    /**
     * Reads one `field:direction` sort order, the form [encodeSortChoice] writes. The app decoder
     * repairs an unknown field or a missing direction instead of failing, so anything it had to
     * repair is not a value this format wrote and the record is rejected.
     */
    private fun <S : Enum<S>> String.decodeSortChoice(
        label: String,
        fromPersisted: (String?) -> SortChoice<S>,
    ): SortChoice<S> = fromPersisted(this).also {
        if (encodeSortChoice(it) != this) formatError("Unknown $label")
    }

    private fun StringBuilder.appendRecord(vararg fields: String) = appendRecord(fields.asList())

    private fun StringBuilder.appendRecord(fields: List<String>) {
        val recordLength = fields.sumOf { it.length.toLong() } + (fields.size - 1).coerceAtLeast(0)
        if (recordLength > MAX_RECORD_CHARS) formatError("Backup record exceeds the supported size")
        if (fields.any { it.length > MAX_ENCODED_FIELD_CHARS }) {
            formatError("Backup field exceeds the supported size")
        }
        fields.forEachIndexed { index, field ->
            if (index > 0) append('\t')
            append(field)
        }
        append('\n')
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: formatError("Unknown $label")

    private fun Boolean.toBit(): String = if (this) "1" else "0"

    private fun String.parseBit(label: String): Boolean = when (this) {
        "1" -> true
        "0" -> false
        else -> formatError("Invalid $label")
    }

    private fun String.parseLong(label: String): Long = toLongOrNull() ?: formatError("Invalid $label")
    private fun String.parseInt(label: String): Int = toIntOrNull() ?: formatError("Invalid $label")
    private fun Long?.encodeNullableLong(): String = this?.toString() ?: "n"
    private fun String.decodeNullableLong(label: String): Long? = if (this == "n") null else parseLong(label)

    private fun String?.encodeNullableField(): String = this?.encodeField() ?: "n"

    private fun String.encodeField(): String {
        if (length > MAX_ENCODED_FIELD_CHARS / 2) formatError("Backup field exceeds the supported size")
        val bytes = encodeToByteArray()
        if (bytes.size > (MAX_ENCODED_FIELD_CHARS - 1) / 2) {
            formatError("Backup field exceeds the supported size")
        }
        return buildString(1 + bytes.size * 2) {
            append('s')
            bytes.forEach { byte ->
                append(HEX[(byte.toInt() ushr 4) and 0x0f])
                append(HEX[byte.toInt() and 0x0f])
            }
        }
    }

    private fun String.decodeNullableField(label: String): String? =
        if (this == "n") null else decodeField(label)

    /** The four origin fields of a v5 history record; see [encode]. */
    private fun FieldCursor.decodeListenOrigin(): ListenOrigin? {
        val present = nextField().parseBit("history origin flag")
        val start = nextField().decodeNullableField("history start")
        val planPosition = nextField().decodeNullableLong("history plan position")
        val parentId = nextField().decodeNullableField("history parent")
        if (!present) {
            if (start != null || planPosition != null || parentId != null) invalid()
            return null
        }
        return ListenOrigin(
            // A start named by a newer build is unknown here, as in the listening log itself.
            start = start?.let { name -> ListenStart.entries.firstOrNull { it.name == name } },
            smartPlanPosition = planPosition?.let {
                if (it !in 1..Int.MAX_VALUE) invalid()
                it.toInt()
            },
            parentId = parentId,
        )
    }

    private fun String.decodeField(label: String): String {
        if (!startsWith('s')) formatError("Invalid $label")
        if (length > MAX_ENCODED_FIELD_CHARS) formatError("$label exceeds the supported size")
        val hex = drop(1)
        if (hex.length % 2 != 0) formatError("Invalid $label")
        return try {
            ByteArray(hex.length / 2) { index ->
                val high = hex[index * 2].digitToIntOrNull(16) ?: formatError("Invalid $label")
                val low = hex[index * 2 + 1].digitToIntOrNull(16) ?: formatError("Invalid $label")
                ((high shl 4) or low).toByte()
            }.decodeToString(throwOnInvalidSequence = true)
        } catch (failure: LocalBackupFormatException) {
            throw failure
        } catch (_: Throwable) {
            formatError("Invalid UTF-8 in $label")
        }
    }

    /** Scans record and field boundaries without materializing whole-file or per-line lists. */
    private class RecordCursor(private val source: String) {
        private var offset = 0
        private var nextLineNumber = 1

        fun next(): FieldCursor? {
            if (offset >= source.length) return null
            val start = offset
            val newline = source.indexOf('\n', start)
            val rawEnd = if (newline >= 0) newline else source.length
            val end = if (rawEnd > start && source[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
            val lineNumber = nextLineNumber++
            offset = if (newline >= 0) newline + 1 else source.length
            if (end == start) formatError("Blank record at line $lineNumber")
            if (end - start > MAX_RECORD_CHARS) {
                formatError("Backup record exceeds the supported size at line $lineNumber")
            }
            return FieldCursor(source, start, end, lineNumber)
        }
    }

    private class FieldCursor(
        private val source: String,
        private val start: Int,
        private val end: Int,
        val lineNumber: Int,
    ) {
        val fieldCount: Int = run {
            var count = 1
            var index = start
            while (index < end) {
                if (source[index] == '\t') count++
                index++
            }
            count
        }
        private var offset = start

        fun nextField(): String {
            if (offset > end) invalid()
            val tab = source.indexOf('\t', offset).let { if (it < 0 || it > end) end else it }
            if (tab - offset > MAX_ENCODED_FIELD_CHARS) {
                formatError("Backup field exceeds the supported size at line $lineNumber")
            }
            return source.substring(offset, tab).also { offset = tab + 1 }
        }

        fun requireFieldCount(expected: Int) {
            if (fieldCount != expected) invalid()
        }

        fun invalid(): Nothing = formatError("Invalid record at line $lineNumber")
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * Captures and restores user-owned local state. It does not read or write a cloud account and does
 * not include audio, artwork, embeddings, or the SMART model index.
 */
internal class LocalBackupService(
    private val settings: AppSettings,
    private val playlists: Playlists,
    private val history: ListeningHistory,
    private val recentSearches: RecentSearches,
    private val library: MusicLibrary,
    private val smartExclusions: SmartExclusions,
    private val favorites: Favorites,
) {
    /**
     * Builds the document for the picker. Encoding up to 64 Mi characters, and folding the library
     * and the listening log into the snapshot it encodes, is CPU work: the settings screen calls
     * this from its main-thread scope, so it runs on the default dispatcher. Every store read inside
     * moves to the store's own IO dispatcher.
     */
    suspend fun exportEncoded(): String = withContext(Dispatchers.Default) {
        LocalBackupCodec.encode(capture())
    }

    /**
     * Applies a picked document. Decoding up to 64 Mi characters, resolving its references against
     * the whole device library, and folding its sections is CPU work, so it runs on the default
     * dispatcher instead of the caller's main thread. The stores written here move to their own IO
     * dispatchers internally.
     */
    suspend fun importEncoded(
        encoded: String,
        mode: LocalBackupRestoreMode,
        sections: LocalBackupSections = LocalBackupSections(),
    ): LocalBackupRestoreReport = withContext(Dispatchers.Default) {
        restore(LocalBackupCodec.decode(encoded), mode, sections)
    }

    suspend fun capture(): LocalBackupSnapshot {
        val storedPlaylists = playlists.all()
        val recentEvents = history.recentEvents(MAX_CAPTURE_HISTORY + 1)
        require(recentEvents.size <= MAX_CAPTURE_HISTORY) { "Listening history is too large to export" }
        val chronologicalEvents = recentEvents.asReversed()
        val searches = recentSearches.recent(Int.MAX_VALUE)
        val hiddenIds = library.hiddenTrackIds()
        val exclusions = smartExclusions.load()
        val hearts = favorites.all()
        val verdicts = DuplicateDismissals.verdicts(settings.readDuplicateDismissalsPayload())
        val descriptors = library.allKnownTracks().associateBy { it.id.value }
        val referencedIds = linkedSetOf<String>().apply {
            storedPlaylists.forEach { addAll(it.trackIds) }
            chronologicalEvents.forEach { add(it.trackId.value) }
            hiddenIds.forEach { add(it.value) }
            exclusions.trackIds.forEach { add(it.value) }
            hearts.forEach { add(it.value) }
            verdicts.forEach { verdict -> verdict.forEach { add(it.value) } }
        }
        val references = referencedIds.map { id ->
            descriptors[id]?.toBackupReference() ?: LocalBackupTrackReference(
                originalId = id,
                title = null,
                artist = null,
                album = null,
                durationMs = null,
            )
        }
        return LocalBackupSnapshot(
            createdAtMs = epochMillis(),
            settings = LocalBackupSettings(
                themeMode = settings.themeMode.value,
                startPage = settings.startPage.value,
                trackColorMode = settings.trackColorMode.value,
                smartQueueLength = settings.smartQueueLength.value,
                saveListeningHistory = settings.saveListeningHistory.value,
                rememberSearches = settings.rememberSearches.value,
                includeNoveltyMixes = settings.includeNoveltyMixes.value,
                normalizeVolume = settings.normalizeVolume.value,
                crossfadeSeconds = settings.crossfadeSeconds.value,
                pageLayout = settings.pageLayout.value,
                artistVariety = settings.artistVariety.value,
                songSort = settings.songSort.value,
                albumSort = settings.albumSort.value,
                artistAlbumSort = settings.artistAlbumSort.value,
                groupSortOrders = settings.groupSortOrders.value,
                appearance = settings.appearance.value,
                pageHeaderStyle = settings.pageHeaderStyle.value,
            ),
            tracks = references,
            playlists = storedPlaylists.map { playlist ->
                LocalBackupPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    createdAtMs = playlist.createdAtMs,
                    trackReferenceIds = playlist.trackIds,
                    includeInSmart = playlist.includeInSmart,
                    addedTrackReferenceIds = playlist.addedTrackIds,
                )
            },
            listeningHistory = chronologicalEvents.map { it.toBackupEvent() },
            recentSearches = searches,
            hiddenTrackReferenceIds = hiddenIds.mapTo(linkedSetOf()) { it.value },
            smartExcludedTrackReferenceIds = exclusions.trackIds.mapTo(linkedSetOf()) { it.value },
            smartExcludedArtists = exclusions.artists,
            favoriteTrackReferenceIds = hearts.map { it.value },
            duplicateVerdicts = verdicts.map { verdict -> verdict.map { it.value } },
        ).also(LocalBackupCodec::validate)
    }

    suspend fun restore(
        snapshot: LocalBackupSnapshot,
        mode: LocalBackupRestoreMode,
        sections: LocalBackupSections = LocalBackupSections(),
    ): LocalBackupRestoreReport = restoreMutex.withLock { restoreLocked(snapshot, mode, sections) }

    private suspend fun restoreLocked(
        snapshot: LocalBackupSnapshot,
        mode: LocalBackupRestoreMode,
        sections: LocalBackupSections,
    ): LocalBackupRestoreReport {
        LocalBackupCodec.validate(snapshot)
        val currentTracks = library.allKnownTracks().distinctBy { it.id }
        val resolver = TrackReferenceResolver(currentTracks, snapshot.tracks)
        val resolved = snapshot.tracks.associate { reference ->
            reference.originalId to resolver.resolve(reference)
        }
        // Matching none of the references is not evidence that the tracks are gone: Android returns
        // no tracks without the media permission or after a failed provider query, and iOS skips the
        // device scan. Replacing stored sets with what such a scan can match would drop playlists,
        // history, hidden tracks, and SMART exclusions for good, so REPLACE is refused before any
        // section is applied. An empty library is the special case in which nothing can match.
        // MERGE still imports whatever the library can confirm. Favourites and duplicate decisions
        // count from v9 on; an older file leaves both alone, so it cannot empty them either.
        val newSections = snapshot.carriesFavoritesAndDuplicateDecisions
        val replacesTrackReferences = sections.playlists || sections.listeningHistory ||
            sections.hiddenTracks || sections.smartExclusions ||
            (newSections && (sections.favorites || sections.duplicateDecisions))
        if (mode == LocalBackupRestoreMode.REPLACE && replacesTrackReferences &&
            snapshot.tracks.isNotEmpty() && resolved.values.none { it != null }
        ) {
            throw LocalBackupRestoreException(
                completedSections = emptySet(),
                cause = LocalBackupLibraryUnavailableException(
                    "The device library matched none of the snapshot track references, " +
                        "so a replace restore was refused",
                ),
            )
        }
        val completed = linkedSetOf<LocalBackupSection>()
        var playlistsApplied = 0
        var historyApplied = 0
        var searchesApplied = 0
        var hiddenApplied = 0
        var smartTracksApplied = 0
        var smartArtistsApplied = 0
        var favoritesApplied = 0
        var duplicateDecisionsApplied = 0
        var duplicateDecisionsNotKept = 0

        try {
            if (sections.playlists) {
                val imported = snapshot.playlists.map { backup ->
                    Playlist(
                        id = backup.id,
                        name = backup.name,
                        createdAtMs = backup.createdAtMs,
                        trackIds = backup.trackReferenceIds.mapNotNull { resolved[it]?.value },
                        includeInSmart = backup.includeInSmart,
                        addedTrackIds = backup.addedTrackReferenceIds.mapNotNull { resolved[it]?.value },
                    )
                }
                val target = when (mode) {
                    LocalBackupRestoreMode.REPLACE -> imported
                    LocalBackupRestoreMode.MERGE -> mergePlaylists(playlists.all(), imported)
                }
                playlists.replaceAll(target)
                settings.setPlaylistSortOrders(settings.playlistSortOrders.value.normalized())
                playlistsApplied = imported.size
                completed += LocalBackupSection.PLAYLISTS
            }

            if (sections.favorites && newSections) {
                // Two references can resolve to one track; the first, newer one keeps its place.
                val imported = snapshot.favoriteTrackReferenceIds.mapNotNull { resolved[it] }.distinct()
                when (mode) {
                    LocalBackupRestoreMode.REPLACE -> favorites.replace(imported)
                    LocalBackupRestoreMode.MERGE -> mergeFavorites(imported)
                }
                favoritesApplied = imported.size
                completed += LocalBackupSection.FAVORITES
            }

            if (sections.listeningHistory) {
                val imported = snapshot.listeningHistory.mapNotNull { event ->
                    resolved[event.trackReferenceId]?.let { trackId -> event.toListenEvent(trackId) }
                }
                historyApplied = when (mode) {
                    LocalBackupRestoreMode.REPLACE -> {
                        history.replace(imported)
                        imported.size
                    }
                    // One locked read-fold-write: music keeps playing behind the settings screen, and
                    // a listen recorded while this restore runs is part of the log the merge writes.
                    // Like recentSearchesApplied below, the count describes the log that was written.
                    LocalBackupRestoreMode.MERGE -> history.merge(
                        imported = imported,
                        maxEvents = MAX_CAPTURE_HISTORY,
                        combine = ::mergeHistoryLog,
                    )
                }
                completed += LocalBackupSection.LISTENING_HISTORY
            }

            if (sections.recentSearches) {
                val target = when (mode) {
                    LocalBackupRestoreMode.REPLACE -> snapshot.recentSearches
                    LocalBackupRestoreMode.MERGE -> snapshot.recentSearches + recentSearches.recent(Int.MAX_VALUE)
                }
                recentSearches.replace(target)
                searchesApplied = recentSearches.recent(Int.MAX_VALUE).size
                completed += LocalBackupSection.RECENT_SEARCHES
            }

            if (sections.hiddenTracks) {
                val imported = snapshot.hiddenTrackReferenceIds.mapNotNullTo(linkedSetOf()) { resolved[it] }
                val target = when (mode) {
                    LocalBackupRestoreMode.REPLACE -> imported
                    LocalBackupRestoreMode.MERGE -> library.hiddenTrackIds() + imported
                }
                library.replaceHidden(target)
                hiddenApplied = imported.size
                completed += LocalBackupSection.HIDDEN_TRACKS
            }

            if (sections.smartExclusions) {
                val importedTracks = snapshot.smartExcludedTrackReferenceIds
                    .mapNotNullTo(linkedSetOf()) { resolved[it] }
                val importedArtists = snapshot.smartExcludedArtists
                    .mapNotNullTo(linkedSetOf()) { it.trim().takeIf(String::isNotEmpty) }
                val target = when (mode) {
                    LocalBackupRestoreMode.REPLACE -> SmartExclusionState(importedTracks, importedArtists)
                    LocalBackupRestoreMode.MERGE -> smartExclusions.load().let { existing ->
                        SmartExclusionState(
                            trackIds = existing.trackIds + importedTracks,
                            artists = existing.artists + importedArtists,
                        )
                    }
                }
                smartExclusions.replace(target)
                smartTracksApplied = importedTracks.size
                smartArtistsApplied = importedArtists.size
                completed += LocalBackupSection.SMART_EXCLUSIONS
            }

            if (sections.duplicateDecisions && newSections) {
                // A verdict keeps apart the tracks it names that are on this device; one left with a
                // single track says nothing, and its unmatched references are already counted.
                val imported = snapshot.duplicateVerdicts.mapNotNull { verdict ->
                    verdict.mapNotNull { resolved[it] }.distinct().takeIf { it.size >= 2 }
                }
                duplicateDecisionsApplied = DuplicateDismissals.restore(
                    settings = settings,
                    imported = imported,
                    replace = mode == LocalBackupRestoreMode.REPLACE,
                )
                duplicateDecisionsNotKept = imported.size - duplicateDecisionsApplied
                completed += LocalBackupSection.DUPLICATE_DECISIONS
            }

            if (sections.settings) {
                settings.setSaveListeningHistory(snapshot.settings.saveListeningHistory).getOrThrow()
                settings.setRememberSearches(snapshot.settings.rememberSearches).getOrThrow()
                settings.setThemeMode(snapshot.settings.themeMode)
                settings.setPageLayout(snapshot.settings.pageLayout)
                settings.setStartPage(snapshot.settings.pageLayout.resolveStartPage(snapshot.settings.startPage))
                settings.setTrackColorMode(snapshot.settings.trackColorMode)
                settings.setSmartQueueLength(snapshot.settings.smartQueueLength)
                settings.setIncludeNoveltyMixes(snapshot.settings.includeNoveltyMixes)
                settings.setNormalizeVolume(snapshot.settings.normalizeVolume)
                settings.setCrossfadeSeconds(snapshot.settings.crossfadeSeconds)
                settings.setArtistVariety(snapshot.settings.artistVariety)
                settings.setSongSort(snapshot.settings.songSort)
                settings.setAlbumSort(snapshot.settings.albumSort)
                settings.setArtistAlbumSort(snapshot.settings.artistAlbumSort)
                settings.setGroupSortOrders(snapshot.settings.groupSortOrders)
                // A backup from before v11 has no look at all: restoring it must not reset the
                // look chosen since to the defaults.
                if (snapshot.carriesAppearance) {
                    settings.setAppearance(snapshot.settings.appearance)
                    settings.setPageHeaderStyle(snapshot.settings.pageHeaderStyle)
                }
                settings.setPlaylistSortOrders(settings.playlistSortOrders.value.normalized())
                completed += LocalBackupSection.SETTINGS
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            throw LocalBackupRestoreException(completed.toSet(), failure)
        }

        val resolvedCount = resolved.values.count { it != null }
        return LocalBackupRestoreReport(
            completedSections = completed,
            resolvedTrackReferences = resolvedCount,
            unresolvedTrackReferences = resolved.size - resolvedCount,
            playlistsApplied = playlistsApplied,
            listeningEventsApplied = historyApplied,
            recentSearchesApplied = searchesApplied,
            hiddenTracksApplied = hiddenApplied,
            smartTrackExclusionsApplied = smartTracksApplied,
            smartArtistExclusionsApplied = smartArtistsApplied,
            favoritesApplied = favoritesApplied,
            duplicateDecisionsApplied = duplicateDecisionsApplied,
            duplicateDecisionsNotKept = duplicateDecisionsNotKept,
        )
    }

    /**
     * Merges restored hearts into the current ones as one compare-and-set ([mergedFavorites] says
     * how): a heart toggled while the restore runs is never overwritten, the merge simply starts
     * again from the list that heart produced.
     */
    private suspend fun mergeFavorites(imported: List<TrackId>) {
        repeat(MAX_FAVORITE_MERGE_ATTEMPTS) {
            val current = favorites.all()
            val merged = mergedFavorites(current, imported)
            if (merged == current || favorites.replaceIfUnchanged(current, merged)) return
        }
        error("Favourites kept changing while a backup was restored")
    }

    /**
     * One restore can contain hundreds of thousands of references. Normalize and index the device
     * library once so an id lookup is O(1), metadata-only uniqueness is O(1), and a duration match
     * only binary-searches the relevant normalized metadata group.
     */
    private class TrackReferenceResolver(
        tracks: List<TrackDescriptor>,
        references: List<LocalBackupTrackReference>,
    ) {
        private val normalizedById = references.associate { it.originalId to NormalizedReference(it) }
        private val requestedMetadataKeys = normalizedById.values.mapNotNullTo(hashSetOf()) { reference ->
            reference.title?.let { MetadataKey(it, reference.artist, reference.album) }
        }
        private val indexedTracks = tracks.map { IndexedTrack(it) }
        private val exactById = indexedTracks
            .filter { it.track.id.value in normalizedById }
            .associateBy { it.track.id.value }

        /**
         * True while the id space is confirmed: every described reference still fits the track that
         * holds its id. One described reference whose track is gone, or whose id now names a
         * different track, means the media database was rebuilt or an id was reused, and a reference
         * captured without any field then has nothing left to confirm it. A snapshot that describes
         * no reference at all has no evidence against its ids, so its bare ids are accepted.
         */
        private val idsPreserved: Boolean = normalizedById.all { (id, reference) ->
            if (!reference.hasIdentity) return@all true
            val candidate = exactById[id] ?: return@all false
            reference.isCompatibleWith(candidate)
        }
        private val metadataByKey: Map<MetadataKey, MetadataCandidates> = buildMap {
            val builders = mutableMapOf<MetadataKey, MutableList<IndexedTrack>>()
            indexedTracks.forEach { indexed ->
                val title = indexed.title ?: return@forEach
                fun add(artist: String?, album: String?) {
                    val key = MetadataKey(title, artist, album)
                    if (key in requestedMetadataKeys) {
                        builders.getOrPut(key) { mutableListOf() }.add(indexed)
                    }
                }
                add(null, null)
                if (indexed.artist != null) add(indexed.artist, null)
                if (indexed.album != null) add(null, indexed.album)
                if (indexed.artist != null && indexed.album != null) {
                    add(indexed.artist, indexed.album)
                }
            }
            builders.forEach { (key, candidates) -> put(key, MetadataCandidates(candidates)) }
        }

        fun resolve(reference: LocalBackupTrackReference): TrackId? {
            val normalized = normalizedById.getValue(reference.originalId)
            exactById[reference.originalId]?.let { exact ->
                // A described reference must fit the track that holds its id. A reference captured
                // without a single field is that id alone, and the backup promises the original id
                // for same-device restores, so it is accepted only while every described reference
                // confirms that the device still holds the ids the export saw.
                val matches = if (normalized.hasIdentity) {
                    normalized.isCompatibleWith(exact)
                } else {
                    idsPreserved
                }
                if (matches) return exact.track.id
            }
            val title = normalized.title ?: return null
            return metadataByKey[MetadataKey(title, normalized.artist, normalized.album)]
                ?.uniqueNear(normalized.durationMs)
        }
    }

    private data class IndexedTrack(
        val track: TrackDescriptor,
        val title: String? = track.title.normalizedBackupIdentity(),
        val artist: String? = track.artist.normalizedBackupIdentity(),
        val album: String? = track.album.normalizedBackupIdentity(),
    )

    private data class NormalizedReference(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
    ) {
        constructor(reference: LocalBackupTrackReference) : this(
            title = reference.title.normalizedBackupIdentity(),
            artist = reference.artist.normalizedBackupIdentity(),
            album = reference.album.normalizedBackupIdentity(),
            durationMs = reference.durationMs,
        )

        /**
         * True when at least one field can be compared with a track. A track whose tags MediaStore
         * could not read still has a duration, so only a reference captured without any field at all
         * is the bare id that [TrackReferenceResolver.resolve] has to judge on its own.
         */
        val hasIdentity: Boolean = title != null || artist != null || album != null || durationMs != null

        fun isCompatibleWith(track: IndexedTrack): Boolean =
            (title == null || title == track.title) &&
                (artist == null || artist == track.artist) &&
                (album == null || album == track.album) &&
                (durationMs == null ||
                    track.track.durationMs?.let { backupDurationsNear(durationMs, it) } == true)
    }

    private data class MetadataKey(
        val title: String,
        val artist: String?,
        val album: String?,
    )

    private class MetadataCandidates(private val tracks: List<IndexedTrack>) {
        private var durationSorted: List<IndexedTrack>? = null

        fun uniqueNear(durationMs: Long?): TrackId? {
            if (durationMs == null) return tracks.singleOrNull()?.track?.id
            val sorted = durationSorted ?: tracks
                .filter { it.track.durationMs != null }
                .sortedBy { it.track.durationMs }
                .also { durationSorted = it }
            val minimum = (durationMs - LOCAL_BACKUP_DURATION_TOLERANCE_MS).coerceAtLeast(0L)
            val maximum = if (durationMs > Long.MAX_VALUE - LOCAL_BACKUP_DURATION_TOLERANCE_MS) {
                Long.MAX_VALUE
            } else {
                durationMs + LOCAL_BACKUP_DURATION_TOLERANCE_MS
            }
            val first = sorted.lowerBound(minimum)
            val afterLast = sorted.upperBound(maximum)
            return if (afterLast - first == 1) sorted[first].track.id else null
        }

        private fun List<IndexedTrack>.lowerBound(target: Long): Int {
            var low = 0
            var high = size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (this[middle].track.durationMs!! < target) low = middle + 1 else high = middle
            }
            return low
        }

        private fun List<IndexedTrack>.upperBound(target: Long): Int {
            var low = 0
            var high = size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (this[middle].track.durationMs!! <= target) low = middle + 1 else high = middle
            }
            return low
        }
    }

    /**
     * The union of the stored log and the imported one, oldest first.
     *
     * A backup carries no sequence numbers, so a listen is identified by its own record
     * ([ListenEvent] equality) and re-importing a snapshot this device exported collapses every
     * duplicate instead of doubling that part of the history. [ListeningHistory.merge] hands over
     * the current log under its lock and enforces the size bound before anything is written, so
     * this function only has to keep the log ordered: a stable sort leaves listens recorded in the
     * same millisecond in local-before-imported order.
     */
    private fun mergeHistoryLog(
        existing: List<ListenEvent>,
        imported: List<ListenEvent>,
    ): List<ListenEvent> = (existing + imported).distinct().sortedBy(ListenEvent::startedAtMs)

    private fun mergePlaylists(existing: List<Playlist>, imported: List<Playlist>): List<Playlist> {
        val result = existing.toMutableList()
        imported.forEachIndexed { index, playlist ->
            val sameNameIndex = result.indexOfFirst { it.name.equals(playlist.name, ignoreCase = true) }
            if (sameNameIndex >= 0) {
                val current = result[sameNameIndex]
                val membership = mergePlaylistOccurrences(current.trackIds, playlist.trackIds)
                result[sameNameIndex] = current.copy(
                    trackIds = membership,
                    addedTrackIds = reconcilePlaylistAddedOrder(
                        mergePlaylistOccurrences(current.addedTrackIds, playlist.addedTrackIds), membership,
                    ),
                    includeInSmart = current.includeInSmart || playlist.includeInSmart,
                )
            } else {
                val usedIds = result.mapTo(hashSetOf(), Playlist::id)
                val uniqueId = if (playlist.id !in usedIds) playlist.id else {
                    generateSequence("${playlist.id}-imported-${index + 1}") { previous -> "$previous-1" }
                        .first { it !in usedIds }
                }
                result += playlist.copy(id = uniqueId)
            }
        }
        return result
    }

    private fun TrackDescriptor.toBackupReference(): LocalBackupTrackReference = LocalBackupTrackReference(
        originalId = id.value,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
    )

    private fun ListenEvent.toBackupEvent(): LocalBackupListenEvent = LocalBackupListenEvent(
        trackReferenceId = trackId.value,
        startedAtMs = startedAtMs,
        playedMs = playedMs,
        trackDurationMs = trackDurationMs,
        completed = completed,
        skipped = skipped,
        shuffleMode = shuffleMode,
        listenedMs = listenedMs,
        origin = origin,
    )

    private fun LocalBackupListenEvent.toListenEvent(trackId: TrackId): ListenEvent = ListenEvent(
        trackId = trackId,
        startedAtMs = startedAtMs,
        playedMs = playedMs,
        trackDurationMs = trackDurationMs,
        completed = completed,
        skipped = skipped,
        shuffleMode = shuffleMode,
        listenedMs = listenedMs,
        origin = origin,
    )

    private companion object {
        const val MAX_CAPTURE_HISTORY = 500_000

        /** Each attempt loses only to a heart edited in between, so a few always settle. */
        const val MAX_FAVORITE_MERGE_ATTEMPTS = 8

        /**
         * One restore at a time across the process. A restore now survives the screen that started
         * it, so the next screen can begin another while the first still applies sections; two
         * merges interleaving their read-modify-write passes would drop each other's sections. The
         * lock is per process, not per instance: every screen builds its own [LocalBackupService].
         */
        val restoreMutex = Mutex()
    }
}

/**
 * The favourites a MERGE restore leaves: the union of [current] and [restored], where every current
 * heart keeps its exact place and the restored hearts this device did not have follow them, in
 * the backup's newest-first order. The device's own hearts lead because they are at least as recent
 * as the file: a backup is a picture of the past, and the store keeps no heart times to interleave
 * by. A restored heart already on the device stays where the device has it.
 */
internal fun mergedFavorites(current: List<TrackId>, restored: List<TrackId>): List<TrackId> {
    val present = current.toHashSet()
    val added = restored.filter { present.add(it) }
    return if (added.isEmpty()) current else current + added
}

private const val LOCAL_BACKUP_DURATION_TOLERANCE_MS = 2_000L
private val LOCAL_BACKUP_IDENTITY_WHITESPACE = Regex("\\s+")

private fun String?.normalizedBackupIdentity(): String? = this
    ?.trim()
    ?.lowercase()
    ?.split(LOCAL_BACKUP_IDENTITY_WHITESPACE)
    ?.joinToString(" ")
    ?.takeIf(String::isNotEmpty)

private fun backupDurationsNear(first: Long, second: Long): Boolean =
    if (first >= second) second >= first - LOCAL_BACKUP_DURATION_TOLERANCE_MS
    else first >= second - LOCAL_BACKUP_DURATION_TOLERANCE_MS

private fun formatError(message: String): Nothing = throw LocalBackupFormatException(message)
