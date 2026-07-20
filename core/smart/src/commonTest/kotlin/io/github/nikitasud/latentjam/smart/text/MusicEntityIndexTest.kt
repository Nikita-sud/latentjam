/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicEntityIndexTest {

    @Test
    fun `aliases and membership use data ids rather than artist rules`() {
        // 17 is a person and 42 is their group. The alias resolves to both; the group name to 42.
        val index = assertNotNull(
            MusicEntityIndex.parse(
                pack(
                    "viktor tsoi" to intArrayOf(17, 42),
                    "виктор" to intArrayOf(17, 42),
                    "кино" to intArrayOf(42),
                    "kino" to intArrayOf(42),
                ),
            ),
        )

        assertContentEquals(intArrayOf(17, 42), index.resolve("Viktor Tsoi"))
        assertTrue(index.matches("Виктор", "КИНО"))
        assertTrue(index.matches("Viktor Tsoi", "Kino"))
        assertFalse(index.matches("Виктор", "Unrelated artist"))
    }

    @Test
    fun `normalization is script preserving and does not call every Cyrillic name Russian`() {
        assertTrue(MusicEntityIndex.normalize("  ПЁТР—Ильич ") == "петр ильич")
        assertTrue(MusicEntityIndex.normalize("Кіно") == "кіно")
        assertFalse(MusicEntityIndex.normalize("Кіно") == MusicEntityIndex.normalize("Кино"))
    }

    @Test
    fun `corrupt packs fail closed`() {
        assertNull(MusicEntityIndex.parse(byteArrayOf(1, 2, 3)))
        assertNull(MusicEntityIndex.parse(pack("name" to intArrayOf(1)).copyOf(24)))
    }

    private fun pack(vararg mappings: Pair<String, IntArray>): ByteArray {
        val ordered = mappings
            .map { (key, values) -> MusicEntityIndex.fnv1a64(key.encodeToByteArray()) to values }
            .sortedBy { it.first }
        val valueCount = ordered.sumOf { it.second.size }
        val bytes = ByteArray(20 + ordered.size * 16 + valueCount * 4)
        byteArrayOf(0x4c, 0x4a, 0x45, 0x4e, 0x54, 0x31, 0, 0).copyInto(bytes)
        writeInt(bytes, 8, ordered.size)
        writeInt(bytes, 12, valueCount)
        writeInt(bytes, 16, 100)
        var valueOffset = 0
        ordered.forEachIndexed { index, (hash, values) ->
            val entry = 20 + index * 16
            writeULong(bytes, entry, hash)
            writeInt(bytes, entry + 8, valueOffset)
            writeUShort(bytes, entry + 12, values.size)
            values.forEachIndexed { offset, value ->
                writeInt(bytes, 20 + ordered.size * 16 + (valueOffset + offset) * 4, value)
            }
            valueOffset += values.size
        }
        return bytes
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun writeUShort(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 2) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun writeULong(bytes: ByteArray, offset: Int, value: ULong) {
        for (index in 0 until 8) bytes[offset + index] = (value shr (index * 8)).toByte()
    }
}
