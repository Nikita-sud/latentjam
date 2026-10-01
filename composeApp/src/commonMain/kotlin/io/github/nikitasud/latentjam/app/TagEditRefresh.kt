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
