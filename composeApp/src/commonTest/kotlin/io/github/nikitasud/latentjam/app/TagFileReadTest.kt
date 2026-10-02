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
    fun anEditableFileIsReadyWithItsSnapshot() {
        val snapshot = TagSnapshot(TagFormat.FLAC, "FLAC", title = "Song", albumArtist = "Various Artists")
        assertEquals(TagFileRead.Ready(snapshot), tagFileReadOf(snapshot))
    }
}
