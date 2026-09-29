/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class TagModelTest {

    private val base = TagSnapshot(
        format = TagFormat.FLAC,
        version = "FLAC",
        title = "Old",
        artist = "Band",
        album = "Record",
        albumArtist = "Band",
        genre = "Rock",
        year = "2001-05-03",
        trackNumber = 3,
        trackTotal = 12,
        discNumber = 1,
        discTotal = 2,
        lyrics = "la la",
        cover = CoverInfo("image/jpeg", 10, 99L),
        otherPictures = 1,
        pictures = listOf(55L, 99L),
        artists = listOf("A", "B"),
    )

    @Test
    fun emptyEditsAreEmpty() {
        assertTrue(TagEdits().isEmpty)
    }

    @Test
    fun anyFieldMakesEditsNonEmpty() {
        assertFalse(TagEdits(albumArtist = "x").isEmpty)
        assertFalse(TagEdits(discTotal = "").isEmpty)
        assertFalse(TagEdits(lyrics = "").isEmpty)
        assertFalse(TagEdits(cover = CoverEdit.Remove).isEmpty)
    }

    @Test
    fun numbersMustBePlainPositiveDigits() {
        assertTrue(TagEdits(trackNumber = "3", trackTotal = "", discNumber = "9999").numbersAreValid)
        assertFalse(TagEdits(trackNumber = "0").numbersAreValid)
        assertFalse(TagEdits(trackNumber = "3/12").numbersAreValid)
        assertFalse(TagEdits(discTotal = "12345").numbersAreValid)
        assertFalse(TagEdits(discNumber = "a").numbersAreValid)
        assertFalse(TagEdits(trackTotal = " 3").numbersAreValid)
    }

    @Test
    fun expectedAfterAppliesSetRemoveAndKeep() {
        val after = base.expectedAfter(TagEdits(title = "New", album = "", trackNumber = "4", lyrics = ""))
        assertEquals("New", after.title)
        assertNull(after.album)
        assertEquals(4, after.trackNumber)
        assertEquals(12, after.trackTotal)
        assertNull(after.lyrics)
        assertEquals("Band", after.artist)
        assertEquals("2001-05-03", after.year)
        assertEquals(base.cover, after.cover)
        assertEquals(1, after.otherPictures)
    }

    @Test
    fun expectedAfterTrimsLyricsAndTreatsBlankAsRemoval() {
        assertEquals("la la la", base.expectedAfter(TagEdits(lyrics = "\nla la la  \n")).lyrics)
        assertNull(base.expectedAfter(TagEdits(lyrics = "  \n")).lyrics)
    }

    @Test
    fun expectedAfterKeepsLoneTotalsOutsideId3() {
        val after = base.expectedAfter(TagEdits(trackNumber = ""))
        assertNull(after.trackNumber)
        assertEquals(12, after.trackTotal)
    }

    @Test
    fun expectedAfterDropsTotalsWithoutNumberOnId3() {
        val id3 = base.copy(format = TagFormat.MP3, version = "ID3v2.4")
        val after = id3.expectedAfter(TagEdits(trackNumber = "", discNumber = ""))
        assertNull(after.trackNumber)
        assertNull(after.trackTotal)
        assertNull(after.discNumber)
        assertNull(after.discTotal)
    }

    @Test
    fun expectedAfterNarrowsYearOnId3v23Only() {
        val v23 = base.copy(format = TagFormat.MP3, version = "ID3v2.3")
        assertEquals("2004", v23.expectedAfter(TagEdits(year = "2004-01-02")).year)
        val v24 = base.copy(format = TagFormat.MP3, version = "ID3v2.4")
        assertEquals("2004-01-02", v24.expectedAfter(TagEdits(year = "2004-01-02")).year)
        assertEquals("2004-01-02", base.expectedAfter(TagEdits(year = "2004-01-02")).year)
    }

    @Test
    fun anUntaggedMp3NarrowsAFullDateLikeId3v23() {
        // The first tag an untagged MP3 gets is ID3v2.3, whose TYER holds four characters.
        val none = TagSnapshot(TagFormat.MP3, "none")
        assertEquals("2004", none.expectedAfter(TagEdits(year = "2004-01-02")).year)
    }

    @Test
    fun normalizedStripsNulFromEveryTextFieldAndTrimsLyrics() {
        val edits = TagEdits(
            title = "Ti\u0000tle", artist = "A\u0000", album = "\u0000Al", genre = "Ro\u0000ck", year = "20\u000001",
            albumArtist = "V\u0000A", lyrics = "  la\u0000 la \n", trackNumber = "3",
        )
        assertEquals(
            TagEdits(
                title = "Title", artist = "A", album = "Al", genre = "Rock", year = "2001",
                albumArtist = "VA", lyrics = "la la", trackNumber = "3",
            ),
            edits.normalized(),
        )
        // A value that was nothing but NUL becomes a removal, exactly as a writer treats it.
        assertEquals("", TagEdits(title = "\u0000").normalized().title)
    }

    @Test
    fun expectedAfterNormalizesTheEditsItself() {
        val after = base.expectedAfter(TagEdits(title = "Ne\u0000w", artist = "X;\u0000 Y"))
        assertEquals("New", after.title)
        assertEquals("X; Y", after.artist)
        assertEquals(listOf("X", "Y"), after.artists)
        assertNull(base.expectedAfter(TagEdits(album = "\u0000")).album)
    }

    @Test
    fun expectedAfterRewritesCreditedArtistsOnlyWhenTheFileHasThem() {
        assertEquals(listOf("X", "Y"), base.expectedAfter(TagEdits(artist = "X; Y")).artists)
        assertEquals(emptyList(), base.expectedAfter(TagEdits(artist = "Solo")).artists)
        assertEquals(emptyList(), base.expectedAfter(TagEdits(artist = "")).artists)
        val none = base.copy(artists = emptyList())
        assertEquals(emptyList(), none.expectedAfter(TagEdits(artist = "X; Y")).artists)
        assertEquals(listOf("A", "B"), base.expectedAfter(TagEdits(title = "t")).artists)
    }

    @Test
    fun expectedAfterReplacesAndRemovesTheCover() {
        val bytes = byteArrayOf(1, 2, 3)
        val replaced = base.expectedAfter(TagEdits(cover = CoverEdit.Replace(bytes, "image/png")))
        assertEquals(CoverInfo.of(bytes, "image/png"), replaced.cover)
        assertEquals(1, replaced.otherPictures)
        assertEquals(listOf(55L, Crc32.of(bytes)).sorted(), replaced.pictures)
        val removed = base.expectedAfter(TagEdits(cover = CoverEdit.Remove))
        assertNull(removed.cover)
        assertEquals(listOf(55L), removed.pictures)
        val added = base.copy(cover = null, pictures = listOf(55L)).expectedAfter(TagEdits(cover = CoverEdit.Replace(bytes, "image/png")))
        assertEquals(listOf(55L, Crc32.of(bytes)).sorted(), added.pictures)
    }

    @Test
    fun removingTheCoverPromotesTheNextPicture() {
        val next = CoverInfo("image/png", 5, 7L)
        val withNext = base.copy(nextCover = next)
        val after = withNext.expectedAfter(TagEdits(cover = CoverEdit.Remove))
        assertEquals(next, after.cover)
        assertEquals(0, after.otherPictures)
        assertNull(after.nextCover)
        val noCover = base.copy(cover = null)
        assertEquals(1, noCover.expectedAfter(TagEdits(cover = CoverEdit.Remove)).otherPictures)
    }

    @Test
    fun id3RefusalsMapOneToOne() {
        assertEquals(TagRefusal.ID3_UNSYNCHRONISED, TagRefusal.of(Id3Refusal.UNSYNCHRONISED))
        assertEquals(TagRefusal.TRUNCATED, TagRefusal.of(Id3Refusal.TRUNCATED))
        assertEquals(Id3Refusal.entries.size, Id3Refusal.entries.map { TagRefusal.of(it) }.toSet().size)
    }

    @Test
    fun crc32MatchesTheStandardCheckValue() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
    }
}
