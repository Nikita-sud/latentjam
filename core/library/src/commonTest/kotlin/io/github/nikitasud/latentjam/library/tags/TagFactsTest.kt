/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TagFactsTest {

    @Test
    fun languageAndOriginalYearAreReadFromRealId3FramesWithoutReadingAudio() {
        for (major in listOf(3, 4)) {
            val languageBodies = listOf(
                Id3TestTags.latin1Body("rus/eng"),
                Id3TestTags.utf16Body("rus\u0000eng"),
                Id3TestTags.utf8Body("русский"),
            )
            for ((index, languageBody) in languageBodies.withIndex()) {
                val tag = Id3TestTags.build(
                    major = major,
                    frames = listOf(
                        Id3TestTags.artFrame(4_096),
                        TestFrame("TLAN", languageBody),
                        TestFrame(if (major == 3) "TYER" else "TDRC", Id3TestTags.latin1Body("2012")),
                        TestFrame(if (major == 3) "TORY" else "TDOR", Id3TestTags.latin1Body("1987")),
                    ),
                )
                val source = ArraySource(tag + Id3TestTags.mp3Payload())
                val facts = assertNotNull(TagFacts.embedded(source))
                assertEquals(listOf("rus/eng", "rus", "русский")[index], facts.language)
                assertEquals(1987, facts.originalYear)
                assertEquals(2012, facts.year)
                // The tag, and the four bytes after it that tell an ID3 tag in front of FLAC or Ogg.
                assertEquals(tag.size + 4, source.position, "Only the ID3 prefix is needed")
            }
        }
    }

    @Test
    fun languageAndOriginalYearSurviveVorbisCommentsWithUnrelatedMetadata() {
        val comments = listOf(
            "TITLE=Example", "DATE=2012", "ORIGINALDATE=1987-06-12",
            "LANGUAGE= ron ", "GENRE=Pop", "ARTISTS=First;Second",
        )
        val body = littleEndian(1) + byteArrayOf('v'.code.toByte()) +
            littleEndian(comments.size) + comments.fold(byteArrayOf()) { bytes, comment ->
                val value = comment.encodeToByteArray()
                bytes + littleEndian(value.size) + value
            }
        val header = byteArrayOf(
            0x84.toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte(),
        )
        val streamInfo = byteArrayOf(0, 0, 0, 34) + ByteArray(34)
        val facts = assertNotNull(TagFacts.embedded(ArraySource("fLaC".encodeToByteArray() + streamInfo + header + body)))
        assertEquals("ron", facts.language)
        assertEquals(1987, facts.originalYear)
        assertEquals(2012, facts.year)
        assertEquals(listOf("Pop"), facts.genres)
        assertEquals(listOf("First", "Second"), facts.artists)
    }

    @Test
    fun vorbisArtistsFieldsBecomeTheCreditList() {
        val facts = TagFacts.fromComments(
            listOf(
                "ARTIST" to "Gorillaz feat. Bootie Brown",
                "ARTISTS" to "Gorillaz",
                "ARTISTS" to "Bootie Brown",
                "GENRE" to "Electronic",
                "ORIGINALDATE" to "2005-05-24",
            ),
        )
        assertEquals(listOf("Gorillaz", "Bootie Brown"), facts.artists)
        assertEquals(listOf("Electronic"), facts.genres)
        assertEquals(2005, facts.originalYear)
    }

    @Test
    fun languageComesFromTheTagVerbatimAndOnlyOnce() {
        assertEquals("rus", TagFacts.fromComments(listOf("LANGUAGE" to " rus ")).language)
        assertEquals("English", TagFacts.fromComments(listOf("TLAN" to "English", "LANGUAGE" to "rus")).language)
        assertNull(TagFacts.fromComments(listOf("LANGUAGE" to "")).language)
        // A field that is really a comment does not become a language.
        assertNull(TagFacts.fromComments(listOf("LANGUAGE" to "x".repeat(40))).language)
        assertNull(TagFacts.fromComments(listOf("GENRE" to "Pop")).language)
    }

    @Test
    fun theAlbumIsTheFilesOwnAndBlankIsNone() {
        assertEquals("Abbey Road", TagFacts.fromComments(listOf("ALBUM" to " Abbey Road ")).album)
        assertEquals("Let It Be", TagFacts.fromComments(listOf("TALB" to "Let It Be", "ALBUM" to "Help!")).album)
        assertNull(TagFacts.fromComments(listOf("ALBUM" to "  ")).album)
        assertNull(TagFacts.fromComments(listOf("ALBUMARTIST" to "The Beatles")).album)
    }

    @Test
    fun aTagReadInPartDoesNotSayItNamesNoAlbum() {
        assertFalse(TagFacts.fromComments(listOf("GENRE" to "Pop", GenreTags.INCOMPLETE to "")).albumKnown)
        assertTrue(TagFacts.fromComments(listOf("ALBUM" to "Help!", GenreTags.INCOMPLETE to "")).albumKnown)
        assertTrue(TagFacts.fromComments(listOf("GENRE" to "Pop")).albumKnown)
    }

    @Test
    fun aLoneDisplayArtistIsNeverGuessedApart() {
        // "feat."-cutting display strings is how taggers ruin band names; a single ARTIST
        // field carries no split information and produces no credit list.
        val facts = TagFacts.fromComments(listOf("ARTIST" to "Crosby, Stills & Nash"))
        assertEquals(emptyList(), facts.artists)
    }

    @Test
    fun multipleArtistFieldsAreALegitimateMultiCredit() {
        val facts = TagFacts.fromComments(
            listOf("ARTIST" to "William Davies", "ARTIST" to "Edward Nutbrown"),
        )
        assertEquals(listOf("William Davies", "Edward Nutbrown"), facts.artists)
    }

    @Test
    fun joinedTxxxArtistsSplitOnListSeparatorsOnly() {
        assertEquals(
            listOf("Gorillaz", "Bootie Brown"),
            TagFacts.splitArtists("Gorillaz;Bootie Brown"),
        )
        assertEquals(
            listOf("Gorillaz", "Bootie Brown"),
            TagFacts.splitArtists("Gorillaz\u0000Bootie Brown"),
        )
        // A space never separates — one person, two words.
        assertEquals(listOf("Bootie Brown"), TagFacts.splitArtists("Bootie Brown"))
    }

    @Test
    fun originalYearComesFromTheFirstSaneSource() {
        assertEquals(1987, TagFacts.fromComments(listOf("TDOR" to "1987")).originalYear)
        assertEquals(1987, TagFacts.fromComments(listOf("ORIGINALYEAR" to "1987-06")).originalYear)
        assertNull(TagFacts.fromComments(listOf("TDOR" to "0000")).originalYear)
        assertNull(TagFacts.fromComments(listOf("TDOR" to "next year")).originalYear)
        // Classical tagging puts the composition year here; that intent survives.
        assertEquals(1707, TagFacts.fromComments(listOf("ORIGINALDATE" to "1707")).originalYear)
    }

    @Test
    fun theFilesOwnYearComesFromItsDateFieldInAnySpelling() {
        assertEquals(1997, TagFacts.fromComments(listOf("DATE" to "1997")).year)
        assertEquals(2001, TagFacts.fromComments(listOf("TDRC" to "2001-06-20")).year)
        assertEquals(1979, TagFacts.fromComments(listOf("YEAR" to "1979")).year)
        assertEquals(1984, TagFacts.fromComments(listOf("TYER" to "1984")).year)
        // The first sane value wins; a placeholder does not block a later real one.
        assertEquals(1999, TagFacts.fromComments(listOf("DATE" to "1999", "YEAR" to "2005")).year)
        assertEquals(2005, TagFacts.fromComments(listOf("DATE" to "0000", "YEAR" to "2005")).year)
        assertNull(TagFacts.fromComments(listOf("DATE" to "someday")).year)
        // The original year is a different fact and never stands in for the edition year.
        assertNull(TagFacts.fromComments(listOf("ORIGINALDATE" to "1987")).year)
        assertEquals(EmbeddedTagFacts(year = 2012), TagFacts.fromComments(listOf("DATE" to "2012")))
    }

    @Test
    fun anId3v23YearFrameIsReadAsTheFilesYear() {
        val tag = Id3TestTags.build(
            major = 3,
            frames = listOf(TestFrame("TYER", Id3TestTags.latin1Body("1983"))),
        )
        val facts = assertNotNull(TagFacts.embedded(ArraySource(tag + Id3TestTags.mp3Payload())))
        assertEquals(1983, facts.year)
        assertNull(facts.originalYear)
    }

    @Test
    fun aYearAloneMakesTheFactsNonEmpty() {
        assertEquals(false, EmbeddedTagFacts(year = 1990).isEmpty)
    }

    @Test
    fun duplicateCreditsCollapseCaseInsensitively() {
        val facts = TagFacts.fromComments(
            listOf("ARTISTS" to "Gorillaz", "ARTISTS" to "gorillaz", "ARTISTS" to "Bootie Brown"),
        )
        assertEquals(listOf("Gorillaz", "Bootie Brown"), facts.artists)
    }

    @Test
    fun theAlbumArtistComesFromAnySpellingOfTheField() {
        assertEquals("Various Artists", TagFacts.fromComments(listOf("ALBUMARTIST" to " Various Artists ")).albumArtist)
        assertEquals("VA", TagFacts.fromComments(listOf("ALBUM ARTIST" to "VA")).albumArtist)
        assertEquals("VA", TagFacts.fromComments(listOf("ALBUM_ARTIST" to "VA", "ALBUMARTIST" to "Other")).albumArtist)
        assertNull(TagFacts.fromComments(listOf("ALBUMARTIST" to "  ")).albumArtist)
    }

    @Test
    fun theAlbumArtistIsReadFromAnId3Tpe2Frame() {
        val tagged = Id3Tags.updateTag(
            byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(1024),
            TagEdits(albumArtist = "Various Artists"),
        )!!
        assertEquals("Various Artists", TagFacts.embedded(ArraySource(tagged))?.albumArtist)
    }

    private val mpegAudio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(1024)

    private fun id3v1(album: String): ByteArray = ByteArray(128).also { trailer ->
        "TAG".encodeToByteArray().copyInto(trailer)
        "A title".encodeToByteArray().copyInto(trailer, 3)
        album.encodeToByteArray().copyInto(trailer, 63)
        trailer[127] = 0xFF.toByte()
    }

    /** An ID3v2.2 tag: three-letter frame names and three-byte frame sizes. */
    private fun id3v22(frames: List<Pair<String, ByteArray>>, padding: Int = 16, flags: Int = 0): ByteArray {
        var body = ByteArray(0)
        for ((name, content) in frames) {
            body += name.encodeToByteArray() +
                byteArrayOf((content.size shr 16).toByte(), (content.size shr 8).toByte(), content.size.toByte()) + content
        }
        body += ByteArray(padding)
        val size = byteArrayOf(
            ((body.size shr 21) and 0x7F).toByte(), ((body.size shr 14) and 0x7F).toByte(),
            ((body.size shr 7) and 0x7F).toByte(), (body.size and 0x7F).toByte(),
        )
        return "ID3".encodeToByteArray() + byteArrayOf(2, 0, flags.toByte()) + size + body
    }

    @Test
    fun anMp3WithoutAnyTagIsReadAndNamesNoAlbum() {
        // The commonest file with neither album nor cover (issue #39): it used to read as "could not read".
        val facts = assertNotNull(TagFacts.embedded(ByteArraySource(mpegAudio)))
        assertNull(facts.album)
        assertTrue(facts.albumKnown)
        assertTrue(facts.isEmpty)
    }

    @Test
    fun anMp3WithOnlyAnId3v1TrailerNamesThatTrailersAlbum() {
        assertEquals("Blue", TagFacts.embedded(ByteArraySource(mpegAudio + id3v1("Blue")))?.album)
        val blank = assertNotNull(TagFacts.embedded(ByteArraySource(mpegAudio + id3v1(""))))
        assertNull(blank.album)
        assertTrue(blank.albumKnown)
    }

    @Test
    fun anId3v22TagAnswersWhetherItNamesAnAlbum() {
        val title = "TT2" to (byteArrayOf(0) + "A title".encodeToByteArray())
        val album = "TAL" to (byteArrayOf(0) + "Abbey Road".encodeToByteArray() + byteArrayOf(0))
        val picture = "PIC" to ByteArray(3_000) { 0x55 }
        assertEquals(
            "Abbey Road",
            TagFacts.embedded(ByteArraySource(id3v22(listOf(title, picture, album)) + mpegAudio))?.album,
        )
        val utf16 = "TAL" to (byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte(), 'H'.code.toByte(), 0, 'i'.code.toByte(), 0))
        assertEquals("Hi", TagFacts.embedded(ByteArraySource(id3v22(listOf(utf16), padding = 0) + mpegAudio))?.album)
        val none = assertNotNull(TagFacts.embedded(ByteArraySource(id3v22(listOf(title, picture)) + mpegAudio)))
        assertNull(none.album)
        assertTrue(none.albumKnown)
    }

    @Test
    fun aFileNoReaderCanJudgeIsReadButCannotTellItsAlbum() {
        val wave = "RIFF".encodeToByteArray() + ByteArray(4) + "WAVEfmt ".encodeToByteArray() + ByteArray(64)
        // An ID3v2.2 tag whose frames cannot be followed: unsynchronised, or cut short.
        val album = "TAL" to (byteArrayOf(0) + "Abbey Road".encodeToByteArray())
        val unsynchronised = id3v22(listOf(album), flags = 0x80) + mpegAudio
        val cut = id3v22(listOf(album)).copyOf(18)
        // An ID3v2.3 tag the strict reader refuses (the unsynchronisation flag).
        val refused = "ID3".encodeToByteArray() + byteArrayOf(3, 0, 0x80.toByte(), 0, 0, 0, 20) + ByteArray(20) + mpegAudio
        for (bytes in listOf(wave, unsynchronised, cut, refused, ByteArray(0))) {
            val facts = assertNotNull(TagFacts.embedded(ByteArraySource(bytes)))
            assertFalse(facts.albumKnown)
            assertTrue(facts.isEmpty)
        }
    }

    private fun littleEndian(value: Int): ByteArray = ByteArray(4) { (value ushr (it * 8)).toByte() }

    private class ArraySource(private val bytes: ByteArray) : GenreTags.ByteSource {
        var position = 0
            private set

        override fun read(count: Int): ByteArray? {
            if (count > bytes.size - position) return null
            return bytes.copyOfRange(position, position + count).also { position += count }
        }

        override fun readUpTo(count: Int): ByteArray = read(minOf(count, bytes.size - position))!!

        override fun skip(count: Long): Boolean {
            if (count > bytes.size - position) return false
            position += count.toInt()
            return true
        }
    }
}
