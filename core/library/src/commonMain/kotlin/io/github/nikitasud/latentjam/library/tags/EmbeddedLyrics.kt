/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/** One line of lyrics; [timeMs] is where it starts when the file carries LRC timing, else null. */
public data class LyricLine(
    public val timeMs: Long?,
    public val text: String,
)

/** Lyrics as lines. [synced] when at least one line knows when it is sung. */
public data class Lyrics(
    public val lines: List<LyricLine>,
) {
    public val synced: Boolean
        get() = lines.any { it.timeMs != null }

    /** The plain text, one line per row, blank rows kept as stanza breaks. */
    public val text: String
        get() = lines.joinToString("\n") { it.text }
}

/**
 * Lyrics embedded in the track's own file, across the containers the library holds.
 *
 * MP3 keeps them in the ID3 `USLT` frame; FLAC and Ogg/Opus keep them as Vorbis comments —
 * `LYRICS` (plain) or `SYNCEDLYRICS`/`LYRICS` in LRC form. Any field may carry LRC: taggers
 * routinely write timed text into the plain field. Whatever the field, the LRC is parsed into
 * [Lyrics]: `[mm:ss.xx]` stamps become line times, `[ar:…]`/`[by:…]`-style ID tags are dropped
 * (an `[offset:…]` tag is applied first), a line carrying several stamps is a chorus sung
 * several times and is expanded, and word-level `<mm:ss.xx>` marks are stripped from the words.
 */
public object EmbeddedLyrics {

    /** The lyrics, or null when the container is unknown or carries none. */
    public fun read(source: GenreTags.ByteSource): Lyrics? {
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
                Id3Tags.lyrics(header + body)?.let(::parse)
            }
            else -> null
        }
    }

    /**
     * Timed text wins whichever field holds it; otherwise the plain field, then whatever the
     * synced field says even without usable stamps.
     */
    internal fun fromComments(comments: List<Pair<String, String>>?): Lyrics? {
        if (comments == null) return null
        var plain: String? = null
        var synced: String? = null
        for ((key, value) in comments) {
            when (key.uppercase()) {
                "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" ->
                    if (plain == null) plain = value.trim().takeIf { it.isNotEmpty() }
                "SYNCEDLYRICS", "SYNCED LYRICS" ->
                    if (synced == null) synced = value.trim().takeIf { it.isNotEmpty() }
            }
        }
        val parsedSynced = synced?.let(::parse)
        val parsedPlain = plain?.let(::parse)
        return listOfNotNull(parsedSynced, parsedPlain).firstOrNull { it.synced }
            ?: parsedPlain
            ?: parsedSynced
    }

    /** LRC or plain text into lines; null when nothing but ID tags and blanks remains. */
    internal fun parse(value: String): Lyrics? {
        var offsetMs = 0L
        class Raw(val times: List<Long>, val text: String)
        val raws = ArrayList<Raw>()
        for (rawLine in value.lineSequence()) {
            val line = rawLine.trim()
            val idTag = ID_TAG.matchEntire(line)
            if (idTag != null) {
                if (idTag.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = idTag.groupValues[2].trim().toLongOrNull() ?: 0L
                }
                continue
            }
            val times = ArrayList<Long>()
            var cursor = 0
            while (true) {
                val match = TIMESTAMP.matchAt(line, cursor) ?: break
                times += stampMs(match)
                cursor = match.range.last + 1
                while (cursor < line.length && line[cursor].isWhitespace()) cursor++
            }
            // Retain one original string while scanning: repeated chorus stamps must not copy
            // the entire remaining suffix once per stamp (quadratic for large embedded tags).
            raws += Raw(times, INLINE_STAMP.replace(line.substring(cursor), "").trim())
        }

        // Expand chorus stamps, keep unstamped lines beside the stamped line they follow, and
        // sort by time; LRC files are usually in order already, but a chorus written once with
        // three stamps is not.
        class Keyed(val line: LyricLine, val key: Long, val order: Int)
        val keyed = ArrayList<Keyed>()
        var lastKey = 0L
        for (raw in raws) {
            if (raw.times.isEmpty()) {
                keyed += Keyed(LyricLine(null, raw.text), lastKey, keyed.size)
            } else {
                for (time in raw.times) {
                    val shifted = when {
                        offsetMs >= time -> 0L
                        offsetMs < 0L && time > Long.MAX_VALUE + offsetMs -> Long.MAX_VALUE
                        else -> time - offsetMs
                    }
                    keyed += Keyed(LyricLine(shifted, raw.text), shifted, keyed.size)
                    // An out-of-order verse still owns the unstamped translation below it.
                    // Using the greatest timestamp seen so far attaches it to a later verse.
                    lastKey = shifted
                }
            }
        }
        val synced = keyed.any { it.line.timeMs != null }
        val ordered = if (synced) keyed.sortedWith(compareBy({ it.key }, { it.order })) else keyed
        val lines = ArrayList<LyricLine>()
        for (item in ordered) {
            val line = item.line
            // Blank unstamped rows are stanza breaks: at most one in a row, none at the edges.
            if (line.timeMs == null && line.text.isEmpty()) {
                if (lines.isNotEmpty() && lines.last().text.isNotEmpty()) lines += line
                continue
            }
            lines += line
        }
        while (lines.isNotEmpty() && lines.last().text.isEmpty() && lines.last().timeMs == null) {
            lines.removeAt(lines.lastIndex)
        }
        if (lines.none { it.text.isNotBlank() }) return null
        return Lyrics(lines)
    }

    private fun stampMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3]
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000L + seconds * 1_000L + fractionMs
    }

    /** `[mm:ss.xx]`, `[mm:ss]`, `[mm:ss:xx]`; minutes may run past 99 in long recordings. */
    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")

    /** Enhanced-LRC word timing inside a line. */
    private val INLINE_STAMP = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /** A whole-line ID tag: `[ar:…]`, `[ti:…]`, `[by:@LosslessRobot]`, `[offset:+500]`. */
    private val ID_TAG = Regex("""^\[([A-Za-z#][A-Za-z0-9_#]*):([^\]]*)\]$""")

    private val FLAC_MAGIC = byteArrayOf(0x66, 0x4C, 0x61, 0x43)
    private val OGG_MAGIC = byteArrayOf(0x4F, 0x67, 0x67, 0x53)
    private const val OGG_PREFIX_BYTES = 512 * 1024
    private const val MAX_ID3_PREFIX = 8 * 1024 * 1024
}
