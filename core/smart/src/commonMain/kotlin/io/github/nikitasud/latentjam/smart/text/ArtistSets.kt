/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * A track's credit as an ordered set of artists (PLAN_FINAL §1.4): the port of
 * tools/research/listenbrainz_eval/artist_sets.py (version "as1", 230a6214), string rules only — the device reads no
 * artist MBIDs, so the reference's `mbid*` / `lastfm_mbid` rules never fire here. Keys are 64-bit FNV-1a hashes of the
 * reference's key strings (its "device keys"), so two credits name the same artist exactly when their keys are equal.
 *
 * @property artists primary first, then the other credited artists, then the featured ones; no duplicates
 * @property featured the subset of [artists] that came after a feat. token (credit or title)
 * @property composer classical tracks only: the composer's primary artist, the run-cap key
 */
public class ArtistSet(
    public val artists: List<Long>,
    public val featured: Set<Long>,
    public val composer: Long? = null,
    public val rules: Set<String> = emptySet(),
) {
    public val primary: Long? get() = artists.firstOrNull()

    /** The artist a run of one artist is counted on: the composer for classical, else the primary. */
    public val capKey: Long? get() = composer ?: primary

    override fun toString(): String = "ArtistSet($artists, featured=$featured, composer=$composer, rules=$rules)"
}

/** The known-artist list: a normalised name's key → its canonical key, or null when the name is not known. */
public fun interface KnownArtists {
    public fun canonical(matchKey: Long): Long?

    public companion object {
        /** Nothing is known: every part stays whole (the parser still splits at feat.). */
        public val NONE: KnownArtists = KnownArtists { null }
    }
}

public object ArtistSets {
    /** Normalised placeholders: never artists, also when the list names them. */
    public val PLACEHOLDERS: Set<String> = setOf(
        "various artists", "various artist", "various", "va", "v a", "varios artistas", "artistes divers", "artisti vari",
        "verschiedene interpreten", "разные исполнители", "различные исполнители", "群星", "オムニバス", "さまざまなアーティスト",
        "여러 아티스트", "unknown", "unknown artist", "unknown artists", "неизвестный исполнитель", "неизвестен", "佚名", "未知",
        "traditional", "trad", "traditionnel", "tradicional", "tradizionale", "народная", "народная песня",
        "anonymous", "anon", "anonym", "anonyme", "anonimo", "anónimo", "аноним", "no artist", "not specified",
    )

    /** MusicBrainz special-purpose names that are words too: placeholders only in brackets. */
    public val BRACKET_ONLY: Set<String> = setOf(
        "data", "dialogue", "nature sounds", "church chimes", "language instruction", "christmas music", "theatre",
        "disney", "mystery artist",
    )

    private val FOLD = mapOf('ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'ł' to "l", 'đ' to "d", 'ð' to "d", 'þ' to "th", 'ı' to "i")

    // Python's str-pattern \s and \w, spelled out: Kotlin regexes are ASCII-only by default on the JVM.
    private const val WS = "[\\t\\n\\u000B\\f\\r\\u001C-\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"
    private const val WORD = "[\\p{L}\\p{N}_]"
    private val FEAT = Regex("(?i)$WS*[\\(\\[（［]?$WS*(?<!$WORD)(?:feat\\.?|ft\\.?|featuring)(?:$WS+|(?<=\\.))")
    private val COND = Regex("(?i)$WS*(?:[,;，；、／＆&+×])$WS*|$WS+(?:with|vs\\.?|x)$WS+")
    private val TITLE = Regex("(?i)[\\(\\[（［]$WS*(?:feat\\.?|ft\\.?|featuring)$WS+([^\\)\\]）］]+)[\\)\\]）］]")
    private const val BRACKETS = " \t()[]（）［］"

    /** artist_sets.normalize, steps a–g. */
    public fun normalize(value: String?): String {
        var s = normalizeUnicode(value.orEmpty(), UnicodeForm.NFKC).lowercase().replace('ё', 'е')
        val decomposed = normalizeUnicode(s, UnicodeForm.NFD)
        val kept = StringBuilder(decomposed.length)
        var base = 0
        var i = 0
        while (i < decomposed.length) {
            val cp = codePointAt(decomposed, i)
            val n = if (cp > 0xFFFF) 2 else 1
            if (isNonspacingMark(cp)) {
                if (base >= 0x0400) kept.append(decomposed, i, i + n)
            } else {
                base = cp
                kept.append(decomposed, i, i + n)
            }
            i += n
        }
        s = normalizeUnicode(kept.toString(), UnicodeForm.NFC)
        val folded = StringBuilder(s.length)
        i = 0
        while (i < s.length) {
            val cp = codePointAt(s, i)
            val n = if (cp > 0xFFFF) 2 else 1
            val fold = if (n == 1) FOLD[s[i]] else null
            when {
                fold != null -> folded.append(fold)
                isLetterNumberOrMark(cp) -> folded.append(s, i, i + n)
                else -> folded.append(' ')
            }
            i += n
        }
        val tokens = folded.split(' ').filter(String::isNotEmpty)
        val kept2 = if (tokens.size > 1 && tokens[0] == "the") tokens.drop(1) else tokens
        return kept2.joinToString(" ")
    }

    /** FNV-1a 64 of the key's UTF-8 bytes; 0 for no key (artist_sets.key_hash). */
    public fun keyHash(key: String): Long {
        if (key.isEmpty()) return 0L
        var h = -0x340d631b7bdddcdbL
        for (b in key.encodeToByteArray()) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
        return h
    }

    public fun isPlaceholder(raw: String): Boolean {
        val k = normalize(raw)
        return k in PLACEHOLDERS || (raw.trim().startsWith('[') && k in BRACKET_ONLY)
    }

    /** artist_sets.parse on the string rules (no MBIDs on device). */
    public fun parse(
        artist: String?,
        title: String? = null,
        known: KnownArtists = KnownArtists.NONE,
        composer: String? = null,
        genreFamily: String? = null,
    ): ArtistSet {
        val rules = LinkedHashSet<String>()
        val main = ArrayList<String>()
        val featured = ArrayList<String>()
        stringRules(artist.orEmpty(), known, rules, main, featured)
        for (m in TITLE.findAll(title.orEmpty())) {
            rules += "title_feat"
            featured += part(m.groupValues[1], known, rules)
        }
        val keys = ArrayList<Long>()
        val feat = LinkedHashSet<Long>()
        for ((source, isFeatured) in listOf(main to false, featured to true)) {
            for (p in source) {
                val k = key(p, known)
                if (k != 0L && k !in keys) {
                    keys += k
                    if (isFeatured) feat += k
                }
            }
        }
        val composerKey = if (composer != null && genreFamily == "classical") parse(composer, known = known).primary else null
        return ArtistSet(keys, feat, composerKey, rules)
    }

    private fun known(raw: String, known: KnownArtists): Boolean = known.canonical(keyHash(normalize(raw))) != null

    private fun key(raw: String, known: KnownArtists): Long {
        val k = keyHash(normalize(raw))
        return if (k == 0L) 0L else known.canonical(k) ?: k
    }

    private fun strip(part: String): String = part.trim { it in BRACKETS }

    private fun stringRules(artist: String, known: KnownArtists, rules: MutableSet<String>, main: MutableList<String>, featured: MutableList<String>) {
        val a = artist.trim(::isPythonSpace)
        if (a.isEmpty()) { rules += "empty"; return }
        if (isPlaceholder(a)) { rules += "placeholder"; return }
        if (known(strip(a), known)) { rules += "whole_known"; main += strip(a); return }
        val pieces = FEAT.split(a)
        if (pieces.size > 1) rules += "feat_split"
        main += part(pieces[0], known, rules)
        for (t in pieces.drop(1)) featured += part(t, known, rules)
    }

    private fun part(raw: String, known: KnownArtists, rules: MutableSet<String>): List<String> {
        val part = strip(raw)
        if (part.isEmpty() || isPlaceholder(part)) return emptyList()
        if (known(part, known)) return listOf(part)
        condSplit(part, known)?.let { rules += "cond_split"; return it }
        rules += "unresolved"
        return listOf(part)
    }

    /** Fewest-segment segmentation at COND cuts into known artists, longest first segment on ties; null = whole. */
    private fun condSplit(part: String, known: KnownArtists): List<String>? {
        val cuts = COND.findAll(part).filter { it.range.first > 0 && it.range.last + 1 < part.length }.toList()
        if (cuts.isEmpty()) return null
        val bounds = listOf(0) + cuts.map { it.range.last + 1 }
        val ends = cuts.map { it.range.first } + part.length
        val n = bounds.size
        val best = arrayOfNulls<List<String>>(n + 1)
        best[n] = emptyList()
        for (i in n - 1 downTo 0) {
            for (j in n - 1 downTo i) {
                val rest = best[j + 1] ?: continue
                val seg = strip(part.substring(bounds[i], ends[j]))
                if (seg.isEmpty() || !known(seg, known) || isPlaceholder(seg)) continue
                val candidate = listOf(seg) + rest
                if (best[i] == null || candidate.size < best[i]!!.size) best[i] = candidate
            }
        }
        return best[0]?.takeIf { it.size >= 2 }
    }

    private fun isPythonSpace(c: Char): Boolean =
        c in "\t\n\u000B\u000C\r\u001C\u001D\u001E\u001F \u0085      　" || c in ' '..' '

    private fun codePointAt(s: String, i: Int): Int {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
        }
        return c.code
    }
}

/** Unicode general category Mn of a code point, by the platform's tables. */
internal expect fun isNonspacingMark(codePoint: Int): Boolean

/** Unicode general category L*, N* or M* of a code point, by the platform's tables. */
internal expect fun isLetterNumberOrMark(codePoint: Int): Boolean

/**
 * The shipped known-artist table (`ml/artist_known.bin`, tools/research/ranker/pack_artist_known.py), read in place:
 * "LJARTK1\0" | u32 version | u32 canonical count | u32 alias count | canonical keys (sorted u64) | aliases (sorted u64
 * key, then u32 index of its canonical key). A canonical key maps to itself, an alias to its canonical key.
 */
public class KnownArtistTable private constructor(
    private val bytes: AssetBytes,
    private val canonicalCount: Int,
    private val aliasCount: Int,
) : KnownArtists {
    private val aliasesAt = HEADER + canonicalCount * 8

    override fun canonical(matchKey: Long): Long? {
        if (matchKey == 0L) return null
        if (search(HEADER, 8, canonicalCount, matchKey) >= 0) return matchKey
        val alias = search(aliasesAt, 12, aliasCount, matchKey)
        if (alias < 0) return null
        val index = le32(aliasesAt + alias * 12 + 8)
        return le64(HEADER + index * 8)
    }

    /** Binary search over unsigned u64 keys at the start of [stride]-byte records. */
    private fun search(at: Int, stride: Int, count: Int, key: Long): Int {
        var low = 0
        var high = count - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val c = le64(at + mid * stride).toULong().compareTo(key.toULong())
            when {
                c < 0 -> low = mid + 1
                c > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun u8(at: Int): Long = bytes[at].toLong() and 0xff
    private fun le32(at: Int): Int = (u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)).toInt()
    private fun le64(at: Int): Long {
        var v = 0L
        for (b in 7 downTo 0) v = (v shl 8) or u8(at + b)
        return v
    }

    public companion object {
        private val MAGIC = "LJARTK1\u0000".encodeToByteArray()
        private const val HEADER = 20

        /** Null for a missing, damaged or newer-format asset: the parser then knows no artist. */
        public fun parse(bytes: AssetBytes): KnownArtistTable? {
            if (bytes.size < HEADER || !MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
            fun u32(at: Int): Long = (0 until 4).fold(0L) { v, b -> v or ((bytes[at + b].toLong() and 0xff) shl (8 * b)) }
            if (u32(8) != 1L) return null
            val canonical = u32(12)
            val aliases = u32(16)
            if (HEADER + canonical * 8 + aliases * 12 != bytes.size.toLong()) return null
            return KnownArtistTable(bytes, canonical.toInt(), aliases.toInt())
        }
    }
}
