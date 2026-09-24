/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

import io.github.nikitasud.latentjam.smart.text.EntityIndexBytes.KEYS_OFFSET
import io.github.nikitasud.latentjam.smart.text.EntityIndexBytes.KEY_SIZE
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
                EntityIndexBytes.of(
                    "viktor tsoi" to intArrayOf(17, 42),
                    "виктор" to intArrayOf(17, 42),
                    "кино" to intArrayOf(42),
                    "kino" to intArrayOf(42),
                ),
            ),
        )

        assertContentEquals(intArrayOf(17, 42), index.resolve("Viktor Tsoi"))
        assertContentEquals(intArrayOf(42), index.resolve("KINO"))
        assertTrue(index.matches("Виктор", "КИНО"))
        assertTrue(index.matches("Viktor Tsoi", "Kino"))
        assertFalse(index.matches("Виктор", "Unrelated artist"))
        assertEquals(0, index.resolve("unrelated").size)
    }

    @Test
    fun `a key reads its values up to the next key and the last key up to the end`() {
        val index = assertNotNull(
            MusicEntityIndex.parse(EntityIndexBytes.of("alpha" to intArrayOf(1), "beta" to intArrayOf(2, 3, 4))),
        )
        assertContentEquals(intArrayOf(2, 3, 4), index.resolve("beta"))
        assertContentEquals(intArrayOf(1), index.resolve("alpha"))
    }

    @Test
    fun `normalization is script preserving and does not call every Cyrillic name Russian`() {
        assertTrue(MusicEntityIndex.normalize("  ПЁТР—Ильич ") == "петр ильич")
        assertTrue(MusicEntityIndex.normalize("Кіно") == "кіно")
        assertFalse(MusicEntityIndex.normalize("Кіно") == MusicEntityIndex.normalize("Кино"))
    }

    @Test
    fun `corrupt packs fail closed`() {
        val good = EntityIndexBytes.of("name" to intArrayOf(1))
        assertNotNull(MusicEntityIndex.parse(good))
        assertNull(MusicEntityIndex.parse(byteArrayOf(1, 2, 3)))
        assertNull(MusicEntityIndex.parse(good.copyOf(24)))
        assertNull(MusicEntityIndex.parse(good.copyOf(good.size - 1)))
        assertNull(MusicEntityIndex.parse(good.copyOf().also { it[5] = '1'.code.toByte() }))
    }

    @Test
    fun `hashes must be strictly sorted within a bucket for binary search`() {
        val bucket = 0x1234uL shl 32
        val sorted = listOf(bucket or 5uL to intArrayOf(1), bucket or 9uL to intArrayOf(2))
        assertNotNull(MusicEntityIndex.parse(EntityIndexBytes.hashed(sorted)))
        assertNull(MusicEntityIndex.parse(EntityIndexBytes.hashed(sorted.reversed(), sort = false)))
        assertNull(MusicEntityIndex.parse(EntityIndexBytes.hashed(listOf(sorted[0], sorted[0]))))
    }

    @Test
    fun `the bucket directory must cover every key in order`() {
        val bytes = EntityIndexBytes.of("alpha" to intArrayOf(1), "beta" to intArrayOf(2))
        // The last slot has to equal the key count.
        assertNull(MusicEntityIndex.parse(bytes.copyOf().also { EntityIndexBytes.writeInt(it, KEYS_OFFSET - 4, 1) }))
        // No slot may point past the keys.
        assertNull(MusicEntityIndex.parse(bytes.copyOf().also { EntityIndexBytes.writeInt(it, 24 + 4, 3) }))
    }

    @Test
    fun `every entity value slice must be bounded sorted and refer to a known entity`() {
        val twoKeys = EntityIndexBytes.of("alpha" to intArrayOf(1), "beta" to intArrayOf(2))
        // The first key starts at the first value.
        assertNull(MusicEntityIndex.parse(twoKeys.copyOf().also { EntityIndexBytes.writeU24(it, KEYS_OFFSET + 4, 1) }))
        // A key cannot be empty: the second key starting where the first does leaves the first nothing.
        assertNull(MusicEntityIndex.parse(twoKeys.copyOf().also { EntityIndexBytes.writeU24(it, KEYS_OFFSET + KEY_SIZE + 4, 0) }))
        // Nor can a key start past the end of the values.
        assertNull(MusicEntityIndex.parse(twoKeys.copyOf().also { EntityIndexBytes.writeU24(it, KEYS_OFFSET + KEY_SIZE + 4, 5) }))
        assertNull(MusicEntityIndex.parse(EntityIndexBytes.of("name" to intArrayOf(2, 1))))
        assertNull(MusicEntityIndex.parse(EntityIndexBytes.of("name" to intArrayOf(1, 1))))
        assertNull(MusicEntityIndex.parse(EntityIndexBytes.of("name" to intArrayOf(100))))
    }
}
