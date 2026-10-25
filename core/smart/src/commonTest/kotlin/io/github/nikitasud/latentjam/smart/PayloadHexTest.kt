/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class PayloadHexTest {

    /** The per-store codec this replaces, kept verbatim as the reference. */
    private fun oldEncode(value: String): String = value.encodeToByteArray().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun oldDecode(value: String): String? {
        if (value.length % 2 != 0) return null
        return runCatching {
            ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }.decodeToString(throwOnInvalidSequence = true)
        }.getOrNull()
    }

    @Test
    fun encodesLikeTheOldCodec() {
        for (text in listOf("", "Rock", "Beyoncé", "Hörspiel; Jazz", "a|b\nc", "日本語", "🎵 x", "\u0000")) {
            assertEquals(oldEncode(text), PayloadHex.encode(text))
            assertEquals(text, PayloadHex.decode(PayloadHex.encode(text)))
        }
    }

    @Test
    fun decodesExactlyWhatTheStrictDecoderAccepts() {
        val random = Random(20261019)
        // Biased towards bytes that form (or almost form) multi-byte sequences.
        val interesting = intArrayOf(0x00, 0x41, 0x7f, 0x80, 0x8f, 0x90, 0x9f, 0xa0, 0xbf, 0xc0, 0xc1, 0xc2,
            0xdf, 0xe0, 0xe1, 0xec, 0xed, 0xee, 0xef, 0xf0, 0xf1, 0xf3, 0xf4, 0xf5, 0xff)
        repeat(200_000) {
            val bytes = ByteArray(random.nextInt(0, 7)) {
                (if (random.nextBoolean()) interesting[random.nextInt(interesting.size)] else random.nextInt(256)).toByte()
            }
            val hex = bytes.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
            assertEquals(oldDecode(hex), PayloadHex.decode(hex), hex)
        }
    }

    @Test
    fun rejectsWhatTheOldCodecRejected() {
        for (bad in listOf("a", "zz", "0g", "c3", "eda080", "f4908080", "c0af", "80")) {
            assertNull(PayloadHex.decode(bad), bad)
            assertNull(oldDecode(bad), bad)
        }
        assertEquals("A", PayloadHex.decode("41"))
        assertEquals("A", PayloadHex.decode("x41y", 1, 3))
        // Upper-case digits parse, as toInt(16) did.
        assertEquals(oldDecode("C3A9"), PayloadHex.decode("C3A9"))
    }
}
