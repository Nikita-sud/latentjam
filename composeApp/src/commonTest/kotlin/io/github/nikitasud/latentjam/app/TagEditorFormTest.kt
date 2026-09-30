/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.CoverEdit
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TagFormat
import io.github.nikitasud.latentjam.library.tags.TagSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class TagEditorFormTest {

    private val snapshot = TagSnapshot(
        format = TagFormat.FLAC,
        version = "FLAC",
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumArtist = "Various Artists",
        genre = "Pop",
        year = "2001-05-03",
        trackNumber = 3,
        trackTotal = 12,
        discNumber = 1,
        lyrics = "la la",
    )
    private val baseline = TagEditorForm.of(snapshot)

    @Test
    fun theFormShowsWhatTheFileHolds() {
        assertEquals("Various Artists", baseline.albumArtist)
        assertEquals("2001-05-03", baseline.year)
        assertEquals("3", baseline.trackNumber)
        assertEquals("12", baseline.trackTotal)
        assertEquals("1", baseline.discNumber)
        assertEquals("", baseline.discTotal)
        assertEquals("la la", baseline.lyrics)
        assertFalse(baseline.hasChanges(baseline))
    }

    @Test
    fun onlyTheFieldsTheUserChangedAreEdited() {
        val edited = baseline.copy(title = "New song", trackTotal = "13")
        assertEquals(TagEdits(title = "New song", trackTotal = "13"), edited.edits(baseline))
    }

    @Test
    fun whitespaceAloneIsNotAnEdit() {
        val edited = baseline.copy(title = "  Song ", lyrics = "la la\n")
        assertTrue(edited.edits(baseline).isEmpty)
        assertFalse(edited.hasChanges(baseline))
    }

    @Test
    fun clearingAFieldRemovesIt() {
        val edited = baseline.copy(albumArtist = "", discNumber = " ")
        assertEquals(TagEdits(albumArtist = "", discNumber = ""), edited.edits(baseline))
    }

    @Test
    fun aNumberOutsideOneTo999IsCaughtBeforeSaving() {
        assertFalse(baseline.copy(trackNumber = "0").edits(baseline).numbersAreValid)
        assertTrue(baseline.copy(trackNumber = "999").edits(baseline).numbersAreValid)
    }

    @Test
    fun aCoverChoiceIsAChangeAndItsEditIsPassedThrough() {
        val replaced = baseline.copy(cover = CoverChoice.Replace("f08f8630-6120-4aa9-9580-973044632c42.jpg"))
        assertTrue(replaced.hasChanges(baseline))
        assertEquals(CoverEdit.Remove, baseline.copy(cover = CoverChoice.Remove).edits(baseline, CoverEdit.Remove).cover)
    }

    @Test
    fun theFormSurvivesBeingSaved() {
        val edited = baseline.copy(
            title = "A\tB\nC",
            lyrics = "line one\nline two",
            cover = CoverChoice.Replace("f08f8630-6120-4aa9-9580-973044632c42.png"),
        )
        assertEquals(edited, TagEditorForm.fromSaveable(edited.toSaveable()))
        assertEquals(baseline.copy(cover = CoverChoice.Remove), TagEditorForm.fromSaveable(baseline.copy(cover = CoverChoice.Remove).toSaveable()))
        assertEquals(null, TagEditorForm.fromSaveable(listOf("too", "short")))
    }

    @Test
    fun inputFiltersKeepWhatATagCanHold() {
        assertEquals("123", numberInput("1a2b34"))
        assertEquals("2001-05-03", yearInput("2001-05-03xyz9"))
    }
}
