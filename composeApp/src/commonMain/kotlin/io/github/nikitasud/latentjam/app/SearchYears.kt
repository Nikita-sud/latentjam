/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.app

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/**
 * A year or decade written into a search, and what to look for within it.
 *
 * The text encoder places "80s" and "1985" only roughly (an exact year finds its tracks less
 * reliably than MiniLM did), while the tags know the year exactly. So search reads the time out
 * of the query and filters by the track's year: [subject] is the rest of the query, empty when the
 * query was only a time ("песни 80-х").
 */
internal data class SearchYears(val first: Int, val last: Int, val subject: String) {

    /** A track's year as the trusted text reads it: the original year first, then the edition's. */
    fun admits(track: TrackDescriptor): Boolean {
        val year = track.originalYear?.takeIf { it > 0 } ?: track.year?.takeIf { it > 0 } ?: return false
        return year in first..last
    }

    companion object {
        /**
         * The first year or decade in [query], or null when it names none. Recognized: `1985`,
         * `1985г`, `'85`, `80s`, `80's`, `1980s`, `80-х`, `80-е`, `80-ые`, `80er`, `80'ler`,
         * `80-talet`, `80年代`, `80년대`, and a decade after `años` / `anni` / `années` / `anos` /
         * `anii` / `jaren` / `lata` / `década` (`anni '80`). A bare `80` or `85` is not a time. The
         * decades `00s` to `20s` and the years `'00` to `'29` are this century's.
         */
        fun parse(query: String): SearchYears? {
            val tokens = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            for (index in tokens.indices) {
                val token = tokens[index].lowercase().trimEnd('.', ',', '!', '?')
                val single = year(token)?.let { it to it } ?: decade(token)?.let { it to it + 9 }
                val range = single ?: tokens.getOrNull(index + 1)
                    ?.takeIf { token in DECADE_WORDS }
                    ?.let { decadeNumber(it.lowercase().trimEnd('.', ',', '!', '?')) }
                    ?.let { it to it + 9 }
                if (range != null) {
                    val used = if (single != null) 1 else 2
                    val subject = (tokens.subList(0, index) + tokens.subList(index + used, tokens.size))
                        .filterNot { it.lowercase().trim('.', ',', '!', '?', '-') in FILLER }
                        .joinToString(" ")
                    return SearchYears(range.first, range.second, subject)
                }
            }
            return null
        }

        /** `1985`, `1985г`, `'85`. */
        private fun year(token: String): Int? {
            if (token.length == 3 && token[0] in APOSTROPHES && token[1].isDigit() && token[2].isDigit()) {
                val value = token.substring(1).toInt()
                return if (value <= 29) 2000 + value else 1900 + value
            }
            val digits = YEAR_SUFFIXES.firstOrNull { token.length == 4 + it.length && token.endsWith(it) }
                ?.let { token.dropLast(it.length) } ?: token
            if (digits.length != 4 || !digits.all(Char::isDigit)) return null
            return digits.toInt().takeIf { it in MIN_YEAR..MAX_YEAR }
        }

        /** `80s`, `1980s`, `80's`, `80-х`, `80er`, `80年代`... */
        private fun decade(token: String): Int? {
            val digits = token.takeWhile(Char::isDigit)
            if (digits.length != 2 && digits.length != 4) return null
            val suffix = token.substring(digits.length).trimStart(*SEPARATORS)
            if (suffix !in DECADE_SUFFIXES) return null
            return decadeNumber(digits)
        }

        /** `80`, `'80`, `1980`, `80s`: the decade a number after a decade word names. */
        private fun decadeNumber(token: String): Int? {
            val number = token.trimStart(*SEPARATORS)
            val digits = number.takeWhile(Char::isDigit)
            val rest = number.substring(digits.length).trimStart(*SEPARATORS)
            if (rest.isNotEmpty() && rest !in DECADE_SUFFIXES) return null
            val value = digits.toIntOrNull() ?: return null
            return when (digits.length) {
                2 -> if (value % 10 == 0) (if (value <= 20) 2000 + value else 1900 + value) else null
                4 -> value.takeIf { it % 10 == 0 && it in MIN_YEAR..MAX_YEAR }
                else -> null
            }
        }

        private const val MIN_YEAR = 1900
        private const val MAX_YEAR = 2099
        private val WHITESPACE = Regex("\\s+")
        private val APOSTROPHES = setOf('\'', '’', '`')
        private val SEPARATORS = charArrayOf('\'', '’', '`', '-', '‑')
        private val YEAR_SUFFIXES = listOf("г.", "г")
        private val DECADE_SUFFIXES = setOf(
            "s", "х", "x", "е", "e", "ые", "ых", "ті", "er", "ern", "erne", "ler", "lar", "talet", "tallet",
            "年代", "년대",
        )
        private val DECADE_WORDS = setOf(
            "años", "anos", "anni", "années", "annees", "anii", "jaren", "lata", "década", "decada", "decade",
        )

        /** Words that only say "music from then"; alone they leave nothing to search for. */
        private val FILLER = setOf(
            "music", "songs", "song", "hits", "hit", "tracks", "best", "top", "greatest", "the", "of", "from",
            "in", "year", "years", "era", "oldies", "music's",
            "музыка", "музыку", "песни", "песня", "хиты", "хит", "лучшие", "треки", "из", "с", "года", "год",
            "году", "годов", "годы", "годах", "в", "г",
            "музика", "пісні", "хіти", "років", "роки", "рік", "з",
            "musik", "lieder", "jahre", "jahren", "aus", "der", "die", "das", "den",
            "música", "musica", "canciones", "éxitos", "exitos", "de", "del", "los", "las", "la", "el",
            "músicas", "canções", "sucessos", "dos", "do", "da",
            "musique", "chansons", "tubes", "des", "les", "du",
            "canzoni", "successi", "degli", "dei",
            "muzică", "muzica", "piese", "cântece", "melodii", "hituri", "din", "anul",
            "muzyka", "piosenki", "przeboje", "hity", "lat",
            "müzik", "şarkılar", "şarkıları", "yıl", "yıllar",
            "muziek", "liedjes", "uit", "van",
            "lagu", "tahun",
            "音乐", "歌曲", "音楽", "曲", "歌", "노래", "음악", "موسيقى", "أغاني", "संगीत", "गाने",
        )
    }
}
