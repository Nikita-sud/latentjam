/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.tags.Id3TestTags.latin1Body
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.mp3Payload
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.utf8Body
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class Id3UserTextAliasesTest {

    private fun file(major: Int, vararg frames: TestFrame, body: (String) -> ByteArray = ::latin1Body) =
        Id3TestTags.build(
            major,
            listOf(TestFrame("TIT2", body("Title")), TestFrame("TPE1", body("Old")), TestFrame("TCON", body("Rock"))) +
                frames,
            padding = 512,
        ) + mp3Payload()

    private fun userTexts(file: ByteArray) = Id3TestTags.framesOf(file).filter { it.id == "TXXX" }

    private fun failures(before: ByteArray, after: ByteArray, edits: TagEdits) =
        TagVerification.verify(Id3TagCodec, ByteArraySource(before), ByteArraySource(after), edits).map { it.check }

    private fun facts(file: ByteArray): EmbeddedTagFacts? {
        var position = 0
        return TagFacts.embedded(object : GenreTags.ByteSource {
            override fun read(count: Int): ByteArray? =
                if (count > file.size - position) null else readUpTo(count)
            override fun readUpTo(count: Int): ByteArray {
                val end = minOf(file.size, position + count)
                return file.copyOfRange(position, end).also { position = end }
            }
            override fun skip(count: Long): Boolean {
                if (count > file.size - position) return false
                position += count.toInt()
                return true
            }
        })
    }

    @Test
    fun artistAndGenreEditsCannotLeaveStaleUserTextAliases() {
        val original = Id3TestTags.build(
            3,
            listOf(
                TestFrame("TPE1", Id3TestTags.latin1Body("Original")),
                TestFrame("TCON", Id3TestTags.latin1Body("Rock")),
                TestFrame("TXXX", Id3TestTags.latin1Body("ARTIST\u0000Original")),
                TestFrame("TXXX", Id3TestTags.latin1Body("GENRE\u0000Rock")),
            ),
        ) + Id3TestTags.mp3Payload()
        val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, original, TagEdits(artist = "New; Second", genre = ""))
        val facts = facts(edited)
        assertEquals(emptyList(), facts?.genres)
        assertEquals(listOf("New", "Second"), facts?.artists)
    }

    @Test
    fun aGenreCopyThatChangesUnderAnUnrelatedEditFailsVerification() {
        val before = file(3, TestFrame("TXXX", latin1Body("GENRE\u0000Rock")))
        val tampered = file(3, TestFrame("TXXX", latin1Body("GENRE\u0000Jazz")))
            .let { WritePlans.applyInMemory(it, Id3TagCodec.plan(ByteArraySource(it), TagEdits(title = "New")))!! }
        assertEquals(listOf(TagVerification.Check.INVENTORY), failures(before, tampered, TagEdits(title = "New")))
    }

    @Test
    fun aGenreEditRemovesEveryGenreCopyAndVerifies() {
        for (genre in listOf("Jazz", "Rock", "")) {
            val before = file(3, TestFrame("TXXX", latin1Body("GENRE\u0000Rock")), TestFrame("TXXX", latin1Body("genre\u0000Pop")))
            val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, before, TagEdits(genre = genre))
            assertEquals(emptyList(), userTexts(edited))
        }
    }

    @Test
    fun anArtistEditOnAFileWithOnlyTxxxArtistVerifies() {
        for (major in listOf(3, 4)) {
            val before = file(major, TestFrame("TXXX", latin1Body("ARTIST\u0000Old One; Old Two")))
            assertEquals(listOf("Old One", "Old Two"), Id3TagCodec.read(ByteArraySource(before)).artists)
            val credited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, before, TagEdits(artist = "New; Second"))
            assertEquals(listOf("New", "Second"), Id3TagCodec.read(ByteArraySource(credited)).artists)
            val solo = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, before, TagEdits(artist = "Solo"))
            assertEquals(emptyList(), userTexts(solo))
        }
    }

    @Test
    fun anArtistCopyWithNoNamesIsLeftAsItIsByAnArtistEdit() {
        val empty = TestFrame("TXXX", latin1Body("ARTIST\u0000"))
        val before = file(3, empty)
        val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, before, TagEdits(artist = "New; Second"))
        assertEquals(1, userTexts(edited).size)
        assertContentEquals(empty.body, userTexts(edited).single().body)
    }

    @Test
    fun anUnrelatedEditKeepsTheArtistAndGenreCopiesByteForByte() {
        for (major in listOf(3, 4)) {
            val body: (String) -> ByteArray = if (major == 4) ::utf8Body else ::latin1Body
            val before = file(
                major,
                TestFrame("TXXX", body("ARTIST\u0000Old; Other")),
                TestFrame("TXXX", body("GENRE\u0000Rock")),
                body = body,
            )
            val plan = Id3TagCodec.plan(ByteArraySource(before), TagEdits(title = "New", album = "Album"))
            assertIs<WritePlan.InPlacePatch>(plan)
            val edited = CodecAssertions.assertWriteMatchesExpectation(Id3TagCodec, before, TagEdits(title = "New", album = "Album"))
            assertEquals(userTexts(before).map { it.body.toList() }, userTexts(edited).map { it.body.toList() })
            assertTrue(Id3TagCodec.inventory(ByteArraySource(edited)).any { it.startsWith(TagVerification.GENRE_ENTRY + "TXXX:") }, "the genre copy is in the inventory")
        }
    }
}
