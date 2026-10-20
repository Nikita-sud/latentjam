/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class IdentityDigestsTest {
    private val a = TrackId("a")
    private val b = TrackId("b")

    @Test
    fun matchesExactlyTheRecordedString() {
        val digests = IdentityDigests()
        assertTrue(digests.put(a, "audio-v2|5:x.mp3|3:120|-1:"))
        assertTrue(digests.matches(a, "audio-v2|5:x.mp3|3:120|-1:"))
        assertFalse(digests.matches(a, "audio-v2|5:x.mp3|3:121|-1:"))
        assertFalse(digests.matches(b, "audio-v2|5:x.mp3|3:120|-1:"))
        assertFalse(digests.put(a, "audio-v2|5:x.mp3|3:120|-1:"), "the same identity again changes nothing")
        assertTrue(digests.put(a, "other"))
        assertTrue(digests.matches(a, "other"))
    }

    @Test
    fun checkpointsRoundTripAndLegacyStringsStillMatch() {
        val written = IdentityDigests()
        written.put(a, "text-v3|genre; artist; 1999; en")
        written.put(b, "text-v3|other")
        val tokens = written.persisted { it == a }
        assertEquals(setOf(a), tokens.keys)
        assertEquals(IdentityDigests.tokenOf("text-v3|genre; artist; 1999; en"), tokens.getValue(a))

        val restored = IdentityDigests()
        restored.putPersisted(a, tokens.getValue(a))
        // A checkpoint from before digests stores the string itself.
        restored.putPersisted(b, "text-v3|other")
        assertTrue(restored.matches(a, "text-v3|genre; artist; 1999; en"))
        assertTrue(restored.matches(b, "text-v3|other"))
        assertFalse(restored.matches(b, "text-v3|changed"))
    }

    @Test
    fun removalFreesTheIdAndKeepsTheOthers() {
        val digests = IdentityDigests()
        repeat(200) { digests.put(TrackId("t$it"), "identity $it") }
        repeat(100) { assertTrue(digests.remove(TrackId("t${it * 2}"))) }
        assertFalse(digests.remove(TrackId("t0")))
        repeat(100) { digests.put(TrackId("n$it"), "new $it") }
        assertEquals(200, digests.size)
        repeat(100) {
            assertFalse(TrackId("t${it * 2}") in digests)
            assertTrue(digests.matches(TrackId("t${it * 2 + 1}"), "identity ${it * 2 + 1}"))
            assertTrue(digests.matches(TrackId("n$it"), "new $it"))
        }
        assertEquals((0 until 100).map { TrackId("t${it * 2 + 1}") } + (0 until 100).map { TrackId("n$it") }, digests.ids())
    }

    @Test
    fun aTokenLookingStringThatIsNotATokenIsDigested() {
        assertEquals(null, IdentityDigests.parseToken("#d1:not-hex-at-all-but-thirty-two-chars"))
        assertEquals(null, IdentityDigests.parseToken("audio-v2|#d1:"))
    }
}
