/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

/**
 * A conditional-logit ranker's weights: score = Σ w · (x − mean) / sd over [features], read from one
 * JSON asset (`ml/smart_ranker.json`) so new weights replace old ones without code changes.
 *
 * ```
 * {"name": "...", "features": ["cos", ...], "mean": [...], "sd": [...], "weights": [...],
 *  "runPenalty": 0.0, "anchor": "seed" | "previous" | "mix", "seedWeight": 2, "previousWeight": 1, "source": "..."}
 * ```
 * [runPenalty] is p in score − p · runLength · sd(hop scores) (SmartChain's artistRunPenalty shape);
 * [anchor] is what the features of each hop are relative to: the queue's seed, the previous pick, or both mixed
 * (seedWeight · s(seed) + previousWeight · s(previous)) / (seedWeight + previousWeight) from hop 2 on
 * (proto_ranker2.py `Query.queue`: 2 : 1).
 * Every feature must be one [RankerFeatures] computes; anything else refuses the asset.
 */
public class RankerModel(
    public val name: String,
    public val features: List<String>,
    private val mean: DoubleArray,
    private val sd: DoubleArray,
    private val weights: DoubleArray,
    public val runPenalty: Double,
    public val anchor: Anchor,
    public val seedWeight: Double = 2.0,
    public val previousWeight: Double = 1.0,
    /** Near-best width in sds of the penalised hop scores (PLAN_FINAL §8: 0.01); 0 keeps exact ties only. */
    public val epsilon: Double = 0.0,
    /** Logit units off a candidate that would be the third in a row by one artist (§8.3: 8); 0 = no cap. */
    public val thirdInRowCap: Double = 0.0,
    /** Candidates, retrieval quotas and the other serving parameters (PLAN_FINAL §1.1, §7.2b). */
    public val serving: Serving = Serving(),
    /** Raw-unit weight of the audio cosine; null = §1.3's rule, max(0, the descriptor cosine's raw weight). */
    private val audioWeightOverride: Double? = null,
    /**
     * Where the logpop column comes from (`inputs.logpop`): [LOGPOP_NONE] = 0 for every track, [LOGPOP_ARTIST] = the
     * artist popularity of the track's pack entity, 0 when unknown. Any other value refuses the asset.
     */
    public val logpop: String = LOGPOP_NONE,
    /** PLAN_L1 §2a's artist re-entry term; null = the D0 decoder unchanged. */
    public val artistReentry: ArtistReentry? = null,
) {
    /**
     * A candidate that does not continue the run loses λ · sd · Σ_{j=0..} γ^j · m(i, back[1 + j]) over the queue's
     * picks t−2 … t−k (newest first; the seed counts as the oldest pick); m = 1 for the same artist key, [credit] for a
     * shared artist-set credit only, else 0 (reference.py `decode`, `redundancy`).
     */
    public class ArtistReentry(
        public val lambda: Double,
        public val k: Int = 10,
        public val gamma: Double = 0.7,
        public val credit: Double = 0.5,
        /**
         * PLAN_L1 §10.3's decoder (reference.py `decode_v5`): the run is anchored on its first item and a pick sharing
         * a credit with it (m > 0) continues it; the run term is λ_run · L · sd · m(i, anchor); re-entry applies only
         * when m(i, anchor) = 0, over the picks before the current run at distance 2 … k. No cap.
         */
        public val creditRun: Boolean = false,
    ) {
        init {
            require(lambda.isFinite() && lambda >= 0.0 && k >= 1 && gamma.isFinite() && gamma >= 0.0 && credit.isFinite() && credit >= 0.0)
        }
    }

    /**
     * @property wholeLibraryUpTo libraries with at most this many eligible tracks are scored whole (as the research
     *   scored them); larger ones score the union of the retrievers' quotas
     * @property quotas per retriever: audio (R1), colisten (R2), artist (R4, same artist set or album), family (R5),
     *   own (FULL R6 / R7: the listener's favourites and transitions)
     * @property artistKey "engine" (the old artist key the research trained on) or "artistSet" (the primary of the
     *   §1.4 artist set) for the same-artist feature and the run penalty
     * @property recentWindow the song-version dedup and recency window, min(this, n / 2) (§7.2b)
     * @property hopBudgetMs the per-hop time budget (§7.2b: 150 ms); a plan stops at length × budget and the ladder
     *   fills the rest
     */
    public class Serving(
        public val wholeLibraryUpTo: Int = 600,
        public val quotas: Map<String, Int> = mapOf("audio" to 100, "colisten" to 150, "artist" to 100, "family" to 150, "own" to 100),
        public val artistKey: String = "engine",
        public val recentWindow: Int = 50,
        public val hopBudgetMs: Long = 150,
    )

    /** The audio term's raw weight (§1.3): the override, else max(0, raw weight of "cos"). */
    public val audioWeight: Double = audioWeightOverride ?: features.indexOf("cos").let { i ->
        if (i < 0) 0.0 else maxOf(0.0, weights[i] / sd[i])
    }

    private val audioColumn = RankerFeatures.NAMES.indexOf("audio_cos")
    public enum class Anchor { SEED, PREVIOUS, MIX }

    /** Positions of [features] in [RankerFeatures.NAMES]. */
    internal val columns: IntArray = IntArray(features.size) { RankerFeatures.NAMES.indexOf(features[it]) }

    init {
        require(features.isNotEmpty() && mean.size == features.size && sd.size == features.size && weights.size == features.size)
        require(columns.all { it >= 0 }) { "unknown ranker feature" }
        require(sd.all { it > 0.0 && it.isFinite() } && mean.all(Double::isFinite) && weights.all(Double::isFinite))
        require(runPenalty.isFinite() && runPenalty >= 0.0)
        require(seedWeight >= 0.0 && previousWeight >= 0.0 && seedWeight + previousWeight > 0.0)
        require(epsilon.isFinite() && epsilon >= 0.0)
        require(thirdInRowCap.isFinite() && thirdInRowCap >= 0.0)
        require(logpop == LOGPOP_NONE || logpop == LOGPOP_ARTIST) { "unknown logpop input" }
    }

    /**
     * [score]'s terms for one row: w · (x − mean) / sd per feature in [features] order, then the audio term (0 when
     * [audioWeight] is 0). They sum to [score] (diagnostics).
     */
    public fun contributions(row: DoubleArray): DoubleArray = DoubleArray(columns.size + 1) { i ->
        when {
            i < columns.size -> weights[i] * ((row[columns[i]] - mean[i]) / sd[i])
            audioWeight != 0.0 && row.size > audioColumn -> audioWeight * row[audioColumn]
            else -> 0.0
        }
    }

    /** The score of one candidate's full feature row (in [RankerFeatures.NAMES] order). */
    public fun score(row: DoubleArray): Double {
        var total = 0.0
        for (i in columns.indices) total += weights[i] * ((row[columns[i]] - mean[i]) / sd[i])
        if (audioWeight != 0.0 && row.size > audioColumn) total += audioWeight * row[audioColumn]
        return total
    }

    public companion object {
        public const val LOGPOP_NONE: String = "none"
        public const val LOGPOP_ARTIST: String = "artist_pop"

        /**
         * The rule-only path's model (§7.2b): RULE_SET constants in code, never read from an asset. Descriptor and
         * family match, co-listening, language and era, the Balanced run penalty, the 2 : 1 seed / previous mix.
         */
        public val RULE_SET: RankerModel = RankerModel(
            name = "rule-only",
            features = listOf("cos", "same_family", "same_lang", "decade_dist", "colisten", "audio_cos"),
            mean = DoubleArray(6),
            sd = DoubleArray(6) { 1.0 },
            weights = doubleArrayOf(1.0, 0.5, 0.3, -0.1, 1.0, 1.0),
            runPenalty = 0.5,
            anchor = Anchor.MIX,
            epsilon = 0.01,
            thirdInRowCap = 8.0,
            audioWeightOverride = 0.0,
        )

        /** Null for a missing, malformed or incompatible asset: SMART then keeps its chain. */
        @Suppress("UNCHECKED_CAST")
        public fun parse(json: String): RankerModel? = runCatching {
            @Suppress("UNCHECKED_CAST")
            val root = MiniJson(json).parse() as Map<String, Any?>
            fun numbers(key: String) = (root.getValue(key) as List<*>).map { (it as Number).toDouble() }.toDoubleArray()
            RankerModel(
                name = root["name"] as? String ?: "ranker",
                features = (root.getValue("features") as List<*>).map { it as String },
                mean = numbers("mean"),
                sd = numbers("sd"),
                weights = numbers("weights"),
                runPenalty = (root["runPenalty"] as? Number)?.toDouble() ?: 0.0,
                anchor = when (root["anchor"] ?: "seed") {
                    "seed" -> Anchor.SEED
                    "previous" -> Anchor.PREVIOUS
                    "mix" -> Anchor.MIX
                    else -> error("anchor")
                },
                seedWeight = (root["seedWeight"] as? Number)?.toDouble() ?: 2.0,
                previousWeight = (root["previousWeight"] as? Number)?.toDouble() ?: 1.0,
                epsilon = (root["epsilon"] as? Number)?.toDouble() ?: 0.0,
                thirdInRowCap = (root["thirdInRowCap"] as? Number)?.toDouble() ?: 0.0,
                serving = (root["serving"] as? Map<*, *>)?.let { m ->
                    val d = Serving()
                    Serving(
                        wholeLibraryUpTo = (m["wholeLibraryUpTo"] as? Number)?.toInt() ?: d.wholeLibraryUpTo,
                        quotas = (m["quotas"] as? Map<*, *>)?.entries?.associate { (k, v) -> k as String to (v as Number).toInt() } ?: d.quotas,
                        artistKey = (m["artistKey"] as? String ?: d.artistKey).also { require(it == "engine" || it == "artistSet") },
                        recentWindow = (m["recentWindow"] as? Number)?.toInt() ?: d.recentWindow,
                        hopBudgetMs = (m["hopBudgetMs"] as? Number)?.toLong() ?: d.hopBudgetMs,
                    )
                } ?: Serving(),
                audioWeightOverride = (root["audioWeight"] as? Number)?.toDouble(),
                logpop = (root["inputs"] as? Map<*, *>)?.get("logpop") as? String ?: LOGPOP_NONE,
                artistReentry = (root["artistReentry"] as? Map<*, *>)?.let { m ->
                    ArtistReentry(
                        lambda = (m["lambda"] as Number).toDouble(),
                        k = (m["k"] as? Number)?.toInt() ?: 10,
                        gamma = (m["gamma"] as? Number)?.toDouble() ?: 0.7,
                        credit = (m["credit"] as? Number)?.toDouble() ?: 0.5,
                        creditRun = m["creditRun"] == true,
                    )
                },
            )
        }.getOrNull()
    }
}

/** A strict little JSON reader for the ranker asset: objects, arrays, strings, numbers, booleans, null. */
internal class MiniJson(private val text: String) {
    private var at = 0

    fun parse(): Any? = value().also { space(); require(at == text.length) }

    private fun space() { while (at < text.length && text[at].isWhitespace()) at++ }

    private fun value(): Any? {
        space()
        require(at < text.length)
        return when (val c = text[at]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> if (c == '-' || c.isDigit()) num() else error("json")
        }
    }

    private fun word(w: String, v: Any?): Any? { require(text.startsWith(w, at)); at += w.length; return v }

    private fun obj(): Map<String, Any?> {
        at++
        val out = LinkedHashMap<String, Any?>()
        space()
        if (text[at] == '}') { at++; return out }
        while (true) {
            space(); val key = str(); space(); require(text[at] == ':'); at++
            out[key] = value(); space()
            when (text[at++]) { ',' -> continue; '}' -> return out; else -> error("json") }
        }
    }

    private fun arr(): List<Any?> {
        at++
        val out = ArrayList<Any?>()
        space()
        if (text[at] == ']') { at++; return out }
        while (true) {
            out += value(); space()
            when (text[at++]) { ',' -> continue; ']' -> return out; else -> error("json") }
        }
    }

    private fun str(): String {
        require(text[at] == '"'); at++
        val out = StringBuilder()
        while (true) {
            val c = text[at++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> when (val e = text[at++]) {
                    'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                    'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                    'u' -> { out.append(text.substring(at, at + 4).toInt(16).toChar()); at += 4 }
                    else -> out.append(e)
                }
                else -> out.append(c)
            }
        }
    }

    private fun num(): Double {
        val start = at
        while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
        return text.substring(start, at).toDouble()
    }
}
