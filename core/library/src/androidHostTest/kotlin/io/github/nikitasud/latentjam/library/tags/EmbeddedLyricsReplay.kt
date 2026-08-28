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
        val source = object : GenreTags.ByteSource {
            private var position = 0
            override fun read(count: Int): ByteArray? {
                if (position + count > bytes.size) return null
                return bytes.copyOfRange(position, position + count).also { position += count }
            }

            override fun readUpTo(count: Int): ByteArray {
                val end = minOf(bytes.size, position + count)
                return bytes.copyOfRange(position, end).also { position = end }
            }

            override fun skip(count: Long): Boolean {
                if (position + count > bytes.size) return false
                position += count.toInt()
                return true
            }
        }
        val lyrics = EmbeddedLyrics.read(source)
        println("lyrics: ${lyrics?.length ?: "NULL"} chars")
        println(lyrics?.take(200))
        check(!lyrics.isNullOrBlank()) { "expected lyrics in $path" }
    }
}
