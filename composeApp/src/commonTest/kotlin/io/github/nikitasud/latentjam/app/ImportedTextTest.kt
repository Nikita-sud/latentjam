/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Playlists written by Windows tools and older players are often not UTF-8. */
internal class ImportedTextTest {

    private val text = "#EXTM3U\n/storage/emulated/0/Music/Beyoncé/Déjà Vu – Live.mp3\n"

    private fun windows1252(value: String): ByteArray = ByteArray(value.length) { index ->
        when (val char = value[index]) {
            '–' -> 0x96.toByte()
            else -> char.code.also { check(it < 0x100) }.toByte()
        }
    }

    private fun utf16(value: String, littleEndian: Boolean): ByteArray {
        val bom = if (littleEndian) byteArrayOf(0xff.toByte(), 0xfe.toByte()) else byteArrayOf(0xfe.toByte(), 0xff.toByte())
        return bom + value.flatMap { char ->
            val high = (char.code shr 8).toByte()
            val low = char.code.toByte()
            if (littleEndian) listOf(low, high) else listOf(high, low)
        }.toByteArray()
    }

    @Test
    fun utf8IsReadAsUtf8() {
        assertEquals(text, decodeImportedText(text.encodeToByteArray()))
    }

    @Test
    fun utf8MarkIsKeptForTheParser() {
        val marked = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + text.encodeToByteArray()
        assertEquals("﻿$text", decodeImportedText(marked))
    }

    @Test
    fun ansiCodePageIsNotReplacedByQuestionMarks() {
        assertEquals(text, decodeImportedText(windows1252(text)))
    }

    @Test
    fun utf16WithMarkIsDecodedInBothByteOrders() {
        assertEquals(text, decodeImportedText(utf16(text, littleEndian = true)))
        assertEquals(text, decodeImportedText(utf16(text, littleEndian = false)))
    }

    @Test
    fun ansiPlaylistStillFindsAccentedSongs() {
        val library = listOf(
            TrackDescriptor(
                id = TrackId("1"),
                title = "Déjà Vu",
                durationMs = 240_000,
                audioUri = "content://media/external/audio/media/1",
                folderPath = "Music/Beyoncé",
                fileName = "Déjà Vu – Live.mp3",
            ),
        )
        val entries = parseM3u(decodeImportedText(windows1252(text)))
        assertEquals(listOf("1"), matchM3uEntries(entries, library, emptyMap()).map { it?.id?.value })
    }

    @Test
    fun ansiPercentEscapesFindTheirSong() {
        val library = listOf(
            TrackDescriptor(
                id = TrackId("1"),
                title = "Café",
                durationMs = 240_000,
                audioUri = "content://media/external/audio/media/1",
                folderPath = "Music",
                fileName = "Café.mp3",
            ),
        )
        val entries = parseM3u("#EXTM3U\nfile:///storage/emulated/0/Music/Caf%E9.mp3\n")
        assertEquals(listOf("1"), matchM3uEntries(entries, library, emptyMap()).map { it?.id?.value })
    }
}
