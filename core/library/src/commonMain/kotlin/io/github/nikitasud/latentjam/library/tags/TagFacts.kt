/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.library.tags

/**
 * The tag facts the system scanner loses, read in ONE pass over a file's metadata.
 *
 * - [genres]: every genre the file carries (see [GenreTags] for the container rules).
 * - [artists]: the credited individuals, from the Picard `ARTISTS` convention — Vorbis
 *   `ARTISTS` fields, an ID3 `TXXX:ARTISTS` frame, or a genuinely multi-valued `TPE1`. The
 *   DISPLAY credit ("Gorillaz feat. Bootie Brown") is a different field and stays untouched;
 *   a lone display string is never guessed apart, because "feat."-cutting a band name is how
 *   taggers ruin libraries.
 * - [originalYear]: the recording's first release year (`ORIGINALDATE`/`ORIGINALYEAR`,
 *   ID3 `TDOR`/`TORY`), as opposed to the edition year the scanner reports — a 2012 remaster
 *   of a 1987 song is a 1987 song to anything reasoning about eras.
 */
public data class EmbeddedTagFacts(
    public val genres: List<String> = emptyList(),
    public val artists: List<String> = emptyList(),
    public val originalYear: Int? = null,
) {
    public val isEmpty: Boolean
        get() = genres.isEmpty() && artists.isEmpty() && originalYear == null
}

public object TagFacts {

    /**
     * Bounds that reject placeholder garbage ("0000", "9999"), nothing more. Deliberately wide:
     * classical tagging routinely puts the COMPOSITION year in the original-date field (Bach,
     * 1707 — that is the intent, keep it), and an app should not carry its own Y2K — the upper
     * bound is far enough out that nobody alive maintains this line under deadline.
     */
    private val YEAR_RANGE = 1000..2999

    private const val MAX_ARTISTS = 10

    /**
     * Container-sniffing single-pass read: FLAC block walk, ID3 prefix, or Ogg scan — the same
     * dispatch as [GenreTags.embeddedGenres], returning every fact in one file open.
     */
    public fun embedded(source: GenreTags.ByteSource): EmbeddedTagFacts? {
        val comments = GenreTags.embeddedComments(source) ?: return null
        return fromComments(comments)
    }

    /** Facts from decoded `KEY=value`-style entries (Vorbis comments or mapped ID3 frames). */
    internal fun fromComments(comments: List<Pair<String, String>>): EmbeddedTagFacts {
        val genres = ArrayList<String>()
        val artistsPlural = ArrayList<String>()
        val artistFields = ArrayList<String>()
        var originalYear: Int? = null
        for ((rawKey, value) in comments) {
            when (rawKey.uppercase()) {
                "GENRE" -> genres.add(value)
                "ARTISTS" -> artistsPlural.addAll(splitArtists(value))
                "ARTIST" -> artistFields.add(value.trim())
                "ORIGINALYEAR", "ORIGINALDATE", "TDOR", "TORY" ->
                    if (originalYear == null) originalYear = parseYear(value)
            }
        }
        // Multiple ARTIST fields are a legitimate multi-credit; a single one is the display
        // string and carries no split information.
        val artists = when {
            artistsPlural.isNotEmpty() -> artistsPlural
            artistFields.size > 1 -> artistFields
            else -> emptyList()
        }
        return EmbeddedTagFacts(
            genres = genres.flatMap { GenreTags.split(it) }.distinctBy { it.lowercase() },
            artists = artists
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .take(MAX_ARTISTS),
            originalYear = originalYear,
        )
    }

    /**
     * `TXXX:ARTISTS` values arrive NUL- or semicolon-joined; both are list separators. A space
     * never is — "Bootie Brown" is one person.
     */
    internal fun splitArtists(value: String): List<String> =
        value.split('\u0000', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    internal fun parseYear(value: String): Int? {
        val digits = value.trim().take(4)
        val year = digits.toIntOrNull() ?: return null
        return year.takeIf { it in YEAR_RANGE }
    }
}
