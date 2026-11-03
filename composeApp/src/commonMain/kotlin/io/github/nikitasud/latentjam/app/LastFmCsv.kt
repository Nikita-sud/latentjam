/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/**
 * Streams the scrobbles of a Last.fm history saved as CSV, as tools such as "last.fm to csv" write it, into the rows the
 * ListenBrainz import matches ([ListenBrainzExportReader] hands such a file here when it is neither a ZIP nor JSON).
 *
 * The layouts are told apart by the first record:
 * - without a header, as "last.fm to csv" (benjaminbenben.com) writes it: `artist,album,name,date`, or `artist,name,date`
 *   in its first weeks, newest first. The date is Last.fm's own UTC text: `03 Nov 2026, 15:29`, which the tool wrote
 *   without the comma until 2026 (it deleted every comma and quote from names then; since, it quotes as RFC 4180 and
 *   starts the file with a byte order mark);
 * - with a header naming its columns, as other exporters write it (`uts,utc_time,artist,artist_mbid,album,album_mbid,
 *   track,track_mbid`, or `artist,album,name,date,mbid…`), in any order: only an artist, a track and one of `uts` /
 *   `utc_time` / `date` are needed.
 *
 * Fields follow RFC 4180: quoted fields may hold commas, line breaks and doubled quotes. A UTF-8 byte order mark and CRLF
 * line ends are accepted. A row without a date (the track playing when the file was saved) is no listen and is skipped;
 * a row whose date cannot be read, or that is longer than [ListenBrainzExportReader.MAX_RECORD_BYTES], is counted as
 * unreadable. Memory stays bounded by the reader's buffer and one record.
 */
internal object LastFmCsvReader {
    /**
     * Whether the input's first record reads as one of the two layouts. Looks ahead only; nothing is consumed. Text that
     * is not CSV (a binary file, a playlist) fails here instead of being scanned to its end for rows.
     */
    fun looksLikeCsv(bytes: ByteReader): Boolean {
        val window = minOf(SNIFF_BYTES, bytes.buffer.size)
        bytes.ensure(window)
        val available = bytes.available()
        var at = if (hasBom(bytes)) 3 else 0
        // Leading blank lines.
        while (at < available && (bytes.at(at) == '\r'.code || bytes.at(at) == '\n'.code)) at++
        val fields = mutableListOf<String>()
        val field = ByteBuffer()
        var quoted = false
        var ended = false
        while (at < available) {
            val c = bytes.at(at++)
            if (c == 0) return false
            if (quoted) {
                if (c == '"'.code) {
                    if (at < available && bytes.at(at) == '"'.code) {
                        field.add(c)
                        at++
                    } else {
                        quoted = false
                    }
                } else {
                    field.add(c)
                }
                continue
            }
            when (c) {
                '"'.code -> if (field.size == 0) quoted = true else field.add(c)
                ','.code -> fields += field.take()
                '\n'.code, '\r'.code -> {
                    ended = true
                    break
                }
                else -> {
                    if (c < 0x20 && c != '\t'.code) return false
                    field.add(c)
                }
            }
        }
        // A one-line file has no line end; a record cut by the window cannot be judged.
        if (!ended && available >= window) return false
        fields += field.take()
        return layoutOf(fields) != null || headerless(fields)?.let { it.date.isEmpty() || dateSeconds(it.date) != null } == true
    }

    /**
     * Reads every record after [looksLikeCsv] said yes. [onRow] gets each scrobble; [onUnreadable] each record that names
     * one but cannot be read.
     */
    fun read(bytes: ByteReader, onRow: (ListenBrainzRow) -> Unit, onUnreadable: () -> Unit) {
        if (hasBom(bytes)) bytes.skip(3)
        val records = Records(bytes)
        var layout: Layout? = null
        var first = true
        while (true) {
            val fields = records.next() ?: return
            if (fields === Records.TOO_LONG) {
                onUnreadable()
                continue
            }
            if (fields.size == 1 && fields[0].isEmpty()) continue
            if (first) {
                first = false
                layout = layoutOf(fields)
                if (layout != null) continue
            }
            val row = (layout?.let { rowOf(fields, it) } ?: if (layout == null) headerless(fields) else null)
            when {
                row == null -> onUnreadable()
                // The track playing when the file was saved: no listen yet.
                row.date.isEmpty() && row.seconds == null -> Unit
                else -> {
                    val seconds = row.seconds ?: dateSeconds(row.date)
                    if (seconds == null || seconds <= 0) {
                        onUnreadable()
                    } else {
                        onRow(
                            ListenBrainzRow(
                                listenedAtSeconds = seconds,
                                artist = row.artist,
                                title = row.title,
                                release = row.album,
                                recordingMbid = row.mbid,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** One record's scrobble before its date is read. */
    private class Scrobble(
        val artist: String?,
        val album: String?,
        val title: String?,
        val date: String,
        val seconds: Long? = null,
        val mbid: String? = null,
    )

    /** Where a header puts the columns the import reads; -1 for one it lacks. */
    private class Layout(
        val uts: Int,
        val time: Int,
        val artist: Int,
        val album: Int,
        val track: Int,
        val trackMbid: Int,
    )

    /** The layout a header record names, or null when the record is not a header. */
    private fun layoutOf(fields: List<String>): Layout? {
        val names = fields.map { it.trim().lowercase() }
        fun column(vararg wanted: String): Int = wanted.firstNotNullOfOrNull { name -> names.indexOf(name).takeIf { it >= 0 } } ?: -1
        val artist = column("artist", "artist_name")
        val track = column("track", "track_name", "title", "name")
        val uts = column("uts", "timestamp")
        val time = column("utc_time", "date", "time")
        if (artist < 0 || track < 0 || (uts < 0 && time < 0)) return null
        return Layout(uts, time, artist, column("album", "album_name"), track, column("track_mbid", "mbid", "recording_mbid"))
    }

    private fun rowOf(fields: List<String>, layout: Layout): Scrobble? {
        if (fields.size <= maxOf(layout.artist, layout.track)) return null
        fun at(index: Int): String? = fields.getOrNull(index)?.trim()?.takeIf(String::isNotEmpty)
        val seconds = at(layout.uts)?.let { ListenBrainzExportReader.listenedAtSeconds(it) ?: return null }
        return Scrobble(
            artist = at(layout.artist),
            album = at(layout.album),
            title = at(layout.track),
            date = at(layout.time).orEmpty(),
            seconds = seconds,
            mbid = at(layout.trackMbid)?.lowercase()?.takeIf { MBID.matches(it) },
        )
    }

    /**
     * The layouts without a header: `artist,album,track,date`, and `artist,track,date` from the tool's first weeks. Last.fm
     * leaves the album, and at times the artist, empty; such a listen still counts (and finds no song without an artist).
     */
    private fun headerless(fields: List<String>): Scrobble? {
        val album = when (fields.size) {
            4 -> fields[1].trim().takeIf(String::isNotEmpty)
            3 -> null
            else -> return null
        }
        val title = fields[fields.size - 2].trim().takeIf(String::isNotEmpty) ?: return null
        return Scrobble(fields[0].trim().takeIf(String::isNotEmpty), album, title, fields.last().trim())
    }

    /** Epoch seconds of a Last.fm date (`03 Nov 2026 15:29`, `03 Nov 2026, 15:29`; UTC), or epoch seconds or ISO 8601. */
    internal fun dateSeconds(text: String): Long? {
        val match = LASTFM_DATE.matchEntire(text.trim())
            ?: return ListenBrainzExportReader.listenedAtSeconds(text.trim())
        val g = match.groupValues
        val month = MONTHS.indexOf(g[2].lowercase()) + 1
        val day = g[1].toInt()
        val hour = g[4].toInt()
        val minute = g[5].toInt()
        if (month == 0 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        val iso = "${g[3]}-${month.pad()}-${day.pad()}T${hour.pad()}:${minute.pad()}:${(g[6].ifEmpty { "0" }).toInt().pad()}Z"
        return ListenBrainzExportReader.listenedAtSeconds(iso)
    }

    private fun Int.pad(): String = toString().padStart(2, '0')

    private fun hasBom(bytes: ByteReader): Boolean =
        bytes.ensure(3) && bytes.at(0) == 0xEF && bytes.at(1) == 0xBB && bytes.at(2) == 0xBF

    /**
     * RFC 4180 records over the reader. A record over [ListenBrainzExportReader.MAX_RECORD_BYTES] is skipped to its end
     * and reported as [TOO_LONG]; a quoted field that never closes ends with the input.
     */
    private class Records(private val bytes: ByteReader) {
        private val field = ByteBuffer()
        private var recordBytes = 0

        fun next(): List<String>? {
            if (bytes.peek() < 0) return null
            val fields = ArrayList<String>(8)
            field.clear()
            recordBytes = 0
            var quoted = false
            var tooLong = false
            while (true) {
                val c = bytes.next()
                if (c < 0) break
                if (++recordBytes > ListenBrainzExportReader.MAX_RECORD_BYTES && !tooLong) {
                    tooLong = true
                    field.clear()
                    fields.clear()
                }
                if (quoted) {
                    if (c == '"'.code) {
                        if (bytes.peek() == '"'.code) {
                            bytes.next()
                            if (!tooLong) field.add(c)
                        } else {
                            quoted = false
                        }
                    } else if (!tooLong) {
                        field.add(c)
                    }
                    continue
                }
                when (c) {
                    '"'.code -> if (field.size == 0) quoted = true else if (!tooLong) field.add(c)
                    ','.code -> if (!tooLong) fields += field.take()
                    '\n'.code -> break
                    '\r'.code -> {
                        if (bytes.peek() == '\n'.code) bytes.next()
                        break
                    }
                    else -> if (!tooLong) field.add(c)
                }
            }
            if (tooLong) return TOO_LONG
            fields += field.take()
            return fields
        }

        companion object {
            val TOO_LONG: List<String> = listOf("\u0000")
        }
    }

    /** A growable byte buffer decoded once per field as UTF-8 (malformed bytes become U+FFFD). */
    private class ByteBuffer {
        private var data = ByteArray(256)
        var size = 0
            private set

        fun add(byte: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = byte.toByte()
        }

        fun clear() {
            size = 0
        }

        fun take(): String = data.decodeToString(0, size).also { size = 0 }
    }

    private const val SNIFF_BYTES = 16 * 1024
    private val LASTFM_DATE = Regex("(\\d{1,2})\\s+([A-Za-z]{3})\\s+(\\d{4}),?\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val MBID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}
