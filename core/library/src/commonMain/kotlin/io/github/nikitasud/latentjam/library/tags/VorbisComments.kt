/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

import io.github.nikitasud.latentjam.library.TrackNumbers

/**
 * One `KEY=value` comment, kept as its original bytes. Entries the editor does not rewrite are
 * re-emitted from [raw], so invalid UTF-8 or odd casing in other people's tags survives exactly.
 */
internal class VorbisEntry(val raw: ByteArray) {
    private val separator = raw.indexOf('='.code.toByte())

    val isWellFormed: Boolean get() = separator > 0

    /** Field name, upper-cased: Vorbis field names are case-insensitive. */
    val key: String get() = raw.decodeToString(0, separator).uppercase()

    val value: String get() = raw.decodeToString(separator + 1, raw.size)

    companion object {
        fun of(key: String, value: String): VorbisEntry = VorbisEntry("$key=$value".encodeToByteArray())
    }
}

/** A Vorbis comment block: vendor string, then entries. No framing bit — containers add their own. */
internal class VorbisComments(val vendor: ByteArray, val entries: List<VorbisEntry>) {

    fun encode(): ByteArray {
        val out = ByteArray(8 + vendor.size + entries.sumOf { 4 + it.raw.size })
        var p = 0
        fun putLe32(value: Int) {
            out[p] = value.toByte()
            out[p + 1] = (value ushr 8).toByte()
            out[p + 2] = (value ushr 16).toByte()
            out[p + 3] = (value ushr 24).toByte()
            p += 4
        }
        putLe32(vendor.size)
        vendor.copyInto(out, p)
        p += vendor.size
        putLe32(entries.size)
        for (entry in entries) {
            putLe32(entry.raw.size)
            entry.raw.copyInto(out, p)
            p += entry.raw.size
        }
        return out
    }

    companion object {
        /** The block starting at [offset] and the offset just past it; null when it does not add up. */
        fun decode(bytes: ByteArray, offset: Int): Pair<VorbisComments, Int>? {
            var p = offset
            fun le32(): Long? {
                if (p < 0 || p + 4 > bytes.size) return null
                val value = (bytes[p].toLong() and 0xFF) or ((bytes[p + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[p + 2].toLong() and 0xFF) shl 16) or ((bytes[p + 3].toLong() and 0xFF) shl 24)
                p += 4
                return value
            }
            fun take(length: Long): ByteArray? {
                if (length > bytes.size - p) return null
                val out = bytes.copyOfRange(p, p + length.toInt())
                p += length.toInt()
                return out
            }
            val vendor = take(le32() ?: return null) ?: return null
            val count = le32() ?: return null
            if (count > (bytes.size - p) / 4L) return null
            val entries = ArrayList<VorbisEntry>(count.toInt())
            for (i in 0 until count.toInt()) {
                val entry = VorbisEntry(take(le32() ?: return null) ?: return null)
                if (!entry.isWellFormed) return null
                entries += entry
            }
            return VorbisComments(vendor, entries) to p
        }
    }
}

/** What a comment block says, in the editor's terms. */
internal class VorbisFieldValues(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: String?,
    val trackNumber: Int?,
    val trackTotal: Int?,
    val discNumber: Int?,
    val discTotal: Int?,
    val lyrics: String?,
    val artists: List<String>,
)

/** The editor's fields mapped onto Vorbis comment names, shared by FLAC, Opus and Vorbis. */
internal object VorbisFields {
    private const val TITLE = "TITLE"
    private const val ARTIST = "ARTIST"
    private const val ARTISTS = "ARTISTS"
    private const val ALBUM = "ALBUM"
    private const val ALBUM_ARTIST = "ALBUMARTIST"
    private const val GENRE = "GENRE"
    private const val DATE = "DATE"
    private const val TRACK_NUMBER = "TRACKNUMBER"
    private const val TRACK_TOTAL = "TRACKTOTAL"
    private const val DISC_NUMBER = "DISCNUMBER"
    private const val DISC_TOTAL = "DISCTOTAL"
    private const val LYRICS = "LYRICS"

    /** Other names the same field is found under; read as the field, removed when it is written. */
    private val ALIASES: Map<String, List<String>> = mapOf(
        ALBUM_ARTIST to listOf("ALBUM ARTIST", "ALBUM_ARTIST"),
        DATE to listOf("YEAR"),
        TRACK_TOTAL to listOf("TOTALTRACKS"),
        DISC_TOTAL to listOf("TOTALDISCS"),
        LYRICS to listOf("UNSYNCEDLYRICS"),
    )

    /** Every name whose value the editor owns; anything else is preserved untouched. */
    val MANAGED: Set<String> =
        setOf(TITLE, ARTIST, ARTISTS, ALBUM, ALBUM_ARTIST, GENRE, DATE, TRACK_NUMBER, TRACK_TOTAL, DISC_NUMBER, DISC_TOTAL, LYRICS) +
            ALIASES.values.flatten()

    private fun names(key: String): List<String> = listOf(key) + ALIASES[key].orEmpty()

    fun read(entries: List<VorbisEntry>): VorbisFieldValues {
        fun all(key: String): List<String> =
            names(key).flatMap { name -> entries.filter { it.key == name }.map { it.value } }.filter { it.isNotEmpty() }
        fun first(key: String): String? = all(key).firstOrNull()
        fun joined(key: String): String? = all(key).joinToString("; ").ifEmpty { null }
        val trackRaw = first(TRACK_NUMBER)
        val discRaw = first(DISC_NUMBER)
        return VorbisFieldValues(
            title = first(TITLE),
            artist = joined(ARTIST),
            album = first(ALBUM),
            albumArtist = first(ALBUM_ARTIST),
            genre = joined(GENRE),
            year = first(DATE),
            trackNumber = TrackNumbers.parse(trackRaw),
            trackTotal = TrackNumbers.parse(first(TRACK_TOTAL)) ?: embeddedTotal(trackRaw),
            discNumber = TrackNumbers.parse(discRaw),
            discTotal = TrackNumbers.parse(first(DISC_TOTAL)) ?: embeddedTotal(discRaw),
            lyrics = first(LYRICS)?.trim()?.ifEmpty { null },
            artists = all(ARTISTS).flatMap { TagFacts.splitArtists(it) },
        )
    }

    fun apply(entries: List<VorbisEntry>, edits: TagEdits): List<VorbisEntry> {
        var out = entries
        out = set(out, TITLE, edits.title)
        val hadArtists = out.any { it.key == ARTISTS }
        out = setJoined(out, ARTIST, edits.artist)
        if (edits.artist != null && hadArtists) {
            out = setMany(out, ARTISTS, CreditedArtists.fromDisplay(edits.artist))
        }
        out = set(out, ALBUM, edits.album)
        out = set(out, ALBUM_ARTIST, edits.albumArtist)
        out = setJoined(out, GENRE, edits.genre)
        out = set(out, DATE, edits.year)
        out = numbers(out, TRACK_NUMBER, TRACK_TOTAL, edits.trackNumber, edits.trackTotal)
        out = numbers(out, DISC_NUMBER, DISC_TOTAL, edits.discNumber, edits.discTotal)
        out = setLyrics(out, edits.lyrics?.trim())
        return out
    }

    /** [set] for a field read as its values joined with "; ": restating that joined text keeps every entry. */
    private fun setJoined(entries: List<VorbisEntry>, key: String, value: String?): List<VorbisEntry> {
        val current = entries.filter { it.key == key }.map { it.value }.filter { it.isNotEmpty() }.joinToString("; ")
        if (!value.isNullOrEmpty() && current == value) return entries
        return set(entries, key, value)
    }

    /** [set] for the lyrics, which read trimmed: a lone entry that reads as [value] is kept as stored. */
    private fun setLyrics(entries: List<VorbisEntry>, value: String?): List<VorbisEntry> {
        val stored = entries.filter { it.key in names(LYRICS) }
        if (!value.isNullOrEmpty() && stored.size == 1 && stored[0].key == LYRICS && stored[0].value.trim() == value) {
            return entries
        }
        return set(entries, LYRICS, value)
    }

    private fun embeddedTotal(pair: String?): Int? =
        pair?.substringAfter('/', "")?.takeIf { it.isNotEmpty() }?.let(TrackNumbers::parse)

    /** Null keeps; "" removes; anything else becomes the single value of [key]. */
    private fun set(entries: List<VorbisEntry>, key: String, value: String?): List<VorbisEntry> = when {
        value == null -> entries
        value.isEmpty() -> setMany(entries, key, emptyList())
        else -> setMany(entries, key, listOf(value))
    }

    /** Replaces every entry of [key] (and its aliases) with [values], at the first one's position. */
    private fun setMany(entries: List<VorbisEntry>, key: String, values: List<String>): List<VorbisEntry> {
        val doomed = names(key).toSet()
        // Entries that already are exactly these values, in this order, stay where they are.
        val existing = entries.filter { it.key in doomed }
        if (existing.size == values.size && existing.indices.all { existing[it].key == key && existing[it].value == values[it] }) {
            return entries
        }
        val out = ArrayList<VorbisEntry>(entries.size + values.size)
        var placed = false
        for (entry in entries) {
            if (entry.key !in doomed) {
                out += entry
                continue
            }
            if (!placed) {
                values.forEach { out += reuse(entries, key, it) }
                placed = true
            }
        }
        if (!placed) values.forEach { out += reuse(entries, key, it) }
        return out
    }

    /** An existing [key] entry already saying [value] is kept byte for byte (its key's casing included). */
    private fun reuse(entries: List<VorbisEntry>, key: String, value: String): VorbisEntry =
        entries.firstOrNull { it.key == key && it.value == value } ?: VorbisEntry.of(key, value)

    private fun numbers(
        entries: List<VorbisEntry>,
        numberKey: String,
        totalKey: String,
        number: String?,
        total: String?,
    ): List<VorbisEntry> {
        if (number == null && total == null) return entries
        val currentRaw = entries.firstOrNull { it.key == numberKey }?.value
        val embedded = embeddedTotal(currentRaw)
        val totals = entries.filter { it.key in names(totalKey) }
        val hasTotalField = totals.isNotEmpty()
        // A half that already reads as the requested number ("03" for 3) is left as written.
        val sameNumber = !number.isNullOrEmpty() && entries.count { it.key == numberKey } == 1 &&
            TrackNumbers.parse(currentRaw) == TagNumbers.strict(number)
        val sameTotal = !total.isNullOrEmpty() && TagNumbers.strict(total).let { wanted ->
            if (hasTotalField) {
                totals.size == 1 && totals[0].key == totalKey && TrackNumbers.parse(totals[0].value) == wanted
            } else {
                embedded == wanted
            }
        }
        return numbersChanged(entries, numberKey, totalKey, if (sameNumber) null else number, if (sameTotal) null else total)
    }

    private fun numbersChanged(
        entries: List<VorbisEntry>,
        numberKey: String,
        totalKey: String,
        number: String?,
        total: String?,
    ): List<VorbisEntry> {
        if (number == null && total == null) return entries
        val currentRaw = entries.firstOrNull { it.key == numberKey }?.value
        val embedded = embeddedTotal(currentRaw)
        val hasTotalField = entries.any { it.key in names(totalKey) }
        var out = entries
        if (number != null) {
            out = set(out, numberKey, number)
            // "3/12" carried its total inside; keep it when the total itself was not edited.
            if (total == null && embedded != null && !hasTotalField) out = set(out, totalKey, embedded.toString())
        } else if (currentRaw != null && currentRaw.contains('/')) {
            // The total is about to live in its own field: the pair must not keep a stale copy.
            TrackNumbers.parse(currentRaw)?.let { out = set(out, numberKey, it.toString()) }
        }
        if (total != null) out = set(out, totalKey, total)
        return out
    }
}

internal fun VorbisFieldValues.toSnapshot(
    format: TagFormat,
    version: String,
    cover: CoverInfo?,
    otherPictures: Int,
    nextCover: CoverInfo?,
    pictures: List<Long>,
): TagSnapshot = TagSnapshot(
    format = format,
    version = version,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    genre = genre,
    year = year,
    trackNumber = trackNumber,
    trackTotal = trackTotal,
    discNumber = discNumber,
    discTotal = discTotal,
    lyrics = lyrics,
    cover = cover,
    otherPictures = otherPictures,
    nextCover = nextCover,
    pictures = pictures,
    artists = artists,
)
