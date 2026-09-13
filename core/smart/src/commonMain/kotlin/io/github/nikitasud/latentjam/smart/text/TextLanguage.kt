/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.text

/**
 * The language word the trusted text carries.
 *
 * A tag wins (`LANGUAGE` / ID3 `TLAN`: ISO 639 codes or plain names). Without one, the script of
 * the title and artist decides, exactly as the chain's own rule does: Cyrillic is Russian,
 * kana/kanji Japanese, anything else stays silent. Latin script is deliberately not "english":
 * the Romanian tracks are the ones a language word is meant to keep together, and calling them
 * English would do the opposite.
 *
 * Measured on the real library (1,084 tracks, 20 playlists): same-language neighbours for
 * Cyrillic tracks rose from 65% to 77% with playlist retrieval unchanged; transliterating the
 * Cyrillic instead raised retrieval but dropped that figure to 49%, because for an English
 * vocabulary the foreign script itself was the signal.
 */
public object TextLanguage {

    private val WORDS: Map<String, String> = mapOf(
        "ru" to "russian", "rus" to "russian", "russian" to "russian", "русский" to "russian",
        "en" to "english", "eng" to "english", "english" to "english",
        "ro" to "romanian", "ron" to "romanian", "rum" to "romanian", "romanian" to "romanian",
        "română" to "romanian", "romana" to "romanian",
        "ja" to "japanese", "jpn" to "japanese", "japanese" to "japanese", "日本語" to "japanese",
        "ko" to "korean", "kor" to "korean", "korean" to "korean",
        "de" to "german", "deu" to "german", "ger" to "german", "german" to "german", "deutsch" to "german",
        "fr" to "french", "fra" to "french", "fre" to "french", "french" to "french", "français" to "french",
        "es" to "spanish", "spa" to "spanish", "spanish" to "spanish", "español" to "spanish",
        "it" to "italian", "ita" to "italian", "italian" to "italian", "italiano" to "italian",
        "uk" to "ukrainian", "ukr" to "ukrainian", "ukrainian" to "ukrainian", "українська" to "ukrainian",
        "pt" to "portuguese", "por" to "portuguese", "portuguese" to "portuguese", "português" to "portuguese",
        "tr" to "turkish", "tur" to "turkish", "turkish" to "turkish",
        "pl" to "polish", "pol" to "polish", "polish" to "polish",
        "zh" to "chinese", "zho" to "chinese", "chi" to "chinese", "chinese" to "chinese", "中文" to "chinese",
        "kk" to "kazakh", "kaz" to "kazakh", "kazakh" to "kazakh",
        "be" to "belarusian", "bel" to "belarusian", "belarusian" to "belarusian",
        "ar" to "arabic", "ara" to "arabic", "arabic" to "arabic",
        "hi" to "hindi", "hin" to "hindi", "hindi" to "hindi",
        "zxx" to "instrumental", "instrumental" to "instrumental",
        "mul" to "multilingual", "multilingual" to "multilingual",
    )

    /** The word for the trusted string, or null when neither tag nor script says anything. */
    public fun word(tag: String?, title: String?, artist: String?): String? {
        val raw = tag?.trim()?.lowercase().orEmpty()
        if (raw.isNotEmpty()) {
            WORDS[raw]?.let { return it }
            // "eng; rus", "en-US", "ru_RU": the first token is the language.
            val head = raw.split(';', ',', '/', '-', '_', ' ').first().trim()
            WORDS[head]?.let { return it }
        }
        return scriptWord(title.orEmpty() + artist.orEmpty())
    }

    private fun scriptWord(text: String): String? {
        for (character in text) {
            val code = character.code
            if (code in 0x0400..0x04FF) return "russian"
            if (code in 0x3040..0x30FF || code in 0x4E00..0x9FFF) return "japanese"
        }
        return null
    }
}
