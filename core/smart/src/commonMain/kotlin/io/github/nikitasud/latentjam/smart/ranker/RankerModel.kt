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
 *  "runPenalty": 0.0, "anchor": "seed" | "previous", "source": "..."}
 * ```
 * [runPenalty] is p in score − p · runLength · sd(hop scores) (SmartChain's artistRunPenalty shape);
 * [anchor] is what the features of each hop are relative to: the queue's seed, or the previous pick.
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
) {
    public enum class Anchor { SEED, PREVIOUS }

    /** Positions of [features] in [RankerFeatures.NAMES]. */
    internal val columns: IntArray = IntArray(features.size) { RankerFeatures.NAMES.indexOf(features[it]) }

    init {
        require(features.isNotEmpty() && mean.size == features.size && sd.size == features.size && weights.size == features.size)
        require(columns.all { it >= 0 }) { "unknown ranker feature" }
        require(sd.all { it > 0.0 && it.isFinite() } && mean.all(Double::isFinite) && weights.all(Double::isFinite))
        require(runPenalty.isFinite() && runPenalty >= 0.0)
    }

    /** The score of one candidate's full feature row (in [RankerFeatures.NAMES] order). */
    public fun score(row: DoubleArray): Double {
        var total = 0.0
        for (i in columns.indices) total += weights[i] * ((row[columns[i]] - mean[i]) / sd[i])
        return total
    }

    public companion object {
        /** Null for a missing, malformed or incompatible asset: SMART then keeps its chain. */
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
                    else -> error("anchor")
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
