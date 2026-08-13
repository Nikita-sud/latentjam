/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class Id3LyricsTest {

    private fun latin1(text: String) = ByteArray(text.length) { text[it].code.toByte() }

    private fun utf16le(text: String): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0xFF.toByte())
        out.add(0xFE.toByte())
        for (c in text) {
            out.add((c.code and 0xFF).toByte())
            out.add(((c.code shr 8) and 0xFF).toByte())
        }
        return out.toByteArray()
    }

    @Test
    fun readsLatin1LyricsAfterTheDescriptor() {
        val body = byteArrayOf(0) + latin1("eng") + latin1("desc") + byteArrayOf(0) +
            latin1("First line\nSecond line")
        val tag = Id3TestTags.build(major = 3, frames = listOf(TestFrame("USLT", body)))
        assertEquals("First line\nSecond line", Id3Tags.lyrics(tag))
    }

    @Test
    fun readsUtf16LyricsBehindTheDoubleByteTerminator() {
        val body = byteArrayOf(1) + latin1("rus") + utf16le("описание") + byteArrayOf(0, 0) +
            utf16le("Так хочется жить")
        val tag = Id3TestTags.build(major = 4, frames = listOf(TestFrame("USLT", body)))
        assertEquals("Так хочется жить", Id3Tags.lyrics(tag))
    }

    @Test
    fun anEmptyDescriptorIsTheCommonCaseAndStillWorks() {
        val body = byteArrayOf(3) + latin1("eng") + byteArrayOf(0) + "текст".encodeToByteArray()
        val tag = Id3TestTags.build(major = 4, frames = listOf(TestFrame("USLT", body)))
        assertEquals("текст", Id3Tags.lyrics(tag))
    }

    @Test
    fun filesWithoutLyricsOrWithEmptyOnesReadAsNull() {
        val noUslt = Id3TestTags.build(
            major = 3,
            frames = listOf(TestFrame("TIT2", Id3TestTags.latin1Body("Title"))),
        )
        assertNull(Id3Tags.lyrics(noUslt))

        val emptyText = byteArrayOf(0) + latin1("eng") + latin1("d") + byteArrayOf(0)
        val emptyTag = Id3TestTags.build(major = 3, frames = listOf(TestFrame("USLT", emptyText)))
        assertNull(Id3Tags.lyrics(emptyTag))

        assertNull(Id3Tags.lyrics(ByteArray(3)))
    }
}
