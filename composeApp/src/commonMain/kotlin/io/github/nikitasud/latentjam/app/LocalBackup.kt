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
)

internal data class LocalBackupListenEvent(
    val trackReferenceId: String,
    val startedAtMs: Long,
    val playedMs: Long,
    val trackDurationMs: Long?,
    val completed: Boolean,
    val skipped: Boolean,
    val shuffleMode: String?,
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
    const val FORMAT_VERSION: Int = 1
    private const val HEADER = "LATENTJAM-LOCAL-BACKUP"
    private const val MAX_TEXT_CHARS = 64 * 1024 * 1024
    private const val MAX_TRACKS = 200_000
    private const val MAX_PLAYLISTS = 10_000
    private const val MAX_PLAYLIST_TRACKS = 200_000
    private const val MAX_HISTORY_EVENTS = 500_000
    private const val MAX_SEARCHES = 100
    private const val MAX_ARTIST_EXCLUSIONS = 100_000

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
                appendRecord(
                    listOf(
                        "P",
                        playlist.id.encodeField(),
                        playlist.name.encodeField(),
                        playlist.createdAtMs.toString(),
                    ) + playlist.trackReferenceIds.map { it.encodeField() },
                )
            }
            snapshot.listeningHistory.forEach { event ->
                appendRecord(
                    "H",
                    event.trackReferenceId.encodeField(),
                    event.startedAtMs.toString(),
                    event.playedMs.toString(),
                    event.trackDurationMs.encodeNullableLong(),
                    event.completed.toBit(),
                    event.skipped.toBit(),
                    event.shuffleMode.encodeNullableField(),
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
        val rawLines = encoded.split('\n').map { it.removeSuffix("\r") }
        val lines = if (rawLines.lastOrNull().isNullOrEmpty()) rawLines.dropLast(1) else rawLines
        if (lines.isEmpty()) formatError("Backup is empty")
        val header = lines.first().split('\t')
        if (header.size != 2 || header[0] != HEADER) formatError("Not a LatentJam local backup")
        val version = header[1].parseInt("format version")
        if (version != FORMAT_VERSION) formatError("Unsupported backup version: $version")

        var createdAtMs: Long? = null
        var settings: LocalBackupSettings? = null
        val tracks = mutableListOf<LocalBackupTrackReference>()
        val playlists = mutableListOf<LocalBackupPlaylist>()
        val history = mutableListOf<LocalBackupListenEvent>()
        val searches = mutableListOf<String>()
        val hidden = linkedSetOf<String>()
        val excludedTracks = linkedSetOf<String>()
        val excludedArtists = linkedSetOf<String>()

        lines.drop(1).forEachIndexed { index, line ->
            if (line.isEmpty()) formatError("Blank record at line ${index + 2}")
            val fields = line.split('\t')
            when (fields.firstOrNull()) {
                "C" -> {
                    fields.requireSize(2, index)
                    if (createdAtMs != null) formatError("Duplicate creation record")
                    createdAtMs = fields[1].parseLong("creation time")
                }
                "S" -> {
                    fields.requireSize(7, index)
                    if (settings != null) formatError("Duplicate settings record")
                    settings = LocalBackupSettings(
                        themeMode = enumValue<ThemeMode>(fields[1], "theme"),
                        startPage = startPageFromPersisted(fields[2]).also {
                            if (it.persistedValue != fields[2]) formatError("Unknown start page")
                        },
                        trackColorMode = trackColorModeFromPersisted(fields[3]).also {
                            if (it.persistedValue != fields[3]) formatError("Unknown track colour mode")
                        },
                        smartQueueLength = fields[4].parseInt("SMART queue length"),
                        saveListeningHistory = fields[5].parseBit("save listening history"),
                        rememberSearches = fields[6].parseBit("remember searches"),
                    )
                }
                "T" -> {
                    fields.requireSize(6, index)
                    tracks += LocalBackupTrackReference(
                        originalId = fields[1].decodeField("track id"),
                        title = fields[2].decodeNullableField("track title"),
                        artist = fields[3].decodeNullableField("track artist"),
                        album = fields[4].decodeNullableField("track album"),
                        durationMs = fields[5].decodeNullableLong("track duration"),
                    )
                }
                "P" -> {
                    if (fields.size < 4) formatError("Invalid playlist record at line ${index + 2}")
                    playlists += LocalBackupPlaylist(
                        id = fields[1].decodeField("playlist id"),
                        name = fields[2].decodeField("playlist name"),
                        createdAtMs = fields[3].parseLong("playlist creation time"),
                        trackReferenceIds = fields.drop(4).map { it.decodeField("playlist track id") },
                    )
                }
                "H" -> {
                    fields.requireSize(8, index)
                    history += LocalBackupListenEvent(
                        trackReferenceId = fields[1].decodeField("history track id"),
                        startedAtMs = fields[2].parseLong("history start time"),
                        playedMs = fields[3].parseLong("history played time"),
                        trackDurationMs = fields[4].decodeNullableLong("history duration"),
                        completed = fields[5].parseBit("history completed flag"),
                        skipped = fields[6].parseBit("history skipped flag"),
                        shuffleMode = fields[7].decodeNullableField("history shuffle mode"),
                    )
                }
                "Q" -> {
                    fields.requireSize(2, index)
                    searches += fields[1].decodeField("recent search")
                }
                "D" -> {
                    fields.requireSize(2, index)
                    hidden += fields[1].decodeField("hidden track id")
                }
                "X" -> {
                    fields.requireSize(2, index)
                    excludedTracks += fields[1].decodeField("excluded track id")
                }
                "A" -> {
                    fields.requireSize(2, index)
                    excludedArtists += fields[1].decodeField("excluded artist")
                }
                else -> formatError("Unknown backup record at line ${index + 2}")
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
        if (snapshot.formatVersion != FORMAT_VERSION) formatError("Unsupported backup version")
        if (snapshot.createdAtMs < 0) formatError("Invalid backup creation time")
        if (snapshot.settings.smartQueueLength !in SMART_QUEUE_LENGTH_OPTIONS) {
            formatError("Unsupported SMART queue length")
        }
        if (snapshot.tracks.size > MAX_TRACKS) formatError("Too many track references")
        if (snapshot.playlists.size > MAX_PLAYLISTS) formatError("Too many playlists")
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
                (event.trackDurationMs != null && event.trackDurationMs < 0)
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
        append(fields.joinToString("\t"))
        append('\n')
    }

    private fun List<String>.requireSize(expected: Int, zeroBasedRecordIndex: Int) {
        if (size != expected) formatError("Invalid record at line ${zeroBasedRecordIndex + 2}")
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

    private fun String.encodeField(): String = buildString(1 + length * 2) {
        append('s')
        encodeToByteArray().forEach { byte ->
            append(HEX[(byte.toInt() ushr 4) and 0x0f])
            append(HEX[byte.toInt() and 0x0f])
        }
    }

    private fun String.decodeNullableField(label: String): String? =
        if (this == "n") null else decodeField(label)

    private fun String.decodeField(label: String): String {
        if (!startsWith('s')) formatError("Invalid $label")
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
            ),
            tracks = references,
            playlists = storedPlaylists.map { playlist ->
                LocalBackupPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    createdAtMs = playlist.createdAtMs,
                    trackReferenceIds = playlist.trackIds,
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
        val resolved = snapshot.tracks.associate { reference ->
            reference.originalId to resolve(reference, currentTracks)
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

    private fun resolve(
        reference: LocalBackupTrackReference,
        tracks: List<TrackDescriptor>,
    ): TrackId? {
        val exact = tracks.firstOrNull { it.id.value == reference.originalId }
        if (exact != null && reference.isCompatibleWith(exact)) return exact.id
        if (reference.title.normalizedIdentity() == null) return null
        return tracks.filter { reference.isMetadataMatch(it) }.singleOrNull()?.id
    }

    private fun LocalBackupTrackReference.isCompatibleWith(track: TrackDescriptor): Boolean {
        val hasIdentity = title.normalizedIdentity() != null ||
            (artist.normalizedIdentity() != null && album.normalizedIdentity() != null)
        if (!hasIdentity) return false
        return listOf(
            title.normalizedIdentity() to track.title.normalizedIdentity(),
            artist.normalizedIdentity() to track.artist.normalizedIdentity(),
            album.normalizedIdentity() to track.album.normalizedIdentity(),
        ).all { (expected, actual) -> expected == null || expected == actual } &&
            (durationMs == null || track.durationMs?.let { durationNear(durationMs, it) } == true)
    }

    private fun LocalBackupTrackReference.isMetadataMatch(track: TrackDescriptor): Boolean {
        val expectedTitle = title.normalizedIdentity() ?: return false
        if (track.title.normalizedIdentity() != expectedTitle) return false
        artist.normalizedIdentity()?.let { if (track.artist.normalizedIdentity() != it) return false }
        album.normalizedIdentity()?.let { if (track.album.normalizedIdentity() != it) return false }
        durationMs?.let { expected ->
            val actual = track.durationMs ?: return false
            if (!durationNear(expected, actual)) return false
        }
        return true
    }

    private fun mergePlaylists(existing: List<Playlist>, imported: List<Playlist>): List<Playlist> {
        val result = existing.toMutableList()
        imported.forEachIndexed { index, playlist ->
            val sameNameIndex = result.indexOfFirst { it.name.equals(playlist.name, ignoreCase = true) }
            if (sameNameIndex >= 0) {
                val current = result[sameNameIndex]
                result[sameNameIndex] = current.copy(trackIds = (current.trackIds + playlist.trackIds).distinct())
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
    )

    private fun LocalBackupListenEvent.toListenEvent(trackId: TrackId): ListenEvent = ListenEvent(
        trackId = trackId,
        startedAtMs = startedAtMs,
        playedMs = playedMs,
        trackDurationMs = trackDurationMs,
        completed = completed,
        skipped = skipped,
        shuffleMode = shuffleMode,
    )

    private fun String?.normalizedIdentity(): String? = this
        ?.trim()
        ?.lowercase()
        ?.split(Regex("\\s+"))
        ?.joinToString(" ")
        ?.takeIf(String::isNotEmpty)

    private fun durationNear(first: Long, second: Long): Boolean =
        if (first >= second) first - second <= DURATION_TOLERANCE_MS
        else second - first <= DURATION_TOLERANCE_MS

    private companion object {
        const val DURATION_TOLERANCE_MS = 2_000L
        const val MAX_CAPTURE_HISTORY = 500_000
    }
}

private fun formatError(message: String): Nothing = throw LocalBackupFormatException(message)
