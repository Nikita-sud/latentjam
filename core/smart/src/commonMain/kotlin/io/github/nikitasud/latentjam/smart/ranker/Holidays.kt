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

/** Fast 3.2 rule penalties (reference.py rule_penalties): seed-dependent, subtracted in the decoder after the spread. */
internal object RulePenalties {
    private val FLAG_WORDS = listOf("intro", "outro", "skit", "interlude", "reprise", "интро", "скит")

    /** Python's `\w`: a letter, digit or underscore (Unicode). */
    private fun wordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    /** reference.title_flagged: a flag word with word boundaries on both sides, or "(short)", in the lower-cased title. */
    fun titleFlagged(title: String?): Boolean {
        val t = title.orEmpty().lowercase()
        if ("(short)" in t) return true
        for (w in FLAG_WORDS) {
            var at = t.indexOf(w)
            while (at >= 0) {
                val before = at == 0 || !wordChar(t[at - 1])
                val end = at + w.length
                val after = end == t.length || !wordChar(t[end])
                if (before && after) return true
                at = t.indexOf(w, at + 1)
            }
        }
        return false
    }

    /** Per pool row: the duration rule and the title flags; null when every row is 0. */
    fun of(model: RankerModel, seedDurationMs: Long?, seedTitle: String?, durations: List<Long?>, titles: List<String?>): DoubleArray? {
        val out = DoubleArray(durations.size)
        model.durationRule?.let { r ->
            for (j in durations.indices) {
                val d = durations[j] ?: continue
                if (d > r.longMs && !(seedDurationMs != null && seedDurationMs >= r.seedLongMs)) out[j] += r.longLambda
                if (d < r.shortMs && !(seedDurationMs != null && seedDurationMs < r.shortMs)) out[j] += r.shortLambda
                // vc21: a cue under veryShortMs pays extra unless the seed is one too (the owner's film-score queue).
                if (d < r.veryShortMs && !(seedDurationMs != null && seedDurationMs < r.veryShortMs)) out[j] += r.veryShortLambda
            }
        }
        model.titleFlags?.let { lam ->
            if (!titleFlagged(seedTitle)) for (j in titles.indices) if (titleFlagged(titles[j])) out[j] += lam
        }
        return out.takeIf { o -> o.any { it != 0.0 } }
    }
}

/**
 * Fast 3.2 linear audio heads (heads_fast32.json, lj-fast4-heads/1): y = Σ (x_i − mean_in_i) · W_i + mean_out over the
 * L2-normalised A0 track vector. A pure function of the stored vector, so it is computed from the press's batched read.
 */
public class LinearHeads private constructor(private val heads: Map<String, Triple<DoubleArray, DoubleArray, Double>>) {
    public val names: Set<String> get() = heads.keys

    public fun value(name: String, unitVector: FloatArray): Double? {
        val (mean, w, out) = heads[name] ?: return null
        if (unitVector.size != mean.size) return null
        var y = 0.0
        for (i in mean.indices) y += (unitVector[i] - mean[i]) * w[i]
        return y + out
    }

    public companion object {
        @Suppress("UNCHECKED_CAST")
        public fun parse(json: String): LinearHeads? = runCatching {
            val root = MiniJson(json).parse() as Map<String, Any?>
            require((root["schema"] as String).startsWith("lj-fast4-heads/1"))
            LinearHeads((root["heads"] as Map<String, Any?>).mapValues { (_, v) ->
                val h = v as Map<String, Any?>
                require(h["kind"] == "linear")
                val mean = (h["mean_in"] as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
                val w = (h["W"] as List<*>).map { ((it as List<*>)[0] as Number).toDouble() }.toDoubleArray()
                require(mean.size == w.size)
                Triple(mean, w, ((h["mean_out"] as List<*>)[0] as Number).toDouble())
            })
        }.getOrNull()
    }
}
