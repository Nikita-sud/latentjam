/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.library.tags.TagFormat
import io.github.nikitasud.latentjam.library.tags.TagRefusal
import io.github.nikitasud.latentjam.library.tags.TagSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException

internal class TagFileReadTest {

    @Test
    fun aFileNoCodecKnowsIsNotEditableAsAnUnsupportedFormat() {
        assertEquals(TagFileRead.NotEditable(TagProblem.UNSUPPORTED_FORMAT), tagFileReadOf(null))
    }

    @Test
    fun aFileTheCodecWouldRefuseSaysWhyBeforeAnyTyping() {
        val protected = TagSnapshot(TagFormat.MP4, "MP4", refusal = TagRefusal.MP4_DRM_PROTECTED)
        assertEquals(TagFileRead.NotEditable(TagProblem.PROTECTED), tagFileReadOf(protected))
    }

    @Test
    fun aTagThatBreaksTheReaderLeavesTheFileUnreadableNotTheEditorDead() {
        // A tag too large for the heap is an Error on Android, which no `catch (Exception)` sees.
        class HeapExhausted : Error("test heap exhausted")
        assertEquals(TagFileRead.NotEditable(TagProblem.UNREADABLE), tagFileReadOrUnreadable { throw HeapExhausted() })
        assertEquals(TagFileRead.NotEditable(TagProblem.UNREADABLE), tagFileReadOrUnreadable { error("a parser bug") })
        assertFailsWith<CancellationException> { tagFileReadOrUnreadable { throw CancellationException("left the screen") } }
        val snapshot = TagSnapshot(TagFormat.FLAC, "FLAC", title = "Song")
        assertEquals(TagFileRead.Ready(snapshot), tagFileReadOrUnreadable { snapshot })
    }

    @Test
    fun anEditableFileIsReadyWithItsSnapshot() {
        val snapshot = TagSnapshot(TagFormat.FLAC, "FLAC", title = "Song", albumArtist = "Various Artists")
        assertEquals(TagFileRead.Ready(snapshot), tagFileReadOf(snapshot))
    }
}
