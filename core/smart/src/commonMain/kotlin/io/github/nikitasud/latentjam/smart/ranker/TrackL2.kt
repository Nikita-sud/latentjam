/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.text.UnicodeForm
import io.github.nikitasud.latentjam.smart.text.normalizeUnicode

/**
 * PLAN_L2's genre families (serving/taxonomy.py, asset `genre_families.json`): one function for MusicBrainz genre
 * names, pack artist genres and the file's genre tag, so training and serving map a genre the same way. Twelve
 * families; a genre maps to the family of the first phrase found on its word tokens (specific phrases first), else a
 * suffix rule on one word (synthpop, glitchhop, …), else the MusicBrainz genre tree's nearest mapped ancestor.
 */
public class GenreFamilies private constructor(
    /** The families in code order (artist_family_l2.bin's float codes index this list). */
    public val families: List<String>,
    private val phrases: List<Pair<List<String>, String>>,
    private val suffixes: List<Pair<String, String>>,
    private val tree: Map<String, String>,
) {
    /** taxonomy.family: the family of one genre string, or null. */
    public fun family(genre: String?): String? {
        val t = tokens(genre)
        if (t.isEmpty()) return null
        for ((p, f) in phrases) {
            val n = p.size
            if (p.last().endsWith("symphon")) {
                if (t.any { it.startsWith("symphon") }) return f
                continue
            }
            for (i in 0..t.size - n) if (t.subList(i, i + n) == p) return f
        }
        for (w in t) for ((suffix, f) in suffixes) if (w.endsWith(suffix) && w.length > suffix.length) return f
        return tree[t.joinToString(" ")]
    }

    /** taxonomy.family_of_tag: a file genre tag split on ; / , | — the family of the first part that maps. */
    public fun familyOfTag(tag: String?): String? {
        for (part in (tag ?: "").split(';', '/', ',', '|')) family(part.trim())?.let { return it }
        return null
    }

    public companion object {
        /** taxonomy.tokens: lowercase, & → and, apostrophes dropped, anything not a letter or digit splits. */
        internal fun tokens(s: String?): List<String> = tokensOf((s ?: "").lowercase().replace("&", " and ").replace("'", ""))

        /** The runs of letters and digits in [text]. */
        internal fun tokensOf(text: String): List<String> {
            val out = ArrayList<String>()
            val word = StringBuilder()
            for (c in text) {
                if (c.isLetterOrDigit()) word.append(c) else if (word.isNotEmpty()) { out += word.toString(); word.clear() }
            }
            if (word.isNotEmpty()) out += word.toString()
            return out
        }

        /** Null for a missing or malformed asset: the L2 track family then stays unknown. */
        @Suppress("UNCHECKED_CAST")
        public fun parse(json: String): GenreFamilies? = runCatching {
            val root = MiniJson(json).parse() as Map<String, Any?>
            GenreFamilies(
                families = (root["families"] as List<*>).map { it as String },
                phrases = (root["phrases"] as List<*>).map { e -> (e as List<*>).let { tokens(it[0] as String) to it[1] as String } },
                suffixes = (root["suffixes"] as List<*>).map { e -> (e as List<*>).let { it[0] as String to it[1] as String } },
                tree = (root["tree"] as Map<*, *>).entries.associate { (k, v) -> k as String to v as String },
            ).takeIf { it.families.isNotEmpty() && it.phrases.isNotEmpty() }
        }.getOrNull()
    }
}

/**
 * PLAN_L2's album fields (norm.py's `basic` / `title_key`, l2_features.album_key): the album key is the normalised album
 * title plus the album artist (else the track artist); no key for an empty or placeholder album.
 */
public object AlbumKeys {
    private val PAREN = Regex("[(\\[{][^)\\]}]*[)\\]}]")
    private val DASH_SUFFIX = Regex("\\s+[-–—]\\s+.*$")
    private val FEAT = Regex("\\s+(feat\\.?|ft\\.?|featuring|with|prod\\.?)\\s+.*$", RegexOption.IGNORE_CASE)
    private val NO_ALBUM = setOf("unknown album", "unknown", "untitled", "no album")

    /** Album-artist spellings of "Various Artists" (after [basic]). */
    private val VARIOUS = setOf(
        "various artists", "various", "va", "v a", "varios artistas", "varios", "artistes divers", "artistes variés",
        "verschiedene interpreten", "verschiedene künstler", "artisti vari", "vari", "diversen", "diverse artiesten",
        "różni wykonawcy", "разные исполнители", "сборник", "різні виконавці", "vários artistas", "çeşitli sanatçılar",
        "多位艺术家", "群星", "オムニバス", "various artist",
    ).map { basic(it) }.toSet()

    /** norm.basic: NFKC, lowercase, combining marks stripped, & → and, punctuation → space, a leading "the " dropped. */
    public fun basic(s: String?): String {
        val lower = normalizeUnicode(s ?: "", UnicodeForm.NFKC).lowercase()
        val stripped = buildString {
            for (c in normalizeUnicode(lower, UnicodeForm.NFKD)) if (c.category != CharCategory.NON_SPACING_MARK) append(c)
        }
        val d = normalizeUnicode(stripped, UnicodeForm.NFC).replace("&", " and ").replace("’", "'").replace("'", "")
        // Punctuation, underscores and whitespace all separate words; words join with one space.
        val joined = GenreFamilies.tokensOf(d).joinToString(" ")
        return if (joined.startsWith("the ")) joined.substring(4) else joined
    }

    /** norm.title_key: bracketed parts, a " - …" suffix and a feat. tail removed, then [basic]. */
    public fun titleKey(t: String?): String {
        var s = normalizeUnicode(t ?: "", UnicodeForm.NFKC)
        s = PAREN.replace(s, " ")
        s = DASH_SUFFIX.replace(s, "")
        s = FEAT.replace(s, "")
        return basic(s)
    }

    /** `title_key(album) + "\t" + basic(album artist, else track artist)`; "" for no or a placeholder album. */
    public fun albumKey(album: String?, albumArtist: String?, artist: String?): String {
        val a = titleKey(album)
        if (a.isEmpty() || a in NO_ALBUM) return ""
        val who = basic(albumArtist).ifEmpty { basic(artist) }
        return "$a\t$who"
    }

    /** The album artist names Various Artists (in one of its usual spellings). */
    public fun isVarious(albumArtist: String?): Boolean = basic(albumArtist).let { it.isNotEmpty() && it in VARIOUS }
}
