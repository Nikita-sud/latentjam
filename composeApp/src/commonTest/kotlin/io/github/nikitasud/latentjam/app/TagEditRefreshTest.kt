/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.LibraryCatalog
import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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

    @Test
    fun aSavedCoverBelongsToEveryFileThatNowHoldsIt() {
        val tracks = listOf(track("1", "A"), track("2", "A"), track("3", "A"), track("4", "A"))
        val entries = listOf(
            TagSaveEntry("k1", FileWriteStatus.SAVED),
            TagSaveEntry("k2", FileWriteStatus.UNCHANGED),
            TagSaveEntry("k3", FileWriteStatus.REFUSED, problem = TagProblem.DAMAGED),
            TagSaveEntry("k4", FileWriteStatus.RECOVERED),
        )
        val replaced = TagSaveResult(entries, cover = CoverEdit.Replace(byteArrayOf(1), "image/png"))
        assertEquals(listOf("1", "2", "4"), replaced.coverSavedTracks(tracks, { it.audioUri }).map { it.value })
        val removed = TagSaveResult(entries, cover = CoverEdit.Remove)
        assertEquals(listOf("1", "2", "4"), removed.coverSavedTracks(tracks, { it.audioUri }).map { it.value })
        assertEquals(emptyList(), TagSaveResult(entries).coverSavedTracks(tracks, { it.audioUri }))
    }

    @Test
    fun aSaveResultCarriesItsReportsCover() {
        val cover = CoverEdit.Replace(byteArrayOf(1), "image/png")
        val report = TagWriteReport(TagWriteKind.EDIT, listOf(FileWriteResult("k1", FileWriteStatus.SAVED)), id = 7, cover = cover)
        assertSame(cover, TagSaveResult.of(report, readOnlyIsMusicLibrary = false).cover)
    }

    @Test
    fun aCoverSavedOnlyIntoFilesThatAlreadyHeldItIsStillRecordedAndRefreshed() {
        val tracks = listOf(track("1", "A"), track("2", "A"))
        val cover = CoverEdit.Replace(byteArrayOf(1), "image/png")
        val result = TagSaveResult(listOf(TagSaveEntry("k1", FileWriteStatus.UNCHANGED)), cover = cover)
        val followUp = TagSaveFollowUp(listOf(tracks to result), listOf(result), { it.audioUri }, recordsCovers = true)
        assertTrue(followUp.needed)
        assertEquals(listOf(listOf(TrackId("1")) to cover), followUp.covers)
        assertEquals(listOf("1"), followUp.refreshed(tracks, { it.audioUri }).map { it.id.value })
        // Rescanned only when recording the cover changed what a scan shows.
        assertTrue(followUp.rescanAfter(recorded = true))
        assertFalse(followUp.rescanAfter(recorded = false))
    }

    @Test
    fun aCoverSavedOnlyIntoFilesThatAlreadyHeldItChangesNothingWhereCoversAreNotRecorded() {
        val tracks = listOf(track("1", "A"), track("2", "A"))
        val cover = CoverEdit.Replace(byteArrayOf(1), "image/png")
        val result = TagSaveResult(listOf(TagSaveEntry("k1", FileWriteStatus.UNCHANGED)), cover = cover)
        val followUp = TagSaveFollowUp(listOf(tracks to result), listOf(result), { it.audioUri }, recordsCovers = false)
        assertFalse(followUp.needed)
        assertEquals(emptyList(), followUp.refreshed(tracks, { it.audioUri }))
        // A file that changed still needs the rescan everywhere.
        val written = TagSaveResult(listOf(TagSaveEntry("k1", FileWriteStatus.SAVED)), cover = cover)
        val changed = TagSaveFollowUp(listOf(tracks to written), listOf(written), { it.audioUri }, recordsCovers = false)
        assertTrue(changed.needed)
        assertTrue(changed.rescanAfter(recorded = false))
    }

    @Test
    fun aSaveThatKeptTheCoverNamesTheFilesThatHoldItAsTheyWereHeld() {
        val tracks = listOf(track("1", "A"), track("2", "A"), track("3", "A"), track("4", "A"), track("5", "A"))
            .map { it.copy(sourceRevision = "before-${it.id.value}") }
        val entries = listOf(
            TagSaveEntry("k1", FileWriteStatus.SAVED),
            TagSaveEntry("k2", FileWriteStatus.UNCHANGED),
            TagSaveEntry("k3", FileWriteStatus.REFUSED, problem = TagProblem.DAMAGED),
            TagSaveEntry("k4", FileWriteStatus.RECOVERED),
            TagSaveEntry("k5", FileWriteStatus.FAILED, problem = TagProblem.FAILED),
        )
        val keptCover = TagSaveResult(entries)
        assertEquals(listOf("1", "2"), keptCover.coverKeptTracks(tracks, { it.audioUri }).map { it.id.value })
        val followUp = TagSaveFollowUp(listOf(tracks to keptCover), listOf(keptCover), { it.audioUri }, recordsCovers = true)
        assertEquals(listOf("before-1", "before-2"), followUp.kept.map { it.sourceRevision })
        val replaced = TagSaveResult(entries, cover = CoverEdit.Replace(byteArrayOf(1), "image/png"))
        assertEquals(emptyList(), replaced.coverKeptTracks(tracks, { it.audioUri }))
        assertEquals(emptyList(), TagSaveResult(entries, cover = CoverEdit.Remove).coverKeptTracks(tracks, { it.audioUri }))
    }

    @Test
    fun aFollowUpRefreshesChangedFilesAndNothingWithoutAChangeOrACover() {
        val tracks = listOf(track("1", "A"), track("2", "A"), track("3", "A"))
        val written = TagSaveResult(listOf(TagSaveEntry("k1", FileWriteStatus.SAVED), TagSaveEntry("k2", FileWriteStatus.UNCHANGED)))
        val followUp = TagSaveFollowUp(listOf(tracks to written), listOf(written), { it.audioUri }, recordsCovers = true)
        assertTrue(followUp.needed)
        assertEquals(emptyList(), followUp.covers)
        assertEquals(listOf("1"), followUp.refreshed(tracks, { it.audioUri }).map { it.id.value })
        val same = TagSaveResult(listOf(TagSaveEntry("k1", FileWriteStatus.UNCHANGED)))
        assertFalse(TagSaveFollowUp(listOf(tracks to same), listOf(same), { it.audioUri }, recordsCovers = true).needed)
    }

    @Test
    fun aReportNamesEveryFileItsSaveTouched() {
        val result = TagSaveResult(
            listOf(TagSaveEntry("a", FileWriteStatus.SAVED), TagSaveEntry("b", FileWriteStatus.UNCHANGED), TagSaveEntry("c", FileWriteStatus.FAILED, problem = TagProblem.FAILED)),
        )
        assertEquals(setOf("a", "b", "c"), result.keys())
    }
}
