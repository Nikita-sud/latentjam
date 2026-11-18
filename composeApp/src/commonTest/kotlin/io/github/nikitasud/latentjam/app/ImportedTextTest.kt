/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor
import io.github.nikitasud.latentjam.smart.TrackId
import kotlinx.coroutines.test.runTest
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

    /** [value] as a Windows tool with the Cyrillic code page writes it: one byte a character. */
    private fun windows1251(value: String): ByteArray = ByteArray(value.length) { index ->
        val char = value[index]
        when {
            char.code < 0x80 -> char.code
            char in 'А'..'я' -> char.code - 0x410 + 0xC0
            char == 'ё' -> 0xB8
            char == 'Ё' -> 0xA8
            else -> error("not in this test's subset of Windows-1251: $char")
        }.toByte()
    }

    /** The same for the Central European code page (Romanian, Polish, Czech). */
    private fun windows1250(value: String): ByteArray = ByteArray(value.length) { index ->
        val char = value[index]
        when (char) {
            'ă' -> 0xE3
            'â' -> 0xE2
            'î' -> 0xEE
            'ł' -> 0xB3
            'ó' -> 0xF3
            'ż' -> 0xBF
            'ś' -> 0x9C
            else -> char.code.also { check(it < 0x80) { "not in this test's subset of Windows-1250: $char" } }
        }.toByte()
    }

    private fun song(id: String, folder: String, file: String) = TrackDescriptor(
        id = TrackId(id),
        title = file.substringBeforeLast('.'),
        durationMs = 200_000,
        audioUri = "content://media/external/audio/media/$id",
        folderPath = folder,
        fileName = file,
    )

    /** As the import reads a picked file: its text, and whether that text is a guess at the code page. */
    private suspend fun import(bytes: ByteArray, library: List<TrackDescriptor>): M3uImport =
        readImportedText(bytes).let { read -> importM3u(read.text, library, ansi = read.ansi) { emptyMap() } }

    @Test
    fun aCyrillicCodePagePlaylistFindsItsSongs() = runTest {
        val library = listOf(
            song("1", "Music/Кино", "Группа крови.mp3"),
            song("2", "Music/Кино", "Звезда по имени Солнце.mp3"),
            song("3", "Music", "Plain.mp3"),
        )
        val m3u = "#EXTM3U\r\n#PLAYLIST:Кино\r\nD:\\Music\\Кино\\Группа крови.mp3\r\n" +
            "D:\\Music\\Кино\\Звезда по имени Солнце.mp3\r\nD:\\Music\\Plain.mp3\r\n"

        val imported = import(windows1251(m3u), library)

        assertEquals(listOf("1", "2", "3"), imported.tracks.map { it.id.value })
        assertEquals(3, imported.matchedEntryCount)
        assertEquals("Кино", imported.name)
    }

    @Test
    fun aCentralEuropeanCodePagePlaylistFindsItsSongs() = runTest {
        val library = listOf(
            song("1", "Music/Phoenix", "Dă-mi mâna în zori.mp3"),
            song("2", "Music/Maanam", "żółte kalendarze.mp3"),
        )
        val m3u = "#EXTM3U\n/Music/Phoenix/Dă-mi mâna în zori.mp3\n/Music/Maanam/żółte kalendarze.mp3\n"

        val imported = import(windows1250(m3u), library)

        assertEquals(listOf("1", "2"), imported.tracks.map { it.id.value })
    }

    @Test
    fun aWesternCodePagePlaylistIsReadAsBeforeWhenAnotherReadingFindsNoMore() = runTest {
        // "Déjà Vu – Live" read as Cyrillic or Central European is other letters, which name no file here.
        val library = listOf(song("1", "Music/Beyoncé", "Déjà Vu – Live.mp3"))
        val imported = import(windows1252(text), library)
        assertEquals(listOf("1"), imported.tracks.map { it.id.value })
        // Nothing in the library at all: the reading does not change, so neither does what is reported.
        val nowhere = import(windows1252(text), listOf(song("9", "Music", "Other.mp3")))
        assertEquals(1, nowhere.entryCount)
        assertEquals(0, nowhere.matchedEntryCount)
    }

    @Test
    fun onlyAnAnsiReadingHasOtherReadingsAndTheyKeepEveryByte() {
        assertEquals(true, readImportedText(windows1251("Кино")).ansi)
        assertEquals(false, readImportedText("Кино é".encodeToByteArray()).ansi)
        assertEquals(false, readImportedText(utf16("Кино", littleEndian = true)).ansi)
        assertEquals(false, readImportedText("plain".encodeToByteArray()).ansi)
        // Not a Windows-1252 reading: a character that code page has no byte for.
        assertEquals(emptyList(), otherCodePageReadings("Кино"))

        val everyByte = ByteArray(256) { it.toByte() }
        val read = readImportedText(everyByte)
        assertEquals(true, read.ansi)
        val (cyrillic, central) = otherCodePageReadings(read.text)
        assertEquals(256, cyrillic.length)
        assertEquals(read.text.take(128), cyrillic.take(128))
        assertEquals("АБВ", cyrillic.substring(0xC0, 0xC3))
        assertEquals("яё№", "" + cyrillic[0xFF] + cyrillic[0xB8] + cyrillic[0xB9])
        assertEquals("ŁąşŻăţ", "" + central[0xA3] + central[0xB9] + central[0xBA] + central[0xAF] + central[0xE3] + central[0xFE])
        assertEquals('\uFFFD', cyrillic[0x98])
        assertEquals("Группа крови", otherCodePageReadings(readImportedText(windows1251("Группа крови")).text).first())
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
