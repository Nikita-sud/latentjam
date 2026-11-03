/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * "Don't recommend artist" by whole segments of a credit (0.9.4, smart-research review_ext/FIXES_NIGHT4.md X v5): an
 * excluded name hides a track when it equals a whole segment of
 * - the credit, split at `& , / x vs with feat ft featuring` (never when the pack knows the whole credit as one artist:
 *   "Simon & Garfunkel", "Earth, Wind & Fire");
 * - a feat. / prod. clause of the title, or a bracketed "(by X)" (a bare "by" is the title's own word);
 * - a " - " segment of the title when the credit is empty ("SK - Beats - …"), or a later one the pack knows as an
 *   artist (never a movement or version name such as "Air").
 * A name of several artists ("Kosta T feat. Ghofra Z") needs all of its segments. Empty names and credits never match.
 * A name that is also a version word ("Live", "Mono") matches the credit only.
 */
public object CreditSegments {
    private val SEP = Regex("\\s*(?:&|,|/|\\bfeat\\b\\.?|\\bft\\b\\.?|\\bfeaturing\\b)\\s*|\\s+(?:and|x|vs\\.?|with)\\s+")
    /** Words a title's " - " part or clause uses for a version of the song; never an artist there. */
    private val VERSION_WORDS = setOf(
        "live", "mono", "stereo", "remaster", "remastered", "edit", "radio edit", "mix", "remix", "demo", "acoustic",
        "instrumental", "original", "original mix", "extended", "extended mix", "single version", "album version",
        "version", "bonus track", "explicit", "clean", "intro", "outro", "interlude", "unplugged", "reprise",
    )
    private val CLAUSE = Regex(
        "(?<![\\p{L}\\p{N}])(?:feat\\.?|ft\\.?|featuring|prod\\.?|produced by)\\s+([^()\\[\\]]*?)(?=\\s+-\\s+|[()\\[\\]]|$)",
    )

    /** "(by X)" in brackets: a bare "by" is a title's own word ("Killed by Death", "Stand by Me"). */
    private val BY_CLAUSE = Regex("[(\\[]\\s*by\\s+([^()\\[\\]]+?)\\s*[)\\]]")

    /** Whether [name] is a whole segment of [credit] or [title]; [knownWhole] says the pack knows a string as one artist. */
    public fun matches(name: String, credit: String, title: String, knownWhole: (String) -> Boolean): Boolean {
        if (segmentMatch(credit, name, knownWhole)) return true
        // "Comfortably Numb - Live" names a version, not the band Live: such names match the credit only.
        if (fold(name).trim() in VERSION_WORDS) return false
        val t = fold(title)
        if (CLAUSE.findAll(t).any { segmentMatch(it.groupValues[1], name, knownWhole) }) return true
        if (BY_CLAUSE.findAll(t).any { segmentMatch(it.groupValues[1], name, knownWhole) }) return true
        val parts = t.split(" - ")
        if (parts.size < 2) return false
        // A credited track's later " - " parts are mostly the song's own words (a movement "Air", a version): they name
        // an artist only when the pack knows the part as one. An uncredited track's parts are all the credit it has.
        val later = parts.drop(1).filter { credit.isBlank() || knownWhole(it.trim()) }
        if (later.any { segmentMatch(it, name, knownWhole) }) return true
        return credit.isBlank() && segmentMatch(parts[0], name, knownWhole)
    }

    private fun segmentMatch(s: String, name: String, knownWhole: (String) -> Boolean): Boolean {
        val n = fold(name).trim()
        val whole = fold(s).trim()
        if (n.isEmpty() || whole.isEmpty()) return false
        if (whole == n) return true
        val parts = segments(s, knownWhole)
        val want = segments(name, knownWhole)
        return if (want.size > 1) parts.containsAll(want) else parts.any { it == n }
    }

    private fun segments(s: String, knownWhole: (String) -> Boolean): List<String> =
        if (knownWhole(s.trim())) {
            listOf(fold(s).trim())
        } else {
            fold(s).split(SEP).map { it.trim(' ', '(', ')', '[', ']', '.', '-') }.filter(String::isNotEmpty)
        }

    /** Lower case without diacritics ("Beyoncé" = "beyonce"). */
    internal fun fold(s: String): String {
        val decomposed = normalizeUnicode(s.lowercase(), UnicodeForm.NFD)
        val out = StringBuilder(decomposed.length)
        var i = 0
        while (i < decomposed.length) {
            val high = decomposed[i]
            val n = if (high.isHighSurrogate() && i + 1 < decomposed.length) 2 else 1
            val cp = if (n == 2) ((high.code - 0xD800) shl 10) + (decomposed[i + 1].code - 0xDC00) + 0x10000 else high.code
            if (!isNonspacingMark(cp)) out.append(decomposed, i, i + n)
            i += n
        }
        return out.toString()
    }
}
