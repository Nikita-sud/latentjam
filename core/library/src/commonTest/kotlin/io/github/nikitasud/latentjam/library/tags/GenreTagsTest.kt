/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GenreTagsTest {

    // ------------------------------------------------------------------ split

    @Test
    fun splitKeepsMultiWordGenresWhole() {
        assertEquals(
            listOf("Electronic", "Rock", "Trip Hop", "Alternative Rock", "Hip Hop"),
            GenreTags.split("Electronic; Rock; Trip Hop; Alternative Rock; Hip Hop"),
        )
    }

    @Test
    fun splitHandlesSlashCommaAndNulJoins() {
        assertEquals(listOf("Pop", "Rock"), GenreTags.split("Pop/Rock"))
        assertEquals(listOf("Disco", "Funk"), GenreTags.split("Disco, Funk"))
        assertEquals(listOf("Rock", "Pop"), GenreTags.split("Rock\u0000Pop"))
        // A space is NOT a separator: multi-word genres are one value.
        assertEquals(listOf("Phonk Drift"), GenreTags.split("Phonk Drift"))
    }

    @Test
    fun splitExpandsId3NumericReferences() {
        // ID3v2.3 wrote genre references as "(nn)" runs, optionally with refinement text.
        assertEquals(listOf("Rock", "Disco"), GenreTags.split("(17)(4)"))
        assertEquals(listOf("Metal", "Doom"), GenreTags.split("(9)Doom"))
        // A bare v1 index as the whole value.
        assertEquals(listOf("Trip-Hop"), GenreTags.split("27"))
    }

    @Test
    fun splitDropsJunkAndDeduplicates() {
        assertEquals(emptyList(), GenreTags.split("<unknown>"))
        assertEquals(emptyList(), GenreTags.split("   "))
        assertEquals(listOf("Rock"), GenreTags.split("Rock; rock; ROCK"))
        // An out-of-table reference contributes nothing rather than a number.
        assertEquals(listOf("Rock"), GenreTags.split("(17)(199)"))
    }

    @Test
    fun canonicalRoundTripsThroughSplit() {
        val genres = listOf("Electronic", "Trip Hop")
        assertEquals(genres, GenreTags.split(GenreTags.canonical(genres)))
        assertNull(GenreTags.canonical(emptyList()))
    }

    // ------------------------------------------------------------------- flac

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

    private fun vorbisComment(vendor: String, comments: List<String>): ByteArray {
        val vendorBytes = vendor.encodeToByteArray()
        var body = leU32(vendorBytes.size) + vendorBytes + leU32(comments.size)
        for (comment in comments) {
            val entry = comment.encodeToByteArray()
            body = body + leU32(entry.size) + entry
        }
        return body
    }

    private fun flacFile(blocks: List<Pair<Int, ByteArray>>): ByteArray {
        var out = "fLaC".encodeToByteArray()
        blocks.forEachIndexed { index, (type, body) ->
            val last = if (index == blocks.lastIndex) 0x80 else 0
            out = out + byteArrayOf(
                (last or type).toByte(),
                ((body.size shr 16) and 0xFF).toByte(),
                ((body.size shr 8) and 0xFF).toByte(),
                (body.size and 0xFF).toByte(),
            ) + body
        }
        return out
    }

    @Test
    fun flacReadsEveryGenreFieldPastOtherBlocks() {
        // The shape of the real bug report: STREAMINFO, a fat PICTURE block, then a Vorbis
        // comment carrying five separate GENRE fields — the correct multi-genre convention.
        val file = flacFile(
            listOf(
                0 to ByteArray(34),
                6 to ByteArray(4096) { 0x7F },
                4 to vorbisComment(
                    vendor = "reference libFLAC",
                    comments = listOf(
                        "TITLE=Dirty Harry",
                        "GENRE=Electronic",
                        "GENRE=Rock",
                        "GENRE=Trip Hop",
                        "GENRE=Alternative Rock",
                        "GENRE=Hip Hop",
                        "ARTIST=Gorillaz",
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("Electronic", "Rock", "Trip Hop", "Alternative Rock", "Hip Hop"),
            GenreTags.flacGenres(ArraySource(file)),
        )
    }

    @Test
    fun flacWithoutCommentBlockOrMagicDeclines() {
        assertNull(GenreTags.flacGenres(ArraySource(flacFile(listOf(0 to ByteArray(34))))))
        assertNull(GenreTags.flacGenres(ArraySource("ID3junk".encodeToByteArray())))
    }

    @Test
    fun flacTruncatedMidBlockDeclinesRatherThanMisreads() {
        val comment = vorbisComment("v", listOf("GENRE=Rock", "GENRE=Pop"))
        val file = flacFile(listOf(4 to comment))
        // The block header claims more bytes than the file holds: a corrupt file, declined.
        val cut = file.copyOfRange(0, file.size - 3)
        assertNull(GenreTags.flacGenres(ArraySource(cut)))
    }

    @Test
    fun aCommentTruncatedInsideItsBodyKeepsWhatWasRead() {
        // The ogg path parses whatever prefix arrived, so an in-body cut exercises the
        // salvage rule: values completed before the cut survive.
        val comment = vorbisComment("libopus", listOf("GENRE=Rock", "GENRE=Pop"))
        val prefix = "OggS".encodeToByteArray() + ByteArray(20) +
            "OpusTags".encodeToByteArray() + comment
        val cut = prefix.copyOfRange(0, prefix.size - 3)
        assertEquals(listOf("Rock"), GenreTags.oggGenres(cut))
    }

    // -------------------------------------------------------------------- ogg

    @Test
    fun oggOpusCommentIsFound() {
        val comment = vorbisComment("libopus", listOf("GENRE=Ambient", "GENRE=Drone"))
        val prefix = "OggS".encodeToByteArray() + ByteArray(60) +
            "OpusTags".encodeToByteArray() + comment
        assertEquals(listOf("Ambient", "Drone"), GenreTags.oggGenres(prefix))
    }

    @Test
    fun nonOggPrefixDeclines() {
        assertNull(GenreTags.oggGenres("fLaC....".encodeToByteArray()))
    }
}
