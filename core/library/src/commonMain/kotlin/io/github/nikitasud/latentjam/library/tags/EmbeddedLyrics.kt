/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * Lyrics embedded in the track's own file, across the containers the library holds.
 *
 * MP3 keeps them in the ID3 `USLT` frame; FLAC and Ogg/Opus keep them as Vorbis comments —
 * `LYRICS` (plain) or `SYNCEDLYRICS`/`LYRICS` in LRC form with `[mm:ss.xx]` stamps. The app
 * displays plain text, so a synced-only file is unstamped line by line rather than shown with
 * timestamp noise. Reading only ID3 was the old behavior, and it silently answered "no lyrics"
 * for every correctly tagged Opus album.
 */
public object EmbeddedLyrics {

    /** The lyrics text, or null when the container is unknown or carries none. */
    public fun read(source: GenreTags.ByteSource): String? {
        val magic = source.read(4) ?: return null
        return when {
            magic.contentEquals(FLAC_MAGIC) ->
                fromComments(GenreTags.flacCommentsAfterMagic(source))
            magic.contentEquals(OGG_MAGIC) ->
                fromComments(GenreTags.oggComments(magic + source.readUpTo(OGG_PREFIX_BYTES)))
            magic.size == 4 && magic[0] == 'I'.code.toByte() &&
                magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte() -> {
                val headerRest = source.read(Id3Tags.HEADER_SIZE - 4) ?: return null
                val header = magic + headerRest
                val tagLength = Id3Tags.tagLength(header) ?: return null
                if (tagLength <= header.size || tagLength > MAX_ID3_PREFIX) return null
                val body = source.read(tagLength - header.size) ?: return null
                Id3Tags.lyrics(header + body)
            }
            else -> null
        }
    }

    internal fun fromComments(comments: List<Pair<String, String>>?): String? {
        if (comments == null) return null
        var synced: String? = null
        for ((key, value) in comments) {
            when (key.uppercase()) {
                "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" -> {
                    val plain = value.trim()
                    // A "plain" field sometimes carries LRC anyway; unstamp it too.
                    if (plain.isNotEmpty()) {
                        return if (looksSynced(plain)) unstamp(plain) else plain
                    }
                }
                "SYNCEDLYRICS", "SYNCED LYRICS" ->
                    if (synced == null) synced = value.trim().takeIf { it.isNotEmpty() }
            }
        }
        return synced?.let(::unstamp)
    }

    private fun looksSynced(value: String): Boolean =
        value.lineSequence().take(3).all { line ->
            line.isBlank() || STAMP.containsMatchIn(line.trim())
        } && STAMP.containsMatchIn(value)

    /** Drops `[mm:ss.xx]` (and metadata `[ar:...]`-style) prefixes, keeping the words. */
    internal fun unstamp(value: String): String = value
        .lineSequence()
        .map { line -> line.replace(STAMP, "").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .takeIf { it.isNotEmpty() }
        ?: value

    private val STAMP = Regex("""^(\[[^\]]{1,16}\])+\s*""")

    private val FLAC_MAGIC = byteArrayOf(0x66, 0x4C, 0x61, 0x43)
    private val OGG_MAGIC = byteArrayOf(0x4F, 0x67, 0x67, 0x53)
    private const val OGG_PREFIX_BYTES = 512 * 1024
    private const val MAX_ID3_PREFIX = 8 * 1024 * 1024
}
