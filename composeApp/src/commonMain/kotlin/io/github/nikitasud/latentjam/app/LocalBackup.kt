/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.history.ListenEvent
import io.github.nikitasud.latentjam.history.ListeningHistory
import io.github.nikitasud.latentjam.history.RecentSearches
import io.github.nikitasud.latentjam.history.SmartExclusionState
import io.github.nikitasud.latentjam.history.SmartExclusions
import io.github.nikitasud.latentjam.history.epochMillis
import io.github.nikitasud.latentjam.library.MusicLibrary
import io.github.nikitasud.latentjam.library.Playlist
import io.github.nikitasud.latentjam.library.Playlists
import io.github.nikitasud.latentjam.playback.MAX_CROSSFADE_SECONDS
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.CancellationException

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
)

/**
 * A portable identity hint, never an audio locator.
 *
 * The original id makes same-device restores exact. Metadata permits a conservative unique match
 * after MediaStore or imported-file ids change. Audio paths, artwork paths, and model embeddings are
 * deliberately absent from backups.
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
)

internal enum class LocalBackupSection {
    PLAYLISTS,
    LISTENING_HISTORY,
    RECENT_SEARCHES,
    HIDDEN_TRACKS,
    SMART_EXCLUSIONS,
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
)

/** A section failed after [completedSections] had already been durably requested. */
internal class LocalBackupRestoreException(
    val completedSections: Set<LocalBackupSection>,
    cause: Throwable,
) : IllegalStateException("Local backup restore stopped after ${completedSections.size} sections", cause)

internal class LocalBackupFormatException(message: String) : IllegalArgumentException(message)

/**
 * Compact line codec designed for local document export.
 *
 * Every user string is UTF-8 hex, so tabs, newlines, Unicode, and control characters round-trip
 * without depending on a JSON library. The strict version/header, record limits, and reference
 * validation make an arbitrary document-picker input safe to reject before any app state changes.
 */
internal object LocalBackupCodec {
    const val FORMAT_VERSION: Int = 3
    private const val LEGACY_FORMAT_VERSION: Int = 1
    private const val HEADER = "LATENTJAM-LOCAL-BACKUP"
    private const val MAX_TEXT_CHARS = 64 * 1024 * 1024
    private const val MAX_TRACKS = 200_000
    private const val MAX_PLAYLISTS = 10_000
    private const val MAX_PLAYLIST_TRACKS = 200_000
    private const val MAX_HISTORY_EVENTS = 500_000
    private const val MAX_SEARCHES = 100
    private const val MAX_ARTIST_EXCLUSIONS = 100_000
    private const val MAX_ENCODED_FIELD_CHARS = 2 * 1024 * 1024 + 1
    private const val MAX_RECORD_CHARS = 16 * 1024 * 1024

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
                }
                appendRecord(
                    fixedFields + playlist.trackReferenceIds.map { it.encodeField() },
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
                    },
                )
            }
            snapshot.recentSearches.forEach { appendRecord("Q", it.encodeField()) }
            snapshot.hiddenTrackReferenceIds.sorted().forEach { appendRecord("D", it.encodeField()) }
            snapshot.smartExcludedTrackReferenceIds.sorted().forEach { appendRecord("X", it.encodeField()) }
            snapshot.smartExcludedArtists.sortedBy(String::lowercase).forEach {
                appendRecord("A", it.encodeField())
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
        val tracks = mutableListOf<LocalBackupTrackReference>()
        val playlists = mutableListOf<LocalBackupPlaylist>()
        val history = mutableListOf<LocalBackupListenEvent>()
        val searches = mutableListOf<String>()
        val hidden = linkedSetOf<String>()
        val excludedTracks = linkedSetOf<String>()
        val excludedArtists = linkedSetOf<String>()
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
                    val fixedFieldCount = if (version >= 2) 5 else 4
                    val trackCount = record.fieldCount - fixedFieldCount
                    if (trackCount < 0) record.invalid()
                    if (playlists.size >= MAX_PLAYLISTS) formatError("Too many playlists")
                    if (trackCount > MAX_PLAYLIST_TRACKS - playlistTrackCount) {
                        formatError("Too many playlist entries")
                    }
                    playlistTrackCount += trackCount
                    playlists += LocalBackupPlaylist(
                        id = record.nextField().decodeField("playlist id"),
                        name = record.nextField().decodeField("playlist name"),
                        createdAtMs = record.nextField().parseLong("playlist creation time"),
                        includeInSmart = if (version >= 2) {
                            record.nextField().parseBit("playlist SMART flag")
                        } else {
                            false
                        },
                        trackReferenceIds = buildList(trackCount) {
                            repeat(trackCount) {
                                add(record.nextField().decodeField("playlist track id"))
                            }
                        },
                    )
                }
                "H" -> {
                    record.requireFieldCount(if (version >= 3) 9 else 8)
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
                else -> formatError("Unknown backup record at line ${record.lineNumber}")
            }
        }

        val snapshot = LocalBackupSnapshot(
            formatVersion = version,
            createdAtMs = createdAtMs ?: formatError("Missing creation record"),
            settings = settings ?: formatError("Missing settings record"),
            tracks = tracks,
            playlists = playlists,
            listeningHistory = history,
            recentSearches = searches,
            hiddenTrackReferenceIds = hidden,
            smartExcludedTrackReferenceIds = excludedTracks,
            smartExcludedArtists = excludedArtists,
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
        }
        snapshot.listeningHistory.forEach { event ->
            if (event.trackReferenceId !in knownTracks || event.startedAtMs < 0 || event.playedMs < 0 ||
                (event.trackDurationMs != null && event.trackDurationMs < 0) ||
                (event.listenedMs != null && event.listenedMs < 0)
            ) {
                formatError("Invalid listening event")
            }
        }
        if (snapshot.recentSearches.any(String::isBlank)) formatError("Recent searches cannot be blank")
        if (snapshot.hiddenTrackReferenceIds.any { it !in knownTracks } ||
            snapshot.smartExcludedTrackReferenceIds.any { it !in knownTracks }
        ) {
            formatError("Unknown track reference")
        }
        if (snapshot.smartExcludedArtists.any(String::isBlank)) formatError("Excluded artists cannot be blank")
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
) {
    suspend fun exportEncoded(): String = LocalBackupCodec.encode(capture())

    suspend fun importEncoded(
        encoded: String,
        mode: LocalBackupRestoreMode,
        sections: LocalBackupSections = LocalBackupSections(),
    ): LocalBackupRestoreReport = restore(LocalBackupCodec.decode(encoded), mode, sections)

    suspend fun capture(): LocalBackupSnapshot {
        val storedPlaylists = playlists.all()
        val recentEvents = history.recentEvents(MAX_CAPTURE_HISTORY + 1)
        require(recentEvents.size <= MAX_CAPTURE_HISTORY) { "Listening history is too large to export" }
        val chronologicalEvents = recentEvents.asReversed()
        val searches = recentSearches.recent(Int.MAX_VALUE)
        val hiddenIds = library.hiddenTrackIds()
        val exclusions = smartExclusions.load()
        val descriptors = library.allKnownTracks().associateBy { it.id.value }
        val referencedIds = linkedSetOf<String>().apply {
            storedPlaylists.forEach { addAll(it.trackIds) }
            chronologicalEvents.forEach { add(it.trackId.value) }
            hiddenIds.forEach { add(it.value) }
            exclusions.trackIds.forEach { add(it.value) }
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
            ),
            tracks = references,
            playlists = storedPlaylists.map { playlist ->
                LocalBackupPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    createdAtMs = playlist.createdAtMs,
                    trackReferenceIds = playlist.trackIds,
                    includeInSmart = playlist.includeInSmart,
                )
            },
            listeningHistory = chronologicalEvents.map { it.toBackupEvent() },
            recentSearches = searches,
            hiddenTrackReferenceIds = hiddenIds.mapTo(linkedSetOf()) { it.value },
            smartExcludedTrackReferenceIds = exclusions.trackIds.mapTo(linkedSetOf()) { it.value },
            smartExcludedArtists = exclusions.artists,
        ).also(LocalBackupCodec::validate)
    }

    suspend fun restore(
        snapshot: LocalBackupSnapshot,
        mode: LocalBackupRestoreMode,
        sections: LocalBackupSections = LocalBackupSections(),
    ): LocalBackupRestoreReport {
        LocalBackupCodec.validate(snapshot)
        val currentTracks = library.allKnownTracks().distinctBy { it.id }
        val resolver = TrackReferenceResolver(currentTracks, snapshot.tracks)
        val resolved = snapshot.tracks.associate { reference ->
            reference.originalId to resolver.resolve(reference)
        }
        val completed = linkedSetOf<LocalBackupSection>()
        var playlistsApplied = 0
        var historyApplied = 0
        var searchesApplied = 0
        var hiddenApplied = 0
        var smartTracksApplied = 0
        var smartArtistsApplied = 0

        try {
            if (sections.playlists) {
                val imported = snapshot.playlists.map { backup ->
                    Playlist(
                        id = backup.id,
                        name = backup.name,
                        createdAtMs = backup.createdAtMs,
                        trackIds = backup.trackReferenceIds.mapNotNull { resolved[it]?.value }.distinct(),
                        includeInSmart = backup.includeInSmart,
                    )
                }
                val target = when (mode) {
                    LocalBackupRestoreMode.REPLACE -> imported
                    LocalBackupRestoreMode.MERGE -> mergePlaylists(playlists.all(), imported)
                }
                playlists.replaceAll(target)
                playlistsApplied = imported.size
                completed += LocalBackupSection.PLAYLISTS
            }

            if (sections.listeningHistory) {
                val imported = snapshot.listeningHistory.mapNotNull { event ->
                    resolved[event.trackReferenceId]?.let { trackId -> event.toListenEvent(trackId) }
                }
                val target = when (mode) {
                    LocalBackupRestoreMode.REPLACE -> imported
                    LocalBackupRestoreMode.MERGE -> {
                        val existing = history.recentEvents(MAX_CAPTURE_HISTORY + 1).asReversed()
                        require(existing.size <= MAX_CAPTURE_HISTORY) { "Listening history is too large to merge" }
                        (existing + imported).distinct().sortedBy(ListenEvent::startedAtMs).also {
                            require(it.size <= MAX_CAPTURE_HISTORY) { "Merged listening history is too large" }
                        }
                    }
                }
                history.replace(target)
                historyApplied = imported.size
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

            if (sections.settings) {
                settings.setSaveListeningHistory(snapshot.settings.saveListeningHistory).getOrThrow()
                settings.setRememberSearches(snapshot.settings.rememberSearches).getOrThrow()
                settings.setThemeMode(snapshot.settings.themeMode)
                settings.setStartPage(snapshot.settings.startPage)
                settings.setTrackColorMode(snapshot.settings.trackColorMode)
                settings.setSmartQueueLength(snapshot.settings.smartQueueLength)
                settings.setIncludeNoveltyMixes(snapshot.settings.includeNoveltyMixes)
                settings.setNormalizeVolume(snapshot.settings.normalizeVolume)
                settings.setCrossfadeSeconds(snapshot.settings.crossfadeSeconds)
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
        )
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
                if (normalized.isCompatibleWith(exact)) return exact.track.id
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

        fun isCompatibleWith(track: IndexedTrack): Boolean {
            val hasIdentity = title != null || (artist != null && album != null)
            if (!hasIdentity) return false
            return (title == null || title == track.title) &&
                (artist == null || artist == track.artist) &&
                (album == null || album == track.album) &&
                (durationMs == null ||
                    track.track.durationMs?.let { backupDurationsNear(durationMs, it) } == true)
        }
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

    private fun mergePlaylists(existing: List<Playlist>, imported: List<Playlist>): List<Playlist> {
        val result = existing.toMutableList()
        imported.forEachIndexed { index, playlist ->
            val sameNameIndex = result.indexOfFirst { it.name.equals(playlist.name, ignoreCase = true) }
            if (sameNameIndex >= 0) {
                val current = result[sameNameIndex]
                result[sameNameIndex] = current.copy(
                    trackIds = (current.trackIds + playlist.trackIds).distinct(),
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
    )

    private companion object {
        const val MAX_CAPTURE_HISTORY = 500_000
    }
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
