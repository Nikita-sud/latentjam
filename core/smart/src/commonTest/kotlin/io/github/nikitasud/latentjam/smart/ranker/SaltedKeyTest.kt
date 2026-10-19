/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Values from Python's hashlib and a reference SplitMix64 (PLAN_FINAL §1.6 step 5). */
class SaltedKeyTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun sha256MatchesTheStandardVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(Sha256.digest("abc".encodeToByteArray())))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Sha256.digest(ByteArray(0))))
        // Two blocks (56 bytes forces the length into a second block).
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    @Test
    fun theSeedAndItsDrawsMatchTheReference() {
        val track = TrackDescriptor(
            TrackId("42"), storageVolume = "external_primary", folderPath = "Music", fileName = "a.mp3",
            sizeBytes = 1234, durationMs = 180_000,
        )
        val query = SaltedKey.contentKey(track)
        assertEquals("v1|external_primary|Music/a.mp3|1234|180000", query)
        val seed = SaltedKey.seed(SaltedKey.SALT, query, press = 3)
        assertEquals(8650811944380572318L, seed)
        val draws = HopDraws(seed)
        assertEquals(listOf(-681825988479836992L, 1435393383262943665L, -7431890153768510843L), (1..3).map(draws::output))
        assertEquals(0xe220a8397b1dcdafuL.toLong(), SplitMix64(0).next())
        // Unsigned remainder: a negative output still gives an index in range.
        assertTrue(draws.pick(1, 7) in 0 until 7)
        assertEquals(((-681825988479836992L).toULong() % 7uL).toInt(), draws.pick(1, 7))
    }

    @Test
    fun aTrackWithoutAFilePathUsesItsIdAndPathsAreNfc() {
        assertEquals("v1||ipod-library://item/7|0|0", SaltedKey.contentKey(TrackDescriptor(TrackId("ipod-library://item/7"))))
        val decomposed = TrackDescriptor(TrackId("1"), folderPath = "/Muzica/", fileName = "Café.flac", sizeBytes = 9, durationMs = -5)
        assertEquals("v1||Muzica/Café.flac|9|0", SaltedKey.contentKey(decomposed))
    }

    @Test
    fun idsOrderByUnsignedUtf8Bytes() {
        val ids = listOf("b", "a", "é", "Z", "aa", "🎵")
        assertEquals(listOf("Z", "a", "aa", "b", "é", "🎵"), ids.sortedWith(SaltedKey.idOrder))
    }
}
