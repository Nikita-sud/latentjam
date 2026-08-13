/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

internal class TrackLyricsTest {

    @Test
    fun sourceIdentityInvalidatesSameIdTagReplacement() {
        val original = TrackDescriptor(
            id = TrackId("same"),
            audioUri = "file:///old.mp3",
            sourceRevision = "1",
        )

        assertEquals(original.lyricsSourceIdentity(), original.copy(title = "Display").lyricsSourceIdentity())
        assertNotEquals(original.lyricsSourceIdentity(), original.copy(sourceRevision = "2").lyricsSourceIdentity())
        assertNotEquals(original.lyricsSourceIdentity(), original.copy(audioUri = "file:///new.mp3").lyricsSourceIdentity())
    }
}
