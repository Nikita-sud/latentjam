/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmbeddedLyricsTest {

    private class ArraySource(private val bytes: ByteArray) : GenreTags.ByteSource {
        private var position = 0
        override fun read(count: Int): ByteArray? {
            if (position + count > bytes.size) return null
            return bytes.copyOfRange(position, position + count).also { position += count }
        }

        override fun readUpTo(count: Int): ByteArray {
            val end = minOf(bytes.size, position + count)
            return bytes.copyOfRange(position, end).also { position = end }
        }

        override fun skip(count: Long): Boolean {
            if (position + count > bytes.size) return false
            position += count.toInt()
            return true
        }
    }

    private fun leU32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun vorbisComment(comments: List<String>): ByteArray {
        val vendor = "test".encodeToByteArray()
        var body = leU32(vendor.size) + vendor + leU32(comments.size)
        for (comment in comments) {
            val entry = comment.encodeToByteArray()
            body = body + leU32(entry.size) + entry
        }
        return body
    }

    private fun oggPrefix(comments: List<String>): ByteArray =
        "OggS".encodeToByteArray() + ByteArray(24) +
            "OpusTags".encodeToByteArray() + vorbisComment(comments)

    @Test
    fun opusPlainLyricsComeThrough() {
        // The Смешарики shape: a LYRICS field plus a SYNCEDLYRICS sibling — plain wins.
        val lyrics = EmbeddedLyrics.read(
            ArraySource(
                oggPrefix(
                    listOf(
                        "TITLE=От винта!",
                        "LYRICS=кто мечтает быть пилотом?\nочень смелый видно тот",
                        "SYNCEDLYRICS=[00:14.23] кто мечтает быть пилотом?",
                    ),
                ),
            ),
        )
        assertEquals("кто мечтает быть пилотом?\nочень смелый видно тот", lyrics)
    }

    @Test
    fun syncedOnlyLyricsAreUnstamped() {
        val lyrics = EmbeddedLyrics.read(
            ArraySource(
                oggPrefix(
                    listOf(
                        "SYNCEDLYRICS=[00:14.23] первая строка\n[00:16.05] вторая строка\n[00:39.25] ",
                    ),
                ),
            ),
        )
        assertEquals("первая строка\nвторая строка", lyrics)
    }

    @Test
    fun aPlainFieldCarryingLrcIsUnstampedToo() {
        val lyrics = EmbeddedLyrics.read(
            ArraySource(oggPrefix(listOf("LYRICS=[00:01.00] слова\n[00:02.00] ещё слова"))),
        )
        assertEquals("слова\nещё слова", lyrics)
    }

    @Test
    fun flacLyricsComeFromTheCommentBlock() {
        val comment = vorbisComment(listOf("UNSYNCEDLYRICS=text of the song"))
        val file = "fLaC".encodeToByteArray() + byteArrayOf(
            (0x80 or 4).toByte(),
            ((comment.size shr 16) and 0xFF).toByte(),
            ((comment.size shr 8) and 0xFF).toByte(),
            (comment.size and 0xFF).toByte(),
        ) + comment
        assertEquals("text of the song", EmbeddedLyrics.read(ArraySource(file)))
    }

    @Test
    fun lyricslessAndUnknownContainersDecline() {
        assertNull(EmbeddedLyrics.read(ArraySource(oggPrefix(listOf("TITLE=No words here")))))
        assertNull(EmbeddedLyrics.read(ArraySource("RIFFjunkjunk".encodeToByteArray())))
    }

    /** Wraps a payload into real Ogg pages (27-byte header + segment table), like encoders do. */
    private fun pagedOgg(payload: ByteArray, pageSize: Int): ByteArray {
        var out = ByteArray(0)
        var offset = 0
        while (offset < payload.size) {
            val chunk = minOf(pageSize, payload.size - offset)
            val segments = ArrayList<Int>()
            var remaining = chunk
            while (remaining >= 255) {
                segments.add(255)
                remaining -= 255
            }
            segments.add(remaining)
            val header = ByteArray(27)
            "OggS".encodeToByteArray().copyInto(header)
            header[26] = segments.size.toByte()
            out = out + header + ByteArray(segments.size) { segments[it].toByte() } +
                payload.copyOfRange(offset, offset + chunk)
            offset += chunk
        }
        return out
    }

    @Test
    fun lyricsSurviveRealOggPaging() {
        // The failure the real album exposed: encoders split the comment packet across ~4KB
        // pages, and a flat scan derails on the first in-stream page header. Bury the lyrics
        // behind a fat padding comment so they land pages deep.
        val padding = "PAD=" + "x".repeat(12_000)
        val packet = "OpusTags".encodeToByteArray() + vorbisComment(
            listOf(padding, "LYRICS=слова за границей страницы"),
        )
        val file = pagedOgg(packet, pageSize = 4080)
        assertEquals("слова за границей страницы", EmbeddedLyrics.read(ArraySource(file)))
    }
}
