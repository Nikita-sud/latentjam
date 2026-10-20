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
    /** PLAN_AUDIO_ON §8.2's energy-continuity term (credit-run decoder only); null = none. */
    public val energy: Energy? = null,
    /** PLAN_AUDIO_ON §8.8.3's energy-band R1 (R1e): the band's |Δe| width; null = plain R1. */
    public val r1EnergyTau: Double? = null,
    /** PLAN_AUDIO_ON §8.2 arm P: the audio term scores against the previous pick (the seed at hop 1), in the decoder. */
    public val audioAgainstPrevious: Boolean = false,
    /** PLAN_L1 §10.5 RULE: an imputed descriptor reads unknown for cos / cos_z / desc_known; an imputed family serves R5 only. */
    public val imputedUnknown: Boolean = false,
    /** PLAN_L1 §2b: FULL's seed gate on the listener's columns; null = ungated. */
    public val seedGate: SeedGate? = null,
    /** PLAN_L1 §2c: the impression discount; null = none. */
    public val freshness: Freshness? = null,
    /** FULL L2 (audit 06 #4): the score adds the embedded [seedGate] core's metadata logit (no audio). */
    public val coreOffset: Boolean = false,
    /** R1e's band τ is in units of the retrieval source's e spread. */
    public val r1EnergyPoolScale: Boolean = false,
    /** The audio term on the per-pool z of audio_cos (known rows' mean / sd; unknown rows 0), not raw audio_cos. */
    public val audioZ: Boolean = false,
    public val softNovelty: SoftNovelty? = null,
    /** Fast 3: in a previous-pick score these columns become share · x(prev) + (1 − share) · x(seed). */
    public val anchorShare: AnchorShare? = null,
    /** Fast 3: −λ when the seed's and the candidate's families are both known and differ. */
    public val familyMismatch: Double? = null,
    /** Fast 3.1: unordered family pairs that take [familyNearScale] · λ instead of the full familyMismatch λ. */
    public val familyNear: Set<Set<String>> = emptySet(),
    public val familyNearScale: Double = 0.0,
    /** Fast 3.1: −λ for a holiday track when the press is outside 15 Nov – 10 Jan (UTC) and the seed is not one. */
    public val holidaySeason: Double? = null,
    /** Fast 3.2 decoder rule penalties (subtracted after the spread): long / short tracks against the seed's length. */
    public val durationRule: DurationRule? = null,
    /** Fast 3.2: −λ for an intro / outro / skit / interlude / reprise / "(short)" title unless the seed has one. */
    public val titleFlags: Double? = null,
    /** Fast 3.2 (off in the shipped models): a seed with no family and no descriptor scores on the seed only. */
    public val unknownSeedAnchor: Boolean = false,
    /** Fast 3: −λ for a candidate in [ClassMismatch.families] when the seed is not. */
    public val classMismatch: ClassMismatch? = null,
    /** Fast 4: seed-anchored continuity on cached audio heads, in head-name order. */
    public val continuity: List<Continuity> = emptyList(),
) {
    /** λ · |Δe| to the previous pick ("prev"), or λ · max(0, |Δe to the seed| − τ) ("seedHinge"). */
    /** [poolScale]: |Δe| in units of the pool's e spread (population sd of the allowed rows' known e, floored). */
    public class Energy(public val lambda: Double, public val seedHinge: Boolean, public val tau: Double, public val poolScale: Boolean = false)

    public class AnchorShare(public val share: Double, public val columns: List<String>) {
        internal val columnIndexes: IntArray = IntArray(columns.size) { RankerFeatures.NAMES.indexOf(columns[it]).also { i -> require(i >= 0) } }
    }

    public class ClassMismatch(public val lambda: Double, public val families: Set<String>)

    /**
     * −λ · max(0, |x − x_seed| / pool sd − τ) over the head [name]'s cached value x; with [seedFamilies], only for a
     * seed of those families; with [seedMin], only when the seed's own value is at least it.
     */
    public class Continuity(
        public val name: String,
        public val lambda: Double,
        public val tau: Double,
        public val seedFamilies: Set<String>? = null,
        public val seedMin: Double? = null,
    )

    public class DurationRule(
        public val longMs: Long,
        public val longLambda: Double,
        public val seedLongMs: Long,
        public val shortMs: Long,
        public val shortLambda: Double,
    )

    /** The novelty set beyond its [hardLast] newest stays in the pool, losing λ · sd · (1 − age / 24 h). */
    public class SoftNovelty(public val lambda: Double, public val hardLast: Int)

    /**
     * g_i = σ(a · clip((CORE logit_i − mu) / sigma, ±clip)) from the seed-anchor rows before gating (the [core] model's
     * metadata logit, never its audio term) multiplies [columns] of every row; [orderR6] also orders R6 by ACT-R + ln g.
     */
    public class SeedGate(
        public val core: RankerModel,
        public val mu: Double,
        public val sigma: Double,
        public val a: Double,
        public val clip: Double,
        public val columns: IntArray,
        public val orderR6: Boolean,
    )

    /** Σ_p delta^p · [track shown at the p-th newest press] over the last [presses] presses; t −= λ · sd · that. */
    public class Freshness(
        public val lambda: Double,
        public val delta: Double,
        public val presses: Int,
        /** How far back an impression counts (FULL 14 days, CORE 2 hours); null = the device's default. */
        public val windowMs: Long? = null,
        /** Fast 3: the largest penalty in sd units; null = none. */
        public val cap: Double? = null,
        /** Fast 3: tracks played after being shown take no impression weight. */
        public val engagedExempt: Boolean = false,
    )

    /** The audio encoder the model's audio terms were fitted on (`inputs.audioEncoder`); null = undeclared. */
    public var audioEncoder: String? = null
        internal set

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
        /** PLAN_L2: pool-mean popularity fill, per-artist cap per retriever, fill to [poolTarget]. */
        public val l2: Boolean = false,
        public val poolTarget: Int = wholeLibraryUpTo,
        public val artistCap: Int = 15,
        /** Fast 3: a seed with no family and no descriptor fills the L2 pool from its own audio neighbours first. */
        public val unknownSeedAudioFill: Boolean = false,
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
            audioWeight != 0.0 && !audioAgainstPrevious && row.size > audioColumn -> audioWeight * row[audioColumn]
            else -> 0.0
        }
    }

    /** The score of one candidate's full feature row (in [RankerFeatures.NAMES] order). */
    public fun score(row: DoubleArray): Double {
        var total = 0.0
        for (i in columns.indices) total += weights[i] * ((row[columns[i]] - mean[i]) / sd[i])
        if (audioWeight != 0.0 && !audioAgainstPrevious && !audioZ && row.size > audioColumn) total += audioWeight * row[audioColumn]
        if (coreOffset) seedGate?.let { total += it.core.score(row) }
        return total
    }

    public companion object {
        /** reference.py GATED: the listener's columns the seed gate scales by default. */
        public val GATED_COLUMNS: List<String> = listOf(
            "heard", "log_freq", "freq_rank", "recency", "recency_rank", "actr", "actr_rank", "is_favourite", "favourited_days_ago",
        )
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

        /** The keys a model file may carry (audit 2026-10-20 #12): any other key refuses the file. */
        private val KEYS = mapOf(
            "" to setOf(
                "schema", "name", "source", "provenance", "features", "mean", "sd", "weights", "runPenalty", "anchor",
                "seedWeight", "previousWeight", "epsilon", "thirdInRowCap", "serving", "inputs", "audioWeight",
                "artistReentry", "energy", "r1Energy", "audioAnchor", "imputedDescriptor", "seedGate", "freshness", "coreOffset",
                "audioZ", "softNovelty", "anchorShare", "colistenPrevShare", "familyMismatch", "classMismatch", "continuity", "holidaySeason",
                "durationRule", "titleFlags", "unknownSeedAnchor",
            ),
            "anchorShare" to setOf("share", "columns"),
            "familyMismatch" to setOf("lambda", "near", "nearScale"),
            "holidaySeason" to setOf("lambda"),
            "durationRule" to setOf("longMs", "longLambda", "seedLongMs", "shortMs", "shortLambda"),
            "titleFlags" to setOf("lambda"),
            "classMismatch" to setOf("lambda", "families"),
            "softNovelty" to setOf("lambda", "hardLast"),
            "serving" to setOf("wholeLibraryUpTo", "quotas", "artistKey", "recentWindow", "hopBudgetMs", "l2", "poolTarget", "artistCap", "unknownSeedAudioFill"),
            "inputs" to setOf("logpop", "colisten", "lfm", "audioEncoder"),
            "artistReentry" to setOf("lambda", "k", "gamma", "credit", "creditRun"),
            "energy" to setOf("lambda", "mode", "tau", "scale"),
            "r1Energy" to setOf("tau", "scale"),
            "seedGate" to setOf("core", "coreSha256", "mu", "sigma", "a", "clip", "columns", "orderR6"),
            "freshness" to setOf("lambda", "delta", "presses", "windowMs", "cap", "engaged"),
        )

        /** The first key [root] carries that this app does not implement, or null. */
        internal fun unknownKey(root: Map<*, *>): String? {
            fun check(path: String, m: Map<*, *>): String? {
                val allowed = KEYS[path] ?: return null
                for (k in m.keys) {
                    val key = k as? String ?: return "$path.?"
                    if (key !in allowed) return if (path.isEmpty()) key else "$path.$key"
                    val v = m[key]
                    if (v is Map<*, *>) check(if (path.isEmpty()) key else "$path.$key", v)?.let { return it }
                }
                return null
            }
            return check("", root)
        }

        /** [parse], with why a file is refused (an unknown key, a malformed value). */
        public fun parseOrProblem(json: String): Pair<RankerModel?, String?> {
            val root = runCatching { MiniJson(json).parse() as Map<*, *> }.getOrNull() ?: return null to "not a JSON object"
            unknownKey(root)?.let { return null to "unknown model key '$it'" }
            return parse(json)?.let { it to null } ?: (null to "invalid model")
        }

        /** Null for a missing, malformed or incompatible asset (an unknown key included): SMART then keeps its chain. */
        @Suppress("UNCHECKED_CAST")
        public fun parse(json: String): RankerModel? = runCatching {
            @Suppress("UNCHECKED_CAST")
            val root = MiniJson(json).parse() as Map<String, Any?>
            require(unknownKey(root) == null) { "unknown model key" }
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
                        l2 = m["l2"] == true,
                        poolTarget = (m["poolTarget"] as? Number)?.toInt() ?: (m["wholeLibraryUpTo"] as? Number)?.toInt() ?: d.wholeLibraryUpTo,
                        artistCap = (m["artistCap"] as? Number)?.toInt() ?: 15,
                        unknownSeedAudioFill = m["unknownSeedAudioFill"] == true,
                    )
                } ?: Serving(),
                audioWeightOverride = (root["audioWeight"] as? Number)?.toDouble(),
                logpop = (root["inputs"] as? Map<*, *>)?.get("logpop") as? String ?: LOGPOP_NONE,
                energy = (root["energy"] as? Map<*, *>)?.let { m ->
                    Energy(
                        lambda = (m["lambda"] as Number).toDouble(),
                        seedHinge = when (m["mode"] ?: "prev") { "prev" -> false; "seedHinge" -> true; else -> error("energy mode") },
                        tau = (m["tau"] as? Number)?.toDouble() ?: 0.0,
                        poolScale = when (m["scale"]) { null -> false; "pool" -> true; else -> error("energy scale") },
                    )
                },
                r1EnergyTau = (root["r1Energy"] as? Map<*, *>)?.let { (it["tau"] as Number).toDouble() },
                audioAgainstPrevious = root["audioAnchor"] == "previous",
                imputedUnknown = root["imputedDescriptor"] == "unknown",
                coreOffset = root["coreOffset"] == true,
                r1EnergyPoolScale = (root["r1Energy"] as? Map<*, *>)?.get("scale").let { when (it) { null -> false; "pool" -> true; else -> error("r1 scale") } },
                audioZ = root["audioZ"] == true,
                anchorShare = (root["anchorShare"] as? Map<*, *>)?.let { a ->
                    AnchorShare((a["share"] as Number).toDouble(), (a["columns"] as? List<*>)?.map { it as String } ?: listOf("colisten", "lfm_cos"))
                } ?: (root["colistenPrevShare"] as? Number)?.let { AnchorShare(it.toDouble(), listOf("colisten")) },
                familyMismatch = (root["familyMismatch"] as? Map<*, *>)?.let { (it["lambda"] as Number).toDouble() },
                familyNear = ((root["familyMismatch"] as? Map<*, *>)?.get("near") as? List<*>).orEmpty()
                    .map { p -> (p as List<*>).map { it as String }.toSet() }.toSet(),
                familyNearScale = ((root["familyMismatch"] as? Map<*, *>)?.get("nearScale") as? Number)?.toDouble() ?: 0.0,
                holidaySeason = (root["holidaySeason"] as? Map<*, *>)?.let { (it["lambda"] as Number).toDouble() },
                durationRule = (root["durationRule"] as? Map<*, *>)?.let { d ->
                    DurationRule(
                        longMs = (d["longMs"] as? Number)?.toLong() ?: 900_000, longLambda = (d["longLambda"] as? Number)?.toDouble() ?: 0.0,
                        seedLongMs = (d["seedLongMs"] as? Number)?.toLong() ?: 600_000, shortMs = (d["shortMs"] as? Number)?.toLong() ?: 90_000,
                        shortLambda = (d["shortLambda"] as? Number)?.toDouble() ?: 0.0,
                    )
                },
                titleFlags = (root["titleFlags"] as? Map<*, *>)?.let { (it["lambda"] as Number).toDouble() },
                unknownSeedAnchor = root["unknownSeedAnchor"] == "seed",
                classMismatch = (root["classMismatch"] as? Map<*, *>)?.let { c ->
                    ClassMismatch((c["lambda"] as Number).toDouble(), ((c["families"] as? List<*>)?.map { it as String } ?: listOf("soundtrack")).toSet())
                },
                continuity = (root["continuity"] as? Map<*, *>).orEmpty().entries
                    .map { (k, v) ->
                        (v as Map<*, *>).let {
                            Continuity(
                                k as String, (it["lambda"] as Number).toDouble(), (it["tau"] as? Number)?.toDouble() ?: 0.25,
                                seedFamilies = (it["seedFamilies"] as? List<*>)?.takeIf { l -> l.isNotEmpty() }?.map { f -> f as String }?.toSet(),
                                seedMin = (it["seedMin"] as? Number)?.toDouble(),
                            )
                        }
                    }
                    .sortedBy { it.name },
                softNovelty = (root["softNovelty"] as? Map<*, *>)?.let { n ->
                    SoftNovelty(lambda = (n["lambda"] as Number).toDouble(), hardLast = (n["hardLast"] as? Number)?.toInt() ?: 10)
                },
                seedGate = (root["seedGate"] as? Map<*, *>)?.let { g ->
                    val coreMap = (g["core"] as Map<*, *>).toMutableMap().also { it["audioWeight"] = 0.0 }
                    SeedGate(
                        core = requireNotNull(parse(MiniJson.write(coreMap))) { "seedGate core" },
                        mu = (g["mu"] as Number).toDouble(),
                        sigma = (g["sigma"] as Number).toDouble(),
                        a = (g["a"] as Number).toDouble(),
                        clip = (g["clip"] as? Number)?.toDouble() ?: 3.0,
                        columns = ((g["columns"] as? List<*>)?.map { it as String } ?: GATED_COLUMNS)
                            .map { c -> RankerFeatures.NAMES.indexOf(c).also { require(it >= 0) { "gate column $c" } } }.toIntArray(),
                        orderR6 = g["orderR6"] == true,
                    )
                },
                freshness = (root["freshness"] as? Map<*, *>)?.let { f ->
                    Freshness(
                        lambda = (f["lambda"] as Number).toDouble(),
                        delta = (f["delta"] as? Number)?.toDouble() ?: 0.5,
                        presses = (f["presses"] as? Number)?.toInt() ?: 5,
                        windowMs = (f["windowMs"] as? Number)?.toLong(),
                        cap = (f["cap"] as? Number)?.toDouble(),
                        engagedExempt = when (f["engaged"]) { null -> false; "exempt" -> true; else -> error("freshness engaged") },
                    )
                },
                artistReentry = (root["artistReentry"] as? Map<*, *>)?.let { m ->
                    ArtistReentry(
                        lambda = (m["lambda"] as Number).toDouble(),
                        k = (m["k"] as? Number)?.toInt() ?: 10,
                        gamma = (m["gamma"] as? Number)?.toDouble() ?: 0.7,
                        credit = (m["credit"] as? Number)?.toDouble() ?: 0.5,
                        creditRun = m["creditRun"] == true,
                    )
                },
            ).also { m -> m.audioEncoder = (root["inputs"] as? Map<*, *>)?.get("audioEncoder") as? String }
        }.getOrNull()
    }
}

/** A strict little JSON reader for the ranker asset: objects, arrays, strings, numbers, booleans, null. */
internal class MiniJson(private val text: String) {
    companion object {
        /** Writes what [parse] reads (a nested model block re-parsed by [RankerModel.parse]). */
        fun write(v: Any?): String = when (v) {
            null -> "null"
            is String -> buildString {
                append('"')
                for (c in v) when (c) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); else -> append(c) }
                append('"')
            }
            is Boolean -> v.toString()
            is Number -> v.toDouble().let { d -> if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString() }
            is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> write(k as String) + ":" + write(x) }
            is List<*> -> v.joinToString(",", "[", "]") { write(it) }
            else -> error("json")
        }
    }

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
