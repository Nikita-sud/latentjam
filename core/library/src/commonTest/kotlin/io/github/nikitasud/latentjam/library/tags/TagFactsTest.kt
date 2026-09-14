/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
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
                assertEquals(tag.size, source.position, "Only the ID3 prefix is needed")
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
        val facts = assertNotNull(TagFacts.embedded(ArraySource("fLaC".encodeToByteArray() + header + body)))
        assertEquals("ron", facts.language)
        assertEquals(1987, facts.originalYear)
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
    fun duplicateCreditsCollapseCaseInsensitively() {
        val facts = TagFacts.fromComments(
            listOf("ARTISTS" to "Gorillaz", "ARTISTS" to "gorillaz", "ARTISTS" to "Bootie Brown"),
        )
        assertEquals(listOf("Gorillaz", "Bootie Brown"), facts.artists)
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
