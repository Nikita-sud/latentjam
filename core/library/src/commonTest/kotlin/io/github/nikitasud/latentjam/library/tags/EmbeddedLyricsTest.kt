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
        // Plain words next to a synced twin: the timed one is what the player shows.
        assertEquals(true, lyrics?.synced)
        assertEquals(listOf(14_230L), lyrics?.lines?.map { it.timeMs })
        assertEquals("кто мечтает быть пилотом?", lyrics?.text)
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
        assertEquals(listOf(14_230L, 16_050L, 39_250L), lyrics?.lines?.map { it.timeMs })
        assertEquals(listOf("первая строка", "вторая строка", ""), lyrics?.lines?.map { it.text })
    }

    @Test
    fun aPlainFieldCarryingLrcIsUnstampedToo() {
        val lyrics = EmbeddedLyrics.read(
            ArraySource(oggPrefix(listOf("LYRICS=[00:01.00] слова\n[00:02.00] ещё слова"))),
        )
        assertEquals("слова\nещё слова", lyrics?.text)
        assertEquals(listOf(1_000L, 2_000L), lyrics?.lines?.map { it.timeMs })
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
        val lyrics = EmbeddedLyrics.read(ArraySource(file))
        assertEquals("text of the song", lyrics?.text)
        assertFalse(lyrics!!.synced)
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
        assertEquals("слова за границей страницы", EmbeddedLyrics.read(ArraySource(file))?.text)
    }

    @Test
    fun idTagsAreDroppedOffsetAppliedAndChorusStampsExpanded() {
        val lyrics = EmbeddedLyrics.parse(
            """
            [ar:5sta Family]
            [ti:Снова вместе]
            [by:@LosslessRobot]
            [offset:+500]
            [00:10.00]Первый куплет
            [00:20.00][00:40.00]Припев
            [00:30.00]Второй <00:31.00>куплет <00:32.50>с пословными метками
            [00:50.5]Конец
            """.trimIndent(),
        )!!
        assertTrue(lyrics.synced)
        assertEquals(
            listOf("Первый куплет", "Припев", "Второй куплет с пословными метками", "Припев", "Конец"),
            lyrics.lines.map { it.text },
        )
        // Offset shifts every stamp; the chorus appears at both of its times, in time order.
        assertEquals(listOf(9_500L, 19_500L, 29_500L, 39_500L, 50_000L), lyrics.lines.map { it.timeMs })
        assertFalse(lyrics.text.contains("LosslessRobot"))
    }

    @Test
    fun plainLyricsLoseTheirIdTagsButKeepTheirStanzas() {
        val lyrics = EmbeddedLyrics.parse("[by:@LosslessRobot]\n\nVerse one\nline two\n\n\nChorus\n\n")!!
        assertFalse(lyrics.synced)
        assertEquals("Verse one\nline two\n\nChorus", lyrics.text)
        assertNull(EmbeddedLyrics.parse("[ar:Nobody]\n[by:bot]\n\n"))
    }

    @Test
    fun unstampedTranslationFollowsItsVerseAfterOutOfOrderCuesAreSorted() {
        val lyrics = EmbeddedLyrics.parse(
            "[00:40]Later verse\nLater translation\n[00:10]Earlier verse\nEarlier translation\n[00:20]Middle verse",
        )!!
        assertEquals(
            listOf("Earlier verse", "Earlier translation", "Middle verse", "Later verse", "Later translation"),
            lyrics.lines.map { it.text },
        )
    }

    @Test
    fun chorusTimestampsMayBeSeparatedByWhitespace() {
        val lyrics = EmbeddedLyrics.parse("[00:10.1]  [00:20:12]\t[00:30.123]Chorus")!!
        assertEquals(listOf(10_100L, 20_120L, 30_123L), lyrics.lines.map { it.timeMs })
        assertEquals(listOf("Chorus", "Chorus", "Chorus"), lyrics.lines.map { it.text })
    }

    @Test
    fun extremeOffsetsNeverWrapLaterCuesBackToTheBeginning() {
        assertEquals(
            Long.MAX_VALUE,
            EmbeddedLyrics.parse("[offset:${Long.MIN_VALUE}]\n[00:10]Later")!!.lines.single().timeMs,
        )
        assertEquals(
            0L,
            EmbeddedLyrics.parse("[offset:${Long.MAX_VALUE}]\n[00:10]Earlier")!!.lines.single().timeMs,
        )
        assertEquals(
            10_500L,
            EmbeddedLyrics.parse("[offset:-500]\n[00:10]Later")!!.lines.single().timeMs,
        )
    }

    @Test
    fun manyChorusStampsPreserveEveryCueAndOneSharedText() {
        // A long embedded field must scan stamp positions without repeatedly copying its suffix.
        val lyrics = EmbeddedLyrics.parse("[00:10]".repeat(10_000) + "Chorus")!!
        assertEquals(10_000, lyrics.lines.size)
        assertTrue(lyrics.lines.all { it.timeMs == 10_000L && it.text == "Chorus" })
    }
}
