/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagFormat
import io.github.nikitasud.latentjam.library.tags.TagSnapshot
import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class BulkTagFormTest {

    private fun snapshot(artist: String?, album: String?, discTotal: Int? = null) =
        TagSnapshot(TagFormat.MP3, "ID3v2.3", artist = artist, album = album, discTotal = discTotal)

    private val baseline = BulkBaseline.of(
        listOf(snapshot("A", "Album", 2), snapshot("B", "Album", 2), snapshot("  A ", "Album")),
    )

    @Test
    fun sharedValuesShowAndDifferentValuesStayEmpty() {
        assertEquals(SharedValue.Same("Album"), baseline.shared[BulkField.ALBUM])
        assertEquals(SharedValue.Different, baseline.shared[BulkField.ARTIST])
        assertEquals(SharedValue.Different, baseline.shared[BulkField.DISC_TOTAL])
        assertEquals(SharedValue.Same(""), baseline.shared[BulkField.GENRE])
        assertEquals("Album", BulkTagForm().text(BulkField.ALBUM, baseline))
        assertEquals("", BulkTagForm().text(BulkField.ARTIST, baseline))
    }

    @Test
    fun anEmptyBoxNeverRemoves() {
        val cleared = BulkTagForm().typed(BulkField.ALBUM, "").typed(BulkField.ARTIST, "   ")
        assertTrue(cleared.edits(baseline).isEmpty)
        assertEquals(0, cleared.changedFieldCount(baseline))
    }

    @Test
    fun removingIsDeliberateAndUndoable() {
        val removed = BulkTagForm().remove(BulkField.ALBUM)
        assertTrue(removed.removed(BulkField.ALBUM))
        assertEquals(TagEdits(album = ""), removed.edits(baseline))
        assertTrue(removed.keep(BulkField.ALBUM).edits(baseline).isEmpty)
        // Typing into a removed field sets it instead.
        assertEquals(TagEdits(album = "New"), removed.typed(BulkField.ALBUM, "New").edits(baseline))
    }

    @Test
    fun typingTheSharedValueAgainIsNotAChange() {
        assertTrue(BulkTagForm().typed(BulkField.ALBUM, "Album ").edits(baseline).isEmpty)
    }

    @Test
    fun typingOverDifferentValuesSetsThemAll() {
        val form = BulkTagForm().typed(BulkField.ARTIST, "A").typed(BulkField.DISC_TOTAL, "2")
        assertEquals(TagEdits(artist = "A", discTotal = "2"), form.edits(baseline))
        assertEquals(2, form.changedFieldCount(baseline))
    }

    @Test
    fun theCoverCountsAsAField() {
        assertEquals(1, BulkTagForm(cover = CoverChoice.Remove).changedFieldCount(baseline))
    }

    @Test
    fun theFormSurvivesBeingSaved() {
        val form = BulkTagForm(cover = CoverChoice.Replace("f08f8630-6120-4aa9-9580-973044632c42.jpg"))
            .typed(BulkField.GENRE, "Jazz\tFusion")
            .remove(BulkField.YEAR)
        assertEquals(form, BulkTagForm.fromSaveable(form.toSaveable()))
        assertEquals(null, BulkTagForm.fromSaveable(listOf("keep", "NOT_A_FIELD", "x", "0")))
    }

    @Test
    fun notEditableFilesAreSetAsideWithTheirReason() {
        val tracks = listOf(TrackDescriptor(TrackId("a")), TrackDescriptor(TrackId("b")), TrackDescriptor(TrackId("c")))
        val ready = snapshot("A", "Album")
        val targets = bulkTargets(
            tracks,
            listOf(
                TagFileRead.Ready(ready),
                TagFileRead.NotEditable(TagProblem.PROTECTED),
                TagFileRead.Ready(ready),
            ),
        )
        assertEquals(listOf("a", "c"), targets.editable.map { it.first.id.value })
        assertEquals(listOf(TrackId("b") to TagProblem.PROTECTED), targets.notEditable.map { it.first.id to it.second })
    }

    @Test
    fun aFileIsNamedByItsTitleThenItsFileName() {
        assertEquals("Song", trackLabel(TrackDescriptor(TrackId("1"), title = "Song", fileName = "s.mp3"), "k"))
        assertEquals("s.mp3", trackLabel(TrackDescriptor(TrackId("1"), fileName = "s.mp3"), "k"))
        assertEquals("k", trackLabel(null, "k"))
    }
}
