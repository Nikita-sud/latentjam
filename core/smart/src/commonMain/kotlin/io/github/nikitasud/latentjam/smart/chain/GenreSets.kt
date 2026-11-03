/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.chain

/**
 * Genre families as sets, what [io.github.nikitasud.latentjam.smart.Genres.families] returns. Every genre value is
 * tokenised; phrases map to one or more families (a sub-genre carries its parent: metal → {metal, rock}); fused words
 * ("synthpop", "krautrock") split at a head word; Russian genre words match by stem; a value with no family keeps its
 * raw lowercased self as one, so nothing is lost. Tracks compare by [overlap], never by one value.
 */
internal object GenreSets {
    private fun f(vararg families: String) = families.toSet()

    /** Phrases as token lists → families. Longer phrases are matched before their words. */
    private val PHRASES: List<Pair<List<String>, Set<String>>> = listOf(
        "hip hop" to f("hiphop"), "trip hop" to f("electronic", "hiphop"), "drum and bass" to f("dnb", "electronic"),
        "drum n bass" to f("dnb", "electronic"), "drum bass" to f("dnb", "electronic"), "r b" to f("soul"), "rhythm and blues" to f("soul"), "new age" to f("ambient"),
        "singer songwriter" to f("folk"), "old time" to f("folk", "country"), "spoken word" to f("spoken"),
        "sound art" to f("experimental"), "field recording" to f("experimental"), "field recordings" to f("experimental"),
        "musique concrete" to f("experimental"), "audio collage" to f("experimental"), "sound collage" to f("experimental"),
        "avant garde" to f("experimental"), "post rock" to f("rock"), "post punk" to f("punk", "rock"), "dream pop" to f("pop"),
        "dance pop" to f("dance", "pop", "electronic"), "big band" to f("jazz"), "bossa nova" to f("latin", "jazz"),
        "hi nrg" to f("dance", "electronic"), "lo fi" to f("lofi"), "free jazz" to f("jazz"), "acid jazz" to f("jazz"),
        "alternative hip hop" to f("hiphop"), "chip music" to f("electronic"), "easy listening" to f("easylistening"), "no wave" to f("punk", "rock"),
        "middle east" to f("world"), "north african" to f("world"), "asia far east" to f("world"), "kid friendly" to f("children"),
        // Electronic styles whose words alone read as rock, punk or classical ("garage", "progressive", "hardcore").
        "uk garage" to f("dance", "electronic"), "speed garage" to f("dance", "electronic"), "future garage" to f("electronic"),
        "garage house" to f("dance", "electronic"), "progressive house" to f("dance", "electronic"),
        "progressive trance" to f("dance", "electronic"), "psychedelic trance" to f("dance", "electronic"),
        "happy hardcore" to f("dance", "electronic"), "hardcore techno" to f("dance", "electronic"),
        "new romantic" to f("pop", "electronic"), "chamber pop" to f("pop"),
        // Rap styles named with punk words ("hardcore", "emo").
        "hardcore hip hop" to f("hiphop"), "hardcore rap" to f("hiphop"), "emo rap" to f("hiphop"), "emo hip hop" to f("hiphop"),
        "хип хоп" to f("hiphop"), "авторская песня" to f("folk"), "русский рок" to f("rock"),
    ).map { (p, fam) -> p.split(' ') to fam }.sortedByDescending { it.first.size }

    private val WORDS: Map<String, Set<String>> = buildMap {
        fun put(families: Set<String>, vararg words: String) = words.forEach { put(it, families) }
        put(f("rock"), "rock", "grunge", "krautrock", "shoegaze", "garage", "alternative", "psychedelic", "stoner", "britrock")
        put(f("metal", "rock"), "metal", "metalcore", "deathcore", "doom", "sludge", "thrash", "djent")
        put(f("punk", "rock"), "punk", "hardcore", "emo", "screamo", "grindcore", "oi")
        put(f("pop"), "pop", "europop", "kpop", "jpop", "cpop", "schlager")
        put(f("pop", "electronic"), "synthpop", "electropop", "technopop")
        put(f("pop", "rock"), "britpop", "powerpop")
        put(f("electronic"), "electronic", "electronica", "electro", "idm", "glitch", "chiptune", "synthwave", "darkwave",
            "vaporwave", "dubstep", "breakbeat", "breakcore")
        put(f("dance", "electronic"), "dance", "disco", "house", "techno", "trance", "edm", "eurodance", "electrohouse", "hardstyle", "club", "psytrance",
            // "-core" dance styles the punk head would claim.
            "nightcore", "frenchcore", "uptempo")
        put(f("dnb", "electronic"), "dnb", "jungle", "drumnbass")
        put(f("ambient", "electronic"), "downtempo", "chillout", "chill")
        put(f("ambient"), "ambient", "drone", "meditation")
        put(f("hiphop"), "hiphop", "rap", "trap", "phonk", "grime", "drill")
        put(f("jazz"), "jazz", "swing", "bebop", "fusion")
        put(f("blues"), "blues", "boogie")
        put(f("folk"), "folk", "songwriter", "americana", "bard")
        put(f("folk", "country"), "bluegrass")
        put(f("country"), "country")
        put(f("soul"), "soul", "rnb", "gospel", "motown")
        put(f("funk", "soul"), "funk")
        put(f("classical"), "classical", "orchestral", "baroque", "opera", "choral", "chamber", "symphonic", "romantic", "renaissance", "minimalism")
        put(f("soundtrack"), "soundtrack", "score", "ost", "cinematic", "film")
        put(f("experimental"), "experimental", "noise", "improvisation", "improv", "avantgarde")
        put(f("reggae"), "reggae", "dub", "ska", "dancehall", "reggaeton")
        put(f("latin"), "latin", "salsa", "cumbia", "tango", "flamenco", "samba")
        put(f("world"), "world", "international", "afrobeat", "balkan", "klezmer")
        put(f("world", "folk"), "celtic")
        put(f("spoken"), "spoken", "poetry", "comedy", "audiobook", "podcast")
        put(f("industrial"), "industrial")
        put(f("hiphop"), "nerdcore", "horrorcore")
        put(f("electronic"), "speedcore", "gabber", "breakcore")
        put(f("electronic", "experimental"), "electroacoustic")
        put(f("electronic"), "bigbeat")
        put(f("easylistening"), "lounge", "exotica")
        put(f("world"), "african", "indian", "europe", "european", "french", "fado", "asian")
        put(f("world", "latin"), "brazilian")
        put(f("children"), "children", "kids", "nursery", "lullaby")
        put(f("rock"), "progressive")
        put(f("chanson"), "chanson")
        put(f("lofi"), "lofi")
    }

    /** Fused-word heads: "synthpop" → pop (+ the prefix's own families when it has some). */
    private val HEADS: List<Pair<String, Set<String>>> = listOf(
        "pop" to f("pop"), "rock" to f("rock"), "punk" to f("punk", "rock"), "metal" to f("metal", "rock"), "core" to f("punk", "rock"),
        "folk" to f("folk"), "jazz" to f("jazz"), "funk" to f("funk", "soul"), "wave" to f("electronic"), "gaze" to f("rock"),
        "step" to f("electronic"), "tronica" to f("electronic"), "house" to f("dance", "electronic"), "hop" to f("hiphop"),
    )

    /** Russian stems (tokens starting with them). */
    private val STEMS: List<Pair<String, Set<String>>> = listOf(
        "рок" to f("rock"), "поп" to f("pop"), "эстрад" to f("pop"), "рэп" to f("hiphop"), "реп" to f("hiphop"), "джаз" to f("jazz"),
        "блюз" to f("blues"), "фолк" to f("folk"), "бард" to f("folk"), "метал" to f("metal", "rock"), "панк" to f("punk", "rock"),
        "электрон" to f("electronic"), "танцев" to f("dance", "electronic"), "классич" to f("classical"), "классик" to f("classical"),
        "саундтрек" to f("soundtrack"), "шансон" to f("chanson"), "регги" to f("reggae"), "соул" to f("soul"), "эмбиент" to f("ambient"),
        "альтернатив" to f("rock"), "хаус" to f("dance", "electronic"), "техно" to f("dance", "electronic"),
    )

    private val IGNORE = setOf("music", "musik", "музыка", "песня", "песни", "русский", "русская", "the", "and", "n", "of", "genre", "misc", "other")

    private fun tokens(value: String): List<String> {
        val out = ArrayList<String>()
        var start = -1
        for (i in value.indices) {
            if (value[i].isLetterOrDigit()) { if (start < 0) start = i } else if (start >= 0) { out += value.substring(start, i); start = -1 }
        }
        if (start >= 0) out += value.substring(start)
        return out
    }

    private fun wordFamilies(t: String): Set<String>? {
        WORDS[t]?.let { return it }
        if (t.firstOrNull()?.let { it in 'а'..'я' || it == 'ё' } == true) {
            for ((stem, fam) in STEMS) if (if (stem.length <= 4) t == stem else t.startsWith(stem)) return fam
            return null
        }
        for ((head, fam) in HEADS) {
            if (t.length >= head.length + 3 && t.endsWith(head)) {
                val prefix = t.substring(0, t.length - head.length)
                return fam + (WORDS[prefix] ?: emptySet())
            }
        }
        return null
    }

    /** The families of one genre value; [raw] = true when the value kept its raw self (no family matched). */
    fun valueFamilies(value: String): Pair<Set<String>, Boolean> {
        val raw = value.trim().lowercase()
        if (raw.isEmpty() || raw == "<unknown>" || raw == "unknown" || raw == "other") return emptySet<String>() to false
        val toks = tokens(raw)
        val out = LinkedHashSet<String>()
        val used = BooleanArray(toks.size)
        for ((phrase, fam) in PHRASES) {
            for (s in 0..toks.size - phrase.size) {
                if ((phrase.indices).all { toks[s + it] == phrase[it] && !used[s + it] }) {
                    out += fam
                    for (k in phrase.indices) used[s + k] = true
                }
            }
        }
        for ((i, t) in toks.withIndex()) {
            if (used[i] || t in IGNORE) continue
            wordFamilies(t)?.let { out += it }
        }
        if (out.isEmpty() && toks.all { it in IGNORE }) return emptySet<String>() to false
        return if (out.isEmpty()) setOf(raw) to true else out to false
    }

    /** Every family of a possibly multi-valued tag, in first-seen order. */
    fun families(genre: String?): Set<String> {
        val out = LinkedHashSet<String>()
        for (piece in genre?.split(';', '/', ',', '|').orEmpty()) {
            if (piece.isBlank()) continue
            out += valueFamilies(piece).first
        }
        return out
    }

    /** Overlap coefficient |A∩B| / min(|A|, |B|); 0 when either is empty. */
    fun overlap(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val shared = a.count { it in b }
        return shared.toFloat() / minOf(a.size, b.size)
    }
}
