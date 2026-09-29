/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.tags.Id3TestTags.artFrame
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.commentFrame
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.latin1Body
import io.github.nikitasud.latentjam.library.tags.Id3TestTags.mp3Payload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class TagVerificationTest {

    private val codec = Id3TagCodec

    private val file = Id3TestTags.build(
        3,
        listOf(TestFrame("TIT2", latin1Body("Title")), commentFrame("keep"), artFrame(size = 200)),
        padding = 1024,
    ) + mp3Payload()

    private fun written(edits: TagEdits): ByteArray =
        WritePlans.applyInMemory(file, codec.plan(ByteArraySource(file), edits))!!

    private fun checks(failures: List<TagVerification.Failure>) = failures.map { it.check }.toSet()

    @Test
    fun aCorrectWriteVerifies() {
        val edits = TagEdits(title = "New", cover = CoverEdit.Remove)
        assertEquals(emptyList(), TagVerification.verify(codec, ByteArraySource(file), ByteArraySource(written(edits)), edits))
    }

    @Test
    fun aReadBackThatMissesTheEditFails() {
        val failures = TagVerification.verify(codec, ByteArraySource(file), ByteArraySource(file), TagEdits(title = "New"))
        assertEquals(setOf(TagVerification.Check.READ_BACK), checks(failures))
    }

    @Test
    fun changedAudioFails() {
        val edits = TagEdits(title = "New")
        val out = written(edits).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(
            setOf(TagVerification.Check.AUDIO),
            checks(TagVerification.verify(codec, ByteArraySource(file), ByteArraySource(out), edits)),
        )
    }

    @Test
    fun changedUnmanagedDataFails() {
        val edits = TagEdits(title = "New")
        val other = Id3TestTags.build(
            3,
            listOf(TestFrame("TIT2", latin1Body("New")), commentFrame("gone"), artFrame(size = 200)),
            padding = 1024,
        ) + mp3Payload()
        assertEquals(
            setOf(TagVerification.Check.INVENTORY),
            checks(TagVerification.verify(codec, ByteArraySource(file), ByteArraySource(other), edits)),
        )
    }

    @Test
    fun aNullDigestNeverMatches() {
        assertFalse(TagVerification.audioMatches(null, null))
        assertFalse(TagVerification.audioMatches(7L, null))
        assertTrue(TagVerification.audioMatches(7L, 7L))
    }

    @Test
    fun aFileThatWasNotEditableNeverVerifies() {
        val flac = Id3TestTags.build(3, listOf(TestFrame("TIT2", latin1Body("t")))) + "fLaC".encodeToByteArray() + ByteArray(100)
        val failures = TagVerification.verify(codec, ByteArraySource(flac), ByteArraySource(flac), TagEdits(title = "x"))
        assertEquals(setOf(TagVerification.Check.EDITABLE), checks(failures))
    }

    @Test
    fun anUntaggedMp3MayGainAnId3v23TagButNoOtherVersion() {
        val before = TagSnapshot(TagFormat.MP3, "none")
        val edits = TagEdits(title = "x")
        assertEquals(emptyList(), TagVerification.readBackFailures(before, edits, TagSnapshot(TagFormat.MP3, "ID3v2.3", title = "x")))
        assertEquals(
            setOf(TagVerification.Check.VERSION),
            checks(TagVerification.readBackFailures(before, edits, TagSnapshot(TagFormat.MP3, "ID3v2.4", title = "x"))),
        )
        assertEquals(
            setOf(TagVerification.Check.VERSION),
            checks(
                TagVerification.readBackFailures(
                    TagSnapshot(TagFormat.MP3, "ID3v2.4"),
                    edits,
                    TagSnapshot(TagFormat.MP3, "ID3v2.3", title = "x"),
                ),
            ),
        )
    }

    @Test
    fun whichPictureComesNextAfterARemovalIsNotCompared() {
        val a = CoverInfo("image/jpeg", 1, 1L)
        val b = CoverInfo("image/jpeg", 2, 2L)
        val c = CoverInfo("image/jpeg", 3, 3L)
        val before = TagSnapshot(TagFormat.FLAC, "FLAC", cover = a, nextCover = b, otherPictures = 2, pictures = listOf(1L, 2L, 3L))
        val after = TagSnapshot(TagFormat.FLAC, "FLAC", cover = b, nextCover = c, otherPictures = 1, pictures = listOf(2L, 3L))
        assertEquals(emptyList(), TagVerification.readBackFailures(before, TagEdits(cover = CoverEdit.Remove), after))
        // After any other edit the next cover is still pinned.
        assertEquals(
            setOf(TagVerification.Check.READ_BACK),
            checks(TagVerification.readBackFailures(before, TagEdits(title = "x"), before.copy(title = "x", nextCover = c))),
        )
    }
}
