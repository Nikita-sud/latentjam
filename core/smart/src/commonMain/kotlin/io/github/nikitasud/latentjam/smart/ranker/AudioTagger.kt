/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.text.AssetBytes
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * PLAN_FINAL §1.3's audio tagger: imputes the genre family and the artist descriptor of a track whose tags or artist
 * the knowledge pack cannot supply, from its A0 audio embedding. The head is
 * tools/research/listenbrainz_eval/audio_tagger.py's export ("LJ audio tagger head v1": a linear family classifier with
 * temperature, and a descriptor that blends the family means with a ridge regression), read from its JSON metadata and
 * weight file (fp32 or int8 with per-row scales). Every imputed block carries the "imputed" flag in the ranker.
 */
public class AudioTagger private constructor(
    private val inputMean: FloatArray,
    private val inputStd: FloatArray,
    private val familyWeight: Array<DoubleArray>,
    private val familyBias: FloatArray,
    private val temperature: Double,
    private val classes: List<String?>,
    private val familyMeans: Array<FloatArray>,
    private val ridgeWeight: Array<DoubleArray>,
    private val ridgeBias: FloatArray,
) {
    /** What the head says about one track. [family] is null for its "other" class (no family in the ranker). */
    public class Imputed(public val family: String?, public val probabilities: DoubleArray, public val descriptor: FloatArray)

    public val inputDim: Int get() = inputMean.size

    public fun impute(embedding: FloatArray): Imputed? {
        if (embedding.size != inputMean.size) return null
        var norm = 0.0
        for (v in embedding) norm += v.toDouble() * v
        if (!(norm > 0.0) || !norm.isFinite()) return null
        val scale = 1.0 / sqrt(norm)
        val z = DoubleArray(embedding.size) { (embedding[it] * scale - inputMean[it]) / inputStd[it] }
        val logits = DoubleArray(familyWeight.size) { c -> dot(familyWeight[c], z) + familyBias[c] }
        val max = logits.max()
        val e = DoubleArray(logits.size) { exp((logits[it] - max) / temperature) }
        val total = e.sum()
        val p = DoubleArray(e.size) { e[it] / total }
        var best = 0
        for (c in p.indices) if (p[c] > p[best]) best = c
        val mean = DoubleArray(ridgeBias.size) { d -> p.indices.sumOf { c -> p[c] * familyMeans[c][d] } }
        val ridge = DoubleArray(ridgeBias.size) { d -> dot(ridgeWeight[d], z) + ridgeBias[d] }
        val blended = unit(mean).let { a -> unit(ridge).let { b -> DoubleArray(a.size) { a[it] + b[it] } } }
        val descriptor = unit(blended)
        return Imputed(classes[best], p, FloatArray(descriptor.size) { descriptor[it].toFloat() })
    }

    private fun dot(w: DoubleArray, z: DoubleArray): Double {
        var s = 0.0
        for (i in w.indices) s += w[i] * z[i]
        return s
    }

    private fun unit(v: DoubleArray): DoubleArray {
        val n = sqrt(v.sumOf { it * it })
        return if (n > 0.0) DoubleArray(v.size) { v[it] / n } else v
    }

    public companion object {
        /** Null for a missing, malformed or foreign head: the ranker then imputes nothing. */
        public fun parse(metadata: String, weights: AssetBytes, int8: Boolean = true): AudioTagger? = runCatching {
            @Suppress("UNCHECKED_CAST")
            val root = MiniJson(metadata).parse() as Map<String, Any?>
            require(root["format"] == "LJ audio tagger head v1")
            val family = root.getValue("family") as Map<String, Any?>
            val classes = (family.getValue("classes") as List<*>).map { it as String }
            @Suppress("UNCHECKED_CAST")
            val tensors = (root.getValue(if (int8) "tensors_int8" else "tensors_fp32") as List<Map<String, Any?>>)
                .associateBy { it.getValue("name") as String }
            fun f32(at: Int): Float {
                var bits = 0
                for (b in 3 downTo 0) bits = (bits shl 8) or (weights[at + b].toInt() and 0xff)
                return Float.fromBits(bits)
            }
            fun shape(name: String) = (tensors.getValue(name).getValue("shape") as List<*>).map { (it as Number).toInt() }
            fun vector(name: String): FloatArray {
                val t = tensors.getValue(name)
                val at = (t.getValue("offset") as Number).toInt()
                return FloatArray(shape(name).fold(1) { a, b -> a * b }) { f32(at + 4 * it) }
            }
            // Doubles, as the reference dequantises (q · scale in float64): parity to 1e-9.
            fun matrix(name: String): Array<DoubleArray> {
                val t = tensors.getValue(name)
                val (rows, cols) = shape(name)
                val at = (t.getValue("offset") as Number).toInt()
                return if (t["dtype"] == "int8") {
                    val scales = (t.getValue("scale_offset") as Number).toInt()
                    Array(rows) { r -> val s = f32(scales + 4 * r).toDouble(); DoubleArray(cols) { c -> weights[at + r * cols + c] * s } }
                } else {
                    Array(rows) { r -> DoubleArray(cols) { c -> f32(at + 4 * (r * cols + c)).toDouble() } }
                }
            }
            val means = vector("family_descriptor_means")
            val dim = shape("family_descriptor_means")[1]
            AudioTagger(
                inputMean = vector("input_mean"),
                inputStd = vector("input_std").also { s -> require(s.all { it > 0f }) },
                familyWeight = matrix("fc0.weight"),
                familyBias = vector("fc0.bias"),
                temperature = (family.getValue("temperature") as Number).toDouble(),
                classes = classes.map { if (it == "other") null else it },
                familyMeans = Array(classes.size) { c -> FloatArray(dim) { means[c * dim + it] } },
                ridgeWeight = matrix("ridge.weight"),
                ridgeBias = vector("ridge.bias"),
            )
        }.getOrNull()
    }
}
