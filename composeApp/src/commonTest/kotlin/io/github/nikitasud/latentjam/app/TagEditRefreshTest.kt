/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class TagEditRefreshTest {

    private fun track(id: String, album: String, artist: String = "Band") =
        TrackDescriptor(TrackId(id), title = "t$id", artist = artist, album = album, audioUri = "k$id")

    private fun ids(vararg values: String) = values.mapTo(HashSet()) { TrackId(it) }

    @Test
    fun anOpenAlbumFollowsItsTracksToTheirNewAlbum() {
        val catalog = LibraryCatalog.build(listOf(track("1", "Renamed"), track("2", "Renamed"), track("3", "Other")))
        assertEquals("Renamed", refreshedAlbum(catalog, ids("1", "2"))?.title)
    }

    @Test
    fun anOpenAlbumStaysWhenOnlySomeOfItsTracksMoved() {
        val catalog = LibraryCatalog.build(
            listOf(track("1", "Elsewhere"), track("2", "Old"), track("3", "Old"), track("4", "Old")),
        )
        assertEquals("Old", refreshedAlbum(catalog, ids("1", "2", "3", "4"))?.title)
    }

    @Test
    fun anOpenArtistPageFollowsTheRenamedArtist() {
        val catalog = LibraryCatalog.build(
            listOf(track("1", "A", artist = "New Name"), track("2", "B", artist = "New Name"), track("3", "C", artist = "Someone")),
        )
        assertEquals("New Name", refreshedArtist(catalog, ids("1", "2"))?.name)
    }

    @Test
    fun freshTracksKeepOrderAndSections() {
        val old = listOf(track("2", "A"), track("1", "A"))
        val selection = CollectionSelection(
            title = "Mix",
            subtitle = null,
            artworkUri = null,
            tracks = old,
            sections = listOf(CollectionSection("A", old)),
        )
        val fresh = mapOf(TrackId("1") to track("1", "A").copy(title = "new"))
        val refreshed = selection.withFreshTracks(fresh)
        assertEquals(listOf("t2", "new"), refreshed.tracks.map { it.title })
        assertEquals(listOf("t2", "new"), refreshed.sections!!.single().tracks.map { it.title })
        assertEquals(selection.routeId, refreshed.routeId)
    }

    @Test
    fun filesUnderRepairAreLeftOut() {
        val tracks = listOf(track("1", "A"), track("2", "A"))
        assertEquals(listOf("1"), withoutFilesUnderRepair(tracks, setOf("k2")) { it.audioUri }.map { it.id.value })
        assertTrue(withoutFilesUnderRepair(tracks, emptySet()) { it.audioUri } === tracks)
    }

    @Test
    fun aRecoveryReportSaysWhatWasFinishedUndoneAndStillWaits() {
        val result = TagSaveResult(
            listOf(
                TagSaveEntry("a", FileWriteStatus.RECOVERED),
                TagSaveEntry("b", FileWriteStatus.RESTORED, problem = TagProblem.UNDONE),
                TagSaveEntry("c", FileWriteStatus.MISSING, problem = TagProblem.MISSING),
                TagSaveEntry("d", FileWriteStatus.FOREIGN, problem = TagProblem.CHANGED_ELSEWHERE),
            ),
        )
        assertEquals(
            listOf(
                TagReportNotice(TagReportNotice.Kind.RECOVERED, 1),
                TagReportNotice(TagReportNotice.Kind.UNDONE, 1),
                TagReportNotice(TagReportNotice.Kind.LEFT_AS_FOUND, 1),
                TagReportNotice(TagReportNotice.Kind.WAITING, 1),
            ),
            tagReportNotices(TagWriteKind.RECOVER, result),
        )
    }

    @Test
    fun anEditReportSaysHowManyFilesWereSaved() {
        val all = TagSaveResult(listOf(TagSaveEntry("a", FileWriteStatus.SAVED), TagSaveEntry("b", FileWriteStatus.UNCHANGED)))
        assertEquals(listOf(TagReportNotice(TagReportNotice.Kind.SAVED, 2)), tagReportNotices(TagWriteKind.EDIT, all))
        val partly = all.copy(entries = all.entries + TagSaveEntry("c", FileWriteStatus.FAILED, problem = TagProblem.FAILED))
        assertEquals(listOf(TagReportNotice(TagReportNotice.Kind.SAVED_PARTLY, 2, 3)), tagReportNotices(TagWriteKind.EDIT, partly))
        val cancelled = TagSaveResult(listOf(TagSaveEntry("a", FileWriteStatus.CANCELLED, problem = TagProblem.CANCELLED)))
        assertEquals(emptyList(), tagReportNotices(TagWriteKind.EDIT, cancelled))
    }

    @Test
    fun onlyFilesWhoseBytesChangedCountAsChanged() {
        val result = TagSaveResult(
            listOf(
                TagSaveEntry("saved", FileWriteStatus.SAVED),
                TagSaveEntry("same", FileWriteStatus.UNCHANGED),
                TagSaveEntry("recovered", FileWriteStatus.RECOVERED),
                TagSaveEntry("restored", FileWriteStatus.RESTORED, problem = TagProblem.UNDONE),
                TagSaveEntry("foreign", FileWriteStatus.FOREIGN, problem = TagProblem.CHANGED_ELSEWHERE),
                TagSaveEntry("failed", FileWriteStatus.FAILED, problem = TagProblem.FAILED),
            ),
        )
        assertEquals(setOf("saved", "recovered", "restored"), result.changedKeys())
    }

    @Test
    fun aReportsTracksAreFoundByKey() {
        val tracks = listOf(track("1", "A"), track("2", "A"), track("3", "A").copy(audioUri = null))
        assertEquals(listOf("2"), tracksWithKeys(tracks, setOf("k2", "gone"), { it.audioUri }).map { it.id.value })
        assertEquals(emptyList(), tracksWithKeys(tracks, emptySet(), { it.audioUri }))
    }
}
