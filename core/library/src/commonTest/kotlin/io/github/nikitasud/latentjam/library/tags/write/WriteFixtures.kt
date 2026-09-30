/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags.write

import io.github.nikitasud.latentjam.library.tags.ByteArraySource
import io.github.nikitasud.latentjam.library.tags.FlacFixtures
import io.github.nikitasud.latentjam.library.tags.Id3TestTags
import io.github.nikitasud.latentjam.library.tags.Mp4Fixtures
import io.github.nikitasud.latentjam.library.tags.OggFixtures
import io.github.nikitasud.latentjam.library.tags.TagCodecs
import io.github.nikitasud.latentjam.library.tags.TagEdits
import io.github.nikitasud.latentjam.library.tags.TestFrame
import io.github.nikitasud.latentjam.library.tags.WritePlan
import io.github.nikitasud.latentjam.library.tags.WritePlans

/** One small file per format and path, with an edit that sends it down that path. */
internal object WriteFixtures {
    class Case(val name: String, val original: ByteArray, val edits: TagEdits)

    private val longLyrics = "la ".repeat(2_000)
    private fun mp3(padding: Int, audio: Int = 1024) =
        Id3TestTags.build(3, listOf(TestFrame("TIT2", Id3TestTags.latin1Body("Old"))), padding = padding) +
            Id3TestTags.mp3Payload(audio)

    // FLAC block types: 1 = PADDING, 4 = VORBIS_COMMENT.
    val inPlace: List<Case>
        get() = listOf(
            Case("mp3", mp3(padding = 512), TagEdits(title = "New")),
            Case("mp3 losing its ID3v1 trailer", mp3(padding = 512) + Id3TestTags.v1Trailer(), TagEdits(title = "New")),
            Case("flac", FlacFixtures.file(4 to FlacFixtures.comments("TITLE" to "Old"), 1 to ByteArray(1_000)), TagEdits(title = "New")),
            Case("opus", OggFixtures.opus("TITLE" to "Old"), TagEdits(title = "New")),
            Case("m4a", Mp4Fixtures.file(listOf(Mp4Fixtures.text("©nam", "Old")), freeAfterIlst = 2_000), TagEdits(title = "New")),
            Case("m4a whose last moov grows", Mp4Fixtures.file(listOf(Mp4Fixtures.text("©nam", "Old")), moovFirst = false), TagEdits(lyrics = longLyrics)),
        )

    val rewrites: List<Case>
        get() = listOf(
            Case("mp3", mp3(padding = 0, audio = 4_096), TagEdits(lyrics = longLyrics)),
            Case("flac", FlacFixtures.file(4 to FlacFixtures.comments("TITLE" to "Old")), TagEdits(lyrics = longLyrics)),
            Case("opus", OggFixtures.opus("TITLE" to "Old", padding = 0), TagEdits(lyrics = "la ".repeat(30_000))),
            Case("m4a", Mp4Fixtures.file(listOf(Mp4Fixtures.text("©nam", "Old"))), TagEdits(lyrics = longLyrics)),
        )

    fun plan(case: Case): WritePlan = TagCodecs.plan(ByteArraySource(case.original), case.edits)

    fun expected(case: Case): ByteArray = WritePlans.applyInMemory(case.original, plan(case))!!
}
