/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart.ranker

import io.github.nikitasud.latentjam.smart.AUDIO_MODEL_VERSION
import io.github.nikitasud.latentjam.smart.text.ArtistKnowledge
import io.github.nikitasud.latentjam.smart.text.ArtistFamilies
import io.github.nikitasud.latentjam.smart.text.ArtistNeighbours
import io.github.nikitasud.latentjam.smart.text.ArtistPopularity
import io.github.nikitasud.latentjam.smart.text.ArtistVectors
import io.github.nikitasud.latentjam.smart.text.AssetBytes
import io.github.nikitasud.latentjam.smart.text.KnownArtistTable
import io.github.nikitasud.latentjam.smart.text.KnownArtists

/**
 * The ranker's assets, loaded lazily and checked against `ml/smart_manifest.json` (PLAN_FINAL §7.2: a versioned,
 * checksummed manifest): every listed file must exist with its size and SHA-256. Any failure leaves [healthy] false
 * and the ranker runs the rule-only path (never today's chain, which shares these assets); a missing optional asset
 * (co-listening, LightFM, artist list, tagger, FULL model, artist popularity, artist families) only drops its feature. The popularity
 * table is read only when a model's `inputs.logpop` is artist_pop.
 *
 * [read] returns an asset's bytes by its file name under ml/, or null when absent.
 *
 * Every audio-derived asset declares the encoder it was fitted on (audit 2026-10-20 #1): the manifest's
 * `audioEncoder`, `embedding_version.txt`, the semantic head's `encoder=` line and each model's `inputs.audioEncoder`
 * must all name [audioEncoder], the encoder the app runs. A mismatch is a [problem] (rule-only), never a silent
 * mix of vectors from one encoder with heads fitted on another.
 */
public class SmartRankerAssets(
    private val knowledge: ArtistKnowledge?,
    private val read: (name: String) -> AssetBytes?,
    private val audio: AudioSource = AudioSource.NONE,
    private val audioEncoder: String = AUDIO_MODEL_VERSION,
) {
    /** Why the manifest check failed, or null when every listed asset matched. */
    public var problem: String? = null
        private set

    public val healthy: Boolean get() = ranker.let { problem == null }

    public val ranker: SmartRanker by lazy {
        problem = verify() ?: encoderMismatch()
        val parsed = text(MODEL)?.let(RankerModel::parseOrProblem)
        val model = parsed?.first?.takeIf { it.audioEncoder == null || it.audioEncoder == audioEncoder }
        if (model == null && problem == null) {
            problem = when {
                parsed == null -> "$MODEL missing"
                parsed.second != null -> "$MODEL: ${parsed.second}"
                else -> "$MODEL fitted on audio encoder ${parsed.first?.audioEncoder}, the app runs $audioEncoder"
            }
        }
        val tagger = runCatching {
            val meta = text(TAGGER_META)
            val weights = read(TAGGER_WEIGHTS)
            if (meta != null && weights != null) AudioTagger.parse(meta, weights) else null
        }.getOrNull()
        val fullParsed = text(FULL_MODEL)?.let(RankerModel::parseOrProblem)
        val full = fullParsed?.first?.takeIf { it.audioEncoder == null || it.audioEncoder == audioEncoder }
        if (fullParsed != null && full == null) {
            fullProblem = fullParsed.second ?: "fitted on audio encoder ${fullParsed.first?.audioEncoder}, the app runs $audioEncoder"
            println("SMART: $FULL_MODEL refused ($fullProblem); Learn from my habits plans with the core model")
        }
        problem?.let { println("SMART: ranker assets refused ($it); planning rule-only") }
        val wantsPopularity = listOfNotNull(model, full).any { it.logpop == RankerModel.LOGPOP_ARTIST }
        SmartRanker(
            model = model ?: RankerModel.RULE_SET,
            knowledge = knowledge,
            neighbours = runCatching { read(COLISTEN)?.let(ArtistNeighbours::parse) }.getOrNull(),
            vectors = runCatching { read(LFM)?.let(ArtistVectors::parse) }.getOrNull(),
            known = runCatching { read(KNOWN)?.let(KnownArtistTable::parse) }.getOrNull() ?: KnownArtists.NONE,
            tagger = tagger,
            audio = audio,
            fullModel = full,
            popularity = if (wantsPopularity) runCatching { read(POPULARITY)?.let(ArtistPopularity::parse) }.getOrNull() else null,
            families = runCatching { read(FAMILIES)?.let(ArtistFamilies::parse) }.getOrNull(),
        )
    }

    /** Why the FULL model was refused (the core model then plans), or null. */
    public var fullProblem: String? = null
        private set

    /** Null when every encoder declaration names [audioEncoder]; a missing declaration file is a mismatch too. */
    private fun encoderMismatch(): String? {
        val embedding = text(EMBEDDING_VERSION)?.lineSequence()?.firstOrNull()?.trim()
        if (embedding != audioEncoder) return "$EMBEDDING_VERSION says $embedding, the app runs $audioEncoder"
        val head = text(SEMANTIC_VERSION)?.lineSequence()?.firstOrNull { it.startsWith("encoder=") }?.removePrefix("encoder=")?.trim()
        if (head != audioEncoder) return "$SEMANTIC_VERSION fitted on $head, the app runs $audioEncoder"
        val manifest = runCatching { (MiniJson(text(MANIFEST)!!).parse() as Map<*, *>)["audioEncoder"] as? String }.getOrNull()
        if (manifest != audioEncoder) return "$MANIFEST declares audio encoder $manifest, the app runs $audioEncoder"
        return null
    }

    private fun text(name: String): String? = runCatching { read(name)?.let { b -> ByteArray(b.size) { b[it] }.decodeToString() } }.getOrNull()

    /** Null when the manifest lists every shipped ranker asset with its size and SHA-256 and they all match. */
    private fun verify(): String? = runCatching {
        val manifest = text(MANIFEST) ?: return@runCatching "$MANIFEST missing"
        @Suppress("UNCHECKED_CAST")
        val root = MiniJson(manifest).parse() as Map<String, Any?>
        if ((root["version"] as? Number)?.toInt() != 1) return@runCatching "$MANIFEST: unknown version"
        @Suppress("UNCHECKED_CAST")
        val assets = root["assets"] as? Map<String, Map<String, Any?>> ?: return@runCatching "$MANIFEST: no assets"
        for ((name, entry) in assets) {
            val bytes = read(name) ?: return@runCatching "$name missing"
            if ((entry["size"] as? Number)?.toLong() != bytes.size.toLong()) return@runCatching "$name size"
            val digest = Sha256.digest(bytes.size.toLong()) { bytes[it.toInt()] }.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            if (entry["sha256"] != digest) return@runCatching "$name checksum"
        }
        null
    }.getOrElse { "$MANIFEST unreadable: ${it::class.simpleName}" }

    public companion object {
        public const val MANIFEST: String = "smart_manifest.json"
        public const val MODEL: String = "smart_ranker.json"
        public const val FULL_MODEL: String = "smart_ranker_full.json"
        public const val COLISTEN: String = "artist_colisten.bin"
        public const val LFM: String = "artist_lfm.bin"
        public const val KNOWN: String = "artist_known.bin"
        public const val TAGGER_META: String = "audio_tagger.json"
        public const val TAGGER_WEIGHTS: String = "audio_tagger.bin"
        public const val POPULARITY: String = "artist_pop.bin"
        public const val FAMILIES: String = "artist_family.bin"
        public const val EMBEDDING_VERSION: String = "embedding_version.txt"
        public const val SEMANTIC_VERSION: String = "semantic_version.txt"
    }
}
