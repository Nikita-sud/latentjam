/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TagWriteKeyFileTest {
    private fun bytesOf(keys: List<String>): ByteArray =
        ByteArrayOutputStream().also { writeTagWriteKeys(it, keys) }.toByteArray()

    private fun read(bytes: ByteArray): List<String> = readTagWriteKeys(ByteArrayInputStream(bytes), bytes.size.toLong())

    @Test
    fun keysComeBackExactly() {
        val keys = listOf(
            "content://media/external/audio/media/42",
            "",
            "a,b:c!d\n",
            "lone \uD800 surrogate",
            "🎵 note",
        )
        assertEquals(keys, read(bytesOf(keys)))
        assertEquals(emptyList(), read(bytesOf(emptyList())))
    }

    @Test
    fun tenThousandKeysComeBack() {
        val keys = List(10_000) { "content://media/external/audio/media/$it" }
        assertEquals(keys, read(bytesOf(keys)))
    }

    @Test
    fun aTornFileIsRefused() {
        val bytes = bytesOf(listOf("content://media/external/audio/media/1", "content://media/external/audio/media/2"))
        assertFailsWith<EOFException> { read(bytes.copyOf(bytes.size - 3)) }
    }

    @Test
    fun trailingBytesAreRefused() {
        val bytes = bytesOf(listOf("content://media/external/audio/media/1"))
        assertFailsWith<IllegalStateException> { read(bytes + 0) }
    }

    @Test
    fun aDamagedCountOrLengthIsRefusedBeforeAnythingIsAllocated() {
        val hugeCount = byteArrayOf(0x7F, -1, -1, -1)
        assertFailsWith<IllegalStateException> { read(hugeCount) }
        val hugeLength = byteArrayOf(0, 0, 0, 1, 0x7F, -1, -1, -1)
        assertFailsWith<IllegalStateException> { read(hugeLength) }
        val negativeCount = byteArrayOf(-1, -1, -1, -1)
        assertFailsWith<IllegalStateException> { read(negativeCount) }
    }
}
