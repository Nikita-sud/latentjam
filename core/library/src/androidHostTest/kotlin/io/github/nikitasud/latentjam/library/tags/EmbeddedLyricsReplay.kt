/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import java.io.File
import kotlin.test.Test

/**
 * Runs the shipping lyrics reader over a real local file named by `REAL_AUDIO_FILE` — the
 * synthetic fixtures cannot prove real-encoder paging, comment casing, and padding habits.
 */
class EmbeddedLyricsReplay {

    @Test
    fun `real file lyrics replay`() {
        val path = System.getenv("REAL_AUDIO_FILE") ?: run {
            println("SKIP lyrics replay: set REAL_AUDIO_FILE")
            return
        }
        val file = File(path)
        if (!file.isFile) {
            println("SKIP lyrics replay: $path is not a file")
            return
        }
        val bytes = file.readBytes()
        val lyrics = EmbeddedLyrics.read(ByteArraySource(bytes))
        println("lyrics: ${lyrics?.lines?.size ?: "NULL"} lines, synced=${lyrics?.synced}")
        println(lyrics?.lines?.take(6)?.joinToString("\n") { "${it.timeMs ?: "-"}\t${it.text}" })
        check(lyrics != null && lyrics.text.isNotBlank()) { "expected lyrics in $path" }
    }
}
