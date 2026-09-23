/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistCoverPickerTest {
    @Test
    fun referencesCannotEscapeOwnedCoverDirectory() {
        val reference = "f08f8630-6120-4aa9-9580-973044632c42.jpg"
        assertTrue(isPlaylistCoverReference(reference))
        listOf(null, "", "../$reference", "/tmp/$reference", "file://$reference", reference.uppercase(),
            "f08f8630-6120-4aa9-9580-973044632c42.png").forEach {
            assertFalse(isPlaylistCoverReference(it))
        }
    }

    @Test
    fun cameraAndPanoramaInputsHaveBoundedDecodeSize() {
        listOf(4032 to 3024, 12000 to 9000, 100000 to 1200, 700 to 700, 1 to 100000).forEach { (w, h) ->
            val sample = playlistCoverDecodeSample(w, h)!!
            assertTrue(maxOf(w, h) / sample < PLAYLIST_COVER_MAX_EDGE * 2)
            assertTrue(sample == 1 || maxOf(w, h) / sample >= PLAYLIST_COVER_MAX_EDGE)
        }
        assertEquals(1, playlistCoverDecodeSample(768, 400))
        assertEquals(4, playlistCoverDecodeSample(4032, 3024))
    }

    @Test
    fun invalidDimensionsNeverReachThePixelDecoder() {
        listOf(-1 to -1, 0 to 200, 100001 to 1, 20 to Int.MAX_VALUE).forEach { (w, h) ->
            assertNull(playlistCoverDecodeSample(w, h))
        }
    }
}
