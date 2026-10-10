/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnindexedAudioTest {

    private val volume: File = Files.createTempDirectory("volume").toFile()
    private val card: File = Files.createTempDirectory("card").toFile()

    @AfterTest
    fun cleanUp() {
        volume.deleteRecursively()
        card.deleteRecursively()
    }

    private fun song(root: File, path: String, bytes: Int = 16): String {
        val file = File(root, path)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(bytes) { 1 })
        return file.path
    }

    private fun walk(vararg roots: File, known: Set<String> = emptySet(), limit: Int = 1_000): Set<String> =
        unindexedAudioFiles(roots.toList(), known, limit).toSet()

    @Test
    fun `audio files MediaStore lacks are found and indexed ones are left alone`() {
        val indexed = song(volume, "Music/24kGoldn/Album/01 Song.mp3")
        val missing = song(volume, "Music/Other Artist/Album/01 Song.flac")
        val elsewhere = song(volume, "My Tunes/Song.ogg")

        assertEquals(setOf(missing, elsewhere), walk(volume, known = setOf(indexed)))
    }

    @Test
    fun `a nomedia folder hides itself and every folder below it`() {
        song(volume, "Music/Voice notes/Note.mp3")
        File(volume, "Music/Voice notes/.nomedia").writeText("")
        song(volume, "Music/Voice notes/Old/Note.mp3")
        val shown = song(volume, "Music/Album/Song.mp3")

        assertEquals(setOf(shown), walk(volume))
    }

    @Test
    fun `hidden folders and hidden files are skipped`() {
        song(volume, "Music/.thumbnails/Song.mp3")
        song(volume, "Music/Album/._Song.mp3")
        val shown = song(volume, "Music/Album/Song.mp3")

        assertEquals(setOf(shown), walk(volume))
    }

    @Test
    fun `other apps data and obb are skipped but Android media is walked`() {
        song(volume, "Android/data/com.example/files/Song.mp3")
        song(volume, "Android/obb/com.example/Song.mp3")
        val media = song(volume, "Android/media/com.example.recorder/Song.m4a")

        assertEquals(setOf(media), walk(volume))
    }

    @Test
    fun `only audio extensions count and their case does not matter`() {
        song(volume, "Music/Album/cover.jpg")
        song(volume, "Music/Album/notes.txt")
        song(volume, "Music/Album/Song.lrc")
        val upper = song(volume, "Music/Album/LOUD.MP3")
        val mixed = song(volume, "Music/Album/Mixed.Flac")

        assertEquals(setOf(upper, mixed), walk(volume))
    }

    @Test
    fun `an empty file is an interrupted copy and is not handed to the scanner`() {
        song(volume, "Music/Album/Partial.flac", bytes = 0)

        assertTrue(walk(volume).isEmpty())
    }

    @Test
    fun `every volume is walked`() {
        val internal = song(volume, "Music/Song.mp3")
        val onCard = song(card, "Music/Walkman/Album/01 Track.flac")

        assertEquals(setOf(internal, onCard), walk(volume, card))
    }

    @Test
    fun `the walk stops at its limit`() {
        repeat(10) { song(volume, "Music/Album/$it.mp3") }

        assertEquals(4, unindexedAudioFiles(listOf(volume), emptySet(), limit = 4).size)
    }

    @Test
    fun `a symbolic link back up the tree does not loop`() {
        val song = song(volume, "Music/Album/Song.mp3")
        Files.createSymbolicLink(File(volume, "Music/Album/loop").toPath(), File(volume, "Music").toPath())

        assertEquals(setOf(song), walk(volume))
    }

    @Test
    fun `a missing root yields nothing`() {
        assertTrue(walk(File(volume, "not there")).isEmpty())
    }

    @Test
    fun `volume roots come from app specific directories`() {
        assertEquals(
            "/storage/emulated/0",
            storageVolumeRoot("/storage/emulated/0/Android/data/io.github.nikitasud.latentjam.kmp/files"),
        )
        assertEquals(
            "/storage/1234-ABCD",
            storageVolumeRoot("/storage/1234-ABCD/Android/data/io.github.nikitasud.latentjam.kmp/files"),
        )
        assertNull(storageVolumeRoot("/data/user/0/io.github.nikitasud.latentjam.kmp/files"))
    }

    @Test
    fun `the MediaStore selection names one pattern per extension`() {
        val (selection, arguments) = audioPathSelection("_data")

        assertEquals(arguments.size, selection.split(" OR ").size)
        assertTrue(selection.split(" OR ").all { it == "_data LIKE ?" })
        assertTrue("%.flac" in arguments && "%.mp3" in arguments && "%.opus" in arguments)
        assertTrue(arguments.all { isScannableAudio("song" + it.removePrefix("%")) })
    }
}
