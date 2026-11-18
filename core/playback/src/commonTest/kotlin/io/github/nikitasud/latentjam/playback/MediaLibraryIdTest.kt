/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaLibraryIdTest {
    @Test
    fun `a Music library track id gives its persistent id`() {
        assertEquals(1234567890123456789uL, mediaLibraryPersistentId("ios-media:1234567890123456789"))
        // Persistent ids use all 64 bits: the largest is no overflow.
        assertEquals(ULong.MAX_VALUE, mediaLibraryPersistentId("ios-media:18446744073709551615"))
        assertEquals(7uL, mediaLibraryPersistentId("ios-media:7"))
    }

    @Test
    fun `anything else gives none`() {
        assertNull(mediaLibraryPersistentId("Imported/song.mp3"))
        assertNull(mediaLibraryPersistentId("ios-media:"))
        assertNull(mediaLibraryPersistentId("ios-media:12a4"))
        assertNull(mediaLibraryPersistentId("ios-media:-5"))
        assertNull(mediaLibraryPersistentId("ios-media:+5"))
        assertNull(mediaLibraryPersistentId("ios-media: 5"))
        assertNull(mediaLibraryPersistentId("ios-media:18446744073709551616"))
        assertNull(mediaLibraryPersistentId("7"))
    }
}
