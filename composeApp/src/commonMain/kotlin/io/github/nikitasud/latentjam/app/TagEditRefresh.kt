/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.app.generated.resources.Res
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_done
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_left
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_undone
import io.github.nikitasud.latentjam.app.generated.resources.tag_recovery_waiting
import io.github.nikitasud.latentjam.app.generated.resources.tags_saved
import io.github.nikitasud.latentjam.app.generated.resources.tags_saved_partly
import io.github.nikitasud.latentjam.library.AlbumGroup
import io.github.nikitasud.latentjam.library.ArtistGroup
import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * Where an open album page lives after an edit: the album now holding most of the page's tracks.
 * A renamed album is followed. An album that lost only a few of its tracks to another stays.
 */
internal fun refreshedAlbum(catalog: LibraryCatalog, pageIds: Set<TrackId>): AlbumGroup? =
    catalog.albums.maxByOrNull { album -> album.tracks.count { it.id in pageIds } }
        ?.takeIf { album -> album.tracks.any { it.id in pageIds } }

/** As [refreshedAlbum], for an artist page. */
internal fun refreshedArtist(catalog: LibraryCatalog, pageIds: Set<TrackId>): ArtistGroup? =
    catalog.artists.maxByOrNull { artist -> artist.tracks.count { it.id in pageIds } }
        ?.takeIf { artist -> artist.tracks.any { it.id in pageIds } }

/** The same page with each track's fresh descriptor, order, sections and identity kept. */
internal fun CollectionSelection.withFreshTracks(byId: Map<TrackId, TrackDescriptor>): CollectionSelection = copy(
    tracks = tracks.map { byId[it.id] ?: it },
    sections = sections?.map { section -> section.copy(tracks = section.tracks.map { byId[it.id] ?: it }) },
)

/**
 * The library without files whose save stopped mid-replace (spec §5.4). Such a file holds neither
 * its old bytes nor its new ones, so it is not offered for playback until recovery finishes it.
 */
internal fun withoutFilesUnderRepair(
    tracks: List<TrackDescriptor>,
    repairing: Set<String>,
    keyOf: (TrackDescriptor) -> String?,
): List<TrackDescriptor> =
    if (repairing.isEmpty()) tracks else tracks.filterNot { track -> keyOf(track)?.let(repairing::contains) == true }

/** One line to tell the user about a save no editor was there to report. */
internal data class TagReportNotice(val kind: Kind, val count: Int, val total: Int = count) {
    enum class Kind { SAVED, SAVED_PARTLY, RECOVERED, UNDONE, LEFT_AS_FOUND, WAITING }
}

internal fun tagReportNotices(kind: TagWriteKind, result: TagSaveResult): List<TagReportNotice> {
    if (result.entries.isEmpty() || result.cancelled) return emptyList()
    if (kind == TagWriteKind.EDIT) {
        return listOf(
            if (result.notChanged.isEmpty()) {
                TagReportNotice(TagReportNotice.Kind.SAVED, result.savedCount)
            } else {
                TagReportNotice(TagReportNotice.Kind.SAVED_PARTLY, result.savedCount, result.entries.size)
            },
        )
    }
    val recovered = result.entries.count { it.status == FileWriteStatus.RECOVERED }
    val undone = result.entries.count { it.status == FileWriteStatus.RESTORED }
    val left = result.entries.count { it.status == FileWriteStatus.FOREIGN }
    val waiting = result.entries.size - recovered - undone - left
    return listOfNotNull(
        TagReportNotice(TagReportNotice.Kind.RECOVERED, recovered).takeIf { recovered > 0 },
        TagReportNotice(TagReportNotice.Kind.UNDONE, undone).takeIf { undone > 0 },
        TagReportNotice(TagReportNotice.Kind.LEFT_AS_FOUND, left).takeIf { left > 0 },
        TagReportNotice(TagReportNotice.Kind.WAITING, waiting).takeIf { waiting > 0 },
    )
}

internal suspend fun tagReportNoticeText(notice: TagReportNotice): String = when (notice.kind) {
    TagReportNotice.Kind.SAVED -> getPluralString(Res.plurals.tags_saved, notice.count, notice.count)
    TagReportNotice.Kind.SAVED_PARTLY -> getString(Res.string.tags_saved_partly, notice.count, notice.total)
    TagReportNotice.Kind.RECOVERED -> getPluralString(Res.plurals.tag_recovery_done, notice.count, notice.count)
    TagReportNotice.Kind.UNDONE -> getPluralString(Res.plurals.tag_recovery_undone, notice.count, notice.count)
    TagReportNotice.Kind.LEFT_AS_FOUND -> getPluralString(Res.plurals.tag_recovery_left, notice.count, notice.count)
    TagReportNotice.Kind.WAITING -> getPluralString(Res.plurals.tag_recovery_waiting, notice.count, notice.count)
}

/** Statuses after which the file's bytes differ from what the library last read. */
internal val TAG_FILE_CHANGED = setOf(FileWriteStatus.SAVED, FileWriteStatus.RECOVERED, FileWriteStatus.RESTORED)

/**
 * This track as a save of it began: the same track at [revision], the revision the library held
 * when Save was tapped. A reload during the save can move the live descriptor to the written file's
 * new revision, and SMART's carry-over must name the old one, which its audio analysis was made from.
 */
internal fun TrackDescriptor.asSavedFrom(revision: String?): TrackDescriptor = copy(sourceRevision = revision)

/** The keys of the files a finished save changed on storage. */
internal fun TagSaveResult.changedKeys(): Set<String> =
    entries.filter { it.status in TAG_FILE_CHANGED }.mapTo(HashSet()) { it.key }

/**
 * The tracks among [tracks] whose files are under [keys]. A report carries only keys, and a
 * recovered file may come back with no track the app held before, so tracks are found by key.
 */
internal fun tracksWithKeys(
    tracks: List<TrackDescriptor>,
    keys: Set<String>,
    keyOf: (TrackDescriptor) -> String?,
): List<TrackDescriptor> =
    if (keys.isEmpty()) emptyList() else tracks.filter { track -> keyOf(track)?.let(keys::contains) == true }

/**
 * The tracks among [saved] whose files now hold this save's new or removed cover: every file that
 * holds the edit. One that already held it (UNCHANGED) is included: its bytes did not change, but
 * the cover shown for it may have been its album's, and from now on it is its own. None when the
 * save kept the cover.
 */
internal fun TagSaveResult.coverSavedTracks(
    saved: List<TrackDescriptor>,
    keyOf: (TrackDescriptor) -> String?,
): List<TrackId> {
    if (cover == CoverEdit.Keep) return emptyList()
    val holding = entries.filter { it.saved }.mapTo(HashSet()) { it.key }
    return tracksWithKeys(saved, holding, keyOf).map { it.id }
}

/**
 * The tracks among [saved], as the library held them when the save began, whose files hold this
 * save's edits while it kept the cover (SAVED or UNCHANGED): their cover is byte for byte the one
 * they had. None when the save changed the cover. A recovered file is not one: its cover is the
 * recovery's, which verification did not pin.
 */
internal fun TagSaveResult.coverKeptTracks(
    saved: List<TrackDescriptor>,
    keyOf: (TrackDescriptor) -> String?,
): List<TrackDescriptor> {
    if (cover != CoverEdit.Keep) return emptyList()
    val holding = entries.filter { it.saved && it.status in COVER_PINNED }.mapTo(HashSet()) { it.key }
    return tracksWithKeys(saved, holding, keyOf)
}

/** Statuses after which the file holds exactly the save's edits, its pictures verified byte for byte. */
private val COVER_PINNED = setOf(FileWriteStatus.SAVED, FileWriteStatus.UNCHANGED)

/** Every file a finished save names, whatever became of it. */
internal fun TagSaveResult.keys(): Set<String> = entries.mapTo(HashSet()) { it.key }

/**
 * What finished saves leave to do outside the files: the files whose bytes changed, each edit's
 * saved cover with the tracks that now hold it, and the tracks whose cover a save kept. [edits]
 * pairs each edit's tracks with its result, [results] are all the saves. Where the library records
 * covers ([recordsCovers], Android), a cover saved only into files that already held it still has
 * its tracks recorded and refreshed, though no file changed; elsewhere such a save changes nothing.
 */
internal class TagSaveFollowUp(
    edits: List<Pair<List<TrackDescriptor>, TagSaveResult>>,
    results: List<TagSaveResult>,
    keyOf: (TrackDescriptor) -> String?,
    recordsCovers: Boolean,
) {
    val changedKeys: Set<String> = results.flatMapTo(HashSet()) { it.changedKeys() }
    val covers: List<Pair<List<TrackId>, CoverEdit>> = edits
        .map { (saved, result) -> result.coverSavedTracks(saved, keyOf) to result.cover }
        .filter { (ids, _) -> ids.isNotEmpty() }

    /** The tracks, as held before their saves, whose cover a save kept ([coverKeptTracks]). */
    val kept: List<TrackDescriptor> = edits.flatMap { (saved, result) -> result.coverKeptTracks(saved, keyOf) }
    private val coveredIds: Set<TrackId> = if (recordsCovers) covers.flatMapTo(HashSet()) { it.first } else emptySet()

    /** False when nothing changed: no file, and no cover the library records. */
    val needed: Boolean get() = changedKeys.isNotEmpty() || coveredIds.isNotEmpty()

    /**
     * Whether the library must be rescanned once the covers are recorded: a file changed, or
     * [recorded] (some `MusicLibrary.coverSaved` said what a scan shows changed).
     */
    fun rescanAfter(recorded: Boolean): Boolean = changedKeys.isNotEmpty() || recorded

    /** The tracks of [fresh] whose queued copies the follow-up refreshes. */
    fun refreshed(fresh: List<TrackDescriptor>, keyOf: (TrackDescriptor) -> String?): List<TrackDescriptor> =
        fresh.filter { track -> track.id in coveredIds || keyOf(track)?.let(changedKeys::contains) == true }
}
