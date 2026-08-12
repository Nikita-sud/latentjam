/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.history

/**
 * Versioned whole-file format for recent searches.
 *
 * Queries are UTF-8 hex so a pasted newline is data rather than an accidental extra search. Files
 * written by older releases had one raw query per line; [decode] continues to accept that layout.
 */
internal object RecentSearchFileCodec {
    private const val HEADER = "LATENTJAM-RECENT-SEARCHES\t1"
    private const val MAX_FILE_CHARS = 4 * 1024 * 1024
    private const val MAX_QUERY_BYTES = 16 * 1024
    private const val MAX_QUERIES = 100
    private const val HEX = "0123456789abcdef"

    fun encode(queries: List<String>): String {
        require(queries.size <= MAX_QUERIES) { "Too many recent searches" }
        return buildString {
            append(HEADER)
            append('\n')
            queries.forEach { query ->
                if (query.length > MAX_QUERY_BYTES) error("Recent search is too large")
                val bytes = query.encodeToByteArray()
                check(bytes.size <= MAX_QUERY_BYTES) { "Recent search is too large" }
                append('s')
                bytes.forEach { byte ->
                    append(HEX[(byte.toInt() ushr 4) and 0x0f])
                    append(HEX[byte.toInt() and 0x0f])
                }
                append('\n')
            }
        }.also { check(it.length <= MAX_FILE_CHARS) { "Recent-search data is too large" } }
    }

    fun decode(contents: String): List<String> {
        if (contents.isEmpty()) return emptyList()
        check(contents.length <= MAX_FILE_CHARS) { "Recent-search data is too large" }
        val firstEnd = contents.indexOf('\n').let { if (it >= 0) it else contents.length }
        val normalizedFirstEnd = if (firstEnd > 0 && contents[firstEnd - 1] == '\r') firstEnd - 1 else firstEnd
        if (!contents.regionMatches(0, HEADER, 0, HEADER.length) || normalizedFirstEnd != HEADER.length) {
            check(!contents.startsWith(VERSIONED_HEADER_PREFIX)) {
                "Unsupported recent-search data version"
            }
            return decodeLegacy(contents)
        }

        val result = mutableListOf<String>()
        var offset = if (firstEnd < contents.length) firstEnd + 1 else contents.length
        while (offset < contents.length) {
            val newline = contents.indexOf('\n', offset)
            val rawEnd = if (newline >= 0) newline else contents.length
            val end = if (rawEnd > offset && contents[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
            check(end > offset) { "Invalid recent-search record" }
            check(result.size < MAX_QUERIES) { "Too many recent searches" }
            val encodedLength = end - offset
            check(encodedLength <= 1 + MAX_QUERY_BYTES * 2) { "Recent search is too large" }
            check(contents[offset] == 's' && encodedLength % 2 == 1) {
                "Invalid recent-search record"
            }
            val byteCount = (encodedLength - 1) / 2
            val bytes = ByteArray(byteCount) { index ->
                val high = contents[offset + 1 + index * 2].digitToIntOrNull(16)
                    ?: error("Invalid recent-search record")
                val low = contents[offset + 2 + index * 2].digitToIntOrNull(16)
                    ?: error("Invalid recent-search record")
                ((high shl 4) or low).toByte()
            }
            result += try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: Throwable) {
                error("Invalid UTF-8 in recent-search data")
            }
            offset = if (newline >= 0) newline + 1 else contents.length
        }
        return result
    }

    private fun decodeLegacy(contents: String): List<String> {
        val result = mutableListOf<String>()
        var offset = 0
        while (offset < contents.length) {
            check(result.size < MAX_QUERIES) { "Too many recent searches" }
            val newline = contents.indexOf('\n', offset)
            val rawEnd = if (newline >= 0) newline else contents.length
            val end = if (rawEnd > offset && contents[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
            check(end - offset <= MAX_QUERY_BYTES) { "Recent search is too large" }
            result += contents.substring(offset, end)
            offset = if (newline >= 0) newline + 1 else contents.length
        }
        return result
    }

    private const val VERSIONED_HEADER_PREFIX = "LATENTJAM-RECENT-SEARCHES\t"
}
