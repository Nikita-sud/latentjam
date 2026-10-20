/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.TrackDescriptor

/** Fast 3.1 holidaySeason (reference.py is_holiday / in_season): a lexicon over title, album and genre, and the season. */
internal object Holidays {
    private val WORDS = listOf(
        "christmas", "xmas", "x-mas", "navidad", "weihnacht", "natale", "joulu", "jingle bell", "santa claus",
        "new year", "рождеств", "новый год", "новогодн", "ёлочк", "елочк", "дед мороз", "noël", "feliz navidad",
    )

    fun isHoliday(t: TrackDescriptor): Boolean {
        val s = listOf(t.title, t.album, t.genre).joinToString(" ") { it.orEmpty() }.lowercase()
        return WORDS.any { it in s } || "holiday" in t.genre.orEmpty().lowercase()
    }

    /** 15 November – 10 January, UTC. */
    fun inSeason(epochMs: Long): Boolean {
        val days = epochMs.floorDiv(86_400_000L)
        val (month, day) = civil(days)
        return (month == 11 && day >= 15) || month == 12 || (month == 1 && day <= 10)
    }

    /** Days since 1970-01-01 → (month, day) in the proleptic Gregorian calendar (Hinnant's civil_from_days). */
    private fun civil(z0: Long): Pair<Int, Int> {
        val z = z0 + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return m to d
    }
}
