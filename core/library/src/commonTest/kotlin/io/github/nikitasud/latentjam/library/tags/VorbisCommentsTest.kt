/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class VorbisCommentsTest {

    private fun entries(vararg pairs: Pair<String, String>) = pairs.map { VorbisEntry.of(it.first, it.second) }

    private fun keys(list: List<VorbisEntry>) = list.map { it.raw.decodeToString() }

    @Test
    fun blockRoundTripsByteExactlyEvenWithInvalidUtf8() {
        val odd = VorbisEntry("COMMENT=".encodeToByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41))
        val block = VorbisComments("vendor ✓".encodeToByteArray(), entries("TITLE" to "Заголовок") + odd)
        val encoded = block.encode()
        val (decoded, end) = assertNotNull(VorbisComments.decode(byteArrayOf(9, 9) + encoded, 2))
        assertEquals(encoded.size + 2, end)
        assertContentEquals(encoded, decoded.encode())
        assertEquals("Заголовок", decoded.entries[0].value)
    }

    @Test
    fun decodeRejectsTruncatedAndMalformedBlocks() {
        val encoded = VorbisComments("v".encodeToByteArray(), entries("TITLE" to "x")).encode()
        assertNull(VorbisComments.decode(encoded.copyOf(encoded.size - 1), 0))
        val noEquals = VorbisComments("v".encodeToByteArray(), listOf(VorbisEntry("TITLE".encodeToByteArray()))).encode()
        assertNull(VorbisComments.decode(noEquals, 0))
    }

    @Test
    fun readMapsFieldsAliasesAndEmbeddedTotals() {
        val values = VorbisFields.read(
            entries(
                "title" to "Song",
                "ARTIST" to "A",
                "ARTIST" to "B",
                "ALBUM ARTIST" to "Various",
                "GENRE" to "Rock",
                "GENRE" to "Pop",
                "YEAR" to "1999",
                "TRACKNUMBER" to "3/12",
                "DISCNUMBER" to "1",
                "TOTALDISCS" to "2",
                "UNSYNCEDLYRICS" to "  words \n",
                "ARTISTS" to "A",
                "ARTISTS" to "B",
            ),
        )
        assertEquals("Song", values.title)
        assertEquals("A; B", values.artist)
        assertEquals("Various", values.albumArtist)
        assertEquals("Rock; Pop", values.genre)
        assertEquals("1999", values.year)
        assertEquals(3, values.trackNumber)
        assertEquals(12, values.trackTotal)
        assertEquals(1, values.discNumber)
        assertEquals(2, values.discTotal)
        assertEquals("words", values.lyrics)
        assertEquals(listOf("A", "B"), values.artists)
    }

    @Test
    fun applySetsRemovesAndKeepsPositionAndUnmanagedEntries() {
        val before = entries("TITLE" to "Old", "REPLAYGAIN_TRACK_GAIN" to "-6.2 dB", "ALBUM" to "Rec")
        val after = VorbisFields.apply(before, TagEdits(title = "New", album = "", genre = "Jazz"))
        assertEquals(listOf("TITLE=New", "REPLAYGAIN_TRACK_GAIN=-6.2 dB", "GENRE=Jazz"), keys(after))
        assertContentEquals(before[1].raw, after[1].raw)
    }

    @Test
    fun applyCollapsesAliasesIntoTheCanonicalField() {
        val before = entries("ALBUM ARTIST" to "x", "ALBUMARTIST" to "y", "TOTALTRACKS" to "9")
        val after = VorbisFields.apply(before, TagEdits(albumArtist = "Various", trackTotal = "10"))
        assertEquals(listOf("ALBUMARTIST=Various", "TRACKTOTAL=10"), keys(after))
    }

    @Test
    fun genreAndArtistAreWrittenExactlyAsTyped() {
        val after = VorbisFields.apply(entries("GENRE" to "a", "GENRE" to "b"), TagEdits(genre = "Rock;Pop"))
        assertEquals(listOf("GENRE=Rock;Pop"), keys(after))
        assertEquals("Rock;Pop", VorbisFields.read(after).genre)
    }

    @Test
    fun creditedArtistsAreRewrittenOnlyWhenTheFileHasThem() {
        val with = entries("ARTIST" to "A; B", "ARTISTS" to "A", "ARTISTS" to "B")
        assertEquals(
            listOf("ARTIST=X; Y", "ARTISTS=X", "ARTISTS=Y"),
            keys(VorbisFields.apply(with, TagEdits(artist = "X; Y"))),
        )
        assertEquals(listOf("ARTIST=Solo"), keys(VorbisFields.apply(with, TagEdits(artist = "Solo"))))
        val without = entries("ARTIST" to "A")
        assertEquals(listOf("ARTIST=X; Y"), keys(VorbisFields.apply(without, TagEdits(artist = "X; Y"))))
    }

    @Test
    fun numberEditKeepsATotalThatWasEmbeddedInThePair() {
        val after = VorbisFields.apply(entries("TRACKNUMBER" to "3/12"), TagEdits(trackNumber = "4"))
        assertEquals(listOf("TRACKNUMBER=4", "TRACKTOTAL=12"), keys(after))
    }

    @Test
    fun totalEditSplitsAnEmbeddedPair() {
        val after = VorbisFields.apply(entries("TRACKNUMBER" to "3/12"), TagEdits(trackTotal = "10"))
        assertEquals(listOf("TRACKNUMBER=3", "TRACKTOTAL=10"), keys(after))
        val read = VorbisFields.read(after)
        assertEquals(3, read.trackNumber)
        assertEquals(10, read.trackTotal)
    }

    @Test
    fun restatingAValueKeepsTheOriginalEntry() {
        val before = entries("title" to "Song", "ALBUM" to "Rec")
        val after = VorbisFields.apply(before, TagEdits(title = "Song"))
        assertContentEquals(before[0].raw, after[0].raw)
        assertEquals(keys(before), keys(after))
    }

    @Test
    fun longNonLatinLyricsRoundTrip() {
        val lyrics = ("Ночь, улица, фонарь, аптека. 夜の街を歩く。\n").repeat(400).trim()
        val after = VorbisFields.apply(emptyList(), TagEdits(lyrics = lyrics))
        val block = VorbisComments("v".encodeToByteArray(), after).encode()
        val (decoded, _) = assertNotNull(VorbisComments.decode(block, 0))
        assertEquals(lyrics, VorbisFields.read(decoded.entries).lyrics)
    }

    @Test
    fun managedKeysCoverEveryWrittenField() {
        val written = VorbisFields.apply(
            emptyList(),
            TagEdits(
                title = "t", artist = "a", album = "b", albumArtist = "c", genre = "g", year = "1",
                trackNumber = "1", trackTotal = "2", discNumber = "1", discTotal = "2", lyrics = "l",
            ),
        )
        assertEquals(emptyList(), written.map { it.key }.filterNot { it in VorbisFields.MANAGED })
    }
}
