/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.text.UnicodeForm
import io.github.nikitasud.latentjam.smart.text.normalizeUnicode

/**
 * PLAN_L2's genre families (serving/taxonomy.py v2.1, asset `genre_families.json` lj-genre-families/3): one function for
 * MusicBrainz genre names, pack artist genres and the file's genre tag, so training and serving map a genre the same way.
 * Per genre: an ID3v1 code ("(13)", "(17)Rock", bare "13") is decoded; then the override table (an override may be
 * null: ambiguous alone, the artist decides), MusicBrainz's curated family for the exact name, the longest phrase span
 * on the word tokens (a tie goes to the rightmost, the head noun: "garage rock"), a single-word suffix rule, the tree.
 */
public class GenreFamilies private constructor(
    /** The families in code order (artist_family_l2.bin's float codes index this list). */
    public val families: List<String>,
    private val phrases: List<Pair<List<String>, String>>,
    private val suffixes: List<Pair<String, String>>,
    private val overrides: Map<String, String?>,
    private val mbFamily: Map<String, String>,
    private val tree: Map<String, String>,
    private val id3v1: List<String>,
) {
    /** taxonomy.family: the family of one genre string, or null. */
    public fun family(genre: String?): String? {
        var g = (genre ?: "").trim()
        (ID3_CODE.matchEntire(g) ?: ID3_BARE.matchEntire(g))?.let { m ->
            val code = m.groupValues[1].toInt()
            if (code < id3v1.size) g = m.groupValues.getOrNull(2)?.trim()?.ifEmpty { null } ?: id3v1[code]
        }
        val t = tokens(g)
        if (t.isEmpty()) return null
        val key = t.joinToString(" ")
        if (key in overrides) return overrides[key]
        mbFamily[key]?.let { return it }
        phraseFamily(t)?.let { return it }
        for (w in t) for ((suffix, f) in suffixes) if (w.endsWith(suffix) && w.length > suffix.length) return f
        return tree[key]
    }

    /** The longest phrase span; a tie goes to the span ending rightmost (taxonomy._phrase_family). */
    private fun phraseFamily(t: List<String>): String? {
        var bestLength = -1
        var bestEnd = -1
        var best: String? = null
        for ((p, f) in phrases) {
            val n = p.size
            val symphon = p.last().endsWith("symphon")
            for (i in 0..t.size - n) {
                val hit = if (symphon) {
                    t.subList(i, i + n - 1) == p.subList(0, n - 1) && t[i + n - 1].startsWith("symphon")
                } else {
                    t.subList(i, i + n) == p
                }
                // Python's max keeps the first of equal keys: replace only on a strictly larger (length, end).
                if (hit && (n > bestLength || (n == bestLength && i + n > bestEnd))) {
                    bestLength = n
                    bestEnd = i + n
                    best = f
                }
            }
        }
        return best
    }

    /** taxonomy.family_of_tag: a file genre tag split on ; / , | — the family of the first part that maps. */
    public fun familyOfTag(tag: String?): String? {
        for (part in (tag ?: "").split(';', '/', ',', '|')) family(part.trim())?.let { return it }
        return null
    }

    public companion object {
        private val ID3_CODE = Regex("\\((\\d{1,3})\\)(.*)")
        private val ID3_BARE = Regex("(\\d{1,3})")

        /** taxonomy.tokens: NFKD with combining marks dropped, lowercase, & → and, apostrophes dropped, non-alphanumerics split. */
        internal fun tokens(s: String?): List<String> {
            val folded = buildString {
                for (c in normalizeUnicode(s ?: "", UnicodeForm.NFKD)) if (c.category != CharCategory.NON_SPACING_MARK) append(c)
            }
            return tokensOf(folded.lowercase().replace("&", " and ").replace("'", ""))
        }

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

        /** Null for a missing, malformed or older-layout asset: the L2 track family then stays unknown. */
        @Suppress("UNCHECKED_CAST")
        public fun parse(json: String): GenreFamilies? = runCatching {
            val root = MiniJson(json).parse() as Map<String, Any?>
            require((root["version"] as String).startsWith("lj-genre-families/3"))
            fun pairs(key: String) = (root[key] as List<*>).map { e -> (e as List<*>).let { it[0] as String to it[1] as String } }
            GenreFamilies(
                families = (root["families"] as List<*>).map { it as String },
                phrases = pairs("phrases").map { (p, f) -> p.split(' ') to f },
                suffixes = pairs("suffixes"),
                overrides = (root["overrides"] as Map<*, *>).entries.associate { (k, v) -> k as String to v as String? },
                mbFamily = (root["mb_family"] as Map<*, *>).entries.associate { (k, v) -> k as String to v as String },
                tree = (root["tree"] as Map<*, *>).entries.associate { (k, v) -> k as String to v as String },
                id3v1 = (root["id3v1"] as List<*>).map { it as String },
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
