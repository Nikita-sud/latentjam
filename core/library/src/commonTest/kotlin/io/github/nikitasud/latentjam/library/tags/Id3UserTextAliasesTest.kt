/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals

internal class Id3UserTextAliasesTest {

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
}
