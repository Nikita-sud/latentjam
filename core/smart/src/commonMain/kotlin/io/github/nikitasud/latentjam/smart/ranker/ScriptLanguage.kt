/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

/**
 * The cold fix's script languages (reference.py script_of / lang_group / same_language, model key langScript): a track
 * with no pack language takes the first non-Latin script group of its title + artist ("script:cyr"); pack languages
 * map to the same groups, and two languages match when equal or, when either is a script code, in the same group.
 * Latin script stays unknown.
 */
internal object ScriptLanguage {
    /** build_artist_knowledge.LANGUAGES: a fixture's resolved language is the 1-based index as a string. */
    private val PACK_LANGS = listOf(
        "en", "ru", "ro", "uk", "ja", "ko", "zh", "es", "pt", "fr", "de", "it", "tr", "pl", "ar", "hi", "kk", "be", "sr", "hr",
        "bg", "el", "he", "fa", "nl", "sv", "fi", "no", "da", "cs", "hu", "id", "th", "vi", "tl", "ka", "hy", "az", "uz", "la",
        "instrumental",
    )
    private val GROUP = mapOf(
        "ru" to "cyr", "uk" to "cyr", "kk" to "cyr", "be" to "cyr", "sr" to "cyr", "bg" to "cyr", "ja" to "jpn", "ko" to "kor",
        "zh" to "han", "el" to "grk", "ar" to "ara", "fa" to "ara", "he" to "heb", "hi" to "dev", "th" to "tha", "ka" to "geo",
        "hy" to "arm",
    )

    /** Kana before Han, so Japanese wins over Chinese. */
    private val SCRIPTS: List<Pair<String, (Char) -> Boolean>> = listOf(
        "jpn" to { c -> c in '぀'..'ヿ' },
        "kor" to { c -> c in '가'..'힯' || c in 'ᄀ'..'ᇿ' },
        "han" to { c -> c in '一'..'鿿' },
        "cyr" to { c -> c in 'Ѐ'..'ӿ' },
        "grk" to { c -> c in 'Ͱ'..'Ͽ' },
        "ara" to { c -> c in '؀'..'ۿ' },
        "heb" to { c -> c in '֐'..'׿' },
        "dev" to { c -> c in 'ऀ'..'ॿ' },
        "tha" to { c -> c in '฀'..'๿' },
        "geo" to { c -> c in 'Ⴀ'..'ჿ' },
        "arm" to { c -> c in '԰'..'֏' },
    )

    /** Pack languages written in Latin script (langScript "latin" groups them as "lat"). */
    private val LATIN_LANGS = setOf(
        "en", "ro", "es", "pt", "fr", "de", "it", "tr", "pl", "hr", "nl", "sv", "fi", "no", "da", "cs", "hu", "id", "vi", "tl",
        "az", "uz", "la",
    )

    private fun latinLetter(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '\u00c0'..'\u024f'

    /** [latin]: a Latin-letter string with no other script is "lat" (langScript "latin"), else unknown. */
    fun scriptOf(title: String?, artist: String?, latin: Boolean = false): String? {
        val text = title.orEmpty() + " " + artist.orEmpty()
        for ((group, inScript) in SCRIPTS) if (text.any(inScript)) return group
        if (latin && text.any(::latinLetter)) return "lat"
        return null
    }

    /**
     * A pack entity whose language is written in Latin script cannot be the artist of a track whose title is in another
     * script (a Russian "Sabi & MIA BOYKA" title resolved by its first credit to an English "Sabi"; a Russian "Shura"
     * to an English Shura): the entity's language, descriptor and tables would all be someone else's. A Latin title with a
     * gloss in another script ("Hanami (花見)") is still a Latin title: only a title whose letters are mostly another
     * script counts.
     */
    fun foreignEntity(language: String?, title: String?): Boolean {
        if (language == null || title == null) return false
        if (group(language) != "lat") return false
        val other = title.count { c -> SCRIPTS.any { it.second(c) } }
        return other > 0 && other > title.count(::latinLetter)
    }

    /** The pack language, else "script:<group>" from the title and artist, else null. */
    fun effective(language: String?, title: String?, artist: String?, latin: Boolean = false): String? =
        language ?: scriptOf(title, artist, latin)?.let { "script:$it" }

    private fun group(code: String): String? {
        if (code.startsWith("script:")) return code.removePrefix("script:")
        val pack = code.toIntOrNull()?.let { PACK_LANGS.getOrNull(it - 1) } ?: code
        return GROUP[pack] ?: if (pack in LATIN_LANGS) "lat" else null
    }

    fun same(a: String?, c: String?): Boolean {
        if (a == null || c == null) return false
        if (a == c) return true
        if (a.startsWith("script:") || c.startsWith("script:")) {
            val ga = group(a)
            return ga != null && ga == group(c)
        }
        return false
    }
}
