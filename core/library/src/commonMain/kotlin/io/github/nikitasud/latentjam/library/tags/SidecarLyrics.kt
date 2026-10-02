/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Lyrics from a `.lrc` file sitting next to the song — `Song.mp3` beside `Song.lrc` — which is
 * how most lyrics downloaders and many desktop players leave them.
 *
 * Only decoding and the choice between the two sources live here; finding and reading the file
 * is the platform's job, because where a player may look differs by platform (a sandboxed
 * Documents folder on iOS, direct file access or folders the listener allowed on Android).
 */
public object SidecarLyrics {

    /**
     * A lyrics file is a few kilobytes; anything past this is not one, and is declined before it
     * is read in full. Readers read at most one byte more and let [decode] refuse the rest.
     */
    public const val MAX_BYTES: Int = 1024 * 1024

    /**
     * The names to look for beside [audioFileName]: its extension replaced by `.lrc`, in the two
     * spellings files actually carry. Empty for a blank name, and for a `.lrc` itself, which is
     * never the song.
     */
    public fun candidateNames(audioFileName: String): List<String> {
        if (audioFileName.isBlank()) return emptyList()
        val dot = audioFileName.lastIndexOf('.')
        if (dot > 0 && audioFileName.substring(dot + 1).equals("lrc", ignoreCase = true)) return emptyList()
        val stem = if (dot > 0) audioFileName.substring(0, dot) else audioFileName
        return listOf("$stem.lrc", "$stem.LRC")
    }

    /**
     * The file's lyrics, or null. A byte order mark names the encoding (UTF-8 or UTF-16 either
     * way round); without one only strict UTF-8 is accepted. A file saved in a legacy code page
     * such as Windows-1251 cannot be told apart from its neighbours with any confidence, and a
     * wrong guess shows the listener garbage in place of their lyrics — so it is declined, and the
     * song's embedded lyrics, if any, stay on screen. Text holding NUL is declined the same way.
     */
    public fun decode(bytes: ByteArray): Lyrics? {
        if (bytes.size > MAX_BYTES) return null
        val text = when {
            bytes.startsWith(UTF8_BOM) ->
                // The BOM settles the encoding: a stray bad byte is damage, not a code page.
                bytes.decodeToString(UTF8_BOM.size, bytes.size, throwOnInvalidSequence = false)
            bytes.startsWith(UTF16_LE_BOM) || bytes.startsWith(UTF16_BE_BOM) ->
                Id3Text.decode(Id3Text.UTF_16_WITH_BOM, bytes, 0, bytes.size)
            else -> TextRepair.decodeUtf8Strict(bytes)
        } ?: return null
        // No lyrics file holds NUL. UTF-16 saved without a BOM passes as UTF-8 — every ASCII
        // byte is valid — with a NUL between each letter, which would show as garbage.
        if ('\u0000' in text) return null
        return EmbeddedLyrics.parse(text.replace("\r\n", "\n").replace('\r', '\n'))
    }

    /**
     * Timed lyrics beat plain ones, since the player can follow them; between two of a kind the
     * sidecar wins, because the listener put that file there on purpose — often to correct what
     * the tag carries.
     */
    public fun choose(embedded: Lyrics?, sidecar: Lyrics?): Lyrics? = when {
        sidecar == null -> embedded
        embedded == null -> sidecar
        embedded.synced && !sidecar.synced -> embedded
        else -> sidecar
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}
