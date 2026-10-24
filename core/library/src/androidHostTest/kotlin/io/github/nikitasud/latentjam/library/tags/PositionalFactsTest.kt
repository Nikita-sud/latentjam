/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.tags.write.WriteFixtures
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The positional facts reader against the stream reader it replaces on Android: the same facts,
 * or the same refusal, for every corpus file, every write fixture, generated tags with the
 * awkward parts of ID3 (pictures, wrong-encoding sizes, extended header, footer, padding), and
 * thousands of byte-level mutations of them. And it never loads a picture.
 */
class PositionalFactsTest {

    /** The one-pass stream the facts were read through before, over the same bytes. */
    private class StreamSource(private val bytes: ByteArray) : GenreTags.ByteSource {
        private var position = 0
        override fun read(count: Int): ByteArray? {
            if (count < 0 || count > bytes.size - position) return null
            return bytes.copyOfRange(position, position + count).also { position += count }
        }
        override fun readUpTo(count: Int): ByteArray {
            val n = minOf(count, bytes.size - position)
            return bytes.copyOfRange(position, position + n).also { position += n }
        }
        override fun skip(count: Long): Boolean {
            if (count > bytes.size - position) {
                position = bytes.size
                return false
            }
            position += count.toInt()
            return true
        }
    }

    /** Counts what is read, and remembers the largest single read. */
    private class CountingSource(bytes: ByteArray) : RandomAccessSource {
        private val inner = ByteArraySource(bytes)
        var total = 0L
        var largest = 0
        override val length: Long get() = inner.length
        override fun read(offset: Long, count: Int): ByteArray? {
            total += count
            largest = maxOf(largest, count)
            return inner.read(offset, count)
        }
    }

    private fun assertSame(bytes: ByteArray, label: String) {
        // The stream reader has no MP4 path; where it reads nothing, the positional reader adds only
        // whether an MP4 file names an album (issue #12).
        val expected = TagFacts.embedded(StreamSource(bytes)) ?: Mp4AlbumProbe.read(ByteArraySource(bytes))
        val actual = TagFacts.embedded(ByteArraySource(bytes))
        assertEquals(expected, actual, label)
    }

    private fun corpus(): List<Pair<String, ByteArray>> {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "tools/fuzz/corpus").isDirectory }
        val files = listOf("id3", "flac", "mp4", "ogg").flatMap { name ->
            File(root, "tools/fuzz/corpus/$name").listFiles().orEmpty().filter { it.isFile }
                .map { "$name/${it.name}" to it.readBytes() }
        }
        val fixtures = (WriteFixtures.inPlace + WriteFixtures.rewrites).mapIndexed { index, case ->
            "fixture $index" to case.original
        }
        return files + fixtures
    }

    private fun syncsafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(), ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(), (value and 0x7F).toByte(),
    )

    private fun plain(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun frame(id: String, body: ByteArray, major: Int, plainSize: Boolean = major == 3) =
        id.encodeToByteArray() + (if (plainSize) plain(body.size) else syncsafe(body.size)) + byteArrayOf(0, 0) + body

    private fun text(value: String) = byteArrayOf(3) + value.encodeToByteArray()

    private fun tag(
        major: Int,
        frames: List<ByteArray>,
        padding: Int = 0,
        extended: Boolean = false,
        footer: Boolean = false,
        audio: Int = 64,
    ): ByteArray {
        val ext = when {
            !extended -> ByteArray(0)
            major == 3 -> plain(6) + ByteArray(6)
            else -> syncsafe(6) + byteArrayOf(1, 0)
        }
        val body = ByteArrayOutputStream().apply {
            write(ext)
            frames.forEach { write(it) }
            write(ByteArray(padding))
        }.toByteArray()
        var flags = 0
        if (extended) flags = flags or 0x40
        if (footer) flags = flags or 0x10
        val header = "ID3".encodeToByteArray() + byteArrayOf(major.toByte(), 0, flags.toByte()) + syncsafe(body.size)
        val foot = if (footer) "3DI".encodeToByteArray() + header.copyOfRange(3, 10) else ByteArray(0)
        return header + body + foot + ByteArray(audio) { 0xFF.toByte() }
    }

    private fun generated(random: Random): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for (major in listOf(3, 4)) {
            val picture = frame("APIC", byteArrayOf(0) + "image/jpeg".encodeToByteArray() + byteArrayOf(0, 3, 0) +
                ByteArray(3_000) { random.nextInt().toByte() }, major)
            val textFrames = listOf(
                frame("TCON", text("Rock\u0000Pop"), major),
                frame("TPE1", text("First\u0000Second"), major),
                frame("TPE2", text("Various Artists"), major),
                frame(if (major == 4) "TDRC" else "TYER", text("1999"), major),
                frame(if (major == 4) "TDOR" else "TORY", text("1987"), major),
                frame("TLAN", text("eng"), major),
                frame("TXXX", byteArrayOf(3) + "ARTISTS".encodeToByteArray() + byteArrayOf(0) + "A; B".encodeToByteArray(), major),
                frame("TXXX", byteArrayOf(3) + "ORIGINALDATE".encodeToByteArray() + byteArrayOf(0) + "1970".encodeToByteArray(), major),
                // A long text frame in the other size encoding (the taggers' bug the parser tolerates).
                frame("TXXX", byteArrayOf(3) + "NOTE".encodeToByteArray() + byteArrayOf(0) + ByteArray(300) { 'x'.code.toByte() }, major, plainSize = major == 4),
            )
            for (pictureFirst in listOf(true, false)) for (extended in listOf(false, true)) for (padding in listOf(0, 5, 2_000)) {
                val frames = if (pictureFirst) listOf(picture) + textFrames else textFrames + picture
                out += tag(major, frames, padding, extended, footer = false)
                if (major == 4) out += tag(major, frames, padding, extended, footer = true)
            }
        }
        return out
    }

    @Test fun positionalReadsGiveTheStreamReadersFactsEverywhere() {
        for ((name, bytes) in corpus()) assertSame(bytes, name)
        val random = Random(48)
        generated(random).forEachIndexed { index, bytes ->
            assertSame(bytes, "generated $index")
            val facts = TagFacts.embedded(ByteArraySource(bytes))
            assertEquals(listOf("Rock", "Pop"), facts?.genres, "generated $index is read, not refused")
            assertEquals(1987, facts?.originalYear, "generated $index")
        }
    }

    @Test fun mutatedTagsAreRefusedOrReadExactlyAlike() {
        val random = Random(10_021)
        val seeds = generated(random) + corpus().map { it.second }.filter { it.size >= 10 && Id3Codec.looksLikeTag(it) }
        repeat(20_000) {
            val bytes = seeds[random.nextInt(seeds.size)].copyOf()
            repeat(1 + random.nextInt(3)) {
                // Mostly in the header and frame headers, where the walk decides things.
                val at = if (random.nextInt(4) == 0) random.nextInt(bytes.size) else random.nextInt(minOf(bytes.size, 64))
                bytes[at] = when (random.nextInt(4)) {
                    0 -> 0
                    1 -> 0xFF.toByte()
                    2 -> (bytes[at] + 1).toByte()
                    else -> random.nextInt().toByte()
                }
            }
            assertSame(bytes, "mutation $it")
        }
    }

    @Test fun anId3PictureIsNeverLoaded() {
        val cover = 3 * 1024 * 1024
        val picture = frame("APIC", byteArrayOf(0) + "image/png".encodeToByteArray() + byteArrayOf(0, 3, 0) + ByteArray(cover), 4)
        val bytes = tag(4, listOf(frame("TCON", text("Jazz"), 4), picture, frame("TPE1", text("A\u0000B"), 4)), padding = 4096)
        val source = CountingSource(bytes)
        val facts = TagFacts.embedded(source)
        assertEquals(listOf("Jazz"), facts?.genres)
        assertEquals(listOf("A", "B"), facts?.artists)
        assertTrue(source.total < 64 * 1024, "read ${source.total} bytes of a ${bytes.size}-byte file")
        assertTrue(source.largest <= 64 * 1024)
    }
}
