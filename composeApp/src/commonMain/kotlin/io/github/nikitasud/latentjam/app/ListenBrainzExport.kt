/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

/** A pull source of bytes. [read] returns how many bytes it wrote, or -1 at the end. */
internal fun interface ExportByteInput {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** An opened export: bytes to read and a handle to close. */
internal interface ExportByteSource : ExportByteInput {
    fun close()
}

/** One listen as a ListenBrainz export states it. Every text field is exactly as exported. */
internal data class ListenBrainzRow(
    /** `listened_at`, epoch seconds. */
    val listenedAtSeconds: Long,
    val artist: String?,
    val title: String?,
    val release: String?,
    /** `additional_info.duration_ms`, or `additional_info.duration` in seconds; null when absent. */
    val durationMs: Long? = null,
    /** `additional_info.recording_mbid` or `mbid_mapping.recording_mbid`, lowercase. */
    val recordingMbid: String? = null,
    /** MusicBrainz's own names for the recording (`mbid_mapping`), when ListenBrainz mapped it. */
    val mappedArtist: String? = null,
    val mappedTitle: String? = null,
)

/** What a read found besides the rows it handed over. */
internal data class ListenBrainzReadSummary(
    /** Rows handed to the callback. */
    val rows: Long,
    /** Lines or records that could not be read as a listen and were skipped. */
    val unreadable: Long,
    /** True when the read stopped at [ListenBrainzExportReader.MAX_ROWS]. */
    val truncated: Boolean,
)

internal class ListenBrainzFormatException(message: String) : IllegalArgumentException(message)

/**
 * Streams the listens of a ListenBrainz export without holding it: the ZIP the site exports today
 * (`listens/<year>/<month>.jsonl`, next to `user.json`, `feedback.jsonl` and pinned recordings,
 * which are skipped), a plain JSON Lines file, or the older single JSON array. The format is told
 * from the first bytes, not from the file name, because document providers rename files.
 *
 * Memory stays bounded by one buffer, one inflater window and one record ([MAX_RECORD_BYTES]):
 * exports run to hundreds of megabytes and millions of rows. The ZIP is read front to back from its
 * local headers, so a provider stream that cannot seek is enough; entries written with a trailing
 * data descriptor are supported for DEFLATE, the only method a streaming writer uses with one.
 */
internal object ListenBrainzExportReader {
    /** Rows read at most; a heavy listener's lifetime export is well under a million. */
    const val MAX_ROWS: Long = 5_000_000L

    /** Bytes one JSON record may take. A listen is about half a kilobyte. */
    const val MAX_RECORD_BYTES: Int = 1 shl 20

    /** Uncompressed bytes read at most across every entry, against decompression bombs. */
    const val MAX_UNCOMPRESSED_BYTES: Long = 8L shl 30

    /** ZIP entries read at most. */
    const val MAX_ZIP_ENTRIES: Int = 100_000

    fun read(input: ExportByteInput, onRow: (ListenBrainzRow) -> Unit): ListenBrainzReadSummary {
        val state = ReadState(onRow)
        val bytes = ByteReader(input)
        try {
            if (bytes.ensure(4) && bytes.le32(0) == ZIP_LOCAL) {
                readZip(bytes, state)
            } else {
                if (!startsLikeJson(bytes)) throw ListenBrainzFormatException("Not a ZIP or JSON file")
                JsonRecords(bytes, state).readAll()
            }
        } catch (_: RowLimitReached) {
            return ListenBrainzReadSummary(state.rows, state.unreadable, truncated = true)
        }
        return ListenBrainzReadSummary(state.rows, state.unreadable, truncated = false)
    }

    /**
     * Whether the first significant byte opens a JSON object or array. A wrongly picked file (a
     * video, an audio file) would otherwise be scanned to its end, with no rows to report progress.
     * Empty input passes: it simply holds no listens.
     */
    private fun startsLikeJson(bytes: ByteReader): Boolean {
        var at = 0
        while (at < JSON_SNIFF_BYTES && bytes.ensure(at + 1)) {
            when (bytes.buffer[bytes.position + at].toInt() and 0xFF) {
                0x20, 0x09, 0x0A, 0x0D, 0xEF, 0xBB, 0xBF -> at++
                '{'.code, '['.code -> return true
                else -> return false
            }
        }
        return true
    }

    private const val JSON_SNIFF_BYTES = 4096

    // ------------------------------------------------------------------------------------ ZIP

    private const val ZIP_LOCAL = 0x04034b50L
    private const val ZIP_CENTRAL = 0x02014b50L
    private const val ZIP_END = 0x06054b50L
    private const val ZIP64_END = 0x06064b50L
    private const val ZIP_DESCRIPTOR = 0x08074b50L

    private fun readZip(bytes: ByteReader, state: ReadState) {
        var entries = 0
        while (bytes.ensure(4)) {
            when (bytes.le32(0)) {
                ZIP_LOCAL -> Unit
                ZIP_CENTRAL, ZIP_END, ZIP64_END -> return
                else -> throw ListenBrainzFormatException("Unexpected data inside the ZIP")
            }
            if (++entries > MAX_ZIP_ENTRIES) throw ListenBrainzFormatException("The ZIP holds too many files")
            if (!bytes.ensure(30)) throw ListenBrainzFormatException("Truncated ZIP header")
            val flags = bytes.le16(6)
            val method = bytes.le16(8)
            var compressedSize = bytes.le32(18)
            var size = bytes.le32(22)
            val nameLength = bytes.le16(26)
            val extraLength = bytes.le16(28)
            bytes.skip(30)
            if (!bytes.ensure(nameLength)) throw ListenBrainzFormatException("Truncated ZIP header")
            val name = bytes.buffer.decodeToString(bytes.position, bytes.position + nameLength)
            bytes.skip(nameLength.toLong())
            // The ZIP64 extra field carries the real sizes when the header says 0xFFFFFFFF.
            var zip64 = false
            if (!bytes.ensure(extraLength)) throw ListenBrainzFormatException("Truncated ZIP header")
            var at = 0
            while (at + 4 <= extraLength) {
                val id = bytes.le16(at)
                val length = bytes.le16(at + 2)
                if (id == 0x0001) {
                    zip64 = true
                    var field = at + 4
                    if (size == 0xFFFFFFFFL && field + 8 <= at + 4 + length) {
                        size = bytes.le64(field)
                        field += 8
                    }
                    if (compressedSize == 0xFFFFFFFFL && field + 8 <= at + 4 + length) {
                        compressedSize = bytes.le64(field)
                    }
                }
                at += 4 + length
            }
            bytes.skip(extraLength.toLong())
            if (flags and 0x1 != 0) throw ListenBrainzFormatException("The ZIP is encrypted")
            val descriptor = flags and 0x8 != 0
            val wanted = isListenEntry(name)
            when (method) {
                0 -> {
                    if (descriptor) throw ListenBrainzFormatException("Unsupported ZIP layout")
                    if (wanted) {
                        val entry = StoredEntry(bytes, compressedSize)
                        JsonRecords(ByteReader(CountingInput(entry, state)), state).readAll()
                        entry.drain()
                    } else {
                        bytes.skip(compressedSize)
                    }
                }
                8 -> {
                    if (!wanted && !descriptor) {
                        bytes.skip(compressedSize)
                    } else {
                        val entry = DeflatedEntry(bytes, if (descriptor) null else compressedSize)
                        try {
                            if (wanted) {
                                JsonRecords(ByteReader(CountingInput(entry, state)), state).readAll()
                            }
                            entry.drain(state)
                        } finally {
                            entry.close()
                        }
                    }
                }
                else -> {
                    if (descriptor) throw ListenBrainzFormatException("Unsupported ZIP compression")
                    // Another method on a file we would skip anyway costs nothing; on a listen file
                    // it cannot be read.
                    if (wanted) throw ListenBrainzFormatException("Unsupported ZIP compression")
                    bytes.skip(compressedSize)
                }
            }
            if (descriptor) skipDescriptor(bytes, zip64)
        }
    }

    /** The data descriptor after an entry: optional signature, CRC, then 32- or 64-bit sizes. */
    private fun skipDescriptor(bytes: ByteReader, zip64: Boolean) {
        if (bytes.ensure(4) && bytes.le32(0) == ZIP_DESCRIPTOR) bytes.skip(4)
        bytes.skip(4)
        // A writer that did not know the size up front may still have used 64-bit sizes; the next
        // signature tells which.
        val small = if (zip64) 16 else 8
        val large = if (zip64) 8 else 16
        fun signatureAt(offset: Int): Boolean = bytes.ensure(offset + 4) && bytes.le32(offset).let {
            it == ZIP_LOCAL || it == ZIP_CENTRAL || it == ZIP_END || it == ZIP64_END
        }
        bytes.skip(if (!signatureAt(small) && signatureAt(large)) large.toLong() else small.toLong())
    }

    /** Listen files: any JSON document except the account, feedback and pin files beside them. */
    internal fun isListenEntry(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith("/")) return false
        if (!lower.endsWith(".jsonl") && !lower.endsWith(".json")) return false
        val file = lower.substringAfterLast('/')
        if (file.startsWith("._") || lower.startsWith("__macosx/")) return false
        return IGNORED_FILE_PREFIXES.none { file.startsWith(it) }
    }

    private val IGNORED_FILE_PREFIXES = listOf("user", "feedback", "pinned")

    private class StoredEntry(private val bytes: ByteReader, size: Long) : ExportByteInput {
        private var left = size

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0L) return -1
            if (bytes.available() == 0 && !bytes.fill()) throw ListenBrainzFormatException("Truncated ZIP entry")
            val count = minOf(length.toLong(), left, bytes.available().toLong()).toInt()
            bytes.buffer.copyInto(buffer, offset, bytes.position, bytes.position + count)
            bytes.position += count
            left -= count
            return count
        }

        fun drain() {
            if (left > 0) bytes.skip(left)
            left = 0
        }
    }

    /**
     * A DEFLATE entry read straight from the shared buffer. Only the bytes the inflater actually
     * consumed advance the buffer, so the next local header is exactly where the stream ended —
     * which is how an entry without a known size finds its end.
     */
    private class DeflatedEntry(private val bytes: ByteReader, private val compressedSize: Long?) : ExportByteInput {
        private val inflater = RawInflater()
        private var given = 0
        private var accounted = 0
        private var consumedTotal = 0L

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (true) {
                if (inflater.finished) return -1
                val written = inflater.inflate(buffer, offset, length)
                account()
                if (written > 0) return written
                if (inflater.finished) return -1
                if (!inflater.needsInput) throw ListenBrainzFormatException("Corrupt ZIP entry")
                feed()
            }
        }

        private fun feed() {
            val left = compressedSize?.let { it - consumedTotal }
            if (left != null && left <= 0L) throw ListenBrainzFormatException("Corrupt ZIP entry")
            if (bytes.available() == 0 && !bytes.fill()) throw ListenBrainzFormatException("Truncated ZIP entry")
            val count = if (left == null) bytes.available() else minOf(left, bytes.available().toLong()).toInt()
            inflater.setInput(bytes.buffer, bytes.position, count)
            given = count
            accounted = 0
        }

        private fun account() {
            val consumed = given - inflater.remaining
            val delta = consumed - accounted
            if (delta > 0) {
                bytes.position += delta
                consumedTotal += delta
                accounted = consumed
            }
        }

        fun drain(state: ReadState) {
            val scratch = ByteArray(16 * 1024)
            while (true) {
                val count = read(scratch, 0, scratch.size)
                if (count < 0) break
                state.countUncompressed(count)
            }
            // A header that states the size is the authority on where the next entry starts.
            if (compressedSize != null && compressedSize > consumedTotal) bytes.skip(compressedSize - consumedTotal)
        }

        fun close() = inflater.close()
    }

    // ----------------------------------------------------------------------------------- JSON

    private class RowLimitReached : RuntimeException()

    private class ReadState(private val onRow: (ListenBrainzRow) -> Unit) {
        var rows = 0L
        var unreadable = 0L
        private var uncompressed = 0L

        fun countUncompressed(count: Int) {
            uncompressed += count
            if (uncompressed > MAX_UNCOMPRESSED_BYTES) throw ListenBrainzFormatException("The export is too large")
        }

        fun value(value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    val row = rowOf(value)
                    when {
                        row != null -> emit(row)
                        // The API's own envelope: {"payload": {"listens": [...]}}.
                        (value["payload"] as? Map<*, *>)?.get("listens") is List<*> ->
                            ((value["payload"] as Map<*, *>)["listens"] as List<*>).forEach(::value)
                        // Not a listen (an account or feedback record): ignored, not unreadable.
                        else -> if ("listened_at" in value) unreadable++
                    }
                }
                is List<*> -> value.forEach(::value)
                else -> unreadable++
            }
        }

        private fun emit(row: ListenBrainzRow) {
            if (rows >= MAX_ROWS) throw RowLimitReached()
            rows++
            onRow(row)
        }
    }

    /** Counts what an entry decodes against [MAX_UNCOMPRESSED_BYTES]. */
    private class CountingInput(private val source: ExportByteInput, private val state: ReadState) : ExportByteInput {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            source.read(buffer, offset, length).also { if (it > 0) state.countUncompressed(it) }
    }

    /**
     * The records of one JSON document: the elements of a top-level array, or every top-level
     * value of a JSON Lines file. A JSON Lines line that cannot be parsed is counted and skipped;
     * an array that breaks cannot be resynchronised and fails the read.
     */
    private class JsonRecords(private val bytes: ByteReader, private val state: ReadState) {
        private val parser = JsonParser(bytes)

        fun readAll() {
            // A UTF-8 byte order mark.
            if (bytes.ensure(3) && bytes.at(0) == 0xEF && bytes.at(1) == 0xBB && bytes.at(2) == 0xBF) bytes.skip(3)
            parser.skipWhitespace()
            if (bytes.peek() == '['.code) readArray() else readLines()
        }

        private fun readArray() {
            bytes.next()
            parser.skipWhitespace()
            if (bytes.peek() == ']'.code) return
            while (true) {
                parser.startRecord()
                state.value(parser.value(0))
                parser.skipWhitespace()
                when (bytes.next()) {
                    ','.code -> parser.skipWhitespace()
                    ']'.code -> return
                    else -> throw ListenBrainzFormatException("Malformed JSON array")
                }
            }
        }

        private fun readLines() {
            while (true) {
                parser.skipWhitespace()
                if (bytes.peek() < 0) return
                val parsed = try {
                    parser.startRecord()
                    parser.value(0)
                } catch (malformed: JsonSyntaxError) {
                    state.unreadable++
                    parser.skipLine()
                    continue
                }
                state.value(parsed)
            }
        }
    }

    private class JsonSyntaxError(message: String) : IllegalArgumentException(message)

    /**
     * A small JSON value parser over bytes. Structural characters are ASCII, so it never decodes
     * text except the contents of strings, which it collects as UTF-8 and decodes once.
     */
    private class JsonParser(private val bytes: ByteReader) {
        private var text = ByteArray(256)
        private var recordStart = 0L

        fun startRecord() {
            recordStart = bytes.consumed
        }

        private fun budget() {
            if (bytes.consumed - recordStart > MAX_RECORD_BYTES) throw JsonSyntaxError("Record too large")
        }

        fun skipWhitespace() {
            while (true) {
                when (bytes.peek()) {
                    ' '.code, '\n'.code, '\r'.code, '\t'.code -> bytes.next()
                    else -> return
                }
            }
        }

        fun skipLine() {
            while (true) {
                val c = bytes.next()
                if (c < 0 || c == '\n'.code) return
            }
        }

        fun value(depth: Int): Any? {
            if (depth > 64) throw JsonSyntaxError("Nested too deep")
            budget()
            skipWhitespace()
            return when (val c = bytes.peek()) {
                '{'.code -> obj(depth)
                '['.code -> array(depth)
                '"'.code -> string()
                't'.code -> literal("true", true)
                'f'.code -> literal("false", false)
                'n'.code -> literal("null", null)
                else -> if (c == '-'.code || c in '0'.code..'9'.code) number() else throw JsonSyntaxError("Unexpected character")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            bytes.next()
            val result = HashMap<String, Any?>()
            skipWhitespace()
            if (bytes.peek() == '}'.code) {
                bytes.next()
                return result
            }
            while (true) {
                skipWhitespace()
                if (bytes.peek() != '"'.code) throw JsonSyntaxError("Expected a key")
                val key = string()
                skipWhitespace()
                if (bytes.next() != ':'.code) throw JsonSyntaxError("Expected a colon")
                result[key] = value(depth + 1)
                skipWhitespace()
                when (bytes.next()) {
                    ','.code -> continue
                    '}'.code -> return result
                    else -> throw JsonSyntaxError("Malformed object")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            bytes.next()
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (bytes.peek() == ']'.code) {
                bytes.next()
                return result
            }
            while (true) {
                result += value(depth + 1)
                skipWhitespace()
                when (bytes.next()) {
                    ','.code -> continue
                    ']'.code -> return result
                    else -> throw JsonSyntaxError("Malformed array")
                }
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            for (char in word) if (bytes.next() != char.code) throw JsonSyntaxError("Unexpected literal")
            return result
        }

        private fun number(): Any {
            size = 0
            var integral = true
            while (true) {
                val c = bytes.peek()
                val part = c == '-'.code || c == '+'.code || c in '0'.code..'9'.code
                val fraction = c == '.'.code || c == 'e'.code || c == 'E'.code
                if (!part && !fraction) break
                if (fraction) integral = false
                if (size >= 64) throw JsonSyntaxError("Number too long")
                append(bytes.next())
            }
            val literal = text.decodeToString(0, size)
            if (integral) literal.toLongOrNull()?.let { return it }
            return literal.toDoubleOrNull() ?: throw JsonSyntaxError("Malformed number")
        }

        private var size = 0

        private fun append(byte: Int) {
            if (size == text.size) text = text.copyOf(text.size * 2)
            text[size++] = byte.toByte()
        }

        private fun appendCodePoint(codePoint: Int) {
            when {
                codePoint < 0x80 -> append(codePoint)
                codePoint < 0x800 -> {
                    append(0xC0 or (codePoint shr 6))
                    append(0x80 or (codePoint and 0x3F))
                }
                codePoint < 0x10000 -> {
                    append(0xE0 or (codePoint shr 12))
                    append(0x80 or ((codePoint shr 6) and 0x3F))
                    append(0x80 or (codePoint and 0x3F))
                }
                else -> {
                    append(0xF0 or (codePoint shr 18))
                    append(0x80 or ((codePoint shr 12) and 0x3F))
                    append(0x80 or ((codePoint shr 6) and 0x3F))
                    append(0x80 or (codePoint and 0x3F))
                }
            }
        }

        private fun hex4(): Int {
            var value = 0
            repeat(4) {
                val c = bytes.next()
                val digit = when (c) {
                    in '0'.code..'9'.code -> c - '0'.code
                    in 'a'.code..'f'.code -> c - 'a'.code + 10
                    in 'A'.code..'F'.code -> c - 'A'.code + 10
                    else -> throw JsonSyntaxError("Malformed escape")
                }
                value = value * 16 + digit
            }
            return value
        }

        fun string(): String {
            bytes.next()
            size = 0
            while (true) {
                val c = bytes.next()
                when {
                    c < 0 -> throw JsonSyntaxError("Unterminated string")
                    c == '"'.code -> break
                    c == '\\'.code -> when (bytes.next()) {
                        '"'.code -> append('"'.code)
                        '\\'.code -> append('\\'.code)
                        '/'.code -> append('/'.code)
                        'b'.code -> append(8)
                        'f'.code -> append(12)
                        'n'.code -> append('\n'.code)
                        'r'.code -> append('\r'.code)
                        't'.code -> append('\t'.code)
                        'u'.code -> {
                            val unit = hex4()
                            if (unit in 0xD800..0xDBFF && bytes.peek() == '\\'.code) {
                                bytes.next()
                                if (bytes.next() != 'u'.code) throw JsonSyntaxError("Malformed escape")
                                val low = hex4()
                                if (low in 0xDC00..0xDFFF) {
                                    appendCodePoint(0x10000 + ((unit - 0xD800) shl 10) + (low - 0xDC00))
                                } else {
                                    appendCodePoint(0xFFFD)
                                    appendCodePoint(if (low in 0xD800..0xDFFF) 0xFFFD else low)
                                }
                            } else {
                                appendCodePoint(if (unit in 0xD800..0xDFFF) 0xFFFD else unit)
                            }
                        }
                        else -> throw JsonSyntaxError("Malformed escape")
                    }
                    c < 0x20 -> throw JsonSyntaxError("Control character in a string")
                    else -> append(c)
                }
                if ((size and 0xFFF) == 0) budget()
            }
            budget()
            return text.decodeToString(0, size)
        }
    }

    // ---------------------------------------------------------------------------------- rows

    private val MBID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** The listen a record describes, or null when it is not one. */
    internal fun rowOf(record: Map<*, *>): ListenBrainzRow? {
        val at = listenedAtSeconds(record["listened_at"]) ?: return null
        val metadata = record["track_metadata"] as? Map<*, *> ?: return null
        val info = metadata["additional_info"] as? Map<*, *>
        val mapping = (metadata["mbid_mapping"] ?: record["mbid_mapping"]) as? Map<*, *>
        val durationMs = (info?.get("duration_ms") as? Number)?.toLong()?.takeIf { it > 0 }
            ?: (info?.get("duration") as? Number)?.toDouble()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        val mbid = sequenceOf(info?.get("recording_mbid"), mapping?.get("recording_mbid"), record["recording_mbid"])
            .mapNotNull { (it as? String)?.trim()?.lowercase() }
            .firstOrNull { MBID.matches(it) }
        val mappedArtist = (mapping?.get("artist_credit_name") as? String) ?: (mapping?.get("artists") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("") { (it["artist_credit_name"] as? String).orEmpty() + (it["join_phrase"] as? String).orEmpty() }
        return ListenBrainzRow(
            listenedAtSeconds = at,
            artist = metadata["artist_name"] as? String,
            title = metadata["track_name"] as? String,
            release = metadata["release_name"] as? String,
            durationMs = durationMs,
            recordingMbid = mbid,
            mappedArtist = mappedArtist?.takeIf(String::isNotBlank),
            mappedTitle = (mapping?.get("recording_name") as? String)?.takeIf(String::isNotBlank),
        )
    }

    /** Epoch seconds from a number (seconds, or milliseconds when that large), digits or ISO 8601. */
    internal fun listenedAtSeconds(value: Any?): Long? {
        val seconds = when (value) {
            is Long -> value
            is Double -> value.toLong()
            is String -> value.trim().let { it.toLongOrNull() ?: isoSeconds(it) }
            else -> null
        } ?: return null
        val normalized = if (seconds > 100_000_000_000L) seconds / 1000 else seconds
        return normalized.takeIf { it > 0 }
    }

    private val ISO = Regex(
        "(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,]\\d+)?)?\\s*(Z|[+-]\\d{2}(?::?\\d{2})?)?",
        RegexOption.IGNORE_CASE,
    )

    private fun isoSeconds(text: String): Long? {
        val match = ISO.matchEntire(text) ?: return null
        val g = match.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val days = daysFromCivil(year, month, day)
        var seconds = days * 86_400L + g[4].toInt() * 3600L + g[5].toInt() * 60L + (g[6].toIntOrNull() ?: 0)
        val zone = g[7]
        if (zone.isNotEmpty() && !zone.equals("z", ignoreCase = true)) {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.drop(1).replace(":", "")
            val offset = digits.take(2).toInt() * 3600 + (digits.drop(2).toIntOrNull() ?: 0) * 60
            seconds -= sign * offset
        }
        return seconds
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}

/**
 * A refillable window over an [ExportByteInput] with look-ahead, for headers that must be read
 * before deciding what follows.
 */
internal class ByteReader(private val input: ExportByteInput, capacity: Int = 64 * 1024) {
    val buffer = ByteArray(capacity)
    var position = 0
    private var limit = 0
    private var ended = false
    private var discarded = 0L

    /** Bytes taken from the reader so far. */
    val consumed: Long get() = discarded + position

    fun available(): Int = limit - position

    /** Reads more after compacting; false at the end of the input. */
    fun fill(): Boolean {
        if (ended) return false
        if (position > 0) {
            buffer.copyInto(buffer, 0, position, limit)
            limit -= position
            discarded += position
            position = 0
        }
        if (limit == buffer.size) return true
        while (true) {
            val count = input.read(buffer, limit, buffer.size - limit)
            if (count < 0) {
                ended = true
                return false
            }
            if (count > 0) {
                limit += count
                return true
            }
        }
    }

    /** Whether [count] bytes can be looked at from [position]. */
    fun ensure(count: Int): Boolean {
        require(count <= buffer.size)
        while (available() < count) if (!fill()) return available() >= count
        return true
    }

    fun peek(): Int = if (ensure(1)) buffer[position].toInt() and 0xFF else -1

    fun next(): Int = if (ensure(1)) buffer[position++].toInt() and 0xFF else -1

    fun at(offset: Int): Int = buffer[position + offset].toInt() and 0xFF

    fun skip(count: Long) {
        var left = count
        while (left > 0) {
            if (available() == 0 && !fill()) throw ListenBrainzFormatException("Unexpected end of the export")
            val step = minOf(left, available().toLong()).toInt()
            position += step
            left -= step
        }
    }

    fun le16(offset: Int): Int = at(offset) or (at(offset + 1) shl 8)

    fun le32(offset: Int): Long = le16(offset).toLong() or (le16(offset + 2).toLong() shl 16)

    fun le64(offset: Int): Long = le32(offset) or (le32(offset + 4) shl 32)
}
