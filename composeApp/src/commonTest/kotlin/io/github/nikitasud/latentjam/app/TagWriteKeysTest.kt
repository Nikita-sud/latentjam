/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TagWriteKeysTest {
    @Test
    fun keysComeBackExactly() {
        val keys = listOf("Album/Track 01.mp3", "", "a,b:c!d\n", "lone \uD800 surrogate", "🎵 note")
        assertEquals(keys, decodeTagWriteKeys(encodeTagWriteKeys(keys)))
        assertEquals(emptyList(), decodeTagWriteKeys(encodeTagWriteKeys(emptyList())))
    }

    @Test
    fun theBytesAreBigEndianCountLengthAndCodeUnits() {
        assertContentEquals(
            byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2, 0, 0x41, -0x28, 0),
            encodeTagWriteKeys(listOf("A\uD800")),
        )
    }

    @Test
    fun tornTrailingOrDamagedBytesAreRefused() {
        val bytes = encodeTagWriteKeys(listOf("Album/one.mp3", "Album/two.mp3"))
        assertFailsWith<IllegalStateException> { decodeTagWriteKeys(bytes.copyOf(bytes.size - 3)) }
        assertFailsWith<IllegalStateException> { decodeTagWriteKeys(bytes + 0) }
        assertFailsWith<IllegalStateException> { decodeTagWriteKeys(byteArrayOf(0x7F, -1, -1, -1)) }
        assertFailsWith<IllegalStateException> { decodeTagWriteKeys(byteArrayOf(0, 0, 0, 1, 0x7F, -1, -1, -1)) }
        assertFailsWith<IllegalStateException> { decodeTagWriteKeys(byteArrayOf(-1, -1, -1, -1)) }
        assertFailsWith<IllegalStateException> { decodeTagWriteKeys(byteArrayOf(0, 0)) }
    }
}
